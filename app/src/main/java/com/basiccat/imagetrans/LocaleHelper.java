package com.basiccat.imagetrans;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * Applies the user-selected UI language to a Context.
 *
 * Activities and the foreground service call this from attachBaseContext(),
 * so every screen and notification uses the same locale without restarting
 * the whole process.
 */
public final class LocaleHelper {
    public static final String SYSTEM = "system";
    public static final String ZH = "zh";
    public static final String EN = "en";
    public static final String[] LANGUAGES = {SYSTEM, ZH, EN};

    private LocaleHelper() {
    }

    public static Context wrap(Context context) {
        String language = Prefs.getUiLang(context);
        if (SYSTEM.equals(language)) {
            language = systemLanguage(context);
        }
        if (!EN.equals(language) && !ZH.equals(language)) language = ZH;
        Locale locale = new Locale(language);
        Locale.setDefault(locale);
        Configuration configuration =
                new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);
        return context.createConfigurationContext(configuration);
    }

    /**
     * Only Chinese and English are shipped. English system locales use
     * English; everything else falls back to Chinese.
     */
    private static String systemLanguage(Context context) {
        Configuration configuration = context.getResources().getConfiguration();
        Locale system = configuration.getLocales().isEmpty()
                ? Locale.getDefault() : configuration.getLocales().get(0);
        return "en".equals(system.getLanguage()) ? EN : ZH;
    }
}
