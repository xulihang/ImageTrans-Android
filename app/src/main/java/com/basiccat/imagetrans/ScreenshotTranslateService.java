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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
 * A draggable ball starts a screen capture after the user picks a region
 * (or taps once to capture the whole screen); the translation then appears
 * in a floating panel on top of other apps. Text in the panel is tappable
 * (same source/target dialog as TranslateActivity) and the "×" clears it.
 * The ball must be expanded to capture again, so it never covers the screen
 * capture; that also allows a second capture of the same content with
 * different settings.
 */
public class ScreenshotTranslateService extends Service {
    private static final String TAG = "ScreenshotTranslate";

    static final String ACTION_SHOW = "com.basiccat.imagetrans.FLOATING_SHOW";
    static final String ACTION_HIDE = "com.basiccat.imagetrans.FLOATING_HIDE";
    static final String ACTION_BALL = "com.basiccat.imagetrans.FLOATING_BALL";
    private static final String ACTION_START = "com.basiccat.imagetrans.FLOATING_START";

    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

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

    // Result panel
    private FrameLayout panel;
    private ZoomableImageView panelImage;
    private LinearLayout panelStatus;
    private TextView panelStatusText;
    private TextView clearButton;
    private WindowManager.LayoutParams panelParams;
    private boolean panelShown;
    private final List<Box> hitBoxes = new ArrayList<>();
    private Bitmap translatedBitmap;

    // Capture
    private MediaProjection mediaProjection = null;
    private int projectionResultCode;
    private Intent projectionData;
    private View selectionView;
    private final Runnable hideBallForCapture = () -> {
        if (ballShown) ball.setVisibility(View.GONE);
    };

    private int generation;

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
            if (!ballShown) showBall();
            try {
                startCapture();
            } catch (Throwable throwable) {
                Log.e(TAG, "capture failed", throwable);
                onCaptureError(throwable);
            }
            return START_NOT_STICKY;
        }
        enterForeground(getString(R.string.floating_preparing));
        if (ACTION_BALL.equals(action) || intent == null || ACTION_START.equals(action)) {
            // Restarted by the system (no capture token) or the user tapped
            // the notification: offer the ball again, it asks for capture.
            if (!ballShown) showBall();
            else if (!panelShown) startCapture();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    /**
     * Puts the service in the foreground. Android only hands out a screen
     * projection to a foreground service of type mediaProjection, so this
     * happens before getMediaProjection() is ever called.
     */
    private void enterForeground(String title) {
        Intent content = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, content,
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /** Starts the capture permission prompt if the token is missing/expired. */
    private boolean ensureProjectionToken() throws Exception {
        if (projectionData != null && mediaProjection == null) {
            ensureProjection();
            if (mediaProjection != null) return true;
        } else if (mediaProjection != null) {
            return true;
        }
        // No usable grant: ask again (e.g. after the user revoked it).
        projectionData = null;
        enterForeground(getString(R.string.floating_preparing));
        startProjectionPermission();
        return false;
    }

    private void onCaptureError(Throwable throwable) {
        Log.e(TAG, "capture error", throwable);
        ImageUtils.toast(this, "截屏失败：" + ImageUtils.humanError(throwable));
        projectionData = null;
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
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

    /** Foreground notification while the translation runs. */
    private void prepareTranslationNotification() {
        enterForeground(getString(R.string.floating_translating));
        mainHandler.removeCallbacks(autoHideNotification);
        mainHandler.postDelayed(autoHideNotification, NOTIFICATION_HIDE_DELAY_MS);
    }

    private final Runnable autoHideNotification = () -> {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 33) {
            manager.cancel(NOTIFICATION_ID);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    };

    private void shutdown() {
        removeWindows();
        mediaProjection = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        stopSelf();
    }

    private void removeWindows() {
        mainHandler.removeCallbacks(collapseBall);
        mainHandler.removeCallbacks(hideBallForCapture);
        mainHandler.removeCallbacks(autoHideNotification);
        removeSelectionView();
        if (panelShown) {
            windowManager.removeView(panel);
            panelShown = false;
        }
        if (ballShown) {
            windowManager.removeView(ball);
            ballShown = false;
        }
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    // --- floating ball ----------------------------------------------------

    private View buildBall(Context themed) {
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
            Log.i(TAG, "capture tapped");
            setBallExpanded(false);
            startCapture();
        });
        row.addView(captureItem, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));

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
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        return row;
    }

    private void showBall() {
        if (ballShown) return;
        Context themed = new ContextThemeWrapper(this, R.style.FloatingTheme);
        ball = (LinearLayout) buildBall(themed);
        ball.setOnTouchListener(new View.OnTouchListener() {
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
        windowManager.addView(ball, ballParams);
        clampBall();
        windowManager.updateViewLayout(ball, ballParams);
        ballShown = true;
        setBallExpanded(false);
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

    // --- capture ----------------------------------------------------------

    private void startCapture() {
        if (!Prefs.hasOverlayPermission(this)) {
            ImageUtils.toast(this, getString(R.string.floating_need_overlay));
            shutdown();
            return;
        }
        try {
            if (!ensureProjectionToken()) return;
        } catch (Throwable throwable) {
            Log.e(TAG, "projection setup failed", throwable);
            onCaptureError(throwable);
            return;
        }
        showSelection();
    }

    private void startProjectionPermission() {
        Log.i(TAG, "asking for capture permission");
        startActivity(new Intent(this, ProjectionPermissionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void ensureProjection() {
        if (mediaProjection != null) return;
        if (projectionData == null) return;
        // Must be a foreground service of type mediaProjection by now,
        // otherwise the system throws SecurityException.
        enterForeground(getString(R.string.floating_preparing));
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try {
            mediaProjection = manager.getMediaProjection(projectionResultCode, projectionData);
        } catch (RuntimeException e) {
            // The grant was used up or expired: ask for it again.
            Log.w(TAG, "getMediaProjection failed: " + e);
            projectionData = null;
            mediaProjection = null;
            return;
        }
        if (mediaProjection == null) return;
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "projection stopped");
                mediaProjection = null;
                projectionData = null;
                projectionResultCode = 0;
            }
        }, mainHandler);
    }

    private void showSelection() {
        removeSelectionView();
        if (ballShown) ball.setVisibility(View.GONE);
        final SelectionOverlay overlay = new SelectionOverlay(this, new SelectionOverlay.Callback() {
            @Override
            public void onSelected(Rect rect) {
                Log.i(TAG, "region selected " + rect);
                removeSelectionView();
                captureRegion(rect);
            }

            @Override
            public void onCancelled() {
                Log.i(TAG, "region selection cancelled");
                removeSelectionView();
                if (ballShown) ball.setVisibility(View.VISIBLE);
                if (!panelShown) {
                    // Nothing was open: stop the foreground notification later.
                    mainHandler.postDelayed(() -> {
                        if (!panelShown) shutdown();
                    }, 300);
                }
            }
        });
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(overlay, params);
        selectionView = overlay;
    }

    private void removeSelectionView() {
        if (selectionView != null) {
            windowManager.removeView(selectionView);
            selectionView = null;
        }
    }

    private void captureRegion(final Rect rect) {
        final int width = rect.width();
        final int height = rect.height();
        if (width <= 0 || height <= 0) {
            if (ballShown) ball.setVisibility(View.VISIBLE);
            return;
        }
        // The selection overlay is already gone; let the app redraw without
        // the dimmed screen before grabbing pixels.
        mainHandler.postDelayed(hideBallForCapture, 0);
        mainHandler.postDelayed(() -> doCapture(rect), 350);
    }

    private void doCapture(final Rect rect) {
        final int width = rect.width();
        final int height = rect.height();
        Log.i(TAG, "capturing " + rect);
        final ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        final VirtualDisplay[] display = {null};
        final Runnable release = () -> {
            if (display[0] != null) {
                display[0].release();
                display[0] = null;
            }
            reader.close();
        };
        final long startedAt = SystemClock.uptimeMillis();
        final boolean[] got = {false};
        reader.setOnImageAvailableListener(r -> {
            if (got[0]) return;
            got[0] = true;
            Bitmap bitmap = null;
            Image image = null;
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
                    int rowPadding = rowStride - pixelStride * width;
                    int paddedWidth = width + rowPadding / pixelStride;
                    bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);
                    if (paddedWidth != width) {
                        Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                        bitmap.recycle();
                        bitmap = cropped;
                    }
                    Log.i(TAG, "captured " + width + "x" + height
                            + " in " + (SystemClock.uptimeMillis() - startedAt) + "ms");
                } catch (Throwable throwable) {
                    Log.e(TAG, "reading capture failed", throwable);
                } finally {
                    image.close();
                }
            }
            reader.setOnImageAvailableListener(null, null);
            final Bitmap result = bitmap;
            mainHandler.post(() -> {
                release.run();
                onCaptured(result);
            });
        }, mainHandler);

        try {
            display[0] = mediaProjection.createVirtualDisplay("screenshot-translate",
                    width, height, getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, mainHandler);
        } catch (Throwable throwable) {
            Log.e(TAG, "createVirtualDisplay failed", throwable);
            release.run();
            onCaptured(null);
        }
    }

    private void onCaptured(final Bitmap bitmap) {
        if (bitmap == null) {
            ImageUtils.toast(this, getString(R.string.floating_capture_failed));
            if (ballShown) ball.setVisibility(View.VISIBLE);
            return;
        }
        showPanel();
        showStatus(R.string.floating_translating);
        final int current = ++generation;
        // The capture bitmap is needed as the renderer's background, so it is
        // released only once the render callback has run.
        executor.execute(() -> {
            WebHelper helper = null;
            try {
                Log.i(TAG, "translating " + bitmap.getWidth() + "x" + bitmap.getHeight());
                ImageUtils.Result result = ImageUtils.detectAndTranslate(this, bitmap);
                Log.i(TAG, "detected " + (result == null ? 0 : result.boxes.size()) + " boxes");
                if (result == null) {
                    mainHandler.post(() -> {
                        if (current == generation) showStatusText(getString(R.string.floating_no_text));
                    });
                    return;
                }
                if (current != generation) return;
                helper = new WebHelper();
                final WebHelper renderHelper = helper;
                final String imageDataUrl = ImageUtils.bitmapToDataUrl(bitmap);
                mainHandler.post(() -> {
                    if (current != generation) return;
                    try {
                        List<Box> renderable = new ArrayList<>();
                        for (Box box : result.boxes) {
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
                                        showTranslated(rendered, result.boxes);
                                    }

                                    @Override
                                    public void onError(String message) {
                                        if (current == generation) showStatusText("翻译失败：" + message);
                                    }
                                });
                    } catch (Throwable throwable) {
                        if (current == generation) {
                            showStatusText("翻译失败：" + ImageUtils.humanError(throwable));
                        }
                    }
                });
            } catch (Throwable throwable) {
                Log.e(TAG, "translation failed", throwable);
                mainHandler.post(() -> {
                    if (current != generation) return;
                    showStatusText("翻译失败：" + ImageUtils.humanError(throwable));
                });
            } finally {
                // Renderer must be destroyed on the main thread, and only
                // after the render it was started for has finished; give the
                // timeout a generous margin.
                if (helper != null) {
                    final WebHelper toDestroy = helper;
                    mainHandler.postDelayed(toDestroy::destroy, 5 * 60 * 1000);
                }
            }
        });
    }

    private void showTranslated(Bitmap bitmap, List<Box> boxes) {
        if (translatedBitmap != null && translatedBitmap != bitmap) translatedBitmap.recycle();
        translatedBitmap = bitmap;
        hitBoxes.clear();
        hitBoxes.addAll(boxes);
        panelImage.setImage(bitmap);
        panelStatus.setVisibility(View.GONE);
        clearButton.setVisibility(View.VISIBLE);
        Log.i(TAG, "translation shown, " + boxes.size() + " boxes");
    }

    private void showStatus(int stringRes) {
        showStatusText(getString(stringRes));
    }

    private void showStatusText(String text) {
        panelStatus.setVisibility(View.VISIBLE);
        panelStatusText.setText(text);
        clearButton.setVisibility(hitBoxes.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // --- result panel -----------------------------------------------------

    private void showPanel() {
        if (panelShown) return;
        Context themed = new ContextThemeWrapper(this, R.style.FloatingTheme);
        FrameLayout root = new FrameLayout(themed);
        root.setBackgroundResource(R.drawable.floating_panel_bg);

        panelImage = new ZoomableImageView(themed);
        panelImage.setTapListener(this::onPanelTap);
        root.addView(panelImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panelStatus = new LinearLayout(themed);
        panelStatus.setOrientation(LinearLayout.HORIZONTAL);
        panelStatus.setGravity(Gravity.CENTER);
        panelStatus.setBackgroundColor(0xE6FFFFFF);
        ProgressBar progress = new ProgressBar(themed);
        panelStatus.addView(progress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        panelStatusText = new TextView(themed);
        panelStatusText.setTextColor(getColor(R.color.text_primary));
        panelStatusText.setTextSize(14);
        panelStatusText.setText(R.string.floating_loading);
        panelStatus.addView(panelStatusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            setMargins(dp(8), 0, 0, 0);
        }});
        root.addView(panelStatus, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        clearButton = new TextView(themed);
        clearButton.setText("×");
        clearButton.setTextColor(getColor(R.color.white));
        clearButton.setTextSize(22);
        clearButton.setGravity(Gravity.CENTER);
        clearButton.setBackgroundResource(R.drawable.clear_button_bg);
        clearButton.setVisibility(View.GONE);
        clearButton.setOnClickListener(v -> clearResult());
        FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(dp(40), dp(40));
        clearParams.gravity = Gravity.TOP | Gravity.END;
        clearParams.setMargins(dp(20), dp(20), dp(8), dp(8));
        root.addView(clearButton, clearParams);

        panel = root;
        panelParams = new WindowManager.LayoutParams(
                Prefs.getFloatingPanelWidth(this),
                Prefs.getFloatingPanelHeight(this),
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = Prefs.getFloatingPanelX(this);
        panelParams.y = Prefs.getFloatingPanelY(this);
        clampPanel();
        windowManager.addView(panel, panelParams);
        panelShown = true;
        installPanelDrag(root);
        installPanelResize(root);
    }

    private void installPanelDrag(View root) {
        final int titleBar = dp(48);
        root.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int downX, downY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && event.getY() > titleBar) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downX = panelParams.x;
                        downY = panelParams.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        dragging = true;
                        panelParams.x = downX + (int) (event.getRawX() - downRawX);
                        panelParams.y = downY + (int) (event.getRawY() - downRawY);
                        clampPanel();
                        windowManager.updateViewLayout(panel, panelParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            Prefs.setFloatingPanelX(ScreenshotTranslateService.this, panelParams.x);
                            Prefs.setFloatingPanelY(ScreenshotTranslateService.this, panelParams.y);
                        }
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** Resize by dragging the bottom-end corner. */
    private void installPanelResize(View root) {
        Context themed = new ContextThemeWrapper(this, R.style.FloatingTheme);
        TextView grip = new TextView(themed);
        grip.setText("◢");
        grip.setTextColor(getColor(R.color.white));
        grip.setTextSize(14);
        grip.setGravity(Gravity.CENTER);
        grip.setBackgroundResource(R.drawable.clear_button_bg);
        ((FrameLayout) root).addView(grip, new FrameLayout.LayoutParams(
                dp(36), dp(36), Gravity.BOTTOM | Gravity.END));
        grip.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int downW, downH;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downW = panelParams.width;
                        downH = panelParams.height;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int maxW = getResources().getDisplayMetrics().widthPixels - panelParams.x;
                        int maxH = getResources().getDisplayMetrics().heightPixels - panelParams.y;
                        panelParams.width = Math.max(dp(120), Math.min(maxW,
                                downW + (int) (event.getRawX() - downRawX)));
                        panelParams.height = Math.max(dp(120), Math.min(maxH,
                                downH + (int) (event.getRawY() - downRawY)));
                        windowManager.updateViewLayout(panel, panelParams);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        Prefs.setFloatingPanelWidth(ScreenshotTranslateService.this, panelParams.width);
                        Prefs.setFloatingPanelHeight(ScreenshotTranslateService.this, panelParams.height);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void clampPanel() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        panelParams.width = Math.min(panelParams.width, metrics.widthPixels);
        panelParams.height = Math.min(panelParams.height, metrics.heightPixels);
        panelParams.x = Math.max(0, Math.min(metrics.widthPixels - panelParams.width, panelParams.x));
        panelParams.y = Math.max(0, Math.min(metrics.heightPixels - panelParams.height, panelParams.y));
    }

    private void clearResult() {
        Log.i(TAG, "clear tapped");
        generation++;
        hitBoxes.clear();
        panelImage.setImage(null);
        if (translatedBitmap != null) {
            translatedBitmap.recycle();
            translatedBitmap = null;
        }
        panelStatus.setVisibility(View.GONE);
        clearButton.setVisibility(View.GONE);
        windowManager.removeView(panel);
        panelShown = false;
        if (ballShown) ball.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(autoHideNotification);
        stopForeground(STOP_FOREGROUND_REMOVE);
        // The ball now runs from its own notification.
        Intent intent = new Intent(this, ScreenshotTranslateService.class);
        PendingIntent show = PendingIntent.getService(this, 0,
                new Intent(this, ScreenshotTranslateService.class).setAction(ACTION_BALL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL_BALL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.floating_running))
                .setContentText(getString(R.string.floating_running_hint))
                .setContentIntent(show)
                .setOngoing(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
    }

    private void onPanelTap(float x, float y) {
        if (hitBoxes.isEmpty() || panelImage == null) return;
        float[] image = panelImage.viewToImage(x, y);
        float scale = Math.max(panelImage.getImageScale(), 0.01f);
        float minSize = dp(44) / scale;
        int best = -1;
        double bestArea = Double.MAX_VALUE;
        for (int i = 0; i < hitBoxes.size(); i++) {
            Box box = hitBoxes.get(i);
            if (!box.hasText()) continue;
            float growX = Math.max(0, ((float) minSize - (float) box.width) / 2f);
            float growY = Math.max(0, ((float) minSize - (float) box.height) / 2f);
            if (image[0] < box.left() - growX || image[0] > box.right() + growX
                    || image[1] < box.top() - growY || image[1] > box.bottom() + growY) {
                continue;
            }
            double area = box.width * box.height;
            if (area < bestArea) {
                bestArea = area;
                best = i;
            }
        }
        Log.i(TAG, "panel tap " + image[0] + "," + image[1] + " -> box " + best);
        if (best >= 0) showTextBoxDialog(best);
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
                    WebView webView = new WebView(ScreenshotTranslateService.this);
                    renderer = new Renderer(webView);
                    holder[0] = renderer;
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
