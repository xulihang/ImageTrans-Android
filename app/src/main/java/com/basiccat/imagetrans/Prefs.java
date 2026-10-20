package com.basiccat.imagetrans;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Application settings stored in SharedPreferences.
 */
public final class Prefs {
    private static final String NAME = "imagetrans_settings";

    public static final String MODE_IMAGETRANS = "imagetrans";
    public static final String MODE_OPENAI = "openai";
    public static final String MODE_ORIGINAL = "original";

    public static final String DEFAULT_SERVER_URL = "https://local.basiccat.org:51043";
    public static final String DEFAULT_OPENAI_URL = "https://api.deepseek.com/v1";
    public static final String DEFAULT_OPENAI_MODEL = "deepseek-v4-flash";
    public static final String DEFAULT_RENDER_CSS = "text-align: center;\nborder-radius: 10%;";
    public static final String DEFAULT_PROMPT =
            "Translate the following texts from {sourceLang} to {targetLang}. " +
            "Return ONLY a JSON array of translated strings in the same order " +
            "(no markdown, no code fences).\nTexts: {texts}";

    public static final String[] LANG_CODES = {
            "auto", "zh", "ja", "en", "ko", "fr", "de", "es", "ru",
            "ar", "pt", "it", "th", "vi", "id", "tr", "pl", "nl"
    };

    public static final String[] LANG_NAMES = {
            "自动检测", "中文", "日语", "英语", "韩语", "法语", "德语", "西班牙语", "俄语",
            "阿拉伯语", "葡萄牙语", "意大利语", "泰语", "越南语", "印尼语", "土耳其语", "波兰语", "荷兰语"
    };

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String getMode(Context context) {
        return get(context).getString("mode", MODE_IMAGETRANS);
    }

    public static void setMode(Context context, String mode) {
        get(context).edit().putString("mode", mode).apply();
    }

    public static String getServerUrl(Context context) {
        return get(context).getString("server_url", DEFAULT_SERVER_URL);
    }

    public static void setServerUrl(Context context, String value) {
        get(context).edit().putString("server_url", value).apply();
    }

    public static String getDisplayName(Context context) {
        return get(context).getString("display_name", "");
    }

    public static void setDisplayName(Context context, String value) {
        get(context).edit().putString("display_name", value).apply();
    }

    public static String getPassword(Context context) {
        return get(context).getString("password", "");
    }

    public static void setPassword(Context context, String value) {
        get(context).edit().putString("password", value).apply();
    }

    public static String getSourceLang(Context context) {
        return get(context).getString("source_lang", "auto");
    }

    public static void setSourceLang(Context context, String value) {
        get(context).edit().putString("source_lang", value).apply();
    }

    public static String getTargetLang(Context context) {
        return get(context).getString("target_lang", "zh");
    }

    public static void setTargetLang(Context context, String value) {
        get(context).edit().putString("target_lang", value).apply();
    }

    public static String getOpenAiUrl(Context context) {
        return get(context).getString("openai_url", DEFAULT_OPENAI_URL);
    }

    public static void setOpenAiUrl(Context context, String value) {
        get(context).edit().putString("openai_url", value).apply();
    }

    public static String getOpenAiKey(Context context) {
        return get(context).getString("openai_key", "");
    }

    public static void setOpenAiKey(Context context, String value) {
        get(context).edit().putString("openai_key", value).apply();
    }

    public static String getOpenAiModel(Context context) {
        return get(context).getString("openai_model", DEFAULT_OPENAI_MODEL);
    }

    public static void setOpenAiModel(Context context, String value) {
        get(context).edit().putString("openai_model", value).apply();
    }

    public static String getOpenAiPrompt(Context context) {
        return get(context).getString("openai_prompt", DEFAULT_PROMPT);
    }

    public static void setOpenAiPrompt(Context context, String value) {
        get(context).edit().putString("openai_prompt", value).apply();
    }

    public static String getRenderCss(Context context) {
        return get(context).getString("render_css", DEFAULT_RENDER_CSS);
    }

    public static void setRenderCss(Context context, String value) {
        get(context).edit().putString("render_css", value).apply();
    }

    public static int getMinFontSize(Context context) {
        return get(context).getInt("min_font_size", 14);
    }

    public static void setMinFontSize(Context context, int value) {
        get(context).edit().putInt("min_font_size", value).apply();
    }

    public static String getTextRepairMode(Context context) {
        return get(context).getString("text_repair_mode", "white");
    }

    public static void setTextRepairMode(Context context, String value) {
        get(context).edit().putString("text_repair_mode", value).apply();
    }

    public static String langName(String code) {
        for (int i = 0; i < LANG_CODES.length; i++) {
            if (LANG_CODES[i].equals(code)) {
                return LANG_NAMES[i];
            }
        }
        return code;
    }

    public static String modeName(String mode) {
        if (MODE_IMAGETRANS.equals(mode)) return "ImageTrans 服务器";
        if (MODE_OPENAI.equals(mode)) return "本地 OCR + OpenAI";
        return "仅识别（不翻译）";
    }
}
