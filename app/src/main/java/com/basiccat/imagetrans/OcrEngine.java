package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import io.github.hzkitty.ch_ppocr_det.DBPostProcess;
import io.github.hzkitty.ch_ppocr_det.DetPreProcess;

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
    private static final int DET_LIMIT_SIDE_LEN = 960;
    private static final int DB_MIN_SHORT_SIDE = 1024;

    private static volatile OcrEngine instance;

    private OrtSession detSession;
    private String detInputName;
    private String detOutputName;
    private DetPreProcess detPreprocess;
    private DBPostProcess detPostprocess;
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
        if (detSession != null) return;
        long start = System.currentTimeMillis();
        try {
            // Load the OpenCV AAR's native library up front, so the org.opencv
            // classes shared with rapidocr4j bind to it.
            RootlessOpencvLoader.load();
            detSession = createSession(DET_MODEL);
            detInputName = detSession.getInputNames().iterator().next();
            detOutputName = detSession.getOutputNames().iterator().next();
            detPreprocess = new DetPreProcess(DET_LIMIT_SIDE_LEN, "max");
            // Defaults from rapidocr4j's DetConfig: thresh 0.3, boxThresh 0.5,
            // maxCandidates 1000, unclip 1.6, scoreMode "fast", dilation on.
            detPostprocess = new DBPostProcess(0.3f, 0.5f, 1000, 1.6f, "fast", true);
        } catch (Throwable throwable) {
            throw new Exception("OCR 引擎初始化失败: " + ImageUtils.humanError(throwable), throwable);
        }
        Log.i(TAG, "DB detector initialized in " + (System.currentTimeMillis() - start) + " ms");
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
        List<Point[]> regions = detectRegions(bitmap);

        List<float[]> boxes = new ArrayList<>(regions.size());
        for (Point[] points : regions) {
            float[] b = bounds(points, bitmap.getWidth(), bitmap.getHeight());
            if (b != null) boxes.add(b);
        }
        List<Box> lines = new ArrayList<>();
        for (PaddleRecognizer.RecognizedLine line : getRecognizer().recognize(bitmap, boxes)) {
            lines.add(new Box(line.x, line.y, line.width, line.height, line.text));
        }
        Log.i(TAG, "DB OCR finished in " + (System.currentTimeMillis() - start)
                + " ms, regions=" + regions.size() + ", lines=" + lines.size());
        return lines;
    }

    private List<Point[]> detectRegions(Bitmap bitmap) throws Exception {
        // The DB detector's Resize nodes break on very small inputs; keep a
        // few hundred pixels on the short side regardless of the 32-multiple.
        Bitmap source = bitmap;
        float sourceScale = 1f;
        int shortSide = Math.min(bitmap.getWidth(), bitmap.getHeight());
        if (shortSide < DB_MIN_SHORT_SIDE && shortSide > 0) {
            sourceScale = (float) DB_MIN_SHORT_SIDE / shortSide;
            source = Bitmap.createScaledBitmap(bitmap,
                    Math.round(bitmap.getWidth() * sourceScale),
                    Math.round(bitmap.getHeight() * sourceScale), true);
        }
        Mat mat = new Mat();
        try {
            Utils.bitmapToMat(source, mat);
            Mat bgr = new Mat();
            Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2RGB);
            bgr = resizeToMultipleOf32(bgr);
            float[][][][] input = detPreprocess.call(bgr);
            bgr.release();
            if (input == null) {
                return Collections.emptyList();
            }
            // DetPreProcess in the bundled rapidocr4j returns [1][H][W][C] and
            // normalizes to (v / 255 - 0.5) / 0.5; the detector reads 3-channel
            // NCHW, so the frames are rebuilt as RGB planes below.
            int height = input[0].length;
            int width = input[0][0].length;
            int planes = height * width;
            float[] data = new float[3 * planes];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = y * width + x;
                    float[] pixel = input[0][y][x];
                    // OpenCV order: B, G, R
                    data[2 * planes + i] = pixel[0];
                    data[planes + i] = pixel[1];
                    data[i] = pixel[2];
                }
            }

            float[][][][] probabilityMap;
            try (OnnxTensor tensor = OnnxTensor.createTensor(NativeInference.ENVIRONMENT,
                    java.nio.FloatBuffer.wrap(data), new long[]{1, 3, height, width})) {
                try (OrtSession.Result result = detSession.run(
                        Collections.singletonMap(detInputName, tensor),
                        Collections.singleton(detOutputName))) {
                    probabilityMap = (float[][][][]) result.get(0).getValue();
                }
            }
            // DBPostProcess maps the polygons back to the (possibly upscaled)
            // source size; bring them back to the caller's bitmap.
            List<Point[]> boxes = detPostprocess.call(probabilityMap, mat.rows(), mat.cols()).getBoxes();
            if (sourceScale != 1f) {
                for (Point[] points : boxes) {
                    for (Point point : points) {
                        point.x /= sourceScale;
                        point.y /= sourceScale;
                    }
                }
            }
            return boxes;
        } finally {
            mat.release();
            if (source != bitmap) source.recycle();
        }
    }

    private static Mat resizeToMultipleOf32(Mat image) {
        int height = Math.max(32, Math.round(image.rows() / 32f) * 32);
        int width = Math.max(32, Math.round(image.cols() / 32f) * 32);
        if (height == image.rows() && width == image.cols()) {
            return image;
        }
        Mat resized = new Mat();
        Imgproc.resize(image, resized, new org.opencv.core.Size(width, height));
        image.release();
        return resized;
    }

    private static float[] bounds(Point[] points, int width, int height) {
        if (points == null || points.length == 0) return null;
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
        double left = Math.max(0, Math.min(width, minX));
        double top = Math.max(0, Math.min(height, minY));
        double right = Math.max(0, Math.min(width, maxX));
        double bottom = Math.max(0, Math.min(height, maxY));
        if (right - left < 2 || bottom - top < 2) return null;
        return new float[]{(float) left, (float) top, (float) right, (float) bottom};
    }

    private synchronized PaddleTextLineDetector textLineDetector() {
        if (lineDetector == null) {
            lineDetector = new PaddleTextLineDetector(appContext);
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
