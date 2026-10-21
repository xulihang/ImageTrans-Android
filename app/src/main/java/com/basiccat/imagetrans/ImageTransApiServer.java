package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The ImageTrans server API for the browser extension, on the phone.
 *
 * The desktop server listens on 51042 (plain HTTP; 51043 is its HTTPS
 * port), so the extension's "ImageTrans server" mode can point here with
 *
 *     http://<phone-ip>:51042
 *
 * Supported API (the parts the extension calls):
 *
 *   POST /translate   application/x-www-form-urlencoded
 *       src                      image as a data URL
 *       sourceLang, targetLang   language codes ("auto" allowed)
 *       withoutImage "true"      boxes only (the extension renders them)
 *       displayName, password, headless, saveToFile   accepted, ignored
 *
 *   GET  /            status page
 *
 * Text is recognized on the device (same OCR as image translation) and
 * translated according to the app's settings: OpenAI when that mode is
 * selected, otherwise text is returned as is (recognize-only), which is what
 * the extension wants when it translates the boxes on its own side.
 */
public final class ImageTransApiServer {
    private static final String TAG = "ImageTransServer";
    public static final int PORT = 51042;
    private static final int MAX_REQUEST = 64 * 1024 * 1024;
    private static final long RENDER_TIMEOUT_MS = 120_000;

    private static ImageTransApiServer instance;

    public static synchronized ImageTransApiServer get() {
        if (instance == null) instance = new ImageTransApiServer();
        return instance;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService pool;
    private ServerSocket socket;

    private ImageTransApiServer() {
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Starts listening. Returns the IPv4 address to use, or null when it
     * cannot be determined. Throws when the port cannot be opened.
     */
    public synchronized String start(Context context) throws IOException {
        if (running.get()) return address();
        final Context app = context.getApplicationContext();
        try {
            socket = new ServerSocket(PORT);
        } catch (IOException e) {
            Log.e(TAG, "cannot listen on " + PORT, e);
            throw e;
        }
        pool = Executors.newFixedThreadPool(4);
        running.set(true);
        // OCR models may need a one-time download; get them ready now.
        pool.execute(() -> {
            try {
                OcrEngine.getInstance(app);
            } catch (Throwable throwable) {
                Log.w(TAG, "OCR init failed", throwable);
            }
        });
        pool.execute(() -> acceptLoop(socket, app));
        String address = address();
        Log.i(TAG, "listening on " + PORT + ", address " + address);
        return address;
    }

    public synchronized void stop() {
        running.set(false);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing.
            }
            socket = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        Log.i(TAG, "stopped");
    }

    /** The address the browser extension should use. */
    public String address() {
        if (!running.get()) return null;
        String ip = localIpv4();
        return ip == null ? null : "http://" + ip + ":" + PORT;
    }

    // --- HTTP -------------------------------------------------------------

    private void acceptLoop(ServerSocket server, Context app) {
        while (running.get() && !server.isClosed()) {
            try {
                Socket client = server.accept();
                ExecutorService executor = pool;
                if (executor == null || executor.isShutdown()) {
                    client.close();
                    return;
                }
                executor.execute(() -> handle(client, app));
            } catch (IOException e) {
                if (running.get()) Log.w(TAG, "accept failed: " + e);
            }
        }
    }

    private void handle(Socket client, Context app) {
        try (Socket socket = client) {
            socket.setSoTimeout(180_000);
            InputStream in = socket.getInputStream();
            Request request = readRequest(in);
            if (request == null) return;
            Response response;
            long startedAt = System.currentTimeMillis();
            try {
                if ("OPTIONS".equals(request.method)) {
                    response = new Response(204, "text/plain", new byte[0]);
                } else if ("/".equals(request.path)) {
                    response = Response.html(statusPage(app));
                } else if ("/translate".equals(request.path)) {
                    if (!"POST".equals(request.method)) {
                        response = Response.error(405, "Use POST");
                    } else {
                        response = Response.json(translate(app, request.body));
                    }
                } else {
                    response = Response.error(404, "Not found: " + request.path);
                }
            } catch (IllegalArgumentException e) {
                response = Response.error(400, e.getMessage());
            } catch (Throwable throwable) {
                Log.e(TAG, "request failed", throwable);
                response = Response.error(500, ImageUtils.humanError(throwable));
            }
            Log.i(TAG, request.method + " " + request.path + " -> " + response.status
                    + " in " + (System.currentTimeMillis() - startedAt) + "ms");
            writeResponse(socket.getOutputStream(), response, "HEAD".equals(request.method));
        } catch (Throwable throwable) {
            Log.w(TAG, "connection failed: " + throwable);
        }
    }

    private static final class Request {
        String method;
        String path;
        String body;
    }

    private static Request readRequest(InputStream in) throws IOException {
        String headText = readHead(in);
        if (headText == null) return null;
        String[] lines = headText.split("\r\n");
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) return null;
        Request request = new Request();
        request.method = requestLine[0];
        request.path = requestLine[1];
        int question = request.path.indexOf('?');
        if (question >= 0) request.path = request.path.substring(0, question);

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(),
                        lines[i].substring(colon + 1).trim());
            }
        }
        request.body = "";
        if (headers.getOrDefault("transfer-encoding", "").contains("chunked")) {
            request.body = readChunked(in);
        } else {
            int length = 0;
            try {
                length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
                // No body.
            }
            if (length > MAX_REQUEST) throw new IOException("Request too large");
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) break;
                read += n;
            }
            request.body = new String(body, 0, read, StandardCharsets.UTF_8);
        }
        return request;
    }

    /** Reads up to and including the blank line ending the headers. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            char expected = "\r\n\r\n".charAt(matched);
            if (b == expected) {
                matched++;
            } else {
                matched = b == '\r' ? 1 : 0;
            }
            if (head.size() > 65536) throw new IOException("Header too large");
        }
        return head.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static String readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) break;
            int size = Integer.parseInt(sizeLine.trim(), 16);
            if (size == 0) {
                readLine(in);
                break;
            }
            byte[] chunk = new byte[size];
            int read = 0;
            while (read < size) {
                int n = in.read(chunk, read, size - read);
                if (n < 0) break;
                read += n;
                if (body.size() + read > MAX_REQUEST) throw new IOException("Request too large");
            }
            body.write(chunk, 0, read);
            readLine(in);
        }
        return new String(body.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') return line.toString();
            if (b != '\r') line.append((char) b);
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static final class Response {
        int status;
        String contentType;
        byte[] body;

        Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        static Response json(String json) {
            return new Response(200, "application/json; charset=utf-8",
                    json.getBytes(StandardCharsets.UTF_8));
        }

        static Response html(String html) {
            return new Response(200, "text/html; charset=utf-8",
                    html.getBytes(StandardCharsets.UTF_8));
        }

        static Response error(int status, String message) {
            String body;
            try {
                // JSON, as the extension reads responses as JSON.
                body = new JSONObject().put("error", message == null ? "error" : message).toString();
            } catch (Exception e) {
                body = "{\"error\":\"error\"}";
            }
            return new Response(status, "application/json; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeResponse(OutputStream out, Response response, boolean headOnly)
            throws IOException {
        String head = "HTTP/1.1 " + response.status + " " + statusText(response.status) + "\r\n"
                + "Content-Type: " + response.contentType + "\r\n"
                + "Content-Length: " + response.body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) out.write(response.body);
        out.flush();
    }

    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            default: return "Internal Server Error";
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        if (body == null || body.isEmpty()) return form;
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                form.put(URLDecoder.decode(key, StandardCharsets.UTF_8.name()),
                        URLDecoder.decode(value, StandardCharsets.UTF_8.name()));
            } catch (Exception ignored) {
                // Malformed pair.
            }
        }
        return form;
    }

    // --- /translate -------------------------------------------------------

    private String translate(Context context, String formBody) throws Exception {
        Map<String, String> form = parseForm(formBody);
        String src = form.get("src");
        if (src == null || src.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing src");
        }
        Bitmap bitmap = ImageUtils.dataUrlToBitmap(src);
        if (bitmap == null) {
            // Not a data URL: the extension may send plain base64.
            try {
                byte[] bytes = Base64.decode(src, Base64.DEFAULT);
                bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception ignored) {
                // Reported below.
            }
        }
        if (bitmap == null) throw new IllegalArgumentException("Cannot decode src image");
        try {
            String sourceLang = form.get("sourceLang");
            if (sourceLang == null || sourceLang.isEmpty() || "auto".equals(sourceLang)) {
                sourceLang = Prefs.getSourceLang(context);
            }
            boolean onlyBoxes = "true".equals(form.get("withoutImage"));

            // Merge lines into paragraphs, as the desktop server does: the
            // extension translates what it gets.
            List<Box> boxes = TextBoxMerger.merge(
                    ImageUtils.detectOnly(context, bitmap, sourceLang), sourceLang, 15, 15);
            if (Prefs.MODE_OPENAI.equals(Prefs.getMode(context))) {
                List<String> texts = new ArrayList<>();
                for (Box box : boxes) texts.add(box.source);
                List<String> translations = OpenAiTranslator.translate(context, texts);
                for (int i = 0; i < boxes.size() && i < translations.size(); i++) {
                    boxes.get(i).target = translations.get(i);
                }
            } else {
                // Recognize-only: the extension translates the boxes itself.
                for (Box box : boxes) box.target = box.source;
            }

            JSONObject result = new JSONObject();
            JSONArray array = new JSONArray();
            for (Box box : boxes) array.put(box.toJson());
            result.put("imgMap", new JSONObject().put("boxes", array));
            if (onlyBoxes) return result.toString();

            List<Box> renderable = new ArrayList<>();
            for (Box box : boxes) {
                if (box.target != null && !box.target.trim().isEmpty()) renderable.add(box);
            }
            Bitmap rendered = renderable.isEmpty() ? bitmap : render(context, bitmap, renderable);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                rendered.compress(Bitmap.CompressFormat.JPEG, 90, out);
                result.put("img", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
            } finally {
                if (rendered != bitmap) ImageUtils.recycle(rendered);
            }
            return result.toString();
        } finally {
            ImageUtils.recycle(bitmap);
        }
    }

    /** Re-lays out the boxes through the app's renderer (needs the main thread). */
    private Bitmap render(Context context, Bitmap bitmap, List<Box> boxes) throws Exception {
        final Renderer[] holder = new Renderer[1];
        final Throwable[] error = new Throwable[1];
        final CountDownLatch created = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                holder[0] = new Renderer(new WebView(context));
            } catch (Throwable throwable) {
                error[0] = throwable;
            } finally {
                created.countDown();
            }
        });
        if (!created.await(15, TimeUnit.SECONDS)) throw new Exception("Renderer timeout");
        if (error[0] != null) throw new Exception(ImageUtils.humanError(error[0]));
        if (holder[0] == null) throw new Exception("Cannot create renderer");
        final Renderer renderer = holder[0];
        try {
            final Bitmap[] output = new Bitmap[1];
            final String[] failure = new String[1];
            final CountDownLatch latch = new CountDownLatch(1);
            final String dataUrl = ImageUtils.bitmapToDataUrl(bitmap);
            final JSONObject options = ImageUtils.buildRenderOptions(context);
            mainHandler.post(() -> renderer.render(boxes, dataUrl, options, new Renderer.Callback() {
                @Override
                public void onSuccess(Bitmap rendered, String url) {
                    output[0] = rendered;
                    latch.countDown();
                }

                @Override
                public void onError(String message) {
                    failure[0] = message;
                    latch.countDown();
                }
            }));
            if (!latch.await(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new Exception("Render timeout");
            }
            if (output[0] == null) {
                throw new Exception(failure[0] == null ? "Render failed" : failure[0]);
            }
            return output[0];
        } finally {
            mainHandler.post(renderer::destroy);
        }
    }

    // --- misc -------------------------------------------------------------

    private static String localIpv4() {
        try {
            List<String> addresses = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
            Collections.sort(addresses);
            if (!addresses.isEmpty()) return addresses.get(0);
        } catch (Exception ignored) {
            // No network.
        }
        return null;
    }

    private String statusPage(Context context) {
        String url = address();
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>ImageTrans</title><style>body{font-family:sans-serif;margin:24px;"
                + "line-height:1.7;color:#1F2933}code{background:#EEE;padding:2px 6px;"
                + "border-radius:4px}</style></head><body>"
                + "<h2>ImageTrans 图片翻译服务器运行中</h2>"
                + "<p>在 ImageTrans 浏览器扩展的服务器地址中填写：</p>"
                + "<p><code>" + (url == null ? "http://&lt;手机 IP&gt;:" + PORT : url) + "</code></p>"
                + "<p>翻译方式：" + Prefs.modeName(Prefs.getMode(context)) + "<br>"
                + "语言设置：" + Prefs.langName(Prefs.getSourceLang(context)) + " → "
                + Prefs.langName(Prefs.getTargetLang(context)) + "</p>"
                + "<p>接口：<code>POST /translate</code></p>"
                + "</body></html>";
    }
}
