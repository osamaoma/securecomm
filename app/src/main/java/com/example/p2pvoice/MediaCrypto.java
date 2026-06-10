package com.example.p2pvoice;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM-256 encryption for arbitrary byte arrays — used for image and
 * audio media bodies. Wire format: [12-byte IV][ciphertext+16-byte tag].
 *
 * The text-message equivalent is {@link MessageCrypto}, which adds Base64
 * around the same wire format because text travels through JSON.
 */
public final class MediaCrypto {

    private static final int IV_LEN = 12;
    private static final int TAG_LEN_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private MediaCrypto() {}

    /** Encrypts plaintext bytes, returns raw IV-prefixed ciphertext. */
    public static byte[] encrypt(byte[] key32, byte[] plaintext) {
        if (key32 == null || key32.length != 32) {
            throw new IllegalArgumentException("key must be 32 bytes");
        }
        try {
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key32, "AES"),
                    new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] ct = c.doFinal(plaintext);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("media encrypt failed", e);
        }
    }

    /** Decrypts IV-prefixed ciphertext bytes. Returns null on any failure. */
    public static byte[] decrypt(byte[] key32, byte[] payload) {
        if (key32 == null || key32.length != 32 || payload == null) return null;
        if (payload.length < IV_LEN + 16) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(payload, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[payload.length - IV_LEN];
            System.arraycopy(payload, IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key32, "AES"),
                    new GCMParameterSpec(TAG_LEN_BITS, iv));
            return c.doFinal(ct);
        } catch (Exception e) {
            return null;
        }
    }
}
