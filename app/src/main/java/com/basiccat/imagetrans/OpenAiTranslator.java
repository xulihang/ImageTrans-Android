package com.basiccat.imagetrans;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible chat/completions translator, matching the extension's
 * "OpenAI-compatible API" mode.
 */
public final class OpenAiTranslator {
    private OpenAiTranslator() {
    }

    public static List<String> translate(Context context, List<String> texts) throws Exception {
        String apiUrl = Prefs.getOpenAiUrl(context);
        String apiKey = Prefs.getOpenAiKey(context);
        String model = Prefs.getOpenAiModel(context);
        String promptTemplate = Prefs.getOpenAiPrompt(context);
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            throw new Exception("未设置 OpenAI 兼容 API 地址");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new Exception("未设置 OpenAI API Key");
        }

        String sourceLang = Prefs.getSourceLang(context);
        String targetLang = Prefs.getTargetLang(context);
        String sourceLabel = "auto".equals(sourceLang) ? "the source language"
                : Prefs.langName(context, sourceLang);
        String targetLabel = "auto".equals(targetLang) ? "English"
                : Prefs.langName(context, targetLang);

        JSONArray textsJson = new JSONArray();
        for (String text : texts) textsJson.put(text == null ? "" : text);

        String prompt = promptTemplate
                .replace("{sourceLang}", sourceLabel)
                .replace("{targetLang}", targetLabel)
                .replace("{texts}", textsJson.toString());

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", prompt);
        JSONArray messages = new JSONArray();
        messages.put(message);

        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("messages", messages);

        String base = apiUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpURLConnection connection = HttpUtils.open(base + "/chat/completions");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
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
            throw new Exception("OpenAI API 返回 HTTP " + status
                    + (responseText.isEmpty() ? "" : ": " + shorten(responseText)));
        }

        JSONObject root = new JSONObject(responseText);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("OpenAI API 没有返回结果");
        }
        JSONObject first = choices.optJSONObject(0);
        if (first == null) throw new Exception("OpenAI API 返回格式不正确");
        JSONObject messageObject = first.optJSONObject("message");
        String content = messageObject == null ? "" : messageObject.optString("content", "");
        if (content.isEmpty()) throw new Exception("OpenAI API 返回了空内容");

        JSONArray translations = parseTranslations(content);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (i < translations.length()) {
                result.add(translations.optString(i, ""));
            } else {
                result.add("");
            }
        }
        return result;
    }

    private static JSONArray parseTranslations(String content) throws Exception {
        String value = content.trim();
        int start = value.indexOf('[');
        int end = value.lastIndexOf(']');
        if (start >= 0 && end > start) {
            value = value.substring(start, end + 1);
        }
        try {
            JSONArray array = new JSONArray(value);
            return array;
        } catch (Exception ignored) {
            // Some models return {"translations": [...]} or {"texts": [...]}.
            try {
                JSONObject object = new JSONObject(value);
                JSONArray array = object.optJSONArray("translations");
                if (array == null) array = object.optJSONArray("texts");
                if (array != null) return array;
            } catch (Exception ignored2) {
                // fall through
            }
            throw new Exception("无法解析翻译结果，返回内容: " + shorten(content));
        }
    }

    private static String shorten(String text) {
        String value = text.replace('\n', ' ').trim();
        return value.length() > 300 ? value.substring(0, 300) + "…" : value;
    }
}
