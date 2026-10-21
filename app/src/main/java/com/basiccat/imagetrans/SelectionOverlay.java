package com.basiccat.imagetrans;

import android.content.Context;
import android.content.res.Configuration;
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
 * Full-screen overlay for choosing the screenshot region. A frame spanning
 * most of the screen is shown; the user resizes it with its four corner
 * handles (or drags it from inside) and taps inside it to confirm. Back
 * cancels; Escape confirms and is available for scripted use.
 */
public class SelectionOverlay extends View {
    public interface Callback {
        void onSelected(Rect rect);

        void onCancelled();
    }

    private final Callback callback;
    private final Rect selection = new Rect();
    private final Rect[] handles = {new Rect(), new Rect(), new Rect(), new Rect()};
    private final float density;
    private final float handleSize;
    private final int minSize;
    private final int touchSlop;

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private int dragCorner = -1;
    private boolean dragBox;
    private boolean dragged;
    private float downX, downY;
    private int startLeft, startTop, startRight, startBottom;

    public SelectionOverlay(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        density = getResources().getDisplayMetrics().density;
        handleSize = 22 * density;
        minSize = Math.round(32 * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(0x88000000);
        setFocusable(true);
        setFocusableInTouchMode(true);

        borderPaint.setColor(0xFFF9A825);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * density);
        handlePaint.setColor(0xFF1565C0);
        hintPaint.setColor(0xFFFFFFFF);
        hintPaint.setTextSize(15 * density);
        hintPaint.setTypeface(Typeface.DEFAULT_BOLD);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
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
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(selection, borderPaint);
        for (Rect handle : handles) {
            canvas.drawRoundRect(handle.left, handle.top, handle.right, handle.bottom,
                    handleSize, handleSize, handlePaint);
        }
        String hint = getContext().getString(R.string.selection_hint);
        float y = Math.max(selection.top + hintPaint.getTextSize() + 8 * density,
                selection.top - 16 * density);
        canvas.drawText(hint, selection.exactCenterX(), y, hintPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragged = false;
                dragCorner = cornerAt(downX, downY);
                dragBox = dragCorner < 0 && selection.contains((int) downX, (int) downY);
                startLeft = selection.left;
                startTop = selection.top;
                startRight = selection.right;
                startBottom = selection.bottom;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!dragged && Math.hypot(dx, dy) < touchSlop) return true;
                if (!dragged && dragCorner < 0 && !dragBox) {
                    // A drag that starts outside does nothing.
                    return true;
                }
                dragged = true;
                if (dragBox) {
                    int left = clamp((int) (startLeft + dx), 0, getWidth() - selection.width());
                    int top = clamp((int) (startTop + dy), 0, getHeight() - selection.height());
                    selection.set(left, top, left + selection.width(), top + selection.height());
                } else {
                    resize(dx, dy);
                }
                layoutHandles();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!dragged && selection.contains((int) event.getX(), (int) event.getY())) {
                    finish();
                }
                dragCorner = -1;
                dragBox = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
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

    private void finish() {
        if (!selection.isEmpty()) callback.onSelected(new Rect(selection));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            callback.onCancelled();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_ENTER) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The view is resized by the window manager; onSizeChanged resets the frame.
    }
}
