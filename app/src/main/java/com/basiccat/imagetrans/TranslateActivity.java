package com.basiccat.imagetrans;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
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
public class TranslateActivity extends Activity {
    private static final String TAG = "TranslateActivity";

    private ImageView imageView;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_translate);

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

        imageUri = getIntent().getData();
        if (imageUri == null) {
            showError(new Exception("没有收到图片"));
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
        status("读取图片…");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap bitmap = ImageUtils.decodeBitmap(TranslateActivity.this, imageUri);
                    if (bitmap == null) {
                        throw new Exception("无法读取图片");
                    }
                    originalBitmap = bitmap;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            imageView.setImageBitmap(originalBitmap);
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
        status("正在请求 ImageTrans 服务器…");
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

        if (useBoxes) {
            status("WebView 渲染排版…");
            renderBoxes(result.boxes, bitmap);
            return;
        }
        if (result.renderedImageDataUrl != null) {
            status("显示 ImageTrans 渲染结果…");
            Bitmap rendered = ImageUtils.dataUrlToBitmap(result.renderedImageDataUrl);
            if (rendered == null) {
                throw new Exception("服务器返回的图片无法解码");
            }
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    showResult(rendered);
                }
            });
            return;
        }
        throw new Exception("服务器没有返回可用的结果");
    }

    private void translateLocally(Bitmap bitmap, String mode) throws Exception {
        String sourceLang = Prefs.getSourceLang(TranslateActivity.this);
        status(OcrEngine.usesYoloDetection(sourceLang)
                ? "本地 OCR：检测文本行（YOLO）…"
                : "本地 OCR 识别中…");
        OcrEngine engine = OcrEngine.getInstance(TranslateActivity.this);
        List<Box> boxes = engine.recognize(bitmap, sourceLang,
                new RemoteOcrModels.ProgressListener() {
                    @Override
                    public void onProgress(String message) {
                        status(message);
                    }
                });
        if (boxes.isEmpty()) {
            throw new Exception("没有识别到文字。本地 OCR 支持中、英、日、拉丁系语言，"
                    + "以及首次联网下载模型的韩、阿、俄、泰语；其他语言请使用 ImageTrans 服务器模式。");
        }

        // Same as doOCRYolo(): merge neighbouring lines into paragraphs before
        // translating/rendering.
        status("合并文本框…");
        boxes = TextBoxMerger.merge(boxes, sourceLang, 15, 15);

        if (Prefs.MODE_OPENAI.equals(mode)) {
            status("调用 OpenAI 兼容 API 翻译…");
            List<String> texts = new ArrayList<>();
            for (Box box : boxes) {
                texts.add(box.source);
            }
            List<String> translations = OpenAiTranslator.translate(TranslateActivity.this, texts);
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

        status("WebView 渲染排版…");
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
            postError(new Exception("没有可渲染的译文"));
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
        JSONObject options = new JSONObject();
        options.put("renderMode", "dom");
        options.put("renderTextCSS", Prefs.getRenderCss(this));
        options.put("minFontSize", Prefs.getMinFontSize(this));
        options.put("textRepairMode", Prefs.getTextRepairMode(this));
        options.put("sourceLang", Prefs.getSourceLang(this));
        options.put("targetLang", Prefs.getTargetLang(this));
        return options;
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
            postError(new Exception("图片过大，内存不足，无法渲染"));
            return;
        }
        Canvas canvas = new Canvas(result);

        for (int i = 0; i < chunks.size(); i++) {
            final RenderChunks.Chunk chunk = chunks.get(i);
            status("WebView 渲染排版…（" + (i + 1) + "/" + chunks.size() + "）");

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
                                error[0] = message == null ? "渲染失败" : message;
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
                    error[0] = "渲染超时";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error[0] = "渲染被中断";
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
                postError(new Exception("渲染结果为空"));
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
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                translatedBitmap = bitmap;
                showingOriginal = false;
                savedUri = null;
                imageView.setImageBitmap(bitmap);
                btnToggle.setText(R.string.translate_toggle_original);
                progressPanel.setVisibility(View.GONE);
                errorText.setVisibility(View.GONE);
                busy = false;
                setResultButtonsEnabled(true);
            }
        });
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
        String message = "处理失败：" + ImageUtils.humanError(throwable);
        if (throwable.getCause() != null) {
            message += "\n原因：" + throwable.getCause();
        }
        if (Prefs.MODE_IMAGETRANS.equals(Prefs.getMode(this))) {
            message += "\n提示：默认使用云端服务器 service.basiccat.org:51043；如需连接电脑上的服务器，"
                    + "请填写电脑的局域网 IP（不是 local.basiccat.org）。"
                    + "如果局域网服务器使用自签名证书，请先在系统设置中安装证书，或改用 HTTP 地址。";
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
        imageView.setImageBitmap(showingOriginal ? originalBitmap : translatedBitmap);
        btnToggle.setText(showingOriginal
                ? R.string.translate_toggle_translation
                : R.string.translate_toggle_original);
    }

    private void saveResult(final boolean share) {
        if (translatedBitmap == null || busy) return;
        final Bitmap bitmap = translatedBitmap;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final Uri uri = savedUri != null ? savedUri : ImageUtils.saveToGallery(TranslateActivity.this, bitmap);
                if (uri != null) savedUri = uri;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (uri == null) {
                            ImageUtils.toast(TranslateActivity.this, "保存失败");
                            return;
                        }
                        if (share) {
                            ImageUtils.shareImage(TranslateActivity.this, uri);
                        } else {
                            ImageUtils.toast(TranslateActivity.this, "已保存到相册");
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        imageView.setImageDrawable(null);
        if (renderer != null) {
            renderer.destroy();
            renderer = null;
        }
    }
}
