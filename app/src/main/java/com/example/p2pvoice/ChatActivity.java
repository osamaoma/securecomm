package com.example.p2pvoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.p2pvoice.databinding.ActivityChatBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * One-on-one chat screen. While this activity is in the foreground, incoming
 * messages render directly into the list and unread-count for this peer is
 * kept at zero. When backgrounded, the SignalingHub falls back to system
 * notifications via {@link ChatNotifier}.
 */
public class ChatActivity extends AppCompatActivity implements SignalingHub.ChatListener {

    public static final String EXTRA_PEER = "peer";

    private ActivityChatBinding b;
    private SignalingHub hub;
    private Store store;
    private String peer;

    private final List<ChatMessage> messages = new ArrayList<>();
    private ChatAdapter adapter;
    /** True when this chat is a group conversation (peer = "group:<uuid>"). */
    private boolean isGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        peer = getIntent().getStringExtra(EXTRA_PEER);
        if (peer == null) { finish(); return; }
        peer = peer.toLowerCase();
        isGroup = Group.isGroupPeerId(peer);

        hub = SignalingHub.get(this);
        store = new Store(this);

        b.tvPeer.setText(peer);
        b.btnBack.setOnClickListener(v -> finish());
        renderPeerHeader();

        if (isGroup) {
            // Groups don't have pairwise SAS verification — the group key is
            // delivered over already-verified pairwise channels. Tapping the
            // header opens group info instead of the security code dialog.
            b.tvSecurity.setOnClickListener(v -> openGroupInfo());
            b.tvPeer.setOnClickListener(v -> openGroupInfo());
            b.imgHeaderAvatar.setOnClickListener(v -> openGroupInfo());
            b.tvHeaderAvatar.setOnClickListener(v -> openGroupInfo());
            // No group calls in v1.
            b.btnCallFromChat.setVisibility(View.GONE);
            b.btnVideoCallFromChat.setVisibility(View.GONE);
        } else {
            // Tap the security label to view the verification code.
            b.tvSecurity.setOnClickListener(v -> showSecurityInfo());
            b.btnCallFromChat.setOnClickListener(v -> placeCall(false));
            b.btnVideoCallFromChat.setOnClickListener(v -> placeCall(true));
        }

        adapter = new ChatAdapter(messages, this::onImageTap, this::onVoiceTap,
                this::onLongPressMessage, this::onRichTap,
                hub, store.getUsername(), isGroup);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true); // newest at the bottom
        b.rvMessages.setLayoutManager(lm);
        b.rvMessages.setAdapter(adapter);

        b.btnSend.setOnClickListener(v -> sendCurrent());
        b.etText.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendCurrent(); return true; }
            return false;
        });
        // TextWatcher drives the typing indicator. The actual throttling +
        // stop scheduling is in onTextChanged() below.
        b.etText.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b1, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b1, int c) {
                onComposerTextChanged(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        b.btnAttach.setOnClickListener(v -> showAttachMenu());
        wireMicButton();
        b.btnCancelRecord.setOnClickListener(v -> cancelRecording());
    }

    private void openGroupInfo() {
        Intent i = new Intent(this, GroupInfoActivity.class);
        i.putExtra(GroupInfoActivity.EXTRA_GROUP_ID, Group.groupIdFromPeerId(peer));
        startActivity(i);
    }

    // Modern Android photo picker — doesn't require READ_MEDIA_IMAGES permission.
    private final androidx.activity.result.ActivityResultLauncher<String> picker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) sendImage(uri);
                    });

    // File picker. GetContent (with MIME "*/*") is broadly compatible across
    // OEM file managers — more so than the modern OpenDocument contract,
    // which some Android skins misinterpret and filter to specific types.
    private final androidx.activity.result.ActivityResultLauncher<String> docPicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) sendDocument(uri);
                    });

    // One-shot location permission request. Stored callback is invoked on grant.
    private Runnable locationPermissionContinuation;
    private final androidx.activity.result.ActivityResultLauncher<String[]> locationPermLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        Runnable cb = locationPermissionContinuation;
                        locationPermissionContinuation = null;
                        Boolean fine = result.get(Manifest.permission.ACCESS_FINE_LOCATION);
                        Boolean coarse = result.get(Manifest.permission.ACCESS_COARSE_LOCATION);
                        if ((fine != null && fine) || (coarse != null && coarse)) {
                            if (cb != null) cb.run();
                        } else {
                            android.widget.Toast.makeText(this,
                                    "Location permission denied",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });

    private void launchImagePicker() {
        try {
            picker.launch("image/*");
        } catch (Throwable t) {
            android.widget.Toast.makeText(this,
                    "Couldn't open image picker", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Show the attach options sheet: Photo, File, Contact, Location. */
    private void showAttachMenu() {
        CharSequence[] items = new CharSequence[] {
                "\uD83D\uDDBC\uFE0F  Photo",
                "\uD83D\uDCCE  File (any type)",
                "\uD83D\uDC64  Contact",
                "\uD83D\uDCCD  Location",
        };
        new AlertDialog.Builder(this)
                .setTitle("Attach")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: launchImagePicker();    break;
                        case 1: launchDocumentPicker(); break;
                        case 2: showContactPicker();    break;
                        case 3: shareLocation();        break;
                    }
                })
                .show();
    }

    private void launchDocumentPicker() {
        try {
            // "*/*" accepts every kind of file. We separately enforce a 16MB
            // cap in MediaTransfer.uploadDocument.
            docPicker.launch("*/*");
        } catch (Throwable t) {
            android.widget.Toast.makeText(this,
                    "Couldn't open file picker", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Choose one of our saved contacts and send it as a card. We deliberately
     *  do NOT read the device contact list (would need READ_CONTACTS and feels
     *  invasive) — sharing app contacts is the more relevant action anyway. */
    private void showContactPicker() {
        java.util.List<Contact> contacts = store.getContacts();
        // Filter out the current peer for a 1-on-1 chat (no point sharing them
        // with themselves) and skip pseudo-group-contacts.
        java.util.List<Contact> options = new java.util.ArrayList<>();
        for (Contact c : contacts) {
            if (Group.isGroupPeerId(c.username)) continue;
            if (!isGroup && c.username.equalsIgnoreCase(peer)) continue;
            options.add(c);
        }
        if (options.isEmpty()) {
            android.widget.Toast.makeText(this,
                    "You don't have any contacts to share yet",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] labels = new CharSequence[options.size()];
        for (int i = 0; i < options.size(); i++) {
            Contact c = options.get(i);
            Store.PeerProfile p = store.getPeerProfile(c.username);
            String name = p != null && p.displayName != null && !p.displayName.isEmpty()
                    ? p.displayName : c.displayName;
            labels[i] = name + " (@" + c.username + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Share contact")
                .setItems(labels, (d, which) -> sendContactCard(options.get(which)))
                .show();
    }

    private void sendContactCard(Contact c) {
        Store.PeerProfile p = store.getPeerProfile(c.username);
        String name = p != null && p.displayName != null && !p.displayName.isEmpty()
                ? p.displayName : c.displayName;
        String payload = RichContent.encodeContact(c.username, name);
        // Going through the normal text-send path means the message inherits
        // E2EE, group fan-out, replies, forwarding, status updates, etc.
        ChatMessage m = hub.sendChatMessage(peer, payload, null, null, false);
        if (m != null) {
            // Stamp the kind locally so our own bubble renders as a card.
            m.kind = ChatMessage.Kind.CONTACT;
            store.putMessage(m);
            messages.add(m);
            adapter.notifyItemInserted(messages.size() - 1);
            b.rvMessages.scrollToPosition(messages.size() - 1);
            b.tvEmpty.setVisibility(View.GONE);
            b.rvMessages.setVisibility(View.VISIBLE);
        }
    }

    private void shareLocation() {
        int fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        int coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            locationPermissionContinuation = this::doShareLocation;
            locationPermLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
            });
            return;
        }
        doShareLocation();
    }

    @SuppressWarnings("MissingPermission")
    private void doShareLocation() {
        android.location.LocationManager lm = (android.location.LocationManager)
                getSystemService(LOCATION_SERVICE);
        if (lm == null) {
            android.widget.Toast.makeText(this, "Location service unavailable",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.location.Location best = null;
        // Try every available provider and pick the most recent fix.
        try {
            for (String p : lm.getProviders(true)) {
                android.location.Location loc = lm.getLastKnownLocation(p);
                if (loc == null) continue;
                if (best == null || loc.getTime() > best.getTime()) best = loc;
            }
        } catch (SecurityException ignored) {}
        if (best == null) {
            android.widget.Toast.makeText(this,
                    "No recent location — enable GPS and try again",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String payload = RichContent.encodeLocation(best.getLatitude(), best.getLongitude(), null);
        ChatMessage m = hub.sendChatMessage(peer, payload, null, null, false);
        if (m != null) {
            m.kind = ChatMessage.Kind.LOCATION;
            store.putMessage(m);
            messages.add(m);
            adapter.notifyItemInserted(messages.size() - 1);
            b.rvMessages.scrollToPosition(messages.size() - 1);
            b.tvEmpty.setVisibility(View.GONE);
            b.rvMessages.setVisibility(View.VISIBLE);
        }
    }

    private void sendDocument(android.net.Uri uri) {
        // Look up the original filename + MIME from SAF.
        String fileName = "file";
        String mime = "application/octet-stream";
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && nameIdx >= 0) {
                    String n = c.getString(nameIdx);
                    if (n != null && !n.isEmpty()) fileName = n;
                }
                c.close();
            }
            String t = getContentResolver().getType(uri);
            if (t != null && !t.isEmpty()) mime = t;
        } catch (Throwable ignored) {}

        ChatMessage m = hub.sendDocument(uri, peer, fileName, mime);
        if (m != null) {
            messages.add(m);
            adapter.notifyItemInserted(messages.size() - 1);
            b.rvMessages.scrollToPosition(messages.size() - 1);
            b.tvEmpty.setVisibility(View.GONE);
            b.rvMessages.setVisibility(View.VISIBLE);
        }
    }

    private void sendImage(android.net.Uri uri) {
        // The hub does the heavy lifting on a background thread and persists
        // the placeholder message immediately. Show it.
        ChatMessage m = hub.sendImage(uri, peer, b.etText.getText().toString().trim());
        b.etText.setText("");
        messages.add(m);
        adapter.notifyItemInserted(messages.size() - 1);
        b.rvMessages.scrollToPosition(messages.size() - 1);
        b.tvEmpty.setVisibility(View.GONE);
        b.rvMessages.setVisibility(View.VISIBLE);
    }

    /** User tapped an image bubble. For outgoing messages we already have it
     *  locally; for incoming we need to download+decrypt the full image first
     *  (the bubble was showing the thumbnail until now). */
    private void onImageTap(ChatMessage m) {
        if (m.localPath != null && new java.io.File(m.localPath).exists()) {
            openImageViewer(m.localPath);
            return;
        }
        // Need to download. Outgoing-not-yet-sent images don't have a remote URL.
        if (m.mediaUrl == null) {
            android.widget.Toast.makeText(this,
                    "Image not ready yet", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.Toast.makeText(this,
                "Downloading…", android.widget.Toast.LENGTH_SHORT).show();
        hub.downloadImage(m, new MediaTransfer.DownloadCallback() {
            @Override public void onSuccess(String localPath) {
                runOnUiThread(() -> {
                    // Refresh the row so the bubble's main image becomes the full version.
                    int idx = messages.indexOf(m);
                    if (idx >= 0) adapter.notifyItemChanged(idx);
                    openImageViewer(localPath);
                });
            }
            @Override public void onFailure(Throwable t) {
                runOnUiThread(() -> android.widget.Toast.makeText(
                        ChatActivity.this,
                        "Couldn't load image: " + t.getMessage(),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    private void openImageViewer(String localPath) {
        Intent i = new Intent(this, ImageViewerActivity.class);
        i.putExtra(ImageViewerActivity.EXTRA_PATH, localPath);
        startActivity(i);
    }

    // ===================================================================
    // Voice messages — press and hold the mic to record, release to send.
    // ===================================================================

    private VoiceRecorder recorder;
    private final android.os.Handler recordingHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable recordingTick = new Runnable() {
        @Override public void run() {
            if (recorder != null && recorder.isRecording()) {
                int ms = recorder.elapsedMs();
                int s = ms / 1000;
                b.tvRecordingTimer.setText(String.format(java.util.Locale.US,
                        "Recording…  %d:%02d", s / 60, s % 60));
                recordingHandler.postDelayed(this, 200);
            }
        }
    };

    // Tracks whether a touch-down on the mic button is currently expected to
    // become a recording (i.e. we passed the permission gate). Avoids stale
    // ACTION_UP firing send when no recording actually started.
    private boolean recordingArmed = false;

    private final androidx.activity.result.ActivityResultLauncher<String> micPermLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (!granted) {
                            android.widget.Toast.makeText(this,
                                    "Microphone permission denied",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                        // We don't auto-start recording — the user has to press the
                        // mic again now that permission is granted. Telling them
                        // explicitly is clearer than starting silently after a
                        // permission dialog they're still mentally processing.
                    });

    private void wireMicButton() {
        b.btnMic.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO);
                        return true;
                    }
                    startRecording();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if (recordingArmed) finishRecording();
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (recordingArmed) cancelRecording();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void startRecording() {
        // Stop any currently-playing voice message — listening and recording
        // at the same time is confusing and can cause audio routing glitches.
        VoicePlayer.get().stop();

        recorder = new VoiceRecorder(this);
        try {
            recorder.start();
        } catch (Throwable t) {
            android.widget.Toast.makeText(this,
                    "Can't start recording: " + t.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            recorder = null;
            return;
        }
        recordingArmed = true;
        b.recordingBanner.setVisibility(View.VISIBLE);
        b.tvRecordingTimer.setText("Recording…  0:00");
        recordingHandler.post(recordingTick);
    }

    private void finishRecording() {
        recordingArmed = false;
        recordingHandler.removeCallbacks(recordingTick);
        b.recordingBanner.setVisibility(View.GONE);

        VoiceRecorder r = recorder;
        recorder = null;
        if (r == null) return;
        VoiceRecorder.Result result = r.stop();
        if (result == null) {
            android.widget.Toast.makeText(this,
                    "Hold to record",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Send via the hub — same async upload pipeline as images.
        ChatMessage m = hub.sendVoiceMessage(peer, result.filePath, result.durationMs);
        messages.add(m);
        adapter.notifyItemInserted(messages.size() - 1);
        b.rvMessages.scrollToPosition(messages.size() - 1);
        b.tvEmpty.setVisibility(View.GONE);
        b.rvMessages.setVisibility(View.VISIBLE);
    }

    private void cancelRecording() {
        recordingArmed = false;
        recordingHandler.removeCallbacks(recordingTick);
        b.recordingBanner.setVisibility(View.GONE);
        if (recorder != null) {
            recorder.discard();
            recorder = null;
        }
    }

    /** User tapped the play button on a voice bubble. */
    /** Dispatch taps on document / contact / location bubbles. Each kind has
     *  its own action: documents download then open with the system viewer,
     *  contacts offer to be saved, locations open in Maps. */
    private void onRichTap(ChatMessage m) {
        switch (m.kind) {
            case DOCUMENT: openDocumentBubble(m); break;
            case CONTACT:  openContactBubble(m);  break;
            case LOCATION: openLocationBubble(m); break;
            default: /* nothing */ break;
        }
    }

    private void openDocumentBubble(ChatMessage m) {
        if (m.mediaUrl == null) {
            android.widget.Toast.makeText(this,
                    "Document not ready yet", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Outgoing messages that we just sent have the original bytes on disk
        // already — no need to download. But for simplicity we always download
        // on tap, since the encrypted blob lives at mediaUrl and our local
        // cache pipeline gives us a file ready for FileProvider to share.
        android.widget.Toast.makeText(this, "Downloading…",
                android.widget.Toast.LENGTH_SHORT).show();
        hub.downloadDocument(m, new MediaTransfer.DownloadCallback() {
            @Override public void onSuccess(String localPath) {
                runOnUiThread(() -> openDocumentFile(localPath, m.mediaType));
            }
            @Override public void onFailure(Throwable t) {
                runOnUiThread(() -> android.widget.Toast.makeText(
                        ChatActivity.this,
                        "Couldn't load document: " + t.getMessage(),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    private void openDocumentFile(String localPath, String mimeType) {
        java.io.File f = new java.io.File(localPath);
        if (!f.exists()) {
            android.widget.Toast.makeText(this, "File missing",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // Use FileProvider so we can share with apps that don't have
            // permission to read our cache dir directly.
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", f);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri,
                    mimeType != null ? mimeType : "application/octet-stream");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, "Open with"));
        } catch (Throwable t) {
            android.widget.Toast.makeText(this,
                    "No app can open this file type",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void openContactBubble(ChatMessage m) {
        Contact c = RichContent.parseContact(m.text);
        android.util.Log.d("ChatActivity", "openContactBubble parsed="
                + (c == null ? "null" : c.username));
        if (c == null) {
            android.widget.Toast.makeText(this, "Invalid contact card",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Are they already saved?
        boolean exists = false;
        for (Contact existing : store.getContacts()) {
            if (existing.username.equalsIgnoreCase(c.username)) { exists = true; break; }
        }
        final Contact toAdd = c;
        if (exists) {
            new AlertDialog.Builder(this)
                    .setTitle(toAdd.displayName)
                    .setMessage("@" + toAdd.username + "\n\nAlready in your contacts.")
                    .setPositiveButton("Message", (d, w) -> {
                        Intent i = new Intent(this, ChatActivity.class);
                        i.putExtra(ChatActivity.EXTRA_PEER, toAdd.username);
                        startActivity(i);
                    })
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Add contact?")
                .setMessage(toAdd.displayName + "\n@" + toAdd.username)
                .setPositiveButton("Add", (d, w) -> {
                    store.addContact(toAdd);
                    android.widget.Toast.makeText(ChatActivity.this,
                            "Added " + toAdd.displayName,
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openLocationBubble(ChatMessage m) {
        double[] coords = RichContent.parseLocation(m.text);
        android.util.Log.d("ChatActivity", "openLocationBubble coords=" +
                (coords == null ? "null" : (coords[0] + "," + coords[1])));
        if (coords == null) {
            android.widget.Toast.makeText(this, "Invalid location",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The "geo:0,0?q=lat,lng" form is the most universally-supported
        // variant — it works in Google Maps, OsmAnd, Maps.me, Here WeGo, etc.
        // Force Locale.US so the lat/lng use periods regardless of device locale.
        String latLng = String.format(java.util.Locale.US, "%f,%f", coords[0], coords[1]);
        android.net.Uri uri = android.net.Uri.parse("geo:0,0?q=" + latLng);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        // Use a chooser so the user sees every installed map app, not just
        // whichever one is set as default. Safer when one of the apps is
        // misbehaving — they can pick another.
        try {
            startActivity(Intent.createChooser(intent, "Open in maps"));
        } catch (Throwable t) {
            android.util.Log.w("ChatActivity", "open in maps failed", t);
            // Last-resort fallback: show the coords in a copy-friendly dialog.
            new AlertDialog.Builder(this)
                    .setTitle("Location")
                    .setMessage(latLng + "\n\n(No map app could handle this — install Google Maps or copy the coordinates.)")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void onVoiceTap(ChatMessage m) {
        // If this file is already playing, treat the tap as pause/stop.
        if (m.localPath != null && VoicePlayer.get().isPlaying(m.localPath)) {
            VoicePlayer.get().stop();
            adapter.notifyDataSetChanged();
            return;
        }

        // Already cached locally — play immediately.
        if (m.localPath != null && new java.io.File(m.localPath).exists()) {
            playLocal(m);
            return;
        }
        // Otherwise download+decrypt, then play.
        if (m.mediaUrl == null) {
            android.widget.Toast.makeText(this,
                    "Voice message not ready yet",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.Toast.makeText(this, "Loading…",
                android.widget.Toast.LENGTH_SHORT).show();
        hub.downloadVoice(m, new MediaTransfer.DownloadCallback() {
            @Override public void onSuccess(String localPath) {
                runOnUiThread(() -> {
                    int idx = messages.indexOf(m);
                    if (idx >= 0) adapter.notifyItemChanged(idx);
                    playLocal(m);
                });
            }
            @Override public void onFailure(Throwable t) {
                runOnUiThread(() -> android.widget.Toast.makeText(
                        ChatActivity.this,
                        "Couldn't load voice message: " + t.getMessage(),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    private void playLocal(ChatMessage m) {
        VoicePlayer.get().play(m.localPath, new VoicePlayer.Listener() {
            @Override public void onProgress(int positionMs, int totalMs) {
                int idx = messages.indexOf(m);
                if (idx >= 0) {
                    // Update the duration label as a countdown for the row that's playing.
                    androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
                            b.rvMessages.findViewHolderForAdapterPosition(idx);
                    if (vh instanceof ChatAdapter.VH) {
                        ChatAdapter.VH cvh = (ChatAdapter.VH) vh;
                        int remainingMs = Math.max(0, totalMs - positionMs);
                        int s = remainingMs / 1000;
                        cvh.voiceDuration.setText(String.format(java.util.Locale.US,
                                "%d:%02d", s / 60, s % 60));
                        int pct = totalMs > 0 ? (int) ((positionMs * 100L) / totalMs) : 0;
                        cvh.voiceProgress.setProgress(pct);
                    }
                }
            }
            @Override public void onComplete() {
                int idx = messages.indexOf(m);
                if (idx >= 0) adapter.notifyItemChanged(idx);
            }
            @Override public void onStopped() {
                int idx = messages.indexOf(m);
                if (idx >= 0) adapter.notifyItemChanged(idx);
            }
            @Override public void onError(String msg) {
                android.widget.Toast.makeText(ChatActivity.this,
                        "Playback failed: " + msg,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        // Reflect playing state immediately (▶ → ⏸).
        int idx = messages.indexOf(m);
        if (idx >= 0) adapter.notifyItemChanged(idx);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.addChatListener(this);
        loadHistory();
        // Visiting the chat clears its unread + any pending notification.
        store.clearUnread(peer);
        ChatNotifier.clear(this, peer);
        if (!isGroup) {
            // Make sure we have the peer's public key — otherwise the first
            // message will fall through to legacy crypto.
            hub.requestPeerKey(peer);
            // Push our own profile in case this is a brand-new contact who
            // doesn't have our display name/avatar yet. This is idempotent on
            // their side (version check skips stale).
            hub.pushProfileTo(peer);
            // Subscribe to their presence so the header can show online state.
            hub.subscribePresence(peer);
            // Acknowledge every still-unread INCOMING message as read.
            hub.markChatRead(peer, messages);
        }
        renderPeerHeader();
        if (!isGroup) refreshSecurityIndicator();
        refreshBlockedBanner();
        // Apply any cached presence we already have (saves a flicker while
        // the subscribe-reply makes the round trip).
        if (!isGroup) {
            long[] cached = hub.getPresence(peer);
            if (cached != null) {
                peerOnline = (int) cached[0];
                peerLastSeen = cached[1];
            }
        }
        refreshHeaderSubtitle();
    }

    /** Show / hide the "you blocked this contact" banner, and disable the
     *  composer while blocked. Only applies to 1-on-1 chats (groups don't
     *  support per-member blocking in v1). */
    private void refreshBlockedBanner() {
        boolean blocked = !isGroup && store.isBlocked(peer);
        b.blockedBanner.setVisibility(blocked ? View.VISIBLE : View.GONE);
        // Disable text input + send + attach + mic when blocked.
        b.etText.setEnabled(!blocked);
        b.btnSend.setEnabled(!blocked);
        b.btnAttach.setEnabled(!blocked);
        b.btnMic.setEnabled(!blocked);
        if (blocked) {
            b.btnUnblock.setOnClickListener(v -> {
                store.setBlocked(peer, false);
                android.widget.Toast.makeText(this, "Unblocked",
                        android.widget.Toast.LENGTH_SHORT).show();
                refreshBlockedBanner();
            });
        }
    }

    private void refreshSecurityIndicator() {
        // Group chats don't have a pairwise security code — that label is
        // repurposed for the member count in renderPeerHeader. Skip.
        if (isGroup) return;
        if (keyRotatedWarning) {
            b.tvSecurity.setText("\u26a0\ufe0f security code changed — tap to re-verify");
            b.tvSecurity.setTextColor(ContextCompat.getColor(this, R.color.accent_warn));
        } else if (hub.hasSecureKey(peer)) {
            b.tvSecurity.setText("\ud83d\udd12 end-to-end encrypted · tap to verify");
            b.tvSecurity.setTextColor(ContextCompat.getColor(this, R.color.accent_success));
        } else {
            b.tvSecurity.setText("\u26a0\ufe0f using fallback key");
            b.tvSecurity.setTextColor(ContextCompat.getColor(this, R.color.accent_warn));
        }
    }

    private void showSecurityInfo() {
        String sas = hub.getSAS(peer);
        String body;
        if (keyRotatedWarning) {
            body = "\u26a0\ufe0f " + peer + "'s security code has changed since the last time you verified.\n\n"
                 + "This is normal if they reinstalled the app or switched devices.\n\n"
                 + "It can also (rarely) mean someone is intercepting your conversation.\n\n"
                 + "New verification code:\n\n"
                 + "    " + (sas != null ? (sas.substring(0, 3) + " " + sas.substring(3)) : "(pending)") + "\n\n"
                 + "Compare the new code with " + peer + " out-of-band to confirm.";
        } else if (sas != null) {
            body = "Compare this 6-digit code with " + peer + " out-of-band "
                 + "(in person, phone call, etc.) to verify nobody is intercepting your conversation.\n\n"
                 + "Your verification code:\n\n"
                 + "    " + sas.substring(0, 3) + " " + sas.substring(3) + "\n\n"
                 + "If the codes match on both phones, the conversation is "
                 + "end-to-end encrypted with a key only your two devices know.";
        } else {
            body = "We're still waiting for " + peer + "'s public key. "
                 + "Messages will use a fallback shared key until then.";
        }
        new AlertDialog.Builder(this)
                .setTitle(keyRotatedWarning ? "Security code changed" : "Security verification")
                .setMessage(body)
                .setPositiveButton(keyRotatedWarning ? "I've re-verified" : "Got it",
                        (d, w) -> {
                            if (keyRotatedWarning) {
                                keyRotatedWarning = false;
                                refreshSecurityIndicator();
                            }
                        })
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        hub.removeChatListener(this);
        // Don't leave a recording open or audio playing when the user navigates away.
        if (recorder != null) cancelRecording();
        VoicePlayer.get().stop();
        // Stop telling the peer we're typing, and stop watching their presence.
        sendTypingStopNow();
        if (!isGroup) hub.unsubscribePresence(peer);
        // Clear pending typing-clear runnables.
        for (Runnable r : typingClears.values()) {
            b.tvSecurity.removeCallbacks(r);
        }
        typingClears.clear();
        activeTypers.clear();
    }

    private void loadHistory() {
        messages.clear();
        messages.addAll(store.getMessages(peer));
        adapter.notifyDataSetChanged();
        boolean empty = messages.isEmpty();
        b.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        b.rvMessages.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) b.rvMessages.scrollToPosition(messages.size() - 1);
    }

    private void sendCurrent() {
        String text = b.etText.getText().toString().trim();
        if (text.isEmpty()) return;
        // Cancel any pending typing-stop and tell the peer immediately.
        sendTypingStopNow();
        // If a reply is active, pass its id + preview so the recipient sees
        // the quote box. clearReplyTarget() resets the input bar state.
        ChatMessage m;
        if (activeReplyTarget != null) {
            m = hub.sendChatMessage(peer, text,
                    activeReplyTarget.id,
                    previewOf(activeReplyTarget),
                    false);
            clearReplyTarget();
        } else {
            m = hub.sendChatMessage(peer, text);
        }
        b.etText.setText("");
        // Append locally so the user sees it instantly with PENDING status.
        messages.add(m);
        adapter.notifyItemInserted(messages.size() - 1);
        b.rvMessages.scrollToPosition(messages.size() - 1);
        b.tvEmpty.setVisibility(View.GONE);
        b.rvMessages.setVisibility(View.VISIBLE);
    }

    private void placeCall(boolean isVideo) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                    .setMessage("Microphone permission required. Open Contacts first to grant it.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (isVideo && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                    .setMessage("Camera permission required for video calls. Grant it in app settings and try again.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        hub.placeCall(peer, isVideo);
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra(CallActivity.EXTRA_PEER, peer);
        i.putExtra(CallActivity.EXTRA_MODE, CallActivity.MODE_OUTGOING);
        i.putExtra(CallActivity.EXTRA_IS_VIDEO, isVideo);
        startActivity(i);
    }

    // ----- ChatListener -----
    @Override
    public void onChatMessage(ChatMessage msg) {
        if (!peer.equalsIgnoreCase(msg.peer)) return;
        refreshSecurityIndicator();
        // We're foreground for this peer: clear any unread immediately.
        store.clearUnread(peer);
        messages.add(msg);
        adapter.notifyItemInserted(messages.size() - 1);
        b.rvMessages.scrollToPosition(messages.size() - 1);
        b.tvEmpty.setVisibility(View.GONE);
        b.rvMessages.setVisibility(View.VISIBLE);
        // Live-arrived message while this chat is foreground → ack as read.
        if (!isGroup && msg.direction == ChatMessage.Direction.INCOMING) {
            hub.markChatRead(peer, java.util.Collections.singletonList(msg));
        }
    }

    @Override
    public void onChatStatus(String otherPeer, String id, ChatMessage.Status status) {
        if (!peer.equalsIgnoreCase(otherPeer)) return;
        refreshSecurityIndicator();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id.equals(id)) {
                messages.get(i).status = status;
                adapter.notifyItemChanged(i);
                break;
            }
        }
    }

    /** A peer's pubkey changed since we last cached one. Either they
     *  reinstalled / switched devices, or someone is impersonating them.
     *  Surface this distinctly so the user re-verifies the SAS. */
    @Override
    public void onPeerKeyRotated(String rotatedPeer) {
        if (!peer.equalsIgnoreCase(rotatedPeer)) return;
        keyRotatedWarning = true;
        refreshSecurityIndicator();
        // The local copy of peer's pubkey is now fresh; previously
        // undecryptable image thumbnails may now decrypt. Force a refresh.
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onPeerKeyRefreshed(String refreshedPeer) {
        if (!peer.equalsIgnoreCase(refreshedPeer)) return;
        // Same intent as onPeerKeyRotated but fires for every fetch (even
        // when nothing changed). Cheap to redraw, and lets cases where the
        // local key was unset-then-set update without showing a rotation
        // warning the user didn't actually need to see.
        adapter.notifyDataSetChanged();
    }

    /** Repaint the header avatar + name from cached peer profile (if any),
     *  or group name + member count if this is a group conversation. */
    private void renderPeerHeader() {
        if (isGroup) {
            Group g = store.getGroup(Group.groupIdFromPeerId(peer));
            String name = g != null ? g.name : "Group";
            b.tvPeer.setText(name);
            // Reuse the security indicator slot for the member count.
            int members = g != null ? g.members.size() : 0;
            b.tvSecurity.setText("\uD83D\uDC65 " + members
                    + (members == 1 ? " member · group encrypted" : " members · group encrypted"));
            b.tvSecurity.setTextColor(ContextCompat.getColor(this, R.color.text_dim));

            String init = name.isEmpty() ? "G" : name.substring(0, 1).toUpperCase();
            b.tvHeaderAvatar.setText(init);
            b.tvHeaderAvatar.setVisibility(View.VISIBLE);
            b.imgHeaderAvatar.setVisibility(View.GONE);
            return;
        }

        Store.PeerProfile prof = store.getPeerProfile(peer);
        String name = prof != null && prof.displayName != null && !prof.displayName.isEmpty()
                ? prof.displayName : peer;
        b.tvPeer.setText(name);

        android.graphics.Bitmap bmp = null;
        if (prof != null && prof.avatarPath != null
                && new java.io.File(prof.avatarPath).exists()) {
            bmp = android.graphics.BitmapFactory.decodeFile(prof.avatarPath);
        }
        if (bmp != null) {
            b.imgHeaderAvatar.setImageBitmap(ProfileManager.toCircle(bmp));
            b.imgHeaderAvatar.setVisibility(View.VISIBLE);
            b.tvHeaderAvatar.setVisibility(View.GONE);
        } else {
            String init = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
            b.tvHeaderAvatar.setText(init);
            b.tvHeaderAvatar.setVisibility(View.VISIBLE);
            b.imgHeaderAvatar.setVisibility(View.GONE);
        }
    }

    @Override
    public void onPeerProfileUpdated(String updatedPeer) {
        if (!peer.equalsIgnoreCase(updatedPeer)) return;
        renderPeerHeader();
    }

    @Override
    public void onGroupChanged(String groupId) {
        if (!isGroup) return;
        if (!groupId.equalsIgnoreCase(Group.groupIdFromPeerId(peer))) return;
        // Refresh header (member count, possibly name) and reload messages —
        // we may have inserted a synthetic "X joined/left" notice.
        renderPeerHeader();
        loadHistory();
    }

    // ===================================================================
    // Typing indicator + presence — header subtitle state machine.
    // ===================================================================
    //
    // Three sources of subtitle text, in priority order:
    //   1. Typing (peer / one or more group members is actively composing)
    //   2. Online status / last-seen timestamp (1-on-1 only)
    //   3. Security line / member count (existing fallback)
    //
    // The subtitle reuses the same b.tvSecurity view that the security
    // indicator was already painting, so there's no extra layout slot to
    // manage.

    /** Who's currently typing in this conversation, mapped to the ms
     *  timestamp at which they were last seen typing. For 1-on-1 the only
     *  ever-present key is `peer`; for groups it can hold several. Entries
     *  are removed by per-key delayed runnables ({@code typingClears}) so
     *  a missed "stop" event self-heals after ~5s. */
    private final java.util.Map<String, Long> activeTypers = new java.util.HashMap<>();
    private final java.util.Map<String, Runnable> typingClears = new java.util.HashMap<>();

    /** Throttle state for OUTGOING typing events: when we last told the peer
     *  "I'm typing". Re-sending typing=true any more than every 3 seconds
     *  is wasteful since the recipient already maintains a 5s timeout. */
    private long lastTypingSentTs;
    /** Pending "I stopped typing" runnable, scheduled 5s after every text
     *  change. Re-scheduled on each keystroke so it only fires after a
     *  genuine pause. */
    private Runnable scheduledTypingStop;

    /** Peer's cached online state. -1 = unknown, 0 = offline, 1 = online. */
    private int peerOnline = -1;
    /** Peer's last-seen ms timestamp (0 if unknown or currently online). */
    private long peerLastSeen;

    private static final long TYPING_THROTTLE_MS    = 3_000L;
    private static final long TYPING_STOP_IDLE_MS   = 5_000L;
    private static final long TYPING_AUTOCLEAR_MS   = 6_000L;

    @Override
    public void onTypingChanged(String from, boolean typing, String groupPeerId) {
        if (from == null) return;
        // Filter to events for *this* conversation only.
        boolean forThisChat = isGroup
                ? (groupPeerId != null && groupPeerId.equalsIgnoreCase(peer))
                : (groupPeerId == null && from.equalsIgnoreCase(peer));
        if (!forThisChat) return;
        if (typing) {
            activeTypers.put(from, System.currentTimeMillis());
            // Self-heal in case a "stop" event is lost.
            Runnable prev = typingClears.remove(from);
            if (prev != null) b.tvSecurity.removeCallbacks(prev);
            Runnable clear = () -> {
                activeTypers.remove(from);
                typingClears.remove(from);
                refreshHeaderSubtitle();
            };
            typingClears.put(from, clear);
            b.tvSecurity.postDelayed(clear, TYPING_AUTOCLEAR_MS);
        } else {
            activeTypers.remove(from);
            Runnable prev = typingClears.remove(from);
            if (prev != null) b.tvSecurity.removeCallbacks(prev);
        }
        refreshHeaderSubtitle();
    }

    @Override
    public void onPresenceChanged(String user, boolean online, long lastSeen) {
        if (user == null || isGroup) return;
        if (!user.equalsIgnoreCase(peer)) return;
        peerOnline = online ? 1 : 0;
        peerLastSeen = lastSeen;
        refreshHeaderSubtitle();
    }

    /** Throttle-aware handler for composer keystrokes. Sends typing=true at
     *  most once every {@link #TYPING_THROTTLE_MS}, and re-schedules a
     *  "I've stopped" event to fire after {@link #TYPING_STOP_IDLE_MS} of
     *  inactivity. Clearing the composer immediately sends typing=false. */
    private void onComposerTextChanged(String current) {
        // Empty text → not typing.
        if (current.isEmpty()) {
            sendTypingStopNow();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTypingSentTs > TYPING_THROTTLE_MS) {
            hub.sendTyping(peer, true);
            lastTypingSentTs = now;
        }
        // (Re-)schedule the stop. removeCallbacks on a null is a no-op so
        // this is safe to call every keystroke.
        if (scheduledTypingStop != null) {
            b.etText.removeCallbacks(scheduledTypingStop);
        }
        scheduledTypingStop = () -> {
            hub.sendTyping(peer, false);
            lastTypingSentTs = 0;
            scheduledTypingStop = null;
        };
        b.etText.postDelayed(scheduledTypingStop, TYPING_STOP_IDLE_MS);
    }

    /** Cancel any pending stop callback and tell the peer immediately that
     *  we're no longer typing. Used by send / clear / activity-pause paths. */
    private void sendTypingStopNow() {
        if (scheduledTypingStop != null) {
            b.etText.removeCallbacks(scheduledTypingStop);
            scheduledTypingStop = null;
        }
        if (lastTypingSentTs > 0) {
            hub.sendTyping(peer, false);
            lastTypingSentTs = 0;
        }
    }

    /** Compute and apply the chat-header subtitle based on current state. */
    private void refreshHeaderSubtitle() {
        String typingText = computeTypingText();
        if (typingText != null) {
            b.tvSecurity.setText(typingText);
            b.tvSecurity.setTextColor(androidx.core.content.ContextCompat.getColor(
                    this, R.color.accent_link));
            return;
        }
        // No-one typing: 1-on-1 chats fall through to presence; group chats
        // fall through to the member-count subtitle. Both eventually reach
        // refreshSecurityIndicator / renderPeerHeader which is the existing
        // baseline.
        if (!isGroup) {
            if (peerOnline == 1) {
                b.tvSecurity.setText("online");
                b.tvSecurity.setTextColor(androidx.core.content.ContextCompat.getColor(
                        this, R.color.accent_success));
                return;
            }
            if (peerOnline == 0 && peerLastSeen > 0) {
                b.tvSecurity.setText("last seen " + formatLastSeen(peerLastSeen));
                b.tvSecurity.setTextColor(androidx.core.content.ContextCompat.getColor(
                        this, R.color.text_dim));
                return;
            }
            refreshSecurityIndicator();
            return;
        }
        // Group: refresh header (member count) baseline.
        renderPeerHeader();
    }

    /** Build the "X is typing" string from {@link #activeTypers}. Returns
     *  null when no-one is typing. Resolves display names for groups so the
     *  user sees friendly names rather than raw usernames. */
    private String computeTypingText() {
        if (activeTypers.isEmpty()) return null;
        if (!isGroup) {
            // 1-on-1: the only possible typer is the peer; show a simple verb.
            return "typing\u2026"; // "typing…"
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String username : activeTypers.keySet()) {
            Store.PeerProfile p = store.getPeerProfile(username);
            names.add(p != null && p.displayName != null && !p.displayName.isEmpty()
                    ? p.displayName : username);
        }
        java.util.Collections.sort(names);
        if (names.size() == 1) return names.get(0) + " is typing\u2026";
        if (names.size() == 2) return names.get(0) + " and " + names.get(1) + " are typing\u2026";
        return names.size() + " people are typing\u2026";
    }

    /** Format a last-seen timestamp as "today at HH:mm", "yesterday at HH:mm",
     *  or "<weekday> at HH:mm" depending on age. Anything older than a week
     *  shows the date. */
    private static String formatLastSeen(long ts) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(ts);
        String time = android.text.format.DateFormat.format("HH:mm", then).toString();
        if (sameDay(now, then)) return "today at " + time;
        // Yesterday?
        java.util.Calendar y = (java.util.Calendar) now.clone();
        y.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (sameDay(y, then)) return "yesterday at " + time;
        long diff = now.getTimeInMillis() - ts;
        if (diff < 7L * 24 * 3600 * 1000) {
            return android.text.format.DateFormat.format("EEEE", then).toString()
                    .toLowerCase() + " at " + time;
        }
        return android.text.format.DateFormat.format("MMM d", then).toString()
                + " at " + time;
    }
    private static boolean sameDay(java.util.Calendar a, java.util.Calendar b) {
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    @Override
    public void onReactionUpdated(String reactionPeer, String targetId) {
        // The reaction event might be for a message in this chat OR one we
        // sent ourselves. Check both directions.
        if (peer.equalsIgnoreCase(reactionPeer)) {
            int idx = indexOfMessageId(targetId);
            if (idx >= 0) {
                // Pick up the new reactions map from storage and refresh just
                // that row. Cheaper than reloading the whole history.
                java.util.List<ChatMessage> latest = store.getMessages(peer);
                for (ChatMessage m : latest) {
                    if (m.id.equals(targetId)) {
                        messages.get(idx).reactions = m.reactions;
                        break;
                    }
                }
                adapter.notifyItemChanged(idx);
            }
        }
    }

    private int indexOfMessageId(String id) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    // ===================================================================
    // Long-press action menu: Reply / React / Forward / Copy
    // ===================================================================

    /** The message being replied-to, if any. Cleared by clearReplyTarget(). */
    private ChatMessage activeReplyTarget;

    private void onLongPressMessage(ChatMessage m, View anchor) {
        // Vibrate briefly for haptic feedback if the system honors it.
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        showActionMenu(m, anchor);
    }

    private void showActionMenu(ChatMessage m, View anchor) {
        // Build a popup with action rows.
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        android.view.Menu menu = pm.getMenu();
        boolean isMine = m.direction == ChatMessage.Direction.OUTGOING;
        // Deleted messages have nothing to interact with except a "Remove"
        // local clean-up (handled outside this menu via long-press history).
        if (m.deleted) {
            pm.show();
            return;
        }
        menu.add(0, 1, 0, "Reply");
        menu.add(0, 2, 1, "React");
        menu.add(0, 3, 2, "Forward");
        if (m.kind == ChatMessage.Kind.TEXT) {
            menu.add(0, 4, 3, "Copy");
        }
        // Edit: only for outgoing text-y messages, within 15 minutes of
        // original send. Media captions aren't editable in v1.
        boolean editable = isMine
                && (m.kind == ChatMessage.Kind.TEXT
                    || m.kind == ChatMessage.Kind.CONTACT
                    || m.kind == ChatMessage.Kind.LOCATION)
                && (System.currentTimeMillis() - m.timestamp) <= EDIT_WINDOW_MS;
        if (editable) menu.add(0, 5, 4, "Edit");
        // Unsend: any outgoing message, any kind, any time.
        if (isMine) menu.add(0, 6, 5, "Unsend");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: setReplyTarget(m);     return true;
                case 2: showReactionPicker(m); return true;
                case 3: showForwardPicker(m);  return true;
                case 4: copyToClipboard(m);    return true;
                case 5: showEditDialog(m);     return true;
                case 6: confirmUnsend(m);      return true;
                default: return false;
            }
        });
        pm.show();
    }

    /** How long after sending a message it can still be edited. WhatsApp uses
     *  15 minutes; we mirror that for familiarity. */
    private static final long EDIT_WINDOW_MS = 15 * 60 * 1000L;

    private void showEditDialog(ChatMessage m) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(m.text == null ? "" : m.text);
        input.setSelection(input.getText().length());
        input.setSingleLine(false);
        new AlertDialog.Builder(this)
                .setTitle("Edit message")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String newText = input.getText().toString();
                    if (newText.isEmpty()) {
                        android.widget.Toast.makeText(this,
                                "Use Unsend to delete a message",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (newText.equals(m.text)) return; // no change
                    boolean ok = hub.editChatMessage(m, newText);
                    if (!ok) {
                        android.widget.Toast.makeText(this,
                                "Couldn't edit (older than 15 minutes?)",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Refresh the bubble immediately so the user sees the change
                    // without waiting for a round-trip.
                    int idx = indexOfMessage(m.id);
                    if (idx >= 0) adapter.notifyItemChanged(idx);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmUnsend(ChatMessage m) {
        new AlertDialog.Builder(this)
                .setTitle("Unsend message?")
                .setMessage("This will replace the message with a placeholder for everyone in the conversation.")
                .setPositiveButton("Unsend", (d, w) -> {
                    boolean ok = hub.deleteChatMessage(m);
                    if (!ok) return;
                    int idx = indexOfMessage(m.id);
                    if (idx >= 0) adapter.notifyItemChanged(idx);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int indexOfMessage(String id) {
        return indexOfMessageId(id);
    }

    /** Called when a peer (or our own other-device — though we don't have
     *  multi-device yet) edits or unsends a message we already have. The
     *  hub has already updated the stored message; we just sync the
     *  in-memory list and refresh the row. */
    @Override
    public void onChatMessageUpdated(ChatMessage updated) {
        if (!peer.equalsIgnoreCase(updated.peer)) return;
        int idx = indexOfMessageId(updated.id);
        if (idx < 0) return;
        // Replace the in-memory copy with the fresh one so re-binds pick up
        // the new text / editedAt / deleted flags.
        messages.set(idx, updated);
        adapter.notifyItemChanged(idx);
    }

    // ----- Reply -----

    private void setReplyTarget(ChatMessage m) {
        activeReplyTarget = m;
        b.replyBar.setVisibility(View.VISIBLE);
        String author = m.direction == ChatMessage.Direction.OUTGOING ? "yourself" : peer;
        b.tvReplyAuthor.setText("Replying to " + author);
        b.tvReplyPreview.setText(previewOf(m));
        b.btnCancelReply.setOnClickListener(v -> clearReplyTarget());
        // Move focus to the input so the keyboard pops up.
        b.etText.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(b.etText, 0);
    }

    private void clearReplyTarget() {
        activeReplyTarget = null;
        b.replyBar.setVisibility(View.GONE);
    }

    /** Generate a short text preview of a message — used for both reply bars
     *  (locally) and reply quotes (sent with the message). */
    private static String previewOf(ChatMessage m) {
        if (m.kind == ChatMessage.Kind.IMAGE) {
            return m.text != null && !m.text.isEmpty() ? "\uD83D\uDCF7 " + m.text : "\uD83D\uDCF7 Photo";
        }
        if (m.kind == ChatMessage.Kind.VOICE) {
            int s = Math.max(1, m.durationMs / 1000);
            return String.format(java.util.Locale.US, "\uD83C\uDFA4 Voice (%d:%02d)", s / 60, s % 60);
        }
        String t = m.text == null ? "" : m.text;
        if (t.length() > 80) t = t.substring(0, 80) + "…";
        return t;
    }

    // ----- React -----

    /** The six quick reactions used by the popup picker. Same order as common
     *  messaging apps so muscle memory carries over. */
    private static final String[] QUICK_REACTIONS = { "👍", "❤️", "😂", "😮", "😢", "🙏" };

    private void showReactionPicker(ChatMessage m) {
        // Build a horizontal row of emoji buttons inside an AlertDialog.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, pad);
        row.setGravity(android.view.Gravity.CENTER);

        AlertDialog dlg = new AlertDialog.Builder(this).setView(row).create();
        String myUser = store.getUsername();
        String existing = myUser != null ? m.reactions.get(myUser) : null;
        for (String emoji : QUICK_REACTIONS) {
            TextView t = new TextView(this);
            t.setText(emoji);
            t.setTextSize(28);
            t.setPadding(pad, pad / 2, pad, pad / 2);
            // Highlight the emoji the local user has currently selected, so
            // tapping it again is clearly the "remove" action.
            if (emoji.equals(existing)) {
                t.setBackgroundColor(0x334F8DFF);
            }
            t.setOnClickListener(v -> {
                // Toggle: same emoji again removes; different emoji replaces.
                String send = emoji.equals(existing) ? "" : emoji;
                hub.sendReaction(peer, m.id, send);
                dlg.dismiss();
            });
            row.addView(t);
        }
        dlg.show();
    }

    // ----- Forward -----

    private void showForwardPicker(ChatMessage m) {
        // Build a list of forward targets: every 1-on-1 contact + every group,
        // except the current conversation itself (forwarding to where you already
        // are is just a duplicate).
        java.util.List<String[]> targets = new java.util.ArrayList<>(); // {peerId, label}
        for (Contact c : store.getContacts()) {
            if (c.username.equalsIgnoreCase(peer)) continue;
            String label = c.displayName != null && !c.displayName.isEmpty()
                    ? c.displayName + " (@" + c.username + ")"
                    : c.username;
            targets.add(new String[]{c.username, label});
        }
        for (Group g : store.getGroups()) {
            if (g.peerId().equalsIgnoreCase(peer)) continue;
            targets.add(new String[]{g.peerId(), g.name + " (" + g.members.size() + " members)"});
        }
        if (targets.isEmpty()) {
            android.widget.Toast.makeText(this,
                    "Add another contact or create a group to forward messages",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] labels = new CharSequence[targets.size()];
        for (int i = 0; i < targets.size(); i++) labels[i] = targets.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle("Forward to…")
                .setItems(labels, (d, which) -> {
                    String targetPeer = targets.get(which)[0];
                    ChatMessage fwd = hub.forwardMessage(m, targetPeer);
                    if (fwd != null) {
                        android.widget.Toast.makeText(ChatActivity.this,
                                "Forwarded",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ----- Copy text -----

    private void copyToClipboard(ChatMessage m) {
        if (m.text == null || m.text.isEmpty()) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", m.text));
            android.widget.Toast.makeText(this, "Copied",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private boolean keyRotatedWarning = false;
}
