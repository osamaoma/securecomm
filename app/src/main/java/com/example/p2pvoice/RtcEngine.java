package com.example.p2pvoice;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.FrameCryptor;
import org.webrtc.FrameCryptorAlgorithm;
import org.webrtc.FrameCryptorFactory;
import org.webrtc.FrameCryptorKeyProvider;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps libwebrtc. Responsible for:
 *  - Audio capture with hardware/software AEC + Noise Suppression + AGC
 *  - PeerConnection lifecycle and SDP negotiation
 *  - End-to-End Encryption of media frames via FrameCryptor (AES-GCM),
 *    so frames are encrypted independently of the mandatory DTLS-SRTP
 *    transport encryption.
 */
public class RtcEngine {

    private static final String TAG = "RtcEngine";

    public interface Events {
        void onLocalDescription(SessionDescription sdp);
        void onIceCandidate(IceCandidate candidate);
        void onConnectionState(PeerConnection.PeerConnectionState state);
        void onIceState(PeerConnection.IceConnectionState state);
        /** Optional: a remote video track has been attached. The activity
         *  should sink it into its remote SurfaceViewRenderer. Default no-op
         *  so audio-only callers don't need to override. */
        default void onRemoteVideoTrack(VideoTrack track) {}
    }

    private final Context appContext;
    private final Events events;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private AudioDeviceModule adm;
    private EglBase eglBase;

    // ---------------- Video state ----------------
    // Lazy: created only when this is a video call. Audio-only calls leave
    // these null so the audio-only happy path is unchanged.
    private VideoCapturer videoCapturer;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private SurfaceTextureHelper captureHelper;
    private boolean usingFrontCamera = true;
    /** Set to true when {@link #createPeerConnection} was asked to enable
     *  video. Drives both the offer/answer constraints and whether we add a
     *  local video track. */
    private boolean videoCallEnabled = false;
    /** Cached reference so renderer attachments arriving late can still hook
     *  up to the right surfaces. */
    private SurfaceViewRenderer localRenderer;
    private SurfaceViewRenderer remoteRenderer;
    /** Saved off in onAddTrack so a renderer attached AFTER the remote SDP
     *  applies can still get the video sunk into it. */
    private VideoTrack pendingRemoteVideo;

    private FrameCryptorKeyProvider keyProvider;
    private final List<FrameCryptor> frameCryptors = new ArrayList<>();
    private final byte[] e2eeKey;

    public RtcEngine(Context context, byte[] e2eeKey, Events events) {
        this.appContext = context.getApplicationContext();
        this.e2eeKey = e2eeKey;
        this.events = events;
        initFactory();
    }

    // ---------------------------------------------------------------
    // Factory + Audio Device Module (AEC / NS / AGC configured here)
    // ---------------------------------------------------------------
    private static boolean sGlobalInit = false;

    private static synchronized void initGlobalOnce(Context appContext) {
        if (sGlobalInit) return;
        // PeerConnectionFactory.initialize must run exactly once per process.
        // Calling it again on a later call can crash, so guard it statically.
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                        .builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions());
        sGlobalInit = true;
    }

    private void initFactory() {
        initGlobalOnce(appContext);

        eglBase = EglBase.create();

        // The JavaAudioDeviceModule enables WebRTC's software audio processing
        // and the platform's hardware acoustic echo canceler / noise suppressor
        // when available, falling back to WebRTC's AEC3 + RNNoise otherwise.
        adm = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)   // AEC (hardware)
                .setUseHardwareNoiseSuppressor(true)        // NS  (hardware)
                .createAudioDeviceModule();

        DefaultVideoEncoderFactory encoderFactory =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory =
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        // Build the E2EE key provider once (shared key, ratcheted internally).
        // Set to false to disable the per-frame E2EE layer entirely; the call still
        // runs encrypted under WebRTC's mandatory DTLS-SRTP. Useful if FrameCryptor
        // ever misbehaves on a particular device or library version.
        if (E2EE_ENABLED) {
            keyProvider = createKeyProvider(e2eeKey);
        } else {
            Log.w(TAG, "E2EE disabled by flag; DTLS-SRTP transport encryption still active");
            keyProvider = null;
        }
    }

    /** Master switch for the FrameCryptor E2EE layer. */
    public static final boolean E2EE_ENABLED = true;

    /** Force all media through the TURN relay. Set to true ONLY if direct P2P
     *  consistently fails (one-way audio symptoms across asymmetric NAT). Off
     *  by default so calls try direct UDP first, which works on the same Wi-Fi
     *  and many home networks. */
    public static final boolean FORCE_RELAY = false;

    private FrameCryptorKeyProvider createKeyProvider(byte[] key) {
        // sharedKey = true: both peers use the same symmetric key.
        // Wrapped so a native/version mismatch logs instead of crashing the app;
        // if E2EE can't initialize, the call still runs under mandatory DTLS-SRTP.
        try {
            byte[] salt = "p2pvoice-e2ee-ratchet-salt".getBytes(StandardCharsets.UTF_8);
            FrameCryptorKeyProvider provider = FrameCryptorFactory.createFrameCryptorKeyProvider(
                    /* sharedKey */ true,
                    /* ratchetSalt */ salt,
                    /* ratchetWindowSize */ 16,
                    /* uncryptedMagicBytes */ new byte[0],
                    /* failureTolerance */ -1,
                    /* keyRingSize */ 16,
                    /* discardFrameWhenCryptorNotReady */ false);
            provider.setSharedKey(0, key);
            Log.d(TAG, "E2EE key provider created");
            return provider;
        } catch (Throwable t) {
            // Catches UnsatisfiedLinkError (version mismatch) and any other failure.
            Log.e(TAG, "E2EE unavailable, continuing with DTLS-SRTP only: " + t, t);
            return null;
        }
    }

    // ---------------------------------------------------------------
    // PeerConnection setup
    // ---------------------------------------------------------------
    public void createPeerConnection(List<PeerConnection.IceServer> iceServers) {
        createPeerConnection(iceServers, false);
    }

    /** Overload that lets the caller request video media on top of audio. */
    public void createPeerConnection(List<PeerConnection.IceServer> iceServers, boolean enableVideo) {
        this.videoCallEnabled = enableVideo;
        PeerConnection.RTCConfiguration config =
                new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        // Note: DTLS-SRTP is always enabled and mandatory in modern libwebrtc;
        // there is no longer an enableDtlsSrtp flag to set.

        // FORCE_RELAY: route media through the TURN server instead of attempting
        // direct P2P first. Slower (extra hop) but the same path is used in both
        // directions, which fixes "one-way audio" symptoms that happen when
        // P2P works one way but the return path is blocked by the other peer's NAT.
        // Set to false once you've deployed a reliable TURN server and want P2P
        // for lower latency.
        if (FORCE_RELAY) {
            config.iceTransportsType = PeerConnection.IceTransportsType.RELAY;
        }

        peerConnection = factory.createPeerConnection(config, new PeerObserver());

        addLocalAudio();
        if (enableVideo) {
            addLocalVideo();
        }
    }

    /** Returns the EglBase context used by the local video pipeline. Activities
     *  must use this same context when initialising their SurfaceViewRenderers,
     *  otherwise frames won't render. */
    public EglBase.Context getEglBaseContext() {
        return eglBase != null ? eglBase.getEglBaseContext() : null;
    }

    /** Whether this engine was created with a local video track. */
    public boolean isVideoEnabled() { return videoCallEnabled; }

    /** Attach the activity's local-preview SurfaceViewRenderer. Safe to call
     *  before or after createPeerConnection — the engine remembers the
     *  renderer and binds it once the local track exists.
     *
     *  Implementation note: {@link SurfaceViewRenderer#init} asserts it's
     *  being called from the UI thread (via WebRTC's ThreadUtils), but
     *  this method itself is invoked from the rtc executor thread. We
     *  therefore post the init + addSink work onto the main looper and
     *  let it happen there. */
    public void attachLocalRenderer(SurfaceViewRenderer renderer) {
        this.localRenderer = renderer;
        if (renderer == null) return;
        final EglBase.Context eglCtx = eglBase != null ? eglBase.getEglBaseContext() : null;
        final boolean mirror = usingFrontCamera;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                renderer.init(eglCtx, null);
                renderer.setMirror(mirror);
                renderer.setEnableHardwareScaler(true);
            } catch (Throwable t) {
                Log.e(TAG, "local renderer init failed", t);
                return;
            }
            if (localVideoTrack != null) {
                try { localVideoTrack.addSink(renderer); }
                catch (Throwable t) { Log.e(TAG, "local sink failed", t); }
            }
        });
    }

    /** Attach the activity's remote-feed SurfaceViewRenderer. Same async
     *  semantics as attachLocalRenderer — if the remote track has already
     *  arrived (pendingRemoteVideo non-null) we sink it now; otherwise we
     *  remember the renderer and sink it from PeerObserver.onAddTrack. */
    public void attachRemoteRenderer(SurfaceViewRenderer renderer) {
        this.remoteRenderer = renderer;
        if (renderer == null) return;
        final EglBase.Context eglCtx = eglBase != null ? eglBase.getEglBaseContext() : null;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                renderer.init(eglCtx, null);
                renderer.setEnableHardwareScaler(true);
            } catch (Throwable t) {
                Log.e(TAG, "remote renderer init failed", t);
                return;
            }
            if (pendingRemoteVideo != null) {
                try { pendingRemoteVideo.addSink(renderer); }
                catch (Throwable t) { Log.e(TAG, "remote sink failed", t); }
            }
        });
    }

    /** Flip between front and back camera. No-op for audio-only calls. */
    public void switchCamera() {
        if (videoCapturer instanceof CameraVideoCapturer) {
            ((CameraVideoCapturer) videoCapturer).switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                @Override public void onCameraSwitchDone(boolean isFront) {
                    usingFrontCamera = isFront;
                    if (localRenderer != null) {
                        try { localRenderer.setMirror(isFront); } catch (Throwable ignored) {}
                    }
                }
                @Override public void onCameraSwitchError(String err) {
                    Log.w(TAG, "switchCamera failed: " + err);
                }
            });
        }
    }

    /** Pause / resume the local camera capture without tearing down the
     *  PeerConnection. When paused, the peer sees a frozen frame (or black). */
    public void setVideoEnabled(boolean enabled) {
        if (localVideoTrack != null) localVideoTrack.setEnabled(enabled);
        if (videoCapturer != null) {
            try {
                if (enabled) videoCapturer.startCapture(640, 480, 24);
                else videoCapturer.stopCapture();
            } catch (Throwable t) {
                Log.w(TAG, "setVideoEnabled toggle failed: " + t);
            }
        }
    }

    private void addLocalVideo() {
        try {
            videoCapturer = createCameraCapturer(true);
            if (videoCapturer == null) {
                Log.e(TAG, "No camera available; falling back to audio-only");
                videoCallEnabled = false;
                return;
            }
            captureHelper = SurfaceTextureHelper.create("VideoCaptureThread",
                    eglBase.getEglBaseContext());
            videoSource = factory.createVideoSource(false);
            videoCapturer.initialize(captureHelper, appContext,
                    videoSource.getCapturerObserver());
            // 640x480 @ 24fps strikes a reasonable balance between bandwidth
            // and quality on mid-range devices. Higher settings can be added
            // as a quality preference later.
            videoCapturer.startCapture(640, 480, 24);

            localVideoTrack = factory.createVideoTrack("video0", videoSource);
            localVideoTrack.setEnabled(true);
            // Attach to the local preview if the activity already gave us one.
            if (localRenderer != null) {
                try { localVideoTrack.addSink(localRenderer); }
                catch (Throwable t) { Log.e(TAG, "local sink delayed-attach failed", t); }
            }

            List<String> streamIds = new ArrayList<>();
            streamIds.add("stream0");
            RtpSender sender = peerConnection.addTrack(localVideoTrack, streamIds);

            // E2EE wrap on outgoing video frames, mirroring the audio path.
            enableSenderEncryption(sender, "video");
        } catch (Throwable t) {
            Log.e(TAG, "addLocalVideo failed", t);
            videoCallEnabled = false;
        }
    }

    /** Choose a working camera. Prefer the requested facing; fall back to
     *  whatever's available if it isn't. */
    private VideoCapturer createCameraCapturer(boolean front) {
        CameraEnumerator enumerator = new Camera2Enumerator(appContext);
        String[] names = enumerator.getDeviceNames();
        // First pass: matching orientation.
        for (String name : names) {
            if (enumerator.isFrontFacing(name) == front) {
                VideoCapturer c = enumerator.createCapturer(name, null);
                if (c != null) {
                    usingFrontCamera = front;
                    return c;
                }
            }
        }
        // Fallback: any camera.
        for (String name : names) {
            VideoCapturer c = enumerator.createCapturer(name, null);
            if (c != null) {
                usingFrontCamera = enumerator.isFrontFacing(name);
                return c;
            }
        }
        return null;
    }

    private void addLocalAudio() {
        MediaConstraints audioConstraints = new MediaConstraints();
        // Software audio processing constraints (belt-and-suspenders with the ADM).
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));  // AEC
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));  // NS
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));   // AGC
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googEchoCancellation2", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"));

        audioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);

        List<String> streamIds = new ArrayList<>();
        streamIds.add("stream0");
        RtpSender sender = peerConnection.addTrack(localAudioTrack, streamIds);

        // Cap the audio bitrate at 24 kbps via the RtpSender parameters. This is
        // belt-and-suspenders alongside the SDP fmtp settings. A capped bitrate
        // gives WebRTC headroom on weak networks so brief congestion doesn't
        // immediately translate into audio artifacts.
        try {
            org.webrtc.RtpParameters params = sender.getParameters();
            if (params != null && params.encodings != null) {
                for (org.webrtc.RtpParameters.Encoding e : params.encodings) {
                    e.maxBitrateBps = 24000;
                }
                sender.setParameters(params);
            }
        } catch (Throwable t) {
            Log.w(TAG, "couldn't cap sender bitrate: " + t);
        }

        // Attach E2EE encryptor to the outgoing audio sender.
        enableSenderEncryption(sender, "audio");
    }

    private void enableSenderEncryption(RtpSender sender, String label) {
        if (keyProvider == null) return; // E2EE unavailable; DTLS-SRTP still protects transport
        try {
            FrameCryptor cryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(
                    factory,
                    sender,
                    label,
                    FrameCryptorAlgorithm.AES_GCM,
                    keyProvider);
            cryptor.setEnabled(true);
            cryptor.setKeyIndex(0);
            frameCryptors.add(cryptor);
            Log.d(TAG, "E2EE encryptor enabled on " + label + " sender");
        } catch (Throwable t) {
            Log.e(TAG, "sender E2EE failed for " + label + ", continuing without it: " + t, t);
        }
    }

    private void enableReceiverDecryption(RtpReceiver receiver, String label) {
        if (keyProvider == null) return;
        try {
            FrameCryptor cryptor = FrameCryptorFactory.createFrameCryptorForRtpReceiver(
                    factory,
                    receiver,
                    label,
                    FrameCryptorAlgorithm.AES_GCM,
                    keyProvider);
            cryptor.setEnabled(true);
            cryptor.setKeyIndex(0);
            frameCryptors.add(cryptor);
            Log.d(TAG, "E2EE decryptor enabled on " + label + " receiver");
        } catch (Throwable t) {
            Log.e(TAG, "receiver E2EE failed for " + label + ", continuing without it: " + t, t);
        }
    }

    // ---------------------------------------------------------------
    // SDP
    // ---------------------------------------------------------------
    public void createOffer() {
        MediaConstraints constraints = offerAnswerConstraints();
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                SessionDescription tuned = tuneAudioSdp(sdp);
                peerConnection.setLocalDescription(new SimpleSdpObserver(), tuned);
                events.onLocalDescription(tuned);
            }
        }, constraints);
    }

    public void createAnswer() {
        MediaConstraints constraints = offerAnswerConstraints();
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                SessionDescription tuned = tuneAudioSdp(sdp);
                peerConnection.setLocalDescription(new SimpleSdpObserver(), tuned);
                events.onLocalDescription(tuned);
            }
        }, constraints);
    }

    /**
     * Patches the Opus codec parameters in an SDP for better audio resilience:
     *  - useinbandfec=1   Forward Error Correction. The most important setting for
     *                    flaky networks: lets the receiver reconstruct a lost
     *                    packet from data piggy-backed on the following packet.
     *  - usedtx=1         Discontinuous Transmission. Stops sending packets during
     *                    silence, saving bandwidth and reducing pressure on the
     *                    network during conversation pauses.
     *  - stereo=0         Mono. Voice doesn't need stereo and mono halves the bitrate.
     *  - maxaveragebitrate=24000  Cap at 24 kbps. Opus speech is excellent at this
     *                    rate and it leaves headroom for bursts on weak networks.
     *  - b=AS:24          SDP bandwidth attribute - tells the network layer the cap.
     */
    private SessionDescription tuneAudioSdp(SessionDescription sdp) {
        try {
            String text = sdp.description;
            int opusPt = findOpusPayloadType(text);
            if (opusPt < 0) return sdp; // no Opus, nothing to do

            // Inject or replace the fmtp line for the Opus payload type.
            String fmtpParams = "minptime=10;useinbandfec=1;usedtx=1;stereo=0;maxaveragebitrate=24000";
            String fmtpLine = "a=fmtp:" + opusPt + " " + fmtpParams;

            java.util.regex.Pattern existing = java.util.regex.Pattern.compile(
                    "(?m)^a=fmtp:" + opusPt + " .*$");
            if (existing.matcher(text).find()) {
                text = existing.matcher(text).replaceFirst(java.util.regex.Matcher.quoteReplacement(fmtpLine));
            } else {
                // Insert after the matching rtpmap line
                String rtpmapPrefix = "a=rtpmap:" + opusPt + " ";
                int rtpmapIdx = text.indexOf(rtpmapPrefix);
                if (rtpmapIdx >= 0) {
                    int eol = text.indexOf('\n', rtpmapIdx);
                    if (eol >= 0) {
                        text = text.substring(0, eol + 1) + fmtpLine + "\r\n" + text.substring(eol + 1);
                    }
                }
            }

            // Add a session-level bandwidth cap on the audio m-line (b=AS:24 kbps).
            text = text.replaceAll("(?m)^(m=audio .*)$", "$1\r\nb=AS:24");

            return new SessionDescription(sdp.type, text);
        } catch (Throwable t) {
            Log.e(TAG, "SDP tune failed, using original", t);
            return sdp;
        }
    }

    private int findOpusPayloadType(String sdp) {
        // a=rtpmap:111 opus/48000/2
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^a=rtpmap:(\\d+)\\s+opus/", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(sdp);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private MediaConstraints offerAnswerConstraints() {
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                videoCallEnabled ? "true" : "false"));
        return c;
    }

    public void setRemoteDescription(SessionDescription sdp) {
        peerConnection.setRemoteDescription(new SimpleSdpObserver(), sdp);
    }

    public void addIceCandidate(IceCandidate candidate) {
        if (peerConnection != null) peerConnection.addIceCandidate(candidate);
    }

    // ---------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------
    public void setMicEnabled(boolean enabled) {
        if (localAudioTrack != null) localAudioTrack.setEnabled(enabled);
    }

    public void dispose() {
        for (FrameCryptor fc : frameCryptors) {
            try { fc.dispose(); } catch (Exception ignored) {}
        }
        frameCryptors.clear();
        if (videoCapturer != null) {
            try { videoCapturer.stopCapture(); } catch (Throwable ignored) {}
            try { videoCapturer.dispose(); } catch (Throwable ignored) {}
            videoCapturer = null;
        }
        if (captureHelper != null) {
            try { captureHelper.dispose(); } catch (Throwable ignored) {}
            captureHelper = null;
        }
        if (videoSource != null) { try { videoSource.dispose(); } catch (Throwable ignored) {} videoSource = null; }
        // Release renderers on the UI thread — they assert main-thread in
        // their teardown the same way init does.
        final SurfaceViewRenderer lr = localRenderer;
        final SurfaceViewRenderer rr = remoteRenderer;
        localRenderer = null;
        remoteRenderer = null;
        if (lr != null || rr != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (lr != null) { try { lr.release(); } catch (Throwable ignored) {} }
                if (rr != null) { try { rr.release(); } catch (Throwable ignored) {} }
            });
        }
        localVideoTrack = null;
        pendingRemoteVideo = null;
        if (peerConnection != null) { peerConnection.dispose(); peerConnection = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        if (adm != null) { adm.release(); adm = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        if (eglBase != null) { eglBase.release(); eglBase = null; }
    }

    // ---------------------------------------------------------------
    // Observer
    // ---------------------------------------------------------------
    private class PeerObserver implements PeerConnection.Observer {
        @Override public void onIceCandidate(IceCandidate candidate) {
            // Log the candidate type so we can see in logcat which network paths
            // are being discovered: host=local, srflx=STUN/public, relay=TURN.
            // If only host candidates appear, neither STUN nor TURN is reachable.
            String sdp = candidate.sdp == null ? "" : candidate.sdp;
            String type = "unknown";
            int i = sdp.indexOf("typ ");
            if (i >= 0) {
                int j = sdp.indexOf(' ', i + 4);
                type = j > 0 ? sdp.substring(i + 4, j) : sdp.substring(i + 4);
            }
            Log.d(TAG, "ICE candidate: " + type);
            events.onIceCandidate(candidate);
        }
        @Override public void onAddTrack(RtpReceiver receiver, org.webrtc.MediaStream[] streams) {
            MediaStreamTrack track = receiver.track();
            if (track instanceof AudioTrack) {
                AudioTrack at = (AudioTrack) track;
                at.setEnabled(true);
                // Pin the playback volume explicitly. On some Android devices the
                // remote AudioTrack starts at volume 0 which produces the exact
                // symptom of "the call is connected but I hear nothing".
                try { at.setVolume(10.0); } catch (Throwable ignored) {}
                Log.d(TAG, "remote audio track attached, volume pinned");
                enableReceiverDecryption(receiver, "audio");
            } else if (track instanceof VideoTrack) {
                VideoTrack vt = (VideoTrack) track;
                vt.setEnabled(true);
                pendingRemoteVideo = vt;
                Log.d(TAG, "remote video track attached");
                if (remoteRenderer != null) {
                    try { vt.addSink(remoteRenderer); }
                    catch (Throwable t) { Log.e(TAG, "remote video sink failed", t); }
                }
                enableReceiverDecryption(receiver, "video");
                try { events.onRemoteVideoTrack(vt); }
                catch (Throwable ignored) {}
            }
        }
        @Override public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
            events.onConnectionState(newState);
        }
        @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
            Log.d(TAG, "ICE state: " + s);
            events.onIceState(s);
        }
        @Override public void onIceConnectionReceivingChange(boolean b) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
        @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
        @Override public void onAddStream(org.webrtc.MediaStream s) {}
        @Override public void onRemoveStream(org.webrtc.MediaStream s) {}
        @Override public void onDataChannel(org.webrtc.DataChannel d) {}
        @Override public void onRenegotiationNeeded() {}
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) { Log.e(TAG, "SDP create fail: " + s); }
        @Override public void onSetFailure(String s) { Log.e(TAG, "SDP set fail: " + s); }
    }

    // ---------------------------------------------------------------
    // Helper: derive a 256-bit key from a passphrase (SHA-256).
    // In production prefer an authenticated ECDH exchange + SAS verification.
    // ---------------------------------------------------------------
    public static byte[] deriveKey(String passphrase) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(passphrase.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
