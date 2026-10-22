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
                    // Flip the intent now so a quick leave/resume cannot see
                    // the old "enabled" value and start the service again.
                    Prefs.setServerEnabled(ServerActivity.this, false);
                    ScreenshotTranslateService.stopServer(ServerActivity.this);
                } else {
                    startServer();
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
        if (Prefs.isServerEnabled(this)) {
            // The process (and with it the foreground service notification)
            // may have been killed while the enabled flag stayed set: start
            // the service again instead of only reopening the socket.
            startServer();
            toggle.postDelayed(this::updateState, 800);
            toggle.postDelayed(this::updateState, 2500);
        }
        updateState();
    }

    private void startServer() {
        try {
            ScreenshotTranslateService.startServer(this);
        } catch (Throwable throwable) {
            ImageUtils.toast(this, getString(R.string.server_start_failed,
                    ImageUtils.humanError(throwable)));
        }
    }

    private void updateState() {
        boolean enabled = Prefs.isServerEnabled(this);
        toggle.setText(enabled ? R.string.main_server_on : R.string.main_server_off);
        String local = ImageTransApiServer.get().address();
        if (enabled) {
            if (local == null) {
                address.setText(R.string.main_server_not_running);
            } else {
                StringBuilder text = new StringBuilder(
                        getString(R.string.main_server_running, local));
                String lan = ImageTransApiServer.get().lanAddress();
                if (lan != null) {
                    text.append('\n').append(getString(R.string.main_server_lan, lan));
                }
                address.setText(text.toString());
            }
            address.setTextIsSelectable(true);
        } else {
            address.setText(R.string.main_server_hint);
        }
    }
}
