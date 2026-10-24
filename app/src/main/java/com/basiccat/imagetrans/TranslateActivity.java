package com.basiccat.imagetrans;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Single-image translation flow:
 *
 *   pick image -> (ImageTrans server OCR/translate) or (local OCR + OpenAI)
 *   -> render boxes with the WebView renderer -> show / save / share.
 */
public class TranslateActivity extends LocalizedActivity {
    private static final String TAG = "TranslateActivity";
    private static final int REQUEST_STORAGE_PERMISSION = 3001;

    private ZoomableImageView imageView;
    private TextView errorText;
    private TextView statusText;
    private TextView btnToggle;
    private TextView btnBack;
    private LinearLayout progressPanel;
    private ProgressBar progressBar;
    private Button btnSave;
    private Button btnShare;
    private WebView renderWebView;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Renderer renderer;

    private Uri imageUri;
    private Bitmap originalBitmap;
    private Bitmap translatedBitmap;
    private Uri savedUri;
    private boolean showingOriginal;
    private boolean busy;

    /**
     * Text boxes hit-testable on the shown image, in image coordinates: the
     * OCR / server result if there is one, otherwise boxes read back from the
     * renderer's layout (see {@link #fetchRenderedBoxes}).
     */
    private List<Box> hitBoxes = new ArrayList<>();
    /** The Source/target dialog, kept open while a box after another shows. */
    private AlertDialog resultDialog;
    private TextView dialogSourceText;
    private TextView dialogTargetText;
    private int textBoxIndex;
    private List<Box> pendingHitBoxes;
    private boolean fetchingHitBoxes;
    private long lastTapAt;
    private float lastTapX, lastTapY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_translate);
        applySystemBarInsets(R.id.appBar, false);

        imageView = findViewById(R.id.imageView);
        errorText = findViewById(R.id.errorText);
        statusText = findViewById(R.id.statusText);
        btnToggle = findViewById(R.id.btnToggle);
        btnBack = findViewById(R.id.btnBack);
        progressPanel = findViewById(R.id.progressPanel);
        progressBar = findViewById(R.id.progressBar);
        btnSave = findViewById(R.id.btnSave);
        btnShare = findViewById(R.id.btnShare);
        renderWebView = findViewById(R.id.renderWebView);

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOriginal();
            }
        });
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveResult(false);
            }
        });
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveResult(true);
            }
        });

        // At the fitted scale, swiping sideways flips original/translation.
        // Zoomed-in images use horizontal drags for panning instead.
        imageView.setSwipeListener(new ZoomableImageView.OnSwipeListener() {
            @Override
            public void onSwipe(boolean towardLeft) {
                toggleOriginal();
            }
        });

        // Tapping a translated text box shows its source and target text so
        // it can be selected and copied.
        imageView.setTapListener(new ZoomableImageView.OnTapListener() {
            @Override
            public void onTap(float x, float y) {
                onImageTap(x, y);
            }
        });

        imageUri = getIntent().getData();
        if (imageUri == null) {
            showError(new Exception(getString(R.string.error_no_image_received)));
            return;
        }
        startTranslation();
    }

    private void startTranslation() {
        busy = true;
        errorText.setVisibility(View.GONE);
        progressPanel.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        setResultButtonsEnabled(false);
        status(getString(R.string.translate_status_reading));

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap bitmap = ImageUtils.decodeBitmap(TranslateActivity.this, imageUri);
                    if (bitmap == null) {
                        throw new Exception(getString(R.string.error_cannot_read_image));
                    }
                    originalBitmap = bitmap;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            imageView.setImage(originalBitmap);
                        }
                    });

                    String mode = Prefs.getMode(TranslateActivity.this);
                    if (Prefs.MODE_IMAGETRANS.equals(mode)) {
                        translateWithServer(bitmap);
                    } else {
                        translateLocally(bitmap, mode);
                    }
                } catch (Throwable throwable) {
                    postError(throwable);
                }
            }
        });
    }

    private void translateWithServer(Bitmap bitmap) throws Exception {
        status(getString(R.string.translate_status_server));
        String imageDataUrl = ImageUtils.bitmapToDataUrl(bitmap);
        ImageTransServer.Result result = ImageTransServer.translate(TranslateActivity.this, imageDataUrl);

        // Default: show the image ImageTrans rendered (its project's layout).
        // "App rendering" instead prefers the returned boxes and lays them out
        // with the app's own CSS/font settings. Either way, fall back to the
        // other response shape if the preferred one is missing.
        boolean preferServerImage = !Prefs.SERVER_RENDER_APP.equals(
                Prefs.getServerRenderMode(TranslateActivity.this));
        boolean useBoxes = preferServerImage
                ? result.renderedImageDataUrl == null && !result.boxes.isEmpty()
                : !result.boxes.isEmpty();
        if (!useBoxes) pendingHitBoxes = result.boxes;

        if (useBoxes) {
            status(getString(R.string.translate_status_render));
            renderBoxes(result.boxes, bitmap);
            return;
        }
        if (result.renderedImageDataUrl != null) {
            status(getString(R.string.translate_status_show_server));
            // No boxes in the response: read them back from the renderer's
            // layout so text boxes are still tappable.
            fetchRenderedBoxes(bitmap, result.renderedImageDataUrl);
            Bitmap rendered = ImageUtils.dataUrlToBitmap(result.renderedImageDataUrl);
            if (rendered == null) {
                throw new Exception(getString(R.string.error_server_image_decode));
            }
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    showResult(rendered);
                }
            });
            return;
        }
        throw new Exception(getString(R.string.error_server_no_result));
    }

    private void translateLocally(Bitmap bitmap, String mode) throws Exception {
        String sourceLang = Prefs.getSourceLang(TranslateActivity.this);
        status(getString(OcrEngine.usesYoloDetection(TranslateActivity.this, sourceLang)
                ? R.string.translate_status_ocr_yolo
                : R.string.translate_status_ocr));
        OcrEngine engine = OcrEngine.getInstance(TranslateActivity.this);
        List<Box> boxes = engine.recognize(bitmap, sourceLang,
                new RemoteOcrModels.ProgressListener() {
                    @Override
                    public void onProgress(String message) {
                        status(message);
                    }
                });
        if (boxes.isEmpty()) {
            throw new Exception(getString(R.string.error_no_text_local_support));
        }

        // Merge neighbouring lines into paragraphs before
        // translating/rendering, grouped by panels (comics: one speech
        // balloon each; the panels are estimated from the lines).
        status(getString(R.string.translate_status_merge));
        // Japanese uses YOLO, whose manga class gives the balloons directly;
        // otherwise the panels are estimated from the lines.
        List<Box> balloons = engine.getLastBalloons();
        boxes = balloons.isEmpty()
                ? PanelMerger.merge(boxes, sourceLang, 15, 15, null)
                : PanelMerger.merge(boxes, balloons, sourceLang, 15, 15);

        if (Prefs.MODE_OPENAI.equals(mode) || Prefs.MODE_MYMEMORY.equals(mode)) {
            boolean mymemory = Prefs.MODE_MYMEMORY.equals(mode);
            status(getString(mymemory ? R.string.translate_status_mymemory
                    : R.string.translate_status_openai));
            List<String> texts = new ArrayList<>();
            for (Box box : boxes) {
                texts.add(box.source);
            }
            List<String> translations = mymemory
                    ? MyMemoryTranslator.translate(TranslateActivity.this, texts)
                    : OpenAiTranslator.translate(TranslateActivity.this, texts);
            for (int i = 0; i < boxes.size() && i < translations.size(); i++) {
                boxes.get(i).target = translations.get(i);
            }
        } else {
            // "Original" mode: render the recognized text in place, exactly like
            // the extension's "recognize only" output (white background boxes
            // over the original text, new text on top).
            for (Box box : boxes) {
                box.target = box.source;
            }
        }

        status(getString(R.string.translate_status_render));
        pendingHitBoxes = boxes;
        renderBoxes(boxes, bitmap);
    }

    private void renderBoxes(final List<Box> boxes, final Bitmap bitmap) {
        final List<Box> renderable = new ArrayList<>();
        for (Box box : boxes) {
            if (box.target != null && !box.target.trim().isEmpty()) {
                renderable.add(box);
            }
        }
        if (renderable.isEmpty()) {
            postError(new Exception(getString(R.string.error_no_renderable)));
            return;
        }

        final JSONObject options;
        try {
            options = buildRenderOptions();
        } catch (Throwable throwable) {
            postError(throwable);
            return;
        }

        if (RenderChunks.needsSplit(bitmap)) {
            renderChunked(renderable, bitmap, options);
            return;
        }

        final String imageDataUrl = ImageUtils.bitmapToDataUrl(bitmap);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureRenderer();
                    renderer.render(renderable, imageDataUrl, options, new Renderer.Callback() {
                        @Override
                        public void onSuccess(Bitmap bitmap, String dataUrl) {
                            showResult(bitmap);
                        }

                        @Override
                        public void onError(String message) {
                            postError(new Exception(message));
                        }
                    });
                } catch (Throwable throwable) {
                    postError(throwable);
                }
            }
        });
    }

    private JSONObject buildRenderOptions() throws Exception {
        return ImageUtils.buildRenderOptions(this);
    }

    /**
     * Renders a webtoon-sized image one vertical chunk at a time, then stitches
     * the chunks into a full-size bitmap. A single offscreen WebView canvas of
     * a 10000+ px tall strip exceeds the WebView/GPU canvas limits, so the
     * extension's whole-image render cannot be used here.
     */
    private void renderChunked(final List<Box> renderable, final Bitmap bitmap,
                               final JSONObject options) {
        final List<RenderChunks.Chunk> chunks = RenderChunks.split(bitmap, renderable);
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final Bitmap result;
        try {
            result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError error) {
            postError(new Exception(getString(R.string.error_too_large)));
            return;
        }
        Canvas canvas = new Canvas(result);

        for (int i = 0; i < chunks.size(); i++) {
            final RenderChunks.Chunk chunk = chunks.get(i);
            status(getString(R.string.translate_status_render_progress,
                    i + 1, chunks.size()));

            Bitmap region = Bitmap.createBitmap(bitmap, 0, chunk.top, width, chunk.height);
            final String dataUrl = ImageUtils.bitmapToDataUrl(region);
            region.recycle();

            final CountDownLatch latch = new CountDownLatch(1);
            final Bitmap[] rendered = new Bitmap[1];
            final String[] error = new String[1];
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        ensureRenderer();
                        renderer.render(chunk.boxes, dataUrl, options, new Renderer.Callback() {
                            @Override
                            public void onSuccess(Bitmap chunkBitmap, String url) {
                                rendered[0] = chunkBitmap;
                                latch.countDown();
                            }

                            @Override
                            public void onError(String message) {
                                error[0] = message == null
                                        ? getString(R.string.error_render_failed) : message;
                                latch.countDown();
                            }
                        });
                    } catch (Throwable throwable) {
                        error[0] = ImageUtils.humanError(throwable);
                        latch.countDown();
                    }
                }
            });

            try {
                if (!latch.await(120, TimeUnit.SECONDS)) {
                    error[0] = getString(R.string.error_render_timeout);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error[0] = getString(R.string.error_render_interrupted);
            }
            if (error[0] != null) {
                if (rendered[0] != null) rendered[0].recycle();
                result.recycle();
                postError(new Exception(error[0]));
                return;
            }
            Bitmap chunkBitmap = rendered[0];
            if (chunkBitmap == null) {
                result.recycle();
                postError(new Exception(getString(R.string.error_render_empty)));
                return;
            }
            if (chunkBitmap.getWidth() != width || chunkBitmap.getHeight() != chunk.height) {
                Log.w(TAG, "chunk size mismatch: expected " + width + "x" + chunk.height
                        + ", got " + chunkBitmap.getWidth() + "x" + chunkBitmap.getHeight());
            }
            canvas.drawBitmap(chunkBitmap, 0, chunk.top, null);
            chunkBitmap.recycle();
        }
        showResult(result);
    }

    private void ensureRenderer() {
        if (renderer == null) {
            renderer = new Renderer(renderWebView);
        }
    }

    private void showResult(Bitmap bitmap) {
        final List<Box> boxes = pendingHitBoxes;
        pendingHitBoxes = null;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                translatedBitmap = bitmap;
                showingOriginal = false;
                savedUri = null;
                hitBoxes = boxes != null ? boxes : new ArrayList<Box>();
                imageView.setImage(bitmap);
                btnToggle.setText(R.string.translate_toggle_original);
                progressPanel.setVisibility(View.GONE);
                errorText.setVisibility(View.GONE);
                busy = false;
                setResultButtonsEnabled(true);
            }
        });
    }

    // --- tappable text boxes --------------------------------------------

    /**
     * Re-renders the source / server-rendered image through the app's own
     * renderer to recover the laid-out box positions (the rendered result is
     * discarded). Used when the server returns only an image and no boxes.
     */
    private void fetchRenderedBoxes(final Bitmap original, final String renderedDataUrl) {
        fetchingHitBoxes = true;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Bitmap rendered = null;
                try {
                    ensureRenderer();
                    rendered = ImageUtils.dataUrlToBitmap(renderedDataUrl);
                    if (rendered == null) {
                        throw new Exception(getString(R.string.error_server_image_decode));
                    }
                    renderer.render(pendingHitBoxes, ImageUtils.bitmapToDataUrl(original),
                            buildRenderOptions(), new Renderer.Callback() {
                                @Override
                                public void onSuccess(Bitmap bitmap, String dataUrl) {
                                    if (bitmap != translatedBitmap) bitmap.recycle();
                                    finishFetch();
                                }

                                @Override
                                public void onError(String message) {
                                    Log.w(TAG, "hit boxes render failed: " + message);
                                    finishFetch();
                                }
                            });
                } catch (Throwable throwable) {
                    Log.w(TAG, "hit boxes unavailable: " + throwable);
                    if (rendered != null) rendered.recycle();
                    finishFetch();
                }
            }
        });
    }

    private void finishFetch() {
        fetchingHitBoxes = false;
        if (pendingHitBoxes != null) {
            hitBoxes = pendingHitBoxes;
            pendingHitBoxes = null;
        }
    }

    private void onImageTap(float x, float y) {
        if (busy || fetchingHitBoxes || hitBoxes.isEmpty()) return;
        // The first tap of a double tap zooms instead; drop a second quick tap
        // near it so the double-tap zoom does not also pop the dialog.
        long now = System.currentTimeMillis();
        float slop = 48 * getResources().getDisplayMetrics().density;
        if (now - lastTapAt < 350 && Math.abs(x - lastTapX) < slop && Math.abs(y - lastTapY) < slop) {
            lastTapAt = 0;
            return;
        }
        lastTapAt = now;
        lastTapX = x;
        lastTapY = y;

        float[] point = imageView.viewToImage(x, y);
        int index = findBox(point[0], point[1]);
        if (index >= 0) showTextBoxDialog(index);
    }

    /**
     * Finds the box under an image coordinate. Small boxes get a finger-sized
     * minimum hit area; when several overlap, the smallest wins.
     */
    private int findBox(float x, float y) {
        float scale = Math.max(imageView.getImageScale(), 0.01f);
        float minSize = 44 * getResources().getDisplayMetrics().density / scale;
        int best = -1;
        double bestArea = Double.MAX_VALUE;
        for (int i = 0; i < hitBoxes.size(); i++) {
            Box box = hitBoxes.get(i);
            if (!box.hasText()) continue;
            float growX = Math.max(0, (minSize - (float) box.width) / 2f);
            float growY = Math.max(0, (minSize - (float) box.height) / 2f);
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
        return best;
    }

    private void showTextBoxDialog(final int index) {
        if (resultDialog != null && resultDialog.isShowing()) {
            // Already open: show the next box's text in the same dialog.
            textBoxIndex = index;
            updateTextBoxDialog();
            return;
        }
        textBoxIndex = index;
        View content = getLayoutInflater().inflate(R.layout.dialog_text_box, null);
        dialogSourceText = content.findViewById(R.id.sourceText);
        dialogTargetText = content.findViewById(R.id.targetText);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                // The title is set here too: setTitle() alone before show()
                // left the title off on some releases.
                .setTitle(getString(R.string.text_box_title, index + 1, hitBoxes.size()))
                .setView(content)
                .setPositiveButton(R.string.action_close, null);
        if (hitBoxes.size() > 1) {
            // The listeners are set after show(), so a click does not
            // dismiss the dialog: the next box's text replaces this one's.
            builder.setNegativeButton(R.string.action_previous, null);
            builder.setNeutralButton(R.string.action_next, null);
        }
        // Selectable (long press to copy part of it); a tap copies all.
        enableSelection(dialogSourceText);
        enableSelection(dialogTargetText);
        dialogSourceText.setOnClickListener(this::copyText);
        dialogTargetText.setOnClickListener(this::copyText);
        resultDialog = builder.create();
        resultDialog.setOnDismissListener(dialog -> {
            resultDialog = null;
            dialogSourceText = null;
            dialogTargetText = null;
        });
        resultDialog.show();
        if (hitBoxes.size() > 1) {
            resultDialog.getButton(DialogInterface.BUTTON_NEGATIVE)
                    .setOnClickListener(v -> {
                        textBoxIndex = (textBoxIndex - 1 + hitBoxes.size()) % hitBoxes.size();
                        updateTextBoxDialog();
                    });
            resultDialog.getButton(DialogInterface.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> {
                        textBoxIndex = (textBoxIndex + 1) % hitBoxes.size();
                        updateTextBoxDialog();
                    });
        }
        updateTextBoxDialog();
    }

    /** The open dialog's title and texts, for the box at textBoxIndex. */
    private void updateTextBoxDialog() {
        if (resultDialog == null || dialogSourceText == null || hitBoxes.isEmpty()) return;
        int index = Math.max(0, Math.min(textBoxIndex, hitBoxes.size() - 1));
        Box box = hitBoxes.get(index);
        resultDialog.setTitle(getString(R.string.text_box_title, index + 1, hitBoxes.size()));
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
        return text == null || text.trim().isEmpty() ? getString(R.string.text_box_empty) : text;
    }

    private void postError(Throwable throwable) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                showError(throwable);
            }
        });
    }

    private void showError(Throwable throwable) {
        busy = false;
        progressPanel.setVisibility(View.GONE);
        errorText.setVisibility(View.VISIBLE);
        String message = getString(R.string.translate_error_prefix,
                ImageUtils.humanError(throwable));
        if (throwable.getCause() != null) {
            message += "\n" + getString(R.string.translate_error_cause, throwable.getCause());
        }
        if (Prefs.MODE_IMAGETRANS.equals(Prefs.getMode(this))) {
            message += "\n" + getString(R.string.translate_error_hint);
        }
        errorText.setText(message);
        setResultButtonsEnabled(translatedBitmap != null);
    }

    private void status(String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                statusText.setText(text);
            }
        });
    }

    private void setResultButtonsEnabled(boolean enabled) {
        btnToggle.setEnabled(enabled);
        btnSave.setEnabled(enabled);
        btnShare.setEnabled(enabled);
    }

    private void toggleOriginal() {
        if (translatedBitmap == null || originalBitmap == null) return;
        showingOriginal = !showingOriginal;
        imageView.setImage(showingOriginal ? originalBitmap : translatedBitmap);
        btnToggle.setText(showingOriginal
                ? R.string.translate_toggle_translation
                : R.string.translate_toggle_original);
    }

    private void saveResult(final boolean share) {
        if (translatedBitmap == null || busy) return;
        if (!share && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // API 26-28 write into Pictures/ directly and need this grant.
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
            return;
        }
        final Bitmap bitmap = translatedBitmap;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // Sharing only needs a readable file; saving means the gallery,
                // so it must still be written there on every version.
                final Uri uri = share && savedUri != null
                        ? savedUri
                        : (share
                                ? ImageUtils.saveToCache(TranslateActivity.this, bitmap)
                                : ImageUtils.saveToGallery(TranslateActivity.this, bitmap));
                if (!share && uri != null) savedUri = uri;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (uri == null) {
                            ImageUtils.toast(TranslateActivity.this,
                                    getString(R.string.translate_save_failed));
                            return;
                        }
                        if (share) {
                            ImageUtils.shareImage(TranslateActivity.this, uri);
                        } else {
                            ImageUtils.toast(TranslateActivity.this,
                                    getString(R.string.translate_saved));
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_STORAGE_PERMISSION) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            saveResult(false);
        } else {
            ImageUtils.toast(this, getString(R.string.translate_save_failed));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        imageView.setImage(null);
        if (renderer != null) {
            renderer.destroy();
            renderer = null;
        }
    }
}
