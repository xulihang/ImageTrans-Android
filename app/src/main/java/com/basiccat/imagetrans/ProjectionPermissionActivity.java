package com.basiccat.imagetrans;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Holds the "allow screen capture" system dialog (Android 14+ refuses to
 * start it from a background service), then hands the grant to the floating
 * service and disappears.
 */
public class ProjectionPermissionActivity extends Activity {
    private static final String TAG = "ScreenshotTranslate";
    private static final int REQUEST_CAPTURE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
            // yet foreground, so start it as such (the notification appears a
            // moment later; the system allows this window for services that
            // start from an activity).
            startForegroundService(new Intent(this, ScreenshotTranslateService.class)
                    .setAction(ScreenshotTranslateService.ACTION_SHOW)
                    .putExtra(ScreenshotTranslateService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenshotTranslateService.EXTRA_RESULT_DATA, data));
        } else {
            ScreenshotTranslateService.stop(this);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
