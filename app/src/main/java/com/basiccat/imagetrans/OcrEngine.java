package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.List;

import io.github.hzkitty.RapidOCR;
import io.github.hzkitty.entity.OcrConfig;
import io.github.hzkitty.entity.OcrResult;
import io.github.hzkitty.entity.RecResult;

/**
 * Local OCR on the PP-OCRv6 models in {@code assets/ocr/} (the same files the
 * browser extension ships in {@code paddleocr/}):
 *
 * - default: PP-OCRv6 DB text detection ({@code PP-OCRv6_det_small.onnx})
 *   followed by PP-OCRv6 recognition ({@code rec.onnx}) per region;
 * - Japanese: the YOLO text-line detector ({@code model.onnx}) followed by
 *   PP-OCRv6 recognition, matching the extension's default for that language.
 *
 * rapidocr4j's sessions accept file paths or asset paths only; these models are
 * loaded as bytes from the APK assets (see NativeInference), and rapidocr4j
 * still provides the PaddleOCR DB pre/post-processing.
 */
public final class OcrEngine extends NativeInference {
    private static final String TAG = "OcrEngine";
    private static final String DET_MODEL = NativeInference.MODEL_DIR + "PP-OCRv6_det_small.onnx";
    private static final String REC_MODEL = NativeInference.MODEL_DIR + "rec.onnx";
    private static final String REC_DICT = NativeInference.MODEL_DIR + "ppocrv6_dict.txt";
    private static final String CLS_MODEL = NativeInference.MODEL_DIR + "ch_ppocr_mobile_v2.0_cls_infer.onnx";
    /** Below this short side the DB detector finds too little to work with. */
    private static final int DB_MIN_SHORT_SIDE = 1024;
    private static final String YOLO_MODEL = NativeInference.MODEL_DIR + "model.onnx";

    private static volatile OcrEngine instance;

    private RapidOCR rapidOCR;
    private PaddleTextLineDetector lineDetector;
    private PaddleRecognizer recognizer;

    private OcrEngine(Context context) {
        super(context);
    }

    public static OcrEngine getInstance(Context context) {
        if (instance == null) {
            synchronized (OcrEngine.class) {
                if (instance == null) {
                    instance = new OcrEngine(context);
                }
            }
        }
        return instance;
    }

    /** Whether local OCR runs YOLO line detection (the extension's default for Japanese). */
    static boolean usesYoloDetection(String sourceLang) {
        return "ja".equals(sourceLang);
    }

    public synchronized void initialize() throws Exception {
        if (rapidOCR != null) return;
        long start = System.currentTimeMillis();
        try {
            // Load the OpenCV AAR's native library up front, so the org.opencv
            // classes shared with rapidocr4j bind to it.
            RootlessOpencvLoader.load();
            // rapidocr4j's OrtInferSession takes a file path, so the assets are
            // extracted once into filesDir/models; the recognizer and the YOLO
            // detector are loaded the same way from the paths below.
            OcrConfig config = new OcrConfig();
            config.Det.modelPath = ensureModelFile(appContext, DET_MODEL);
            config.Rec.modelPath = ensureModelFile(appContext, REC_MODEL);
            // PP-OCRv6 conventions: the dictionary file gives the 18706 regular
            // characters, followed by space and then blank at index 0, matching
            // the extension (special tokens: blank, joiner, space, BOS, EOS).
            config.Rec.recKeysPath = NativeInference.ensureDictFile(appContext, REC_DICT);
            config.Cls.modelPath = ensureModelFile(appContext, CLS_MODEL);
            rapidOCR = RapidOCR.create(appContext, config);
        } catch (Throwable throwable) {
            throw new Exception("OCR 引擎初始化失败: " + ImageUtils.humanError(throwable), throwable);
        }
        Log.i(TAG, "RapidOCR initialized in " + (System.currentTimeMillis() - start) + " ms");
    }

    public List<Box> recognizeAndMerge(Bitmap bitmap, String sourceLang, boolean yoloDetection,
                                       double xSpacing, double ySpacing) throws Exception {
        try {
            return recognizeAndMergeUnlogged(bitmap, sourceLang, yoloDetection, xSpacing, ySpacing);
        } catch (Throwable throwable) {
            Log.e(TAG, "local OCR failed", throwable);
            throw throwable;
        }
    }

    private List<Box> recognizeAndMergeUnlogged(Bitmap bitmap, String sourceLang, boolean yoloDetection,
                                                double xSpacing, double ySpacing) throws Exception {
        Log.i(TAG, "local OCR start, lang=" + sourceLang + ", yolo=" + yoloDetection
                + ", image=" + bitmap.getWidth() + "x" + bitmap.getHeight());
        if (yoloDetection) {
            List<float[]> regions = textLineDetector().detect(bitmap);
            List<Box> paragraphs = getRecognizer().recognizeAndMerge(bitmap, regions,
                    sourceLang, xSpacing, ySpacing);
            Log.i(TAG, "YOLO OCR produced " + regions.size() + " lines, "
                    + paragraphs.size() + " paragraphs");
            return paragraphs;
        }
        List<Box> lines = detect(bitmap);
        List<Box> merged = TextBoxMerger.merge(lines, sourceLang, xSpacing, ySpacing);
        Log.i(TAG, "DB OCR produced " + lines.size() + " lines, " + merged.size() + " paragraphs");
        return merged;
    }

    /** DB-detector OCR (PP-OCRv6 det + rec), one box per detected line. */
    private List<Box> detect(Bitmap bitmap) throws Exception {
        initialize();
        long start = System.currentTimeMillis();
        android.graphics.Bitmap probe = bitmap;
        float probeScale = 1f;
        int shortSide = Math.min(bitmap.getWidth(), bitmap.getHeight());
        if (shortSide < DB_MIN_SHORT_SIDE && shortSide > 0) {
            probeScale = (float) DB_MIN_SHORT_SIDE / shortSide;
            probe = android.graphics.Bitmap.createScaledBitmap(bitmap,
                    Math.round(bitmap.getWidth() * probeScale),
                    Math.round(bitmap.getHeight() * probeScale), true);
        }
        // Small images (e.g. a 440 px screenshot) give the DB detector too
        // little signal; detect on an upscaled copy and map the boxes back.
        OcrResult result = rapidOCR.run(probe);
        if (probe != bitmap) probe.recycle();
        List<Box> boxes = new ArrayList<>();
        if (result == null || result.getRecRes() == null) {
            return boxes;
        }
        for (RecResult rec : result.getRecRes()) {
            if (rec == null) continue;
            String text = rec.getText();
            Log.i(TAG, "rec: conf=" + rec.getConfidence() + " textLength="
                    + (text == null ? -1 : text.length()) + " text=" + (text == null ? "null" : text));
            if (text == null || text.trim().isEmpty()) continue;
            if (rec.getConfidence() < 0.30f) continue;
            Point[] points = rec.getDtBoxes();
            if (points == null || points.length == 0) continue;

            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (Point point : points) {
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
            }
            if (maxX <= minX || maxY <= minY) continue;
            boxes.add(new Box(minX / probeScale, minY / probeScale,
                    (maxX - minX) / probeScale, (maxY - minY) / probeScale, text.trim()));
        }
        Log.i(TAG, "DB OCR finished in " + (System.currentTimeMillis() - start)
                + " ms, lines=" + boxes.size());
        Log.i(TAG, "DB OCR raw:" + result.getStrRes().replace(" ", "|").replace("\n", "/"));
        return boxes;
    }

    private synchronized PaddleTextLineDetector textLineDetector() throws Exception {
        if (lineDetector == null) {
            lineDetector = new PaddleTextLineDetector(appContext,
                    ensureModelFile(appContext, YOLO_MODEL));
        }
        return lineDetector;
    }

    private synchronized PaddleRecognizer getRecognizer() throws Exception {
        if (recognizer == null) {
            recognizer = new PaddleRecognizer(appContext,
                    ensureModelFile(appContext, REC_MODEL),
                    ensureModelFile(appContext, REC_DICT));
        }
        return recognizer;
    }
}
