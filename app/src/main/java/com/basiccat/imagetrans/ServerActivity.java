package com.basiccat.imagetrans;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * Starts and stops the background server that the browser extension calls in
 * its "ImageTrans server" mode.
 */
public class ServerActivity extends Activity {
    private Button toggle;
    private TextView address;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server);
        toggle = findViewById(R.id.btnServerToggle);
        address = findViewById(R.id.serverAddress);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Prefs.isServerEnabled(ServerActivity.this)) {
                    ScreenshotTranslateService.stopServer(ServerActivity.this);
                } else {
                    ScreenshotTranslateService.startServer(ServerActivity.this);
                }
                // The service starts/stops asynchronously; show the new
                // address shortly afterwards.
                toggle.postDelayed(ServerActivity.this::updateState, 800);
                toggle.postDelayed(ServerActivity.this::updateState, 2500);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateState();
    }

    private void updateState() {
        boolean enabled = Prefs.isServerEnabled(this);
        toggle.setText(enabled ? R.string.main_server_on : R.string.main_server_off);
        String url = ImageTransApiServer.get().address();
        if (enabled) {
            address.setText(url != null
                    ? getString(R.string.main_server_running, url)
                    : getString(R.string.main_server_addr_unknown));
            address.setTextIsSelectable(true);
        } else {
            address.setText(R.string.main_server_hint);
        }
    }
}
