package com.basiccat.imagetrans;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Shared ONNX Runtime plumbing for the OCR models in {@code assets/ocr/}.
 *
 * Models are loaded from the APK assets as bytes and handed to ONNX Runtime
 * directly: the asset manager gives no file descriptor for a path, and the
 * cache directory is never written to.
 */
abstract class NativeInference {
    protected static final String MODEL_DIR = "ocr/";
    static final OrtEnvironment ENVIRONMENT = OrtEnvironment.getEnvironment("ImageTransOcr");
    private static final int INFERENCE_THREADS = 4;

    protected final Context appContext;

    NativeInference(Context context) {
        this.appContext = context.getApplicationContext();
    }

    protected OrtSession createSession(String assetName) throws Exception {
        byte[] model = readAssetBytes(appContext, assetName);
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(INFERENCE_THREADS);
        // PP-OCRv6_det_small's uint8 Resize reads the buffer its input shares
        // with a previous node; with ORT's default memory pattern on Android
        // this fails with "Shape mismatch attempting to re-use buffer".
        options.setMemoryPatternOptimization(false);
        return ENVIRONMENT.createSession(model, options);
    }

    static String readAssetText(Context context, String assetName) throws Exception {
        return new String(readAssetBytes(context, assetName), StandardCharsets.UTF_8);
    }

    static byte[] readAssetBytes(Context context, String assetName) throws Exception {
        try (InputStream in = context.getAssets().open(assetName);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
