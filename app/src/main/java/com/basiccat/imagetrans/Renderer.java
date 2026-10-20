package com.basiccat.imagetrans;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts assets/render.html in a WebView and hands the image + boxes to the
 * extension's rendering logic. The resulting PNG/WebP is returned through a
 * JavascriptInterface.
 */
public class Renderer {
    private static final String TAG = "Renderer";
    private static final long TIMEOUT_MS = 90000L;

    public interface Callback {
        void onSuccess(Bitmap bitmap, String dataUrl);

        void onError(String message);
    }

    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();

    private boolean ready;
    private boolean destroyed;
    private JSONObject pendingPayload;
    private Callback pendingCallback;
    private Runnable timeoutRunnable;

    @SuppressLint("SetJavaScriptEnabled")
    public Renderer(WebView webView) {
        this.webView = webView;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDomStorageEnabled(true);
        webView.setBackgroundColor(0x00000000);
        webView.addJavascriptInterface(new Bridge(), "AndroidRenderer");
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                Log.i(TAG, "console: " + message.message() + " (" + message.lineNumber() + ")");
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                ready = true;
                flushPending();
            }
        });
        webView.loadUrl("file:///android_asset/render.html");
    }

    public void render(List<Box> boxes, String imageDataUrl, JSONObject options, Callback callback) {
        if (destroyed) {
            callback.onError("渲染器已关闭");
            return;
        }
        if (pendingCallback != null) {
            callback.onError("上一张图片仍在渲染中");
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (Box box : boxes) {
                array.put(box.toJson());
            }
            JSONObject payload = new JSONObject();
            payload.put("image", imageDataUrl);
            payload.put("boxes", array);
            payload.put("options", options);

            pendingPayload = payload;
            pendingCallback = callback;
            startTimeout();
            if (ready) {
                flushPending();
            }
        } catch (Exception e) {
            callback.onError("构建渲染请求失败: " + ImageUtils.humanError(e));
        }
    }

    private void flushPending() {
        if (!ready || destroyed || pendingPayload == null) return;
        JSONObject payload = pendingPayload;
        pendingPayload = null;
        String script = "window.renderTranslation(" + payload.toString() + ");";
        webView.evaluateJavascript(script, null);
    }

    private void startTimeout() {
        cancelTimeout();
        timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                Callback callback = pendingCallback;
                pendingCallback = null;
                pendingPayload = null;
                if (callback != null) {
                    callback.onError("渲染超时，请尝试较小尺寸的图片");
                }
            }
        };
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS);
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void deliverResult(String dataUrl) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cancelTimeout();
                final Callback callback = pendingCallback;
                pendingCallback = null;
                if (callback == null) return;
                decodeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        final Bitmap bitmap = ImageUtils.dataUrlToBitmap(dataUrl);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (bitmap == null) {
                                    callback.onError("无法解码渲染结果");
                                } else {
                                    callback.onSuccess(bitmap, dataUrl);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    private void deliverError(String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cancelTimeout();
                Callback callback = pendingCallback;
                pendingCallback = null;
                pendingPayload = null;
                if (callback != null) {
                    callback.onError(message == null ? "渲染失败" : message);
                }
            }
        });
    }

    public void destroy() {
        destroyed = true;
        cancelTimeout();
        decodeExecutor.shutdownNow();
        try {
            webView.removeJavascriptInterface("AndroidRenderer");
        } catch (Exception e) {
            Log.w(TAG, "removeJavascriptInterface failed", e);
        }
        webView.destroy();
    }

    private class Bridge {
        @JavascriptInterface
        public void onRenderResult(String dataUrl) {
            deliverResult(dataUrl);
        }

        @JavascriptInterface
        public void onRenderError(String message) {
            deliverError(message);
        }
    }
}
