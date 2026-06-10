package com.example.p2pvoice;

import org.json.JSONObject;

/**
 * In-band sentinels used to distinguish "rich" text payloads (contact card,
 * location share) from plain chat text. The sentinel travels INSIDE the
 * encrypted payload, so the server never sees the difference between a
 * regular text message and one of these — the recipient parses it after
 * decryption.
 *
 * Wire format:
 *   plaintext = "::CONTACT::{\"u\":\"01127778616\",\"n\":\"Sarah\"}"
 *   plaintext = "::LOCATION::{\"lat\":30.05,\"lng\":31.23,\"label\":\"Cairo\"}"
 *   plaintext = "hello there"                                  (plain text)
 *
 * Older clients that don't recognise the sentinel will display it verbatim
 * as text — degraded but not broken.
 */
public final class RichContent {

    public static final String CONTACT_SENTINEL  = "::CONTACT::";
    public static final String LOCATION_SENTINEL = "::LOCATION::";

    private RichContent() {}

    public static String encodeContact(String username, String displayName) {
        try {
            JSONObject o = new JSONObject();
            o.put("u", username);
            if (displayName != null) o.put("n", displayName);
            return CONTACT_SENTINEL + o.toString();
        } catch (Exception e) {
            // Fall back to a plain text representation so the user still
            // receives something meaningful.
            return "Contact: " + username;
        }
    }

    public static String encodeLocation(double lat, double lng, String label) {
        try {
            JSONObject o = new JSONObject();
            o.put("lat", lat);
            o.put("lng", lng);
            if (label != null) o.put("label", label);
            return LOCATION_SENTINEL + o.toString();
        } catch (Exception e) {
            return "Location: " + lat + ", " + lng;
        }
    }

    /** Inspect a freshly-decrypted plaintext and return the most specific
     *  Kind we can identify. Returns TEXT for anything we don't recognise. */
    public static ChatMessage.Kind kindOf(String plaintext) {
        if (plaintext == null) return ChatMessage.Kind.TEXT;
        if (plaintext.startsWith(CONTACT_SENTINEL))  return ChatMessage.Kind.CONTACT;
        if (plaintext.startsWith(LOCATION_SENTINEL)) return ChatMessage.Kind.LOCATION;
        return ChatMessage.Kind.TEXT;
    }

    /** Parse a contact payload (without the sentinel) into {username,name}.
     *  Returns null on any failure. */
    public static Contact parseContact(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(CONTACT_SENTINEL)) return null;
        try {
            JSONObject o = new JSONObject(plaintext.substring(CONTACT_SENTINEL.length()));
            String u = o.optString("u", null);
            String n = o.optString("n", null);
            if (u == null || u.isEmpty()) return null;
            return new Contact(u, n);
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse a location payload into double[]{lat, lng} (length 2), null on failure. */
    public static double[] parseLocation(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(LOCATION_SENTINEL)) return null;
        try {
            JSONObject o = new JSONObject(plaintext.substring(LOCATION_SENTINEL.length()));
            return new double[]{ o.optDouble("lat", 0), o.optDouble("lng", 0) };
        } catch (Exception e) {
            return null;
        }
    }

    /** Optional label string ("Cairo", "Home") attached to a location share. */
    public static String parseLocationLabel(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(LOCATION_SENTINEL)) return null;
        try {
            JSONObject o = new JSONObject(plaintext.substring(LOCATION_SENTINEL.length()));
            String s = o.optString("label", null);
            return (s == null || s.isEmpty() || "null".equals(s)) ? null : s;
        } catch (Exception e) {
            return null;
        }
    }
}
