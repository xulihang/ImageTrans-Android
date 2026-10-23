package com.basiccat.imagetrans;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.util.Log;

/**
 * Lets the floating service ask for screen capture consent on releases where
 * there is no AccessibilityService.takeScreenshot() (below Android 11 / API
 * 30). Android 14+ refuses to start this activity from a background service,
 * which is one more reason those releases use the accessibility service.
 */
public class ProjectionPermissionActivity extends LocalizedActivity {
    private static final String TAG = "ScreenshotTranslate";
    private static final int REQUEST_CAPTURE = 1;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "requesting capture permission in activity");
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.i(TAG, "capture permission result code=" + resultCode);
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            // Android refuses to hand a projection to a service that is not
            // yet foreground, so start it as such.
            Intent service = new Intent(this, ScreenshotTranslateService.class)
                    .setAction(ScreenshotTranslateService.ACTION_SHOW)
                    .putExtra(ScreenshotTranslateService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenshotTranslateService.EXTRA_RESULT_DATA, data);
            startForegroundService(service);
        } else {
            ScreenshotTranslateService.stop(this);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
