package com.basiccat.imagetrans;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_IMAGE = 1001;
    private static final int REQUEST_CAPTURE = 1002;
    private static final int REQUEST_OVERLAY = 1003;
    private static final int REQUEST_NOTIFICATIONS = 1004;

    private TextView modeSummary;
    private TextView langSummary;
    private Button btnFloating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        modeSummary = findViewById(R.id.modeSummary);
        langSummary = findViewById(R.id.langSummary);
        btnFloating = findViewById(R.id.btnFloating);
        btnFloating.setOnClickListener(v -> toggleFloating());

        findViewById(R.id.btnPickImage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        updateSummary();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSummary();
        updateFloatingButton();
    }

    private void updateSummary() {
        String mode = Prefs.getMode(this);
        modeSummary.setText("翻译方式：" + Prefs.modeName(mode));
        langSummary.setText("语言：" + Prefs.langName(Prefs.getSourceLang(this))
                + " → " + Prefs.langName(Prefs.getTargetLang(this)));
    }

    private void updateFloatingButton() {
        btnFloating.setText(Prefs.isFloatingEnabled(this)
                ? R.string.main_floating_on : R.string.main_floating_off);
    }

    // --- floating screenshot translation ----------------------------------

    private void toggleFloating() {
        if (Prefs.isFloatingEnabled(this)) {
            ScreenshotTranslateService.stop(this);
            Prefs.setFloatingEnabled(this, false);
            updateFloatingButton();
        } else {
            enableFloating();
        }
    }

    private void enableFloating() {
        if (!Prefs.hasOverlayPermission(this)) {
            if (Prefs.isOverlayAsked(this)) {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.main_floating_need_permission)
                        .setPositiveButton(R.string.main_open_settings,
                                (dialog, which) -> startActivityForResult(
                                        Prefs.overlayPermissionIntent(this), REQUEST_OVERLAY))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
            Prefs.setOverlayAsked(this);
            startActivityForResult(Prefs.overlayPermissionIntent(this), REQUEST_OVERLAY);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // The notification is only a convenience; continue either way.
        if (requestCode == REQUEST_NOTIFICATIONS) requestScreenCapture();
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE) {
            // The grant is handed straight to the floating service.
            if (resultCode == RESULT_OK && data != null) {
                Prefs.setFloatingEnabled(this, true);
                ScreenshotTranslateService.show(this, resultCode, data);
                ImageUtils.toast(this, getString(R.string.main_floating_started));
            } else {
                ImageUtils.toast(this, getString(R.string.main_floating_cancelled));
            }
            updateFloatingButton();
            return;
        }
        if (requestCode == REQUEST_OVERLAY) {
            if (Prefs.hasOverlayPermission(this)) {
                enableFloating();
            } else {
                ImageUtils.toast(this, getString(R.string.main_floating_need_permission_short));
            }
            return;
        }
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
            // Some providers don't offer persistable permissions; the temporary
            // grant is enough for the TranslateActivity launch below.
        }
        Intent intent = new Intent(this, TranslateActivity.class);
        intent.setData(uri);
        startActivity(intent);
    }
}
