package com.basiccat.imagetrans;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-image translation flow:
 *
 *   pick image -> (ImageTrans server OCR/translate) or (local OCR + OpenAI)
 *   -> render boxes with the WebView renderer -> show / save / share.
 */
public class TranslateActivity extends Activity {
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

        if (!result.boxes.isEmpty()) {
            status("WebView 渲染排版…");
            renderBoxes(result.boxes, imageDataUrl);
            return;
        }
        if (result.renderedImageDataUrl != null) {
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
        boolean yolo = OcrEngine.usesYoloDetection(sourceLang);
        status(yolo ? "本地 OCR：检测文本行（YOLO）…" : "本地 OCR 识别中…");
        OcrEngine engine = OcrEngine.getInstance(TranslateActivity.this);
        List<Box> boxes = engine.recognizeAndMerge(bitmap, sourceLang, yolo, 15, 15);
        if (boxes.isEmpty()) {
            throw new Exception("没有识别到文字。本地 OCR 使用中英日模型，其他语言请使用 ImageTrans 服务器模式。");
        }

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
            // "Original" mode: render the recognized text in place.
            for (Box box : boxes) {
                box.target = box.source;
            }
            if (!OcrEngine.usesYoloDetection(sourceLang)) {
                // No translation to show; layer the recognized text over the
                // image instead of replacing it with bare text.
                status("绘制识别结果…");
                drawRecognizedText(bitmap, boxes);
                return;
            }
        }

        status("WebView 渲染排版…");
        renderBoxes(boxes, ImageUtils.bitmapToDataUrl(bitmap));
    }

    /** Overlay of recognized text, matching the extension's "recognize only" output. */
    private void drawRecognizedText(final Bitmap bitmap, final List<Box> boxes) {
        final Bitmap output = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        final Canvas canvas = new Canvas(output);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.DEFAULT);
        for (Box box : boxes) {
            Rect bounds = new Rect();
            paint.setTextSize((float) Math.max(6, box.height * 0.8));
            paint.getTextBounds(box.source, 0, box.source.length(), bounds);
            float textWidth = Math.max(1, bounds.width());
            float textHeight = Math.max(1, bounds.height());
            float scale = Math.min((float) box.width / textWidth, (float) box.height / textHeight);
            canvas.save();
            canvas.translate((float) box.x, (float) box.y);
            canvas.scale(scale, scale);
            canvas.drawText(box.source,
                    (float) box.width / scale / 2 - textWidth / 2f - bounds.left,
                    (float) box.height / scale / 2 + textHeight / 2f - bounds.bottom,
                    paint);
            canvas.restore();
        }
        showResult(output);
    }

    private void renderBoxes(final List<Box> boxes, final String imageDataUrl) {
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

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject options = new JSONObject();
                    options.put("renderMode", "dom");
                    options.put("renderTextCSS", Prefs.getRenderCss(TranslateActivity.this));
                    options.put("minFontSize", Prefs.getMinFontSize(TranslateActivity.this));
                    options.put("textRepairMode", Prefs.getTextRepairMode(TranslateActivity.this));
                    options.put("sourceLang", Prefs.getSourceLang(TranslateActivity.this));
                    options.put("targetLang", Prefs.getTargetLang(TranslateActivity.this));

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
            message += "\n提示：手机访问电脑上的服务器时，请填写电脑的局域网 IP（不是 local.basiccat.org）。"
                    + "如果服务器使用自签名证书，请先在系统设置中安装证书，或改用 HTTP 地址。";
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
