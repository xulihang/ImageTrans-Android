package com.basiccat.imagetrans;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;

/**
 * Self-contained zoomable, pannable image view. It needs no parent
 * ScrollView: it handles pinch-to-zoom, double-tap to toggle zoom,
 * one-finger panning, fling and edge clamping, including images taller than
 * the screen (webtoon strips).
 *
 * The image opens fitted to the view's width (short images are shown
 * centred, never enlarged), and can be zoomed out to see the whole picture or
 * in until one image pixel covers {@link #MAX_PIXELS_ON_SCREEN} device
 * pixels.
 */
public class ZoomableImageView extends View {

    /** At most this many device pixels per image pixel at maximum zoom. */
    private static final float MAX_PIXELS_ON_SCREEN = 3f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;
    private static final int SETTLE_DURATION_MS = 220;
    private static final int FLING_DURATION_MS = 450;

    private final Matrix draw = new Matrix();
    private final Matrix from = new Matrix();
    private final RectF imageRect = new RectF();
    private final float[] values = new float[9];

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private Bitmap bitmap;
    private float minScale = 1f;
    private float initialScale = 1f;
    private float maxScale = 4f;

    private ValueAnimator animator;
    // Recent taps, for detecting double taps ourselves: GestureDetector alone
    // is unreliable here because ScaleGestureDetector may claim the stream.
    private final ArrayList<long[]> recentTaps = new ArrayList<>();
    private long doubleTapHandledAt = -1;
    private static final long DOUBLE_TAP_TIMEOUT_MS = 300;
    private static final int DOUBLE_TAP_SLOP_DP = 100;

    /** Fired for a horizontal swipe at the fitted scale. */
    public interface OnSwipeListener {
        /** @param towardLeft true when the finger moved left. */
        void onSwipe(boolean towardLeft);
    }

    private OnSwipeListener swipeListener;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomableImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        stopAnimation();
                        float factor = detector.getScaleFactor();
                        if (factor != 1f) {
                            draw.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                            constrain();
                            invalidate();
                        }
                        return true;
                    }
                });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                stopAnimation();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // Fallback: our own detection missed the first tap. Skip when
                // the same tap was already handled in onTouchEvent.
                long time = e.getEventTime();
                if (doubleTapHandledAt >= 0 && Math.abs(time - doubleTapHandledAt) < 50) return true;
                onDoubleTapAt(e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                draw.postTranslate(-dx, -dy);
                constrain();
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (swipeListener != null && scale() <= minScale + 0.01f
                        && Math.abs(dx) > getWidth() * 0.2f && Math.abs(dx) > Math.abs(dy) * 2f) {
                    swipeListener.onSwipe(dx < 0);
                    return true;
                }
                if (Math.abs(velocityX) < 150 && Math.abs(velocityY) < 150) return false;
                startFling(velocityX, velocityY);
                return true;
            }
        });
    }

    public void setSwipeListener(OnSwipeListener listener) {
        swipeListener = listener;
    }

    public void setImage(Bitmap bm) {
        bitmap = bm;
        stopAnimation();
        if (bm != null) computeScaleRange();
        fitToView();
    }

    /** True when the image is larger than its initial (fit-to-view) size. */
    public boolean isZoomedIn() {
        return bitmap != null && scale() > initialScale * 1.01f;
    }

    /** Returns to the fitted scale and centred position. */
    public void resetZoom() {
        stopAnimation();
        fitToView();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (bitmap != null) {
            computeScaleRange();
            fitToView();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return super.onTouchEvent(event);
        // Track taps before the detectors so the double tap is never
        // swallowed by a drag or scale that happens between them.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && event.getPointerCount() == 1
                && isDoubleTap(event.getX(), event.getY(), event.getEventTime())) {
            recentTaps.clear();
            doubleTapHandledAt = event.getEventTime();
            onDoubleTapAt(event.getX(), event.getY());
            return true;
        }
        // GestureDetector reports the whole swipe once: at the fitted scale a
        // mostly-horizontal fling means "flip to the other image".
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);
        return true;
    }

    private boolean isDoubleTap(float x, float y, long time) {
        long now = time > 0 ? time : System.currentTimeMillis();
        long timeout = ViewConfiguration.getDoubleTapTimeout() > 0
                ? ViewConfiguration.getDoubleTapTimeout() : DOUBLE_TAP_TIMEOUT_MS;
        long slop = (long) (DOUBLE_TAP_SLOP_DP * getResources().getDisplayMetrics().density);
        for (int i = recentTaps.size() - 1; i >= 0; i--) {
            long[] tap = recentTaps.get(i);
            if (now - tap[2] > timeout) {
                recentTaps.remove(i);
                continue;
            }
            long dx = (long) (x - Float.intBitsToFloat((int) tap[0]));
            long dy = (long) (y - Float.intBitsToFloat((int) tap[1]));
            if (dx * dx + dy * dy < slop * slop) return true;
        }
        recentTaps.add(new long[]{Float.floatToIntBits(x), Float.floatToIntBits(y), now});
        return false;
    }

    /** Cycles fit width → zoomed in → fit entire image → fit width. */
    private void onDoubleTapAt(float x, float y) {
        stopAnimation();
        float s = scale();
        if (s > Math.max(minScale, initialScale) + 0.01f) {
            // Zoomed in: if the whole picture is visible at a smaller scale,
            // show it all; otherwise go back to fit width.
            settleTo(Math.max(minScale, initialScale), x, y);
        } else if (s > minScale + 0.01f) {
            fitToView();
        } else {
            settleTo(Math.min(initialScale * DOUBLE_TAP_SCALE, maxScale), x, y);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, draw, null);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    // --- geometry -------------------------------------------------------

    private void computeScaleRange() {
        if (getWidth() == 0 || getHeight() == 0) return;
        // Open at "fit width". It may be zoomed out to "fit inside" so a tall
        // photo can be seen whole, and in from there.
        float fitWidth = getWidth() / (float) bitmap.getWidth();
        minScale = Math.min(fitWidth, getHeight() / (float) bitmap.getHeight());
        initialScale = fitWidth;
        // Drawing more than MAX_PIXELS_ON_SCREEN device pixels per image pixel
        // only blurs, so stop zooming there.
        float nativeScale = MAX_PIXELS_ON_SCREEN * getResources().getDisplayMetrics().density;
        maxScale = Math.max(initialScale * 2f, nativeScale);
    }

    private void fitToView() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        draw.reset();
        // Width fills the view; if the image is shorter than the view, it is
        // shown centred above/below with its full width visible.
        draw.postScale(initialScale, initialScale);
        draw.postTranslate(
                (getWidth() - bitmap.getWidth() * initialScale) / 2f,
                (getHeight() - bitmap.getHeight() * initialScale) / 2f);
        invalidate();
    }

    /** Clamps the scale to the allowed range and keeps the image on screen. */
    private void constrain() {
        float s = scale();
        float clamped = Math.max(minScale, Math.min(maxScale, s));
        if (clamped != s) {
            draw.postScale(clamped / s, clamped / s, getWidth() / 2f, getHeight() / 2f);
        }
        updateImageRect();
        float dx = 0, dy = 0;
        float vw = getWidth(), vh = getHeight();
        if (imageRect.width() <= vw) {
            dx = (vw - imageRect.width()) / 2f - imageRect.left;
        } else if (imageRect.left > 0) {
            dx = -imageRect.left;
        } else if (imageRect.right < vw) {
            dx = vw - imageRect.right;
        }
        if (imageRect.height() <= vh) {
            dy = (vh - imageRect.height()) / 2f - imageRect.top;
        } else if (imageRect.top > 0) {
            dy = -imageRect.top;
        } else if (imageRect.bottom < vh) {
            dy = vh - imageRect.bottom;
        }
        if (dx != 0 || dy != 0) draw.postTranslate(dx, dy);
    }

    private float scale() {
        draw.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    private void updateImageRect() {
        imageRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        draw.mapRect(imageRect);
    }

    // --- animation ------------------------------------------------------

    private void stopAnimation() {
        if (animator != null) animator.cancel();
        animator = null;
    }

    /** Smoothly zooms to {@code target}, keeping the touch point in place. */
    private void settleTo(float target, float focusX, float focusY) {
        float factor = target / scale();
        Matrix start = new Matrix(draw);
        draw.postScale(factor, factor, focusX, focusY);
        constrain();
        Matrix end = new Matrix(draw);
        draw.set(start);
        animateTo(start, end, SETTLE_DURATION_MS);
    }

    /** Glides in the fling direction, stopping at the image edge. */
    private void startFling(float velocityX, float velocityY) {
        Matrix start = new Matrix(draw);
        draw.postTranslate(velocityX * FLING_DURATION_MS / 2000f, velocityY * FLING_DURATION_MS / 2000f);
        constrain();
        Matrix end = new Matrix(draw);
        draw.set(start);
        animateTo(start, end, FLING_DURATION_MS);
    }

    /** Animates translation while interpolating scale geometrically. */
    private void animateTo(Matrix start, Matrix end, long duration) {
        stopAnimation();
        final float[] startValues = new float[9];
        final float[] endValues = new float[9];
        start.getValues(startValues);
        end.getValues(endValues);
        double scaleRatio = endValues[Matrix.MSCALE_X] / startValues[Matrix.MSCALE_X];
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            float scale = startValues[Matrix.MSCALE_X] * (float) Math.pow(scaleRatio, t);
            draw.reset();
            draw.setScale(scale, scale);
            draw.postTranslate(
                    startValues[Matrix.MTRANS_X] + (endValues[Matrix.MTRANS_X] - startValues[Matrix.MTRANS_X]) * t,
                    startValues[Matrix.MTRANS_Y] + (endValues[Matrix.MTRANS_Y] - startValues[Matrix.MTRANS_Y]) * t);
            invalidate();
        });
        animator.start();
    }
}
