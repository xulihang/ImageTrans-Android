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
import android.text.Selection;
import android.text.Spannable;
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
 * A selection frame ("选框") stays on screen; a tap in it captures its
 * region and the translated image is shown inside the same frame
 * (tapping its text opens the source/target dialog). The frame's "×" clears the result and leaves the
 * frame ready for the next capture. The frame is hidden while capturing so
 * it does not end up in the screenshot.
 */
public class ScreenshotTranslateService extends Service {
    private static final String TAG = "ScreenshotTranslate";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    static final String ACTION_SHOW = "com.basiccat.imagetrans.FLOATING_SHOW";
    static final String ACTION_HIDE = "com.basiccat.imagetrans.FLOATING_HIDE";
    static final String ACTION_BALL = "com.basiccat.imagetrans.FLOATING_BALL";
    static final String ACTION_START_SERVER = "com.basiccat.imagetrans.START_SERVER";

    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "screenshot_translate";
    private static final String CHANNEL_BALL_ID = "screenshot_translate_ball";
    private static final String CHANNEL_SERVER_ID = "image_trans_server";
    private static final long NOTIFICATION_HIDE_DELAY_MS = 8000;
    /** Delay between screenshot attempts while the screen is settling. */
    private static final long ATTEMPT_DELAY_MS = 150;
    /** Attempts before giving up (a few seconds at most). */
    private static final int MAX_CAPTURE_ATTEMPTS = 20;
    /** A projection grant may be reused while the user is in this app. */
    private static final long PROJECTION_GRANT_LIFETIME_MS = 5 * 60 * 1000;

    /**
     * Whether captures go through the accessibility service: Android 12+ has
     * the screenshot permission on its accessibility settings. Android 11
     * could take a screenshot through it too, but some of its phones refuse
     * an enabled service; there MediaProjection is used, as below 11.
     */
    private static boolean useAccessibility() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** Starts the floating windows with a projection grant (Android 11). */
    public static void show(Context context, int resultCode, Intent resultData) {
        context.startForegroundService(new Intent(context, ScreenshotTranslateService.class)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData));
    }

    /** Starts the frame in the background (shown at once, ready to capture). */
    public static void enable(Context context) {
        context.startForegroundService(new Intent(context, ScreenshotTranslateService.class)
                .setAction(ACTION_SHOW));
    }

    /**
     * Runs the image translation server for the browser extension in the
     * background, with an ongoing notification showing its address. No
     * floating windows are shown.
     */
    public static void startServer(Context context) {
        context.startForegroundService(new Intent(context, ScreenshotTranslateService.class)
                .setAction(ACTION_START_SERVER));
    }

    /** Stops only the server; the floating windows (if any) stay. */
    public static void stopServer(Context context) {
        hide(context, true);
    }

    public static void stop(Context context) {
        hide(context, false);
    }

    /**
     * Sends the "close" command, but only to a running service. Starting a
     * stopped one would have to post a foreground notification at once, which
     * closing never does, and seconds later the system kills the app with
     * ForegroundServiceDidNotStartInTime. A stopped service has nothing to
     * stop anyway.
     */
    private static void hide(Context context, boolean serverOnly) {
        if (!isRunning()) return;
        Intent intent = new Intent(context, ScreenshotTranslateService.class)
                .setAction(ACTION_HIDE);
        if (serverOnly) intent.putExtra(EXTRA_SERVER_ONLY, true);
        context.startService(intent);
    }

    /**
     * Whether this service is live. It is only ever started from the app's
     * own process (the main screen, the capture consent activity or a
     * notification), which is where this static lives as well.
     */
    private static boolean isRunning() {
        // Set in onCreate(), cleared when the instance really goes away; a
        // "restart pending" onDestroy keeps the service alive.
        return running;
    }

    private static volatile boolean running;

    static final String EXTRA_SERVER_ONLY = "server_only";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WindowManager windowManager;

    // Floating ball
    private LinearLayout ball;
    /** The ball's menu entry for the frame (see setFrameHidden). */
    private TextView frameItem;
    /** The ball's menu entry that closes a result (see closeResult). */
    private TextView resultItem;
    /** The frame is off the screen; its place is remembered instead. */
    private boolean frameHidden;
    /** The window that shows a result while the frame is hidden. */
    private SelectionOverlay resultView;
    /** The area a hidden translation is taking, in screen pixels; null without. */
    private Rect captureRegion;
    private WindowManager.LayoutParams ballParams;
    private boolean ballShown;
    private boolean ballExpanded;

    // Selection frame, which also shows the result
    private SelectionOverlay overlay;
    private WindowManager.LayoutParams overlayParams;
    private final List<Box> hitBoxes = new ArrayList<>();
    /** The source/target overlay, if open. */
    private View textBoxDialog;
    /** The box the open dialog shows (see showTextBoxDialog). */
    private int textBoxIndex;
    private LinearLayout selectionBar;
    private TextView sourceText;
    private TextView textBoxTitle;
    private TextView targetText;
    private boolean targetPressed;

    // Capture
    /**
     * Whether this session captures through MediaProjection. Android 12+ asks
     * only when the accessibility service is not connected (a projector's
     * whole screen mirror is too slow for a phone to care about otherwise);
     * the one consent of main screen's start then carries the session.
     */
    private boolean usesProjection() {
        return !useAccessibility() || !ScreenshotAccessibilityService.isEnabled();
    }

    private MediaProjection mediaProjection;
    private int projectionResultCode;
    private Intent projectionData;
    private long projectionGrantedAt;
    /** A capture asked for a grant while the consent prompt was open. */
    private boolean pendingCapture;
    private int generation;

    private final Runnable autoHideNotification = () -> {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (Prefs.isServerEnabled(ScreenshotTranslateService.this)
                && !ScreenshotTranslateService.this.isServerOnly()) {
            // The floating windows keep their own notification; add the
            // server address to it.
            showServerNotification();
        }
    };

    /** Started only to run the server (no floating windows). */
    private boolean serverOnly;
    private android.os.PowerManager.WakeLock wakeLock;

    private boolean isServerOnly() {
        return serverOnly;
    }

    // --- lifecycle --------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // Remembered across starts: a service started again still shows
        // what was on screen last time.
        frameHidden = Prefs.isFrameHidden(this);
        createChannels();
        if (Prefs.isServerEnabled(this)) {
            // Service recreated: the server in this process may be a stale
            // one (its workers belong to the old instance); replace it. The
            // foreground notification is set up by the start command.
            stopApiServer();
            startApiServer();
            serverOnly = true;
            acquireWakeLock();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "onStartCommand " + action);
        if (intent == null) {
            // START_STICKY restart after the system killed the process:
            // restore the server and its notification instead of treating
            // the missing action as a stale start.
            if (Prefs.isServerEnabled(this)) {
                serverOnly = true;
                enterServerForeground();
                return START_STICKY;
            }
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE.equals(action)) {
            if (intent.getBooleanExtra(EXTRA_SERVER_ONLY, false)) {
                // "Stop server": the server goes; floating windows (if any)
                // stay. Flip the flag here as well, so a quick app launch
                // cannot see the old value and start the service again.
                Prefs.setServerEnabled(this, false);
                stopApiServer();
                if (windowsUp()) {
                    showWindows();
                } else {
                    // Nothing else is running: stop the whole service.
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
                return START_NOT_STICKY;
            }
            if (Prefs.isServerEnabled(this)) {
                // Closing the windows: the server keeps running.
                Prefs.setFloatingEnabled(this, false);
                removeWindows();
                serverOnly = true;
                // A new start command: restart the sticky state again.
                startForeground(NOTIFICATION_ID, buildServerNotification());
                enterServerForeground();
                return START_STICKY;
            }
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_START_SERVER.equals(action)) {
            Prefs.setServerEnabled(this, true);
            if (!startApiServer()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!ballShown && overlay == null) {
                // Server only: an ongoing foreground notification.
                serverOnly = true;
                enterServerForeground();
            } else {
                // Alongside the floating windows: their notification stays.
                showServerNotification();
            }
            return START_STICKY;
        }
        if (ACTION_SHOW.equals(action)) {
            // From ProjectionPermissionActivity (Android 11), the main screen
            // or the consent itself (both with a grant, or not on API 31+).
            if (intent.hasExtra(EXTRA_RESULT_DATA)) {
                projectionResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                projectionData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                projectionGrantedAt = SystemClock.elapsedRealtime();
            }
            Prefs.setFloatingEnabled(this, true);
            if (!pendingCapture) {
                // On screen from the start (with the consent there is only
                // the capture to come).
                showWindows();
            }
            if (pendingCapture) {
                pendingCapture = false;
                if (frameHidden) {
                    // Alone: no frame to show before the capture.
                    enterForeground(getString(R.string.floating_translating));
                    startRememberedCapture();
                } else {
                    showWindows();
                    capture();
                }
            } else if (projectionData != null) {
                // The main screen's consent: this session goes through
                // MediaProjection whatever the accessibility service does, so
                // the projection is made now, from the start.
                enterForeground(getString(R.string.floating_translating));
                ensureProjection();
            }
            return START_NOT_STICKY;
        }
        if (!Intent.ACTION_MAIN.equals(action) && !ACTION_BALL.equals(action)) {
            // Not a system restart nor the notification: a stale start.
            shutdown();
            return START_NOT_STICKY;
        }
        // Restarted by the system or opened from the notification: keep the
        // current content and show the frame.
        enterForeground(getString(R.string.floating_running));
        if (!Prefs.hasOverlayPermission(this)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (!ballShown) showBall();
        showOverlay();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // The pill on the main screen follows this, not the last tap: the
        // service may also stop by itself or be killed by the system.
        Prefs.setFloatingEnabled(this, false);
        mainHandler.removeCallbacksAndMessages(null);
        removeWindows();
        if (Prefs.isServerEnabled(this)) {
            // The service is being restarted (e.g. for an app update). The
            // new instance replaces the server; keep serving in the meantime.
            Log.i(TAG, "service destroyed; server continues");
            return;
        }
        running = false;
        releaseWakeLock();
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
        NotificationChannel serverChannel = new NotificationChannel(CHANNEL_SERVER_ID,
                getString(R.string.server_notification_title), NotificationManager.IMPORTANCE_LOW);
        serverChannel.setShowBadge(false);
        manager.createNotificationChannel(serverChannel);
    }

    /**
     * Puts the service in the foreground. With the accessibility service a
     * capture may wait for it to reconnect, so that path is dataSync; the
     * MediaProjection path needs the projection type before it gets a grant.
     */
    private void enterForeground(String title) {
        Log.i(TAG, "enterForeground: " + title);
        Intent content = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, content,
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_floating)
                .setContentTitle(title)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        try {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (!serverOnly && usesProjection()) {
                // A mediaProjection-type foreground service is required to hold
                // a projection. Never combine it with the projected process,
                // as some systems refuse a dataSync service that also carries it.
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, type);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable throwable) {
            // Say so instead of failing later with a bare NPE.
            Log.e(TAG, "startForeground failed", throwable);
            ImageUtils.toast(this, getString(R.string.floating_need_notifications));
            shutdown();
        }
    }

    private void shutdown() {
        Prefs.setServerEnabled(this, false);
        stopApiServer();
        mainHandler.removeCallbacksAndMessages(null);
        removeWindows();
        mediaProjection = null;
        projectionData = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        stopSelf();
    }

    private void removeWindows() {
        dismissTextBoxDialog();
        dismissResultView();
        if (ballShown) {
            windowManager.removeView(ball);
            ball = null;
            ballShown = false;
        }
        if (overlay != null) {
            SelectionOverlay removing = overlay;
            overlay = null;
            try {
                removing.remove();
            } catch (IllegalArgumentException e) {
                // The system already took the window away.
                Log.w(TAG, "frame removal failed: " + e);
            }
        }
    }

    /** Resumes the foreground notification whichever mode is active. */
    private void restoreForegroundNotification() {
        if (!Prefs.isServerEnabled(this)) return;
        if (windowsUp()) {
            // Floating windows: the foreground belongs to them; the server
            // address is appended to their notification.
            enterForeground(getString(R.string.floating_running));
            showServerNotification();
        } else {
            serverOnly = true;
            enterServerForeground();
        }
    }

    // --- server -----------------------------------------------------------

    /** Opens port 51042; false when that failed (the error is reported). */
    private boolean startApiServer() {
        try {
            ImageTransApiServer.get().start(this);
            acquireWakeLock();
            return true;
        } catch (Throwable throwable) {
            Log.e(TAG, "server start failed", throwable);
            Prefs.setServerEnabled(this, false);
            ImageUtils.toast(this, getString(R.string.server_start_failed,
                    ImageUtils.humanError(throwable)));
            return false;
        }
    }

    private void stopApiServer() {
        ImageTransApiServer.get().stop();
        releaseWakeLock();
    }

    /**
     * With the screen off the phone may power down the radio/CPU and stop
     * answering HTTP requests. The server is meant to answer whenever the
     * extension asks, so keep a partial wake lock while it runs (a partial
     * wake lock is allowed from a foreground service of type dataSync and
     * does not keep the screen on).
     */
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        wakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "imagetrans:server");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        Log.i(TAG, "wake lock acquired for the server");
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "wake lock released");
        }
    }

    /** Notification for a server running without floating windows. */
    private Notification buildServerNotification() {
        Intent content = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, content,
                PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ScreenshotTranslateService.class)
                .setAction(ACTION_HIDE)
                .putExtra(EXTRA_SERVER_ONLY, true);
        PendingIntent stopPending = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = serverNotificationText();
        return new Notification.Builder(this, CHANNEL_SERVER_ID)
                .setSmallIcon(R.drawable.ic_stat_floating)
                .setContentTitle(getString(R.string.server_notification_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(0, getString(R.string.main_server_on), stopPending)
                .setOngoing(true)
                .build();
    }

    /** Loopback address plus, when online, the LAN address for a PC browser. */
    private String serverNotificationText() {
        String local = ImageTransApiServer.get().address();
        if (local == null) return getString(R.string.server_notification_unavailable);
        StringBuilder text = new StringBuilder(
                getString(R.string.server_notification_text, local));
        String lan = ImageTransApiServer.get().lanAddress();
        if (lan != null) {
            text.append('\n').append(getString(R.string.server_notification_lan, lan));
        }
        return text.toString();
    }

    /** Foreground service with the server's ongoing notification. */
    private void enterServerForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildServerNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, buildServerNotification());
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "server foreground failed", throwable);
            ImageUtils.toast(this, getString(R.string.server_need_notifications));
            shutdown();
        }
    }

    /**
     * While the floating windows run they own the foreground notification;
     * the address is added to it instead of a second notification.
     */
    private void showServerNotification() {
        if (!ballShown && overlay == null) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        String text = getString(R.string.server_notification_title) + " · "
                + serverNotificationText();
        Intent content = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, content,
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_BALL_ID)
                .setSmallIcon(R.drawable.ic_stat_floating)
                .setContentTitle(getString(R.string.floating_running))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true);
        manager.notify(NOTIFICATION_ID, builder.build());
        mainHandler.removeCallbacks(autoHideNotification);
        mainHandler.postDelayed(autoHideNotification, NOTIFICATION_HIDE_DELAY_MS);
    }

    /**
     * Whether anything of the floating windows is meant to be on screen: the
     * frame (shown or hidden last time), or the ball.
     */
    private boolean windowsUp() {
        return Prefs.isFloatingEnabled(this) || frameHidden || ballShown || overlay != null;
    }

    private void showWindows() {
        enterForeground(getString(R.string.floating_running));
        // A frame hidden last time would leave nothing but the ball on the
        // first start: it is shown here, with the ball, and the ball can
        // hide it again.
        if (frameHidden) setFrameHidden(false, false);
        try {
            if (!ballShown) showBall();
            showOverlay();
            // Overlay windows stack in the order added: the frame goes under
            // the ball, which is put on top again.
            raiseBall();
        } catch (RuntimeException e) {
            // e.g. BadTokenException when the overlay grant was revoked: say
            // so instead of crashing the app.
            Log.e(TAG, "floating windows failed", e);
            ImageUtils.toast(this, getString(R.string.floating_need_overlay));
            shutdown();
            return;
        }
        // The ball is the launcher; the frame its notification names.
        showNotificationHint();
    }

    /** The ball above the frame: a window added again goes on top. */
    private void raiseBall() {
        if (!ballShown || ball == null) return;
        try {
            windowManager.removeViewImmediate(ball);
        } catch (IllegalArgumentException ignored) {
            // Already gone.
        }
        windowManager.addView(ball, ballParams);
    }

    /**
     * Takes the frame off the screen, or puts it back where it was. With it
     * hidden only the ball is left: the app underneath is used as usual and
     * the ball's "翻译" takes the frame's remembered area.
     */
    private void setFrameHidden(boolean hidden) {
        setFrameHidden(hidden, true);
    }

    /**
     * @param fromMenu false when the windows are being started: the frame
     *                 is then created by the caller, with the ball
     */
    private void setFrameHidden(boolean hidden, boolean fromMenu) {
        if (hidden == frameHidden) return;
        frameHidden = hidden;
        Prefs.setFrameHidden(this, hidden);
        if (frameItem != null) {
            frameItem.setText(hidden
                    ? R.string.floating_show_frame : R.string.floating_hide_frame);
        }
        if (hidden) {
            // The place stays, for the capture and for showing it again.
            if (overlay != null) Prefs.setFrameRect(this, overlay.getFrame());
            destroyOverlay();
            // A translation still running belonged to the frame: it stops.
            captureRegion = null;
            generation++;
        } else if (fromMenu) {
            showOverlay();
            raiseBall();
        }
        showNotificationHint();
    }

    /** The ball's notification, which names what is on screen now. */
    private void showNotificationHint() {
        if (!ballShown) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_BALL_ID)
                .setSmallIcon(R.drawable.ic_stat_floating)
                .setContentTitle(getString(R.string.floating_running))
                .setContentText(getString(frameHidden
                        ? R.string.floating_running_hint_hidden : R.string.floating_running_hint))
                .setOngoing(true);
        manager.notify(NOTIFICATION_ID, builder.build());
        mainHandler.removeCallbacks(autoHideNotification);
        mainHandler.postDelayed(autoHideNotification, NOTIFICATION_HIDE_DELAY_MS);
    }

    private void destroyOverlay() {
        if (overlay == null) return;
        SelectionOverlay removing = overlay;
        overlay = null;
        try {
            removing.removeImmediately();
        } catch (IllegalArgumentException e) {
            // The system already took the window away.
            Log.w(TAG, "frame removal failed: " + e);
        }
    }

    private void dismissResultView() {
        if (resultView == null) return;
        SelectionOverlay removing = resultView;
        resultView = null;
        if (overlay == removing) overlay = null;
        try {
            removing.removeImmediately();
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "result removal failed: " + e);
        }
    }


    /** Floor of the app area, from the frame's last place (no frame to ask). */
    private Rect rememberedFrameOnScreen() {
        Rect local = Prefs.getFrameRect(this);
        local.offset(0, Prefs.getFrameScreenOffsetY(this));
        return local;
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

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
            capture();
        });
        row.addView(captureItem, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));

        resultItem = new TextView(themed);
        resultItem.setText(R.string.floating_close_result);
        resultItem.setTextColor(getColor(R.color.text_secondary));
        resultItem.setTextSize(14);
        resultItem.setGravity(Gravity.CENTER);
        resultItem.setPadding(dp(12), 0, dp(12), 0);
        resultItem.setOnClickListener(v -> {
            Log.i(TAG, "close result tapped");
            closeResult();
        });
        row.addView(resultItem, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));

        frameItem = new TextView(themed);
        frameItem.setText(R.string.floating_hide_frame);
        frameItem.setTextColor(getColor(R.color.text_secondary));
        frameItem.setTextSize(14);
        frameItem.setGravity(Gravity.CENTER);
        frameItem.setPadding(dp(12), 0, dp(12), 0);
        frameItem.setOnClickListener(v -> {
            Log.i(TAG, "frame toggle tapped");
            setFrameHidden(!frameHidden);
        });
        row.addView(frameItem, new LinearLayout.LayoutParams(
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

    /**
     * Opens or closes the menu. Only the ball itself does it: it stays open
     * until the ball is tapped again, and no choice closes it.
     */
    private void setBallExpanded(boolean expanded) {
        if (!ballShown) return;
        ballExpanded = expanded;
        boolean hasResult = hasResult();
        for (int i = 1; i < ball.getChildCount(); i++) {
            View item = ball.getChildAt(i);
            // Closing a result is offered only while there is one.
            boolean relevant = item != resultItem || hasResult;
            item.setVisibility(expanded && relevant ? View.VISIBLE : View.GONE);
        }
    }

    /** Whether a result is on screen, in the frame or in its own window. */
    private boolean hasResult() {
        return (overlay != null && overlay.hasResult())
                || (resultView != null && resultView.hasResult());
    }

    /**
     * Closes what a translation left on screen. A frame's result is cleared
     * in place (the frame stays, ready for the next one); a result shown
     * without the frame is taken away, leaving only the ball.
     */
    private void closeResult() {
        if (overlay != null && overlay.hasResult()) {
            overlay.clearTranslation();
        }
        dismissResultView();
        hitBoxes.clear();
        if (ballShown) ball.setVisibility(View.VISIBLE);
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
        // Windows left by an earlier turnkey session (whose code may have
        // covered the whole screen) must be removed, not reused.
        if (overlay != null && overlay.isAttachedToWindow()) {
            overlay.setVisibility(View.VISIBLE);
            return;
        }
        if (overlay != null) {
            SelectionOverlay removing = overlay;
            overlay = null;
            try {
                removing.remove();
            } catch (IllegalArgumentException e) {
                // The system already took the window away.
                Log.w(TAG, "frame removal failed: " + e);
            }
        }
        overlay = new SelectionOverlay(this, new SelectionOverlay.Callback() {
            @Override
            public void onCancelled() {
                // Only while shutting down.
            }
        });
        overlay.setTapListener(new SelectionOverlay.TapListener() {
            @Override
            public boolean onTap(float x, float y, float displayScale) {
                return onOverlayTap(x, y, displayScale);
            }

            @Override
            public void onTapInFrame() {
                Log.i(TAG, "frame tapped");
                capture();
            }
        });
        Rect frame = new Rect(
                Math.round(getResources().getDisplayMetrics().widthPixels * 0.06f),
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.18f),
                Math.round(getResources().getDisplayMetrics().widthPixels * 0.94f),
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.82f));
        overlay.show(frame);
    }

    // --- capture ----------------------------------------------------------

    private void capture() {
        if (frameHidden) {
            captureRememberedFrame();
            return;
        }
        if (overlay == null) {
            // The frame went away (or was rebuilt) since the tap: a new
            // frame has nothing to capture yet.
            showWindows();
            return;
        }
        if (!Prefs.hasOverlayPermission(this)) {
            ImageUtils.toast(this, getString(R.string.floating_need_overlay));
            shutdown();
            return;
        }
        showOverlay();
        overlay.setVisibility(View.VISIBLE);
        enterForeground(getString(R.string.floating_translating));
        mainHandler.removeCallbacks(autoHideNotification);
        try {
            if (usesProjection() && !ensureProjection()) {
                // Permission prompt: the result comes back through ACTION_SHOW,
                // which shows the frame again.
                return;
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "projection setup failed", throwable);
            onCaptureError(throwable);
            return;
        }
        // The overlay's frame is in the window's coordinate space, which
        // starts below the status bar; a screenshot or projection is
        // screen-sized.
        capture(overlay.getFrameRectOnScreen());
    }

    /**
     * The frame is hidden: its remembered area is taken, with a toast for
     * the wait (there is nothing on screen to show it in). The result then
     * appears in a window over that same area.
     */
    private void captureRememberedFrame() {
        if (!Prefs.hasFrameRect(this)) {
            ImageUtils.toast(this, getString(R.string.floating_need_frame));
            return;
        }
        if (!Prefs.hasOverlayPermission(this)) {
            ImageUtils.toast(this, getString(R.string.floating_need_overlay));
            shutdown();
            return;
        }
        startRememberedCapture();
    }

    /** The capture itself, for a tap on the ball and the capture consent. */
    private void startRememberedCapture() {
        if (ballShown) ball.setVisibility(View.GONE);
        captureRegion = rememberedFrameOnScreen();
        ImageUtils.toast(this, getString(R.string.floating_translating));
        enterForeground(getString(R.string.floating_translating));
        mainHandler.removeCallbacks(autoHideNotification);
        try {
            if (usesProjection() && !ensureProjection()) {
                return;
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "projection setup failed", throwable);
            onCaptureError(throwable);
            return;
        }
        capture(captureRegion);
    }

    /**
     * The window for the hidden frame's capture: the remembered area, just
     * for showing the work and its result.
     */
    private SelectionOverlay resultViewForCapture() {
        dismissResultView();
        resultView = new SelectionOverlay(this, new SelectionOverlay.Callback() {
            @Override
            public void onCancelled() {
                // Only while being taken away.
            }
        });
        resultView.setTapListener(new SelectionOverlay.TapListener() {
            @Override
            public boolean onTap(float x, float y, float displayScale) {
                return onOverlayTap(x, y, displayScale);
            }

            @Override
            public void onTapInFrame() {
                // The result is for reading there, not translated again.
            }

            @Override
            public void onResultCleared() {
                // The frame is hidden: nothing left but the ball.
                dismissResultView();
            }
        });
        Rect local = captureRegion != null ? new Rect(captureRegion) : Prefs.getFrameRect(this);
        local.offset(0, -Prefs.getFrameScreenOffsetY(this));
        resultView.show(local);
        // The capture hidden the only ball's own window; the ball also goes
        // for the screenshot and comes back with the result.
        if (ballShown) {
            ball.setVisibility(View.VISIBLE);
            raiseBall();
        }
        return resultView;
    }

    /** A failure with the frame hidden: a toast, and the ball back. */
    private void showFailure(String error) {
        captureRegion = null;
        ImageUtils.toast(this, error != null
                ? getString(R.string.floating_capture_failed) + "：" + error
                : getString(R.string.floating_capture_failed));
        if (ballShown) ball.setVisibility(View.VISIBLE);
    }

    /** Captures the given screen region, then translates it in the frame. */
    private void capture(Rect region) {
        final Rect rect = new Rect(region);
        if (rect.width() <= 0 || rect.height() <= 0) return;
        if (!usesProjection()) {
            Log.i(TAG, "capturing " + rect + " mode=accessibility");
            captureScreen(rect, 1);
        } else {
            Log.i(TAG, "capturing " + rect + " mode=projection");
            captureProjection(rect);
        }
    }

    // --- MediaProjection (below Android 12, the fallback on Android 12+) ---

    /**
     * Starts a capture with a projection, or, on Android 12+, through the
     * accessibility service where the projection is not there to be used.
     * The consent comes from ProjectionPermissionActivity and is kept for a
     * while, so repeated captures only ask once; a consent Intent that
     * already created a projection is never reused.
     */
    private void captureProjection(Rect rect) {
        if (mediaProjection == null && !ensureProjection()) return;
        if (mediaProjection == null) {
            if (useAccessibility()) {
                // A grant that made no projection: the accessibility
                // service's screenshots still work.
                Log.i(TAG, "no projection, capturing through accessibility");
                captureScreen(rect, 1);
                return;
            }
            return;
        }
        projectionCapture(rect);
    }

    /**
     * Makes sure a live projection exists; starts the consent prompt and
     * returns false when there is none yet. A consent Intent creates exactly
     * one projection, so it is dropped once used.
     */
    private boolean ensureProjection() {
        if (mediaProjection != null) return true;
        if (projectionData == null || SystemClock.elapsedRealtime() - projectionGrantedAt
                > PROJECTION_GRANT_LIFETIME_MS) {
            projectionData = null;
            projectionResultCode = 0;
            askProjectionPermission();
            return false;
        }
        enterForeground(getString(R.string.floating_translating));
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try {
            mediaProjection = manager.getMediaProjection(projectionResultCode, projectionData);
        } catch (RuntimeException e) {
            Log.w(TAG, "getMediaProjection failed: " + e);
        }
        projectionData = null;
        if (mediaProjection == null) {
            askProjectionPermission();
            return false;
        }
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "projection stopped");
                mediaProjection = null;
            }
        }, mainHandler);
        return true;
    }

    private void askProjectionPermission() {
        pendingCapture = true;
        mainHandler.removeCallbacks(autoHideNotification);
        if (overlay != null) overlay.setVisibility(View.GONE);
        Log.i(TAG, "asking for capture permission");
        startActivity(new Intent(this, ProjectionPermissionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * Mirrors the whole screen into an ImageReader (the only size a projection
     * reliably accepts) and cuts out the requested region in memory.
     */
    private void projectionCapture(Rect rect) {
        final DisplayMetrics metrics = realDisplayMetrics();
        final int screenWidth = metrics.widthPixels;
        final int screenHeight = metrics.heightPixels;
        final Rect screen = new Rect(0, 0, screenWidth, screenHeight);
        final Rect crop = new Rect(rect);
        crop.intersect(screen);
        if (crop.isEmpty()) {
            imageOnCaptured(null, null);
            return;
        }
        final ImageReader reader = ImageReader.newInstance(screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2);
        final VirtualDisplay[] display = {null};
        final Runnable release = () -> {
            if (display[0] != null) {
                display[0].release();
                display[0] = null;
            }
            reader.close();
        };
        final long startedAt = SystemClock.uptimeMillis();
        // Hide the frame first, then give the screen a moment to
        // redraw without them before grabbing pixels.
        if (!frameHidden && overlay != null) overlay.setVisibility(View.INVISIBLE);
        mainHandler.postDelayed(() -> {
            if (!frameHidden && overlay == null) {
                // Closed before the projection was created.
                release.run();
                return;
            }
            if (mediaProjection == null) {
                release.run();
                imageOnCaptured(null, getString(R.string.floating_capture_auth_expired));
                return;
            }
            try {
                display[0] = mediaProjection.createVirtualDisplay("screenshot-translate",
                        screenWidth, screenHeight, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, mainHandler);
            } catch (Throwable throwable) {
                Log.e(TAG, "createVirtualDisplay failed", throwable);
                release.run();
                imageOnCaptured(null, ImageUtils.humanError(throwable));
            }
        }, 300);
        reader.setOnImageAvailableListener(r -> {
            r.setOnImageAvailableListener(null, null);
            executor.execute(() -> decodeProjectionImage(r, crop, screenWidth, screenHeight,
                    startedAt, release));
        }, mainHandler);
    }

    private void decodeProjectionImage(ImageReader r, Rect crop, int screenWidth,
                                       int screenHeight, long startedAt, Runnable release) {
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
                int paddedWidth = screenWidth + (rowStride - pixelStride * screenWidth)
                        / pixelStride;
                Bitmap full = Bitmap.createBitmap(paddedWidth, screenHeight,
                        Bitmap.Config.ARGB_8888);
                full.copyPixelsFromBuffer(buffer);
                Bitmap result = full;
                if (paddedWidth != screenWidth || !crop.isEmpty()) {
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
            error = getString(R.string.floating_no_image);
        }
        final Bitmap result = bitmap;
        final String message = error;
        mainHandler.post(() -> {
            release.run();
            imageOnCaptured(result, message);
        });
    }

    /** Display size including the system bars, i.e. the projection's size. */
    @SuppressWarnings("deprecation")
    private DisplayMetrics realDisplayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        DisplayManager manager = getSystemService(DisplayManager.class);
        android.view.Display display = manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if (display != null) {
            display.getRealMetrics(metrics);
        } else {
            metrics.setTo(getResources().getDisplayMetrics());
        }
        return metrics;
    }

    /**
     * Screenshots the whole screen and cuts out the region. Right after a tap
     * the accessibility service may still be finishing an app switch, so a
     * failed attempt is repeated for a few seconds before giving up.
     */
    private void captureScreen(final Rect crop, final int attempt) {
        if (!frameHidden && overlay == null) {
            // Closed while a retry was still scheduled.
            return;
        }
        // Screenshots show what is drawn, the frame included, so hide it.
        if (!frameHidden) overlay.setVisibility(View.INVISIBLE);
        if (ScreenshotAccessibilityService.isEnabled()) {
            ScreenshotAccessibilityService.capture((bitmap, error) -> {
                if (bitmap != null) {
                    imageOnCaptured(cropToFrame(bitmap, crop), null);
                    bitmap.recycle();
                } else {
                    failedCapture(attempt, crop, new IllegalStateException(error));
                }
            });
            return;
        }
        // Turned on in the settings but not (yet) connected to this process.
        if (Prefs.hasAccessibilityService(this) && attempt < MAX_CAPTURE_ATTEMPTS) {
            mainHandler.postDelayed(() -> captureScreen(crop, attempt + 1),
                    ATTEMPT_DELAY_MS);
            return;
        }
        // imageOnCaptured() puts the windows back.
        imageOnCaptured(null, getString(R.string.floating_need_accessibility));
    }

    private void failedCapture(int attempt, Rect crop, Throwable throwable) {
        if (attempt < MAX_CAPTURE_ATTEMPTS) {
            mainHandler.postDelayed(() -> captureScreen(crop, attempt + 1),
                    ATTEMPT_DELAY_MS);
            return;
        }
        if (mediaProjection != null) {
            // The accessibility service is connected, so no projection is
            // asked for on Android 12+: one kept from the start of the
            // session is still good (or the whole screen is mirrored).
            Log.i(TAG, "accessibility capture failed, trying the projection");
            captureProjection(crop);
            return;
        }
        onCaptureError(throwable);
    }

    /** Accounts for a screenshot whose size differs from the window's. */
    private Bitmap cropToFrame(Bitmap screen, Rect crop) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float scale = metrics.widthPixels / (float) screen.getWidth();
        Rect scaled = new Rect(
                Math.round(crop.left * scale), Math.round(crop.top * scale),
                Math.round(crop.right * scale), Math.round(crop.bottom * scale));
        scaled.intersect(0, 0, screen.getWidth(), screen.getHeight());
        if (scaled.isEmpty()) return null;
        return Bitmap.createBitmap(screen, scaled.left, scaled.top,
                scaled.width(), scaled.height());
    }

    private void imageOnCaptured(Bitmap bitmap, String error) {
        if (frameHidden) {
            // Nothing was on screen for the work. A result is translated
            // without its own window (see showTranslated), a failure is only
            // said.
            if (bitmap == null) {
                showFailure(error);
            } else {
                ImageUtils.toast(this, getString(R.string.floating_translating));
                onCaptured(forHiddenResult(bitmap));
            }
            return;
        }
        // Closed while the capture was still running.
        if (overlay == null) {
            ImageUtils.recycle(bitmap);
            return;
        }
        restoreWindows();
        if (bitmap == null) {
            overlay.showResult(null, error != null
                    ? getString(R.string.floating_capture_failed) + "：" + error
                    : getString(R.string.floating_capture_failed));
            return;
        }
        onCaptured(bitmap);
    }

    /** Undoes what capture() changed about the windows. */
    private void restoreWindows() {
        if (!frameHidden && overlay != null) overlay.setVisibility(View.VISIBLE);
        if (ballShown) {
            ball.setVisibility(View.VISIBLE);
            raiseBall();
        }
    }

    /**
     * The captured image for the result: while hidden, the screen version
     * was scaled when cut out, so it is scaled back to the area's size here.
     */
    private Bitmap forHiddenResult(Bitmap bitmap) {
        if (!frameHidden || captureRegion == null || bitmap == null) return bitmap;
        int w = captureRegion.width();
        int h = captureRegion.height();
        if (bitmap.getWidth() == w && bitmap.getHeight() == h) return bitmap;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, w, h, true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    private void onCaptured(Bitmap bitmap) {
        if (overlay == null && !frameHidden) {
            ImageUtils.recycle(bitmap);
            return;
        }
        final SelectionOverlay frame = overlay;
        if (frame != null) frame.showStatus(getString(R.string.floating_translating));
        if (bitmap == null) {
            if (frame != null) {
                frame.showResult(null, getString(R.string.floating_capture_failed));
            } else {
                showFailure(null);
            }
            return;
        }

        // Translation keeps running in the background; its results are only
        // shown while this frame is still the one on screen.
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
                        if (current != generation) return;
                        if (frame != null) {
                            if (overlay == frame) {
                                frame.showResult(null, getString(R.string.floating_no_text));
                            }
                        } else {
                            showFailure(getString(R.string.floating_no_text));
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
                    if (current != generation || (frame != null && overlay != frame)) return;
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
                                        if (current != generation || (frame != null && overlay != frame)) {
                                            rendered.recycle();
                                            return;
                                        }
                                        showTranslated(rendered, boxes);
                                    }

                                    @Override
                                    public void onError(String message) {
                                        if (current == generation && (frame == null || overlay == frame)) {
                                            if (frame != null) {
                                                frame.showResult(null, getString(
                                                        R.string.floating_translate_failed, message));
                                            } else {
                                                showFailure(getString(
                                                        R.string.floating_translate_failed, message));
                                            }
                                        }
                                    }
                                });
                    } catch (Throwable throwable) {
                        if (current == generation && (frame == null || overlay == frame)) {
                            if (frame != null) {
                                frame.showResult(null, getString(
                                        R.string.floating_translate_failed,
                                        ImageUtils.humanError(throwable)));
                            } else {
                                showFailure(getString(R.string.floating_translate_failed,
                                        ImageUtils.humanError(throwable)));
                            }
                        }
                    }
                });
            } catch (Throwable throwable) {
                Log.e(TAG, "translation failed", throwable);
                mainHandler.post(() -> {
                    if (current != generation || (frame != null && overlay != frame)) return;
                    if (frame != null) {
                        frame.showResult(null, getString(R.string.floating_translate_failed,
                                ImageUtils.humanError(throwable)));
                    } else {
                        showFailure(getString(R.string.floating_translate_failed,
                                ImageUtils.humanError(throwable)));
                    }
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
        if (frameHidden) {
            showFailure(null);
        } else if (overlay != null) {
            overlay.showResult(null, getString(R.string.floating_capture_failed));
        } else if (ballShown) {
            ball.setVisibility(View.VISIBLE);
        }
        ImageUtils.toast(this, getString(R.string.floating_capture_error,
                ImageUtils.humanError(throwable)));
        restoreWindows();
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    private void showTranslated(Bitmap bitmap, List<Box> boxes) {
        SelectionOverlay shown = overlay;
        if (shown == null && frameHidden) {
            // No frame: the result gets its own window now.
            shown = resultViewForCapture();
        }
        if (shown == null) {
            ImageUtils.recycle(bitmap);
            return;
        }
        // A tap on it is read as the frame's own (see onOverlayTap).
        if (frameHidden) overlay = shown;
        // The boxes with text, in the same order for the taps, the numbering
        // and the source/target texts; a box without text has nothing to show.
        hitBoxes.clear();
        for (Box box : boxes) {
            if (box != null && box.hasText()) hitBoxes.add(box);
        }
        shown.showResult(bitmap, null);
        captureRegion = null;
        Log.i(TAG, "translation shown, " + hitBoxes.size() + " boxes");
    }

    /** A tap on the frame's content. Returns true when it opened a dialog. */
    private boolean onOverlayTap(float x, float y, float displayScale) {
        if (hitBoxes.isEmpty() || overlay == null) return false;
        // The boxes are in image pixels; keep the touch target at 44dp on
        // screen whatever the image is zoomed to.
        float scale = Math.max(displayScale, 0.01f);
        float minSize = dp(44) / scale;
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

    /**
     * Shows the source/target of a box. A Dialog needs an activity token,
     * which a service does not have, so the dialog is added as an overlay
     * window instead.
     */
    private void showTextBoxDialog(final int index) {
        if (textBoxDialog != null) {
            // Already open: show the next box's text in the same window
            // instead of building a new one (the selection would be lost and
            // the window jump).
            fillTextBoxDialog(index);
            return;
        }
        Context themed = new ContextThemeWrapper(this, R.style.FloatingTheme);
        View content = LayoutInflater.from(themed).inflate(R.layout.dialog_text_box, null);
        TextView sourceText = content.findViewById(R.id.sourceText);
        TextView targetText = content.findViewById(R.id.targetText);

        // Plain text with a custom selection action: the system's own text
        // selection menu does not appear in an app-overlay window, but the
        // selection itself works. Long press selects, and a bar offers the
        // actions for the selected range.
        configureSelection(sourceText);
        configureSelection(targetText);

        LinearLayout panel = new LinearLayout(themed);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.card_bg);

        TextView title = new TextView(themed);
        title.setText(getString(R.string.text_box_title, index + 1, hitBoxes.size()));
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(18);
        title.setPadding(dp(24), dp(20), dp(24), dp(4));
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        fillBoxText(sourceText, targetText, index);

        LinearLayout buttons = new LinearLayout(themed);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(dp(12), dp(4), dp(12), dp(8));
        if (hitBoxes.size() > 1) {
            // From the box shown now, not from the one the dialog was
            // opened with: the dialog stays up while the box changes.
            TextView previous = dialogButton(themed, R.string.action_previous);
            previous.setOnClickListener(v ->
                    showTextBoxDialog((textBoxIndex - 1 + hitBoxes.size()) % hitBoxes.size()));
            buttons.addView(previous);
            TextView next = dialogButton(themed, R.string.action_next);
            next.setOnClickListener(v ->
                    showTextBoxDialog((textBoxIndex + 1) % hitBoxes.size()));
            buttons.addView(next);
        }
        // Tapping a text copies it; long press selects part of it.
        sourceText.setOnClickListener(v -> {
            if (!selectedInsteadOfTapping(sourceText)) copyText(sourceText);
        });
        targetText.setOnClickListener(v -> {
            if (!selectedInsteadOfTapping(targetText)) copyText(targetText);
        });
        touchTracker = (view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP) {
                touchView = null;
                touchDownAt = 0;
                return;
            }
            if (action != MotionEvent.ACTION_DOWN) return;
            touchView = (TextView) view;
            touchDownAt = SystemClock.uptimeMillis();
            if (view == targetText) {
                targetPressed = true;
                // One selection at a time: picking text in this field drops
                // the one in the other.
                clearSelection(sourceText);
            } else if (view == sourceText) {
                targetPressed = false;
                clearSelection(targetText);
            }
        };
        sourceText.setOnTouchListener(this::trackTouch);
        targetText.setOnTouchListener(this::trackTouch);

        LinearLayout selectionBar = new LinearLayout(themed);
        selectionBar.setOrientation(LinearLayout.HORIZONTAL);
        selectionBar.setGravity(Gravity.END);
        selectionBar.setPadding(dp(12), 0, dp(12), 0);
        TextView copySelection = dialogButton(themed, R.string.copy_selection);
        copySelection.setOnClickListener(v -> copySelection());
        selectionBar.addView(copySelection);
        TextView copyAll = dialogButton(themed, R.string.copy_all);
        copyAll.setOnClickListener(v -> copyAll());
        selectionBar.addView(copyAll);
        panel.addView(selectionBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView close = dialogButton(themed, R.string.action_close);
        close.setOnClickListener(v -> dismissTextBoxDialog());
        buttons.addView(close);
        panel.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        this.selectionBar = selectionBar;
        this.sourceText = sourceText;
        this.targetText = targetText;
        this.textBoxTitle = title;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        android.widget.ScrollView scroll = new android.widget.ScrollView(themed);
        scroll.addView(panel);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.round(metrics.widthPixels * 0.86f),
                Math.round(metrics.heightPixels * 0.6f),
                overlayType(),
                // Touchable but not focusable: app-overlay windows cannot
                // become the focused window, so requesting focus would keep
                // every touch from reaching the view here.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        params.setTitle("ImageTransTextBox");
        try {
            windowManager.addView(scroll, params);
            textBoxDialog = scroll;
        } catch (Throwable throwable) {
            Log.e(TAG, "text box dialog failed", throwable);
        }
    }

    private void configureSelection(TextView view) {
        // Not focusable, so a long press never opens a keyboard; it only
        // selects, and the selection bar below becomes usable.
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setTextIsSelectable(true);
    }

    private interface TouchTracker {
        void onTouch(View view, MotionEvent event);
    }

    private TouchTracker touchTracker;
    /** The text view under the finger, to tell a new selection from a copy tap. */
    private TextView touchView;
    private long touchDownAt;

    /** Whether the finger did not merely tap the text (copy) but selected it. */
    private boolean selectedInsteadOfTapping(TextView view) {
        if (view != touchView || touchDownAt == 0) return false;
        return SystemClock.uptimeMillis() - touchDownAt
                > ViewConfiguration.getLongPressTimeout() / 2;
    }

    /**
     * Removes a text view's selection without a touch: setSelection exists on
     * EditText only, and the framework selection hand grips need a touch
     * stream. The spans behind getSelectionStart() are removed instead, which
     * makes hasSelection() false again.
     */
    private static void clearSelection(TextView view) {
        if (view == null || !view.hasSelection()) return;
        int start = Math.min(view.getSelectionStart(), view.getSelectionEnd());
        int end = Math.max(view.getSelectionStart(), view.getSelectionEnd());
        Spannable text = (Spannable) view.getText();
        text.removeSpan(Selection.getSelectionStart(text));
        text.removeSpan(Selection.getSelectionEnd(text));
        Selection.setSelection(text, end, end);
        Selection.removeSelection(text);
        if (view.hasSelection()) {
            // Last resort: a cursor at the end is not a selection either.
            Selection.setSelection(text, Math.min(start, end));
        }
    }

    private boolean trackTouch(View view, MotionEvent event) {
        if (touchTracker != null) touchTracker.onTouch(view, event);
        return false;
    }

    /** Tapping a text copies all of it. */
    private void copyText(TextView view) {
        CharSequence text = view.getText();
        if (text == null || text.length() == 0) return;
        clipboard().setPrimaryClip(android.content.ClipData.newPlainText("text", text));
        ImageUtils.toast(this, getString(R.string.copied));
    }

    private android.content.ClipboardManager clipboard() {
        return (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    }

    private void copySelection() {
        TextView view = selectionView();
        int start = Math.min(view.getSelectionStart(), view.getSelectionEnd());
        int end = Math.max(view.getSelectionStart(), view.getSelectionEnd());
        CharSequence text = view.getText();
        if (start < 0 || end <= start || text == null) {
            ImageUtils.toast(this, getString(R.string.select_text_first));
            return;
        }
        clipboard().setPrimaryClip(android.content.ClipData.newPlainText("text",
                text.subSequence(start, end)));
        ImageUtils.toast(this, getString(R.string.copied));
    }

    private void copyAll() {
        TextView view = selectionView();
        CharSequence text = view.getText();
        if (text == null || text.length() == 0) return;
        clipboard().setPrimaryClip(android.content.ClipData.newPlainText("text", text));
        ImageUtils.toast(this, getString(R.string.copied));
    }

    /**
     * The text being selected: whichever one was last touched. Only one can
     * have a selection at a time (see the touch tracker).
     */
    private TextView selectionView() {
        if (targetPressed && targetText != null) return targetText;
        return sourceText != null ? sourceText : targetText;
    }

    private TextView dialogButton(Context context, int textRes) {
        TextView button = new TextView(context);
        button.setText(textRes);
        button.setTextColor(getColor(R.color.primary));
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(4), dp(16), dp(4));
        button.setBackgroundResource(selectableItemBackground(context));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        button.setLayoutParams(params);
        return button;
    }

    @SuppressWarnings("deprecation")
    private static int selectableItemBackground(Context context) {
        android.util.TypedValue value = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                value, true);
        return value.resourceId;
    }

    /** Shows another box's text in the dialog that is already open. */
    private void fillTextBoxDialog(int index) {
        if (index < 0 || index >= hitBoxes.size()) return;
        textBoxIndex = index;
        if (textBoxTitle != null) {
            textBoxTitle.setText(getString(R.string.text_box_title, index + 1, hitBoxes.size()));
        }
        if (sourceText == null || targetText == null) return;
        clearSelection(sourceText);
        clearSelection(targetText);
        fillBoxText(sourceText, targetText, index);
    }

    private void fillBoxText(TextView source, TextView target, int index) {
        Box box = hitBoxes.get(index);
        source.setText(box.source == null || box.source.trim().isEmpty()
                ? getString(R.string.text_box_empty) : box.source);
        target.setText(box.target == null || box.target.trim().isEmpty()
                ? getString(R.string.text_box_empty) : box.target);
    }

    private void dismissTextBoxDialog() {
        if (textBoxDialog != null) {
            try {
                windowManager.removeView(textBoxDialog);
            } catch (IllegalArgumentException ignored) {
                // Already removed.
            }
            textBoxDialog = null;
        }
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
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new Exception(getString(R.string.floating_renderer_timeout));
            }
            if (error[0] != null) throw new Exception(ImageUtils.humanError(error[0]));
            if (holder[0] == null) {
                throw new Exception(getString(R.string.floating_renderer_failed));
            }
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
