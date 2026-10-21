package com.basiccat.imagetrans;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Full-screen overlay for screenshot translation.
 *
 * The frame ("选框") is always on screen: it starts as a selection frame
 * (drag its corner handles or the frame itself to choose the region, tap
 * inside to translate), then shows the translated image inside it.
 *
 * While the result is shown, taps on text boxes are forwarded to the
 * listener (for the source/target dialog), a tap inside the frame translates
 * again, and the "×" above the frame clears the result, leaving the frame in
 * place for the next capture. Drag the frame itself to move it.
 */
public class SelectionOverlay extends View {
    public interface Callback {
        void onSelected(Rect rect);

        void onCancelled();
    }

    public interface TapListener {
        /** @param x,y position in image (capture) coordinates. */
        boolean onTap(float x, float y);
    }

    private static final int STATE_SELECT = 0;
    private static final int STATE_TRANSLATING = 1;
    private static final int STATE_RESULT = 2;

    private final Callback callback;
    private final Rect selection = new Rect();
    private final Rect[] handles = {new Rect(), new Rect(), new Rect(), new Rect()};
    private final Rect clearRect = new Rect();
    private final float density;
    private final float handleSize;
    private final int minSize;
    private final int touchSlop;

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private int state = STATE_SELECT;
    private Bitmap translated;
    private String status;
    private TapListener tapListener;

    // Interaction
    private int dragCorner = -1;
    private boolean dragBox;
    private boolean dragged;
    private boolean clearing;
    private float downX, downY;
    private int startLeft, startTop, startRight, startBottom;

    public SelectionOverlay(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        density = getResources().getDisplayMetrics().density;
        handleSize = 20 * density;
        minSize = Math.round(32 * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(0x66000000);
        setFocusable(true);
        setFocusableInTouchMode(true);

        // Neutral grey frame: it is captured inside the region (a bright
        // colour would tint the result's edge).
        borderPaint.setColor(0x991F2933);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * density);
        handlePaint.setColor(0xFF1565C0);
        bubblePaint.setColor(0xEEFFFFFF);
        strokePaint.setColor(0xFFFFFFFF);
        strokePaint.setStrokeWidth(2 * density);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        hintPaint.setColor(0xFFFFFFFF);
        hintPaint.setTextSize(15 * density);
        hintPaint.setTypeface(Typeface.DEFAULT_BOLD);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!selection.isEmpty()) return;
        // Default frame: most of the shorter side, a comfortable band.
        int insetX = Math.round(w * 0.06f);
        int insetY = Math.round(h * 0.18f);
        selection.set(insetX, insetY, w - insetX, h - insetY);
        layoutHandles();
    }

    private void layoutHandles() {
        float half = handleSize;
        handles[0].set((int) (selection.left - half), (int) (selection.top - half),
                (int) (selection.left + half), (int) (selection.top + half));
        handles[1].set((int) (selection.right - half), (int) (selection.top - half),
                (int) (selection.right + half), (int) (selection.top + half));
        handles[2].set((int) (selection.right - half), (int) (selection.bottom - half),
                (int) (selection.right + half), (int) (selection.bottom + half));
        handles[3].set((int) (selection.left - half), (int) (selection.bottom - half),
                (int) (selection.left + half), (int) (selection.bottom + half));
        int clearSize = Math.round(36 * density);
        int top = selection.top - clearSize - Math.round(10 * density);
        if (top < 0) top = selection.top + Math.round(10 * density);
        clearRect.set(selection.right - clearSize, top, selection.right, top + clearSize);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (translated != null) {
            // The result fills the frame at the frame's size, so the text sits
            // exactly where it was captured.
            canvas.drawBitmap(translated, null, selection, null);
        }
        canvas.drawRect(selection, borderPaint);

        if (state == STATE_SELECT) {
            for (Rect handle : handles) {
                canvas.drawRoundRect(handle.left, handle.top, handle.right, handle.bottom,
                        handleSize, handleSize, handlePaint);
            }
            // The hint sits outside the frame (it would be captured inside
            // and end up in the translated image).
            String hint = getContext().getString(R.string.selection_hint);
            float y = selection.top - Math.round(24 * density);
            if (y < hintPaint.getTextSize()) {
                y = selection.bottom + Math.round(28 * density);
            }
            canvas.drawText(hint, selection.exactCenterX(), y, hintPaint);
            return;
        }

        if (state == STATE_RESULT) {
            // Clear button above the top-right corner.
            canvas.drawRoundRect(clearRect.left, clearRect.top, clearRect.right, clearRect.bottom,
                    clearRect.width() / 2f, clearRect.width() / 2f, handlePaint);
            float cx = clearRect.exactCenterX();
            float cy = clearRect.exactCenterY();
            float r = clearRect.width() * 0.18f;
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, strokePaint);
            canvas.drawLine(cx + r, cy - r, cx - r, cy + r, strokePaint);
        }

        if (status != null) {
            float cx = selection.exactCenterX();
            float cy = selection.exactCenterY();
            float width = hintPaint.measureText(status) + Math.round(32 * density);
            float height = Math.round(44 * density);
            Rect bubble = new Rect((int) (cx - width / 2), (int) (cy - height / 2),
                    (int) (cx + width / 2), (int) (cy + height / 2));
            canvas.drawRoundRect(bubble.left, bubble.top, bubble.right, bubble.bottom,
                    height / 2, height / 2, bubblePaint);
            Paint textPaint = new TextPaint(hintPaint);
            textPaint.setColor(0xFF1F2933);
            canvas.drawText(status, cx, cy + textPaint.getTextSize() / 3f, textPaint);
        }
    }

    // --- state updates ----------------------------------------------------

    /** Shows the translation inside the frame; null when there is none. */
    void showTranslation(Bitmap bitmap, String status) {
        if (translated != null && translated != bitmap) translated.recycle();
        translated = bitmap;
        state = bitmap != null ? STATE_RESULT : STATE_TRANSLATING;
        this.status = status;
        // Once there is content, the frame is no longer interactive for
        // selection; only a fresh capture uses SELECT.
        invalidate();
    }

    void showStatus(String status) {
        this.status = status;
        if (state != STATE_TRANSLATING) state = STATE_TRANSLATING;
        invalidate();
    }

    /** "×": drops the result and returns to the selection frame. */
    void clearResult() {
        if (translated != null) {
            translated.recycle();
            translated = null;
        }
        status = null;
        state = STATE_SELECT;
        invalidate();
    }

    boolean hasResult() {
        return translated != null;
    }

    /** The frame's rectangle on screen (the capture region). */
    Rect getFrameRect() {
        return new Rect(selection);
    }

    void setTapListener(TapListener listener) {
        tapListener = listener;
    }

    // --- touch ------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (state == STATE_TRANSLATING) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragged = false;
                startLeft = selection.left;
                startTop = selection.top;
                startRight = selection.right;
                startBottom = selection.bottom;
                dragCorner = -1;
                dragBox = false;
                // A button always contains the touch, so the matching ACTION_UP
                // can never turn into a "translate again" tap on the frame.
                if (state == STATE_RESULT && clearRect.contains((int) downX, (int) downY)) {
                    clearing = true;
                    return true;
                }
                if (state == STATE_SELECT) {
                    dragCorner = cornerAt(downX, downY);
                    dragBox = dragCorner < 0 && selection.contains((int) downX, (int) downY);
                } else {
                    // With a result shown, the frame is moved by its border;
                    // drags inside the frame are for selecting text later.
                    dragBox = borderNear(downX, downY);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!dragged && Math.hypot(dx, dy) < touchSlop) return true;
                dragged = true;
                if (dragBox) {
                    int left = clamp((int) (startLeft + dx), 0, getWidth() - selection.width());
                    int top = clamp((int) (startTop + dy), 0, getHeight() - selection.height());
                    selection.set(left, top, left + selection.width(), top + selection.height());
                } else if (dragCorner >= 0) {
                    resize(dx, dy);
                }
                layoutHandles();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                clearing = false;
                return true;
            case MotionEvent.ACTION_UP:
                if (clearing) {
                    clearing = false;
                    clearResult();
                    return true;
                }
                if (!dragged) {
                    if (state == STATE_RESULT) {
                        if (selection.contains((int) event.getX(), (int) event.getY())) {
                            newResultTap(event.getX() - selection.left,
                                    event.getY() - selection.top);
                        }
                    } else if (selection.contains((int) event.getX(), (int) event.getY())
                            && selection.width() >= minSize && selection.height() >= minSize) {
                        callback.onSelected(new Rect(selection));
                    }
                }
                dragCorner = -1;
                dragBox = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** True near the frame's edge (inside or just outside it). */
    private boolean borderNear(float x, float y) {
        float margin = 12 * density;
        boolean withinOuter = x >= selection.left - margin && x <= selection.right + margin
                && y >= selection.top - margin && y <= selection.bottom + margin;
        boolean insideInner = x > selection.left + margin && x < selection.right - margin
                && y > selection.top + margin && y < selection.bottom - margin;
        return withinOuter && !insideInner;
    }

    private void newResultTap(float imageX, float imageY) {
        // A tap on text opens its dialog; a tap elsewhere translates again.
        if (tapListener != null && tapListener.onTap(imageX, imageY)) {
            return;
        }
        callback.onSelected(new Rect(selection));
    }

    private void resize(float dx, float dy) {
        int left = startLeft;
        int top = startTop;
        int right = startRight;
        int bottom = startBottom;
        if (dragCorner == 0) {
            left = clamp((int) (startLeft + dx), 0, right - minSize);
            top = clamp((int) (startTop + dy), 0, bottom - minSize);
        } else if (dragCorner == 1) {
            right = clamp((int) (startRight + dx), left + minSize, getWidth());
            top = clamp((int) (startTop + dy), 0, bottom - minSize);
        } else if (dragCorner == 2) {
            right = clamp((int) (startRight + dx), left + minSize, getWidth());
            bottom = clamp((int) (startBottom + dy), top + minSize, getHeight());
        } else {
            left = clamp((int) (startLeft + dx), 0, right - minSize);
            bottom = clamp((int) (startBottom + dy), top + minSize, getHeight());
        }
        selection.set(left, top, right, bottom);
    }

    private int cornerAt(float x, float y) {
        for (int i = 0; i < handles.length; i++) {
            Rect handle = handles[i];
            if (x >= handle.left - handleSize && x <= handle.right + handleSize
                    && y >= handle.top - handleSize && y <= handle.bottom + handleSize) {
                return i;
            }
        }
        return -1;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (state == STATE_RESULT) {
                clearResult();
            } else {
                callback.onCancelled();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (state == STATE_SELECT && !selection.isEmpty()) {
                callback.onSelected(new Rect(selection));
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // onSizeChanged keeps the frame on screen after rotation.
    }
}
