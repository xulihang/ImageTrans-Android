package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * YOLO text-line detection (the extension's paddleocr/model.onnx, used by
 * runYOLO in page-ocr.js). Used for Japanese, where the DB detector in
 * PP-OCRv6 struggles with the vertical layout and the YOLO model's manga
 * class (5, dropped in the extension as well) keeps non-text art out.
 *
 * Pre/post-processing is the same as the extension: 640x640 letterbox with
 * gray (114) padding, NCHW RGB in [0, 1], output [1, 4 + classes, anchors] in
 * xywh with boxes relative to the input size, per-class NMS.
 */
final class PaddleTextLineDetector extends NativeInference {
    private static final String TAG = "PaddleTextLineDetector";
    private static final String MODEL = MODEL_DIR + "model.onnx";
    private static final int INPUT_SIZE = 640;
    private static final int PAD_COLOR = 114;
    private static final float CONF_THRESHOLD = 0.25f;
    private static final float NMS_THRESHOLD = 0.45f;
    private static final int MANGA_CLASS = 5;

    private io.github.hzkitty.utils.OrtInferSession session;

    /** {@code modelPath} comes from NativeInference.ensureModelFile. */
    PaddleTextLineDetector(Context context, String modelPath) {
        super(context);
        this.modelPath = modelPath;
    }

    private String modelPath;

    synchronized void initialize() throws Exception {
        if (session != null) return;
        long start = System.currentTimeMillis();
        try {
            // rapidocr4j's OrtInferSession only opens absolute file paths.
            io.github.hzkitty.entity.OrtInferConfig config = new io.github.hzkitty.entity.OrtInferConfig();
            config.setModelPath(modelPath);
            // OrtInferSession keeps its session private and exposes only
            // run(float[][][][]), which already takes the [1][3][640][640]
            // input this detector builds.
            session = new io.github.hzkitty.utils.OrtInferSession(appContext, config);
        } catch (Throwable throwable) {
            Log.e(TAG, "YOLO init failed", throwable);
            throw new Exception("文本行检测模型初始化失败: " + ImageUtils.humanError(throwable), throwable);
        }
        Log.i(TAG, "YOLO initialized in " + (System.currentTimeMillis() - start) + " ms");
    }

    /** Detects text-line regions as [x1, y1, x2, y2] boxes in bitmap coordinates. */
    synchronized List<float[]> detect(Bitmap bitmap) throws Exception {
        initialize();
        long start = System.currentTimeMillis();

        int srcWidth = bitmap.getWidth();
        int srcHeight = bitmap.getHeight();
        float ratio = Math.min((float) INPUT_SIZE / srcWidth, (float) INPUT_SIZE / srcHeight);
        int newWidth = Math.round(srcWidth * ratio);
        int newHeight = Math.round(srcHeight * ratio);
        float dw = (INPUT_SIZE - newWidth) / 2f;
        float dh = (INPUT_SIZE - newHeight) / 2f;

        Bitmap resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
        Bitmap canvas = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        new Canvas(canvas).drawBitmap(resized, dw, dh, new Paint(Paint.FILTER_BITMAP_FLAG));
        if (resized != bitmap) resized.recycle();

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        canvas.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        canvas.recycle();

        int area = INPUT_SIZE * INPUT_SIZE;
        float[] data = new float[3 * area];
        for (int i = 0; i < area; i++) {
            int pixel = pixels[i];
            data[i] = ((pixel >> 16) & 0xFF) / 255f;
            data[area + i] = ((pixel >> 8) & 0xFF) / 255f;
            data[2 * area + i] = (pixel & 0xFF) / 255f;
        }

        float[][][][] input = new float[1][3][INPUT_SIZE][INPUT_SIZE];
        // The input is planar (C, H, W): OrtInferSession.run() flattens
        // [batch][C][H][W] itself, so each channel only needs its rows.
        for (int c = 0; c < 3; c++) {
            float[] plane = new float[area];
            System.arraycopy(data, c * area, plane, 0, area);
            input[0][c] = reshape(plane, INPUT_SIZE);
        }
        // The output is [1][4 + classes][anchors]: the batch dimension is
        // dropped for the single-image case.
        float[][][] raw = (float[][][]) session.run(input);
        List<float[]> boxes = postprocess(raw[0], ratio, dw, dh, srcWidth, srcHeight);
        // Reading order: vertical lines right to left, then top to bottom.
        // Within a column (same x band) keep top to bottom; columns themselves
        // run right to left. Sorting only by (y, -x) would interleave columns.
        Collections.sort(boxes, new Comparator<float[]>() {
            @Override
            public int compare(float[] a, float[] b) {
                float ax = (a[0] + a[2]) / 2, bx = (b[0] + b[2]) / 2;
                float ay = (a[1] + a[3]) / 2, by = (b[1] + b[3]) / 2;
                if (Math.abs(ax - bx) < (a[2] - a[0] + b[2] - b[0]) / 2) {
                    return Float.compare(ay, by);
                }
                return Float.compare(bx, ax);
            }
        });
        Log.i(TAG, "YOLO finished in " + (System.currentTimeMillis() - start)
                + " ms, boxes=" + boxes.size());
        return boxes;
    }

    private static float[][] reshape(float[] plane, int size) {
        float[][] rows = new float[size][size];
        for (int y = 0; y < size; y++) {
            System.arraycopy(plane, y * size, rows[y], 0, size);
        }
        return rows;
    }

    /** output is [dims, anchors] with dims = 4 + classes. */
    private static List<float[]> postprocess(float[][] output, float ratio, float dw, float dh,
                                             int srcWidth, int srcHeight) {
        int dims = output.length;
        int anchors = output[0].length;
        int numClasses = dims - 4;

        // Same as the extension's postprocessYOLO: keep the best class per
        // anchor, drop the manga class (5), then NMS per class.
        Map<Integer, List<float[]>> byClass = new LinkedHashMap<>();
        for (int i = 0; i < anchors; i++) {
            int bestClass = -1;
            float bestScore = -1f;
            for (int c = 0; c < numClasses; c++) {
                float score = output[4 + c][i];
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }
            if (bestScore < CONF_THRESHOLD) continue;
            if (bestClass == MANGA_CLASS) continue;

            float cx = output[0][i];
            float cy = output[1][i];
            float w = output[2][i];
            float h = output[3][i];
            float[] box = {cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, bestScore};
            if (box[0] >= box[2] || box[1] >= box[3]) continue;
            byClass.computeIfAbsent(bestClass, k -> new ArrayList<>()).add(box);
        }

        List<float[]> detections = new ArrayList<>();
        for (List<float[]> classBoxes : byClass.values()) {
            detections.addAll(nonMaxSuppression(classBoxes, NMS_THRESHOLD));
        }

        List<float[]> scaled = new ArrayList<>(detections.size());
        for (float[] box : detections) {
            float[] b = {
                    Math.max(0, Math.min(srcWidth - 1, Math.round((box[0] - dw) / ratio))),
                    Math.max(0, Math.min(srcHeight - 1, Math.round((box[1] - dh) / ratio))),
                    Math.max(0, Math.min(srcWidth - 1, Math.round((box[2] - dw) / ratio))),
                    Math.max(0, Math.min(srcHeight - 1, Math.round((box[3] - dh) / ratio)))
            };
            // doOCRYolo() skips regions smaller than 10 px in either direction.
            if (b[2] - b[0] < 10 || b[3] - b[1] < 10) continue;
            scaled.add(b);
        }
        return scaled;
    }

    private static List<float[]> nonMaxSuppression(List<float[]> boxes, float iouThreshold) {
        List<float[]> remaining = new ArrayList<>(boxes);
        Collections.sort(remaining, new Comparator<float[]>() {
            @Override
            public int compare(float[] a, float[] b) {
                return Float.compare(b[4], a[4]);
            }
        });
        List<float[]> kept = new ArrayList<>();
        while (!remaining.isEmpty()) {
            float[] best = remaining.remove(0);
            kept.add(best);
            for (int i = remaining.size() - 1; i >= 0; i--) {
                if (iou(best, remaining.get(i)) >= iouThreshold) {
                    remaining.remove(i);
                }
            }
        }
        return kept;
    }

    private static float iou(float[] a, float[] b) {
        float left = Math.max(a[0], b[0]);
        float top = Math.max(a[1], b[1]);
        float right = Math.min(a[2], b[2]);
        float bottom = Math.min(a[3], b[3]);
        float intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        float union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - intersection;
        return union <= 0 ? 0 : intersection / union;
    }
}
