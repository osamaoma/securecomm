package com.example.p2pvoice;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

/**
 * Singleton playback for voice messages. Only one voice message plays at a
 * time — starting a new playback stops the previous one. Routes audio through
 * the media stream (speaker) so the user can hear it without holding the phone
 * to their ear.
 */
public class VoicePlayer {

    private static final String TAG = "VoicePlayer";
    private static final VoicePlayer INSTANCE = new VoicePlayer();
    public static VoicePlayer get() { return INSTANCE; }
    private VoicePlayer() {}

    public interface Listener {
        /** Called as playback progresses. positionMs is current playhead. */
        default void onProgress(int positionMs, int totalMs) {}
        /** Playback reached the end naturally. */
        default void onComplete() {}
        /** Playback was stopped (because another file started, or stop() was called). */
        default void onStopped() {}
        /** A fatal playback error. */
        default void onError(String msg) {}
    }

    private MediaPlayer mp;
    private String      currentPath;   // file being played right now
    private Listener    currentListener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (mp == null || currentListener == null) return;
            try {
                if (mp.isPlaying()) {
                    currentListener.onProgress(mp.getCurrentPosition(), mp.getDuration());
                    ui.postDelayed(this, 100);
                }
            } catch (Throwable ignored) {}
        }
    };

    /** Start playing `path`. If another file is already playing, it stops first. */
    public synchronized void play(String path, Listener listener) {
        stopInternal(true);
        currentPath = path;
        currentListener = listener;
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mp.setDataSource(path);
            mp.setOnCompletionListener(m -> {
                Listener l = currentListener;
                stopInternal(false);
                if (l != null) ui.post(l::onComplete);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                Listener l = currentListener;
                stopInternal(false);
                if (l != null) ui.post(() -> l.onError("playback error " + what + "/" + extra));
                return true;
            });
            mp.prepare();
            mp.start();
            ui.postDelayed(tick, 50);
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "play failed", e);
            stopInternal(false);
            if (listener != null) listener.onError(e.getMessage());
        }
    }

    /** Stop the current playback (if any). */
    public synchronized void stop() {
        stopInternal(true);
    }

    /** True if `path` is the file currently playing. */
    public synchronized boolean isPlaying(String path) {
        return mp != null && path != null && path.equals(currentPath) && safeIsPlaying();
    }

    private boolean safeIsPlaying() {
        try { return mp.isPlaying(); } catch (Throwable t) { return false; }
    }

    private void stopInternal(boolean notify) {
        ui.removeCallbacks(tick);
        Listener prev = currentListener;
        if (mp != null) {
            try { if (mp.isPlaying()) mp.stop(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
        currentPath = null;
        currentListener = null;
        if (notify && prev != null) ui.post(prev::onStopped);
    }
}
