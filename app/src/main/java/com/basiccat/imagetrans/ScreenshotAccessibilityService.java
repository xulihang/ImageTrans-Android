package com.basiccat.imagetrans;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

/**
 * Takes the screenshots for the floating translation windows. Unlike
 * MediaProjection this needs no per-session consent: once enabled, the user
 * can switch apps freely and capture any time. The service lives in this
 * process, so the floating service talks to it through a static reference.
 */
public class ScreenshotAccessibilityService extends AccessibilityService {
    private static final String TAG = "ScreenshotAccessibility";
    /** takeScreenshot() fails while a window transition is still running. */
    private static final long RETRY_DELAY_MS = 120;
    private static final int ATTEMPTS = 8;

    private static volatile ScreenshotAccessibilityService instance;

    interface Callback {
        /** Runs on the main thread; a null bitmap means {@code error} is set. */
        void onScreenshot(Bitmap bitmap, String error);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        Log.i(TAG, "unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Screenshots only; nothing else is observed.
    }

    @Override
    public void onInterrupt() {
    }

    static boolean isEnabled() {
        return instance != null;
    }

    /** Takes a full-screen screenshot; the callback runs on the main thread. */
    static void capture(Callback callback) {
        ScreenshotAccessibilityService service = instance;
        if (service == null) {
            callback.onScreenshot(null, "disabled");
            return;
        }
        service.take(1, SystemClock.uptimeMillis(), callback);
    }

    private void take(final int attempt, final long startedAt, final Callback callback) {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        Bitmap bitmap = toBitmap(result);
                        if (bitmap == null) {
                            retry(attempt, startedAt, callback, "empty");
                            return;
                        }
                        Log.i(TAG, "captured " + bitmap.getWidth() + "x" + bitmap.getHeight()
                                + " in " + (SystemClock.uptimeMillis() - startedAt) + "ms");
                        callback.onScreenshot(bitmap, null);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        retry(attempt, startedAt, callback, errorName(errorCode));
                    }
                });
    }

    private static Bitmap toBitmap(ScreenshotResult result) {
        if (result == null) return null;
        Bitmap hardware = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),
                result.getColorSpace());
        result.getHardwareBuffer().close();
        if (hardware == null) return null;
        // ONNX and the renderer both want readable pixels.
        Bitmap copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
        hardware.recycle();
        return copy;
    }

    /**
     * Animations and window transitions make takeScreenshot() fail for a
     * moment, which is normal right after a tap, so try again shortly.
     */
    private void retry(final int attempt, final long startedAt, final Callback callback,
                       String error) {
        if (attempt < ATTEMPTS) {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> take(attempt + 1, startedAt, callback), RETRY_DELAY_MS);
        } else {
            Log.w(TAG, "screenshot failed: " + error);
            callback.onScreenshot(null, error);
        }
    }

    private static String errorName(int errorCode) {
        switch (errorCode) {
            case ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:
                return "interval_time_short";
            case ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY:
                return "invalid_display";
            case ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:
                return "no_accessibility_access";
            default:
                return "error_" + errorCode;
        }
    }
}
