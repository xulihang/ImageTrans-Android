package com.basiccat.imagetrans;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;

public class MainActivity extends LocalizedActivity {
    private static final int REQUEST_PICK_IMAGE = 1001;
    private static final int REQUEST_CAPTURE = 1002;
    private static final int REQUEST_OVERLAY = 1003;
    private static final int REQUEST_ACCESSIBILITY = 1005;
    private boolean notificationRequested;
    /**
     * A capture grant (below Android 12, or above it with the accessibility
     * service off) was requested and its result is expected.
     * The system may still report RESULT_CANCELED to the recreated activity
     * (e.g. before the grant dialog is shown again); that is not a refusal.
     */
    private boolean capturePending;
    /**
     * The user just came back from the overlay settings: the grant may not be
     * visible to this process yet, so the floating windows are started as
     * soon as it is, instead of reporting a missing permission.
     */
    private boolean overlayPending;
    private static final long OVERLAY_GRANT_POLL_MS = 200;
    private static final long OVERLAY_GRANT_TIMEOUT_MS = 3000;
    private final Runnable pollOverlayGrant = this::checkOverlayGrant;

    private TextView modeSummary;
    private TextView langSummary;
    private TextView serverState;
    private Button btnFloating;
    /** While the button says "close", poll whether the service is still up. */
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollFloating = new Runnable() {
        @Override
        public void run() {
            updateFloatingButton();
        }
    };
    /** Refresh the server state after the start/stop request is handled. */
    private final Runnable updateServerStateRunnable = this::updateServerState;
    /** The user is touching the screen, i.e. this activity is in front. */
    private boolean touching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            capturePending = savedInstanceState.getBoolean("capture_pending", false);
            if (savedInstanceState.getBoolean("overlay_pending", false)) awaitOverlayGrant();
        }
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        modeSummary = findViewById(R.id.modeSummary);
        langSummary = findViewById(R.id.langSummary);
        serverState = findViewById(R.id.serverState);
        btnFloating = findViewById(R.id.btnFloating);

        TextView appVersion = findViewById(R.id.appVersion);
        appVersion.setText(getString(R.string.main_version, BuildConfig.VERSION_NAME));
        findViewById(R.id.projectUrl).setOnClickListener(v -> {
            Intent browser = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.main_project_url)));
            try {
                startActivity(browser);
            } catch (Exception ignored) {
                // No browser installed.
            }
        });
        // Toggling screen capture requires visible UI: Android stops a
        // projection whose foreground service was started from the
        // background, and the floating service keeps its session alive.
        getWindow().getDecorView().setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                touching = event.getActionMasked() == MotionEvent.ACTION_DOWN
                        || event.getActionMasked() == MotionEvent.ACTION_MOVE;
                return false;
            }
        });
        btnFloating.setOnClickListener(v -> toggleFloating());
        initFloatingMode();
        findViewById(R.id.btnServer).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, ServerActivity.class)));

        findViewById(R.id.btnPickImage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        findViewById(R.id.btnCameraTranslate).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, CameraTranslateActivity.class)));
        findViewById(R.id.btnFolderTranslate).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, FolderTranslateActivity.class)));
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
        restoreServerIfEnabled();
        updateServerState();
        pollHandler.removeCallbacks(updateServerStateRunnable);
        pollHandler.postDelayed(updateServerStateRunnable, 1000);
    }

    /**
     * The process (and its foreground notification) may have been killed
     * while the "server enabled" flag stayed set. Start the foreground
     * service again so the server and its notification come back.
     */
    private void restoreServerIfEnabled() {
        if (!Prefs.isServerEnabled(this)) return;
        try {
            ScreenshotTranslateService.startServer(this);
        } catch (Throwable throwable) {
            ImageUtils.toast(this, getString(R.string.server_start_failed,
                    ImageUtils.humanError(throwable)));
        }
    }

    private void updateServerState() {
        boolean enabled = Prefs.isServerEnabled(this);
        serverState.setText(enabled
                ? R.string.main_server_state_on : R.string.main_server_state_off);
        serverState.setTextColor(getColor(enabled
                ? R.color.primary : R.color.text_secondary));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("capture_pending", capturePending);
        outState.putBoolean("overlay_pending", overlayPending);
    }

    @Override
    protected void onDestroy() {
        pollHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void updateSummary() {
        String mode = Prefs.getMode(this);
        modeSummary.setText(getString(R.string.main_mode_summary,
                Prefs.modeName(this, mode)));
        langSummary.setText(getString(R.string.main_lang_summary,
                Prefs.langName(this, Prefs.getSourceLang(this)),
                Prefs.langName(this, Prefs.getTargetLang(this))));
    }

    private void updateFloatingButton() {
        boolean running = isFloatingRunning();
        if (!running && Prefs.isFloatingEnabled(this)) {
            // Nothing on screen: the service died without a chance to reset
            // the flag (killed process, crash): forget "enabled".
            Prefs.setFloatingEnabled(this, false);
        }
        btnFloating.setText(running ? R.string.main_floating_on : R.string.main_floating_off);
        pollHandler.removeCallbacks(pollFloating);
        if (running) pollHandler.postDelayed(pollFloating, 2000);
    }

    /**
     * Waits for the overlay grant made in the system settings to reach this
     * process, then continues starting the floating windows. Only when it does
     * not arrive is the user told the permission is missing.
     */
    private void awaitOverlayGrant() {
        overlayPending = true;
        pollHandler.removeCallbacks(pollOverlayGrant);
        pollHandler.postDelayed(pollOverlayGrant, 0);
        pollHandler.postDelayed(() -> {
            if (!overlayPending) return;
            overlayPending = false;
            ImageUtils.toast(this, getString(R.string.main_floating_need_permission_short));
        }, OVERLAY_GRANT_TIMEOUT_MS);
    }

    private void checkOverlayGrant() {
        if (!overlayPending) return;
        if (Prefs.hasOverlayPermission(this)) {
            overlayPending = false;
            pollHandler.removeCallbacks(pollOverlayGrant);
            enableFloating();
        } else {
            pollHandler.postDelayed(pollOverlayGrant, OVERLAY_GRANT_POLL_MS);
        }
    }

    /** True when the floating windows are actually on screen. */
    private boolean isFloatingRunning() {
        if (!Prefs.isFloatingEnabled(this)) return false;
        android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
        for (android.app.ActivityManager.RunningServiceInfo info
                : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (ScreenshotTranslateService.class.getName().equals(info.service.getClassName())
                    && info.pid == android.os.Process.myPid()) {
                return true;
            }
        }
        return false;
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
        // Drop a floating window left over from a previous process without
        // its capture session (it would otherwise show an unusable mask).
        // A grant made in the system settings a moment ago continues from
        // checkOverlayGrant(), not from another trip to the settings.
        if (overlayPending) return;
        // A windows-only session from a previous attempt is closed below,
        // unless a server also runs: that case keeps its service and the
        // window start replaces the windows.
        if (!Prefs.isServerEnabled(this)) ScreenshotTranslateService.stop(this);
        if (!Prefs.hasOverlayPermission(this)) {
            // Always say why first: jumping straight to the settings screen
            // leaves the user wondering what they are supposed to switch on.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.main_floating)
                    .setMessage(R.string.main_floating_need_permission)
                    .setPositiveButton(R.string.main_open_settings,
                            (dialog, which) -> startActivityForResult(
                                    Prefs.overlayPermissionIntent(this), REQUEST_OVERLAY))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !Prefs.hasAccessibilityService(this)) {
            // API 31+ captures through the accessibility service: enabled once
            // here, it can take screenshots at any time, even after the user
            // switches apps. Older releases use MediaProjection instead, which
            // asks when the floating windows start.
            new AlertDialog.Builder(this)
                    .setMessage(R.string.main_floating_need_accessibility)
                    .setPositiveButton(R.string.main_open_settings,
                            (dialog, which) -> startActivityForResult(
                                    new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                    REQUEST_ACCESSIBILITY))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        startFloating();
    }

    /**
     * Area or full-screen translation. The running service reads this on
     * every capture, so a change takes effect at once; the windows are
     * restarted only so the frame comes back (or goes) with it.
     */
    private void initFloatingMode() {
        RadioGroup group = findViewById(R.id.floatingMode);
        group.check(Prefs.isFloatingFullScreen(this)
                ? R.id.floatingModeFull : R.id.floatingModeArea);
        group.setOnCheckedChangeListener((g, checkedId) -> {
            Prefs.setFloatingMode(this, checkedId == R.id.floatingModeFull
                    ? Prefs.FLOATING_MODE_FULL : Prefs.FLOATING_MODE_AREA);
            // The running windows would still show the other mode's frame.
            if (!isFloatingRunning()) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // The restarted session needs a projection grant again.
                startFloating();
                return;
            }
            Prefs.setFloatingEnabled(this, true);
            try {
                ScreenshotTranslateService.enable(this);
            } catch (RuntimeException e) {
                Log.e("MainActivity", "floating restart failed", e);
            }
            updateFloatingButton();
        });
    }

    private void startFloating() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Below API 31 MediaProjection consent has to be asked for from
            // here (on one that has the accessibility service on, the service
            // takes the screenshots).
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            capturePending = true;
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
            return;
        }
        Prefs.setFloatingEnabled(this, true);
        try {
            ScreenshotTranslateService.enable(this);
            ImageUtils.toast(this, getString(R.string.main_floating_started));
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException: the system
            // refused the start instead of letting it crash the app.
            Prefs.setFloatingEnabled(this, false);
            Log.e("MainActivity", "floating start failed", e);
            ImageUtils.toast(this, getString(R.string.main_floating_start_failed,
                    ImageUtils.humanError(e)));
        }
        updateFloatingButton();
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
            boolean asked = capturePending;
            capturePending = false;
            if (resultCode == RESULT_OK && data != null) {
                Prefs.setFloatingEnabled(this, true);
                try {
                    ScreenshotTranslateService.show(this, resultCode, data);
                    ImageUtils.toast(this, getString(R.string.main_floating_started));
                } catch (RuntimeException e) {
                    Prefs.setFloatingEnabled(this, false);
                    Log.e("MainActivity", "floating start failed", e);
                    ImageUtils.toast(this, getString(R.string.main_floating_start_failed,
                            ImageUtils.humanError(e)));
                }
            } else if (asked) {
                ImageUtils.toast(this, getString(R.string.main_floating_cancelled));
            }
            updateFloatingButton();
            return;
        }
        if (requestCode == REQUEST_ACCESSIBILITY) {
            if (!Prefs.hasAccessibilityService(this)) return;
            enableFloating();
            return;
        }
        if (requestCode == REQUEST_OVERLAY) {
            awaitOverlayGrant();
            return;
        }
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != RESULT_OK || data == null) {
            return;
        }
        startTranslate(data);
    }

    /** Opens an image in the translation screen. */
    private void startTranslate(Intent data) {
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
