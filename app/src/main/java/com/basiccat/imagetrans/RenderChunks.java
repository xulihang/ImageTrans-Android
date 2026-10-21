package com.basiccat.imagetrans;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Splits a webtoon-sized bitmap into vertical chunks small enough for the
 * offscreen WebView renderer, then the caller stitches them into one image.
 *
 * Webtoons are the only images this applies to: for a 10000+ px tall strip a
 * single canvas (plus html-to-image's overlay canvas) is tens of MB, which
 * exceeds Android WebView's canvas/GPU limits. Unlike the OCR parts, render
 * cuts are placed in vertical gaps between boxes, so a translated text box is
 * not cut in half by a chunk boundary.
 */
final class RenderChunks {
    private static final String TAG = "RenderChunks";
    /** Keep each WebView canvas well below the memory and GPU limits. */
    static final int MAX_RENDER_PIXELS = 6_000_000;
    static final int MAX_RENDER_SIDE = 4096;

    static final class Chunk {
        /** Top edge in the source image; the chunk covers [top, top+height). */
        final int top;
        final int height;
        /** Boxes assigned to this chunk, translated into chunk coordinates. */
        final List<Box> boxes;

        Chunk(int top, int height, List<Box> boxes) {
            this.top = top;
            this.height = height;
            this.boxes = boxes;
        }
    }

    private RenderChunks() {
    }

    /** Webtoon strips that are too large for a single offscreen render. */
    static boolean needsSplit(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (!ImageUtils.isTall(width, height)) return false;
        return (long) width * height > MAX_RENDER_PIXELS
                || Math.max(width, height) > MAX_RENDER_SIDE;
    }

    static List<Chunk> split(Bitmap bitmap, List<Box> boxes) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int maxHeight = Math.max(1,
                Math.min(MAX_RENDER_SIDE, MAX_RENDER_PIXELS / Math.max(1, width)));

        List<Integer> cuts = new ArrayList<>();
        cuts.add(0);
        int top = 0;
        while (height - top > maxHeight) {
            int target = top + maxHeight;
            int minCut = top + Math.max(1, maxHeight / 2);
            int cut = chooseCut(boxes, top, minCut, target);
            cuts.add(cut);
            top = cut;
        }
        cuts.add(height);

        List<Chunk> chunks = new ArrayList<>(cuts.size() - 1);
        for (int i = 0; i + 1 < cuts.size(); i++) {
            int start = cuts.get(i);
            int end = cuts.get(i + 1);
            List<Box> chunkBoxes = new ArrayList<>();
            for (Box box : boxes) {
                double center = box.centerY();
                if (center >= start && (center < end || (i + 2 == cuts.size() && center <= end))) {
                    Box copy = new Box(box.x, box.y - start, box.width, box.height, box.source);
                    copy.target = box.target;
                    chunkBoxes.add(copy);
                }
            }
            chunks.add(new Chunk(start, end - start, chunkBoxes));
        }
        Log.i(TAG, "rendering " + chunks.size() + " chunks, maxHeight=" + maxHeight);
        return chunks;
    }

    /**
     * Picks a cut between {@code minCut} and {@code target}, preferring the
     * largest gap between boxes so translations that grow slightly still fit
     * inside the chunk. Falls back to the target when a box covers everything.
     */
    private static int chooseCut(List<Box> boxes, int top, int minCut, int target) {
        List<double[]> intervals = new ArrayList<>();
        for (Box box : boxes) {
            double start = Math.max(top, box.top());
            double end = Math.min(target, box.bottom());
            if (end > start) {
                intervals.add(new double[]{start, end});
            }
        }
        Collections.sort(intervals, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return Double.compare(a[0], b[0]);
            }
        });

        double cursor = minCut;
        double bestGap = -1;
        int bestCut = target;
        for (double[] interval : intervals) {
            if (interval[0] > cursor) {
                double gapEnd = Math.min(interval[0], target);
                if (gapEnd - cursor > bestGap) {
                    bestGap = gapEnd - cursor;
                    bestCut = (int) Math.round((cursor + gapEnd) / 2.0);
                }
            }
            cursor = Math.max(cursor, interval[1]);
            if (cursor >= target) break;
        }
        if (cursor < target && target - cursor > bestGap) {
            bestCut = target;
        }
        return Math.max(minCut, Math.min(target, bestCut));
    }
}
