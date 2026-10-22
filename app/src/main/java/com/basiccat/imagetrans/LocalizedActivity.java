package com.basiccat.imagetrans;

import android.app.Activity;
import android.content.Context;

/** Base Activity that applies the selected UI language to its resources. */
public class LocalizedActivity extends Activity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }
}
