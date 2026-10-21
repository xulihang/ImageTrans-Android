package com.basiccat.imagetrans;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Screenshot translation as floating windows.
 *
 * Two things stay on screen together: a draggable ball and a selection frame
 * ("选框"). Tapping "翻译" on the ball captures the frame's region; the
 * translated image is shown inside the same frame (tapping its text opens the
 * source/target dialog). The frame's "×" clears the result and leaves the
 * frame ready for the next capture. The ball is hidden while capturing so it
 * does not end up in the screenshot.
 */
public class ScreenshotTranslateService extends Service {
    private static final String TAG = "ScreenshotTranslate";

    static final String ACTION_SHOW = "com.basiccat.imagetrans.FLOATING_SHOW";
    static final String ACTION_HIDE = "com.basiccat.imagetrans.FLOATING_HIDE";
    static final String ACTION_BALL = "com.basiccat.imagetrans.FLOATING_BALL";

    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";
    static final String EXTRA_ENABLE_ONLY = "enable_only";

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "screenshot_translate";
    private static final String CHANNEL_BALL_ID = "screenshot_translate_ball";
    private static final long NOTIFICATION_HIDE_DELAY_MS = 8000;

    public static void show(Context context, int resultCode, Intent resultData) {
        Intent intent = new Intent(context, ScreenshotTranslateService.class)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData);
        context.startForegroundService(intent);
    }

    /** Starts the floating ball in the background (frame shown on capture). */
    public static void enable(Context context, int resultCode, Intent resultData) {
        Intent intent = new Intent(context, ScreenshotTranslateService.class)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_ENABLE_ONLY, true);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, ScreenshotTranslateService.class)
                .setAction(ACTION_HIDE));
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WindowManager windowManager;

    // Floating ball
    private LinearLayout ball;
    private WindowManager.LayoutParams ballParams;
    private boolean ballShown;
    private boolean ballExpanded;
    private final Runnable collapseBall = () -> setBallExpanded(false);

    // Selection frame, which also shows the result
    private SelectionOverlay overlay;
    private WindowManager.LayoutParams overlayParams;
    private boolean overlayShown;
    /** A capture was requested while the permission prompt was open. */
    private boolean pendingCapture;
    private final List<Box> hitBoxes = new ArrayList<>();

    // Capture
    private MediaProjection mediaProjection = null;
    private int projectionResultCode;
    private Intent projectionData;
    private int generation;

    private final Runnable autoHideNotification = () -> {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    };

    // --- lifecycle --------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "onStartCommand " + action);
        if (ACTION_HIDE.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW.equals(action)) {
            projectionResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            projectionData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (intent.getBooleanExtra(EXTRA_ENABLE_ONLY, false)) {
                // Enabled from the main screen: show the ball only; the frame
                // appears with the first capture.
                Prefs.setFloatingEnabled(this, true);
                enterForeground(getString(R.string.floating_running));
                if (!ballShown) showBall();
                return START_NOT_STICKY;
            }
            showWindows();
            if (pendingCapture) {
                pendingCapture = false;
                capture();
            }
            return START_NOT_STICKY;
        }
        if (!Intent.ACTION_MAIN.equals(action) && !ACTION_BALL.equals(action)) {
            // Not a system restart nor the notification: a stale start.
            shutdown();
            return START_NOT_STICKY;
        }
        // Restarted by the system (no capture token): the system refuses a
        // projection for a service started in the background, so show only
        // the ball. The frame appears at the first capture, which gets the
        // permission (the ball must be tapped first: that is visible UI).
        enterForeground(getString(R.string.floating_running));
        if (!Prefs.hasOverlayPermission(this)) {
            shutdown();
            return START_NOT_STICKY;
        }
        projectionResultCode = 0;
        projectionData = null;
        mediaProjection = null;
        if (!ballShown) showBall();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        removeWindows();
        executor.shutdownNow();
        if (mediaProjection != null) mediaProjection.stop();
        super.onDestroy();
    }

    private void createChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.floating_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        NotificationChannel ballChannel = new NotificationChannel(CHANNEL_BALL_ID,
                getString(R.string.floating_ball_channel_name), NotificationManager.IMPORTANCE_MIN);
        ballChannel.setShowBadge(false);
        manager.createNotificationChannel(ballChannel);
    }

    /**
     * Puts the service in the foreground. Android only hands out a screen
     * projection to a foreground service of type mediaProjection, so this
     * happens before getMediaProjection() is ever called.
     */
    private void enterForeground(String title) {
        Log.i(TAG, "enterForeground: " + title);
        Intent content = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, content,
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable throwable) {
            // Without the projection type the system refuses the projection;
            // say so instead of failing later with a bare NPE.
            Log.e(TAG, "startForeground failed", throwable);
            ImageUtils.toast(this, getString(R.string.floating_need_notifications));
            shutdown();
        }
    }

    private void shutdown() {
        mainHandler.removeCallbacksAndMessages(null);
        removeWindows();
        mediaProjection = null;
        projectionData = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        stopSelf();
    }

    private void removeWindows() {
        if (overlayShown) {
            windowManager.removeView(overlay);
            overlay = null;
            overlayShown = false;
        }
        if (ballShown) {
            windowManager.removeView(ball);
            ball = null;
            ballShown = false;
        }
    }

    private void showWindows() {
        enterForeground(getString(R.string.floating_running));
        if (!ballShown) showBall();
        if (!overlayShown) showOverlay();
        overlay.setVisibility(View.VISIBLE);
        // The ball is only needed as a launcher; shrink its notification.
        NotificationManager manager = getSystemService(NotificationManager.class);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_BALL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.floating_running))
                .setContentText(getString(R.string.floating_running_hint))
                .setOngoing(true);
        manager.notify(NOTIFICATION_ID, builder.build());
        mainHandler.removeCallbacks(autoHideNotification);
        mainHandler.postDelayed(autoHideNotification, NOTIFICATION_HIDE_DELAY_MS);
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    // --- floating ball ----------------------------------------------------

    private void showBall() {
        Context themed = new ContextThemeWrapper(this, R.style.FloatingTheme);
        LinearLayout row = new LinearLayout(themed);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.floating_ball_bg);

        TextView icon = new TextView(themed);
        icon.setText(R.string.floating_ball_label);
        icon.setTextColor(getColor(R.color.white));
        icon.setTextSize(18);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView captureItem = new TextView(themed);
        captureItem.setText(R.string.floating_capture);
        captureItem.setTextColor(getColor(R.color.text_primary));
        captureItem.setTextSize(14);
        captureItem.setGravity(Gravity.CENTER);
        captureItem.setPadding(dp(12), 0, dp(12), 0);
        captureItem.setOnClickListener(v -> {
            Log.i(TAG, "translate tapped");
            setBallExpanded(false);
            capture();
        });
        row.addView(captureItem, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));

        TextView hideItem = new TextView(themed);
        hideItem.setText(R.string.action_close);
        hideItem.setTextColor(getColor(R.color.text_secondary));
        hideItem.setTextSize(14);
        hideItem.setGravity(Gravity.CENTER);
        hideItem.setPadding(dp(12), 0, dp(14), 0);
        hideItem.setOnClickListener(v -> {
            Log.i(TAG, "hide tapped");
            shutdown();
        });
        row.addView(hideItem, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));

        row.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int downLeft, downTop;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downLeft = ballParams.x;
                        downTop = ballParams.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > ViewConfiguration.get(
                                ScreenshotTranslateService.this).getScaledTouchSlop()) {
                            dragging = true;
                        }
                        if (dragging) {
                            ballParams.x = downLeft + (int) dx;
                            ballParams.y = downTop + (int) dy;
                            clampBall();
                            windowManager.updateViewLayout(ball, ballParams);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            Prefs.setFloatingBallX(ScreenshotTranslateService.this, ballParams.x);
                            Prefs.setFloatingBallY(ScreenshotTranslateService.this, ballParams.y);
                        } else {
                            setBallExpanded(!ballExpanded);
                        }
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });

        ballParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;
        ballParams.x = Prefs.getFloatingBallX(this);
        ballParams.y = Prefs.getFloatingBallY(this);
        ball = row;
        windowManager.addView(ball, ballParams);
        ballShown = true;
        setBallExpanded(false);
        ball.post(this::clampBallAtStart);
    }

    /** Keeps a saved position valid after a screen size change. */
    private void clampBallAtStart() {
        if (!ballShown || ball == null) return;
        int x = ballParams.x;
        int y = ballParams.y;
        clampBall();
        if (x != ballParams.x || y != ballParams.y) {
            windowManager.updateViewLayout(ball, ballParams);
            Prefs.setFloatingBallX(this, ballParams.x);
            Prefs.setFloatingBallY(this, ballParams.y);
        }
    }

    private void setBallExpanded(boolean expanded) {
        mainHandler.removeCallbacks(collapseBall);
        if (!ballShown) return;
        ballExpanded = expanded;
        for (int i = 1; i < ball.getChildCount(); i++) {
            ball.getChildAt(i).setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (expanded) mainHandler.postDelayed(collapseBall, 5000);
    }

    private void clampBall() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - ball.getWidth());
        int maxY = Math.max(0, metrics.heightPixels - ball.getHeight());
        ballParams.x = Math.max(0, Math.min(maxX, ballParams.x));
        ballParams.y = Math.max(0, Math.min(maxY, ballParams.y));
    }

    // --- selection frame --------------------------------------------------

    private void showOverlay() {
        overlay = new SelectionOverlay(this, new SelectionOverlay.Callback() {
            @Override
            public void onSelected(Rect rect) {
                Log.i(TAG, "frame region " + rect);
                capture(rect);
            }

            @Override
            public void onCancelled() {
                Log.i(TAG, "overlay cancelled");
                shutdown();
            }
        });
        overlay.setTapListener(this::onOverlayTap);
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.setTitle("SelectionOverlay");
        windowManager.addView(overlay, overlayParams);
        overlayShown = true;
    }

    // --- capture ----------------------------------------------------------

    private void capture() {
        if (!Prefs.hasOverlayPermission(this)) {
            ImageUtils.toast(this, getString(R.string.floating_need_overlay));
            shutdown();
            return;
        }
        if (overlayShown) {
            overlay.setVisibility(View.VISIBLE);
        } else {
            // Opened from the notification before a frame existed.
            showOverlay();
        }
        enterForeground(getString(R.string.floating_translating));
        mainHandler.removeCallbacks(autoHideNotification);
        try {
            if (!ensureProjection()) {
                // Permission prompt: the result comes back through ACTION_SHOW.
                projectionData = null;
                mainHandler.removeCallbacks(autoHideNotification);
                return;
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "projection setup failed", throwable);
            onCaptureError(throwable);
            return;
        }
        capture(overlay.getFrameRect());
    }

    /**
     * Makes sure a live projection exists. The session is kept for as long
     * as the floating windows run, so only the first capture needs the system
     * permission prompt; it is recreated only if the user or system ended it.
     */
    private boolean ensureProjection() {
        if (mediaProjection != null) return true;
        if (projectionData == null) {
            startProjectionPermission();
            return false;
        }
        enterForeground(getString(R.string.floating_translating));
        mediaProjection = restartMediaProjection();
        if (mediaProjection == null) {
            projectionData = null;
            startProjectionPermission();
            return false;
        }
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "projection stopped by system");
                mediaProjection = null;
                projectionData = null;
                projectionResultCode = 0;
            }
        }, mainHandler);
        return true;
    }

    private MediaProjection restartMediaProjection() {
        if (projectionData == null) return null;
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        MediaProjection created = null;
        for (int attempt = 0; attempt < 2 && created == null; attempt++) {
            try {
                created = manager.getMediaProjection(projectionResultCode, projectionData);
            } catch (RuntimeException e) {
                Log.w(TAG, "getMediaProjection failed (attempt " + attempt + "): " + e);
                // Projection needs a mediaProjection-type foreground service.
                enterForeground(getString(R.string.floating_translating));
            }
        }
        Log.i(TAG, "mediaProjection " + (created == null ? "unavailable" : "created"));
        return created;
    }

    private void startProjectionPermission() {
        pendingCapture = true;
        Log.i(TAG, "asking for capture permission");
        startActivity(new Intent(this, ProjectionPermissionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Captures the given screen region, then translates it in the frame. */
    private void capture(Rect region) {
        Rect rect = new Rect(region);
        if (rect.width() <= 0 || rect.height() <= 0) return;
        if (mediaProjection == null) {
            // A capture called directly by the overlay also needs a projection.
            try {
                if (!ensureProjection()) return;
            } catch (Throwable throwable) {
                onCaptureError(throwable);
                return;
            }
        }
        Log.i(TAG, "capturing " + rect + " mode=projection");
        captureProjection(rect);
    }

    /**
     * Mirrors the whole screen into an ImageReader (the only size a
     * projection reliably accepts) and cuts out the requested region in
     * memory; the full-screen bitmap is recycled immediately.
     */
    private void captureProjection(Rect rect) {
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        final int screenWidth = metrics.widthPixels;
        final int screenHeight = metrics.heightPixels;
        // The overlay is hidden (not removed) rather than going invisible:
        // some systems stop the projection when the app's UI leaves the
        // screen, and the overlay still counts as visible UI.
        overlay.setVisibility(View.INVISIBLE);
        final Rect screen = new Rect(0, 0, screenWidth, screenHeight);
        final Rect crop = new Rect(rect);
        crop.intersect(screen);
        if (crop.isEmpty()) {
            imageOnCaptured(null, null);
            return;
        }
        final ImageReader reader = ImageReader.newInstance(screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2);
        final int screenHeightFinal = screenHeight;
        final int screenWidthFinal = screenWidth;
        final VirtualDisplay[] display = {null};
        final Runnable release = () -> {
            if (display[0] != null) {
                display[0].release();
                display[0] = null;
            }
            reader.close();
        };
        final long startedAt = SystemClock.uptimeMillis();
        // Hide the ball first, then give the screen a moment to redraw
        // without it before grabbing pixels.
        mainHandler.postDelayed(() -> {
            if (mediaProjection == null) {
                release.run();
                imageOnCaptured(null, "截屏授权已失效，请再点一次翻译");
                return;
            }
            try {
                display[0] = mediaProjection.createVirtualDisplay("screenshot-translate",
                        screenWidthFinal, screenHeightFinal, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, mainHandler);
            } catch (Throwable throwable) {
                Log.e(TAG, "createVirtualDisplay failed", throwable);
                release.run();
                imageOnCaptured(null, ImageUtils.humanError(throwable));
            }
        }, 300);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                // The listener runs on the service's main handler; do the
                // (slow) decoding off it so the UI stays responsive.
                r.setOnImageAvailableListener(null, null);
                executor.execute(() -> decodeScreen(r, crop, screenWidthFinal, screenHeightFinal,
                        startedAt, release));
            }
        }, mainHandler);
    }

    private void decodeScreen(ImageReader r, Rect crop, int screenWidthFinal,
                              int screenHeightFinal, long startedAt, Runnable release) {
        Image image = null;
        String error = null;
        Bitmap bitmap = null;
        try {
            image = r.acquireLatestImage();
        } catch (Exception e) {
            Log.e(TAG, "acquireLatestImage failed: " + e);
        }
        if (image != null) {
            try {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                int pixelStride = image.getPlanes()[0].getPixelStride();
                int rowStride = image.getPlanes()[0].getRowStride();
                int rowPadding = rowStride - pixelStride * screenWidthFinal;
                int paddedWidth = screenWidthFinal + rowPadding / pixelStride;
                Bitmap full = Bitmap.createBitmap(paddedWidth, screenHeightFinal,
                        Bitmap.Config.ARGB_8888);
                full.copyPixelsFromBuffer(buffer);
                Bitmap result = full;
                if (paddedWidth != screenWidthFinal || !crop.isEmpty()) {
                    result = Bitmap.createBitmap(full, crop.left, crop.top,
                            crop.width(), crop.height());
                    if (result != full) full.recycle();
                }
                bitmap = result;
                Log.i(TAG, "captured " + result.getWidth() + "x" + result.getHeight()
                        + " in " + (SystemClock.uptimeMillis() - startedAt) + "ms");
            } catch (Throwable throwable) {
                Log.e(TAG, "reading capture failed", throwable);
                error = ImageUtils.humanError(throwable);
            } finally {
                image.close();
            }
        } else {
            error = "没有收到图像";
        }
        final Bitmap result = bitmap;
        final String message = error;
        mainHandler.post(() -> {
            release.run();
            imageOnCaptured(result, message);
        });
    }

    private void imageOnCaptured(Bitmap bitmap, String error) {
        overlay.setVisibility(View.VISIBLE);
        if (ballShown) ball.setVisibility(View.VISIBLE);
        if (bitmap == null) {
            overlay.showTranslation(null, error != null
                    ? getString(R.string.floating_capture_failed) + "：" + error
                    : getString(R.string.floating_capture_failed));
            return;
        }
        onCaptured(bitmap);
    }

    private void onCaptured(Bitmap bitmap) {
        if (ballShown) ball.setVisibility(View.VISIBLE);
        overlay.showStatus(getString(R.string.floating_translating));
        if (bitmap == null) {
            overlay.showTranslation(null, getString(R.string.floating_capture_failed));
            return;
        }
        final int current = ++generation;
        // The capture bitmap is the renderer's background, so it is released
        // only after the render callback has run.
        executor.execute(() -> {
            WebHelper helper = null;
            try {
                Log.i(TAG, "translating " + bitmap.getWidth() + "x" + bitmap.getHeight());
                ImageUtils.Result result = ImageUtils.detectAndTranslate(this, bitmap);
                Log.i(TAG, "detected " + (result == null ? 0 : result.boxes.size()) + " boxes");
                if (result == null) {
                    mainHandler.post(() -> {
                        if (current == generation) {
                            overlay.showTranslation(null, getString(R.string.floating_no_text));
                        }
                    });
                    return;
                }
                if (current != generation) return;
                helper = new WebHelper();
                final WebHelper renderHelper = helper;
                final List<Box> boxes = result.boxes;
                final String imageDataUrl = ImageUtils.bitmapToDataUrl(bitmap);
                mainHandler.post(() -> {
                    if (current != generation) return;
                    try {
                        List<Box> renderable = new ArrayList<>();
                        for (Box box : boxes) {
                            if (box.target != null && !box.target.trim().isEmpty()) renderable.add(box);
                        }
                        renderHelper.renderer.render(renderable, imageDataUrl,
                                ImageUtils.buildRenderOptions(ScreenshotTranslateService.this),
                                new Renderer.Callback() {
                                    @Override
                                    public void onSuccess(Bitmap rendered, String url) {
                                        if (current != generation) {
                                            rendered.recycle();
                                            return;
                                        }
                                        showTranslated(rendered, boxes);
                                    }

                                    @Override
                                    public void onError(String message) {
                                        if (current == generation) {
                                            overlay.showTranslation(null, getString(
                                                    R.string.floating_translate_failed, message));
                                        }
                                    }
                                });
                    } catch (Throwable throwable) {
                        if (current == generation) {
                            overlay.showTranslation(null, getString(
                                    R.string.floating_translate_failed,
                                    ImageUtils.humanError(throwable)));
                        }
                    }
                });
            } catch (Throwable throwable) {
                Log.e(TAG, "translation failed", throwable);
                mainHandler.post(() -> {
                    if (current != generation) return;
                    overlay.showTranslation(null, getString(R.string.floating_translate_failed,
                            ImageUtils.humanError(throwable)));
                });
            } finally {
                // Renderer must be destroyed on the main thread, after the
                // render it was started for has finished (timeout + margin).
                if (helper != null) {
                    final WebHelper toDestroy = helper;
                    mainHandler.postDelayed(toDestroy::destroy, 3 * 60 * 1000);
                }
            }
        });
    }

    private void onCaptureError(Throwable throwable) {
        Log.e(TAG, "capture error", throwable);
        if (overlayShown) {
            overlay.showTranslation(null, getString(R.string.floating_capture_failed));
        }
        ImageUtils.toast(this, "截屏失败：" + ImageUtils.humanError(throwable));
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        // The grant is single-use; the next capture asks for a new one.
        projectionData = null;
    }

    private void showTranslated(Bitmap bitmap, List<Box> boxes) {
        hitBoxes.clear();
        hitBoxes.addAll(boxes);
        overlay.showTranslation(bitmap, null);
        Log.i(TAG, "translation shown, " + boxes.size() + " boxes");
    }

    /** A tap on the frame's content. Returns true when it opened a dialog. */
    private boolean onOverlayTap(float x, float y) {
        if (hitBoxes.isEmpty() || overlay == null) return false;
        float scale = 1f;
        float minSize = dp(44) / Math.max(scale, 0.01f);
        int best = -1;
        double bestArea = Double.MAX_VALUE;
        for (int i = 0; i < hitBoxes.size(); i++) {
            Box box = hitBoxes.get(i);
            if (!box.hasText()) continue;
            float growX = Math.max(0, ((float) minSize - (float) box.width) / 2f);
            float growY = Math.max(0, ((float) minSize - (float) box.height) / 2f);
            if (x < box.left() - growX || x > box.right() + growX
                    || y < box.top() - growY || y > box.bottom() + growY) {
                continue;
            }
            double area = box.width * box.height;
            if (area < bestArea) {
                bestArea = area;
                best = i;
            }
        }
        Log.i(TAG, "tap " + x + "," + y + " -> box " + best);
        if (best < 0) return false;
        showTextBoxDialog(best);
        return true;
    }

    private void showTextBoxDialog(int index) {
        Context themed = new ContextThemeWrapper(this, R.style.FloatingTheme);
        View content = LayoutInflater.from(themed).inflate(R.layout.dialog_text_box, null);
        TextView sourceText = content.findViewById(R.id.sourceText);
        TextView targetText = content.findViewById(R.id.targetText);
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(themed)
                .setTitle(getString(R.string.text_box_title, index + 1, hitBoxes.size()))
                .setView(content)
                .setPositiveButton(R.string.action_close, null);
        if (hitBoxes.size() > 1) {
            builder.setNegativeButton(R.string.action_previous,
                    (dialog, which) -> showTextBoxDialog((index - 1 + hitBoxes.size()) % hitBoxes.size()));
            builder.setNeutralButton(R.string.action_next,
                    (dialog, which) -> showTextBoxDialog((index + 1) % hitBoxes.size()));
        }
        Box box = hitBoxes.get(index);
        sourceText.setText(box.source == null || box.source.trim().isEmpty()
                ? getString(R.string.text_box_empty) : box.source);
        targetText.setText(box.target == null || box.target.trim().isEmpty()
                ? getString(R.string.text_box_empty) : box.target);
        builder.show();
    }

    /** A hidden WebView renderer on the main thread, destroyed afterwards. */
    private final class WebHelper {
        final Renderer renderer;

        WebHelper() throws Exception {
            final Renderer[] holder = new Renderer[1];
            final Throwable[] error = new Throwable[1];
            final CountDownLatch latch = new CountDownLatch(1);
            mainHandler.post(() -> {
                try {
                    holder[0] = new Renderer(new WebView(ScreenshotTranslateService.this));
                } catch (Throwable throwable) {
                    error[0] = throwable;
                } finally {
                    latch.countDown();
                }
            });
            if (!latch.await(10, TimeUnit.SECONDS)) throw new Exception("渲染器初始化超时");
            if (error[0] != null) throw new Exception(ImageUtils.humanError(error[0]));
            if (holder[0] == null) throw new Exception("无法创建渲染器");
            this.renderer = holder[0];
        }

        void destroy() {
            renderer.destroy();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
