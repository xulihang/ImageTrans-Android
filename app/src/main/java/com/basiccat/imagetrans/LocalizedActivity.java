package com.basiccat.imagetrans;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Base Activity that applies the selected UI language to its resources and
 * lays its content out edge-to-edge.
 */
public class LocalizedActivity extends Activity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Android 15+ enforces edge-to-edge for apps targeting API 35+. Opt in
        // on older releases too, so the inset handling below is exercised on
        // every supported version instead of only on new devices.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    }

    /** Pads the screen content for a layout without a header bar. */
    protected void applySystemBarInsets() {
        applySystemBarInsets(0, false);
    }

    /**
     * Pads the screen content for edge-to-edge. For header layouts the header
     * is pushed down by the status bar inset, so its background fills the
     * status bar area and the root only takes the side and bottom insets.
     *
     * @param appBarId           header view shown under the status bar, or 0
     * @param lightNavigationBar true when the bottom of the screen is dark
     */
    protected void applySystemBarInsets(int appBarId, boolean lightNavigationBar) {
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        final View root = content.getChildAt(0);
        final View appBar = appBarId == 0 ? null : findViewById(appBarId);

        final int rootLeft = root.getPaddingLeft();
        final int rootTop = root.getPaddingTop();
        final int rootRight = root.getPaddingRight();
        final int rootBottom = root.getPaddingBottom();
        final int barLeft = appBar == null ? 0 : appBar.getPaddingLeft();
        final int barTop = appBar == null ? 0 : appBar.getPaddingTop();
        final int barRight = appBar == null ? 0 : appBar.getPaddingRight();
        final int barBottom = appBar == null ? 0 : appBar.getPaddingBottom();

        // Dark headers need light status bar icons; light screens need dark
        // ones. The navigation bar follows the background at the screen bottom.
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(appBar == null);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(!lightNavigationBar);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            // Keep content above the keyboard: with decor fitting disabled the
            // window is no longer resized when the IME opens.
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);
            if (appBar == null) {
                view.setPadding(rootLeft + bars.left, rootTop + bars.top,
                        rootRight + bars.right, rootBottom + bottom);
            } else {
                appBar.setPadding(barLeft, barTop + bars.top, barRight, barBottom);
                view.setPadding(rootLeft + bars.left, rootTop,
                        rootRight + bars.right, rootBottom + bottom);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
