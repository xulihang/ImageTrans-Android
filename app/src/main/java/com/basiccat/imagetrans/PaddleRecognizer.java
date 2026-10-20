package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;


import java.util.ArrayList;
import java.util.List;

import io.github.hzkitty.entity.OcrConfig;
import io.github.hzkitty.entity.OcrResult;
import io.github.hzkitty.entity.RecResult;

/**
 * Recognition-only pass for regions produced by the YOLO text-line detector
 * (Japanese), using rapidocr4j's PP-OCRv6 recognizer with detection disabled.
 *
 * rapidocr4j owns the preprocessing (48 px high, pad to 320 wide, [-1, 1]
 * normalization) and the CTC decoding; this class only crops, rotates vertical
 * lines clockwise and maps the results back to the given regions, the way the
 * extension recognizes every YOLO line on its own.
 */
final class PaddleRecognizer extends NativeInference {
    private static final String TAG = "PaddleRecognizer";
    private static final String REC_MODEL = MODEL_DIR + "rec.onnx";
    private static final String REC_DICT = MODEL_DIR + "ppocrv6_dict.txt";
    private static final String CLS_MODEL = MODEL_DIR + "ch_ppocr_mobile_v2.0_cls_infer.onnx";
    /**
     * doOCRYolo() drops results with mean below 0.5, but rapidocr4j's mean for
     * these lines is often lower even when the text is right; empty results are
     * already rejected, so only a floor against noise is kept here.
     */
    private static final float MIN_SCORE = 0.1f;
    /**
     * esearch-ocr's verticalRotateRatio: a line taller than 1.5x its width is
     * rotated before recognition (jn(), measured on the detector's crop).
     */
    private static final float VERTICAL_ROTATE_RATIO = 1.5f;

    private io.github.hzkitty.RapidOCR rapidOCR;

    PaddleRecognizer(Context context) {
        super(context);
    }

    synchronized void initialize() throws Exception {
        if (rapidOCR != null) return;
        try {
            OcrConfig config = new OcrConfig();
            config.Global.useDet = false;
            // Same as the extension's recognize(): one orientation per line, no
            // classifier.
            config.Global.useCls = false;
            config.Rec.modelPath = ensureModelFile(appContext, REC_MODEL);
            config.Rec.recKeysPath = ensureDictFile(appContext, REC_DICT);
            config.Cls.modelPath = ensureModelFile(appContext, CLS_MODEL);
            rapidOCR = io.github.hzkitty.RapidOCR.create(appContext, config);
        } catch (Throwable throwable) {
            Log.e(TAG, "recognizer init failed", throwable);
            throw new Exception("识别模型初始化失败: " + ImageUtils.humanError(throwable), throwable);
        }
    }

    /**
     * Recognizes one line: a vertical line is rotated counter-clockwise and
     * recognized once, as the extension's recognize() does with Et(img, -90).
     */
    private RecResult recognizeLine(Bitmap crop, boolean vertical) throws Exception {
        if (!vertical) {
            return first(runRecognizer(crop));
        }
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(-90);
        Bitmap upright = Bitmap.createBitmap(crop, 0, 0, crop.getWidth(), crop.getHeight(), matrix, true);
        try {
            return first(runRecognizer(upright));
        } finally {
            upright.recycle();
        }
    }

    /** The first recognition result, i.e. what doOCRYolo() reads. */
    private static RecResult first(OcrResult result) {
        if (result == null || result.getRecRes() == null) return null;
        for (RecResult rec : result.getRecRes()) {
            if (rec != null) return rec;
        }
        return null;
    }

    /** Recognition of a single crop, using rapidocr4j with detection disabled. */
    private OcrResult runRecognizer(Bitmap crop) throws Exception {
        return rapidOCR.run(crop);
    }

    /**
     * Recognizes each [x1, y1, x2, y2] region of {@code source}. Returns one
     * result per region that produced text.
     */
    List<RecognizedLine> recognize(Bitmap source, List<float[]> boxes) throws Exception {
        initialize();
        List<RecognizedLine> lines = new ArrayList<>();
        if (boxes == null || boxes.isEmpty()) return lines;

        int width = source.getWidth();
        int height = source.getHeight();
        for (float[] b : boxes) {
            int left = Math.max(0, Math.round(b[0]));
            int top = Math.max(0, Math.round(b[1]));
            int right = Math.min(width, Math.round(b[2]));
            int bottom = Math.min(height, Math.round(b[3]));
            if (right - left < 4 || bottom - top < 4) continue;

            Bitmap crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top);
            boolean vertical = (bottom - top) > (right - left) * VERTICAL_ROTATE_RATIO;
            String text;
            float score;
            try {
                RecResult result = recognizeLine(crop, vertical);
                text = result == null ? "" : result.getText();
                score = result == null ? 0f : result.getConfidence();
            } finally {
                crop.recycle();
            }
            if (text != null && !text.trim().isEmpty() && score >= MIN_SCORE) {
                lines.add(new RecognizedLine(b[0], b[1], b[2] - b[0], b[3] - b[1],
                        text.trim(), score, vertical ? 90 : 0));
            }
        }
        Log.i(TAG, "recognized " + lines.size() + " lines from " + boxes.size() + " regions");
        return lines;
    }

    static final class RecognizedLine {
        final double x;
        final double y;
        final double width;
        final double height;
        final String text;
        final float confidence;
        final int rotation;

        RecognizedLine(double x, double y, double width, double height, String text, float confidence,
                       int rotation) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.text = text;
            this.confidence = confidence;
            this.rotation = rotation;
        }
    }
}
