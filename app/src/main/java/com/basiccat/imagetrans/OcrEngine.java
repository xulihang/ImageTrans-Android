package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.github.hzkitty.RapidOCR;
import io.github.hzkitty.entity.OcrConfig;
import io.github.hzkitty.entity.OcrResult;
import io.github.hzkitty.entity.ParamConfig;
import io.github.hzkitty.entity.RecResult;

/**
 * Local OCR on the PP-OCRv6 models in {@code assets/ocr/} (the same files the
 * browser extension ships in {@code paddleocr/}):
 *
 * - default: PP-OCRv6 DB text detection ({@code PP-OCRv6_det_small.onnx})
 *   followed by PP-OCRv6 recognition ({@code rec.onnx}) per region;
 * - Japanese: the YOLO text-line detector ({@code model.onnx}) followed by
 *   PP-OCRv6 recognition, matching the extension's default for that language;
 *   the DB detector can be selected instead in settings;
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
    /**
     * Webtoon OCR: parts are this many widths tall, then scaled down for OCR.
     * Keep 4 (the extension's value): each part is capped at 1500 px on its
     * long side, so the ratio directly sets the part width (1500 / ratio).
     * With 8 the parts are only ~187 px wide and small Hangul is misread.
     */
    private static final int OCR_PART_RATIO = 4;
    private static final int OCR_PART_MAX_SIDE = 1500;

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

    /**
     * Whether local OCR runs YOLO line detection. Japanese defaults to YOLO
     * (the extension's default) and can be switched to PP-OCRv6's DB
     * detector in settings; other languages always use the DB detector.
     */
    static boolean usesYoloDetection(Context context, String sourceLang) {
        return "ja".equals(sourceLang) && Prefs.isJapaneseYolo(context);
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
            // The line direction classifier only helps Chinese; it is part of
            // the engine key because switching between Chinese and another
            // language that reuses the bundled recognizer must rebuild it.
            boolean useCls = "zh".equals(sourceLang);
            String key = recModel + "|" + recDict + "|cls=" + useCls;
            if (rapidOCR != null && key.equals(engineKey)) return;

            // Load the OpenCV AAR's native library up front, so the org.opencv
            // classes shared with rapidocr4j bind to it.
            RootlessOpencvLoader.load();
            // rapidocr4j's OrtInferSession takes a file path, so the assets are
            // extracted once into filesDir/models; the recognizer and the YOLO
            // detector are loaded the same way from the paths below.
            OcrConfig config = new OcrConfig();
            config.Det.modelPath = ensureModelFile(appContext, DET_MODEL);
            // Same detection thresholds as the extension's defaults (and the
            // values users tune there). erode_size has no rapidocr4j
            // equivalent, so useDilation keeps its library default.
            config.Det.thresh = 0.3f;
            config.Det.boxThresh = 0.6f;
            config.Det.unclipRatio = 1.5f;
            config.Rec.modelPath = recModel;
            config.Rec.recKeysPath = recDict;
            // Line direction classifier for Chinese only.
            // ch_ppocr_mobile_v2.0_cls_infer is trained on Chinese/English
            // lines and often labels upright Korean (or other non-Chinese)
            // lines as 180 degrees with high confidence; rapidocr4j would
            // rotate them before recognition, so the text is garbled or
            // dropped by the score filter. The extension does not run this
            // classifier at all.
            config.Global.useCls = useCls;
            // rapidocr4j constructs the classifier whether or not it is
            // enabled, so the model path still has to point at an existing file.
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
            if (ImageUtils.isTall(bitmap.getWidth(), bitmap.getHeight())) {
                return recognizeTall(bitmap, sourceLang, listener);
            }
            return recognizePart(bitmap, sourceLang, listener);
        } catch (Throwable throwable) {
            Log.e(TAG, "local OCR failed", throwable);
            throw throwable;
        }
    }

    private List<Box> recognizePart(Bitmap bitmap, String sourceLang,
                                    RemoteOcrModels.ProgressListener listener) throws Exception {
        return usesYoloDetection(appContext, sourceLang)
                ? detectWithYolo(bitmap)
                : detectWithDb(bitmap, sourceLang, listener);
    }

    /**
     * Webtoon OCR, based on the extension's paddleOCR(): an image taller than
     * 8x its width is cut into parts of height 4*width, each part is scaled to
     * at most 1500 px on its long side and recognized on its own, and the
     * boxes are mapped back to the full image. Working on the whole strip
     * would make the detectors resize away the text.
     */
    private List<Box> recognizeTall(Bitmap bitmap, String sourceLang,
                                    RemoteOcrModels.ProgressListener listener) throws Exception {
        long start = System.currentTimeMillis();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int partHeight = width * OCR_PART_RATIO;
        int count = (height + partHeight - 1) / partHeight;
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int top = i * partHeight;
            int heightPart = Math.min(partHeight, height - top);
            if (listener != null) {
                listener.onProgress("本地 OCR 识别中…（" + (i + 1) + "/" + count + "）");
            }
            Bitmap part = Bitmap.createBitmap(bitmap, 0, top, width, heightPart);
            float scale = Math.min(1f,
                    (float) OCR_PART_MAX_SIDE / Math.max(width, heightPart));
            Bitmap ocrBitmap = part;
            if (scale < 1f) {
                int scaledWidth = Math.max(1, Math.round(width * scale));
                int scaledHeight = Math.max(1, Math.round(heightPart * scale));
                ocrBitmap = Bitmap.createScaledBitmap(part, scaledWidth, scaledHeight, true);
                part.recycle();
            }
            try {
                // Only the first part may report model downloads/initialization;
                // later parts reuse the engine.
                for (Box box : recognizePart(ocrBitmap, sourceLang, i == 0 ? listener : null)) {
                    box.x /= scale;
                    box.y = box.y / scale + top;
                    box.width /= scale;
                    box.height /= scale;
                    boxes.add(box);
                }
            } finally {
                ocrBitmap.recycle();
            }
        }
        Log.i(TAG, "tall OCR finished in " + (System.currentTimeMillis() - start)
                + " ms, parts=" + count + ", lines=" + boxes.size());
        return boxes;
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

    /** DB-detector OCR (PP-OCRv6 det + recognition), one box per line. */
    private List<Box> detectWithDb(Bitmap bitmap, String sourceLang,
                                   RemoteOcrModels.ProgressListener listener) throws Exception {
        initialize(sourceLang, listener);
        if (listener != null) listener.onProgress("本地 OCR 识别中…");
        // Japanese: the built-in recognizer cannot handle vertical columns,
        // so run detection only and recognize every detected line separately.
        if ("ja".equals(sourceLang)) {
            return detectJapaneseWithDb(bitmap);
        }
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
            Box box = boundingBox(points, text.trim());
            if (box != null) boxes.add(box);
        }
        Log.i(TAG, "DB OCR finished in " + (System.currentTimeMillis() - start)
                + " ms, lines=" + boxes.size());
        return boxes;
    }

    /**
     * PP-OCRv6's DB detector groups several vertical Japanese columns into
     * one region and rapidocr4j's recognizer does not split them. Detection
     * also runs with recognition turned off: {@code run()} otherwise drops
     * every region whose recognized text is empty or scores below the
     * global textScore, losing boxes even when detection is fine.
     *
     * Each detected region is then recognized line by line, as the YOLO path
     * does (vertical crops are rotated first), and sorted in Japanese reading
     * order: columns right to left, lines within a column top to bottom.
     */
    private List<Box> detectJapaneseWithDb(Bitmap bitmap) throws Exception {
        long start = System.currentTimeMillis();
        // Per-call overrides only; the engine's own config stays untouched.
        ParamConfig params = new ParamConfig();
        params.setUseDet(true);
        params.setUseCls(false);
        params.setUseRec(false);
        OcrResult result = rapidOCR.run(bitmap, params);

        List<float[]> regions = new ArrayList<>();
        if (result != null && result.getRecRes() != null) {
            for (RecResult rec : result.getRecRes()) {
                if (rec == null) continue;
                Point[] points = rec.getDtBoxes();
                if (points == null || points.length == 0) continue;
                Box box = boundingBox(points, "");
                if (box == null || box.width < 10 || box.height < 10) continue;
                regions.add(new float[]{(float) box.left(), (float) box.top(),
                        (float) box.right(), (float) box.bottom()});
            }
        }
        Log.d(TAG, "JA DB det: image=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " regions=" + regions.size());

        List<Box> boxes = new ArrayList<>();
        for (PaddleRecognizer.RecognizedLine line : getRecognizer().recognize(bitmap, regions)) {
            boxes.add(new Box(line.x, line.y, line.width, line.height, line.text));
            Log.d(TAG, "JA line: [" + Math.round(line.x) + "," + Math.round(line.y) + " "
                    + Math.round(line.width) + "x" + Math.round(line.height) + "] rot="
                    + line.rotation + " " + line.confidence + " " + line.text);
        }
        sortReadingOrder(boxes);
        Log.i(TAG, "DB OCR (ja) finished in " + (System.currentTimeMillis() - start)
                + " ms, regions=" + regions.size() + ", lines=" + boxes.size());
        return boxes;
    }

    /** Axis-aligned bounds of a quad with its recognized text, or null when it has no area. */
    private static Box boundingBox(Point[] points, String text) {
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
        if (maxX <= minX || maxY <= minY) return null;
        return new Box(minX, minY, maxX - minX, maxY - minY, text);
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

    /**
     * Reading order for vertical Japanese: columns right to left, lines within
     * a column top to bottom (the order the YOLO detector returns).
     */
    private static void sortReadingOrder(List<Box> boxes) {
        Collections.sort(boxes, new Comparator<Box>() {
            @Override
            public int compare(Box a, Box b) {
                if (Math.abs(a.centerX() - b.centerX()) < (a.width + b.width) / 2) {
                    return Double.compare(a.centerY(), b.centerY());
                }
                return Double.compare(b.centerX(), a.centerX());
            }
        });
    }
}
