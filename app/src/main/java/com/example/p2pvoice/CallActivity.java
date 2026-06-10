package com.example.p2pvoice;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.media.Ringtone;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.p2pvoice.databinding.ActivityCallBinding;

/**
 * Single screen for the whole call lifecycle:
 *  - MODE_OUTGOING: shows "Calling..." + End
 *  - MODE_INCOMING: rings + shows Accept / Decline
 *  - connected:     shows timer + Mute / Speaker / End
 */
public class CallActivity extends AppCompatActivity implements SignalingHub.UiListener {

    public static final String EXTRA_PEER = "peer";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_IS_VIDEO = "is_video";
    /** When true and MODE=INCOMING, the activity triggers acceptCall()
     *  immediately on launch. Set by the Answer button on the incoming-call
     *  notification so the user goes straight into the call without seeing
     *  the Accept/Decline screen. */
    public static final String EXTRA_AUTO_ACCEPT = "auto_accept";
    public static final String MODE_INCOMING = "incoming";
    public static final String MODE_OUTGOING = "outgoing";

    private ActivityCallBinding b;
    private SignalingHub hub;
    private AudioManager audioManager;
    private Ringtone ringtone;
    private Vibrator vibrator;

    private String peer;
    private boolean isVideo;
    private boolean muted = false;
    private boolean speakerOn = false;
    private boolean videoOn = true;
    private boolean connected = false;

    private final android.os.Handler timer = new android.os.Handler();

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        Boolean mic = result.get(Manifest.permission.RECORD_AUDIO);
                        if (mic == null || !mic) {
                            Toast.makeText(this, "Microphone permission required",
                                    Toast.LENGTH_LONG).show();
                            hub.rejectIncoming();
                            finish();
                            return;
                        }
                        // Camera is optional — if the user denies it for a video
                        // call we silently downgrade to audio-only on this side.
                        Boolean cam = result.get(Manifest.permission.CAMERA);
                        if (isVideo && (cam == null || !cam)) {
                            Toast.makeText(this, "Camera denied — joining as audio",
                                    Toast.LENGTH_LONG).show();
                            // Don't flip isVideo: the peer may still send video,
                            // we just won't send any back. The audioBlock stays
                            // visible to occlude our absent local preview.
                        }
                        doAccept();
                    });
    private long startMs;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long s = (System.currentTimeMillis() - startMs) / 1000;
            b.tvCallStatus.setText(String.format("%02d:%02d", s / 60, s % 60));
            timer.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Show over lock screen for incoming calls
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        b = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        hub = SignalingHub.get(this);

        peer = getIntent().getStringExtra(EXTRA_PEER);
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        isVideo = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);

        // Use the synced display name + avatar if we have one cached.
        Store callStore = new Store(this);
        Store.PeerProfile prof = peer != null ? callStore.getPeerProfile(peer) : null;
        String shownName = prof != null && prof.displayName != null && !prof.displayName.isEmpty()
                ? prof.displayName
                : (peer == null ? "Unknown" : peer);
        b.tvCallName.setText(shownName);

        android.graphics.Bitmap callBmp = null;
        if (prof != null && prof.avatarPath != null
                && new java.io.File(prof.avatarPath).exists()) {
            callBmp = android.graphics.BitmapFactory.decodeFile(prof.avatarPath);
        }
        if (callBmp != null) {
            // Reuse the existing TextView as a backdrop, layer an avatar over it.
            // Simplest: just hide the letter and rely on the existing bg, but we
            // don't have an ImageView in the layout. Set the bitmap as a
            // background drawable on the avatar TextView instead.
            b.tvCallAvatar.setText("");
            b.tvCallAvatar.setBackground(new android.graphics.drawable.BitmapDrawable(
                    getResources(), ProfileManager.toCircle(callBmp)));
        } else {
            b.tvCallAvatar.setText(shownName.isEmpty() ? "?"
                    : shownName.substring(0, 1).toUpperCase());
        }

        b.btnEnd.setOnClickListener(v -> { hub.hangup(); finish(); });
        b.btnReject.setOnClickListener(v -> { hub.rejectIncoming(); stopRinging(); finish(); });
        b.btnAccept.setOnClickListener(v -> acceptCall());
        b.btnMute.setOnClickListener(v -> toggleMute());
        b.btnSpeaker.setOnClickListener(v -> toggleSpeaker());
        b.btnSwitchCam.setOnClickListener(v -> hub.switchCamera());
        b.btnVideoToggle.setOnClickListener(v -> toggleVideo());

        if (isVideo) {
            // Show the video surfaces. Renderer init happens inside the hub
            // (it needs the RtcEngine's EglBase context which is created on
            // the rtc thread). Speakerphone defaults on for video calls so
            // both hands are free to hold the phone in front of the face.
            b.localVideoFrame.setVisibility(View.VISIBLE);
            b.remoteVideo.setVisibility(View.VISIBLE);
            b.btnSwitchCam.setVisibility(View.VISIBLE);
            b.btnVideoToggle.setVisibility(View.VISIBLE);
            // Audio-only avatar stays visible until the remote frame paints
            // — gives a friendly identity-anchor during connection.
            hub.attachVideoRenderers(b.localVideo, b.remoteVideo);
        }

        // Entering the call screen means the user has chosen to act on the
        // incoming call (whether through Answer, Decline, or tapping the
        // notification body). Dismiss the heads-up notification so it
        // doesn't linger or keep ringing.
        IncomingCallNotifier.cancel(this);

        if (MODE_INCOMING.equals(mode)) {
            showIncoming();
            // Notification's Answer button forwards EXTRA_AUTO_ACCEPT; trigger
            // the accept flow immediately so the user lands directly in the
            // connected call.
            if (getIntent().getBooleanExtra(EXTRA_AUTO_ACCEPT, false)) {
                b.btnAccept.post(this::acceptCall);
            }
        } else {
            showOutgoing();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.setUiListener(this);
        if (connected && !hub.isInCall()) finish();
    }

    private void showIncoming() {
        b.tvCallStatus.setText("Incoming call");
        b.incomingButtons.setVisibility(View.VISIBLE);
        b.btnEnd.setVisibility(View.GONE);
        b.activeControls.setVisibility(View.GONE);
        startRinging();
    }

    private void showOutgoing() {
        b.tvCallStatus.setText("Calling...");
        b.incomingButtons.setVisibility(View.GONE);
        b.btnEnd.setVisibility(View.VISIBLE);
        b.activeControls.setVisibility(View.GONE);
    }

    private void acceptCall() {
        // Decide which permissions we need. Audio is always required;
        // camera only for video calls. Asking for both at once means one
        // permission sheet rather than two consecutive ones.
        boolean haveMic = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean haveCam = !isVideo || ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (haveMic && haveCam) {
            doAccept();
            return;
        }
        java.util.List<String> req = new java.util.ArrayList<>();
        req.add(Manifest.permission.RECORD_AUDIO);
        req.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (isVideo) req.add(Manifest.permission.CAMERA);
        permLauncher.launch(req.toArray(new String[0]));
    }

    private void doAccept() {
        stopRinging();
        b.tvCallStatus.setText("Connecting...");
        b.incomingButtons.setVisibility(View.GONE);
        b.btnEnd.setVisibility(View.VISIBLE);
        hub.acceptIncoming();
    }

    private android.media.AudioFocusRequest audioFocusRequest;

    private void requestAudioFocus() {
        // Request voice-call audio focus so the OS doesn't duck or mute our
        // playback. Without this, on some devices the remote audio plays at
        // zero volume even though the AudioTrack is active.
        try {
            android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest = new android.media.AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(false)
                        .build();
                audioManager.requestAudioFocus(audioFocusRequest);
            } else {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Throwable ignored) {}
    }

    private void abandonAudioFocus() {
        try {
            if (audioFocusRequest != null
                    && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                audioFocusRequest = null;
            }
        } catch (Throwable ignored) {}
    }

    private void showActive() {
        connected = true;
        stopRinging();
        b.incomingButtons.setVisibility(View.GONE);
        b.btnEnd.setVisibility(View.VISIBLE);
        b.activeControls.setVisibility(View.VISIBLE);
        startMs = System.currentTimeMillis();
        timer.post(tick);
        // Audio routing for two-way voice:
        //   1. MODE_IN_COMMUNICATION turns on the voice call path and enables AEC.
        //   2. Request audio focus so the OS doesn't mute our playback.
        //   3. Speaker ON by default — much harder to miss than the earpiece.
        //   4. Bump VOICE_CALL stream volume to max in case the user lowered it.
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        requestAudioFocus();
        setSpeaker(true);
        try {
            int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0);
        } catch (Throwable ignored) {}
        // Video calls: collapse the audio-only avatar block now that the
        // remote feed has a connection. The renderer fades in when the
        // first remote frame paints; until then the screen is black, which
        // is the same indication every other video-call app gives.
        if (isVideo) {
            b.audioBlock.setVisibility(View.GONE);
        }
    }

    private void toggleMute() {
        muted = !muted;
        hub.setMuted(muted);
        b.btnMute.setText(muted ? "Unmute" : "Mute");
    }

    private void toggleSpeaker() { setSpeaker(!speakerOn); }

    private void setSpeaker(boolean on) {
        speakerOn = on;
        audioManager.setSpeakerphoneOn(on);
        b.btnSpeaker.setText(on ? "Speaker On" : "Speaker");
    }

    /** Pause/resume sending our local camera. Doesn't affect the incoming
     *  remote video — that keeps playing. */
    private void toggleVideo() {
        videoOn = !videoOn;
        hub.setVideoEnabled(videoOn);
        b.btnVideoToggle.setText(videoOn ? "Video" : "Video Off");
        // Hide the local preview when paused — otherwise the user sees a
        // frozen frame from the last captured moment.
        b.localVideoFrame.setVisibility(videoOn ? View.VISIBLE : View.GONE);
    }

    // ---- ringtone + vibration for incoming ----
    private void startRinging() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(this, uri);
            if (ringtone != null) ringtone.play();
        } catch (Exception ignored) {}
        try {
            if (vibrator != null) {
                long[] pattern = {0, 800, 1000};
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception ignored) {}
    }

    private void stopRinging() {
        if (ringtone != null && ringtone.isPlaying()) ringtone.stop();
        if (vibrator != null) vibrator.cancel();
    }

    // ---- SignalingHub.UiListener ----
    @Override public void onConnectionState(String state) {
        // Show ICE progress / errors until the call is fully connected.
        if (!connected && state != null && state.startsWith("ICE:")) {
            runOnUiThread(() -> b.tvCallStatus.setText(state));
        }
    }
    @Override public void onRegistered(String username) {}
    @Override public void onRegisterError(String reason) {}
    @Override public void onIncomingCall(String from, String callId, boolean isVideo) {}
    @Override public void onOutgoingRinging(String to) {
        b.tvCallStatus.setText("Ringing...");
    }
    @Override public void onCallConnected(String peer) {
        runOnUiThread(this::showActive);
    }
    @Override public void onCallEnded(String reason) {
        runOnUiThread(() -> {
            stopRinging();
            String label = "rejected".equals(reason) ? "Call declined"
                    : "offline".equals(reason) ? "User is offline"
                    : "Call ended";
            b.tvCallStatus.setText(label);
            b.btnEnd.postDelayed(this::finish, 1200);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRinging();
        timer.removeCallbacks(tick);
        abandonAudioFocus();
        try {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(false);
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        // Don't accidentally drop the call; require explicit End.
        if (connected) {
            super.onBackPressed();
        } else {
            // outgoing/incoming: treat back as not ending unless it's outgoing
            super.onBackPressed();
        }
    }
}
