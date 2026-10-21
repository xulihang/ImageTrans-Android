package com.basiccat.imagetrans;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Recognition models for languages the bundled PP-OCRv6 model does not cover
 * (Korean, Arabic, Russian, Thai), matching the browser extension's
 * PADDLE_LANG_TO_MODEL mapping and PADDLE_MODEL_URLS entries. Latin-script
 * languages keep the bundled model.
 *
 * The extension downloads these through its background service worker and
 * caches the bytes. Here they are downloaded once into filesDir/models and
 * reused, because rapidocr4j only opens existing file paths. The files are
 * named after the URL's last segment, and only rec+dict are fetched: detection
 * stays on the bundled PP-OCRv6 DB model, as in the extension with its default
 * "small" detector.
 */
final class RemoteOcrModels {
    private static final String TAG = "RemoteOcrModels";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0 Mobile Safari/537.36";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int BUFFER_SIZE = 64 * 1024;
    /** Progress is reported in 5% steps, i.e. 20 updates per file at most. */
    private static final int PROGRESS_STEPS = 20;

    private static final String REC_V340 =
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.4.0/onnx/PP-OCRv5/rec/";
    private static final String DICT_V340 =
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.4.0/paddle/PP-OCRv5/rec/";
    // The extension's Arabic entries point at the repository's master branch,
    // so keep the same URLs here.
    private static final String REC_MASTER =
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5/rec/";
    private static final String DICT_MASTER =
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/paddle/PP-OCRv5/rec/";

    interface ProgressListener {
        void onProgress(String message);
    }

    static final class Model {
        final String displayName;
        final String recUrl;
        final String dictUrl;

        private Model(String displayName, String recUrl, String dictUrl) {
            this.displayName = displayName;
            this.recUrl = recUrl;
            this.dictUrl = dictUrl;
        }
    }

    private RemoteOcrModels() {
    }

    /**
     * The remote model for {@code sourceLang}, or null when the bundled
     * PP-OCRv6 model is used (Chinese, English, Japanese and Latin scripts).
     */
    static Model forLanguage(String sourceLang) {
        if (sourceLang == null) return null;
        switch (sourceLang) {
            case "ko":
                return new Model("韩语",
                        REC_V340 + "korean_PP-OCRv5_rec_mobile_infer.onnx",
                        DICT_V340 + "korean_PP-OCRv5_rec_mobile_infer/ppocrv5_korean_dict.txt");
            case "ru":
                return new Model("俄语",
                        REC_V340 + "eslav_PP-OCRv5_rec_mobile_infer.onnx",
                        DICT_V340 + "eslav_PP-OCRv5_rec_mobile_infer/ppocrv5_eslav_dict.txt");
            case "ar":
                return new Model("阿拉伯语",
                        REC_MASTER + "arabic_PP-OCRv5_rec_mobile.onnx",
                        DICT_MASTER + "arabic_PP-OCRv5_rec_mobile/ppocrv5_arabic_dict.txt");
            case "th":
                return new Model("泰语",
                        REC_V340 + "th_PP-OCRv5_rec_mobile_infer.onnx",
                        DICT_V340 + "th_PP-OCRv5_rec_mobile_infer/ppocrv5_th_dict.txt");
            default:
                return null;
        }
    }

    /**
     * Absolute paths {rec model, dictionary} for the model, downloading either
     * file on first use. Files already in filesDir/models are reused.
     */
    static String[] ensure(Context context, Model model, ProgressListener listener)
            throws Exception {
        File rec = ensureFile(context, model.recUrl, model.displayName + "识别模型", listener);
        File dict = ensureFile(context, model.dictUrl, model.displayName + "词典", listener);
        return new String[]{rec.getAbsolutePath(), dict.getAbsolutePath()};
    }

    private static File ensureFile(Context context, String url, String label,
                                   ProgressListener listener) throws Exception {
        File file = new File(NativeInference.modelDir(context), fileName(url));
        if (file.isFile() && file.length() > 0) {
            return file;
        }
        try {
            download(url, file, label, listener);
        } catch (Exception e) {
            throw new IOException("下载" + label + "失败: " + ImageUtils.humanError(e), e);
        }
        return file;
    }

    private static void download(String urlString, File target, String label,
                                 ProgressListener listener) throws Exception {
        Log.i(TAG, "downloading " + urlString);
        if (listener != null) listener.onProgress("正在下载" + label + "…");

        HttpURLConnection connection = null;
        File temp = new File(target.getPath() + ".tmp");
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            // ModelScope rejects some non-browser clients; send a UA similar to
            // the extension's.
            connection.setRequestProperty("User-Agent", USER_AGENT);

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            String contentType = connection.getContentType();
            if (contentType != null
                    && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/html")) {
                throw new IOException("服务器返回了网页而不是文件");
            }
            long total = connection.getContentLengthLong();
            long downloaded = 0;
            int lastStep = -1;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    if (total > 0 && listener != null) {
                        int step = (int) (downloaded * PROGRESS_STEPS / total);
                        if (step != lastStep) {
                            lastStep = step;
                            int percent = Math.min(100, step * 100 / PROGRESS_STEPS);
                            listener.onProgress("正在下载" + label + "… " + percent + "%");
                        }
                    }
                }
            }
            if (downloaded == 0) {
                throw new IOException("文件为空");
            }
            // Guard against a captive portal or error page being cached as a
            // model: a real PP-OCR rec model is >1 MB and a dict has hundreds
            // of entries.
            long minimum = target.getName().endsWith(".onnx") ? 1_000_000L : 512L;
            if (downloaded < minimum) {
                throw new IOException("文件过小（" + downloaded + " 字节）");
            }
            if (!temp.renameTo(target)) {
                // Another thread/process may have completed the same download;
                // accept a usable file either way.
                if (!target.isFile() || target.length() == 0) {
                    throw new IOException("无法写入 " + target.getName());
                }
            }
            Log.i(TAG, "downloaded " + target.getName() + " (" + downloaded + " bytes)");
        } finally {
            if (connection != null) connection.disconnect();
            if (temp.exists()) temp.delete();
        }
    }

    private static String fileName(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }
}
