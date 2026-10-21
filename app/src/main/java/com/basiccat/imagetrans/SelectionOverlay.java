package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;


/**
 * The selection frame ("选框").
 *
 * A transparent, full-screen overlay whose window is touchable only inside
 * the frame, so other apps stay usable everywhere else. Inside the frame the
 * user picks the capture region; after a capture the translated image is
 * shown inside the same frame.
 *
 * - Select state: drag inside the frame to move it, corner handles to resize
 *   it. Tapping inside does nothing: the floating ball starts translation.
 * - Translating: shows progress; touches are ignored.
 * - Result: tap text for its dialog; "×" clears the result, "▾" hides the frame (leaving only the ball).
 */
public class SelectionOverlay extends View {
    public interface Callback {
        /** "▾": hide the frame; the floating ball stays. */
        void onHide();

        /** Nothing to show any more (e.g. the frame became too small). */
        void onCancelled();
    }

    public interface TapListener {
        /** @return true when the tap was handled (a text dialog opened). */
        boolean onTap(float x, float y);
    }

    private static final int STATE_SELECT = 0;
    private static final int STATE_TRANSLATING = 1;
    private static final int STATE_RESULT = 2;

    private static final int MIN_FRAME_DP = 64;

    private final Callback callback;
    private final WindowManager windowManager;
    private final WindowManager.LayoutParams params;
    private final float density;
    private final int minFrame;
    private final int touchSlop;

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    /** The frame, in the overlay's (screen) coordinates. */
    private final Rect frame = new Rect();
    /** Screen width and height, from the first layout pass. */
    private int screenWidth;
    /** Where the (frame-sized) window sits on screen. */
    private int contentOffsetX;
    private int contentOffsetY;
    private int screenHeight;

    private int state = STATE_SELECT;
    private String status;
    private Bitmap translated;
    private TapListener tapListener;

    // Touch tracking
    private boolean dragged;
    private boolean clearing;
    private int dragCorner = -1;
    private float downX, downY;
    private int startLeft, startTop, startRight, startBottom;

    public SelectionOverlay(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        density = getResources().getDisplayMetrics().density;
        minFrame = Math.round(MIN_FRAME_DP * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        borderPaint.setColor(0x991F2933);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * density);
        handlePaint.setColor(0xFF1565C0);
        bubblePaint.setColor(0xEEFFFFFF);
        strokePaint.setColor(0xFFFFFFFF);
        strokePaint.setStrokeWidth(2 * density);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        hintPaint.setColor(0xFFFFFFFF);
        hintPaint.setTextSize(14 * density);
        hintPaint.setTypeface(Typeface.DEFAULT_BOLD);
        hintPaint.setTextAlign(Paint.Align.CENTER);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                // Does not take focus (no keyboard), never outsizes the frame.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("ImageTransFrame");

        setBackgroundColor(0x00000000);
        setFocusable(false);
    }

    private static int overlayType() {
        return android.os.Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    void show(Rect initialFrame) {
        frame.set(initialFrame);
        setVisibility(View.VISIBLE);
        windowManager.addView(this, params);
        // Reopen the frame's touch area once the window knows the screen size.
        post(this::updateTouchableArea);
    }

    void remove() {
        clearTranslation();
        setVisibility(View.GONE);
        windowManager.removeView(this);
        callback.onCancelled();
    }

    // --- geometry ---------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // The window is the frame's size, so the screen size comes from the
        // display and the frame keeps its position.
        android.graphics.Point size = new android.graphics.Point();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            size.set(bounds.width(), bounds.height());
        } else {
            wmLegacySize(size);
        }
        screenWidth = size.x;
        screenHeight = size.y;
        if (frame.isEmpty()) {
            frame.set(Math.round(screenWidth * 0.06f), Math.round(screenHeight * 0.18f),
                    Math.round(screenWidth * 0.94f), Math.round(screenHeight * 0.82f));
        }
        updateTouchableArea();
    }

    @SuppressWarnings("deprecation")
    private void wmLegacySize(android.graphics.Point size) {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        size.set(metrics.widthPixels, metrics.heightPixels);
    }

    private void clampFrame() {
        if (screenWidth == 0 || screenHeight == 0) return;
        int width = Math.min(frame.width(), screenWidth);
        int height = Math.min(frame.height(), screenHeight);
        frame.set(frame.left, frame.top, frame.left + width, frame.top + height);
        if (frame.right > screenWidth) frame.offset(screenWidth - frame.right, 0);
        if (frame.bottom > screenHeight) frame.offset(0, screenHeight - frame.bottom);
        if (frame.left < 0) frame.offset(-frame.left, 0);
        if (frame.top < 0) frame.offset(0, -frame.top);
        updateTouchableArea();
    }

    /**
     * Restricts the window's touchable region to the frame. Overlay windows
     * accept a touchable region, so everything outside keeps going to the app
     * underneath.
     */
    private void updateTouchableArea() {
        if (screenWidth == 0 || screenHeight == 0) return;
        // The window only spans the frame (plus a margin for the handles),
        // so everything else on screen keeps receiving touches. Its layout
        // position is the frame's top-left corner.
        int margin = Math.round(28 * density);
        int left = Math.max(0, frame.left - margin);
        int top = Math.max(0, frame.top - margin);
        int right = Math.min(screenWidth, frame.right + margin);
        int bottom = Math.min(screenHeight, frame.bottom + margin);
        params.x = left;
        params.y = top;
        params.width = Math.max(1, right - left);
        params.height = Math.max(1, bottom - top);
        contentOffsetX = left;
        contentOffsetY = top;
        try {
            windowManager.updateViewLayout(this, params);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Not attached yet; show() posts another update.
        }
        invalidate();
    }

    /** Adds an area (e.g. the floating ball) that must stay touchable. */
    void addTouchableRect(Rect rect) {
        // The window only covers the frame; the ball is a separate window
        // that stays touchable on top of everything.
    }

    /** The frame on screen: the capture region. */
    Rect getFrameRect() {
        return new Rect(frame);
    }

    // --- state ------------------------------------------------------------

    /** Shows the translation inside the frame ("null": just the message). */
    void showResult(Bitmap bitmap, String message) {
        if (translated != null && translated != bitmap) translated.recycle();
        translated = bitmap;
        status = message;
        state = bitmap != null ? STATE_RESULT : STATE_SELECT;
        invalidate();
    }

    void showStatus(String message) {
        status = message;
        state = STATE_TRANSLATING;
        invalidate();
    }

    void clearTranslation() {
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

    void setTapListener(TapListener listener) {
        tapListener = listener;
    }

    // --- drawing ----------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // The view covers the frame's margin area; draw in screen coordinates.
        canvas.translate(-contentOffsetX, -contentOffsetY);
        if (state == STATE_RESULT && translated != null) {
            canvas.drawBitmap(translated, null, frame, null);
        }
        canvas.drawRect(frame, borderPaint);
        int clearSize = clearButtonSize();
        Rect clear = clearButtonRect(clearSize);
        Rect hide = hideButtonRect(clearSize);

        switch (state) {
            case STATE_SELECT: {
                if (status != null) {
                    // A message after a failed/empty capture.
                    drawBubble(canvas, status);
                }
                float r = handleSize();
                float[] hx = {frame.left, frame.right, frame.left, frame.right,
                        frame.exactCenterX(), frame.exactCenterX()};
                float[] hy = {frame.top, frame.top, frame.bottom, frame.bottom,
                        frame.top, frame.bottom};
                for (int i = 0; i < hx.length; i++) {
                    canvas.drawCircle(hx[i], hy[i], r, handlePaint);
                }
                break;
            }
            case STATE_TRANSLATING:
                drawBubble(canvas, status != null ? status
                        : getContext().getString(R.string.floating_loading));
                break;
            case STATE_RESULT:
                canvas.drawRoundRect(clear.left, clear.top, clear.right, clear.bottom,
                        clearSize / 2f, clearSize / 2f, handlePaint);
                float cx = clear.exactCenterX();
                float cy = clear.exactCenterY();
                float k = clearSize * 0.18f;
                canvas.drawLine(cx - k, cy - k, cx + k, cy + k, strokePaint);
                canvas.drawLine(cx + k, cy - k, cx - k, cy + k, strokePaint);
                drawHideButton(canvas, hide);
                break;
            default:
                break;
        }
    }

    private void drawBubble(Canvas canvas, String text) {
        float width = hintPaint.measureText(text) + Math.round(32 * density);
        float height = Math.round(42 * density);
        float cx = frame.exactCenterX();
        float cy = frame.exactCenterY();
        canvas.drawRoundRect(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2,
                height / 2, height / 2, bubblePaint);
        TextPaint textPaint = new TextPaint(hintPaint);
        textPaint.setColor(0xFF1F2933);
        canvas.drawText(text, cx, cy + textPaint.getTextSize() / 3f, textPaint);
    }

    private void drawHideButton(Canvas canvas, Rect rect) {
        // A small "⌄" inside a rounded square: hides the frame but keeps the
        // ball.
        canvas.drawRoundRect(rect.left, rect.top, rect.right, rect.bottom,
                rect.width() / 3f, rect.width() / 3f, handlePaint);
        float cx = rect.exactCenterX();
        float cy = rect.exactCenterY();
        float s = rect.width() * 0.2f;
        canvas.drawLine(cx - s, cy - s * 0.4f, cx, cy + s * 0.6f, strokePaint);
        canvas.drawLine(cx + s, cy - s * 0.4f, cx, cy + s * 0.6f, strokePaint);
    }

    private int clearButtonSize() {
        return Math.round(36 * density);
    }

    /** "×" just above the frame's top-right corner (inside the frame if needed). */
    private Rect clearButtonRect(int size) {
        int top = frame.top - size - Math.round(8 * density);
        if (top < 0) top = frame.top + Math.round(8 * density);
        return new Rect(frame.right - size, top, frame.right, top + size);
    }

    /** "▾" just below the frame's bottom-right corner. */
    private Rect hideButtonRect(int size) {
        int top = frame.bottom + Math.round(8 * density);
        if (top + size > screenHeight) top = frame.bottom - size - Math.round(8 * density);
        return new Rect(frame.right - size, top, frame.right, top + size);
    }

    private float handleSize() {
        return 10 * density;
    }

    // --- touch ------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (state == STATE_TRANSLATING) return true;
        // Touch coordinates are in the (frame-sized) window.
        float x = event.getX() + contentOffsetX;
        float y = event.getY() + contentOffsetY;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                dragged = false;
                clearing = false;
                dragCorner = -1;
                startLeft = frame.left;
                startTop = frame.top;
                startRight = frame.right;
                startBottom = frame.bottom;
                if (state == STATE_RESULT) {
                    int size = clearButtonSize();
                    if (clearButtonRect(size).contains((int) x, (int) y)) {
                        clearing = true;
                        return true;
                    }
                    if (hideButtonRect(size).contains((int) x, (int) y)) {
                        callback.onHide();
                        return true;
                    }
                    return true;
                }
                dragCorner = cornerAt(x, y);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (clearing) return true;
                float dx = x - downX;
                float dy = y - downY;
                if (!dragged && Math.hypot(dx, dy) < touchSlop) return true;
                dragged = true;
                if (dragCorner >= 0) {
                    resize(dx, dy);
                } else {
                    frame.set(startLeft, startTop, startRight, startBottom);
                    frame.offset((int) dx, (int) dy);
                    clampFrame();
                }
                updateTouchableArea();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                clearing = false;
                return true;
            case MotionEvent.ACTION_UP:
                if (clearing) {
                    clearing = false;
                    clearTranslation();
                    return true;
                }
                if (dragged) return true;
                // Only text in the result reacts to a tap; starting the
                // translation is the ball's job.
                if (state == STATE_RESULT && frame.contains((int) x, (int) y)) {
                    newResultTap(x - frame.left, y - frame.top);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void newResultTap(float imageX, float imageY) {
        if (tapListener != null) tapListener.onTap(imageX, imageY);
    }

    /** Corner handles 0-3, edge handles 4 (top) and 5 (bottom). */
    private int cornerAt(float x, float y) {
        float r = handleSize() * 2.5f;
        float cx = frame.exactCenterX();
        float cy = frame.exactCenterY();
        float[] hx = {frame.left, frame.right, frame.left, frame.right, cx, cx};
        float[] hy = {frame.top, frame.top, frame.bottom, frame.bottom, frame.top, frame.bottom};
        for (int i = 0; i < hx.length; i++) {
            if (Math.hypot(x - hx[i], y - hy[i]) < r) return i;
        }
        return -1;
    }

    private void resize(float dx, float dy) {
        int left = startLeft;
        int top = startTop;
        int right = startRight;
        int bottom = startBottom;
        switch (dragCorner) {
            case 0: left = (int) (startLeft + dx); top = (int) (startTop + dy); break;
            case 1: right = (int) (startRight + dx); top = (int) (startTop + dy); break;
            case 2: right = (int) (startRight + dx); bottom = (int) (startBottom + dy); break;
            case 3: left = (int) (startLeft + dx); bottom = (int) (startBottom + dy); break;
            case 4: top = (int) (startTop + dy); break;
            case 5: bottom = (int) (startBottom + dy); break;
            default: break;
        }
        if (right - left < minFrame) {
            if (dragCorner == 0 || dragCorner == 3) left = right - minFrame;
            else right = left + minFrame;
        }
        if (bottom - top < minFrame) {
            if (dragCorner == 0 || dragCorner == 1) top = bottom - minFrame;
            else bottom = top + minFrame;
        }
        frame.set(left, top, right, bottom);
        clampFrame();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            callback.onHide();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
