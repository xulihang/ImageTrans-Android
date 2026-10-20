package com.basiccat.imagetrans;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class HttpUtils {
    private HttpUtils() {
    }

    /**
     * Opens a normal HTTPS connection. Certificate validation is left to the
     * platform. If the ImageTrans server uses a self-signed certificate, the
     * certificate should be installed as a user CA on the device (the app's
     * network security config trusts user certificates); HTTP URLs can be used
     * for local testing.
     */
    public static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(180000);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    public static String encodeForm(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        try {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getValue() == null) continue;
                if (builder.length() > 0) builder.append('&');
                builder.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                builder.append('=');
                builder.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable", e);
        }
        return builder.toString();
    }

    public static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
