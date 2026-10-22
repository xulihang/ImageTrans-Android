package com.basiccat.imagetrans;

import android.content.Context;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MyMemory free translation API, matching the browser extension's preset.
 *
 * The public endpoint needs no API key, accepts at most 500 UTF-8 bytes per
 * query and raises the daily quota from 5000 to 50000 characters when a
 * contact email is supplied. The email comes from the settings and defaults
 * to the app's built-in address; clearing it switches to anonymous usage.
 * Long paragraphs are therefore split into chunks; each request reports the
 * quota error explicitly so the UI can show a useful message.
 */
public final class MyMemoryTranslator {
    private static final String ENDPOINT = "https://api.mymemory.translated.net/get";
    /** The API limit is 500 bytes; leave a little room. */
    private static final int MAX_QUERY_BYTES = 480;
    private static final long REQUEST_INTERVAL_MS = 120;

    private static long lastRequestAt;

    private MyMemoryTranslator() {
    }

    public static List<String> translate(Context context, List<String> texts) throws Exception {
        String source = sourceCode(Prefs.getSourceLang(context));
        String target = targetCode(Prefs.getTargetLang(context));
        String email = Prefs.getMyMemoryEmail(context);
        List<String> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(translateText(text, source, target, email));
        }
        return result;
    }

    /** MyMemory has no auto source; mirror the extension's "ja" choice. */
    private static String sourceCode(String code) {
        return code == null || code.isEmpty() || "auto".equals(code) ? "ja" : code;
    }

    /** MyMemory has no auto target; mirror the extension's "en" choice. */
    private static String targetCode(String code) {
        return code == null || code.isEmpty() || "auto".equals(code) ? "en" : code;
    }

    private static String translateText(String text, String source, String target,
                                        String email) throws Exception {
        if (text == null || text.trim().isEmpty()) return "";
        if (source.equals(target)) return text;

        String reflowed = reflow(source, text);
        List<String> chunks = splitByBytes(reflowed, MAX_QUERY_BYTES);
        String joiner = ("ja".equals(source) || "zh".equals(source)) ? "" : " ";
        StringBuilder result = new StringBuilder();
        for (String chunk : chunks) {
            if (chunk.trim().isEmpty()) continue;
            String translated = request(chunk, source, target, email);
            if (result.length() > 0) result.append(joiner);
            result.append(translated);
        }
        return result.toString();
    }

    private static String reflow(String source, String text) {
        boolean cjk = "ja".equals(source) || "zh".equals(source);
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\n", cjk ? "" : " ");
    }

    private static String request(String chunk, String source, String target, String email)
            throws Exception {
        throttle();
        StringBuilder url = new StringBuilder(ENDPOINT)
                .append("?q=").append(URLEncoder.encode(chunk, StandardCharsets.UTF_8.name()))
                .append("&langpair=").append(URLEncoder.encode(source + "|" + target,
                        StandardCharsets.UTF_8.name()));
        if (email != null && !email.trim().isEmpty()) {
            url.append("&de=").append(URLEncoder.encode(email.trim(),
                    StandardCharsets.UTF_8.name()));
        }
        HttpURLConnection connection = HttpUtils.open(url.toString());
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ImageTrans-Android");
            int status = connection.getResponseCode();
            String body = HttpUtils.readAll(status >= 400
                    ? connection.getErrorStream() : connection.getInputStream());
            if (status < 200 || status >= 300) {
                throw new Exception("MyMemory 返回 HTTP " + status + detailSuffix(body));
            }

            JSONObject root = new JSONObject(body);
            String details = root.optString("responseDetails", "");
            int responseStatus = root.optInt("responseStatus", 200);
            if (root.optBoolean("quotaFinished", false) || containsQuotaWarning(details)) {
                throw new Exception("MyMemory 今日可用额度已用完");
            }
            if (responseStatus != 200 && responseStatus != 0) {
                throw new Exception("MyMemory 翻译失败" + detailSuffix(details));
            }

            JSONObject data = root.optJSONObject("responseData");
            if (data == null) {
                throw new Exception("MyMemory 返回格式不正确" + detailSuffix(details));
            }
            String translated = data.optString("translatedText", "");
            if (translated.trim().isEmpty()) {
                throw new Exception("MyMemory 没有返回译文" + detailSuffix(details));
            }
            String upper = translated.toUpperCase(Locale.US);
            if (containsQuotaWarning(translated)
                    || upper.contains("NO QUERY SPECIFIED")
                    || upper.contains("PLEASE SELECT TWO DISTINCT LANGUAGES")
                    || upper.contains("INVALID LANGUAGE PAIR")) {
                throw new Exception("MyMemory: " + translated);
            }
            return translated;
        } finally {
            connection.disconnect();
        }
    }

    /** Keeps bursts of per-chunk requests from tripping the public rate limit. */
    private static synchronized void throttle() {
        long wait = REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private static boolean containsQuotaWarning(String text) {
        return text != null && text.toUpperCase(Locale.US).contains("MYMEMORY WARNING");
    }

    private static String detailSuffix(String text) {
        if (text == null) return "";
        String value = text.replace('\n', ' ').trim();
        if (value.isEmpty()) return "";
        return "：" + (value.length() > 200 ? value.substring(0, 200) + "…" : value);
    }

    /**
     * Splits at punctuation or whitespace near the limit. Uses code points so
     * surrogate pairs are never cut in half.
     */
    static List<String> splitByBytes(String text, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String value = new String(Character.toChars(codePoint));
            int bytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes + bytes > maxBytes && current.length() > 0) {
                int cut = lastBreak(current);
                if (cut <= 0) cut = current.length();
                String head = current.substring(0, cut).trim();
                if (!head.isEmpty()) chunks.add(head);
                String rest = current.substring(cut).trim();
                current.setLength(0);
                current.append(rest);
                currentBytes = rest.getBytes(StandardCharsets.UTF_8).length;
            }
            current.append(value);
            currentBytes += bytes;
            i += Character.charCount(codePoint);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) chunks.add(tail);
        if (chunks.isEmpty()) chunks.add("");
        return chunks;
    }

    private static int lastBreak(StringBuilder text) {
        int floor = Math.max(0, text.length() - 120);
        for (int i = text.length() - 1; i >= floor; i--) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n'
                    || c == '。' || c == '！' || c == '？' || c == '；'
                    || c == '.' || c == '!' || c == '?' || c == ';'
                    || c == '，' || c == ',') {
                return i + 1;
            }
        }
        return -1;
    }
}
