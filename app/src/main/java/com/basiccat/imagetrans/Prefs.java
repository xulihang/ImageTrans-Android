package com.basiccat.imagetrans;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

/**
 * Application settings stored in SharedPreferences.
 */
public final class Prefs {
    private static final String NAME = "imagetrans_settings";

    public static final String MODE_IMAGETRANS = "imagetrans";
    public static final String MODE_MYMEMORY = "mymemory";
    public static final String MODE_OPENAI = "openai";
    public static final String MODE_ORIGINAL = "original";

    /** Use the image rendered by ImageTrans (its project's layout settings). */
    public static final String SERVER_RENDER_SERVER = "server";
    /** Request text boxes only and render them with the app's WebView settings. */
    public static final String SERVER_RENDER_APP = "app";

    /** Cloud server: Android cannot run the desktop ImageTrans server locally. */
    public static final String DEFAULT_SERVER_URL = "https://service.basiccat.org:51043";
    /** Old default; on a phone it resolves to the phone itself, so migrate it. */
    private static final String LEGACY_DEFAULT_SERVER_URL = "https://local.basiccat.org:51043";
    public static final String DEFAULT_OPENAI_URL = "https://api.deepseek.com/v1";
    public static final String DEFAULT_OPENAI_MODEL = "deepseek-flash";
    /** The previous default, replaced automatically for settings saved before. */
    private static final String LEGACY_DEFAULT_OPENAI_MODEL = "deepseek-v4-flash";
    public static final String DEFAULT_RENDER_CSS = "text-align: center;\nborder-radius: 10%;";
    public static final String DEFAULT_PROMPT =
            "Translate the following texts from {sourceLang} to {targetLang}. " +
            "Return ONLY a JSON array of translated strings in the same order " +
            "(no markdown, no code fences).\nTexts: {texts}";

    public static final String[] LANG_CODES = {
            "zh", "ja", "en", "ko", "fr", "de", "es", "ru",
            "ar", "pt", "it", "th", "vi", "id", "tr", "pl", "nl", "auto"
    };

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** UI language: "system", "zh" or "en". */
    public static String getUiLang(Context context) {
        return get(context).getString("ui_lang", LocaleHelper.SYSTEM);
    }

    public static void setUiLang(Context context, String value) {
        get(context).edit().putString("ui_lang", value).apply();
    }

    public static String getMode(Context context) {
        return get(context).getString("mode", MODE_IMAGETRANS);
    }

    public static void setMode(Context context, String mode) {
        get(context).edit().putString("mode", mode).apply();
    }

    public static String getServerUrl(Context context) {
        String value = get(context).getString("server_url", DEFAULT_SERVER_URL);
        // Settings saved by older builds contain the desktop-local default,
        // which can never work on a phone; use the cloud server instead.
        if (LEGACY_DEFAULT_SERVER_URL.equals(value)) return DEFAULT_SERVER_URL;
        return value;
    }

    public static void setServerUrl(Context context, String value) {
        get(context).edit().putString("server_url", value).apply();
    }

    public static String getServerRenderMode(Context context) {
        return get(context).getString("server_render_mode", SERVER_RENDER_SERVER);
    }

    public static void setServerRenderMode(Context context, String value) {
        get(context).edit().putString("server_render_mode", value).apply();
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
        return get(context).getString("source_lang", "zh");
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

    /**
     * MyMemory's optional "de" contact email. When the user has not entered
     * one, the built-in address is used (it is never shown in the UI).
     */
    public static final String DEFAULT_MYMEMORY_EMAIL = "admin@basiccat.org";

    /** The user-entered override, or "" when the built-in address is used. */
    public static String getMyMemoryEmailOverride(Context context) {
        String value = get(context).getString("mymemory_email", "");
        return value == null ? "" : value;
    }

    /** The address actually sent to MyMemory. */
    public static String getMyMemoryEmail(Context context) {
        String value = getMyMemoryEmailOverride(context).trim();
        return value.isEmpty() ? DEFAULT_MYMEMORY_EMAIL : value;
    }

    public static void setMyMemoryEmail(Context context, String value) {
        get(context).edit().putString("mymemory_email",
                value == null ? "" : value.trim()).apply();
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
        String value = get(context).getString("openai_model", DEFAULT_OPENAI_MODEL);
        // Settings saved by older builds contain the old default; switch them
        // to the new one unless the user picks another model.
        if (LEGACY_DEFAULT_OPENAI_MODEL.equals(value)) return DEFAULT_OPENAI_MODEL;
        return value;
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

    public static String langName(Context context, String code) {
        String[] names = context.getResources().getStringArray(R.array.language_names);
        for (int i = 0; i < LANG_CODES.length && i < names.length; i++) {
            if (LANG_CODES[i].equals(code)) return names[i];
        }
        return code;
    }

    // --- floating screenshot translation ---------------------------------

    public static boolean isFloatingEnabled(Context context) {
        return get(context).getBoolean("floating_enabled", false);
    }

    public static void setFloatingEnabled(Context context, boolean value) {
        get(context).edit().putBoolean("floating_enabled", value).apply();
    }

    public static boolean hasOverlayPermission(Context context) {
        return Settings.canDrawOverlays(context);
    }

    // --- background server (browser extension) ----------------------------

    public static boolean isServerEnabled(Context context) {
        return get(context).getBoolean("server_enabled", false);
    }

    public static void setServerEnabled(Context context, boolean value) {
        get(context).edit().putBoolean("server_enabled", value).apply();
    }

    public static boolean isOverlayAsked(Context context) {
        return get(context).getBoolean("overlay_asked", false);
    }

    public static void setOverlayAsked(Context context) {
        get(context).edit().putBoolean("overlay_asked", true).apply();
    }

    public static Intent overlayPermissionIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
    }

    public static int getFloatingBallX(Context context) {
        return get(context).getInt("floating_ball_x", 0);
    }

    public static int getFloatingBallY(Context context) {
        return get(context).getInt("floating_ball_y",
                Math.round(300 * context.getResources().getDisplayMetrics().density));
    }

    public static void setFloatingBallX(Context context, int value) {
        get(context).edit().putInt("floating_ball_x", value).apply();
    }

    public static void setFloatingBallY(Context context, int value) {
        get(context).edit().putInt("floating_ball_y", value).apply();
    }

    public static int getFloatingPanelX(Context context) {
        return get(context).getInt("floating_panel_x",
                Math.round(24 * context.getResources().getDisplayMetrics().density));
    }

    public static int getFloatingPanelY(Context context) {
        return get(context).getInt("floating_panel_y",
                Math.round(400 * context.getResources().getDisplayMetrics().density));
    }

    public static void setFloatingPanelX(Context context, int value) {
        get(context).edit().putInt("floating_panel_x", value).apply();
    }

    public static void setFloatingPanelY(Context context, int value) {
        get(context).edit().putInt("floating_panel_y", value).apply();
    }

    public static int getFloatingPanelWidth(Context context) {
        int screen = context.getResources().getDisplayMetrics().widthPixels;
        return Math.min(get(context).getInt("floating_panel_width", Math.round(screen * 0.66f)),
                screen);
    }

    public static int getFloatingPanelHeight(Context context) {
        int screen = context.getResources().getDisplayMetrics().heightPixels;
        return Math.min(get(context).getInt("floating_panel_height", Math.round(screen * 0.5f)),
                screen);
    }

    public static void setFloatingPanelWidth(Context context, int value) {
        get(context).edit().putInt("floating_panel_width", value).apply();
    }

    public static void setFloatingPanelHeight(Context context, int value) {
        get(context).edit().putInt("floating_panel_height", value).apply();
    }

    public static String modeName(Context context, String mode) {
        if (MODE_IMAGETRANS.equals(mode)) return context.getString(R.string.mode_imagetrans);
        if (MODE_MYMEMORY.equals(mode)) return context.getString(R.string.mode_mymemory);
        if (MODE_OPENAI.equals(mode)) return context.getString(R.string.mode_openai);
        return context.getString(R.string.mode_original);
    }
}
