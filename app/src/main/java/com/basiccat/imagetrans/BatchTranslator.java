package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Synchronous translation pipeline used by folder translation.
 *
 * The single-image screen drives the same steps through its Activity, but a
 * batch needs a blocking call on a worker thread. OCR, server calls and
 * saving stay on that worker; the WebView renderer is still driven from the
 * main thread and awaited here.
 */
final class BatchTranslator {
    private static final String TAG = "BatchTranslator";
    private static final long RENDER_TIMEOUT_MS = 120_000;

    interface ProgressListener {
        /** A short status for the image currently being processed. */
        void onProgress(String message);
    }

    private final Context context;
    private final Renderer renderer;
    private final Handler mainHandler;
    private final ProgressListener progress;

    BatchTranslator(Context context, Renderer renderer, Handler mainHandler,
                    ProgressListener progress) {
        this.context = context;
        this.renderer = renderer;
        this.mainHandler = mainHandler;
        this.progress = progress;
    }

    /** Returns the translated image; the caller owns and recycles it. */
    Bitmap translate(Bitmap bitmap) throws Exception {
        String mode = Prefs.getMode(context);
        if (Prefs.MODE_IMAGETRANS.equals(mode)) {
            return translateWithServer(bitmap);
        }
        return translateLocally(bitmap, mode);
    }

    private Bitmap translateWithServer(Bitmap bitmap) throws Exception {
        progress.onProgress(context.getString(R.string.translate_status_server));
        ImageTransServer.Result result =
                ImageTransServer.translate(context, ImageUtils.bitmapToDataUrl(bitmap));

        // Same preference as the single-image screen: use the image the
        // server rendered unless "app rendering" is selected. Fall back to
        // the other response shape when one is missing.
        boolean preferServerImage = !Prefs.SERVER_RENDER_APP.equals(
                Prefs.getServerRenderMode(context));
        boolean useBoxes = preferServerImage
                ? result.renderedImageDataUrl == null && !result.boxes.isEmpty()
                : !result.boxes.isEmpty();
        if (useBoxes) {
            progress.onProgress(context.getString(R.string.translate_status_render));
            return renderBoxes(result.boxes, bitmap);
        }
        if (result.renderedImageDataUrl != null) {
            progress.onProgress(context.getString(R.string.translate_status_decode_server));
            Bitmap rendered = ImageUtils.dataUrlToBitmap(result.renderedImageDataUrl);
            if (rendered == null) {
                throw new Exception(context.getString(R.string.error_server_image_decode));
            }
            return rendered;
        }
        throw new Exception(context.getString(R.string.error_server_no_result));
    }

    private Bitmap translateLocally(Bitmap bitmap, String mode) throws Exception {
        String sourceLang = Prefs.getSourceLang(context);
        progress.onProgress(context.getString(R.string.translate_status_ocr));
        OcrEngine engine = OcrEngine.getInstance(context);
        List<Box> boxes = engine.recognize(bitmap, sourceLang,
                message -> progress.onProgress(message));
        if (boxes.isEmpty()) throw new Exception(context.getString(R.string.error_no_text));

        progress.onProgress(context.getString(R.string.translate_status_merge));
        boxes = engine.getLastBalloons().isEmpty()
                ? PanelMerger.merge(boxes, sourceLang, 15, 15, null)
                : PanelMerger.merge(boxes, engine.getLastBalloons(), sourceLang, 15, 15);

        if (Prefs.MODE_OPENAI.equals(mode) || Prefs.MODE_MYMEMORY.equals(mode)) {
            boolean mymemory = Prefs.MODE_MYMEMORY.equals(mode);
            progress.onProgress(context.getString(mymemory
                    ? R.string.translate_status_mymemory
                    : R.string.translate_status_openai));
            List<String> texts = new ArrayList<>();
            for (Box box : boxes) texts.add(box.source);
            List<String> translations = mymemory
                    ? MyMemoryTranslator.translate(context, texts)
                    : OpenAiTranslator.translate(context, texts);
            for (int i = 0; i < boxes.size() && i < translations.size(); i++) {
                boxes.get(i).target = translations.get(i);
            }
        } else {
            // Recognize-only: render the recognized text in place.
            for (Box box : boxes) box.target = box.source;
        }

        progress.onProgress(context.getString(R.string.translate_status_render));
        return renderBoxes(boxes, bitmap);
    }

    private Bitmap renderBoxes(List<Box> boxes, Bitmap bitmap) throws Exception {
        List<Box> renderable = new ArrayList<>();
        for (Box box : boxes) {
            if (box.target != null && !box.target.trim().isEmpty()) renderable.add(box);
        }
        if (renderable.isEmpty()) {
            throw new Exception(context.getString(R.string.error_no_renderable));
        }

        JSONObject options = ImageUtils.buildRenderOptions(context);
        if (RenderChunks.needsSplit(bitmap)) {
            return renderChunked(renderable, bitmap, options);
        }
        return renderSync(renderable, ImageUtils.bitmapToDataUrl(bitmap), options);
    }

    private Bitmap renderChunked(List<Box> boxes, Bitmap bitmap, JSONObject options)
            throws Exception {
        List<RenderChunks.Chunk> chunks = RenderChunks.split(bitmap, boxes);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Bitmap result;
        try {
            result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError error) {
            throw new Exception(context.getString(R.string.error_too_large));
        }
        Canvas canvas = new Canvas(result);
        try {
            for (int i = 0; i < chunks.size(); i++) {
                progress.onProgress(context.getString(R.string.translate_status_render_progress,
                        i + 1, chunks.size()));
                RenderChunks.Chunk chunk = chunks.get(i);
                Bitmap region = Bitmap.createBitmap(bitmap, 0, chunk.top, width, chunk.height);
                Bitmap rendered;
                try {
                    rendered = renderSync(chunk.boxes,
                            ImageUtils.bitmapToDataUrl(region), options);
                } finally {
                    region.recycle();
                }
                if (rendered.getWidth() != width || rendered.getHeight() != chunk.height) {
                    Log.w(TAG, "chunk size mismatch: expected " + width + "x" + chunk.height
                            + ", got " + rendered.getWidth() + "x" + rendered.getHeight());
                }
                canvas.drawBitmap(rendered, 0, chunk.top, null);
                rendered.recycle();
            }
        } catch (Throwable throwable) {
            result.recycle();
            throw throwable;
        }
        return result;
    }

    /** Renders one bitmap on the main-thread WebView and waits for it. */
    private Bitmap renderSync(List<Box> boxes, String imageDataUrl, JSONObject options)
            throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] output = new Bitmap[1];
        final String[] error = new String[1];
        mainHandler.post(() -> {
            try {
                renderer.render(boxes, imageDataUrl, options, new Renderer.Callback() {
                    @Override
                    public void onSuccess(Bitmap bitmap, String url) {
                        output[0] = bitmap;
                        latch.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        error[0] = message;
                        latch.countDown();
                    }
                });
            } catch (Throwable throwable) {
                error[0] = ImageUtils.humanError(throwable);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new Exception(context.getString(R.string.error_render_timeout));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception(context.getString(R.string.error_cancelled));
        }
        if (error[0] != null) throw new Exception(error[0]);
        if (output[0] == null) {
            throw new Exception(context.getString(R.string.error_render_empty));
        }
        return output[0];
    }
}
