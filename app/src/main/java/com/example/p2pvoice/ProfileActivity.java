package com.example.p2pvoice;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.p2pvoice.databinding.ActivityProfileBinding;

import java.io.File;

/**
 * Profile editor: lets the user set their display name and avatar. When
 * "Save" is tapped, the changes go through the hub which encrypts and pushes
 * a profile_msg to every contact.
 *
 * Avatar previews are shown locally as soon as a picture is picked — the user
 * doesn't have to wait for the upload to complete to see what they chose.
 */
public class ProfileActivity extends AppCompatActivity {

    private ActivityProfileBinding b;
    private Store store;
    private SignalingHub hub;
    /** Picked but not yet committed avatar. Null until user picks one. */
    private Uri pendingAvatarUri;

    private final ActivityResultLauncher<String> picker =
            registerForActivityResult(new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) {
                            pendingAvatarUri = uri;
                            // Show the picked image immediately so the user
                            // gets feedback before they tap Save.
                            previewPickedAvatar(uri);
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        store = new Store(this);
        hub = SignalingHub.get(this);

        b.btnBack.setOnClickListener(v -> finish());
        b.btnChangeAvatar.setOnClickListener(v -> {
            try { picker.launch("image/*"); }
            catch (Throwable t) {
                Toast.makeText(this, "Couldn't open photo picker",
                        Toast.LENGTH_SHORT).show();
            }
        });
        b.btnSave.setOnClickListener(v -> save());
        b.btnTheme.setOnClickListener(v -> showThemePicker());
        refreshThemeLabel();
        b.btnReadReceipts.setOnClickListener(v -> {
            boolean cur = store.getSendReadReceipts();
            store.setSendReadReceipts(!cur);
            refreshReadReceiptsLabel();
        });
        refreshReadReceiptsLabel();

        b.etDisplayName.setText(store.getMyDisplayName());
        b.tvUsername.setText("@" + store.getUsername());

        // Render the current avatar (if any) or fall back to letter tile.
        renderCurrentAvatar();
    }

    private void renderCurrentAvatar() {
        String path = store.getMyAvatarPath();
        if (path != null && new File(path).exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(path);
            if (bmp != null) {
                b.imgAvatar.setImageBitmap(ProfileManager.toCircle(bmp));
                b.imgAvatar.setVisibility(View.VISIBLE);
                b.tvAvatarInitial.setVisibility(View.GONE);
                return;
            }
        }
        // Letter tile.
        String name = store.getMyDisplayName();
        String initial = (name == null || name.isEmpty())
                ? "?" : name.substring(0, 1).toUpperCase();
        b.tvAvatarInitial.setText(initial);
        b.tvAvatarInitial.setVisibility(View.VISIBLE);
        b.imgAvatar.setVisibility(View.GONE);
    }

    private void previewPickedAvatar(Uri uri) {
        try {
            // Quick low-res preview just so the user sees their selection.
            // The real (resized, encrypted) upload happens on Save.
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return;
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp != null) {
                    b.imgAvatar.setImageBitmap(ProfileManager.toCircle(bmp));
                    b.imgAvatar.setVisibility(View.VISIBLE);
                    b.tvAvatarInitial.setVisibility(View.GONE);
                }
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't preview image", Toast.LENGTH_SHORT).show();
        }
    }

    /** Show the current theme as a human-friendly label on the button. */
    private void refreshThemeLabel() {
        String mode = store.getThemeMode();
        String label;
        switch (mode) {
            case "light": label = "Theme: Light"; break;
            case "dark":  label = "Theme: Dark"; break;
            default:      label = "Theme: System";
        }
        b.btnTheme.setText(label);
    }

    private void refreshReadReceiptsLabel() {
        b.btnReadReceipts.setText(store.getSendReadReceipts()
                ? "Send read receipts: On" : "Send read receipts: Off");
    }

    /** Prompt the user to pick a theme, then persist + apply it immediately.
     *  AppCompatDelegate.setDefaultNightMode recreates running activities so
     *  the new colours show up without restarting the app. */
    private void showThemePicker() {
        String current = store.getThemeMode();
        String[] modes  = { "system", "light", "dark" };
        String[] labels = { "Follow system", "Light", "Dark" };
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(current)) { checked = i; break; }
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    String pick = modes[which];
                    store.setThemeMode(pick);
                    int night;
                    switch (pick) {
                        case "light": night = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO; break;
                        case "dark":  night = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES; break;
                        default:      night = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                    }
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(night);
                    refreshThemeLabel();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void save() {
        String name = b.etDisplayName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Pick a display name", Toast.LENGTH_SHORT).show();
            return;
        }
        b.btnSave.setEnabled(false);
        b.btnSave.setText(pendingAvatarUri != null ? "Uploading…" : "Saving…");
        // The hub handles encryption + upload + per-contact push.
        hub.updateMyProfile(name, pendingAvatarUri);
        // The upload runs on a background thread; we don't have a direct
        // success callback into this activity. Optimistically finish after
        // a short delay so users aren't blocked staring at the screen.
        b.btnSave.postDelayed(() -> {
            if (!isFinishing()) {
                Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
                finish();
            }
        }, pendingAvatarUri != null ? 1500 : 300);
    }
}
