package com.basiccat.imagetrans;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photo translation: a camera preview with a draggable/resizable frame.
 * "Capture" grabs the frame currently shown in the preview, crops it to the
 * selection and translates it with the app's current settings. The
 * source/target pairs are shown in the same text-box dialog used by image
 * translation; the camera is unbound while the dialog is open and resumes
 * when it is closed.
 */
public class CameraTranslateActivity extends LocalizedActivity implements LifecycleOwner {
    private static final String TAG = "CameraTranslate";
    private static final int REQUEST_CAMERA_PERMISSION = 2001;

    private PreviewView previewView;
    private ViewfinderOverlay viewfinder;
    private View progressPanel;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button btnCapture;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService translationExecutor = Executors.newSingleThreadExecutor();
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);

    private ProcessCameraProvider cameraProvider;
    private boolean cameraBinding;
    private boolean cameraBound;
    /** The result dialog is up: the camera stays unbound until it closes. */
    private boolean dialogShowing;
    private boolean processing;

    private AlertDialog resultDialog;
    private final List<Box> textBoxes = new ArrayList<>();
    private int boxIndex;
    private TextView dialogSourceText;
    private TextView dialogTargetText;

    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewView = findViewById(R.id.cameraPreview);
        viewfinder = findViewById(R.id.viewfinder);
        progressPanel = findViewById(R.id.cameraProgress);
        progressBar = findViewById(R.id.cameraProgressBar);
        statusText = findViewById(R.id.cameraStatus);
        btnCapture = findViewById(R.id.btnCapture);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        // A TextureView lets getBitmap() read the displayed frame directly on
        // the main thread; no photo is taken by the camera pipeline.
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);

        btnCapture.setOnClickListener(v -> capturePhoto());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        previewView.getPreviewStreamState().observe(this, state -> updateCaptureButton());

        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
        // Also covers returning from the system settings page after granting
        // the camera permission.
        if (hasCameraPermission() && !dialogShowing) bindCamera();
    }

    @Override
    protected void onPause() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        super.onPause();
    }

    @Override
    protected void onStop() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        if (cameraProvider != null) cameraProvider.unbindAll();
        translationExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (resultDialog != null) {
            resultDialog.setOnDismissListener(null);
            resultDialog.dismiss();
            resultDialog = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA_PERMISSION) return;
        if (hasCameraPermission()) {
            bindCamera();
        } else {
            showPermissionDialog();
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.camera_need_permission)
                .setPositiveButton(R.string.camera_open_settings, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {
                        // No settings page: the user can grant it elsewhere.
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                .show();
    }

    // --- camera -----------------------------------------------------------

    private void bindCamera() {
        if (cameraBinding || cameraBound || dialogShowing
                || isFinishing() || isDestroyed()) {
            return;
        }
        if (!hasCameraPermission() || previewView == null
                || !lifecycleRegistry.getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            return;
        }
        cameraBinding = true;
        final ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            cameraBinding = false;
            ProcessCameraProvider provider;
            try {
                provider = future.get();
            } catch (Throwable throwable) {
                showCameraError(throwable);
                return;
            }
            if (isFinishing() || isDestroyed() || dialogShowing || !hasCameraPermission()
                    || !lifecycleRegistry.getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                return;
            }
            try {
                provider.unbindAll();
                Preview preview = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview);
                cameraProvider = provider;
                cameraBound = true;
                updateCaptureButton();
                if (!processing) hideProgress();
            } catch (Throwable throwable) {
                Log.e(TAG, "camera start failed", throwable);
                showCameraError(throwable);
            }
        }, mainHandler::post);
    }

    /** Unbinds the camera while the translation dialog is open. */
    private void pauseCamera() {
        dialogShowing = true;
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraBound = false;
        updateCaptureButton();
    }

    private void resumeCamera() {
        dialogShowing = false;
        if (!isFinishing() && !isDestroyed()) bindCamera();
    }

    // --- capture / translate ----------------------------------------------

    private void capturePhoto() {
        if (processing || dialogShowing || !cameraBound) return;
        if (previewView.getPreviewStreamState().getValue()
                != PreviewView.StreamState.STREAMING) {
            onProcessingError(getString(R.string.camera_frame_failed));
            return;
        }
        processing = true;
        showProgress(getString(R.string.camera_capturing));
        // Grab the frame that is on screen instead of taking a photo. The
        // bitmap is already rotated and fitted to this view (the preview is
        // FIT_CENTER), so the selection frame maps directly onto it.
        Bitmap frame;
        try {
            frame = previewView.getBitmap();
        } catch (Throwable throwable) {
            onProcessingError(getString(R.string.camera_capture_failed,
                    ImageUtils.humanError(throwable)));
            return;
        }
        if (frame == null || frame.getWidth() == 0 || frame.getHeight() == 0) {
            onProcessingError(getString(R.string.camera_frame_failed));
            return;
        }
        cropAndTranslate(frame);
    }

    private void cropAndTranslate(Bitmap frame) {
        if (isFinishing() || isDestroyed()) {
            frame.recycle();
            return;
        }
        Rect crop = cropRect(frame.getWidth(), frame.getHeight());
        if (crop == null) {
            frame.recycle();
            onProcessingError(getString(R.string.camera_crop_failed));
            return;
        }
        Bitmap cropped;
        try {
            cropped = Bitmap.createBitmap(frame, crop.left, crop.top,
                    crop.width(), crop.height());
        } catch (Throwable throwable) {
            frame.recycle();
            onProcessingError(getString(R.string.camera_capture_failed,
                    ImageUtils.humanError(throwable)));
            return;
        }
        if (cropped != frame) frame.recycle();

        showProgress(getString(R.string.camera_translating));
        translationExecutor.execute(() -> {
            try {
                ImageUtils.Result result =
                        ImageUtils.detectAndTranslate(CameraTranslateActivity.this, cropped);
                if (result == null || result.boxes.isEmpty()) {
                    throw new Exception(getString(R.string.camera_no_text));
                }
                final List<Box> boxes = result.boxes;
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    processing = false;
                    hideProgress();
                    showResults(boxes);
                });
            } catch (Throwable throwable) {
                final String message = throwable.getMessage() != null
                        && throwable.getMessage().equals(getString(R.string.error_no_text))
                        ? getString(R.string.camera_no_text)
                        : getString(R.string.camera_translate_failed,
                                ImageUtils.humanError(throwable));
                mainHandler.post(() -> onProcessingError(message));
            } finally {
                cropped.recycle();
            }
        });
    }

    /**
     * Maps the frame from the preview view onto the grabbed frame. The frame
     * bitmap is the size of the PreviewView, but scale anyway so the mapping
     * does not depend on the implementation's bitmap size.
     */
    private Rect cropRect(int frameWidth, int frameHeight) {
        int viewWidth = previewView.getWidth();
        int viewHeight = previewView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return null;

        float scaleX = frameWidth / (float) viewWidth;
        float scaleY = frameHeight / (float) viewHeight;
        RectF frame = viewfinder.getFrameRect();
        int l = Math.max(0, Math.min(Math.round(frame.left * scaleX), frameWidth - 1));
        int t = Math.max(0, Math.min(Math.round(frame.top * scaleY), frameHeight - 1));
        int r = Math.max(l + 1, Math.min(Math.round(frame.right * scaleX), frameWidth));
        int b = Math.max(t + 1, Math.min(Math.round(frame.bottom * scaleY), frameHeight));
        if (r - l < 16 || b - t < 16) return null;
        return new Rect(l, t, r, b);
    }

    // --- result dialog ----------------------------------------------------

    private void showResults(List<Box> boxes) {
        textBoxes.clear();
        for (Box box : boxes) {
            if (box.hasText()) textBoxes.add(box);
        }
        if (textBoxes.isEmpty()) {
            onProcessingError(getString(R.string.camera_no_text));
            return;
        }
        boxIndex = 0;
        // The camera is not needed while the translation is being read.
        pauseCamera();
        showTextBoxDialog();
    }

    /**
     * The same source/target dialog as image translation. Previous/next
     * update this dialog in place, so the camera is paused only once.
     */
    private void showTextBoxDialog() {
        if (textBoxes.isEmpty()) return;
        if (resultDialog == null) {
            View content = getLayoutInflater().inflate(R.layout.dialog_text_box, null);
            dialogSourceText = content.findViewById(R.id.sourceText);
            dialogTargetText = content.findViewById(R.id.targetText);
            enableSelection(dialogSourceText);
            enableSelection(dialogTargetText);
            dialogSourceText.setOnClickListener(this::copyText);
            dialogTargetText.setOnClickListener(this::copyText);

            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setView(content)
                    .setPositiveButton(R.string.action_close, null);
            if (textBoxes.size() > 1) {
                // Listeners are installed on the buttons after show(), so
                // clicking them does not dismiss the dialog.
                builder.setNegativeButton(R.string.action_previous, null);
                builder.setNeutralButton(R.string.action_next, null);
            }
            resultDialog = builder.create();
            resultDialog.setOnDismissListener(dialog -> {
                resultDialog = null;
                dialogSourceText = null;
                dialogTargetText = null;
                resumeCamera();
            });
            updateTextBoxDialog();
            resultDialog.show();
            if (textBoxes.size() > 1) {
                resultDialog.getButton(DialogInterface.BUTTON_NEGATIVE)
                        .setOnClickListener(v -> {
                            boxIndex = (boxIndex - 1 + textBoxes.size()) % textBoxes.size();
                            updateTextBoxDialog();
                        });
                resultDialog.getButton(DialogInterface.BUTTON_NEUTRAL)
                        .setOnClickListener(v -> {
                            boxIndex = (boxIndex + 1) % textBoxes.size();
                            updateTextBoxDialog();
                        });
            }
            return;
        }
        updateTextBoxDialog();
    }

    private void updateTextBoxDialog() {
        if (resultDialog == null || textBoxes.isEmpty()) return;
        Box box = textBoxes.get(boxIndex);
        resultDialog.setTitle(getString(R.string.text_box_title,
                boxIndex + 1, textBoxes.size()));
        dialogSourceText.setText(emptyIfBlank(box.source));
        dialogTargetText.setText(emptyIfBlank(box.target));
    }

    private static void enableSelection(TextView view) {
        // Not focusable, so a long press only selects (no keyboard).
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setTextIsSelectable(true);
    }

    private void copyText(View view) {
        CharSequence text = ((TextView) view).getText();
        if (text == null || text.length() == 0) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("text", text));
        if (Build.VERSION.SDK_INT < 33) {
            ImageUtils.toast(this, getString(R.string.copied));
        }
    }

    private String emptyIfBlank(String text) {
        return text == null || text.trim().isEmpty()
                ? getString(R.string.text_box_empty) : text;
    }

    // --- status -----------------------------------------------------------

    private void showProgress(String message) {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText(message);
        progressPanel.setVisibility(View.VISIBLE);
        updateCaptureButton();
    }

    private void hideProgress() {
        progressPanel.setVisibility(View.GONE);
        updateCaptureButton();
    }

    private void updateCaptureButton() {
        boolean streaming = previewView.getPreviewStreamState().getValue()
                == PreviewView.StreamState.STREAMING;
        btnCapture.setEnabled(cameraBound && !processing && !dialogShowing && streaming);
    }

    private void onProcessingError(String message) {
        if (isFinishing() || isDestroyed()) return;
        processing = false;
        hideProgress();
        ImageUtils.toast(this, message);
    }

    private void showCameraError(Throwable throwable) {
        showCameraError(getString(R.string.camera_start_failed,
                ImageUtils.humanError(throwable)));
    }

    private void showCameraError(String message) {
        if (isFinishing() || isDestroyed()) return;
        processing = false;
        cameraBound = false;
        updateCaptureButton();
        progressBar.setVisibility(View.GONE);
        statusText.setText(message);
        progressPanel.setVisibility(View.VISIBLE);
    }
}
