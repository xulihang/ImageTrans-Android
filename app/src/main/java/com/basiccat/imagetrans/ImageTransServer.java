package com.basiccat.imagetrans;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the local/public ImageTrans server.
 *
 * The endpoint is the same one used by the Chrome extension:
 *
 *   POST {serverURL}/translate
 *   Content-Type: application/x-www-form-urlencoded
 *
 * By default the server renders the translated image (its project's layout
 * settings apply) and we display it directly. If the "app rendering" option
 * is selected, withoutImage=true asks for text boxes instead, which the app's
 * WebView renders with its own CSS/font settings. Both response shapes are
 * supported either way.
 */
public final class ImageTransServer {
    public static class Result {
        public final List<Box> boxes = new ArrayList<>();
        public String renderedImageDataUrl;
    }

    private ImageTransServer() {
    }

    public static Result translate(Context context, String imageDataUrl) throws Exception {
        return translate(context, imageDataUrl, null, null);
    }

    /**
     * @param boxes  text lines to translate instead of detecting them again,
     *               or null to let the server recognize the text
     * @param panels the panels the lines belong to (comics), or null
     */
    public static Result translate(Context context, String imageDataUrl,
                                   JSONArray boxes, JSONArray panels) throws Exception {
        String serverUrl = Prefs.getServerUrl(context);
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            throw new Exception("未设置 ImageTrans 服务器地址");
        }
        serverUrl = serverUrl.trim();
        while (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }

        String displayName = Prefs.getDisplayName(context);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "default";
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("src", imageDataUrl);
        form.put("saveToFile", "true");
        form.put("displayName", displayName);
        form.put("password", Prefs.getPassword(context));
        form.put("headless", "true");
        if (Prefs.SERVER_RENDER_APP.equals(Prefs.getServerRenderMode(context))) {
            form.put("withoutImage", "true");
        }
        if (boxes != null) form.put("boxes", boxes.toString());
        if (panels != null) form.put("panels", panels.toString());

        String sourceLang = Prefs.getSourceLang(context);
        String targetLang = Prefs.getTargetLang(context);
        if (sourceLang != null && !sourceLang.isEmpty() && !"auto".equals(sourceLang)) {
            form.put("sourceLang", sourceLang);
        }
        if (targetLang != null && !targetLang.isEmpty() && !"auto".equals(targetLang)) {
            form.put("targetLang", targetLang);
        }

        HttpURLConnection connection = HttpUtils.open(serverUrl + "/translate");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] body = HttpUtils.encodeForm(form).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }

        int status = connection.getResponseCode();
        String responseText = HttpUtils.readAll(status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream());
        connection.disconnect();

        if (status < 200 || status >= 300) {
            throw new Exception("ImageTrans 服务器返回 HTTP " + status
                    + (responseText.isEmpty() ? "" : ": " + shorten(responseText)));
        }
        if (responseText.isEmpty()) {
            throw new Exception("ImageTrans 服务器返回了空响应");
        }

        JSONObject root = new JSONObject(responseText);
        Result result = new Result();

        JSONObject imgMap = root.optJSONObject("imgMap");
        if (imgMap != null) {
            JSONArray array = imgMap.optJSONArray("boxes");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    Box box = parseBox(item);
                    if (box != null) result.boxes.add(box);
                }
            }
        }

        String img = root.optString("img", "");
        if (img != null && !img.isEmpty()) {
            result.renderedImageDataUrl = img.startsWith("data:")
                    ? img
                    : "data:image/jpeg;base64," + img;
        }

        if (result.boxes.isEmpty() && result.renderedImageDataUrl == null) {
            throw new Exception("服务器没有返回文字框或图片，请检查 ImageTrans 服务器和语言设置");
        }
        return result;
    }

    private static Box parseBox(JSONObject item) {
        JSONObject geometry = item.optJSONObject("geometry");
        if (geometry == null) return null;

        double x = optDouble(geometry, "X", "x");
        double y = optDouble(geometry, "Y", "y");
        double width = optDouble(geometry, "width", "Width");
        double height = optDouble(geometry, "height", "Height");
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(width) || Double.isNaN(height)
                || width <= 0 || height <= 0) {
            return null;
        }

        String source = item.optString("source", "");
        if (source.isEmpty()) source = item.optString("text", "");
        String target = item.optString("target", "");
        if (target.isEmpty()) target = source;

        Box box = new Box(x, y, width, height, source);
        box.target = target;
        return box.hasText() ? box : null;
    }

    private static double optDouble(JSONObject object, String firstKey, String secondKey) {
        if (object.has(firstKey)) return object.optDouble(firstKey, Double.NaN);
        if (object.has(secondKey)) return object.optDouble(secondKey, Double.NaN);
        return Double.NaN;
    }

    private static String shorten(String text) {
        String value = text.replace('\n', ' ').trim();
        return value.length() > 300 ? value.substring(0, 300) + "…" : value;
    }
}
