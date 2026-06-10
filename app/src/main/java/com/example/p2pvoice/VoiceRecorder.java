package com.example.p2pvoice;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Records voice messages to a temporary file using MediaRecorder.
 *
 * Encoding: AAC in an MPEG_4 (.m4a) container, mono, 16 kHz, 32 kbps.
 * Roughly 40 KB per 10 seconds of audio. Works on every Android version
 * we support (minSdk 24).
 *
 * Lifecycle:
 *    VoiceRecorder vr = new VoiceRecorder(ctx);
 *    vr.start();    // throws on permission/codec failure
 *    ...
 *    long durationMs = vr.elapsedMs();   // poll for UI timer
 *    ...
 *    Result r = vr.stop();   // r.filePath, r.durationMs ; or null if too short
 *    vr.discard();           // alternatively, throw the file away on cancel
 */
public class VoiceRecorder {

    private static final String TAG = "VoiceRecorder";

    /** Recordings shorter than this are discarded — treated as accidental taps. */
    public static final int MIN_DURATION_MS = 800;

    private final Context ctx;
    private MediaRecorder mr;
    private File outFile;
    private long startedAtNs;
    private boolean recording;

    public static class Result {
        public final String filePath;
        public final int    durationMs;
        Result(String f, int d) { filePath = f; durationMs = d; }
    }

    public VoiceRecorder(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isRecording() { return recording; }

    /** Returns elapsed milliseconds since start(); 0 if not recording. */
    public int elapsedMs() {
        if (!recording) return 0;
        return (int) ((System.nanoTime() - startedAtNs) / 1_000_000L);
    }

    /** Begin recording to a fresh file in the app cache. Throws on failure. */
    public void start() throws IOException {
        if (recording) return;
        File dir = new File(ctx.getCacheDir(), "voice-out");
        if (!dir.exists()) dir.mkdirs();
        outFile = new File(dir, "rec-" + System.currentTimeMillis() + ".m4a");

        // MediaRecorder constructor: Context overload is API 31+, no-arg is older.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mr = new MediaRecorder(ctx);
        } else {
            mr = new MediaRecorder();
        }
        mr.setAudioSource(MediaRecorder.AudioSource.MIC);
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mr.setAudioChannels(1);
        mr.setAudioSamplingRate(16_000);
        mr.setAudioEncodingBitRate(32_000);
        mr.setOutputFile(outFile.getAbsolutePath());

        try {
            mr.prepare();
            mr.start();
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
            safeRelease();
            throw new IOException("Could not start recording: " + t.getMessage(), t);
        }
        startedAtNs = System.nanoTime();
        recording = true;
    }

    /**
     * Stop the recording and return its path + duration. Returns null if the
     * recording was too short to be meaningful (less than MIN_DURATION_MS) —
     * the file is deleted in that case.
     */
    public Result stop() {
        if (!recording) return null;
        int durationMs = elapsedMs();
        recording = false;
        try {
            mr.stop();
        } catch (Throwable t) {
            // Most common: stop() called too quickly — MediaRecorder considers
            // the recording invalid. Treat as discard.
            Log.w(TAG, "stop failed (likely too short): " + t.getMessage());
            safeRelease();
            deleteOut();
            return null;
        }
        safeRelease();

        if (durationMs < MIN_DURATION_MS) {
            deleteOut();
            return null;
        }
        if (outFile == null || !outFile.exists() || outFile.length() == 0) {
            deleteOut();
            return null;
        }
        return new Result(outFile.getAbsolutePath(), durationMs);
    }

    /** Cancel the current recording and delete its file. */
    public void discard() {
        if (recording) {
            try { mr.stop(); } catch (Throwable ignored) {}
        }
        recording = false;
        safeRelease();
        deleteOut();
    }

    private void safeRelease() {
        if (mr != null) {
            try { mr.reset(); } catch (Throwable ignored) {}
            try { mr.release(); } catch (Throwable ignored) {}
            mr = null;
        }
    }

    private void deleteOut() {
        if (outFile != null && outFile.exists()) {
            try { outFile.delete(); } catch (Throwable ignored) {}
        }
        outFile = null;
    }
}
