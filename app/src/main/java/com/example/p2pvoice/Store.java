package com.example.p2pvoice;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Local persistence using SharedPreferences. Stores the user's own profile
 * (username, server URL, shared E2EE secret) and the contact list as JSON.
 * Everything stays on-device.
 */
public class Store {

    // Two-tier storage. The "global" file is device-wide and just remembers
    // which account is currently active (plus a couple of device-scoped
    // settings like theme that apply regardless of who's signed in). Each
    // account's actual data — contacts, chats, keys, blocklist, etc. —
    // lives in its own file named "p2pvoice_user_<username>". Signing out
    // clears the active-user pointer in the global file but leaves the
    // per-user file untouched, so signing back in with the same username
    // restores everything for that account.
    private static final String GLOBAL_PREFS    = "p2pvoice_global";
    private static final String K_ACTIVE_USER   = "active_user";
    private static final String K_THEME_GLOBAL  = "theme_mode";   // device-wide
    private static final String K_FCM_TOKEN_G   = "fcm_token";    // device-scoped
    private static final String PREFS_USER_PREFIX = "p2pvoice_user_";
    // Used while no one is signed in — placeholder reads/writes go here and
    // are harmless (the user is bounced to SetupActivity before anything
    // meaningful happens).
    private static final String PREFS_NONE      = "p2pvoice_none";
    // Legacy single-file location from earlier app versions. On first
    // construction after upgrade we migrate its contents into the per-user
    // file under the username it contained.
    private static final String LEGACY_PREFS    = "p2pvoice_store";

    private static final String K_USERNAME = "username";
    private static final String K_SERVER = "server";
    private static final String K_SECRET = "secret";
    private static final String K_CONTACTS = "contacts";
    private static final String K_CALL_LOG = "call_log";
    private static final String K_FCM_TOKEN = "fcm_token";
    // My profile (display name + avatar metadata). All optional; if unset the
    // app falls back to username-as-display-name and a letter-tile avatar.
    private static final String K_MY_NAME       = "my_display_name";
    private static final String K_MY_AVATAR     = "my_avatar_path";      // local JPEG file
    private static final String K_MY_AV_URL     = "my_avatar_url";       // upload location
    private static final String K_MY_AV_KEY     = "my_avatar_key";       // Base64 32-byte AES key
    private static final String K_MY_PROF_VER   = "my_profile_version"; // ms timestamp
    private static final String K_GROUPS        = "groups";              // JSON array of group records
    private static final String K_INVITES       = "pending_invites";     // JSON array of invite records
    private static final String K_LAST_ACTIVITY = "last_activity";       // JSON map peer->ms
    private static final String K_THEME         = "theme_mode";          // legacy per-user, kept for migration
    private static final String K_BLOCKED       = "blocked_peers";       // JSON array of usernames
    private static final String K_MUTED         = "muted_peers";         // JSON array of peerIds (1-on-1 + groups)
    private static final String K_SEND_READ     = "send_read_receipts";  // boolean, default true
    private static final String K_OUT_REQS      = "out_contact_reqs";    // JSON array of usernames we've requested
    private static final String K_IN_REQS       = "in_contact_reqs";     // JSON array of {from, dn, ts}
    private static final String K_PEND_LEAVES   = "pending_leaves";      // JSON array of "peer:groupId" entries for retry on reconnect
    private static final int MAX_LOG = 100;

    private final Context app;
    private final SharedPreferences global;
    private SharedPreferences sp;

    public Store(Context ctx) {
        this.app = ctx.getApplicationContext();
        this.global = app.getSharedPreferences(GLOBAL_PREFS, Context.MODE_PRIVATE);

        // One-shot migration from the pre-namespacing layout. If the old
        // single-file SP holds a username and no active-user pointer is set,
        // move the contents to the new per-user file and adopt that account
        // as the active one. This makes the upgrade invisible: existing data
        // remains for its original owner.
        migrateLegacyIfNeeded();

        String activeUser = global.getString(K_ACTIVE_USER, "");
        String prefsName = activeUser.isEmpty()
                ? PREFS_NONE
                : PREFS_USER_PREFIX + activeUser;
        this.sp = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    private void migrateLegacyIfNeeded() {
        if (global.contains(K_ACTIVE_USER)) return;  // already on new layout
        SharedPreferences legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        String legacyUser = legacy.getString(K_USERNAME, "");
        if (legacyUser.isEmpty()) return;            // never signed in on old layout
        // Copy every key from legacy to the per-user file. Use type-aware
        // copy so booleans, longs, ints, and strings all survive.
        SharedPreferences perUser = app.getSharedPreferences(
                PREFS_USER_PREFIX + legacyUser, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = perUser.edit();
        for (java.util.Map.Entry<String, ?> en : legacy.getAll().entrySet()) {
            Object v = en.getValue();
            String k = en.getKey();
            if      (v instanceof String)  e.putString(k, (String) v);
            else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Long)    e.putLong(k, (Long) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Float)   e.putFloat(k, (Float) v);
            else if (v instanceof java.util.Set)
                e.putStringSet(k, (java.util.Set<String>) v);
        }
        e.apply();
        global.edit().putString(K_ACTIVE_USER, legacyUser).apply();
        // Also lift theme (device-wide) into global.
        String theme = legacy.getString(K_THEME, "");
        if (!theme.isEmpty()) global.edit().putString(K_THEME_GLOBAL, theme).apply();
        legacy.edit().clear().apply();
    }

    // ---- Profile ----
    public String getUsername() { return sp.getString(K_USERNAME, ""); }

    /** Switches the active account to {@code username} and opens that
     *  account's per-user file. Called by SetupActivity for first-time
     *  setup and for re-signing-in to an existing account. Note that the
     *  newly opened SP backs {@link #sp} only for subsequently-constructed
     *  Store instances — the activity calling this should typically restart
     *  itself (or relaunch MainActivity) to get a fresh Store. */
    public void setUsername(String v) {
        if (v == null) return;
        String u = v.trim().toLowerCase();
        if (u.isEmpty()) return;
        global.edit().putString(K_ACTIVE_USER, u).apply();
        // Re-open the per-user file so subsequent reads/writes through this
        // same Store instance go to the right account. Without this, any
        // setX call right after setUsername would still hit the previous
        // (probably placeholder) file.
        this.sp = app.getSharedPreferences(PREFS_USER_PREFIX + u, Context.MODE_PRIVATE);
        this.sp.edit().putString(K_USERNAME, u).apply();
    }

    /** Whether any account is currently signed in on this device. */
    public boolean hasProfile() {
        return !global.getString(K_ACTIVE_USER, "").isEmpty();
    }

    /** List of all accounts that have data on this device (signed in at
     *  least once and not since deleted). Useful for an account-switcher
     *  UI; not used by the app today. */
    public java.util.List<String> getKnownAccounts() {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.io.File prefDir = new java.io.File(app.getApplicationInfo().dataDir, "shared_prefs");
        if (!prefDir.isDirectory()) return out;
        for (String f : prefDir.list() != null ? prefDir.list() : new String[0]) {
            if (f.startsWith(PREFS_USER_PREFIX) && f.endsWith(".xml")) {
                String u = f.substring(PREFS_USER_PREFIX.length(), f.length() - 4);
                if (!u.isEmpty()) out.add(u);
            }
        }
        return out;
    }

    public String getServer() { return sp.getString(K_SERVER, "ws://10.0.2.2:8080"); }
    public void setServer(String v) { sp.edit().putString(K_SERVER, v).apply(); }

    // Default built-in shared E2EE key. Every device using this app shares it,
    // which means the demo "just works" without users coordinating a passphrase.
    // SECURITY TRADEOFF: anyone with the APK can extract this string and decrypt
    // traffic; for production-grade E2EE replace this with an authenticated key
    // exchange (X25519 ECDH + Short Authentication String). DTLS-SRTP still
    // encrypts transport regardless of this key.
    public static final String DEFAULT_E2EE_SECRET = "p2pvoice-builtin-2026-shared-secret";

    public String getSecret() {
        String v = sp.getString(K_SECRET, "");
        return v.isEmpty() ? DEFAULT_E2EE_SECRET : v;
    }
    public void setSecret(String v) { sp.edit().putString(K_SECRET, v).apply(); }

    /** Sign out: clears the active-user pointer in the global file so the
     *  next launch lands on SetupActivity. Per-user data is left on disk —
     *  signing back in with the same username restores contacts, chats,
     *  identity keys, etc. To genuinely delete an account's data, call
     *  {@link #deleteAccount}. */
    public void clearProfile() {
        global.edit().remove(K_ACTIVE_USER).apply();
    }

    /** Permanently delete one account's data on this device. Does NOT touch
     *  the global file's active-user pointer (callers should sign out
     *  separately if needed). */
    public void deleteAccount(String username) {
        if (username == null) return;
        String u = username.trim().toLowerCase();
        if (u.isEmpty()) return;
        SharedPreferences perUser = app.getSharedPreferences(
                PREFS_USER_PREFIX + u, Context.MODE_PRIVATE);
        perUser.edit().clear().apply();
        // Best-effort: also remove the file itself on API 24+. If the API
        // isn't available or the call fails, the cleared-keys file remains
        // harmlessly on disk.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try { app.deleteSharedPreferences(PREFS_USER_PREFIX + u); }
            catch (Throwable ignored) {}
        }
    }

    // ---- Contacts ----
    public List<Contact> getContacts() {
        List<Contact> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(K_CONTACTS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Contact(o.getString("u"), o.optString("d")));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public void saveContacts(List<Contact> contacts) {
        JSONArray arr = new JSONArray();
        try {
            for (Contact c : contacts) {
                JSONObject o = new JSONObject();
                o.put("u", c.username);
                o.put("d", c.displayName);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        sp.edit().putString(K_CONTACTS, arr.toString()).apply();
    }

    public void addContact(Contact c) {
        List<Contact> list = getContacts();
        for (Contact existing : list) {
            if (existing.username.equalsIgnoreCase(c.username)) {
                existing.displayName = c.displayName; // update
                saveContacts(list);
                return;
            }
        }
        list.add(c);
        saveContacts(list);
    }

    public void removeContact(String username) {
        List<Contact> list = getContacts();
        list.removeIf(c -> c.username.equalsIgnoreCase(username));
        saveContacts(list);
    }

    // ---- Call log ----
    public List<CallLogEntry> getCallLog() {
        List<CallLogEntry> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(K_CALL_LOG, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new CallLogEntry(
                        o.getString("peer"),
                        CallLogEntry.Direction.valueOf(o.getString("dir")),
                        o.getLong("ts"),
                        o.getLong("dur"),
                        o.optBoolean("vid", false)));   // default false: old rows had no field
            }
        } catch (Exception ignored) {}
        return list;
    }

    public void addCallLogEntry(CallLogEntry e) {
        List<CallLogEntry> list = getCallLog();
        list.add(0, e); // newest first
        while (list.size() > MAX_LOG) list.remove(list.size() - 1);
        JSONArray arr = new JSONArray();
        try {
            for (CallLogEntry c : list) {
                JSONObject o = new JSONObject();
                o.put("peer", c.peer);
                o.put("dir", c.direction.name());
                o.put("ts", c.timestamp);
                o.put("dur", c.durationSec);
                o.put("vid", c.isVideo);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        sp.edit().putString(K_CALL_LOG, arr.toString()).apply();
    }

    public void clearCallLog() {
        sp.edit().remove(K_CALL_LOG).apply();
    }

    // ---- FCM token ----
    public String getFcmToken() {
        return global.getString(K_FCM_TOKEN_G, null);
    }

    public void setFcmToken(String token) {
        global.edit().putString(K_FCM_TOKEN_G, token).apply();
    }

    // ---- My profile ----
    // The user's own display name + avatar. Display name defaults to username
    // if unset. Avatar is held locally as a plain JPEG (for self-display) AND
    // as ciphertext on the server (for sharing with contacts via profile_msg).
    public String getMyDisplayName() {
        String v = sp.getString(K_MY_NAME, "");
        return v.isEmpty() ? getUsername() : v;
    }
    public void setMyDisplayName(String name) {
        sp.edit().putString(K_MY_NAME, name == null ? "" : name).apply();
    }
    public String getMyAvatarPath()    { return sp.getString(K_MY_AVATAR, null); }
    public void   setMyAvatarPath(String p)   { sp.edit().putString(K_MY_AVATAR, p).apply(); }
    public String getMyAvatarUrl()     { return sp.getString(K_MY_AV_URL, null); }
    public void   setMyAvatarUrl(String u)    { sp.edit().putString(K_MY_AV_URL, u).apply(); }
    public String getMyAvatarKeyB64()  { return sp.getString(K_MY_AV_KEY, null); }
    public void   setMyAvatarKeyB64(String k) { sp.edit().putString(K_MY_AV_KEY, k).apply(); }
    public long   getMyProfileVersion(){ return sp.getLong(K_MY_PROF_VER, 0L); }
    public void   setMyProfileVersion(long v) { sp.edit().putLong(K_MY_PROF_VER, v).apply(); }

    // ---- Per-peer profile cache ----
    // Stored under "prof_<peer>" as a JSON object. version lets us skip re-
    // download when a peer pushes profile_msg with an older or equal version.
    private static String profKey(String peer) { return "prof_" + peer.toLowerCase(); }

    /** Snapshot of a peer's profile as we last saw it. Fields may be null. */
    public static class PeerProfile {
        public String displayName;
        public String avatarPath;   // local cached JPEG (decrypted)
        public long   version;
    }

    public PeerProfile getPeerProfile(String peer) {
        String s = sp.getString(profKey(peer), null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            PeerProfile p = new PeerProfile();
            p.displayName = o.optString("name", null);
            p.avatarPath  = o.optString("avatar", null);
            p.version     = o.optLong("ver", 0L);
            if ("null".equals(p.displayName)) p.displayName = null;
            if ("null".equals(p.avatarPath))  p.avatarPath = null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    public void putPeerProfile(String peer, PeerProfile p) {
        try {
            JSONObject o = new JSONObject();
            if (p.displayName != null) o.put("name",   p.displayName);
            if (p.avatarPath  != null) o.put("avatar", p.avatarPath);
            o.put("ver", p.version);
            sp.edit().putString(profKey(peer), o.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ---- Groups ----
    // Stored as a single JSON array under K_GROUPS. Each group entry holds id,
    // name, member list, version, and the group key (base64). For small numbers
    // of groups this is more practical than per-group keys, and keeps a single
    // source of truth atomic on write.
    public java.util.List<Group> getGroups() {
        java.util.List<Group> out = new java.util.ArrayList<>();
        String s = sp.getString(K_GROUPS, "[]");
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Group g = new Group();
                g.id   = o.optString("id");
                g.name = o.optString("name", g.id);
                g.version = o.optLong("ver", 0);
                String keyB64 = o.optString("key", null);
                if (keyB64 != null && !keyB64.isEmpty() && !"null".equals(keyB64)) {
                    g.groupKey = android.util.Base64.decode(keyB64, android.util.Base64.NO_WRAP);
                }
                JSONArray members = o.optJSONArray("members");
                if (members != null) {
                    for (int j = 0; j < members.length(); j++) g.members.add(members.optString(j));
                }
                out.add(g);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public Group getGroup(String groupId) {
        if (groupId == null) return null;
        for (Group g : getGroups()) {
            if (g.id.equalsIgnoreCase(groupId)) return g;
        }
        return null;
    }

    public void putGroup(Group g) {
        if (g == null || g.id == null) return;
        java.util.List<Group> all = getGroups();
        // Replace existing or append.
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equalsIgnoreCase(g.id)) { idx = i; break; }
        }
        if (idx >= 0) all.set(idx, g); else all.add(g);
        saveGroups(all);
    }

    public void removeGroup(String groupId) {
        java.util.List<Group> all = getGroups();
        all.removeIf(g -> g.id.equalsIgnoreCase(groupId));
        saveGroups(all);
        // Also drop the group's chat history.
        sp.edit().remove(chatKey("group:" + groupId)).apply();
    }

    private void saveGroups(java.util.List<Group> groups) {
        try {
            JSONArray arr = new JSONArray();
            for (Group g : groups) {
                JSONObject o = new JSONObject();
                o.put("id", g.id);
                o.put("name", g.name == null ? "" : g.name);
                o.put("ver", g.version);
                if (g.groupKey != null) {
                    o.put("key", android.util.Base64.encodeToString(
                            g.groupKey, android.util.Base64.NO_WRAP));
                }
                JSONArray members = new JSONArray();
                for (String m : g.members) members.put(m);
                o.put("members", members);
                arr.put(o);
            }
            sp.edit().putString(K_GROUPS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ---- Pending group invites ----
    // When a "group_event_msg" arrives with event=invite, we DON'T auto-join
    // the group anymore — we store it as a pending invite and surface a
    /** Friend-request style contact addition. When user A wants to add user B,
     *  A's client sends a contact_request rather than just adding B locally.
     *  B sees the request, accepts or declines. On accept, B adds A to their
     *  contacts and sends contact_accept back to A, who adds B in turn.
     *  Both incoming and outgoing pending state lives here so the UI can
     *  show the request banner (incoming) and stop offering "add" for an
     *  already-pending peer (outgoing). */
    public static class PendingContactRequest {
        public String from;          // username who sent the request
        public String displayName;   // optional friendly name from sender
        public long   receivedAt;
    }

    public java.util.List<PendingContactRequest> getIncomingContactRequests() {
        java.util.List<PendingContactRequest> out = new java.util.ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(K_IN_REQS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                PendingContactRequest r = new PendingContactRequest();
                r.from        = o.optString("from", "");
                r.displayName = o.optString("dn", "");
                r.receivedAt  = o.optLong("ts", System.currentTimeMillis());
                if (!r.from.isEmpty()) out.add(r);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void putIncomingContactRequest(String from, String displayName, long ts) {
        if (from == null || from.isEmpty()) return;
        java.util.List<PendingContactRequest> all = getIncomingContactRequests();
        // Dedup: if we already have a pending request from this user, replace
        // it (refresh timestamp + display name) rather than appending.
        for (java.util.Iterator<PendingContactRequest> it = all.iterator(); it.hasNext(); ) {
            if (it.next().from.equalsIgnoreCase(from)) it.remove();
        }
        PendingContactRequest r = new PendingContactRequest();
        r.from = from.toLowerCase();
        r.displayName = displayName == null ? "" : displayName;
        r.receivedAt = ts > 0 ? ts : System.currentTimeMillis();
        all.add(r);
        writeIncomingContactRequests(all);
    }

    public void removeIncomingContactRequest(String from) {
        if (from == null) return;
        java.util.List<PendingContactRequest> all = getIncomingContactRequests();
        boolean changed = false;
        for (java.util.Iterator<PendingContactRequest> it = all.iterator(); it.hasNext(); ) {
            if (it.next().from.equalsIgnoreCase(from)) { it.remove(); changed = true; }
        }
        if (changed) writeIncomingContactRequests(all);
    }

    private void writeIncomingContactRequests(java.util.List<PendingContactRequest> list) {
        try {
            JSONArray arr = new JSONArray();
            for (PendingContactRequest r : list) {
                arr.put(new JSONObject()
                        .put("from", r.from)
                        .put("dn", r.displayName == null ? "" : r.displayName)
                        .put("ts", r.receivedAt));
            }
            sp.edit().putString(K_IN_REQS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** True if we already have an unanswered outgoing request to this user. */
    public boolean hasOutgoingContactRequest(String username) {
        if (username == null) return false;
        return readSet(K_OUT_REQS).contains(username.toLowerCase());
    }
    public void addOutgoingContactRequest(String username) {
        if (username == null) return;
        java.util.Set<String> s = readSet(K_OUT_REQS);
        s.add(username.toLowerCase());
        writeSet(K_OUT_REQS, s);
    }
    public void removeOutgoingContactRequest(String username) {
        if (username == null) return;
        java.util.Set<String> s = readSet(K_OUT_REQS);
        if (s.remove(username.toLowerCase())) writeSet(K_OUT_REQS, s);
    }
    public java.util.List<String> getOutgoingContactRequests() {
        return new java.util.ArrayList<>(readSet(K_OUT_REQS));
    }

    // ----- Pending group-leave broadcasts -----
    // When the user taps "Leave" while the WebSocket isn't ready, the local
    // group is still removed (the user's intent is clear), but the wire
    // "leave" messages to the other members can't go out. We persist the
    // (peer, groupId) tuples here so we can flush them on the next register,
    // even though the group itself no longer exists in our store. Encoded
    // as "peer:groupId" because Set<String> is what the underlying prefs
    // give us cheaply.
    public void addPendingLeave(String peer, String groupId) {
        if (peer == null || groupId == null) return;
        java.util.Set<String> s = readSet(K_PEND_LEAVES);
        s.add(peer.toLowerCase() + ":" + groupId);
        writeSet(K_PEND_LEAVES, s);
    }
    public void removePendingLeave(String peer, String groupId) {
        if (peer == null || groupId == null) return;
        java.util.Set<String> s = readSet(K_PEND_LEAVES);
        if (s.remove(peer.toLowerCase() + ":" + groupId)) writeSet(K_PEND_LEAVES, s);
    }
    /** Returns a list of {peer, groupId} pairs. */
    public java.util.List<String[]> getPendingLeaves() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String entry : readSet(K_PEND_LEAVES)) {
            int idx = entry.indexOf(':');
            if (idx > 0 && idx < entry.length() - 1) {
                out.add(new String[]{entry.substring(0, idx), entry.substring(idx + 1)});
            }
        }
        return out;
    }

    // ----- Group invitations (existing) -----
    // Group invitations received from peers, held until the user accepts.
    // The user explicitly accepts (promotes to a real Group) or declines
    // (sends a reject event back to the inviter).
    public static class PendingInvite {
        public String groupId;
        public String groupName;
        public byte[] groupKey;
        public java.util.List<String> members = new java.util.ArrayList<>();
        public String inviter;        // username who sent the invite
        public long   version;        // bump value from the invite, for stale-check
        public long   receivedAt;     // local ms timestamp for ordering
    }

    public java.util.List<PendingInvite> getPendingInvites() {
        java.util.List<PendingInvite> out = new java.util.ArrayList<>();
        String s = sp.getString(K_INVITES, "[]");
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                PendingInvite p = new PendingInvite();
                p.groupId   = o.optString("id");
                p.groupName = o.optString("name", p.groupId);
                p.inviter   = o.optString("from", "");
                p.version   = o.optLong("ver", 0L);
                p.receivedAt = o.optLong("ts", System.currentTimeMillis());
                String keyB64 = o.optString("key", null);
                if (keyB64 != null && !keyB64.isEmpty() && !"null".equals(keyB64)) {
                    p.groupKey = android.util.Base64.decode(keyB64, android.util.Base64.NO_WRAP);
                }
                JSONArray members = o.optJSONArray("members");
                if (members != null) {
                    for (int j = 0; j < members.length(); j++) p.members.add(members.optString(j));
                }
                out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public PendingInvite getPendingInvite(String groupId) {
        if (groupId == null) return null;
        for (PendingInvite p : getPendingInvites()) {
            if (p.groupId.equalsIgnoreCase(groupId)) return p;
        }
        return null;
    }

    public void putPendingInvite(PendingInvite p) {
        if (p == null || p.groupId == null) return;
        java.util.List<PendingInvite> all = getPendingInvites();
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).groupId.equalsIgnoreCase(p.groupId)) { idx = i; break; }
        }
        if (idx >= 0) all.set(idx, p); else all.add(p);
        saveInvites(all);
    }

    public void removePendingInvite(String groupId) {
        java.util.List<PendingInvite> all = getPendingInvites();
        all.removeIf(p -> p.groupId.equalsIgnoreCase(groupId));
        saveInvites(all);
    }

    private void saveInvites(java.util.List<PendingInvite> invites) {
        try {
            JSONArray arr = new JSONArray();
            for (PendingInvite p : invites) {
                JSONObject o = new JSONObject();
                o.put("id", p.groupId);
                o.put("name", p.groupName == null ? "" : p.groupName);
                o.put("from", p.inviter == null ? "" : p.inviter);
                o.put("ver", p.version);
                o.put("ts", p.receivedAt);
                if (p.groupKey != null) {
                    o.put("key", android.util.Base64.encodeToString(
                            p.groupKey, android.util.Base64.NO_WRAP));
                }
                JSONArray members = new JSONArray();
                for (String m : p.members) members.put(m);
                o.put("members", members);
                arr.put(o);
            }
            sp.edit().putString(K_INVITES, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ---- Chat messages ----
    // Stored per peer under key "chat_<peer>". Cap each conversation at MAX_CHAT
    // most-recent messages to keep SharedPreferences small.
    private static final int MAX_CHAT = 500;
    private static String chatKey(String peer) { return "chat_" + peer.toLowerCase(); }

    public List<ChatMessage> getMessages(String peer) {
        List<ChatMessage> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(chatKey(peer), "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ChatMessage m = new ChatMessage(
                        o.getString("id"),
                        peer,
                        ChatMessage.Direction.valueOf(o.getString("dir")),
                        o.getString("text"),
                        o.getLong("ts"),
                        ChatMessage.Status.valueOf(o.optString("st", "DELIVERED")));
                m.kind = ChatMessage.Kind.valueOf(o.optString("kind", "TEXT"));
                m.mediaUrl     = o.optString("murl", null);
                m.mediaType    = o.optString("mtype", null);
                m.mediaWidth   = o.optInt("mw", 0);
                m.mediaHeight  = o.optInt("mh", 0);
                m.thumbnailB64 = o.optString("mthumb", null);
                m.localPath    = o.optString("mlocal", null);
                m.durationMs   = o.optInt("mdur", 0);
                m.replyToId      = o.optString("rid", null);
                m.replyToPreview = o.optString("rpv", null);
                m.forwarded      = o.optBoolean("fwd", false);
                m.senderUsername = o.optString("sender", null);
                m.groupId        = o.optString("gid", null);
                m.fileName       = o.optString("fname", null);
                m.fileSize       = o.optLong("fsize", 0L);
                m.editedAt       = o.optLong("eat", 0L);
                m.deleted        = o.optBoolean("del", false);
                JSONObject rx = o.optJSONObject("rx");
                if (rx != null) {
                    java.util.Iterator<String> it = rx.keys();
                    while (it.hasNext()) {
                        String user = it.next();
                        m.reactions.put(user, rx.optString(user, ""));
                    }
                }
                if ("null".equals(m.mediaUrl))        m.mediaUrl = null;
                if ("null".equals(m.mediaType))       m.mediaType = null;
                if ("null".equals(m.thumbnailB64))    m.thumbnailB64 = null;
                if ("null".equals(m.localPath))       m.localPath = null;
                if ("null".equals(m.replyToId))       m.replyToId = null;
                if ("null".equals(m.replyToPreview))  m.replyToPreview = null;
                if ("null".equals(m.senderUsername))  m.senderUsername = null;
                if ("null".equals(m.groupId))         m.groupId = null;
                if ("null".equals(m.fileName))        m.fileName = null;
                out.add(m);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Append or replace a message (matched by id) and persist. */
    public void putMessage(ChatMessage m) {
        List<ChatMessage> list = getMessages(m.peer);
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(m.id)) { idx = i; break; }
        }
        if (idx >= 0) list.set(idx, m);
        else list.add(m);
        while (list.size() > MAX_CHAT) list.remove(0);
        saveMessages(m.peer, list);
        // Bump last-activity for sorting. Using max() guards against late
        // status-update writes for an older message accidentally pushing the
        // conversation up over a newer one.
        setLastActivity(m.peer, m.timestamp);
    }

    // ---- Last-activity timestamps (for sorting conversations) ----
    // Stored as a single JSON object mapping peer -> ms timestamp. Read on
    // every list refresh; the cost is one prefs read + one JSON parse, vs
    // re-deserialising every message in every conversation just to find the
    // most recent timestamp.
    public long getLastActivity(String peer) {
        if (peer == null) return 0;
        try {
            JSONObject o = new JSONObject(sp.getString(K_LAST_ACTIVITY, "{}"));
            return o.optLong(peer.toLowerCase(), 0L);
        } catch (Exception e) {
            return 0;
        }
    }

    public void setLastActivity(String peer, long ts) {
        if (peer == null || ts <= 0) return;
        try {
            JSONObject o = new JSONObject(sp.getString(K_LAST_ACTIVITY, "{}"));
            long current = o.optLong(peer.toLowerCase(), 0L);
            if (ts > current) {
                o.put(peer.toLowerCase(), ts);
                sp.edit().putString(K_LAST_ACTIVITY, o.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    /** One-time migration: walk every existing conversation and seed the
     *  last-activity map from the timestamp of the most recent message in
     *  each. Lets users upgrading from a build without sorting see their
     *  conversations correctly ordered on first launch.
     *
     *  Safe to call on every launch; it short-circuits once it's run. */
    public void backfillLastActivityIfNeeded() {
        if (sp.contains(K_LAST_ACTIVITY)) return;
        try {
            JSONObject acc = new JSONObject();
            for (Contact c : getContacts()) {
                long ts = lastMessageTs(c.username);
                if (ts > 0) acc.put(c.username.toLowerCase(), ts);
            }
            for (Group g : getGroups()) {
                long ts = lastMessageTs(g.peerId());
                if (ts > 0) acc.put(g.peerId().toLowerCase(), ts);
            }
            sp.edit().putString(K_LAST_ACTIVITY, acc.toString()).apply();
        } catch (Exception ignored) {
            // Leave the key unset; future putMessage calls will populate it
            // organically as new messages arrive.
        }
    }

    /** Theme mode: one of "system" (default), "light", "dark". Applied at app
     *  start via AppCompatDelegate.setDefaultNightMode. Device-wide (lives
     *  in the global file) so the choice persists across sign-outs and
     *  account switches. */
    public String getThemeMode() {
        return global.getString(K_THEME_GLOBAL, "system");
    }

    public void setThemeMode(String mode) {
        global.edit().putString(K_THEME_GLOBAL, mode == null ? "system" : mode).apply();
    }

    /** Whether we should send "read" acks back to senders when the user
     *  opens chats. Default true. */
    public boolean getSendReadReceipts() {
        return sp.getBoolean(K_SEND_READ, true);
    }
    public void setSendReadReceipts(boolean enabled) {
        sp.edit().putBoolean(K_SEND_READ, enabled).apply();
    }

    // ---- Block / mute ----
    // Both are entirely local state — the server doesn't know who you've
    // blocked or muted. Blocked usernames cause incoming chat/media/calls
    // from them to be silently dropped; muted peerIds suppress notifications
    // but otherwise behave normally.

    public boolean isBlocked(String peer) {
        return peer != null && readSet(K_BLOCKED).contains(peer.toLowerCase());
    }
    public void setBlocked(String peer, boolean blocked) {
        if (peer == null) return;
        java.util.Set<String> s = readSet(K_BLOCKED);
        if (blocked) s.add(peer.toLowerCase()); else s.remove(peer.toLowerCase());
        writeSet(K_BLOCKED, s);
    }
    public boolean isMuted(String peer) {
        return peer != null && readSet(K_MUTED).contains(peer.toLowerCase());
    }
    public void setMuted(String peer, boolean muted) {
        if (peer == null) return;
        java.util.Set<String> s = readSet(K_MUTED);
        if (muted) s.add(peer.toLowerCase()); else s.remove(peer.toLowerCase());
        writeSet(K_MUTED, s);
    }

    private java.util.Set<String> readSet(String key) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(key, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        } catch (Exception ignored) {}
        return out;
    }
    private void writeSet(String key, java.util.Set<String> set) {
        try {
            JSONArray arr = new JSONArray();
            for (String s : set) arr.put(s);
            sp.edit().putString(key, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Helper: timestamp of the most recent message in this conversation, or
     *  0 if there are none. Avoids deserialising every message just to find
     *  the last timestamp by reading the raw JSON array directly. */
    private long lastMessageTs(String peer) {
        try {
            JSONArray arr = new JSONArray(sp.getString(chatKey(peer), "[]"));
            if (arr.length() == 0) return 0;
            // Messages are appended in chronological order, so the last entry
            // has the highest timestamp.
            return arr.getJSONObject(arr.length() - 1).optLong("ts", 0L);
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveMessages(String peer, List<ChatMessage> list) {
        JSONArray arr = new JSONArray();
        try {
            for (ChatMessage m : list) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("dir", m.direction.name());
                o.put("text", m.text);
                o.put("ts", m.timestamp);
                o.put("st", m.status.name());
                o.put("kind", m.kind.name());
                if (m.mediaUrl     != null) o.put("murl",   m.mediaUrl);
                if (m.mediaType    != null) o.put("mtype",  m.mediaType);
                if (m.mediaWidth   > 0)     o.put("mw",     m.mediaWidth);
                if (m.mediaHeight  > 0)     o.put("mh",     m.mediaHeight);
                if (m.thumbnailB64 != null) o.put("mthumb", m.thumbnailB64);
                if (m.localPath    != null) o.put("mlocal", m.localPath);
                if (m.durationMs   > 0)     o.put("mdur",   m.durationMs);
                if (m.replyToId      != null) o.put("rid", m.replyToId);
                if (m.replyToPreview != null) o.put("rpv", m.replyToPreview);
                if (m.forwarded)              o.put("fwd", true);
                if (m.senderUsername != null) o.put("sender", m.senderUsername);
                if (m.groupId        != null) o.put("gid", m.groupId);
                if (m.fileName       != null) o.put("fname", m.fileName);
                if (m.fileSize       > 0)     o.put("fsize", m.fileSize);
                if (m.editedAt       > 0)     o.put("eat", m.editedAt);
                if (m.deleted)                o.put("del", true);
                if (m.reactions != null && !m.reactions.isEmpty()) {
                    JSONObject rx = new JSONObject();
                    for (java.util.Map.Entry<String, String> e : m.reactions.entrySet()) {
                        rx.put(e.getKey(), e.getValue());
                    }
                    o.put("rx", rx);
                }
                arr.put(o);
            }
        } catch (Exception ignored) {}
        sp.edit().putString(chatKey(peer), arr.toString()).apply();
    }

    public void clearMessages(String peer) {
        sp.edit().remove(chatKey(peer)).apply();
    }

    // ---- Unread counts ----
    // We track unread per peer as a simple counter; bumped on INCOMING when the
    // chat screen for that peer isn't currently in the foreground.
    public int getUnread(String peer) {
        return sp.getInt("unread_" + peer.toLowerCase(), 0);
    }

    public void setUnread(String peer, int n) {
        sp.edit().putInt("unread_" + peer.toLowerCase(), Math.max(0, n)).apply();
    }

    public void incrementUnread(String peer) {
        setUnread(peer, getUnread(peer) + 1);
    }

    public void clearUnread(String peer) {
        sp.edit().remove("unread_" + peer.toLowerCase()).apply();
    }
}
