package com.example.p2pvoice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.google.crypto.tink.subtle.X25519;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the device's long-term X25519 keypair and derives per-peer
 * conversation keys via ECDH (Elliptic Curve Diffie-Hellman).
 *
 * Cryptographic model:
 *
 *   Device A keeps secret key skA, publishes public key pkA.
 *   Device B keeps secret key skB, publishes public key pkB.
 *   On A's device: shared = X25519(skA, pkB)
 *   On B's device: shared = X25519(skB, pkA)
 *   Both arrive at the same 32-byte shared secret without it ever being
 *   transmitted. The signaling server sees only public keys.
 *
 *   conversation_key = SHA-256(shared || "p2pvoice-v1" || min(A,B) || max(A,B))
 *
 *   The sorted-username binding ensures A->B and B->A derive the same key
 *   regardless of who initiated. The version tag lets us rotate the KDF
 *   without colliding with old keys.
 *
 * The private key never leaves the device. The public key is sent to the
 * server once per registration and stored in-memory there for routing.
 *
 * If a peer's public key is unknown locally, peerKey() falls back to a
 * legacy hardcoded key so the app keeps working during rollout — but
 * messages between two upgraded clients use the proper per-conversation key.
 */
public final class KeyManager {

    private static final String TAG = "KeyManager";
    private static final String PREFS = "p2pvoice_keys";
    private static final String K_PRIVATE = "x25519_priv";
    private static final String K_PUBLIC  = "x25519_pub";
    private static final String K_KEY_VERSION = "x25519_version";   // our keypair's generation timestamp
    private static final String K_PEER_PREFIX = "peer_pk_";          // peer_pk_<username>      → base64 pubkey
    private static final String K_PEER_VER_PREFIX = "peer_ver_";     // peer_ver_<username>     → long version

    /** Legacy hardcoded key for backward compatibility with v1 clients. */
    private static final String LEGACY_SECRET = "p2pvoice-builtin-2026-shared-secret";

    private final SharedPreferences sp;
    private final byte[] privateKey;
    private final byte[] publicKey;
    private final String myUsername;

    /** Cache derived per-peer keys so we don't recompute on every message. */
    private final ConcurrentHashMap<String, byte[]> peerKeyCache = new ConcurrentHashMap<>();

    public KeyManager(Context ctx, String myUsername) {
        this.myUsername = myUsername == null ? "" : myUsername.toLowerCase();
        // Per-username identity keys. Two accounts on the same device must
        // never share the same X25519 keypair: a peer cryptographically
        // identifies you by your public key, so reusing one would make the
        // accounts indistinguishable on the wire. The PREFS constant stays
        // as a fallback for the no-account case (legacy upgrade path); for
        // any real account we use a per-user file.
        String prefsName = this.myUsername.isEmpty() ? PREFS : PREFS + "_" + this.myUsername;
        this.sp = ctx.getApplicationContext()
                .getSharedPreferences(prefsName, Context.MODE_PRIVATE);

        // Load or generate the device keypair once.
        String privB64 = sp.getString(K_PRIVATE, null);
        String pubB64  = sp.getString(K_PUBLIC, null);
        if (privB64 == null || pubB64 == null) {
            try {
                byte[] priv = X25519.generatePrivateKey();
                byte[] pub  = X25519.publicFromPrivate(priv);
                long version = System.currentTimeMillis();
                sp.edit()
                        .putString(K_PRIVATE, Base64.encodeToString(priv, Base64.NO_WRAP))
                        .putString(K_PUBLIC,  Base64.encodeToString(pub,  Base64.NO_WRAP))
                        .putLong(K_KEY_VERSION, version)
                        .apply();
                this.privateKey = priv;
                this.publicKey  = pub;
                Log.d(TAG, "generated new device keypair (v=" + version + ")");
            } catch (Exception e) {
                throw new RuntimeException("X25519 keygen failed", e);
            }
        } else {
            this.privateKey = Base64.decode(privB64, Base64.NO_WRAP);
            this.publicKey  = Base64.decode(pubB64,  Base64.NO_WRAP);
        }
    }

    /** Timestamp our current keypair was generated. Sent to server with our pubkey
     *  so peers can detect when we've rotated (reinstalled / new device). */
    public long myKeyVersion() {
        return sp.getLong(K_KEY_VERSION, 0L);
    }

    /** Public key of THIS device, Base64-encoded, to upload to the server. */
    public String myPublicKeyB64() {
        return Base64.encodeToString(publicKey, Base64.NO_WRAP);
    }

    /** Result of storing a peer's public key. */
    public enum StoreResult {
        UNCHANGED,    // key bytes are identical to what we had
        FIRST_TIME,   // we never had a key for this peer
        ROTATED       // we had a different key cached; the peer has rotated
    }

    /** Store a peer's public key as received from the server. Returns whether
     *  this is the first time we've seen them, a benign re-confirmation, or a
     *  rotation event (which the user may want to be warned about). */
    public StoreResult putPeerPublicKey(String peerUsername, String publicKeyB64, long version) {
        if (peerUsername == null || publicKeyB64 == null) return StoreResult.UNCHANGED;
        String peer = peerUsername.toLowerCase();
        String existing = sp.getString(K_PEER_PREFIX + peer, null);

        StoreResult result;
        if (existing == null) {
            result = StoreResult.FIRST_TIME;
        } else if (existing.equals(publicKeyB64)) {
            result = StoreResult.UNCHANGED;
        } else {
            result = StoreResult.ROTATED;
            Log.w(TAG, "peer key ROTATED for " + peer
                    + " (had v=" + sp.getLong(K_PEER_VER_PREFIX + peer, 0L)
                    + ", got v=" + version + ")");
        }

        sp.edit()
                .putString(K_PEER_PREFIX + peer, publicKeyB64)
                .putLong(K_PEER_VER_PREFIX + peer, version)
                .apply();
        peerKeyCache.remove(peer); // invalidate the derived-key cache
        return result;
    }

    /** Backward-compatible overload for callers that don't know the version. */
    public StoreResult putPeerPublicKey(String peerUsername, String publicKeyB64) {
        return putPeerPublicKey(peerUsername, publicKeyB64, System.currentTimeMillis());
    }

    public long getPeerVersion(String peerUsername) {
        if (peerUsername == null) return 0L;
        return sp.getLong(K_PEER_VER_PREFIX + peerUsername.toLowerCase(), 0L);
    }

    public String getPeerPublicKeyB64(String peerUsername) {
        if (peerUsername == null) return null;
        return sp.getString(K_PEER_PREFIX + peerUsername.toLowerCase(), null);
    }

    /**
     * Derive the per-conversation 32-byte AES key for talking to `peerUsername`.
     * Returns null if we don't have the peer's public key yet — caller should
     * either request it or fall back to legacyKey().
     */
    public byte[] conversationKey(String peerUsername) {
        if (peerUsername == null) return null;
        String peer = peerUsername.toLowerCase();
        byte[] cached = peerKeyCache.get(peer);
        if (cached != null) return cached;

        String peerPubB64 = getPeerPublicKeyB64(peer);
        if (peerPubB64 == null) return null;

        try {
            byte[] peerPub = Base64.decode(peerPubB64, Base64.NO_WRAP);
            byte[] shared = X25519.computeSharedSecret(privateKey, peerPub);

            // Bind the key to the (sorted) usernames so swapping which side
            // is "caller" can't produce different keys. Hash everything together.
            String low  = myUsername.compareTo(peer) < 0 ? myUsername : peer;
            String high = myUsername.compareTo(peer) < 0 ? peer       : myUsername;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(shared);
            md.update("p2pvoice-v1".getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0); // separator
            md.update(low.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(high.getBytes(StandardCharsets.UTF_8));
            byte[] derived = md.digest();

            peerKeyCache.put(peer, derived);
            return derived;
        } catch (Exception e) {
            Log.e(TAG, "shared secret derivation failed for " + peer, e);
            return null;
        }
    }

    /** Returns the legacy hardcoded 32-byte key for backward compatibility. */
    public byte[] legacyKey() {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(LEGACY_SECRET.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Convenience: get conversation key or fall back to legacy. */
    public byte[] keyFor(String peerUsername) {
        byte[] k = conversationKey(peerUsername);
        return k != null ? k : legacyKey();
    }

    /** Convenience: are we using a real per-conversation key, or the legacy fallback? */
    public boolean hasPeerKey(String peerUsername) {
        return conversationKey(peerUsername) != null;
    }

    /** Compute a 6-digit Short Authentication String for users to compare
     *  out-of-band, to detect MITM. Two devices that derive the same shared
     *  secret will print the same SAS. */
    public String shortAuthString(String peerUsername) {
        byte[] k = conversationKey(peerUsername);
        if (k == null) return null;
        // Take first 4 bytes, mod 1_000_000 → 6-digit code.
        int v = ((k[0] & 0xFF) << 24)
              | ((k[1] & 0xFF) << 16)
              | ((k[2] & 0xFF) << 8)
              |  (k[3] & 0xFF);
        int sas = Math.abs(v) % 1_000_000;
        return String.format("%06d", sas);
    }
}
