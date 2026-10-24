package com.basiccat.imagetrans;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ImageUtils {
    private static final String TAG = "ImageUtils";
    private static final int MAX_DIMENSION = 3500;
    private static final long MAX_PIXELS = 12_000_000L;
    /** Tall images may keep up to this width instead of being squeezed to MAX_DIMENSION. */
    private static final int MAX_TALL_WIDTH = 2000;
    /** height > width * 8 is a webtoon: OCR and rendering split it into parts. */
    private static final int TALL_ASPECT = 8;
    private static final int MAX_SAMPLE = 8;

    private ImageUtils() {
    }

    public static Bitmap decodeBitmap(Context context, Uri uri, int maxDimension) throws Exception {
        ContentResolver resolver = context.getContentResolver();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new Exception(context.getString(R.string.error_image_dimensions));
        }

        int orientation = readOrientation(context, uri);
        // BitmapFactory's bounds are in the encoded orientation; a rotated
        // image swaps width and height once decoded.
        boolean swapsAxes = orientation == ExifInterface.ORIENTATION_ROTATE_90
                || orientation == ExifInterface.ORIENTATION_ROTATE_270
                || orientation == ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE;
        int orientedWidth = swapsAxes ? bounds.outHeight : bounds.outWidth;
        int orientedHeight = swapsAxes ? bounds.outWidth : bounds.outHeight;
        if (isTall(orientedWidth, orientedHeight)) {
            // A long webtoon cannot use the maxDimension cap: squeezing the
            // long side to 3500 would leave a handful of pixels of width.
            // ImageDecoder scales during decode, so no oversized intermediate
            // bitmap is allocated.
            Bitmap tall = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? decodeTall(context, uri, orientedWidth, orientedHeight)
                    : null;
            if (tall != null) return tall;
            Log.w(TAG, "ImageDecoder unavailable for tall image, falling back to BitmapFactory");
        }

        int sample = 1;
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (longSide / (sample * 2) >= maxDimension && sample < MAX_SAMPLE) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) {
            throw new Exception(context.getString(R.string.error_image_decode));
        }

        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            bitmap = applyOrientation(bitmap, orientation);
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longest = Math.max(width, height);
        float scale = 1f;
        if (longest > maxDimension) {
            scale = (float) maxDimension / (float) longest;
        }
        long pixels = (long) width * (long) height;
        if (pixels > MAX_PIXELS) {
            float pixelScale = (float) Math.sqrt((double) MAX_PIXELS / (double) pixels);
            scale = Math.min(scale, pixelScale);
        }
        if (scale < 1f) {
            int newWidth = Math.max(1, Math.round(width * scale));
            int newHeight = Math.max(1, Math.round(height * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
            if (scaled != bitmap) {
                bitmap.recycle();
            }
            bitmap = scaled;
        }
        return bitmap;
    }

    public static Bitmap decodeBitmap(Context context, Uri uri) throws Exception {
        return decodeBitmap(context, uri, MAX_DIMENSION);
    }

    private static int readOrientation(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return ExifInterface.ORIENTATION_NORMAL;
            ExifInterface exif = new ExifInterface(in);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** Whether the image is a webtoon-style strip that OCR/rendering must split. */
    static boolean isTall(int width, int height) {
        return width > 0 && height > (long) width * TALL_ASPECT;
    }

    /**
     * Decodes a tall image with ImageDecoder, which scales during decode (so no
     * huge intermediate bitmap) and applies EXIF orientation itself. The target
     * keeps the strip's width and caps the total pixel count instead of the
     * long side, which is what makes webtoon text readable.
     */
    @android.annotation.TargetApi(28)
    private static Bitmap decodeTall(Context context, Uri uri, int orientedWidth, int orientedHeight) {
        float scale = Math.min(1f, Math.min(
                (float) MAX_TALL_WIDTH / orientedWidth,
                (float) Math.sqrt((double) MAX_PIXELS / ((double) orientedWidth * orientedHeight))));
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
            Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                Size size = info.getSize();
                int width = Math.max(1, Math.round(size.getWidth() * scale));
                int height = Math.max(1, Math.round(size.getHeight() * scale));
                decoder.setTargetSize(width, height);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                // Keep the bitmap at 8 bits per channel; RGBA_F16 would double
                // the memory a webtoon already needs.
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            });
            if (bitmap != null) {
                Log.i(TAG, "tall image decoded " + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " (scale " + scale + ")");
            }
            return bitmap;
        } catch (Throwable throwable) {
            Log.w(TAG, "tall decode failed", throwable);
            return null;
        }
    }

    private static Bitmap applyOrientation(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270);
                matrix.postScale(-1, 1);
                break;
            default:
                return bitmap;
        }
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    public static String bitmapToDataUrl(Bitmap bitmap) {
        // Always send JPEG to the OCR/translation backend. It keeps the base64
        // payload small enough for the WebView bridge and for the server form.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    public static Bitmap dataUrlToBitmap(String dataUrl) {
        if (dataUrl == null) return null;
        int comma = dataUrl.indexOf(',');
        String base64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    /** Saves the image to the gallery, as Pictures/ImageTrans on every version. */
    public static Uri saveToGallery(Context context, Bitmap bitmap) {
        if (!isAtLeastQ()) return saveToGalleryLegacy(context, bitmap);
        String name = "imagetrans_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ImageTrans");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = context.getContentResolver().insert(collection, values);
        if (uri == null) {
            return null;
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) return null;
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            return null;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        context.getContentResolver().update(uri, done, null, null);
        return uri;
    }

    /**
     * Before scoped storage (API 29) an image reaches the gallery by writing a
     * file into Pictures/ and letting the media scanner index it; RELATIVE_PATH
     * and IS_PENDING do not exist yet. Needs WRITE_EXTERNAL_STORAGE (declared
     * for API <= 28 only).
     */
    private static Uri saveToGalleryLegacy(Context context, Bitmap bitmap) {
        String name = "imagetrans_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "ImageTrans");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File file = new File(dir, name);
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            Log.w(TAG, "saving to " + file + " failed", e);
            return null;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.DATA, file.getAbsolutePath());
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        Uri uri = context.getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) return uri;
        // The insert failed: ask the scanner to index the file instead.
        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
        return Uri.fromFile(file);
    }

    /**
     * Saves the image in the app cache for sharing through FileProvider; only
     * for sharing, it does not show up in the gallery.
     */
    public static Uri saveToCache(Context context, Bitmap bitmap) {
        String name = "imagetrans_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        File file = new File(context.getCacheDir(), name);
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            return null;
        }
        return FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", file);
    }

    public static void shareImage(Context context, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent,
                context.getString(R.string.translate_share_chooser)));
    }

    public static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static String humanError(Throwable throwable) {
        if (throwable == null) return "未知错误";
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    public static boolean isAtLeastQ() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    public static void recycle(Bitmap bitmap) {
        if (bitmap != null) bitmap.recycle();
    }

    /** Bitmaps for the current settings, or null when there is no text. */
    public static final class Result {
        /** All detected boxes, including ones without a translation. */
        public final List<Box> boxes;

        Result(List<Box> boxes) {
            this.boxes = boxes;
        }
    }

    /**
     * Detects and translates text on a bitmap with the current settings
     * (server, local OCR + OpenAI, or recognize only). Returns null when
     * nothing was found; otherwise the caller renders with the returned boxes.
     */
    public static Result detectAndTranslate(Context context, Bitmap bitmap) throws Exception {
        String mode = Prefs.getMode(context);
        List<Box> boxes;
        if (Prefs.MODE_IMAGETRANS.equals(mode)) {
            boxes = translateOnServer(context, bitmap);
        } else {
            String sourceLang = Prefs.getSourceLang(context);
            boxes = OcrEngine.getInstance(context).recognize(bitmap, sourceLang, message -> { });
            if (boxes.isEmpty()) throw new Exception(context.getString(R.string.error_no_text));
            boxes = PanelMerger.merge(boxes, sourceLang, 15, 15, null);
            if (Prefs.MODE_OPENAI.equals(mode) || Prefs.MODE_MYMEMORY.equals(mode)) {
                List<String> texts = new ArrayList<>();
                for (Box box : boxes) texts.add(box.source);
                List<String> translations = Prefs.MODE_MYMEMORY.equals(mode)
                        ? MyMemoryTranslator.translate(context, texts)
                        : OpenAiTranslator.translate(context, texts);
                for (int i = 0; i < boxes.size() && i < translations.size(); i++) {
                    boxes.get(i).target = translations.get(i);
                }
            } else {
                for (Box box : boxes) box.target = box.source;
            }
        }
        for (Box box : boxes) {
            if (box.target != null && !box.target.trim().isEmpty()) return new Result(boxes);
        }
        return null;
    }

    /**
     * Desktop server mode: the app recognizes the text and groups the lines
     * into panels itself, and the server only translates them. Letting the
     * server recognize and merge again would group the lines its own way.
     */
    private static List<Box> translateOnServer(Context context, Bitmap bitmap) throws Exception {
        String sourceLang = Prefs.getSourceLang(context);
        List<Box> lines = OcrEngine.getInstance(context).recognize(bitmap, sourceLang,
                message -> { });
        if (lines.isEmpty()) throw new Exception(context.getString(R.string.error_no_text));
        JSONArray panels = new JSONArray();
        List<Box> merged = PanelMerger.merge(lines, sourceLang, 15, 15, panels);
        JSONArray boxes = new JSONArray();
        for (Box box : merged) {
            JSONObject item = box.toJson();
            JSONObject geometry = item.optJSONObject("geometry");
            if (geometry != null) {
                // "text" with x/y, the form the extension sends.
                geometry.put("x", box.x);
                geometry.put("y", box.y);
                geometry.remove("X");
                geometry.remove("Y");
            }
            item.remove("source");
            item.put("text", box.source == null ? "" : box.source);
            boxes.put(item);
        }
        return ImageTransServer.translate(context, bitmapToDataUrl(bitmap), boxes, panels).boxes;
    }

    /**
     * OCR only: single text lines without paragraph merging. The browser
     * extension merges lines into paragraphs itself, so its server API
     * returns the lines as recognized.
     */
    public static List<Box> detectOnly(Context context, Bitmap bitmap, String sourceLang)
            throws Exception {
        return OcrEngine.getInstance(context).recognize(bitmap, sourceLang, message -> { });
    }

    public static JSONObject buildRenderOptions(Context context) throws JSONException {
        JSONObject options = new JSONObject();
        options.put("renderMode", "dom");
        options.put("renderTextCSS", renderTextCss(context));
        options.put("minFontSize", Prefs.getMinFontSize(context));
        options.put("fontScale", Prefs.getFontScale(context) / 100f);
        options.put("textRepairMode", Prefs.getTextRepairMode(context));
        options.put("sourceLang", Prefs.getSourceLang(context));
        options.put("targetLang", Prefs.getTargetLang(context));
        return options;
    }

    /** The user's text-box CSS with the vertical-layout option applied. */
    private static String renderTextCss(Context context) {
        String css = Prefs.getRenderCss(context);
        if (!Prefs.isVerticalText(context)) return css;
        // Override text-align because it aligns horizontally in horizontal
        // mode, but would align vertically (start = top) here.
        css = css == null ? "" : css.replaceAll("(?i)text-align\\s*:[^;]*;?", "");
        if (!css.isEmpty() && !css.endsWith(";")) css += ";";
        // In vertical mode text-align aligns the columns vertically, so
        // "start" is vertical-align: top.
        return css + "writing-mode: vertical-rl!important;text-align: start!important;";
    }
}
