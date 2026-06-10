package com.example.p2pvoice;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM-256 encryption for chat message bodies. Stateless utility — caller
 * supplies the 32-byte key (typically a per-conversation key from
 * {@link KeyManager}).
 *
 * Wire format (Base64 over JSON):  [12-byte IV][ciphertext+16-byte tag]
 *
 * GCM provides both confidentiality and integrity: a tampered ciphertext
 * fails to decrypt rather than producing garbage plaintext.
 */
public final class MessageCrypto {

    private static final int IV_LEN = 12;          // GCM standard nonce size
    private static final int TAG_LEN_BITS = 128;   // GCM auth tag

    private static final SecureRandom RNG = new SecureRandom();

    private MessageCrypto() {}

    /** Encrypts plaintext with the given 32-byte key, returns Base64(iv || ct+tag). */
    public static String encrypt(byte[] key32, String plaintext) {
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
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException("encrypt failed", e);
        }
    }

    /** Decrypts Base64(iv || ct+tag). Returns null on any failure (wrong key,
     *  tampered data, malformed input). */
    public static String decrypt(byte[] key32, String base64) {
        if (key32 == null || key32.length != 32 || base64 == null) return null;
        try {
            byte[] in = Base64.decode(base64, Base64.NO_WRAP);
            if (in.length < IV_LEN + 16) return null;
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(in, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[in.length - IV_LEN];
            System.arraycopy(in, IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key32, "AES"),
                    new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] pt = c.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
