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
 * The recognizer and the YOLO detector run through rapidocr4j's
 * OrtInferSession, which only opens existing file paths, so the assets are
 * extracted once into filesDir/models (see NativeInference). The DB detector
 * runs through rapidocr4j's OcrConfig the same way.
 */
public final class OcrEngine extends NativeInference {
    private static final String TAG = "OcrEngine";
    private static final String DET_MODEL = NativeInference.MODEL_DIR + "PP-OCRv6_det_small.onnx";
    private static final String REC_MODEL = NativeInference.MODEL_DIR + "rec.onnx";
    private static final String REC_DICT = NativeInference.MODEL_DIR + "ppocrv6_dict.txt";
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
            // rapidocr4j creates the direction classifier whenever useCls is on,
            // and the PP-OCRv6 pipeline does not use it.
            config.Global.useCls = false;
            rapidOCR = RapidOCR.create(appContext, config);
        } catch (Throwable throwable) {
            throw new Exception("OCR 引擎初始化失败: " + ImageUtils.humanError(throwable), throwable);
        }
        Log.i(TAG, "RapidOCR initialized in " + (System.currentTimeMillis() - start) + " ms");
    }

    /**
     * Recognizes the bitmap, one box per detected line (not merged). Japanese
     * uses the YOLO text-line detector like the extension; other languages use
     * PP-OCRv6's DB detector.
     */
    public List<Box> recognize(Bitmap bitmap, String sourceLang) throws Exception {
        try {
            return usesYoloDetection(sourceLang) ? detectWithYolo(bitmap) : detectWithDb(bitmap);
        } catch (Throwable throwable) {
            Log.e(TAG, "local OCR failed", throwable);
            throw throwable;
        }
    }


    /** YOLO text-line detection, then PP-OCRv6 recognition of every line. */
    private List<Box> detectWithYolo(Bitmap bitmap) throws Exception {
        long start = System.currentTimeMillis();
        List<float[]> regions = textLineDetector().detect(bitmap);
        List<Box> lines = new ArrayList<>();
        for (PaddleRecognizer.RecognizedLine line : getRecognizer().recognize(bitmap, regions)) {
            lines.add(new Box(line.x, line.y, line.width, line.height, line.text));
        }
        Log.i(TAG, "YOLO OCR finished in " + (System.currentTimeMillis() - start)
                + " ms, regions=" + regions.size() + ", lines=" + lines.size());
        return lines;
    }

    /** DB-detector OCR (PP-OCRv6 det + rec), one box per detected line. */
    private List<Box> detectWithDb(Bitmap bitmap) throws Exception {
        initialize();
        long start = System.currentTimeMillis();
        OcrResult result = rapidOCR.run(bitmap);
        List<Box> boxes = new ArrayList<>();
        if (result == null || result.getRecRes() == null) {
            return boxes;
        }
        for (RecResult rec : result.getRecRes()) {
            if (rec == null) continue;
            String text = rec.getText();
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
            boxes.add(new Box(minX, minY, maxX - minX, maxY - minY, text.trim()));
        }
        Log.i(TAG, "DB OCR finished in " + (System.currentTimeMillis() - start)
                + " ms, lines=" + boxes.size());
        return boxes;
    }

    private synchronized PaddleTextLineDetector textLineDetector() throws Exception {
        if (lineDetector == null) {
            lineDetector = new PaddleTextLineDetector(appContext,
                    ensureModelFile(appContext, YOLO_MODEL));
        }
        return lineDetector;
    }

    private synchronized PaddleRecognizer getRecognizer() {
        if (recognizer == null) {
            recognizer = new PaddleRecognizer(appContext);
        }
        return recognizer;
    }
}
