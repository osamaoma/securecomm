package com.example.p2pvoice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;

/**
 * Handles avatar bytes for the user's own profile and for incoming peer
 * profiles. The cryptographic model is:
 *
 *   - Avatar bytes are encrypted with a random per-upload AES-GCM-256 key
 *     and uploaded to /media/{id} on the signaling server.
 *   - The key is small (~32 bytes); it travels per-peer via profile_msg,
 *     wrapped with each peer's conversation key.
 *   - The server only ever sees opaque ciphertext.
 *
 * This is cheaper than re-encrypting and re-uploading the image bytes once
 * per peer, and avoids broadcasting a long-lived "self key" that compromise
 * of one contact would leak.
 *
 * Avatar image format: square JPEG, max 256px, q=85 — typically 8-20 KB.
 */
public final class ProfileManager {

    private static final String TAG = "ProfileManager";

    public static final int AVATAR_DIM = 256;
    public static final int AVATAR_QUALITY = 85;

    public interface UploadCallback {
        /** localPath = decrypted JPEG cached on this device (for self-display).
         *  url = HTTP URL of the encrypted blob.
         *  keyB64 = Base64 of the AES key used for the blob.        */
        void onSuccess(String localPath, String url, String keyB64);
        void onFailure(Throwable t);
    }

    public interface DownloadCallback {
        void onSuccess(String localPath);
        void onFailure(Throwable t);
    }

    private ProfileManager() {}

    // ====================================================================
    // Upload (my own avatar)
    // ====================================================================

    /**
     * Read, downscale to a square, encrypt with a fresh random key, and
     * upload to the local server. Caches the unencrypted JPEG under
     * cacheDir/profile/me-<ts>.jpg so we can display it without redownloading.
     */
    public static void uploadOwnAvatar(Context ctx, Uri source, String serverWsUrl,
                                       UploadCallback cb) {
        new Thread(() -> {
            try {
                String httpBase = MediaTransfer.wsToHttpBase(serverWsUrl);
                if (httpBase == null) {
                    cb.onFailure(new RuntimeException("Bad server URL: " + serverWsUrl));
                    return;
                }
                byte[] raw = readAllBytes(ctx, source);
                if (raw == null) { cb.onFailure(new RuntimeException("read failed")); return; }

                Bitmap bmp = decodeSquare(raw, AVATAR_DIM);
                if (bmp == null) { cb.onFailure(new RuntimeException("decode failed")); return; }
                bmp = applyExifOrientation(raw, bmp);
                bmp = centerSquare(bmp, AVATAR_DIM);

                ByteArrayOutputStream jpegStream = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, AVATAR_QUALITY, jpegStream);
                byte[] jpeg = jpegStream.toByteArray();
                bmp.recycle();

                // Cache locally so the user can see their own avatar immediately.
                File dir = new File(ctx.getCacheDir(), "profile");
                if (!dir.exists()) dir.mkdirs();
                File local = new File(dir, "me-" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(local)) {
                    fos.write(jpeg);
                }

                // Fresh random AES key for this upload.
                byte[] key = new byte[32];
                new SecureRandom().nextBytes(key);
                byte[] cipher = MediaCrypto.encrypt(key, jpeg);

                // Upload to /media/{id} reusing the existing endpoint.
                String id = MediaTransfer.newMediaId();
                String url = httpBase + "/media/" + id;
                MediaTransfer.uploadBytes(url, cipher);

                String keyB64 = Base64.encodeToString(key, Base64.NO_WRAP);
                Log.d(TAG, "avatar uploaded " + cipher.length + "b to " + url);
                cb.onSuccess(local.getAbsolutePath(), url, keyB64);
            } catch (Throwable t) {
                Log.e(TAG, "avatar upload failed", t);
                cb.onFailure(t);
            }
        }, "avatar-upload").start();
    }

    // ====================================================================
    // Download (peer avatar)
    // ====================================================================

    /**
     * Fetch a peer's encrypted avatar, decrypt with the supplied key, and
     * cache locally. Idempotent: if a fresh local copy already exists for
     * this URL+key pair we still re-fetch (caller decides based on version).
     */
    public static void downloadPeerAvatar(Context ctx, String peer, String url,
                                          String keyB64, DownloadCallback cb) {
        new Thread(() -> {
            try {
                byte[] cipher = MediaTransfer.downloadBytes(url);
                byte[] key = Base64.decode(keyB64, Base64.NO_WRAP);
                byte[] plain = MediaCrypto.decrypt(key, cipher);
                if (plain == null) {
                    cb.onFailure(new RuntimeException("avatar decrypt failed"));
                    return;
                }
                File dir = new File(ctx.getCacheDir(), "profile");
                if (!dir.exists()) dir.mkdirs();
                // We use one file per peer rather than per-version because
                // the old one becomes stale and we'd otherwise leak disk.
                File local = new File(dir, "peer-" + peer.toLowerCase() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(local)) {
                    fos.write(plain);
                }
                Log.d(TAG, "avatar from " + peer + " cached -> " + local);
                cb.onSuccess(local.getAbsolutePath());
            } catch (Throwable t) {
                Log.e(TAG, "avatar download failed for " + peer, t);
                cb.onFailure(t);
            }
        }, "avatar-download").start();
    }

    // ====================================================================
    // Bitmap helpers
    // ====================================================================

    /** Make a circular bitmap, useful for inline avatar UI. */
    public static Bitmap toCircle(Bitmap src) {
        if (src == null) return null;
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Draw an alpha circle.
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        // Then composite the (centered-cropped) source through the alpha mask.
        Rect src2 = new Rect(
                (src.getWidth() - size) / 2,
                (src.getHeight() - size) / 2,
                (src.getWidth() - size) / 2 + size,
                (src.getHeight() - size) / 2 + size);
        RectF dst = new RectF(0, 0, size, size);
        c.drawBitmap(src, src2, dst, p);
        return out;
    }

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

    private static Bitmap decodeSquare(byte[] raw, int maxDim) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);
        int longSide = Math.max(probe.outWidth, probe.outHeight);
        int sample = 1;
        while (longSide / sample > maxDim * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
    }

    private static Bitmap applyExifOrientation(byte[] raw, Bitmap bmp) {
        try {
            ExifInterface exif = new ExifInterface(new java.io.ByteArrayInputStream(raw));
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

    /** Crop to a centered square and scale to target dimension. */
    private static Bitmap centerSquare(Bitmap bmp, int dim) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;
        Bitmap square = Bitmap.createBitmap(bmp, x, y, side, side);
        if (square != bmp) bmp.recycle();
        if (side == dim) return square;
        Bitmap scaled = Bitmap.createScaledBitmap(square, dim, dim, true);
        if (scaled != square) square.recycle();
        return scaled;
    }
}
