package com.basiccat.imagetrans;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
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
 * - Result: pinch to zoom the image (1× = fitted in the frame), drag with
 *   one finger to look around, tap text for its dialog; "×" clears the
 *   result. The frame itself never goes away, it is closed together with
 *   the floating ball.
 */
public class SelectionOverlay extends View {
    public interface Callback {
        /** Nothing to show any more (e.g. the frame became too small). */
        void onCancelled();
    }

    public interface TapListener {
        /**
         * @param x,y          the tap in bitmap/image pixels
         * @param displayScale screen pixels per image pixel at the tap, so
         *                     the listener can size its hit targets in dp
         * @return true when the tap was handled (a text dialog opened).
         */
        boolean onTap(float x, float y, float displayScale);

        /** A tap inside the frame, on what it shows (not on its edges). */
        default void onTapInFrame() {
        }
    }

    private static final int STATE_SELECT = 0;
    private static final int STATE_TRANSLATING = 1;
    private static final int STATE_RESULT = 2;

    private static final int MIN_FRAME_DP = 64;
    /** How far the result image can be zoomed past the fitted size. */
    private static final float MAX_ZOOM = 4f;

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
    /** Smooths the result image when it is zoomed in. */
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

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

    // Result image transform: 1× is fitted in the frame, pan is in screen px.
    private float zoom = 1f;
    private float panX, panY;
    private float startPanX, startPanY;
    private boolean pinching;
    private float pinchStartSpan;
    private float pinchStartZoom;
    /** Image pixel under the pinch focus when the pinch began. */
    private float pinchAnchorX, pinchAnchorY;
    private final Rect baseDrawRect = new Rect();
    private final RectF scaledDrawRect = new RectF();

    // Touch tracking
    private boolean dragged;
    private boolean clearing;
    private int dragCorner = -1;
    private float downRawX, downRawY;
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
        params.width = -1;
        params.height = -1;
        params.x = -1;
        params.y = -1;
        frame.set(initialFrame);
        setVisibility(View.VISIBLE);
        // Added before the flag, so a failed addView() does not leave the
        // window manager updates below thinking the view is attached.
        windowManager.addView(this, params);
        attached = true;
        // Reopen the frame's touch area once the window knows the screen size.
        post(this::updateTouchableArea);
    }

    void remove() {
        attached = false;
        // Reset the placement so a later show() places the window anew.
        params.width = -1;
        params.height = -1;
        params.x = -1;
        params.y = -1;
        clearTranslation();
        setVisibility(View.GONE);
        if (isAttachedToWindow()) windowManager.removeView(this);
        callback.onCancelled();
    }

    // --- geometry ---------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // The window is the frame's size, so the screen size comes from the
        // display and the frame keeps its position.
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        if (frame.isEmpty()) {
            frame.set(Math.round(screenWidth * 0.06f), Math.round(screenHeight * 0.18f),
                    Math.round(screenWidth * 0.94f), Math.round(screenHeight * 0.82f));
        }
        updateTouchableArea();
        android.util.Log.i("ImageTransFrame", "size " + screenWidth + "x" + screenHeight
                + " frame " + frame + " window " + params.x + "," + params.y
                + " " + params.width + "x" + params.height);
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
        if (params.width > 0 && params.x != -1) {
            // Already placed: keep the window where it is unless the frame
            // moved away from it. A position change during a drag would
            // shift the touch coordinates.
            moveWindow(params.x, params.y);
            return;
        }
        // The window is placed around the frame with room for the handles on
        // every side, so none of them are drawn outside it.
        Rect staged = new Rect(frame);
        int left = clampInt(staged.left - sideInset(), 0, screenWidth);
        int top = clampInt(staged.top - topInset(), 0, screenHeight);
        params.x = left;
        params.y = top;
        params.width = Math.max(1, Math.min(screenWidth - left, frameWidth(staged)));
        params.height = Math.max(1, Math.min(screenHeight - top, frameHeight(staged)));
        contentOffsetX = left;
        contentOffsetY = top;
        if (!attached) return;
        try {
            windowManager.updateViewLayout(this, params);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Not attached yet; show() posts another update.
        }
        invalidate();
    }

    /**
     * Fits the window around the frame (plus the insets that keep the border
     * and the handles visible). Called while resizing and when a gesture
     * ends; raw screen coordinates make it safe to move the window mid-drag.
     */
    private void fitWindowToFrame() {
        if (!attached || screenHeight == 0) return;
        int left = clampInt(frame.left - sideInset(), 0, screenWidth);
        int top = clampInt(frame.top - topInset(), 0, screenHeight);
        int width = Math.max(1, Math.min(screenWidth - left, frameWidth(frame)));
        int height = Math.max(1, Math.min(screenHeight - top, frameHeight(frame)));
        if (params.x == left && params.y == top
                && params.width == width && params.height == height) return;
        params.x = left;
        params.y = top;
        params.width = width;
        params.height = height;
        contentOffsetX = left;
        contentOffsetY = top;
        try {
            windowManager.updateViewLayout(this, params);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Detached.
        }
        invalidate();
    }

    /**
     * Moves the window without letting its position follow the frame during a
     * drag: the window keeps the size and (where possible) the position it
     * had when the gesture started, so touch coordinates stay stable. It is
     * only panned when the frame would otherwise leave it.
     */
    private void moveWindow(int preferredX, int preferredY) {
        int x = preferredX;
        int y = preferredY;
        int side = sideInset();
        int above = topInset();
        // Cover the frame plus the "×" button above it.
        if (frame.left - side < x) x = Math.max(0, frame.left - side);
        if (frame.right > x + params.width) x = Math.max(0, frame.right - params.width);
        if (frame.top - above < y) y = Math.max(0, frame.top - above);
        if (frame.bottom > y + params.height) {
            y = Math.max(0, frame.bottom - params.height);
        }
        if (x == params.x && y == params.y) {
            invalidate();
            return;
        }
        params.x = x;
        params.y = y;
        contentOffsetX = x;
        contentOffsetY = y;
        if (!attached) return;
        try {
            windowManager.updateViewLayout(this, params);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Detached while dragging.
        }
        invalidate();
    }

    /**
     * The translated image at 1×: fitted (and centred) in the frame. The
     * returned rect is reused; do not keep it across calls.
     */
    private Rect baseImageRect() {
        baseDrawRect.set(frame);
        if (translated == null || translated.getWidth() == 0 || translated.getHeight() == 0) {
            return baseDrawRect;
        }
        float scale = Math.min(frame.width() / (float) translated.getWidth(),
                frame.height() / (float) translated.getHeight());
        int w = Math.round(translated.getWidth() * scale);
        int h = Math.round(translated.getHeight() * scale);
        baseDrawRect.set(frame.left + (frame.width() - w) / 2,
                frame.top + (frame.height() - h) / 2,
                frame.left + (frame.width() + w) / 2,
                frame.top + (frame.height() + h) / 2);
        return baseDrawRect;
    }

    /** Where the result image is drawn now (fitted rect, zoomed and panned). */
    private RectF resultDestRect() {
        Rect base = baseImageRect();
        float w = base.width() * zoom;
        float h = base.height() * zoom;
        float cx = base.exactCenterX() + panX;
        float cy = base.exactCenterY() + panY;
        scaledDrawRect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        return scaledDrawRect;
    }

    /** Screen pixels per image pixel at the current zoom. */
    private float displayScale() {
        if (translated == null || translated.getWidth() == 0) return 1f;
        Rect base = baseImageRect();
        return base.width() / (float) translated.getWidth() * zoom;
    }

    /** Resets the result image to fitted, un-panned. */
    private void resetResultTransform() {
        zoom = 1f;
        panX = 0;
        panY = 0;
        pinching = false;
    }

    /**
     * Keeps the zoomed image from exposing the frame's background: an axis
     * that is larger than the frame may not show a gap, a smaller one is
     * centred (which is always the case at 1×).
     */
    private void clampPan() {
        Rect base = baseImageRect();
        float w = base.width() * zoom;
        float h = base.height() * zoom;
        float cx = base.exactCenterX() + panX;
        float cy = base.exactCenterY() + panY;
        if (w <= frame.width()) {
            cx = frame.exactCenterX();
        } else {
            // The image's left edge may be between frame.left - (w - width)
            // and frame.left, i.e. its centre between these two bounds.
            cx = Math.max(frame.right - w / 2f, Math.min(frame.left + w / 2f, cx));
        }
        if (h <= frame.height()) {
            cy = frame.exactCenterY();
        } else {
            cy = Math.max(frame.bottom - h / 2f, Math.min(frame.top + h / 2f, cy));
        }
        panX = cx - base.exactCenterX();
        panY = cy - base.exactCenterY();
    }

    /** Starts a pinch: the image point under the focus is pinned to it. */
    private void startPinch(MotionEvent event) {
        dragged = true;
        pinching = true;
        pinchStartSpan = pointerSpan(event);
        pinchStartZoom = zoom;
        float focusX = (event.getRawX(0) + event.getRawX(1)) / 2f;
        float focusY = (event.getRawY(0) + event.getRawY(1)) / 2f;
        Rect base = baseImageRect();
        float scale = base.width() / (float) translated.getWidth() * zoom;
        pinchAnchorX = (focusX - base.exactCenterX() - panX) / scale
                + translated.getWidth() / 2f;
        pinchAnchorY = (focusY - base.exactCenterY() - panY) / scale
                + translated.getHeight() / 2f;
    }

    /** Zooms around the moving pinch focus and pans with its midpoint. */
    private void updatePinch(MotionEvent event) {
        float focusX = (event.getRawX(0) + event.getRawX(1)) / 2f;
        float focusY = (event.getRawY(0) + event.getRawY(1)) / 2f;
        zoom = Math.max(1f, Math.min(MAX_ZOOM,
                pinchStartZoom * pointerSpan(event) / pinchStartSpan));
        Rect base = baseImageRect();
        float scale = base.width() / (float) translated.getWidth() * zoom;
        panX = focusX - base.exactCenterX()
                - scale * (pinchAnchorX - translated.getWidth() / 2f);
        panY = focusY - base.exactCenterY()
                - scale * (pinchAnchorY - translated.getHeight() / 2f);
        clampPan();
        invalidate();
    }

    private static float pointerSpan(MotionEvent event) {
        float dx = event.getRawX(1) - event.getRawX(0);
        float dy = event.getRawY(1) - event.getRawY(0);
        return Math.max(1f, (float) Math.hypot(dx, dy));
    }

    private Rect inflate(Rect rect) {
        float pad = 6 * density;
        Rect result = new Rect(rect);
        result.inset(-Math.round(pad), -Math.round(pad));
        return result;
    }

    /** Top of the window's visible part, relative to the frame's top. */
    private int topInset() {
        return Math.round(50 * density);
    }

    /**
     * Margin below the frame. Without it the bottom border and the bottom
     * handles would be cut off at the window's edge (they are drawn centred
     * on the frame's sides).
     */
    private int bottomInset() {
        return Math.round(12 * density);
    }

    private int frameWidth(Rect f) {
        return f.width() + 2 * sideInset();
    }

    private int frameHeight(Rect f) {
        return f.height() + topInset() + bottomInset();
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int sideInset() {
        return Math.round(12 * density);
    }

    private boolean attached;

    /**
     * The frame in real screen coordinates. The frame is kept in the
     * overlay window's own space, which starts below the status bar (the
     * system lays overlay windows out inside the app area), so the window's
     * position on screen has to be added for the capture.
     */
    Rect getFrameRectOnScreen() {
        int[] location = new int[2];
        getLocationOnScreen(location);
        Rect screen = new Rect(frame);
        screen.offset(location[0] - contentOffsetX, location[1] - contentOffsetY);
        return screen;
    }

    // --- state ------------------------------------------------------------

    /** Shows the translation inside the frame ("null": just the message). */
    void showResult(Bitmap bitmap, String message) {
        if (translated != null && translated != bitmap) translated.recycle();
        translated = bitmap;
        status = message;
        resetResultTransform();
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
        resetResultTransform();
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
        // Draw with the frame's top-left as origin.
        canvas.translate(-contentOffsetX, -contentOffsetY);
        if (state == STATE_RESULT && translated != null) {
            // Fitted (1×) or zoomed/panned; never drawn outside the frame,
            // so it cannot cover the border, the handles or the "×".
            RectF dst = resultDestRect();
            canvas.save();
            canvas.clipRect(frame);
            canvas.drawBitmap(translated, null, dst, imagePaint);
            canvas.restore();
        }
        canvas.drawRect(frame, borderPaint);
        int clearSize = clearButtonSize();
        Rect clear = clearButtonRect(clearSize);

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

    private int clearButtonSize() {
        return Math.round(36 * density);
    }

    /**
     * "×" at the frame's top-right corner, just above it. It is always placed
     * so the window (which starts above the frame) contains it.
     */
    private Rect clearButtonRect(int size) {
        int gap = Math.round(8 * density);
        int top = frame.top - size - gap;
        if (top < contentOffsetY) top = contentOffsetY;
        if (top < 0) top = frame.top + gap;
        return new Rect(frame.right - size, top, frame.right, top + size);
    }

    private float handleSize() {
        return 10 * density;
    }

    // --- touch ------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (state == STATE_TRANSLATING) return true;
        // Touch coordinates are relative to the window; the canvas is drawn
        // with the frame's top-left at (sideInset, topInset). The raw
        // coordinates are screen coordinates and never move with the window.
        float x = event.getX() + contentOffsetX;
        float y = event.getY() + contentOffsetY;
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = rawX;
                downRawY = rawY;
                dragged = false;
                clearing = false;
                dragCorner = -1;
                startLeft = frame.left;
                startTop = frame.top;
                startRight = frame.right;
                startBottom = frame.bottom;
                if (state == STATE_RESULT) {
                    pinching = false;
                    startPanX = panX;
                    startPanY = panY;
                    int size = clearButtonSize();
                    if (inflate(clearButtonRect(size)).contains((int) x, (int) y)) {
                        clearing = true;
                        return true;
                    }
                    return true;
                }
                dragCorner = cornerAt(x, y);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (clearing) return true;
                if (state == STATE_RESULT) {
                    if (pinching) {
                        if (event.getPointerCount() >= 2) updatePinch(event);
                        return true;
                    }
                    if (!dragged && Math.hypot(rawX - downRawX, rawY - downRawY) < touchSlop) {
                        return true;
                    }
                    // One finger pans the (possibly zoomed) result image.
                    dragged = true;
                    panX = startPanX + (rawX - downRawX);
                    panY = startPanY + (rawY - downRawY);
                    clampPan();
                    invalidate();
                    return true;
                }
                if (!dragged && Math.hypot(rawX - downRawX, rawY - downRawY) < touchSlop) {
                    return true;
                }
                if (!dragged) {
                    dragged = true;
                    if (dragCorner < 0) {
                        // Moving: the window must pan exactly as far as the
                        // frame, otherwise the view slides under the finger
                        // and the frame jumps.
                        followFrame();
                        return true;
                    }
                }
                if (dragCorner >= 0) {
                    // Resizing: the window stays put so the coordinates do
                    // not drift; screen distances ignore the window layout.
                    resize(rawX - downRawX, rawY - downRawY);
                    return true;
                }
                frame.set(startLeft, startTop, startRight, startBottom);
                frame.offset(Math.round(rawX - downRawX), Math.round(rawY - downRawY));
                clampFrame();
                followFrame();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (state == STATE_RESULT && translated != null
                        && event.getPointerCount() == 2) {
                    startPinch(event);
                    return true;
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (pinching) {
                    // The gesture continues as a one-finger pan from here,
                    // without a jump: rebase at the remaining finger.
                    pinching = false;
                    int remaining = event.getActionIndex() == 0 ? 1 : 0;
                    downRawX = event.getRawX(remaining);
                    downRawY = event.getRawY(remaining);
                    startPanX = panX;
                    startPanY = panY;
                    return true;
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                clearing = false;
                pinching = false;
                return true;
            case MotionEvent.ACTION_UP:
                try {
                    return handleUp(event, x, y);
                } finally {
                    fitWindowToFrame();
                }
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean handleUp(MotionEvent event, float x, float y) {
        if (clearing) {
            clearing = false;
            clearTranslation();
            return true;
        }
        if (dragged) return true;
        boolean inside = frame.contains((int) x, (int) y);
        if (state != STATE_RESULT || translated == null) {
            // Nothing to read: a tap in the frame starts the translation of
            // what is in it.
            if (inside && tapListener != null) tapListener.onTapInFrame();
            return true;
        }
        // Only text in the result reacts to a tap; on the image as a whole it
        // asks to be translated again. Map through the current zoom/pan.
        RectF image = resultDestRect();
        if (image.contains(x, y) && inside) {
            float scale = displayScale();
            if (scale > 0f) {
                boolean reading = newResultTap((x - image.left) / scale,
                        (y - image.top) / scale, scale);
                if (!reading && tapListener != null) tapListener.onTapInFrame();
            }
        }
        return true;
    }

    private boolean newResultTap(float imageX, float imageY, float displayScale) {
        return tapListener != null && tapListener.onTap(imageX, imageY, displayScale);
    }

    /** Corner handles 0-3, edge handles 4 (top) and 5 (bottom). */
    private int cornerAt(float x, float y) {
        // Edge handles only stick out of the frame; inside it, only the
        // corners (plus a small margin) resize.
        float r = 22 * density;
        float inset = 12 * density;
        float cx = frame.exactCenterX();
        float cy = frame.exactCenterY();
        float[][] corners = {
                {frame.left, frame.top}, {frame.right, frame.top},
                {frame.right, frame.bottom}, {frame.left, frame.bottom}};
        int best = -1;
        float bestDistance = r;
        for (int i = 0; i < corners.length; i++) {
            float distance = (float) Math.hypot(x - corners[i][0], y - corners[i][1]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best >= 0) return best;
        boolean above = y < frame.top + inset;
        boolean below = y > frame.bottom - inset;
        if (Math.abs(x - cx) < r) {
            if (above) return 4;
            if (below) return 5;
        }
        return -1;
    }

    /**
     * Resizes with the dragged corner/edge following the finger; the
     * opposite side stays where it was when the gesture began.
     */
    private void resize(float rawDx, float rawDy) {
        int left = startLeft;
        int top = startTop;
        int right = startRight;
        int bottom = startBottom;
        switch (dragCorner) {
            case 0: // top-left
                left = startLeft + Math.round(rawDx);
                top = startTop + Math.round(rawDy);
                break;
            case 1: // top-right
                right = startRight + Math.round(rawDx);
                top = startTop + Math.round(rawDy);
                break;
            case 2: // bottom-right
                right = startRight + Math.round(rawDx);
                bottom = startBottom + Math.round(rawDy);
                break;
            case 3: // bottom-left
                left = startLeft + Math.round(rawDx);
                bottom = startBottom + Math.round(rawDy);
                break;
            case 4: // top edge
                top = startTop + Math.round(rawDy);
                break;
            case 5: // bottom edge
                bottom = startBottom + Math.round(rawDy);
                break;
            default:
                return;
        }
        // Keep the moving side on screen.
        left = clampInt(left, 0, screenWidth);
        right = clampInt(right, 0, screenWidth);
        top = clampInt(top, 0, screenHeight);
        bottom = clampInt(bottom, 0, screenHeight);
        // The opposite side is the anchor: it must not move.
        boolean leftMoves = dragCorner == 0 || dragCorner == 3;
        boolean topMoves = dragCorner == 0 || dragCorner == 1 || dragCorner == 4;
        if (leftMoves) {
            left = Math.max(0, Math.min(left, right - minFrame));
            right = startRight;
        } else if (dragCorner == 1 || dragCorner == 2) {
            right = Math.min(screenWidth, Math.max(right, left + minFrame));
            left = startLeft;
        } else {
            left = startLeft;
            right = startRight;
        }
        if (topMoves) {
            top = Math.max(0, Math.min(top, bottom - minFrame));
            bottom = startBottom;
        } else if (dragCorner == 2 || dragCorner == 3 || dragCorner == 5) {
            bottom = Math.min(screenHeight, Math.max(bottom, top + minFrame));
            top = startTop;
        } else {
            top = startTop;
            bottom = startBottom;
        }

        boolean changed = frame.left != left || frame.top != top
                || frame.right != right || frame.bottom != bottom;
        if (changed) {
            frame.set(left, top, right, bottom);
            // Keep the window around the frame while it is being resized,
            // otherwise sides that grow past the old window are clipped.
            // Deltas are measured in raw screen coordinates, so moving or
            // resizing the window here cannot make the gesture drift.
            fitWindowToFrame();
            // The window may already be as large as the screen allows, in
            // which case fitWindowToFrame() does not repaint by itself.
            invalidate();
        }
    }

    /**
     * Pans the window along with the frame while the frame is dragged, and
     * restores the margins the frame needs. A window that was trimmed at a
     * screen edge earlier would otherwise keep clipping the opposite side
     * (e.g. the right border) until the finger is lifted.
     */
    private void followFrame() {
        fitWindowToFrame();
        // The geometry may not change (the window is already clamped at the
        // screen edge) while the frame itself still moves.
        invalidate();
    }
}
