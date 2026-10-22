package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * The capture frame over the camera preview.
 *
 * Dragging inside the frame moves it; dragging one of the four corner
 * handles resizes it. The frame never leaves the preview and keeps a
 * minimum size, so the captured region is always usable.
 */
public class ViewfinderOverlay extends View {
    private static final int MODE_NONE = -1;
    private static final int MODE_MOVE = 0;
    private static final int MODE_TOP_LEFT = 1;
    private static final int MODE_TOP_RIGHT = 2;
    private static final int MODE_BOTTOM_RIGHT = 3;
    private static final int MODE_BOTTOM_LEFT = 4;

    private static final float MIN_FRAME_DP = 64f;
    private static final float HANDLE_RADIUS_DP = 10f;
    private static final float HANDLE_TOUCH_DP = 30f;

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path dimPath = new Path();

    private final RectF frame = new RectF();
    private boolean initialized;
    private final float density;
    private final float minFrame;
    private final float handleRadius;
    private final float handleTouchRadius;
    private final int touchSlop;

    private int mode = MODE_NONE;
    private float downX;
    private float downY;
    private final RectF startFrame = new RectF();

    public ViewfinderOverlay(Context context) {
        this(context, null);
    }

    public ViewfinderOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        minFrame = MIN_FRAME_DP * density;
        handleRadius = HANDLE_RADIUS_DP * density;
        handleTouchRadius = HANDLE_TOUCH_DP * density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        dimPaint.setColor(0x66000000);
        dimPaint.setStyle(Paint.Style.FILL);
        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * density);
        gridPaint.setColor(0x66FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1 * density);
        handleFillPaint.setColor(0xFF1565C0);
        handleFillPaint.setStyle(Paint.Style.FILL);
        handleStrokePaint.setColor(0xFFFFFFFF);
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(2 * density);

        setFocusable(false);
    }

    /** The frame in view coordinates: the region that will be cropped. */
    public RectF getFrameRect() {
        return new RectF(frame);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        if (!initialized || oldw == 0 || oldh == 0) {
            frame.set(w * 0.08f, h * 0.22f, w * 0.92f, h * 0.78f);
            initialized = true;
        }
        clampFrame();
    }

    private void clampFrame() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || frame.isEmpty()) return;
        float width = Math.min(frame.width(), w);
        float height = Math.min(frame.height(), h);
        float left = Math.max(0, Math.min(frame.left, w - width));
        float top = Math.max(0, Math.min(frame.top, h - height));
        frame.set(left, top, left + width, top + height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || frame.isEmpty()) return;

        // Dim everything outside the frame.
        dimPath.reset();
        dimPath.setFillType(Path.FillType.EVEN_ODD);
        dimPath.addRect(0, 0, w, h, Path.Direction.CW);
        dimPath.addRect(frame, Path.Direction.CW);
        canvas.drawPath(dimPath, dimPaint);

        // Rule-of-thirds guides inside the frame.
        canvas.save();
        canvas.clipRect(frame);
        for (int i = 1; i <= 2; i++) {
            float x = frame.left + frame.width() * i / 3f;
            float y = frame.top + frame.height() * i / 3f;
            canvas.drawLine(x, frame.top, x, frame.bottom, gridPaint);
            canvas.drawLine(frame.left, y, frame.right, y, gridPaint);
        }
        canvas.restore();

        canvas.drawRect(frame, borderPaint);

        float[][] corners = {
                {frame.left, frame.top}, {frame.right, frame.top},
                {frame.right, frame.bottom}, {frame.left, frame.bottom}};
        for (float[] corner : corners) {
            canvas.drawCircle(corner[0], corner[1], handleRadius, handleFillPaint);
            canvas.drawCircle(corner[0], corner[1], handleRadius, handleStrokePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = modeAt(x, y);
                if (mode == MODE_NONE) return false;
                downX = x;
                downY = y;
                startFrame.set(frame);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == MODE_NONE) return false;
                if (Math.abs(x - downX) < touchSlop * 0.5f
                        && Math.abs(y - downY) < touchSlop * 0.5f) {
                    return true;
                }
                applyDrag(x - downX, y - downY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = MODE_NONE;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private int modeAt(float x, float y) {
        float[][] corners = {
                {frame.left, frame.top}, {frame.right, frame.top},
                {frame.right, frame.bottom}, {frame.left, frame.bottom}};
        for (int i = 0; i < corners.length; i++) {
            if (Math.hypot(x - corners[i][0], y - corners[i][1]) <= handleTouchRadius) {
                return i + 1;
            }
        }
        if (frame.contains(x, y)) return MODE_MOVE;
        return MODE_NONE;
    }

    private void applyDrag(float dx, float dy) {
        int w = getWidth();
        int h = getHeight();
        if (mode == MODE_MOVE) {
            float left = startFrame.left + dx;
            float top = startFrame.top + dy;
            left = Math.max(0, Math.min(left, w - frame.width()));
            top = Math.max(0, Math.min(top, h - frame.height()));
            frame.set(left, top, left + frame.width(), top + frame.height());
        } else {
            float left = startFrame.left;
            float top = startFrame.top;
            float right = startFrame.right;
            float bottom = startFrame.bottom;
            if (mode == MODE_TOP_LEFT || mode == MODE_BOTTOM_LEFT) {
                left = Math.max(0, Math.min(startFrame.left + dx, right - minFrame));
            }
            if (mode == MODE_TOP_RIGHT || mode == MODE_BOTTOM_RIGHT) {
                right = Math.min(w, Math.max(startFrame.right + dx, left + minFrame));
            }
            if (mode == MODE_TOP_LEFT || mode == MODE_TOP_RIGHT) {
                top = Math.max(0, Math.min(startFrame.top + dy, bottom - minFrame));
            }
            if (mode == MODE_BOTTOM_LEFT || mode == MODE_BOTTOM_RIGHT) {
                bottom = Math.min(h, Math.max(startFrame.bottom + dy, top + minFrame));
            }
            frame.set(left, top, right, bottom);
        }
        invalidate();
    }
}
