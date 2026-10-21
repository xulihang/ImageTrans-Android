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
 *   PP-OCRv6 recognition, matching the extension's default for that language;
 * - Korean, Arabic, Russian and Thai: the bundled DB detector with a
 *   language-specific PP-OCRv5 recognizer downloaded on first use (see
 *   RemoteOcrModels), matching the extension's dedicated models.
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
    private static final String CLS_MODEL = NativeInference.MODEL_DIR + "ch_ppocr_mobile_v2.0_cls_infer.onnx";

    private static volatile OcrEngine instance;

    private RapidOCR rapidOCR;
    /** rec model + dict currently loaded, so switching back doesn't rebuild. */
    private String engineKey;
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

    public synchronized void initialize(String sourceLang,
                                        RemoteOcrModels.ProgressListener listener)
            throws Exception {
        RemoteOcrModels.Model remote = RemoteOcrModels.forLanguage(sourceLang);
        long start = System.currentTimeMillis();
        try {
            String recModel;
            String recDict;
            if (remote != null) {
                // First use of the language downloads rec+dict from ModelScope;
                // later runs reuse the files in filesDir/models.
                String[] paths = RemoteOcrModels.ensure(appContext, remote, listener);
                recModel = paths[0];
                recDict = paths[1];
                if (listener != null) {
                    listener.onProgress("正在初始化" + remote.displayName + "识别模型…");
                }
            } else {
                recModel = ensureModelFile(appContext, REC_MODEL);
                recDict = ensureDictFile(appContext, REC_DICT);
            }
            String key = recModel + "|" + recDict;
            if (rapidOCR != null && key.equals(engineKey)) return;

            // Load the OpenCV AAR's native library up front, so the org.opencv
            // classes shared with rapidocr4j bind to it.
            RootlessOpencvLoader.load();
            // rapidocr4j's OrtInferSession takes a file path, so the assets are
            // extracted once into filesDir/models; the recognizer and the YOLO
            // detector are loaded the same way from the paths below.
            OcrConfig config = new OcrConfig();
            config.Det.modelPath = ensureModelFile(appContext, DET_MODEL);
            config.Rec.modelPath = recModel;
            config.Rec.recKeysPath = recDict;
            // DB detection keeps the direction classifier on: rapidocr4j turns
            // 180-degree lines upright before recognition, which the combined
            // DB pipeline needs for the detection order it produces.
            config.Global.useCls = true;
            config.Cls.modelPath = ensureModelFile(appContext, CLS_MODEL);
            rapidOCR = RapidOCR.create(appContext, config);
            engineKey = key;
        } catch (Throwable throwable) {
            throw new Exception("OCR 引擎初始化失败: " + ImageUtils.humanError(throwable), throwable);
        }
        Log.i(TAG, "RapidOCR initialized in " + (System.currentTimeMillis() - start) + " ms"
                + (remote == null ? "" : " (" + remote.displayName + ")"));
    }

    /**
     * Recognizes the bitmap, one box per detected line (not merged). Japanese
     * uses the YOLO text-line detector like the extension; other languages use
     * PP-OCRv6's DB detector with the recognition model for that language.
     */
    public List<Box> recognize(Bitmap bitmap, String sourceLang) throws Exception {
        return recognize(bitmap, sourceLang, null);
    }

    /** Same as {@link #recognize(Bitmap, String)}, reporting download progress. */
    public List<Box> recognize(Bitmap bitmap, String sourceLang,
                               RemoteOcrModels.ProgressListener listener) throws Exception {
        try {
            return usesYoloDetection(sourceLang)
                    ? detectWithYolo(bitmap)
                    : detectWithDb(bitmap, sourceLang, listener);
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

    /** DB-detector OCR (PP-OCRv6 det + language recognition model), one box per line. */
    private List<Box> detectWithDb(Bitmap bitmap, String sourceLang,
                                   RemoteOcrModels.ProgressListener listener) throws Exception {
        initialize(sourceLang, listener);
        if (listener != null) listener.onProgress("本地 OCR 识别中…");
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
