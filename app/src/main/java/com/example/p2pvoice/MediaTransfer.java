package com.example.p2pvoice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;

/**
 * Image upload/download pipeline. All work is encrypted with the per-conversation
 * key so the signaling server only ever sees ciphertext.
 *
 * This build uses the signaling server itself as a local media store: phones
 * POST encrypted bytes to http://<server>:<port>/media/{id} and GET them back
 * from the same path. The server treats the body as opaque bytes — no decryption,
 * no key, no metadata leakage. Files auto-expire after 7 days server-side.
 *
 * Upload flow (sender side):
 *   1. read picked image as bytes
 *   2. decode + downscale to MAX_DIM (so we don't ship a 12MP original)
 *   3. honor EXIF rotation so the recipient sees the photo right-side-up
 *   4. JPEG-encode at JPEG_QUALITY
 *   5. produce a small thumbnail (~THUMB_DIM) for inline preview
 *   6. encrypt both with the conversation key (AES-GCM)
 *   7. HTTP POST the full ciphertext to /media/{id}
 *   8. return a {downloadUrl, width, height, thumbnailB64} bundle
 *
 * Download flow (recipient side):
 *   1. HTTP GET ciphertext bytes from URL
 *   2. decrypt with the conversation key
 *   3. write decrypted JPEG to app cache at media/{id}.jpg
 *   4. return the local path
 */
public final class MediaTransfer {

    private static final String TAG = "MediaTransfer";

    private static final int  MAX_DIM        = 1600;        // resize so longest side ≤ this
    private static final int  THUMB_DIM      = 240;         // inline thumbnail longest side
    private static final int  JPEG_QUALITY   = 80;          // full-image JPEG
    private static final int  THUMB_QUALITY  = 60;          // thumbnail JPEG (more aggressive)
    private static final int  HTTP_CONN_MS   = 10_000;   // 10s to connect
    private static final int  HTTP_READ_MS   = 30_000;   // 30s for body
    private static final long MAX_DOWNLOAD   = 16L * 1024 * 1024; // 16 MB ceiling

    public static class UploadResult {
        public final String downloadUrl;
        public final int    width;
        public final int    height;
        public final String thumbnailB64;
        public final String localCachePath;  // sender's local copy of the JPEG
        UploadResult(String url, int w, int h, String tb64, String localPath) {
            downloadUrl = url; width = w; height = h;
            thumbnailB64 = tb64; localCachePath = localPath;
        }
    }

    public interface UploadCallback {
        void onSuccess(UploadResult r);
        void onFailure(Throwable t);
    }

    public interface DownloadCallback {
        void onSuccess(String localPath);
        void onFailure(Throwable t);
    }

    private MediaTransfer() {}

    // ===================================================================
    // Upload
    // ===================================================================

    /**
     * Read, resize, encrypt, and upload an image to the local signaling server.
     * Callback runs on a background thread.
     *
     * @param serverWsUrl the same ws://host:port string the SignalingClient
     *                    uses — we derive the http URL from it.
     */
    public static void uploadImage(Context ctx, Uri sourceUri, byte[] convKey,
                                   String serverWsUrl, UploadCallback cb) {
        new Thread(() -> {
            try {
                String httpBase = wsToHttpBase(serverWsUrl);
                if (httpBase == null) {
                    cb.onFailure(new RuntimeException("Bad server URL: " + serverWsUrl));
                    return;
                }

                // 1. Read raw bytes (so we can also parse EXIF if needed).
                byte[] raw = readAllBytes(ctx, sourceUri);
                if (raw == null) { cb.onFailure(new RuntimeException("image read failed")); return; }

                // 2. Decode + downscale.
                Bitmap full = decodeAndScale(raw, MAX_DIM);
                if (full == null) { cb.onFailure(new RuntimeException("decode failed")); return; }

                // 3. EXIF rotation.
                full = applyExifOrientation(raw, full);

                int width = full.getWidth();
                int height = full.getHeight();

                // 4. JPEG encode the full image.
                ByteArrayOutputStream fullStream = new ByteArrayOutputStream();
                full.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fullStream);
                byte[] fullJpeg = fullStream.toByteArray();

                // 5. Make a thumbnail from the (already small-ish) full bitmap.
                Bitmap thumb = scaleBitmap(full, THUMB_DIM);
                ByteArrayOutputStream thumbStream = new ByteArrayOutputStream();
                thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, thumbStream);
                byte[] thumbJpeg = thumbStream.toByteArray();
                if (thumb != full) thumb.recycle();
                full.recycle();

                // 6. Encrypt both.
                byte[] fullCipher  = MediaCrypto.encrypt(convKey, fullJpeg);
                byte[] thumbCipher = MediaCrypto.encrypt(convKey, thumbJpeg);
                String thumbB64    = Base64.encodeToString(thumbCipher, Base64.NO_WRAP);

                // 7. Generate a random id and POST to /media/{id}.
                String id = newMediaId();
                String url = httpBase + "/media/" + id;
                uploadBytes(url, fullCipher);
                Log.d(TAG, "uploaded " + fullCipher.length + " bytes to " + url);

                // 8. Save a local copy of the unencrypted JPEG so the sender
                // doesn't need to re-download from the server to view it.
                String localPath = null;
                try {
                    File dir = new File(ctx.getCacheDir(), "media");
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, id + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(fullJpeg);
                    }
                    localPath = out.getAbsolutePath();
                } catch (Throwable t) {
                    // Non-fatal — sender can still tap to re-download.
                    Log.w(TAG, "local cache write failed", t);
                }

                cb.onSuccess(new UploadResult(url, width, height, thumbB64, localPath));

            } catch (Throwable t) {
                Log.e(TAG, "upload failed", t);
                cb.onFailure(t);
            }
        }, "media-upload").start();
    }

    // ===================================================================
    // Download
    // ===================================================================

    /** Download ciphertext from URL, decrypt, write to app cache. */
    public static void downloadImage(Context ctx, String id, String url,
                                     byte[] convKey, DownloadCallback cb) {
        new Thread(() -> {
            try {
                Log.d(TAG, "downloading from " + url);
                byte[] cipher = downloadBytes(url);
                Log.d(TAG, "downloaded " + cipher.length + " bytes, decrypting");
                byte[] plain  = MediaCrypto.decrypt(convKey, cipher);
                if (plain == null) {
                    cb.onFailure(new RuntimeException("decrypt failed (wrong key?)"));
                    return;
                }
                File dir = new File(ctx.getCacheDir(), "media");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, id + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(plain);
                }
                Log.d(TAG, "decrypted -> " + out.getAbsolutePath());
                cb.onSuccess(out.getAbsolutePath());
            } catch (Throwable t) {
                Log.e(TAG, "download failed for " + url, t);
                // Pass through the URL host so the user can see if it looks
                // wrong (stale IP, wrong port, etc.) in the failure Toast.
                String hint = url;
                try { hint = new URL(url).getHost() + ":" + new URL(url).getPort(); } catch (Throwable ignored) {}
                cb.onFailure(new RuntimeException(t.getMessage() + " (" + hint + ")", t));
            }
        }, "media-download").start();
    }

    /** Decrypt a thumbnail Base64 into a Bitmap (or null on failure). */
    public static Bitmap decryptThumbnail(byte[] convKey, String thumbB64) {
        if (thumbB64 == null || convKey == null) return null;
        try {
            byte[] cipher = Base64.decode(thumbB64, Base64.NO_WRAP);
            byte[] plain = MediaCrypto.decrypt(convKey, cipher);
            if (plain == null) return null;
            return BitmapFactory.decodeByteArray(plain, 0, plain.length);
        } catch (Throwable t) {
            return null;
        }
    }

    // ===================================================================
    // Audio (voice messages)
    // ===================================================================

    public interface AudioUploadCallback {
        void onSuccess(String downloadUrl, String localCachePath);
        void onFailure(Throwable t);
    }

    public interface DocumentUploadCallback {
        void onSuccess(String downloadUrl, long sizeBytes);
        void onFailure(Throwable t);
    }

    /**
     * Maximum size for a single document upload, in bytes. Matches the image
     * cap and the server's MAX_MEDIA_BYTES so the upload fails fast on the
     * client instead of getting rejected at the server.
     */
    public static final int MAX_DOC_BYTES = 16 * 1024 * 1024;

    /**
     * Encrypt and upload an arbitrary file. Unlike images / audio there is
     * no transcoding here — we just send the raw bytes after encrypting them
     * with the conversation key. The recipient downloads + decrypts and
     * hands them off to the system's document viewer.
     *
     * @param sourceUri  any content:// URI the user picked
     * @param convKey    32-byte AES key shared with the peer (or group key)
     */
    public static void uploadDocument(Context ctx, Uri sourceUri, byte[] convKey,
                                      String serverWsUrl, DocumentUploadCallback cb) {
        new Thread(() -> {
            try {
                String httpBase = wsToHttpBase(serverWsUrl);
                if (httpBase == null) {
                    cb.onFailure(new RuntimeException("Bad server URL: " + serverWsUrl));
                    return;
                }
                byte[] raw;
                try (java.io.InputStream in = ctx.getContentResolver().openInputStream(sourceUri)) {
                    if (in == null) { cb.onFailure(new RuntimeException("read failed")); return; }
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_DOC_BYTES) {
                            cb.onFailure(new RuntimeException(
                                    "File too large (>" + (MAX_DOC_BYTES / (1024 * 1024)) + " MB)"));
                            return;
                        }
                        bos.write(buf, 0, n);
                    }
                    raw = bos.toByteArray();
                }
                byte[] cipher = MediaCrypto.encrypt(convKey, raw);
                String id = newMediaId();
                String url = httpBase + "/media/" + id;
                uploadBytes(url, cipher);
                cb.onSuccess(url, raw.length);
            } catch (Throwable t) {
                Log.e(TAG, "doc upload failed", t);
                cb.onFailure(t);
            }
        }, "doc-upload").start();
    }

    /**
     * Download an encrypted document, decrypt it with the supplied key, and
     * write to a file in cacheDir (under "docs/"). The caller usually then
     * hands the file path to the system viewer via a FileProvider URI.
     */
    public static void downloadDocument(Context ctx, String url, byte[] key,
                                        String suggestedFileName,
                                        DownloadCallback cb) {
        new Thread(() -> {
            try {
                byte[] cipher = downloadBytes(url);
                byte[] plain = MediaCrypto.decrypt(key, cipher);
                if (plain == null) {
                    cb.onFailure(new RuntimeException("doc decrypt failed"));
                    return;
                }
                java.io.File dir = new java.io.File(ctx.getCacheDir(), "docs");
                if (!dir.exists()) dir.mkdirs();
                // Sanitise filename — drop slashes, fall back to a random
                // name if nothing useful was supplied.
                String safeName = (suggestedFileName == null || suggestedFileName.isEmpty())
                        ? ("file-" + System.currentTimeMillis())
                        : suggestedFileName.replaceAll("[\\\\/]", "_");
                java.io.File out = new java.io.File(dir, safeName);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    fos.write(plain);
                }
                cb.onSuccess(out.getAbsolutePath());
            } catch (Throwable t) {
                Log.e(TAG, "doc download failed", t);
                cb.onFailure(t);
            }
        }, "doc-download").start();
    }

    /**
     * Encrypt an already-recorded audio file (on disk) and upload it to the
     * local server. Unlike image upload there's no resize / EXIF / thumbnail
     * step — the recorder already produced the final bytes at the right
     * bitrate, and we don't have a visual preview to show.
     *
     * @param audioFile  path to the source file (e.g. .m4a from VoiceRecorder)
     * @param convKey    32-byte AES key shared with the peer
     * @param serverWsUrl  the ws:// URL we use for signaling; we derive http:// from it
     */
    public static void uploadAudio(Context ctx, String audioFile, byte[] convKey,
                                   String serverWsUrl, AudioUploadCallback cb) {
        new Thread(() -> {
            try {
                String httpBase = wsToHttpBase(serverWsUrl);
                if (httpBase == null) {
                    cb.onFailure(new RuntimeException("Bad server URL: " + serverWsUrl));
                    return;
                }
                byte[] raw = readFileBytes(audioFile);
                if (raw == null || raw.length == 0) {
                    cb.onFailure(new RuntimeException("audio file empty or unreadable"));
                    return;
                }
                byte[] cipher = MediaCrypto.encrypt(convKey, raw);
                String id = newMediaId();
                String url = httpBase + "/media/" + id;
                uploadBytes(url, cipher);
                Log.d(TAG, "uploaded audio " + cipher.length + " bytes to " + url);

                // Cache locally so the sender can replay without a round trip.
                String localPath = null;
                try {
                    File dir = new File(ctx.getCacheDir(), "media");
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, id + ".m4a");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(raw);
                    }
                    localPath = out.getAbsolutePath();
                } catch (Throwable t) {
                    Log.w(TAG, "audio cache write failed", t);
                }

                cb.onSuccess(url, localPath);
            } catch (Throwable t) {
                Log.e(TAG, "audio upload failed", t);
                cb.onFailure(t);
            }
        }, "media-upload-audio").start();
    }

    /** Download ciphertext, decrypt, write to cache as .m4a, return local path. */
    public static void downloadAudio(Context ctx, String id, String url,
                                     byte[] convKey, DownloadCallback cb) {
        new Thread(() -> {
            try {
                Log.d(TAG, "downloading audio from " + url);
                byte[] cipher = downloadBytes(url);
                byte[] plain  = MediaCrypto.decrypt(convKey, cipher);
                if (plain == null) {
                    cb.onFailure(new RuntimeException("decrypt failed (wrong key?)"));
                    return;
                }
                File dir = new File(ctx.getCacheDir(), "media");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, id + ".m4a");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(plain);
                }
                Log.d(TAG, "decrypted audio -> " + out.getAbsolutePath());
                cb.onSuccess(out.getAbsolutePath());
            } catch (Throwable t) {
                Log.e(TAG, "audio download failed for " + url, t);
                String hint = url;
                try { hint = new URL(url).getHost() + ":" + new URL(url).getPort(); } catch (Throwable ignored) {}
                cb.onFailure(new RuntimeException(t.getMessage() + " (" + hint + ")", t));
            }
        }, "media-download-audio").start();
    }

    private static byte[] readFileBytes(String path) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "readFileBytes failed for " + path, t);
            return null;
        }
    }

    // ===================================================================
    // HTTP helpers (raw bytes; no JSON, no multipart, no auth — payload
    // is opaque ciphertext so we keep the wire format minimal).
    // ===================================================================

    static void uploadBytes(String url, byte[] data) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(HTTP_CONN_MS);
            c.setReadTimeout(HTTP_READ_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(data.length);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setRequestProperty("Content-Length", String.valueOf(data.length));
            try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
                out.write(data);
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("Upload HTTP " + code + " " + c.getResponseMessage());
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] downloadBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(HTTP_CONN_MS);
            c.setReadTimeout(HTTP_READ_MS);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("Download HTTP " + code + " " + c.getResponseMessage());
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_DOWNLOAD) {
                        throw new RuntimeException("Download exceeded 16 MB");
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * "ws://host:8080" or "wss://host:8080" -> "http://host:8080" / "https://host:8080".
     * Returns null on a non-ws URL.
     */
    static String wsToHttpBase(String wsUrl) {
        if (wsUrl == null) return null;
        if (wsUrl.startsWith("ws://"))  return "http://"  + wsUrl.substring(5);
        if (wsUrl.startsWith("wss://")) return "https://" + wsUrl.substring(6);
        // Already http? Tolerate.
        if (wsUrl.startsWith("http://") || wsUrl.startsWith("https://")) return wsUrl;
        return null;
    }

    /** 18 url-safe-base64 chars (~108 bits), matches server's accepted pattern. */
    static String newMediaId() {
        byte[] rnd = new byte[14];
        new SecureRandom().nextBytes(rnd);
        return Base64.encodeToString(rnd, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    // ===================================================================
    // Image helpers
    // ===================================================================

    private static byte[] readAllBytes(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "readAllBytes failed", t);
            return null;
        }
    }

    /** Decode with inSampleSize so the resulting bitmap is roughly maxDim on the long side. */
    private static Bitmap decodeAndScale(byte[] raw, int maxDim) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);
        int longSide = Math.max(probe.outWidth, probe.outHeight);
        int sample = 1;
        while (longSide / sample > maxDim * 2) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
        if (bmp == null) return null;
        // Final precise scale to maxDim.
        return scaleBitmap(bmp, maxDim);
    }

    private static Bitmap scaleBitmap(Bitmap src, int maxDim) {
        int w = src.getWidth(), h = src.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= maxDim) return src;
        float scale = (float) maxDim / longSide;
        int newW = Math.round(w * scale);
        int newH = Math.round(h * scale);
        return Bitmap.createScaledBitmap(src, newW, newH, true);
    }

    private static Bitmap applyExifOrientation(byte[] raw, Bitmap bmp) {
        try {
            java.io.ByteArrayInputStream bin = new java.io.ByteArrayInputStream(raw);
            ExifInterface exif = new ExifInterface(bin);
            int orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            Matrix mtx = new Matrix();
            switch (orient) {
                case ExifInterface.ORIENTATION_ROTATE_90:  mtx.postRotate(90);  break;
                case ExifInterface.ORIENTATION_ROTATE_180: mtx.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: mtx.postRotate(270); break;
                default: return bmp;
            }
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), mtx, true);
            if (rotated != bmp) bmp.recycle();
            return rotated;
        } catch (Throwable t) {
            return bmp;
        }
    }
}
