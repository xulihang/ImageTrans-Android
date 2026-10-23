package com.basiccat.imagetrans;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;

public class SettingsActivity extends LocalizedActivity {
    private static final String[] UI_LANG_VALUES = {
            LocaleHelper.SYSTEM, LocaleHelper.ZH, LocaleHelper.EN};
    private static final String[] REPAIR_VALUES = {"white", "background", "none"};
    private static final String[] JA_DETECTION_VALUES = {
            Prefs.JP_DETECT_YOLO, Prefs.JP_DETECT_PADDLE};
    private static final String[] RENDER_VALUES = {
            Prefs.SERVER_RENDER_SERVER, Prefs.SERVER_RENDER_APP};

    private RadioGroup modeGroup;
    private RadioButton modeImagetrans;
    private RadioButton modeMyMemory;
    private RadioButton modeOpenai;
    private RadioButton modeOriginal;
    private LinearLayout serverSection;
    private LinearLayout mymemorySection;
    private LinearLayout openaiSection;
    private EditText editServerUrl;
    private EditText editDisplayName;
    private EditText editPassword;
    private Spinner spinnerServerRender;
    private Spinner spinnerUiLang;
    private EditText editMyMemoryEmail;
    private EditText editOpenAiUrl;
    private EditText editOpenAiKey;
    private EditText editOpenAiModel;
    private EditText editOpenAiPrompt;
    private Spinner spinnerSource;
    private Spinner spinnerTarget;
    private EditText editRenderCss;
    private CheckBox checkVertical;
    private EditText editMinFontSize;
    private Spinner spinnerRepairMode;
    private Spinner spinnerJaDetection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applySystemBarInsets();

        modeGroup = findViewById(R.id.modeGroup);
        modeImagetrans = findViewById(R.id.modeImagetrans);
        modeMyMemory = findViewById(R.id.modeMyMemory);
        modeOpenai = findViewById(R.id.modeOpenai);
        modeOriginal = findViewById(R.id.modeOriginal);
        serverSection = findViewById(R.id.serverSection);
        mymemorySection = findViewById(R.id.mymemorySection);
        openaiSection = findViewById(R.id.openaiSection);
        editServerUrl = findViewById(R.id.editServerUrl);
        editDisplayName = findViewById(R.id.editDisplayName);
        editPassword = findViewById(R.id.editPassword);
        spinnerServerRender = findViewById(R.id.spinnerServerRender);
        spinnerUiLang = findViewById(R.id.spinnerUiLang);
        editMyMemoryEmail = findViewById(R.id.editMyMemoryEmail);
        editOpenAiUrl = findViewById(R.id.editOpenAiUrl);
        editOpenAiKey = findViewById(R.id.editOpenAiKey);
        editOpenAiModel = findViewById(R.id.editOpenAiModel);
        editOpenAiPrompt = findViewById(R.id.editOpenAiPrompt);
        spinnerSource = findViewById(R.id.spinnerSource);
        spinnerTarget = findViewById(R.id.spinnerTarget);
        editRenderCss = findViewById(R.id.editRenderCss);
        checkVertical = findViewById(R.id.checkVertical);
        editMinFontSize = findViewById(R.id.editMinFontSize);
        spinnerRepairMode = findViewById(R.id.spinnerRepairMode);
        spinnerJaDetection = findViewById(R.id.spinnerJaDetection);

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
                this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.language_names));
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSource.setAdapter(langAdapter);
        spinnerTarget.setAdapter(langAdapter);

        ArrayAdapter<String> repairAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.repair_mode_names));
        repairAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRepairMode.setAdapter(repairAdapter);

        ArrayAdapter<String> jaDetectionAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.ja_detection_names));
        jaDetectionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerJaDetection.setAdapter(jaDetectionAdapter);

        ArrayAdapter<String> renderAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.render_mode_names));
        renderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerServerRender.setAdapter(renderAdapter);

        ArrayAdapter<String> uiLangAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.ui_language_names));
        uiLangAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerUiLang.setAdapter(uiLangAdapter);
    }

    private void loadSettings() {
        String mode = Prefs.getMode(this);
        if (Prefs.MODE_MYMEMORY.equals(mode)) {
            modeMyMemory.setChecked(true);
        } else if (Prefs.MODE_OPENAI.equals(mode)) {
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
        editMyMemoryEmail.setText(Prefs.getMyMemoryEmailOverride(this));
        editOpenAiUrl.setText(Prefs.getOpenAiUrl(this));
        editOpenAiKey.setText(Prefs.getOpenAiKey(this));
        editOpenAiModel.setText(Prefs.getOpenAiModel(this));
        editOpenAiPrompt.setText(Prefs.getOpenAiPrompt(this));

        spinnerSource.setSelection(indexOfCode(Prefs.getSourceLang(this)));
        spinnerTarget.setSelection(indexOfCode(Prefs.getTargetLang(this)));
        spinnerUiLang.setSelection(indexOfUiLang(Prefs.getUiLang(this)));
        editRenderCss.setText(Prefs.getRenderCss(this));
        checkVertical.setChecked(Prefs.isVerticalText(this));
        editMinFontSize.setText(String.valueOf(Prefs.getMinFontSize(this)));

        String repair = Prefs.getTextRepairMode(this);
        for (int i = 0; i < REPAIR_VALUES.length; i++) {
            if (REPAIR_VALUES[i].equals(repair)) {
                spinnerRepairMode.setSelection(i);
                break;
            }
        }

        String jaDetection = Prefs.getJapaneseDetection(this);
        for (int i = 0; i < JA_DETECTION_VALUES.length; i++) {
            if (JA_DETECTION_VALUES[i].equals(jaDetection)) {
                spinnerJaDetection.setSelection(i);
                break;
            }
        }

        updateSections();
    }

    private void updateSections() {
        int checked = modeGroup.getCheckedRadioButtonId();
        boolean server = checked == R.id.modeImagetrans;
        boolean mymemory = checked == R.id.modeMyMemory;
        boolean openai = checked == R.id.modeOpenai;
        serverSection.setVisibility(server ? View.VISIBLE : View.GONE);
        mymemorySection.setVisibility(mymemory ? View.VISIBLE : View.GONE);
        openaiSection.setVisibility(openai ? View.VISIBLE : View.GONE);
    }

    private void saveSettings() {
        int checked = modeGroup.getCheckedRadioButtonId();
        String mode;
        if (checked == R.id.modeMyMemory) {
            mode = Prefs.MODE_MYMEMORY;
        } else if (checked == R.id.modeOpenai) {
            mode = Prefs.MODE_OPENAI;
        } else if (checked == R.id.modeOriginal) {
            mode = Prefs.MODE_ORIGINAL;
        } else {
            mode = Prefs.MODE_IMAGETRANS;
        }

        String serverUrl = editServerUrl.getText().toString().trim();
        if (Prefs.MODE_IMAGETRANS.equals(mode) && serverUrl.isEmpty()) {
            ImageUtils.toast(this, getString(R.string.settings_server_url_required));
            return;
        }

        int minFont = 14;
        try {
            minFont = Integer.parseInt(editMinFontSize.getText().toString().trim());
        } catch (Exception ignored) {
        }
        if (minFont <= 0) minFont = 14;

        // The {texts} placeholder is how the texts reach the model; without
        // it nothing can be translated.
        String prompt = editOpenAiPrompt.getText().toString().trim();
        if (!prompt.isEmpty() && !prompt.contains("{texts}")) {
            ImageUtils.toast(this, getString(R.string.settings_prompt_needs_texts));
            return;
        }

        String newUiLang = UI_LANG_VALUES[spinnerUiLang.getSelectedItemPosition()];
        boolean uiLanguageChanged = !newUiLang.equals(Prefs.getUiLang(this));

        Prefs.setMode(this, mode);
        Prefs.setUiLang(this, newUiLang);
        Prefs.setServerUrl(this, serverUrl);
        Prefs.setDisplayName(this, editDisplayName.getText().toString().trim());
        Prefs.setPassword(this, editPassword.getText().toString());
        Prefs.setServerRenderMode(this,
                RENDER_VALUES[spinnerServerRender.getSelectedItemPosition()]);
        Prefs.setMyMemoryEmail(this, editMyMemoryEmail.getText().toString());
        Prefs.setOpenAiUrl(this, editOpenAiUrl.getText().toString().trim());
        Prefs.setOpenAiKey(this, editOpenAiKey.getText().toString().trim());
        Prefs.setOpenAiModel(this, editOpenAiModel.getText().toString().trim());
        Prefs.setOpenAiPrompt(this, prompt.isEmpty() ? Prefs.DEFAULT_PROMPT : prompt);
        Prefs.setSourceLang(this, Prefs.LANG_CODES[spinnerSource.getSelectedItemPosition()]);
        Prefs.setTargetLang(this, Prefs.LANG_CODES[spinnerTarget.getSelectedItemPosition()]);
        Prefs.setRenderCss(this, editRenderCss.getText().toString());
        Prefs.setVerticalText(this, checkVertical.isChecked());
        Prefs.setMinFontSize(this, minFont);
        Prefs.setTextRepairMode(this, REPAIR_VALUES[spinnerRepairMode.getSelectedItemPosition()]);
        Prefs.setJapaneseDetection(this,
                JA_DETECTION_VALUES[spinnerJaDetection.getSelectedItemPosition()]);

        if (uiLanguageChanged) {
            // Recreate the task so every screen picks up the new locale.
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        } else {
            ImageUtils.toast(this, getString(R.string.settings_saved));
        }
        finish();
    }

    private int indexOfCode(String code) {
        for (int i = 0; i < Prefs.LANG_CODES.length; i++) {
            if (Prefs.LANG_CODES[i].equals(code)) return i;
        }
        return 0;
    }

    private int indexOfUiLang(String language) {
        for (int i = 0; i < UI_LANG_VALUES.length; i++) {
            if (UI_LANG_VALUES[i].equals(language)) return i;
        }
        return 0;
    }
}
