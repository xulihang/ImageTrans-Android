package com.basiccat.imagetrans;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_IMAGE = 1001;

    private TextView modeSummary;
    private TextView langSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        modeSummary = findViewById(R.id.modeSummary);
        langSummary = findViewById(R.id.langSummary);

        findViewById(R.id.btnPickImage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        updateSummary();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSummary();
    }

    private void updateSummary() {
        String mode = Prefs.getMode(this);
        modeSummary.setText("翻译方式：" + Prefs.modeName(mode));
        langSummary.setText("语言：" + Prefs.langName(Prefs.getSourceLang(this))
                + " → " + Prefs.langName(Prefs.getTargetLang(this)));
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
            // Some providers don't offer persistable permissions; the temporary
            // grant is enough for the TranslateActivity launch below.
        }
        Intent intent = new Intent(this, TranslateActivity.class);
        intent.setData(uri);
        startActivity(intent);
    }
}
