package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;

/**
 * PP-OCRv6 text recognition (the extension's paddleocr/rec.onnx +
 * ppocrv6_dict.txt) for regions produced by either detector.
 *
 * The model reads 3x48xW RGB in [0, 1] and returns [1, T, 18710] scores over
 * 5 PaddleOCR special tokens (blank, joiner, space, BOS, EOS) followed by the
 * dictionary entries, so CTC greedy decoding over the dictionary works
 * exactly as in the extension. Model and dictionary are read from the APK
 * assets; nothing is written to the cache directory.
 */
final class PaddleRecognizer extends NativeInference {
    private static final String TAG = "PaddleRecognizer";
    private static final String MODEL = MODEL_DIR + "rec.onnx";
    private static final String DICT = MODEL_DIR + "ppocrv6_dict.txt";
    private static final int REC_HEIGHT = 48;
    /** Minimum input width; rapidocr4j's recImgShape is 3x48x320. */
    private static final int MIN_ASPECT_TEXT = 320;
    private static final int SPECIAL_TOKENS = 5;

    private OrtSession session;
    private String[] characters;

    PaddleRecognizer(Context context) {
        super(context);
    }

    synchronized void initialize() throws Exception {
        if (session != null) return;
        try {
            Log.i(TAG, "loading " + MODEL + " from assets");
            session = createSession(MODEL);
            characters = parseDictionary(readAssetText(appContext, DICT));
        } catch (Throwable throwable) {
            Log.e(TAG, "recognizer init failed", throwable);
            throw new Exception("识别模型初始化失败: " + ImageUtils.humanError(throwable), throwable);
        }
    }

    /**
     * Recognizes each [x1, y1, x2, y2] region of {@code source}. Returns one
     * result per region that produced text.
     */
    List<RecognizedLine> recognize(Bitmap source, List<float[]> boxes) throws Exception {
        initialize();
        List<RecognizedLine> lines = new ArrayList<>();
        if (boxes == null || boxes.isEmpty()) return lines;

        Mat mat = new Mat();
        Utils.bitmapToMat(source, mat);
        try {
            for (float[] b : boxes) {
                int left = Math.max(0, Math.round(b[0]));
                int top = Math.max(0, Math.round(b[1]));
                int right = Math.min(source.getWidth(), Math.round(b[2]));
                int bottom = Math.min(source.getHeight(), Math.round(b[3]));
                if (right - left < 4 || bottom - top < 4) continue;

                Mat crop = mat.submat(top, bottom, left, right).clone();
                Recognized best;
                try {
                    best = recognizeCrop(crop);
                    // The recognizer reads horizontal lines only. Vertical
                    // text (e.g. Japanese) is also tried rotated 90 degrees,
                    // which turns the characters upright.
                    if (bottom - top > (right - left) * 1.2) {
                        Mat rotated = new Mat();
                        Core.rotate(crop, rotated, Core.ROTATE_90_COUNTERCLOCKWISE);
                        Recognized upright = recognizeCrop(rotated);
                        rotated.release();
                        if (best.text.isEmpty()
                                || (!upright.text.isEmpty() && upright.confidence > best.confidence)) {
                            best = upright;
                        }
                    }
                } finally {
                    crop.release();
                }
                if (!best.text.isEmpty()) {
                    lines.add(new RecognizedLine(b[0], b[1], b[2] - b[0], b[3] - b[1],
                            best.text, best.confidence));
                }
            }
        } finally {
            mat.release();
        }
        Log.i(TAG, "recognized " + lines.size() + " lines from " + boxes.size() + " regions");
        for (int i = 0; i < lines.size() && i < 5; i++) {
            RecognizedLine line = lines.get(i);
            Log.w(TAG, "line " + i + " conf=" + line.confidence + " text=" + line.text);
        }
        return lines;
    }

    /** Recognizes a list of boxes and merges them into translation paragraphs. */
    List<Box> recognizeAndMerge(Bitmap source, List<float[]> boxes, String sourceLang,
                                double xSpacing, double ySpacing) throws Exception {
        List<Box> items = new ArrayList<>();
        for (RecognizedLine line : recognize(source, boxes)) {
            items.add(new Box(line.x, line.y, line.width, line.height, line.text));
        }
        return TextBoxMerger.merge(items, sourceLang, xSpacing, ySpacing);
    }

    private Recognized recognizeCrop(Mat crop) throws Exception {
        // Same as rapidocr4j's TextRecognizer.resizeNormImg (48 px high):
        // resize keeping the aspect ratio, right-pad to at least
        // recImgShape[2] / recImgShape[1] (320 / 48), normalize to
        // (value / 255 - 0.5) / 0.5.
        int height = REC_HEIGHT;
        int resizedWidth = Math.min(MIN_ASPECT_TEXT,
                (int) Math.ceil((double) height * crop.cols() / crop.rows()));
        resizedWidth = Math.max(resizedWidth, 1);
        int width = Math.max(MIN_ASPECT_TEXT, resizedWidth);
        Mat resized = new Mat();
        Imgproc.resize(crop, resized, new Size(resizedWidth, height), 0, 0, Imgproc.INTER_LINEAR);
        int area = width * height;
        float[] data = new float[3 * area];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < resized.cols(); x++) {
                double[] bgr = resized.get(y, x);
                int i = y * width + x;
                data[i] = (float) ((bgr[2] / 255.0 - 0.5) / 0.5);
                data[area + i] = (float) ((bgr[1] / 255.0 - 0.5) / 0.5);
                data[2 * area + i] = (float) ((bgr[0] / 255.0 - 0.5) / 0.5);
            }
        }
        resized.release();

        try (OnnxTensor tensor = OnnxTensor.createTensor(ENVIRONMENT,
                java.nio.FloatBuffer.wrap(data), new long[]{1, 3, height, width})) {
            try (OrtSession.Result result = session.run(Collections.singletonMap(
                    session.getInputNames().iterator().next(), tensor),
                    Collections.singleton(session.getOutputNames().iterator().next()))) {
                float[][][] output = (float[][][]) result.get(0).getValue();
                return decode(output[0]);
            }
        }
    }

    private Recognized decode(float[][] probabilities) {
        StringBuilder text = new StringBuilder();
        float score = 0;
        int count = 0;
        int previous = -1;
        for (float[] step : probabilities) {
            int index = 0;
            float best = step[0];
            for (int i = 1; i < step.length; i++) {
                if (step[i] > best) {
                    best = step[i];
                    index = i;
                }
            }
            if (index != 0 && index != previous) {
                int character = index - SPECIAL_TOKENS;
                String value = null;
                if (character == 2) {
                    value = " ";
                } else if (character >= 0 && character < characters.length) {
                    value = characters[character];
                }
                // Ids 1/3/4 (joiner, BOS, EOS) carry no text and are not scored.
                if (value != null) {
                    text.append(value);
                    score += best;
                    count++;
                }
            }
            previous = index;
        }
        float confidence = count > 0 ? score / count : 0f;
        return new Recognized(removeSpaces(text.toString()), confidence);
    }

    private static String removeSpaces(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) builder.append(ch);
        }
        return builder.toString();
    }

    /** Dictionary file, one character per line (CRLF in the extension's copy). */
    private static String[] parseDictionary(String content) {
        content = content.replace("\r\n", "\n").replace('\r', '\n');
        int end = content.endsWith("\n") ? content.length() - 1 : content.length();
        return content.substring(0, end).split("\n", -1);
    }

    private static final class Recognized {
        final String text;
        final float confidence;

        Recognized(String text, float confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }

    static final class RecognizedLine {
        final double x;
        final double y;
        final double width;
        final double height;
        final String text;
        final float confidence;

        RecognizedLine(double x, double y, double width, double height, String text, float confidence) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.text = text;
            this.confidence = confidence;
        }
    }
}
