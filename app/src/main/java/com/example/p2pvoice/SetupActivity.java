package com.example.p2pvoice;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.p2pvoice.databinding.ActivitySetupBinding;

/**
 * First-run profile setup: just pick a username and confirm the server URL.
 * The E2EE shared key is built into the app (Store.DEFAULT_E2EE_SECRET) so
 * every install can call every other install without coordinating a passphrase.
 */
public class SetupActivity extends AppCompatActivity implements SignalingHub.UiListener {

    private ActivitySetupBinding b;
    private SignalingHub hub;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySetupBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        Store store = new Store(this);
        hub = SignalingHub.get(this);

        // Pre-fill if returning
        b.etUsername.setText(store.getUsername());
        b.etServer.setText(store.getServer());

        b.btnSave.setOnClickListener(v -> save(store));
    }

    private void save(Store store) {
        String username = b.etUsername.getText().toString().trim().toLowerCase();
        String server = b.etServer.getText().toString().trim();

        if (username.isEmpty() || !username.matches("[a-z0-9_]{3,20}")) {
            Toast.makeText(this, "Username: 3-20 chars, a-z 0-9 _", Toast.LENGTH_LONG).show();
            return;
        }
        if (server.isEmpty()) { Toast.makeText(this, "Enter server URL", Toast.LENGTH_SHORT).show(); return; }

        store.setUsername(username);
        store.setServer(server);
        // Secret stays as the built-in default; nothing for the user to set.

        b.tvSetupStatus.setText("Connecting...");
        hub.setUiListener(this);
        hub.connectAndRegister();
    }

    // ---- SignalingHub.UiListener ----
    @Override public void onConnectionState(String state) {
        b.tvSetupStatus.setText("Server: " + state);
    }
    @Override public void onRegistered(String username) {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
    @Override public void onRegisterError(String reason) {
        if ("taken".equals(reason)) {
            b.tvSetupStatus.setText("That username is taken. Pick another.");
        } else {
            b.tvSetupStatus.setText("Registration failed: " + reason);
        }
    }
    @Override public void onIncomingCall(String from, String callId, boolean isVideo) {}
    @Override public void onOutgoingRinging(String to) {}
    @Override public void onCallConnected(String peer) {}
    @Override public void onCallEnded(String reason) {}
}
