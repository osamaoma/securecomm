package com.example.p2pvoice;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;

/**
 * WebSocket signaling client for the username-routed protocol.
 * Handles registration, presence, call setup (call/incoming/accept/reject),
 * and WebRTC signaling relay (offer/answer/candidate). Never carries audio.
 */
public class SignalingClient {

    private static final String TAG = "SignalingClient";

    public interface Listener {
        void onOpen();
        void onRegistered(String username);
        void onRegisterError(String reason);
        void onPresence(String username, boolean online);

        void onIncomingCall(String from, String callId, boolean isVideo);
        void onCalling(String to, String callId);
        void onCallAccepted(String callId, boolean initiator, boolean isVideo);
        void onCallRejected(String callId);
        void onCallFailed(String to, String reason);
        void onCallEnded(String callId);

        void onOffer(String callId, JSONObject sdp);
        void onAnswer(String callId, JSONObject sdp);
        void onCandidate(String callId, JSONObject candidate);

        // Chat messaging
        void onChatMessage(String from, String id, String ciphertext,
                           String replyToId, String replyToPreviewCt,
                           boolean forwarded, String groupId, long ts);
        void onChatAck(String id, String status);
        // An earlier message was edited by its sender. id matches the original.
        void onChatEdit(String from, String id, String ciphertext, String groupId, long ts);
        // An earlier message was unsent by its sender. id matches the original.
        void onChatDelete(String from, String id, String groupId, long ts);

        /** Recipient acked one or more messages as read. ids correspond to
         *  message ids previously sent by `to=from` (the now-listener). */
        void onChatRead(String from, java.util.List<String> ids, long ts);
        /** Peer started or stopped typing. Transient — UI should time out
         *  on its own after a few seconds in case a stop event is lost. */
        void onTyping(String from, boolean typing, String groupId, long ts);
        /** Presence update for a watched user. lastSeen is 0 when online. */
        void onPresence(String user, boolean online, long lastSeen);

        /** Someone is asking to add us as a contact. */
        void onContactRequest(String from, String displayName, long ts);
        /** A peer accepted our earlier contact request. */
        void onContactAccept(String from, String displayName, long ts);

        // Media (images, voice notes)
        void onMediaMessage(String from, String id, String url, String mediaType,
                            int width, int height, String thumbnailB64, String captionCt,
                            int durationMs, String replyToId, String replyToPreviewCt,
                            boolean forwarded, String groupId, long ts);
        void onMediaAck(String id, String status);

        // Reactions
        void onReaction(String from, String targetId, String emoji, String groupId, long ts);

        // Profile updates (display name + avatar metadata, encrypted per peer)
        void onProfileMessage(String from, String payloadCt, long version, long ts);

        // Group roster/key events (invite, update, leave). payloadCt encrypted
        // with the pairwise conversation key. event == "leave" carries no payload.
        void onGroupEvent(String from, String groupId, String event,
                          String payloadCt, long ts);

        // E2EE key exchange
        void onPeerPublicKey(String peer, String publicKeyB64, long version);

        void onForceLogout(String reason);
        void onClosed();
        void onError(String message);
    }

    private WebSocketClient ws;
    private final Listener listener;

    public SignalingClient(String url, Listener listener) {
        this.listener = listener;
        connect(url);
    }

    private void connect(String url) {
        try {
            ws = new WebSocketClient(new URI(url)) {
                @Override public void onOpen(ServerHandshake h) { listener.onOpen(); }
                @Override public void onMessage(String message) { handle(message); }
                @Override public void onClose(int c, String r, boolean remote) { listener.onClosed(); }
                @Override public void onError(Exception ex) {
                    Log.e(TAG, "WS error", ex);
                    listener.onError(ex.getMessage());
                }
            };
            ws.connect();
        } catch (Exception e) {
            listener.onError(e.getMessage());
        }
    }

    private void handle(String message) {
        try {
            JSONObject m = new JSONObject(message);
            String type = m.optString("type");
            switch (type) {
                case "registered":
                    listener.onRegistered(m.optString("username")); break;
                case "force_logout":
                    listener.onForceLogout(m.optString("reason")); break;
                case "register_error":
                    listener.onRegisterError(m.optString("reason")); break;
                case "presence":
                    listener.onPresence(m.optString("username"), m.optBoolean("online")); break;
                case "incoming_call":
                    listener.onIncomingCall(m.optString("from"), m.optString("callId"),
                            m.optBoolean("isVideo", false)); break;
                case "calling":
                    listener.onCalling(m.optString("to"), m.optString("callId")); break;
                case "call_accepted":
                    listener.onCallAccepted(m.optString("callId"),
                            m.optBoolean("initiator"),
                            m.optBoolean("isVideo", false)); break;
                case "call_rejected":
                    listener.onCallRejected(m.optString("callId")); break;
                case "call_failed":
                    listener.onCallFailed(m.optString("to"), m.optString("reason")); break;
                case "call_ended":
                    listener.onCallEnded(m.optString("callId")); break;
                case "offer":
                    listener.onOffer(m.optString("callId"), m.getJSONObject("sdp")); break;
                case "answer":
                    listener.onAnswer(m.optString("callId"), m.getJSONObject("sdp")); break;
                case "candidate":
                    listener.onCandidate(m.optString("callId"), m.getJSONObject("candidate")); break;
                case "chat_msg": {
                    JSONObject rt = m.optJSONObject("replyTo");
                    listener.onChatMessage(
                            m.optString("from"),
                            m.optString("id"),
                            m.optString("ciphertext"),
                            rt != null ? rt.optString("id", null) : null,
                            rt != null ? rt.optString("previewCt", null) : null,
                            m.optBoolean("forwarded", false),
                            m.optString("groupId", null),
                            m.optLong("ts", System.currentTimeMillis())); break;
                }
                case "chat_ack":
                    listener.onChatAck(m.optString("id"), m.optString("status")); break;
                case "chat_edit_in":
                    listener.onChatEdit(
                            m.optString("from"),
                            m.optString("id"),
                            m.optString("ciphertext"),
                            m.optString("groupId", null),
                            m.optLong("ts", System.currentTimeMillis())); break;
                case "chat_delete_in":
                    listener.onChatDelete(
                            m.optString("from"),
                            m.optString("id"),
                            m.optString("groupId", null),
                            m.optLong("ts", System.currentTimeMillis())); break;
                case "chat_read_in": {
                    JSONArray arr = m.optJSONArray("ids");
                    java.util.List<String> ids = new java.util.ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            String s = arr.optString(i, null);
                            if (s != null && !s.isEmpty()) ids.add(s);
                        }
                    }
                    listener.onChatRead(
                            m.optString("from"),
                            ids,
                            m.optLong("ts", System.currentTimeMillis()));
                    break;
                }
                case "typing_in":
                    listener.onTyping(
                            m.optString("from"),
                            m.optBoolean("typing", false),
                            m.optString("groupId", null),
                            m.optLong("ts", System.currentTimeMillis())); break;
                case "presence_in":
                    listener.onPresence(
                            m.optString("user"),
                            m.optBoolean("online", false),
                            m.optLong("lastSeen", 0L)); break;
                case "contact_request_in":
                    listener.onContactRequest(
                            m.optString("from"),
                            m.optString("displayName", ""),
                            m.optLong("ts", System.currentTimeMillis())); break;
                case "contact_accept_in":
                    listener.onContactAccept(
                            m.optString("from"),
                            m.optString("displayName", ""),
                            m.optLong("ts", System.currentTimeMillis())); break;
                case "media_msg": {
                    JSONObject rt = m.optJSONObject("replyTo");
                    listener.onMediaMessage(
                            m.optString("from"),
                            m.optString("id"),
                            m.optString("url"),
                            m.optString("mediaType", "image/jpeg"),
                            m.optInt("width", 0),
                            m.optInt("height", 0),
                            m.optString("thumbnail", null),
                            m.optString("caption", null),
                            m.optInt("durationMs", 0),
                            rt != null ? rt.optString("id", null) : null,
                            rt != null ? rt.optString("previewCt", null) : null,
                            m.optBoolean("forwarded", false),
                            m.optString("groupId", null),
                            m.optLong("ts", System.currentTimeMillis())); break;
                }
                case "media_ack":
                    listener.onMediaAck(m.optString("id"), m.optString("status")); break;
                case "reaction_msg":
                    listener.onReaction(
                            m.optString("from"),
                            m.optString("targetId"),
                            m.optString("emoji", ""),
                            m.optString("groupId", null),
                            m.optLong("ts", System.currentTimeMillis())); break;
                case "profile_msg":
                    listener.onProfileMessage(
                            m.optString("from"),
                            m.optString("payloadCt"),
                            m.optLong("version", 0L),
                            m.optLong("ts", System.currentTimeMillis())); break;
                case "group_event_msg":
                    listener.onGroupEvent(
                            m.optString("from"),
                            m.optString("groupId"),
                            m.optString("event", ""),
                            m.optString("payloadCt", null),
                            m.optLong("ts", System.currentTimeMillis())); break;
                case "pubkey_response":
                    listener.onPeerPublicKey(
                            m.optString("peer"),
                            m.optString("publicKey", null),
                            m.optLong("version", 0L)); break;
                default:
                    Log.w(TAG, "unknown type: " + type);
            }
        } catch (Exception e) {
            Log.e(TAG, "parse error", e);
        }
    }

    // ---- outbound ----
    private void send(JSONObject o) {
        if (ws != null && ws.isOpen()) ws.send(o.toString());
    }

    public void register(String username) {
        try { send(new JSONObject().put("type", "register").put("username", username)); }
        catch (Exception ignored) {}
    }

    /** Tell the server our FCM device token so calls can wake the app via push. */
    public void sendFcmToken(String token) {
        try { send(new JSONObject().put("type", "fcm_token").put("token", token)); }
        catch (Exception ignored) {}
    }

    /** Send an encrypted chat message via the server. */
    public void sendChat(String to, String id, String ciphertext,
                         String replyToId, String replyToPreviewCt, boolean forwarded,
                         String groupId) {
        try {
            JSONObject o = new JSONObject()
                    .put("type", "chat_send")
                    .put("to", to)
                    .put("id", id)
                    .put("ciphertext", ciphertext);
            if (replyToId != null) {
                JSONObject rt = new JSONObject().put("id", replyToId);
                if (replyToPreviewCt != null) rt.put("previewCt", replyToPreviewCt);
                o.put("replyTo", rt);
            }
            if (forwarded) o.put("forwarded", true);
            if (groupId != null) o.put("groupId", groupId);
            send(o);
        } catch (Exception ignored) {}
    }

    /** Update the text of a previously-sent message. id matches the original
     *  message; ciphertext is the new encrypted plaintext. Server routes to
     *  the recipient(s) and queues if they're offline. */
    public void sendChatEdit(String to, String id, String ciphertext, String groupId) {
        try {
            JSONObject o = new JSONObject()
                    .put("type", "chat_edit")
                    .put("to", to)
                    .put("id", id)
                    .put("ciphertext", ciphertext);
            if (groupId != null) o.put("groupId", groupId);
            send(o);
        } catch (Exception ignored) {}
    }

    /** Unsend a previously-sent message. The recipient(s) will replace
     *  the bubble's content with a "[message deleted]" placeholder. */
    public void sendChatDelete(String to, String id, String groupId) {
        try {
            JSONObject o = new JSONObject()
                    .put("type", "chat_delete")
                    .put("to", to)
                    .put("id", id);
            if (groupId != null) o.put("groupId", groupId);
            send(o);
        } catch (Exception ignored) {}
    }

    /** Report to the sender that we've read one or more of their messages.
     *  Batched: one event covers many ids. Server doesn't queue these. */
    public void sendChatRead(String to, java.util.List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (String id : ids) arr.put(id);
            JSONObject o = new JSONObject()
                    .put("type", "chat_read")
                    .put("to", to)
                    .put("ids", arr);
            send(o);
        } catch (Exception ignored) {}
    }

    /** Send a typing start/stop event to a peer (or to a group's recipient
     *  via fan-out at the hub layer). */
    public void sendTyping(String to, boolean typing, String groupId) {
        try {
            JSONObject o = new JSONObject()
                    .put("type", "typing")
                    .put("to", to)
                    .put("typing", typing);
            if (groupId != null) o.put("groupId", groupId);
            send(o);
        } catch (Exception ignored) {}
    }

    /** Subscribe to presence updates for a single peer. The server replies
     *  immediately with current state and pushes further updates. */
    public void subscribePresence(String user) {
        try { send(new JSONObject().put("type", "presence_subscribe").put("user", user)); }
        catch (Exception ignored) {}
    }

    public void unsubscribePresence(String user) {
        try { send(new JSONObject().put("type", "presence_unsubscribe").put("user", user)); }
        catch (Exception ignored) {}
    }

    /** Request to be added to a peer's contact list. They'll see Accept/Decline. */
    public void sendContactRequest(String to, String displayName) {
        try {
            JSONObject o = new JSONObject().put("type", "contact_request").put("to", to);
            if (displayName != null && !displayName.isEmpty()) o.put("displayName", displayName);
            send(o);
        } catch (Exception ignored) {}
    }

    /** Inform the original requester that we've accepted their contact request. */
    public void sendContactAccept(String to, String displayName) {
        try {
            JSONObject o = new JSONObject().put("type", "contact_accept").put("to", to);
            if (displayName != null && !displayName.isEmpty()) o.put("displayName", displayName);
            send(o);
        } catch (Exception ignored) {}
    }

    /** Send a media (image/audio) reference. The actual bytes live at `url`
     *  on the signaling server; the server only relays the metadata. */
    public void sendMedia(String to, String id, String url, String mediaType,
                          int width, int height, String thumbnailB64, String captionCt,
                          int durationMs,
                          String replyToId, String replyToPreviewCt, boolean forwarded,
                          String groupId) {
        try {
            JSONObject o = new JSONObject()
                    .put("type", "media_send")
                    .put("to", to)
                    .put("id", id)
                    .put("url", url)
                    .put("mediaType", mediaType)
                    .put("width", width)
                    .put("height", height);
            if (thumbnailB64 != null) o.put("thumbnail", thumbnailB64);
            if (captionCt    != null) o.put("caption", captionCt);
            if (durationMs   > 0)     o.put("durationMs", durationMs);
            if (replyToId != null) {
                JSONObject rt = new JSONObject().put("id", replyToId);
                if (replyToPreviewCt != null) rt.put("previewCt", replyToPreviewCt);
                o.put("replyTo", rt);
            }
            if (forwarded) o.put("forwarded", true);
            if (groupId != null) o.put("groupId", groupId);
            send(o);
        } catch (Exception ignored) {}
    }

    /** Send a group roster/membership/key event to a single peer. payloadCt
     *  is opaque to the server (encrypted with our pairwise key with `to`). */
    public void sendGroupEvent(String to, String groupId, String event, String payloadCt) {
        try {
            JSONObject o = new JSONObject()
                    .put("type", "group_event_send")
                    .put("to", to)
                    .put("groupId", groupId)
                    .put("event", event);
            if (payloadCt != null) o.put("payloadCt", payloadCt);
            send(o);
        } catch (Exception ignored) {}
    }

    /** Add/remove a reaction on a previously-sent message. Empty emoji = remove.
     *  For group reactions, the sender fans this method out across all members
     *  with `groupId` set so each can route the update to the right thread. */
    public void sendReaction(String to, String targetId, String emoji, String groupId) {
        try {
            org.json.JSONObject o = new org.json.JSONObject()
                    .put("type", "reaction_send")
                    .put("to", to)
                    .put("targetId", targetId)
                    .put("emoji", emoji == null ? "" : emoji);
            if (groupId != null) o.put("groupId", groupId);
            send(o);
        } catch (Exception ignored) {}
    }

    /** Push our updated profile (display name + avatar metadata, already
     *  encrypted with the per-conversation key) to a single peer. The server
     *  relays it as a profile_msg and queues for offline recipients. */
    public void sendProfile(String to, String payloadCt, long version) {
        try {
            send(new JSONObject()
                    .put("type", "profile_send")
                    .put("to", to)
                    .put("payloadCt", payloadCt)
                    .put("version", version));
        } catch (Exception ignored) {}
    }

    /** Upload this device's X25519 public key + version to the server. */
    public void uploadPublicKey(String publicKeyB64, long version) {
        try {
            send(new JSONObject()
                    .put("type", "pubkey_upload")
                    .put("publicKey", publicKeyB64)
                    .put("version", version));
        } catch (Exception ignored) {}
    }

    /** Ask the server for a peer's public key. Reply arrives as onPeerPublicKey. */
    public void requestPublicKey(String peer) {
        try { send(new JSONObject().put("type", "pubkey_request").put("peer", peer)); }
        catch (Exception ignored) {}
    }

    public void checkPresence(String username) {
        try { send(new JSONObject().put("type", "presence").put("username", username)); }
        catch (Exception ignored) {}
    }

    public void call(String to) {
        call(to, false);
    }

    public void call(String to, boolean isVideo) {
        try {
            JSONObject o = new JSONObject().put("type", "call").put("to", to);
            if (isVideo) o.put("isVideo", true);
            send(o);
        } catch (Exception ignored) {}
    }

    public void accept(String callId) {
        try { send(new JSONObject().put("type", "accept").put("callId", callId)); }
        catch (Exception ignored) {}
    }

    public void reject(String callId) {
        try { send(new JSONObject().put("type", "reject").put("callId", callId)); }
        catch (Exception ignored) {}
    }

    public void hangup(String callId) {
        try { send(new JSONObject().put("type", "hangup").put("callId", callId)); }
        catch (Exception ignored) {}
    }

    public void sendRaw(JSONObject o) { send(o); }

    public boolean isOpen() { return ws != null && ws.isOpen(); }

    public void close() { if (ws != null) ws.close(); }
}
