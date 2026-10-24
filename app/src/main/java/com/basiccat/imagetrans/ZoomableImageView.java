package com.basiccat.imagetrans;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
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
 * one-finger panning and edge clamping, including images taller than the
 * screen (webtoon strips). Dragging moves the image 1:1 with the finger; it
 * does not coast after the finger is lifted.
 *
 * The image opens fitted inside the view, whole if it fits at screen width
 * (tall images are fitted to the height, landscape ones to the width), and
 * can be zoomed out to see the whole picture or in until one image pixel
 * covers {@link #MAX_PIXELS_ON_SCREEN} device pixels.
 */
public class ZoomableImageView extends View {

    /** At most this many device pixels per image pixel at maximum zoom. */
    private static final float MAX_PIXELS_ON_SCREEN = 3f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;
    private static final int SETTLE_DURATION_MS = 220;
    private static final int FLING_DURATION_MS = 450;
    /**
     * A lift-off horizontal speed (dp/s) above this is a flick rather than a
     * drag, and the only way a slow, steady drag can turn into a flip.
     */
    private static final float SWIPE_FLICK_DP_PER_SECOND = 600f;
    private final float swipeFlickVelocity;

    private final Matrix draw = new Matrix();
    /**
     * Scales the bitmap in the draw call. A matrix passed to drawBitmap()
     * goes through the shader path, which skips the bilinear filter (and so
     * looks jagged); a paint with bitmap filtering always samples smoothly.
     */
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Matrix from = new Matrix();
    private final RectF imageRect = new RectF();
    private final float[] values = new float[9];

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private Bitmap bitmap;
    private float minScale = 1f;
    private float initialScale = 1f;
    private float maxScale = 4f;
    /** Fit-to-width scale, the reference for "is this a sideways swipe". */
    private float fitWidthScale = 1f;

    private ValueAnimator animator;
    // Recent taps, for detecting double taps ourselves: GestureDetector alone
    // is unreliable here because ScaleGestureDetector may claim the stream.
    private final ArrayList<long[]> recentTaps = new ArrayList<>();
    private long doubleTapHandledAt = -1;
    private static final long DOUBLE_TAP_TIMEOUT_MS = 300;
    private static final int DOUBLE_TAP_SLOP_DP = 100;

    /** Distance the finger travelled during the current gesture. */
    private float dragX, dragY;
    /** The fitted image has already been flipped during this gesture. */
    private boolean swiped;

    /** Fired for a horizontal swipe at the fitted scale. */
    public interface OnSwipeListener {
        /** @param towardLeft true when the finger moved left. */
        void onSwipe(boolean towardLeft);
    }

    private OnSwipeListener swipeListener;
    private static final int TAP_SLOP_DP = 16;
    private final int tapSlop = (int) (TAP_SLOP_DP * getResources().getDisplayMetrics().density);

    /** Fired for a single (non-double) tap on the image. */
    public interface OnTapListener {
        /**
         * @param x view x of the tap
         * @param y view y of the tap
         */
        void onTap(float x, float y);
    }

    private OnTapListener tapListener;
    // Where the current gesture started, to tell taps from drags.
    private float downX, downY;
    private boolean gestureMoved;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomableImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        swipeFlickVelocity = SWIPE_FLICK_DP_PER_SECOND
                * getResources().getDisplayMetrics().density;
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
                dragX = 0;
                dragY = 0;
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                swiped = false;
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
                dragX += -dx;
                dragY += -dy;
                // At the fitted size a sideways drag means "other image", not
                // panning: panning would be a no-op there, since the whole
                // image is visible. The flip waits for the finger to be
                // lifted (onFling decides from the speed there), so a slow
                // drag never flips mid-way. Panning starts once zoomed in.
                if (scale() <= fitWidthScale + 0.01f) return true;
                // The image moves exactly as far as the finger (1:1); a fast
                // lift-off adds a short glide.
                draw.postTranslate(-dx, -dy);
                constrain();
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                // Only a flick, never a slow drag, can flip the image or start
                // a glide: a slow drag must stop exactly where the finger was.
                boolean flick = Math.hypot(velocityX, velocityY) > swipeFlickVelocity;
                if (scale() <= fitWidthScale + 0.01f) {
                    if (flick && !swiped && swipeListener != null
                            && Math.abs(dragX) > getWidth() * 0.2f
                            && Math.abs(dragX) > Math.abs(dragY) * 2f) {
                        swiped = true;
                        swipeListener.onSwipe(dragX < 0);
                    }
                    return true;
                }
                if (flick) startFling(velocityX, velocityY);
                return true;
            }
        });
    }

    public void setSwipeListener(OnSwipeListener listener) {
        swipeListener = listener;
    }

    public void setTapListener(OnTapListener listener) {
        tapListener = listener;
    }

    /** Maps a view coordinate to image (bitmap) coordinates. */
    public float[] viewToImage(float x, float y) {
        float[] point = {x, y};
        Matrix inverse = new Matrix();
        if (draw.invert(inverse)) inverse.mapPoints(point);
        return point;
    }

    /** Current image pixels per device pixel. */
    public float getImageScale() {
        return scale();
    }

    public void setImage(Bitmap bm) {
        bitmap = bm;
        stopAnimation();
        if (bm != null) computeScaleRange();
        fitToView();
    }

    /** True when the image is zoomed past the size it opened at. */
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
        trackTap(event);
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
        // mostly-horizontal swipe means "flip to the other image".
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);
        return true;
    }

    /**
     * Fires {@link OnTapListener} for a single-finger tap that did not move or
     * scale. A tap that turns out to be the first half of a double tap also
     * arrives here, so listeners should allow for that (e.g. ignore a second
     * quick tap on the same spot).
     */
    private void trackTap(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                gestureMoved = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                gestureMoved = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() > 1) {
                    gestureMoved = true;
                } else {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (dx * dx + dy * dy > tapSlop * tapSlop) gestureMoved = true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (event.getActionMasked() == MotionEvent.ACTION_UP && !gestureMoved
                        && tapListener != null && event.getPointerCount() == 1) {
                    tapListener.onTap(event.getX(), event.getY());
                }
                break;
            default:
                break;
        }
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

    /** Cycles fit inside → zoomed in → fit inside. */
    private void onDoubleTapAt(float x, float y) {
        stopAnimation();
        float s = scale();
        if (s > Math.max(minScale, initialScale) + 0.01f) {
            // Zoomed in: show the whole picture again; its side is then at
            // full width for a tall image.
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
            canvas.drawBitmap(bitmap, draw, imagePaint);
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
        // Open at "fit inside": the whole image is visible without panning.
        // Tall images are fitted to the height, so their sides no longer run
        // off the screen; the image can still be zoomed out only to "fit
        // inside" and in from there. A landscape image is fitted to the width.
        float fitWidth = getWidth() / (float) bitmap.getWidth();
        fitWidthScale = fitWidth;
        float fitInside = Math.min(fitWidth, getHeight() / (float) bitmap.getHeight());
        minScale = fitInside;
        initialScale = fitInside;
        // Drawing more than MAX_PIXELS_ON_SCREEN device pixels per image pixel
        // only blurs, so stop zooming there.
        float nativeScale = MAX_PIXELS_ON_SCREEN * getResources().getDisplayMetrics().density;
        maxScale = Math.max(initialScale * 2f, nativeScale);
    }

    private void fitToView() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        draw.reset();
        // The whole image is scaled to fit inside the view and centred; the
        // side that does not fill it stays blank.
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

    /**
     * Glides in the flick direction and stops at the image edge. The distance
     * grows with the lift-off speed, so a gentle flick coasts a little and a
     * hard one carries on.
     */
    private void startFling(float velocityX, float velocityY) {
        float speed = Math.max(1f, (float) Math.hypot(velocityX, velocityY));
        // Pixels travelled before stopping: a fraction of a screen that grows
        // nearly linearly with the lift-off speed (a screen at ~4000 px/s),
        // capped at 2 screens.
        float screens = Math.min(speed / 4000f, 2f);
        float distance = Math.max(getWidth(), getHeight()) * screens;
        float dirX = velocityX / speed;
        float dirY = velocityY / speed;
        Matrix start = new Matrix(draw);
        draw.postTranslate(dirX * distance, dirY * distance);
        constrain();
        Matrix end = new Matrix(draw);
        draw.set(start);
        // Longer glides for faster flicks, so the speed feels consistent.
        long duration = FLING_DURATION_MS
                + Math.round(FLING_DURATION_MS * Math.min(speed / 4000f, 1f));
        animateTo(start, end, duration);
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
