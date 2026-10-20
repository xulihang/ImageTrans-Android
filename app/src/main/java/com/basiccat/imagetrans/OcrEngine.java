package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.List;

import io.github.hzkitty.RapidOCR;
import io.github.hzkitty.entity.OcrResult;
import io.github.hzkitty.entity.RecResult;

/**
 * Thin wrapper around rapidocr4j-android. The bundled model is the
 * PP-OCRv4 Chinese/English model, so local OCR is primarily useful for those
 * languages; use the ImageTrans server for Japanese or other languages.
 */
public final class OcrEngine {
    private static final String TAG = "OcrEngine";
    private static volatile OcrEngine instance;

    private final Context appContext;
    private RapidOCR rapidOCR;

    private OcrEngine(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static OcrEngine getInstance(Context context) {
        if (instance == null) {
            synchronized (OcrEngine.class) {
                if (instance == null) {
                    instance = new OcrEngine(context);
                }
            }
        }
        return instance;
    }

    public synchronized void initialize() throws Exception {
        if (rapidOCR != null) return;
        long start = System.currentTimeMillis();
        try {
            rapidOCR = RapidOCR.create(appContext);
        } catch (Throwable throwable) {
            throw new Exception("OCR 引擎初始化失败: " + ImageUtils.humanError(throwable), throwable);
        }
        Log.i(TAG, "RapidOCR initialized in " + (System.currentTimeMillis() - start) + " ms");
    }

    public synchronized List<Box> recognize(Bitmap bitmap) throws Exception {
        initialize();
        long start = System.currentTimeMillis();
        OcrResult result = rapidOCR.run(bitmap);
        List<Box> boxes = new ArrayList<>();
        if (result == null || result.getRecRes() == null) {
            return boxes;
        }
        for (RecResult rec : result.getRecRes()) {
            if (rec == null) continue;
            String text = rec.getText();
            if (text == null || text.trim().isEmpty()) continue;
            if (rec.getConfidence() < 0.30f) continue;
            Point[] points = rec.getDtBoxes();
            if (points == null || points.length == 0) continue;

            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (Point point : points) {
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
            }
            if (maxX <= minX || maxY <= minY) continue;
            boxes.add(new Box(minX, minY, maxX - minX, maxY - minY, text.trim()));
        }
        Log.i(TAG, "OCR finished in " + (System.currentTimeMillis() - start)
                + " ms, boxes=" + boxes.size());
        return boxes;
    }
}
