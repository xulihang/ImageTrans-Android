package com.basiccat.imagetrans;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;

public class SettingsActivity extends Activity {
    private static final String[] REPAIR_NAMES = {"白色背景", "取背景色", "不处理"};
    private static final String[] REPAIR_VALUES = {"white", "background", "none"};
    private static final String[] RENDER_NAMES = {
            "服务端渲染（ImageTrans 返回图片）", "App 内渲染（返回文字框）"};
    private static final String[] RENDER_VALUES = {
            Prefs.SERVER_RENDER_SERVER, Prefs.SERVER_RENDER_APP};

    private RadioGroup modeGroup;
    private RadioButton modeImagetrans;
    private RadioButton modeOpenai;
    private RadioButton modeOriginal;
    private LinearLayout serverSection;
    private LinearLayout openaiSection;
    private EditText editServerUrl;
    private EditText editDisplayName;
    private EditText editPassword;
    private Spinner spinnerServerRender;
    private EditText editOpenAiUrl;
    private EditText editOpenAiKey;
    private EditText editOpenAiModel;
    private Spinner spinnerSource;
    private Spinner spinnerTarget;
    private EditText editRenderCss;
    private EditText editMinFontSize;
    private Spinner spinnerRepairMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        modeGroup = findViewById(R.id.modeGroup);
        modeImagetrans = findViewById(R.id.modeImagetrans);
        modeOpenai = findViewById(R.id.modeOpenai);
        modeOriginal = findViewById(R.id.modeOriginal);
        serverSection = findViewById(R.id.serverSection);
        openaiSection = findViewById(R.id.openaiSection);
        editServerUrl = findViewById(R.id.editServerUrl);
        editDisplayName = findViewById(R.id.editDisplayName);
        editPassword = findViewById(R.id.editPassword);
        spinnerServerRender = findViewById(R.id.spinnerServerRender);
        editOpenAiUrl = findViewById(R.id.editOpenAiUrl);
        editOpenAiKey = findViewById(R.id.editOpenAiKey);
        editOpenAiModel = findViewById(R.id.editOpenAiModel);
        spinnerSource = findViewById(R.id.spinnerSource);
        spinnerTarget = findViewById(R.id.spinnerTarget);
        editRenderCss = findViewById(R.id.editRenderCss);
        editMinFontSize = findViewById(R.id.editMinFontSize);
        spinnerRepairMode = findViewById(R.id.spinnerRepairMode);

        setupSpinners();
        loadSettings();

        modeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                updateSections();
            }
        });

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });
    }

    private void setupSpinners() {
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, Prefs.LANG_NAMES);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSource.setAdapter(langAdapter);
        spinnerTarget.setAdapter(langAdapter);

        ArrayAdapter<String> repairAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, REPAIR_NAMES);
        repairAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRepairMode.setAdapter(repairAdapter);

        ArrayAdapter<String> renderAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, RENDER_NAMES);
        renderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerServerRender.setAdapter(renderAdapter);
    }

    private void loadSettings() {
        String mode = Prefs.getMode(this);
        if (Prefs.MODE_OPENAI.equals(mode)) {
            modeOpenai.setChecked(true);
        } else if (Prefs.MODE_ORIGINAL.equals(mode)) {
            modeOriginal.setChecked(true);
        } else {
            modeImagetrans.setChecked(true);
        }

        editServerUrl.setText(Prefs.getServerUrl(this));
        editDisplayName.setText(Prefs.getDisplayName(this));
        editPassword.setText(Prefs.getPassword(this));
        String renderMode = Prefs.getServerRenderMode(this);
        for (int i = 0; i < RENDER_VALUES.length; i++) {
            if (RENDER_VALUES[i].equals(renderMode)) {
                spinnerServerRender.setSelection(i);
                break;
            }
        }
        editOpenAiUrl.setText(Prefs.getOpenAiUrl(this));
        editOpenAiKey.setText(Prefs.getOpenAiKey(this));
        editOpenAiModel.setText(Prefs.getOpenAiModel(this));

        spinnerSource.setSelection(indexOfCode(Prefs.getSourceLang(this)));
        spinnerTarget.setSelection(indexOfCode(Prefs.getTargetLang(this)));
        editRenderCss.setText(Prefs.getRenderCss(this));
        editMinFontSize.setText(String.valueOf(Prefs.getMinFontSize(this)));

        String repair = Prefs.getTextRepairMode(this);
        for (int i = 0; i < REPAIR_VALUES.length; i++) {
            if (REPAIR_VALUES[i].equals(repair)) {
                spinnerRepairMode.setSelection(i);
                break;
            }
        }

        updateSections();
    }

    private void updateSections() {
        int checked = modeGroup.getCheckedRadioButtonId();
        boolean server = checked == R.id.modeImagetrans;
        boolean openai = checked == R.id.modeOpenai;
        serverSection.setVisibility(server ? View.VISIBLE : View.GONE);
        openaiSection.setVisibility(openai ? View.VISIBLE : View.GONE);
    }

    private void saveSettings() {
        int checked = modeGroup.getCheckedRadioButtonId();
        String mode;
        if (checked == R.id.modeOpenai) {
            mode = Prefs.MODE_OPENAI;
        } else if (checked == R.id.modeOriginal) {
            mode = Prefs.MODE_ORIGINAL;
        } else {
            mode = Prefs.MODE_IMAGETRANS;
        }

        String serverUrl = editServerUrl.getText().toString().trim();
        if (Prefs.MODE_IMAGETRANS.equals(mode) && serverUrl.isEmpty()) {
            ImageUtils.toast(this, "请填写 ImageTrans 服务器地址");
            return;
        }

        int minFont = 14;
        try {
            minFont = Integer.parseInt(editMinFontSize.getText().toString().trim());
        } catch (Exception ignored) {
        }
        if (minFont <= 0) minFont = 14;

        Prefs.setMode(this, mode);
        Prefs.setServerUrl(this, serverUrl);
        Prefs.setDisplayName(this, editDisplayName.getText().toString().trim());
        Prefs.setPassword(this, editPassword.getText().toString());
        Prefs.setServerRenderMode(this,
                RENDER_VALUES[spinnerServerRender.getSelectedItemPosition()]);
        Prefs.setOpenAiUrl(this, editOpenAiUrl.getText().toString().trim());
        Prefs.setOpenAiKey(this, editOpenAiKey.getText().toString().trim());
        Prefs.setOpenAiModel(this, editOpenAiModel.getText().toString().trim());
        Prefs.setSourceLang(this, Prefs.LANG_CODES[spinnerSource.getSelectedItemPosition()]);
        Prefs.setTargetLang(this, Prefs.LANG_CODES[spinnerTarget.getSelectedItemPosition()]);
        Prefs.setRenderCss(this, editRenderCss.getText().toString());
        Prefs.setMinFontSize(this, minFont);
        Prefs.setTextRepairMode(this, REPAIR_VALUES[spinnerRepairMode.getSelectedItemPosition()]);

        ImageUtils.toast(this, "设置已保存");
        finish();
    }

    private int indexOfCode(String code) {
        for (int i = 0; i < Prefs.LANG_CODES.length; i++) {
            if (Prefs.LANG_CODES[i].equals(code)) return i;
        }
        return 0;
    }
}
