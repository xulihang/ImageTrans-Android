package com.basiccat.imagetrans;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


/**
 * Model plumbing for the OCR models in {@code assets/ocr/}.
 *
 * rapidocr4j's OrtInferSession accepts only existing file paths, so models are
 * extracted from the APK assets into filesDir/models once and reused after
 * that. Every model session (DB detector, recognizer, YOLO) is created by
 * rapidocr4j on those paths.
 */
abstract class NativeInference {
    protected static final String MODEL_DIR = "ocr/";

    protected final Context appContext;

    NativeInference(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Absolute path for a rapidocr4j config (OrtInferSession accepts only
     * absolute paths, and only if the file exists), so the asset is extracted
     * to {@code filesDir/models} once per app version and reused.
     */
    static String ensureModelFile(Context context, String assetName) throws Exception {
        java.io.File dir = new java.io.File(context.getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException("Cannot create " + dir);
        }
        java.io.File file = new java.io.File(dir, new java.io.File(assetName).getName());
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }
        java.io.File temp = new java.io.File(file.getPath() + ".tmp");
        try (java.io.OutputStream out = new java.io.FileOutputStream(temp)) {
            out.write(readAssetBytes(context, assetName));
        }
        if (!temp.renameTo(file)) {
            throw new java.io.IOException("Cannot rename " + temp);
        }
        return file.getAbsolutePath();
    }

    /**
     * Dictionary file for rapidocr4j, which reads it line by line and skips
     * lines whose trimmed text is empty. Every line matters: the dictionary
     * must be exactly 18706 entries so that adding rapidocr4j's space (at the
     * end) and blank (at index 0) gives the model's 18708 characters. That
     * file has no empty-looking lines, so it is only copied.
     */
    static String ensureDictFile(Context context, String assetName) throws Exception {
        String text = readAssetText(context, assetName);
        int characters = 0;
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (!line.trim().isEmpty()) characters++;
        }
        if (characters != 18706) {
            Log.w("NativeInference", "PP-OCRv6 dictionary has " + characters
                    + " entries, expected 18706");
        }
        java.io.File dir = new java.io.File(context.getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException("Cannot create " + dir);
        }
        java.io.File file = new java.io.File(dir, new java.io.File(assetName).getName());
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return file.getAbsolutePath();
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
