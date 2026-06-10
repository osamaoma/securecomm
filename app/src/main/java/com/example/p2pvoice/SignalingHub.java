package com.example.p2pvoice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-wide singleton that owns ONE persistent signaling connection so the user
 * can receive incoming calls at any time. It also drives the active call's
 * RtcEngine. The Activity attaches/detaches a UI listener as it comes and goes.
 */
public class SignalingHub {

    private static final String TAG = "SignalingHub";
    private static SignalingHub INSTANCE;

    public static synchronized SignalingHub get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new SignalingHub(ctx.getApplicationContext());
        return INSTANCE;
    }

    public interface UiListener {
        void onConnectionState(String state);
        void onRegistered(String username);
        void onRegisterError(String reason);
        void onIncomingCall(String from, String callId, boolean isVideo);
        void onOutgoingRinging(String to);
        void onCallConnected(String peer);
        void onCallEnded(String reason);
    }

    /** Separate listener for chat events so screens can subscribe independently
     *  of the main call UI. Multiple chat listeners can be attached. */
    public interface ChatListener {
        void onChatMessage(ChatMessage msg);
        void onChatStatus(String peer, String id, ChatMessage.Status status);
        /** A peer's public key changed since the last cached one. Listeners
         *  should warn the user to re-verify the security code. Default no-op
         *  so existing implementations don't need to change. */
        default void onPeerKeyRotated(String peer) {}
        /** A peer's public key was (re)fetched. Listeners that display
         *  encrypted content should re-attempt decryption (some bubbles may
         *  have rendered empty because the previous local copy of the key was
         *  stale). */
        default void onPeerKeyRefreshed(String peer) {}
        /** A reaction was added (emoji != "") or removed (emoji == "") for
         *  the message with id `targetId`. The listener should refresh that row. */
        default void onReactionUpdated(String peer, String targetId) {}
        /** A peer's profile (display name and/or avatar) changed. Listeners
         *  that show contact info should refresh — the new fields are
         *  available via Store.getPeerProfile. */
        default void onPeerProfileUpdated(String peer) {}
        /** A group's roster/name/key was created or updated. */
        default void onGroupChanged(String groupId) {}
        /** A previously-delivered message was edited or deleted by its
         *  sender. Listeners should re-bind that row to reflect the new
         *  text / "edited" tag / "deleted" placeholder. */
        default void onChatMessageUpdated(ChatMessage msg) {}
        /** Peer is currently typing in this conversation. For 1-on-1, `from`
         *  is the peer username and `groupPeerId` is null. For groups, `from`
         *  is the typer and `groupPeerId` is the group's peer-id ("group:UUID"). */
        default void onTypingChanged(String from, boolean typing, String groupPeerId) {}
        /** Online state for a watched peer changed. */
        default void onPresenceChanged(String peer, boolean online, long lastSeen) {}
        /** A pending contact request was added, removed, or refreshed.
         *  Listeners should re-render the pending banner. */
        default void onContactRequestsChanged() {}
    }

    private final java.util.Set<ChatListener> chatListeners =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public void addChatListener(ChatListener l)    { if (l != null) chatListeners.add(l); }
    public void removeChatListener(ChatListener l) { chatListeners.remove(l); }

    private final Context ctx;
    private Store store;
    private final Handler main = new Handler(Looper.getMainLooper());
    // All WebRTC/native calls MUST run on this single thread. libjingle's native
    // layer is not safe to call from arbitrary threads (e.g. the WebSocket thread);
    // doing so causes a native SIGABRT that crashes the whole process.
    private final ExecutorService rtcExec = Executors.newSingleThreadExecutor();
    private void rtcPost(Runnable r) { rtcExec.execute(r); }

    private SignalingClient signaling;
    private RtcEngine rtc;
    private KeyManager keys;   // lazily built when we know our username

    // Outgoing messages composed while offline; replayed in onRegistered.
    private final java.util.List<ChatMessage> pendingOutgoing =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    // Outgoing messages waiting for a peer's public key to arrive so we can
    // encrypt them with the proper per-conversation key (rather than legacy).
    // Key: peer username, value: list of messages destined to them.
    private final java.util.Map<String, java.util.List<ChatMessage>> waitingForKey =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Incoming messages we couldn't decrypt (cached key may be stale because
    // the peer rotated). Held briefly until a fresh pubkey arrives.
    // Each entry is {id, from, ciphertext, ts}. Capped per-peer.
    private final java.util.Map<String, java.util.List<Object[]>> undecryptedIncoming =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_UNDECRYPTED_PER_PEER = 20;
    private UiListener ui;

    private boolean registered = false;
    private String activeCallId = null;
    private String activePeer = null;
    private boolean inCall = false;
    // For the call log: was this call outgoing or incoming, when did it start
    // ringing, and when did the media actually connect.
    private boolean activeIsOutgoing = false;
    private long activeRingStartMs = 0;
    /** Whether the active call has a video stream. Set when the call is
     *  placed (caller) or when incoming_call arrives (callee). */
    private boolean activeIsVideo = false;
    private long activeConnectedMs = 0;

    private SignalingHub(Context ctx) {
        this.ctx = ctx;
        this.store = new Store(ctx);
    }

    /** Build (or rebuild) the KeyManager. Idempotent. Safe to call as soon as
     *  the username is known. */
    private void ensureKeys() {
        if (keys != null) return;
        String me = store.getUsername();
        if (me == null || me.isEmpty()) return;
        keys = new KeyManager(ctx, me);
    }

    public void setUiListener(UiListener l) { this.ui = l; }
    public boolean isRegistered() { return registered; }
    public boolean isInCall() { return inCall; }
    public String getActivePeer() { return activePeer; }

    private void post(Runnable r) { main.post(r); }

    // ------------------------------------------------------------------
    // Connection + registration
    // ------------------------------------------------------------------
    public void connectAndRegister() {
        if (signaling != null && signaling.isOpen() && registered) {
            if (ui != null) post(() -> ui.onRegistered(store.getUsername()));
            return;
        }
        if (signaling != null) signaling.close();

        signaling = new SignalingClient(store.getServer(), new SignalingClient.Listener() {
            @Override public void onOpen() {
                if (ui != null) post(() -> ui.onConnectionState("connected"));
                signaling.register(store.getUsername());
            }
            @Override public void onRegistered(String username) {
                registered = true;
                // Lazy-init the KeyManager now that we know our username, and
                // upload our public key so peers can fetch it.
                ensureKeys();
                if (keys != null && signaling != null) {
                    try { signaling.uploadPublicKey(keys.myPublicKeyB64(), keys.myKeyVersion()); }
                    catch (Throwable ignored) {}
                }
                // If we already have an FCM token, share it now so the server
                // can wake this device for incoming calls when the app is closed.
                String tok = store.getFcmToken();
                if (tok != null && signaling != null) {
                    try { signaling.sendFcmToken(tok); } catch (Throwable ignored) {}
                }
                // Drain any messages composed while we were offline.
                replayPendingOutgoing();
                // Re-send any contact requests whose first send was lost
                // (e.g. the user tapped "Send Request" while the WebSocket
                // wasn't ready yet). These remain in outgoing pending until
                // the recipient accepts, so it's safe to resend on every
                // registration — the recipient's client dedups by `from`.
                replayPendingContactRequests();
                replayPendingLeaves();
                if (ui != null) post(() -> ui.onRegistered(username));
            }
            @Override public void onRegisterError(String reason) {
                registered = false;
                if (ui != null) post(() -> ui.onRegisterError(reason));
            }
            @Override public void onPresence(String username, boolean online) { }

            @Override public void onIncomingCall(String from, String callId, boolean isVideo) {
                if (store.isBlocked(from)) {
                    // Same wire-level rejection as "busy" — the caller just
                    // sees their call end, no special leak that they're blocked.
                    Log.d(TAG, "auto-rejecting call from blocked sender " + from);
                    signaling.reject(callId);
                    return;
                }
                if (inCall) { signaling.reject(callId); return; } // busy
                activeCallId = callId;
                activePeer = from;
                activeIsOutgoing = false;
                activeIsVideo = isVideo;
                activeRingStartMs = System.currentTimeMillis();
                activeConnectedMs = 0;
                // Don't start the microphone foreground service yet — wait until the
                // user accepts (and mic permission is confirmed) in startMedia().

                // Post the heads-up + lockscreen notification with
                // Answer / Decline action buttons. The notification's
                // fullScreenIntent points at CallActivity, so locked devices
                // still get the immersive call-takeover experience.
                try {
                    IncomingCallNotifier.show(ctx, from, callId, isVideo);
                } catch (Throwable t) {
                    Log.e(TAG, "failed to post incoming-call notification", t);
                }
                if (ui != null) post(() -> ui.onIncomingCall(from, callId, isVideo));
            }
            @Override public void onCalling(String to, String callId) {
                activeCallId = callId;
                activePeer = to;
                activeIsOutgoing = true;
                activeRingStartMs = System.currentTimeMillis();
                activeConnectedMs = 0;
                if (ui != null) post(() -> ui.onOutgoingRinging(to));
            }
            @Override public void onCallAccepted(String callId, boolean initiator, boolean isVideo) {
                // Server's isVideo is authoritative here — but we already had
                // it set client-side when the call was placed/received. Use
                // logical OR so the side that initiated as video keeps it.
                activeIsVideo = activeIsVideo || isVideo;
                startMedia(initiator);
            }
            @Override public void onCallRejected(String callId) {
                endLocal("rejected");
            }
            @Override public void onCallFailed(String to, String reason) {
                endLocal(reason);
            }
            @Override public void onCallEnded(String callId) {
                endLocal("ended");
            }

            @Override public void onOffer(String callId, JSONObject sdp) {
                if (rtc == null) return;
                final String desc;
                try { desc = sdp.getString("sdp"); } catch (Exception e) { Log.e(TAG, "offer", e); return; }
                rtcPost(() -> {
                    if (rtc == null) return;
                    try {
                        rtc.setRemoteDescription(new SessionDescription(
                                SessionDescription.Type.OFFER, desc));
                        rtc.createAnswer();
                    } catch (Throwable t) { Log.e(TAG, "offer apply", t); }
                });
            }
            @Override public void onAnswer(String callId, JSONObject sdp) {
                if (rtc == null) return;
                final String desc;
                try { desc = sdp.getString("sdp"); } catch (Exception e) { Log.e(TAG, "answer", e); return; }
                rtcPost(() -> {
                    if (rtc == null) return;
                    try {
                        rtc.setRemoteDescription(new SessionDescription(
                                SessionDescription.Type.ANSWER, desc));
                    } catch (Throwable t) { Log.e(TAG, "answer apply", t); }
                });
            }
            @Override public void onCandidate(String callId, JSONObject c) {
                if (rtc == null) return;
                final String mid, cand; final int idx;
                try {
                    mid = c.getString("sdpMid");
                    idx = c.getInt("sdpMLineIndex");
                    cand = c.getString("candidate");
                } catch (Exception e) { Log.e(TAG, "candidate", e); return; }
                rtcPost(() -> {
                    if (rtc == null) return;
                    try {
                        rtc.addIceCandidate(new IceCandidate(mid, idx, cand));
                    } catch (Throwable t) { Log.e(TAG, "candidate apply", t); }
                });
            }

            @Override public void onChatMessage(String from, String id, String ciphertext,
                                                 String replyToId, String replyToPreviewCt,
                                                 boolean forwarded, String groupId, long ts) {
                // Blocked-sender check first: dropped silently for 1-on-1
                // messages. We still route group messages even from blocked
                // users, because blocking a member doesn't apply inside a
                // group (you'd leave the group instead).
                if ((groupId == null || groupId.isEmpty()) && store.isBlocked(from)) {
                    Log.d(TAG, "dropping chat from blocked sender " + from);
                    return;
                }
                // Group message? Route through the group decrypt path instead.
                if (groupId != null && !groupId.isEmpty()) {
                    handleGroupChatMessage(from, id, ciphertext, replyToId, replyToPreviewCt,
                            forwarded, groupId, ts);
                    return;
                }
                ensureKeys();
                // Try the per-conversation key first; if it fails, try the
                // legacy key. This lets new clients still receive messages
                // from older clients during rollout.
                String text = null;
                byte[] usedKey = null;
                if (keys != null) {
                    byte[] convKey = keys.conversationKey(from);
                    if (convKey != null) {
                        text = MessageCrypto.decrypt(convKey, ciphertext);
                        if (text != null) usedKey = convKey;
                    }
                    if (text == null) {
                        text = MessageCrypto.decrypt(keys.legacyKey(), ciphertext);
                        if (text != null) usedKey = keys.legacyKey();
                    }
                }
                if (text == null) {
                    Log.w(TAG, "chat decrypt failed from " + from
                            + " (key " + (keys != null && keys.hasPeerKey(from) ? "present" : "missing") + ")"
                            + " — refetching pubkey and buffering");
                    bufferUndecrypted(from, id, ciphertext, replyToId, replyToPreviewCt, forwarded, ts);
                    if (signaling != null) {
                        try { signaling.requestPublicKey(from); } catch (Throwable ignored) {}
                    }
                    return;
                }
                // Decrypt the reply preview with the same key we used for the body.
                String replyPreview = null;
                if (replyToPreviewCt != null && !replyToPreviewCt.isEmpty() && usedKey != null) {
                    replyPreview = MessageCrypto.decrypt(usedKey, replyToPreviewCt);
                }
                deliverDecrypted(from, id, text, replyToId, replyPreview, forwarded, ts);
            }

            private void bufferUndecrypted(String from, String id, String ciphertext,
                                           String replyToId, String replyToPreviewCt,
                                           boolean forwarded, long ts) {
                java.util.List<Object[]> list = undecryptedIncoming.computeIfAbsent(
                        from.toLowerCase(),
                        k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
                list.add(new Object[]{id, ciphertext, ts, replyToId, replyToPreviewCt, forwarded});
                while (list.size() > MAX_UNDECRYPTED_PER_PEER) list.remove(0);
            }

            private void deliverDecrypted(String from, String id, String text,
                                          String replyToId, String replyPreview,
                                          boolean forwarded, long ts) {
                ChatMessage m = new ChatMessage(
                        id, from, ChatMessage.Direction.INCOMING, text, ts,
                        ChatMessage.Status.DELIVERED);
                m.kind = RichContent.kindOf(text);
                m.replyToId = replyToId;
                m.replyToPreview = replyPreview;
                m.forwarded = forwarded;
                store.putMessage(m);
                store.incrementUnread(from);
                if (chatListeners.isEmpty()) {
                    // The notification preview is more user-friendly than the
                    // raw sentinel JSON. Replace it for richer message kinds.
                    String preview = previewForNotification(m, text);
                    if (!store.isMuted(from)) {
                        ChatNotifier.show(ctx, from, preview);
                    }
                }
                for (ChatListener l : chatListeners) {
                    post(() -> l.onChatMessage(m));
                }
            }

            private String previewForNotification(ChatMessage m, String text) {
                switch (m.kind) {
                    case CONTACT:  return "\uD83D\uDC64 Contact card";
                    case LOCATION: return "\uD83D\uDCCD Location";
                    default:       return text;
                }
            }

            @Override public void onChatAck(String id, String status) {
                // Server sends one ack per recipient; for a group fan-out we
                // therefore receive N acks for the same id. updateChatStatus
                // is idempotent and monotonic so this is safe.
                ChatMessage.Status st = "sent".equals(status)
                        ? ChatMessage.Status.SENT
                        : ChatMessage.Status.DELIVERED;
                updateChatStatus(id, st);
                // Media tracks separately because the upload pipeline already
                // populates outgoingMedia at a different point in the flow.
                updateMediaStatus(id, st);
            }

            @Override public void onChatEdit(String from, String id, String ciphertext,
                                             String groupId, long ts) {
                // Same blocked-sender guard as regular chat messages: drop
                // silently for 1-on-1 from blocked users.
                if ((groupId == null || groupId.isEmpty()) && store.isBlocked(from)) return;
                ensureKeys();
                // Pick the right key: group key for groups, conversation key
                // (with legacy fallback) for 1-on-1.
                byte[] key;
                String peerId;
                if (groupId != null && !groupId.isEmpty()) {
                    Group g = store.getGroup(groupId);
                    if (g == null) return;
                    key = g.groupKey;
                    peerId = g.peerId();
                } else {
                    byte[] conv = keys != null ? keys.keyFor(from) : null;
                    byte[] leg  = keys != null ? keys.legacyKey() : null;
                    key = conv != null ? conv : leg;
                    peerId = from;
                }
                if (key == null) { Log.w(TAG, "edit: no key for " + from); return; }
                String newText;
                try { newText = MessageCrypto.decrypt(key, ciphertext); }
                catch (Throwable t) { Log.w(TAG, "edit decrypt failed", t); return; }
                if (newText == null) return;
                // Look up the original message in the conversation history,
                // update its text + editedAt, persist, notify listeners. If
                // the original doesn't exist locally (rare — original was
                // never delivered or was already cleared), drop the edit.
                ChatMessage existing = findMessage(peerId, id);
                if (existing == null) {
                    Log.d(TAG, "edit for unknown msg " + id + " — dropped");
                    return;
                }
                if (existing.deleted) return; // can't edit a deleted message
                existing.text = newText;
                existing.editedAt = ts > 0 ? ts : System.currentTimeMillis();
                // Re-stamp kind in case the edit changed the sentinel
                // (e.g. plain text edited to a location share — exotic but
                // safe to handle).
                existing.kind = RichContent.kindOf(newText);
                store.putMessage(existing);
                for (ChatListener l : chatListeners) {
                    post(() -> l.onChatMessageUpdated(existing));
                }
            }

            @Override public void onChatDelete(String from, String id, String groupId, long ts) {
                if ((groupId == null || groupId.isEmpty()) && store.isBlocked(from)) return;
                String peerId = (groupId != null && !groupId.isEmpty())
                        ? "group:" + groupId : from;
                ChatMessage existing = findMessage(peerId, id);
                if (existing == null) {
                    // Original wasn't in our history — synthesise a deleted
                    // placeholder so the recipient sees the "[message
                    // deleted]" bubble in chronological position. ts comes
                    // from the server's wall-clock at delete time, which is
                    // close enough.
                    existing = new ChatMessage(id, peerId,
                            ChatMessage.Direction.INCOMING, "", ts,
                            ChatMessage.Status.DELIVERED);
                    if (groupId != null) {
                        existing.senderUsername = from;
                        existing.groupId = groupId;
                    }
                }
                existing.deleted = true;
                store.putMessage(existing);
                final ChatMessage updated = existing;
                for (ChatListener l : chatListeners) {
                    post(() -> l.onChatMessageUpdated(updated));
                }
            }

            @Override public void onChatRead(String from, java.util.List<String> ids, long ts) {
                if (store.isBlocked(from)) return;
                // Bump each acked outgoing message to READ. We only ever
                // step status forwards (PENDING -> SENT -> DELIVERED -> READ)
                // so this is safe to invoke even on already-read messages.
                for (String id : ids) {
                    updateChatStatus(id, ChatMessage.Status.READ);
                    updateMediaStatus(id, ChatMessage.Status.READ);
                }
            }

            @Override public void onTyping(String from, boolean typing, String groupId, long ts) {
                if ((groupId == null || groupId.isEmpty()) && store.isBlocked(from)) return;
                final String groupPeerId = groupId == null || groupId.isEmpty() ? null
                        : "group:" + groupId;
                for (ChatListener l : chatListeners) {
                    post(() -> l.onTypingChanged(from, typing, groupPeerId));
                }
            }

            @Override public void onPresence(String user, boolean online, long lastSeen) {
                // Cache so a chat opening after the initial subscribe-reply
                // can still surface presence without re-subscribing.
                presenceCache.put(user.toLowerCase(),
                        new long[]{ online ? 1L : 0L, lastSeen });
                for (ChatListener l : chatListeners) {
                    post(() -> l.onPresenceChanged(user, online, lastSeen));
                }
            }

            @Override public void onContactRequest(String from, String displayName, long ts) {
                // Drop silently if we've blocked the requester — they
                // shouldn't be able to nag us via contact requests either.
                if (from == null || from.isEmpty()) return;
                if (store.isBlocked(from)) {
                    Log.d(TAG, "dropping contact request from blocked user " + from);
                    return;
                }
                // Already on our contact list? Treat as a no-op — the
                // relationship already exists. Optionally we could mutually
                // re-accept to handle a reset on the other side; for v1 just
                // ignore.
                for (Contact c : store.getContacts()) {
                    if (c.username.equalsIgnoreCase(from)) {
                        Log.d(TAG, "contact_request for already-known contact " + from);
                        return;
                    }
                }
                // Mutual-pending shortcut: if WE had already requested THEM,
                // both sides clearly want this; auto-accept right away so the
                // user doesn't have to tap twice.
                if (store.hasOutgoingContactRequest(from)) {
                    store.removeOutgoingContactRequest(from);
                    Contact c = new Contact(from, displayName == null || displayName.isEmpty()
                            ? from : displayName);
                    store.addContact(c);
                    signaling.sendContactAccept(from, store.getMyDisplayName());
                    for (ChatListener l : chatListeners) {
                        post(() -> l.onContactRequestsChanged());
                    }
                    return;
                }
                store.putIncomingContactRequest(from, displayName, ts);
                // Notify so the user sees it even when not in MainActivity.
                ChatNotifier.show(ctx, "contactreq:" + from,
                        (displayName != null && !displayName.isEmpty() ? displayName : from)
                                + " wants to add you as a contact");
                for (ChatListener l : chatListeners) {
                    post(() -> l.onContactRequestsChanged());
                }
            }

            @Override public void onContactAccept(String from, String displayName, long ts) {
                if (from == null || from.isEmpty()) return;
                // Did we actually request this? If not, ignore — could be a
                // stale ack from a previous install, or someone trying to
                // bypass the request flow.
                if (!store.hasOutgoingContactRequest(from)) {
                    Log.d(TAG, "ignoring contact_accept we didn't request: " + from);
                    return;
                }
                store.removeOutgoingContactRequest(from);
                Contact c = new Contact(from, displayName == null || displayName.isEmpty()
                        ? from : displayName);
                store.addContact(c);
                ChatNotifier.show(ctx, "contactacc:" + from,
                        (displayName != null && !displayName.isEmpty() ? displayName : from)
                                + " accepted your contact request");
                for (ChatListener l : chatListeners) {
                    post(() -> l.onContactRequestsChanged());
                }
            }

            @Override public void onMediaMessage(String from, String id, String url, String mediaType,
                                                  int width, int height, String thumbnailB64,
                                                  String captionCt, int durationMs,
                                                  String replyToId, String replyToPreviewCt,
                                                  boolean forwarded, String groupId, long ts) {
                if ((groupId == null || groupId.isEmpty()) && store.isBlocked(from)) {
                    Log.d(TAG, "dropping media from blocked sender " + from);
                    return;
                }
                if (groupId != null && !groupId.isEmpty()) {
                    handleGroupMediaMessage(from, id, url, mediaType, width, height,
                            thumbnailB64, captionCt, durationMs, replyToId, replyToPreviewCt,
                            forwarded, groupId, ts);
                    return;
                }
                ensureKeys();
                // Decrypt the optional caption + reply preview with the conversation key.
                String captionText = "";
                byte[] usedKey = null;
                if (keys != null) {
                    byte[] k = keys.conversationKey(from);
                    if (captionCt != null && !captionCt.isEmpty()) {
                        if (k != null) {
                            captionText = MessageCrypto.decrypt(k, captionCt);
                            if (captionText != null) usedKey = k;
                        }
                        if (captionText == null) {
                            captionText = MessageCrypto.decrypt(keys.legacyKey(), captionCt);
                            if (captionText != null) usedKey = keys.legacyKey();
                        }
                        if (captionText == null) captionText = "";
                    } else {
                        usedKey = k != null ? k : keys.legacyKey();
                    }
                }
                String replyPreview = null;
                if (replyToPreviewCt != null && !replyToPreviewCt.isEmpty() && usedKey != null) {
                    replyPreview = MessageCrypto.decrypt(usedKey, replyToPreviewCt);
                }
                boolean isVoice = mediaType != null && mediaType.startsWith("audio/");
                boolean isImage = mediaType != null && mediaType.startsWith("image/");
                // The caption field carries the filename for documents (so the
                // recipient sees the original name from the sender's filesystem),
                // and the user-typed caption for images.
                ChatMessage m = new ChatMessage(
                        id, from, ChatMessage.Direction.INCOMING,
                        captionText, ts, ChatMessage.Status.DELIVERED);
                if (isVoice)       m.kind = ChatMessage.Kind.VOICE;
                else if (isImage)  m.kind = ChatMessage.Kind.IMAGE;
                else               m.kind = ChatMessage.Kind.DOCUMENT;
                if (m.kind == ChatMessage.Kind.DOCUMENT) {
                    m.fileName = captionText;
                    // For documents the wire `width` field carries the file size
                    // (bytes); see sendDocument. The recipient repurposes it.
                    m.fileSize = (long) width;
                    m.text = "";  // don't leak filename into adapter "text" rendering
                }
                m.mediaUrl = url;
                m.mediaType = mediaType;
                m.mediaWidth = width;
                m.mediaHeight = height;
                m.thumbnailB64 = thumbnailB64;
                m.durationMs = durationMs;
                m.replyToId = replyToId;
                m.replyToPreview = replyPreview;
                m.forwarded = forwarded;
                store.putMessage(m);
                store.incrementUnread(from);
                if (chatListeners.isEmpty()) {
                    String preview;
                    switch (m.kind) {
                        case VOICE:    preview = "\uD83C\uDFA4 Voice message"; break;
                        case DOCUMENT: preview = "\uD83D\uDCC4 " + (m.fileName == null ? "Document" : m.fileName); break;
                        default:       preview = captionText.isEmpty()
                                ? "\uD83D\uDCF7 Photo" : "\uD83D\uDCF7 " + captionText;
                    }
                    if (!store.isMuted(from)) {
                        ChatNotifier.show(ctx, from, preview);
                    }
                }
                for (ChatListener l : chatListeners) {
                    post(() -> l.onChatMessage(m));
                }
            }

            @Override public void onMediaAck(String id, String status) {
                ChatMessage.Status st = "sent".equals(status)
                        ? ChatMessage.Status.SENT
                        : ChatMessage.Status.DELIVERED;
                updateMediaStatus(id, st);
            }

            @Override public void onReaction(String from, String targetId, String emoji,
                                              String groupId, long ts) {
                handleIncomingReaction(from, targetId, emoji, groupId);
            }

            @Override public void onProfileMessage(String from, String payloadCt,
                                                    long version, long ts) {
                handleIncomingProfile(from, payloadCt, version);
            }

            @Override public void onGroupEvent(String from, String groupId, String event,
                                                String payloadCt, long ts) {
                handleGroupEvent(from, groupId, event, payloadCt);
            }

            @Override public void onPeerPublicKey(String peer, String publicKeyB64, long version) {
                ensureKeys();
                if (keys == null) return;
                if (publicKeyB64 == null || publicKeyB64.isEmpty()) {
                    Log.w(TAG, "no public key on file for " + peer);
                    return;
                }
                KeyManager.StoreResult result = keys.putPeerPublicKey(peer, publicKeyB64, version);
                if (result == KeyManager.StoreResult.ROTATED) {
                    // Notify any chat screen for this peer so it can warn the user.
                    for (ChatListener l : chatListeners) {
                        post(() -> l.onPeerKeyRotated(peer));
                    }
                }
                // Always notify "refreshed" so UIs can retry decryption of any
                // content that previously failed (e.g. image thumbnails that
                // rendered empty because of a stale local key).
                for (ChatListener l : chatListeners) {
                    post(() -> l.onPeerKeyRefreshed(peer));
                }
                // Drain any messages we held back waiting for this key.
                java.util.List<ChatMessage> waiting = waitingForKey.remove(peer.toLowerCase());
                if (waiting != null) {
                    for (ChatMessage m : waiting) {
                        try {
                            byte[] k = keys.keyFor(peer);
                            String ct = MessageCrypto.encrypt(k, m.text);
                            String replyCt = null;
                            if (m.replyToPreview != null) {
                                replyCt = MessageCrypto.encrypt(k, m.replyToPreview);
                            }
                            if (signaling != null) {
                                signaling.sendChat(peer, m.id, ct,
                                        m.replyToId, replyCt, m.forwarded, m.groupId);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "drain failed for " + peer, t);
                        }
                    }
                }
                // Replay any undecryptable incoming messages we held back.
                drainUndecryptedFor(peer);
            }

            @Override public void onForceLogout(String reason) {
                registered = false;
                endLocal("logged_out");
                if (ui != null) post(() -> ui.onConnectionState("logged out (used elsewhere)"));
            }
            @Override public void onClosed() {
                registered = false;
                if (ui != null) post(() -> ui.onConnectionState("disconnected"));
            }
            @Override public void onError(String message) {
                if (ui != null) post(() -> ui.onConnectionState("error: " + message));
            }
        });
    }

    // ------------------------------------------------------------------
    // Call control (from UI)
    // ------------------------------------------------------------------
    public void placeCall(String username) {
        placeCall(username, false);
    }

    public void placeCall(String username, boolean isVideo) {
        if (signaling == null || !registered) return;
        activePeer = username;
        activeIsVideo = isVideo;
        signaling.call(username, isVideo);
    }

    /** Called by PushService when FCM hands us a fresh device token. */
    public void publishFcmToken(String token) {
        if (token == null) return;
        if (signaling != null && registered) {
            try { signaling.sendFcmToken(token); } catch (Throwable ignored) {}
        }
        // If not connected yet, the token is in Store and will be sent on the
        // next successful registration (see onRegistered above).
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    // Outgoing chat messages keyed by id, so we can update status on chat_ack.
    // Populated in sendChatMessage / sendGroupChatMessage; entry is removed
    // once the message reaches its terminal DELIVERED status. Needed because
    // the wire ack carries only the message id, not the peer it belongs to,
    // and the persisted store may have hundreds of messages across N peers.
    private final java.util.Map<String, ChatMessage> outgoingChat =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void updateChatStatus(String id, ChatMessage.Status st) {
        ChatMessage m = outgoingChat.get(id);
        if (m == null) {
            // Already evicted from the in-memory cache (we evict on DELIVERED).
            // Read receipts arriving later need a store-side lookup.
            if (st == ChatMessage.Status.READ) {
                m = findOutgoingById(id);
                if (m == null) return;
            } else {
                return;
            }
        }
        // Group fan-outs produce one ack per recipient. Keep monotonically
        // advancing rather than letting a late "sent" overwrite a "delivered".
        if (rankStatus(st) <= rankStatus(m.status)) return;
        m.status = st;
        store.putMessage(m);
        final ChatMessage finalM = m;
        for (ChatListener l : chatListeners) {
            post(() -> l.onChatStatus(finalM.peer, id, st));
        }
        if (st == ChatMessage.Status.DELIVERED) outgoingChat.remove(id);
        if (st == ChatMessage.Status.READ) outgoingChat.remove(id);
    }

    private static int rankStatus(ChatMessage.Status s) {
        if (s == null) return 0;
        switch (s) {
            case PENDING:   return 0;
            case SENT:      return 1;
            case DELIVERED: return 2;
            case READ:      return 3;
            case FAILED:    return -1; // terminal-bad, don't auto-progress over it
            default:        return 0;
        }
    }

    /** Find an outgoing message we sent by its id, searching every contact +
     *  group conversation. Used by read-receipt processing after the message
     *  has aged out of the in-memory outgoingChat cache. */
    private ChatMessage findOutgoingById(String id) {
        // Search all 1-on-1 conversations first.
        for (Contact c : store.getContacts()) {
            for (ChatMessage m : store.getMessages(c.username)) {
                if (id.equals(m.id) && m.direction == ChatMessage.Direction.OUTGOING) return m;
            }
        }
        // Then groups.
        for (Group g : store.getGroups()) {
            for (ChatMessage m : store.getMessages(g.peerId())) {
                if (id.equals(m.id) && m.direction == ChatMessage.Direction.OUTGOING) return m;
            }
        }
        return null;
    }

    // Outgoing media messages keyed by id, so we can update status on media_ack.
    private final java.util.Map<String, ChatMessage> outgoingMedia =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Cached presence state per peer username (lowercase). Each entry is
    // [online (0/1), lastSeen ms]. Populated by onPresence; cleared on
    // unsubscribe is unnecessary — the cache is small and harmless to keep.
    private final java.util.Map<String, long[]> presenceCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void updateMediaStatus(String id, ChatMessage.Status st) {
        ChatMessage m = outgoingMedia.get(id);
        if (m == null) {
            if (st == ChatMessage.Status.READ) {
                m = findOutgoingById(id);
                if (m == null) return;
            } else {
                return;
            }
        }
        if (rankStatus(st) <= rankStatus(m.status)) return;
        m.status = st;
        store.putMessage(m);
        final ChatMessage finalM = m;
        for (ChatListener l : chatListeners) {
            post(() -> l.onChatStatus(finalM.peer, id, st));
        }
        if (st == ChatMessage.Status.DELIVERED || st == ChatMessage.Status.READ) {
            outgoingMedia.remove(id);
        }
    }

    /**
     * Send an image. The image bytes are fetched from `sourceUri`, resized,
     * encrypted with the per-conversation key, and uploaded to Firebase
     * Storage. Then a signaling message points the recipient at the blob.
     *
     * Returns the outgoing ChatMessage immediately with PENDING status; the
     * status moves through SENT → DELIVERED as the upload+relay completes.
     * Listeners receive onChatStatus updates on the main thread.
     */
    public ChatMessage sendImage(android.net.Uri sourceUri, String peer, String caption) {
        return sendImage(sourceUri, peer, caption, false);
    }

    public ChatMessage sendImage(android.net.Uri sourceUri, String peer, String caption,
                                 boolean forwarded) {
        if (Group.isGroupPeerId(peer)) {
            return sendImageToGroup(sourceUri, peer, caption);
        }
        ensureKeys();
        final String id = java.util.UUID.randomUUID().toString();
        final long ts = System.currentTimeMillis();

        // Persist the outgoing message immediately so the UI can show it
        // (with a "uploading…" indicator from the PENDING status).
        ChatMessage m = new ChatMessage(
                id, peer, ChatMessage.Direction.OUTGOING,
                caption == null ? "" : caption, ts, ChatMessage.Status.PENDING);
        m.kind = ChatMessage.Kind.IMAGE;
        m.mediaType = "image/jpeg";
        m.forwarded = forwarded;
        store.putMessage(m);
        outgoingMedia.put(id, m);

        // Resolve the conversation key (or legacy fallback). If a key fetch
        // is needed we still proceed — the upload doesn't depend on the key
        // being upgraded; the recipient will use whichever key matches.
        final byte[] convKey;
        if (keys != null && keys.hasPeerKey(peer)) {
            convKey = keys.conversationKey(peer);
        } else {
            convKey = keys != null ? keys.legacyKey() : null;
            // Kick a request so future messages use the proper key.
            if (signaling != null) {
                try { signaling.requestPublicKey(peer); } catch (Throwable ignored) {}
            }
        }
        if (convKey == null) {
            m.status = ChatMessage.Status.FAILED;
            store.putMessage(m);
            return m;
        }

        // Off-thread upload via MediaTransfer. We pass the same server URL the
        // signaling socket uses; MediaTransfer turns ws:// into http:// for the
        // /media/{id} endpoint.
        MediaTransfer.uploadImage(ctx, sourceUri, convKey, store.getServer(), new MediaTransfer.UploadCallback() {
            @Override public void onSuccess(MediaTransfer.UploadResult r) {
                m.mediaUrl = r.downloadUrl;
                m.mediaWidth = r.width;
                m.mediaHeight = r.height;
                m.thumbnailB64 = r.thumbnailB64;
                // Also stash a local copy so the sender can re-view their own
                // image without a round trip to the server.
                if (r.localCachePath != null) m.localPath = r.localCachePath;
                store.putMessage(m);

                String captionCt = null;
                if (caption != null && !caption.isEmpty()) {
                    try { captionCt = MessageCrypto.encrypt(convKey, caption); }
                    catch (Throwable t) { Log.w(TAG, "caption encrypt failed", t); }
                }

                if (signaling != null && registered) {
                    try {
                        signaling.sendMedia(peer, id, r.downloadUrl, "image/jpeg",
                                r.width, r.height, r.thumbnailB64, captionCt, 0,
                                null, null, forwarded, null);
                    } catch (Throwable t) {
                        Log.e(TAG, "media send failed", t);
                        m.status = ChatMessage.Status.FAILED;
                        store.putMessage(m);
                        for (ChatListener l : chatListeners) {
                            post(() -> l.onChatStatus(peer, id, ChatMessage.Status.FAILED));
                        }
                        return;
                    }
                }
                // PENDING → wait for the server's media_ack to bump us to SENT.
            }
            @Override public void onFailure(Throwable t) {
                Log.e(TAG, "image upload failed", t);
                m.status = ChatMessage.Status.FAILED;
                store.putMessage(m);
                outgoingMedia.remove(id);
                final String why = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                post(() -> android.widget.Toast.makeText(
                        ctx, "Image upload failed: " + why,
                        android.widget.Toast.LENGTH_LONG).show());
                for (ChatListener l : chatListeners) {
                    post(() -> l.onChatStatus(peer, id, ChatMessage.Status.FAILED));
                }
            }
        });
        return m;
    }

    /**
     * Send a voice message: encrypt the local audio file, upload to the
     * signaling server, and emit a media_send referencing it. Mirrors
     * sendImageMessage but with no thumbnail / dimensions / caption.
     *
     * @param audioFilePath  path to the .m4a recording produced by VoiceRecorder
     * @param durationMs     length of the recording, included in the signaling
     *                        envelope so recipients can show "0:12" before
     *                        downloading
     * @return  the persisted outgoing ChatMessage (PENDING until upload+ack)
     */
    public ChatMessage sendVoiceMessage(String peer, String audioFilePath, int durationMs) {
        return sendVoiceMessage(peer, audioFilePath, durationMs, false);
    }

    public ChatMessage sendVoiceMessage(String peer, String audioFilePath, int durationMs,
                                        boolean forwarded) {
        if (Group.isGroupPeerId(peer)) {
            return sendVoiceToGroup(peer, audioFilePath, durationMs);
        }
        ensureKeys();
        final String id = java.util.UUID.randomUUID().toString();
        final long ts = System.currentTimeMillis();

        ChatMessage m = new ChatMessage(
                id, peer, ChatMessage.Direction.OUTGOING,
                "", ts, ChatMessage.Status.PENDING);
        m.kind = ChatMessage.Kind.VOICE;
        m.mediaType = "audio/mp4";
        m.durationMs = durationMs;
        m.forwarded = forwarded;
        store.putMessage(m);
        outgoingMedia.put(id, m);

        // Resolve the conversation key (same rules as sendImageMessage).
        final byte[] convKey;
        if (keys != null && keys.hasPeerKey(peer)) {
            convKey = keys.conversationKey(peer);
        } else {
            convKey = keys != null ? keys.legacyKey() : null;
            if (signaling != null) {
                try { signaling.requestPublicKey(peer); } catch (Throwable ignored) {}
            }
        }
        if (convKey == null) {
            m.status = ChatMessage.Status.FAILED;
            store.putMessage(m);
            return m;
        }

        MediaTransfer.uploadAudio(ctx, audioFilePath, convKey, store.getServer(),
                new MediaTransfer.AudioUploadCallback() {
            @Override public void onSuccess(String downloadUrl, String localCachePath) {
                m.mediaUrl = downloadUrl;
                if (localCachePath != null) m.localPath = localCachePath;
                store.putMessage(m);

                if (signaling != null && registered) {
                    try {
                        signaling.sendMedia(peer, id, downloadUrl, "audio/mp4",
                                0, 0, null, null, durationMs,
                                null, null, forwarded, null);
                    } catch (Throwable t) {
                        Log.e(TAG, "voice send failed", t);
                        m.status = ChatMessage.Status.FAILED;
                        store.putMessage(m);
                        for (ChatListener l : chatListeners) {
                            post(() -> l.onChatStatus(peer, id, ChatMessage.Status.FAILED));
                        }
                    }
                }
                // Delete the source recording — we already cached the encrypted
                // copy locally for replay, and the ciphertext is on the server.
                try { new java.io.File(audioFilePath).delete(); } catch (Throwable ignored) {}
            }
            @Override public void onFailure(Throwable t) {
                Log.e(TAG, "voice upload failed", t);
                m.status = ChatMessage.Status.FAILED;
                store.putMessage(m);
                outgoingMedia.remove(id);
                final String why = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                post(() -> android.widget.Toast.makeText(
                        ctx, "Voice upload failed: " + why,
                        android.widget.Toast.LENGTH_LONG).show());
                for (ChatListener l : chatListeners) {
                    post(() -> l.onChatStatus(peer, id, ChatMessage.Status.FAILED));
                }
            }
        });
        return m;
    }

    /** Download + decrypt a voice message's audio into local cache. */
    public void downloadVoice(ChatMessage m, MediaTransfer.DownloadCallback cb) {
        ensureKeys();
        if (m.mediaUrl == null) { cb.onFailure(new RuntimeException("no url")); return; }
        final byte[] finalKey;
        if (Group.isGroupPeerId(m.peer)) {
            Group g = store.getGroup(Group.groupIdFromPeerId(m.peer));
            finalKey = g != null ? g.groupKey : null;
        } else {
            // Voice messages have no thumbnail probe, so we just try both keys
            // in sequence: try conv first; if that fails downstream, the
            // failure toast will explain.
            byte[] tryConv = keys != null ? keys.conversationKey(m.peer) : null;
            byte[] tryLeg  = keys != null ? keys.legacyKey() : null;
            finalKey = tryConv != null ? tryConv : tryLeg;
        }
        if (finalKey == null) { cb.onFailure(new RuntimeException("no key")); return; }

        MediaTransfer.downloadAudio(ctx, m.id, m.mediaUrl, finalKey,
                new MediaTransfer.DownloadCallback() {
            @Override public void onSuccess(String localPath) {
                m.localPath = localPath;
                store.putMessage(m);
                cb.onSuccess(localPath);
            }
            @Override public void onFailure(Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : "";
                if (msg.contains("decrypt")) {
                    requestPeerKey(m.peer);
                    cb.onFailure(new RuntimeException(
                            "Can't decrypt this voice message. The sender's encryption " +
                            "key changed since this was sent — ask them to send it again.", t));
                } else {
                    cb.onFailure(t);
                }
            }
        });
    }

    /**
     * Download + decrypt the full image for `m` into local cache. Returns the
     * resulting localPath via callback (on a background thread). The hub also
     * persists the localPath onto the message so subsequent loads skip the
     * network round-trip.
     */
    public void downloadImage(ChatMessage m, MediaTransfer.DownloadCallback cb) {
        ensureKeys();
        if (m.mediaUrl == null) { cb.onFailure(new RuntimeException("no url")); return; }
        byte[] key;
        if (Group.isGroupPeerId(m.peer)) {
            Group g = store.getGroup(Group.groupIdFromPeerId(m.peer));
            key = g != null ? g.groupKey : null;
        } else {
            // Decryption key follows the same dual-attempt rule the message side uses.
            // Try conversation key; if that fails, try legacy. We test by attempting
            // to decrypt the thumbnail first as a fast probe (cheaper than full DL).
            byte[] tryConv = keys != null ? keys.conversationKey(m.peer) : null;
            byte[] tryLeg  = keys != null ? keys.legacyKey() : null;
            key = null;
            if (m.thumbnailB64 != null) {
                if (tryConv != null && MediaTransfer.decryptThumbnail(tryConv, m.thumbnailB64) != null) {
                    key = tryConv;
                } else if (tryLeg != null && MediaTransfer.decryptThumbnail(tryLeg, m.thumbnailB64) != null) {
                    key = tryLeg;
                }
            }
            if (key == null) key = tryConv != null ? tryConv : tryLeg;
        }
        final byte[] finalKey = key;
        if (finalKey == null) { cb.onFailure(new RuntimeException("no key")); return; }

        MediaTransfer.downloadImage(ctx, m.id, m.mediaUrl, finalKey, new MediaTransfer.DownloadCallback() {
            @Override public void onSuccess(String localPath) {
                m.localPath = localPath;
                store.putMessage(m);
                cb.onSuccess(localPath);
            }
            @Override public void onFailure(Throwable t) {
                // If it's a decryption failure (rather than network), our local
                // copy of the peer's pubkey is probably stale. Refresh it so
                // future messages from this peer use the right key. We surface
                // a human-readable error to the user.
                String msg = t.getMessage() != null ? t.getMessage() : "";
                if (msg.contains("decrypt")) {
                    requestPeerKey(m.peer);
                    cb.onFailure(new RuntimeException(
                            "Can't decrypt this image. The sender's encryption " +
                            "key changed since this was sent — ask them to send it again.", t));
                } else {
                    cb.onFailure(t);
                }
            }
        });
    }

    /**
     * Encrypt and upload a document (any file) to the conversation. Same
     * pipeline as images, but no thumbnail and no resize. The filename
     * travels as the caption field so the recipient can save it under the
     * original name; the wire `width` field carries the byte size so the
     * UI can show "12 KB" without downloading the blob first.
     *
     * Works for both 1-on-1 (peer = username) and group (peer = group:UUID).
     */
    public ChatMessage sendDocument(android.net.Uri sourceUri, String peer,
                                    String fileName, String mediaType) {
        ensureKeys();
        boolean isGroup = Group.isGroupPeerId(peer);
        final byte[] convKey;
        final Group group;
        final String me = store.getUsername();
        if (isGroup) {
            group = store.getGroup(Group.groupIdFromPeerId(peer));
            convKey = group != null ? group.groupKey : null;
        } else {
            group = null;
            convKey = keys != null ? keys.keyFor(peer) : null;
        }
        if (convKey == null) {
            android.widget.Toast.makeText(ctx, "Can't send: no key for this conversation",
                    android.widget.Toast.LENGTH_LONG).show();
            return null;
        }

        final String id = java.util.UUID.randomUUID().toString();
        long ts = System.currentTimeMillis();
        final ChatMessage m = new ChatMessage(
                id, peer, ChatMessage.Direction.OUTGOING, "", ts,
                ChatMessage.Status.PENDING);
        m.kind = ChatMessage.Kind.DOCUMENT;
        m.fileName = fileName;
        m.mediaType = mediaType != null ? mediaType : "application/octet-stream";
        if (isGroup) {
            m.senderUsername = me;
            m.groupId = Group.groupIdFromPeerId(peer);
        }
        store.putMessage(m);
        outgoingMedia.put(id, m);

        final String captionForFilename;
        try {
            captionForFilename = MessageCrypto.encrypt(convKey, fileName == null ? "" : fileName);
        } catch (Throwable t) {
            Log.e(TAG, "doc filename encrypt failed", t);
            m.status = ChatMessage.Status.FAILED;
            store.putMessage(m);
            outgoingMedia.remove(id);
            return m;
        }

        MediaTransfer.uploadDocument(ctx, sourceUri, convKey, store.getServer(),
                new MediaTransfer.DocumentUploadCallback() {
            @Override public void onSuccess(String downloadUrl, long sizeBytes) {
                m.mediaUrl = downloadUrl;
                m.fileSize = sizeBytes;
                // Cap to int range — we already enforce 16MB in MediaTransfer
                // so this is safe, but defend against future changes.
                int sizeForWire = (int) Math.min(sizeBytes, Integer.MAX_VALUE);
                m.mediaWidth = sizeForWire;
                store.putMessage(m);

                if (signaling != null && registered) {
                    if (isGroup && group != null) {
                        for (String member : group.members) {
                            if (me != null && member.equalsIgnoreCase(me)) continue;
                            try {
                                signaling.sendMedia(member, id, downloadUrl, m.mediaType,
                                        sizeForWire, 0, null, captionForFilename, 0,
                                        null, null, false, group.id);
                            } catch (Throwable t) {
                                Log.w(TAG, "doc fanout to " + member + " failed", t);
                            }
                        }
                    } else {
                        try {
                            signaling.sendMedia(peer, id, downloadUrl, m.mediaType,
                                    sizeForWire, 0, null, captionForFilename, 0,
                                    null, null, false, null);
                        } catch (Throwable t) {
                            Log.e(TAG, "doc send failed", t);
                            m.status = ChatMessage.Status.FAILED;
                            store.putMessage(m);
                        }
                    }
                }
            }
            @Override public void onFailure(Throwable t) {
                Log.e(TAG, "doc upload failed", t);
                m.status = ChatMessage.Status.FAILED;
                store.putMessage(m);
                outgoingMedia.remove(id);
                post(() -> android.widget.Toast.makeText(ctx,
                        "Document upload failed: " + t.getMessage(),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
        return m;
    }

    /** Download + decrypt a previously-received document. */
    public void downloadDocument(ChatMessage m, MediaTransfer.DownloadCallback cb) {
        ensureKeys();
        if (m.mediaUrl == null) { cb.onFailure(new RuntimeException("no url")); return; }
        byte[] key;
        if (Group.isGroupPeerId(m.peer)) {
            Group g = store.getGroup(Group.groupIdFromPeerId(m.peer));
            key = g != null ? g.groupKey : null;
        } else {
            byte[] tryConv = keys != null ? keys.conversationKey(m.peer) : null;
            byte[] tryLeg  = keys != null ? keys.legacyKey() : null;
            key = tryConv != null ? tryConv : tryLeg;
        }
        if (key == null) { cb.onFailure(new RuntimeException("no key")); return; }
        MediaTransfer.downloadDocument(ctx, m.mediaUrl, key, m.fileName, cb);
    }

    /**
     * Encrypt and send a chat message to a peer. Returns the persisted
     * outgoing ChatMessage so the caller can display it immediately with
     * its PENDING/SENT status. If the connection isn't ready, the message
     * is queued and sent once registration succeeds.
     *
     * Encryption flow:
     *  - Use the per-conversation key (X25519 ECDH) if we have the peer's
     *    public key locally.
     *  - Otherwise: request the peer's public key from the server, queue the
     *    message in waitingForKey, and let onPeerPublicKey drain it.
     *  - Connection-not-ready is handled separately via pendingOutgoing.
     *
     * Optional fields:
     *  - replyToId / replyToPreview: when non-null, this message quotes another.
     *    The preview is encrypted with the same key as the body and sent
     *    alongside.
     *  - forwarded: marks this as a forwarded message; the recipient bubble
     *    will show a "↪ Forwarded" label.
     */
    public ChatMessage sendChatMessage(String peer, String text) {
        return sendChatMessage(peer, text, null, null, false);
    }

    public ChatMessage sendChatMessage(String peer, String text,
                                       String replyToId, String replyToPreview,
                                       boolean forwarded) {
        if (Group.isGroupPeerId(peer)) {
            return sendGroupChatMessage(peer, text, replyToId, replyToPreview, forwarded);
        }
        ensureKeys();
        String id = java.util.UUID.randomUUID().toString();
        long ts = System.currentTimeMillis();
        ChatMessage m = new ChatMessage(
                id, peer, ChatMessage.Direction.OUTGOING, text, ts,
                ChatMessage.Status.PENDING);
        m.replyToId = replyToId;
        m.replyToPreview = replyToPreview;
        m.forwarded = forwarded;
        store.putMessage(m);
        // Track until the server's chat_ack reflects a terminal status. This
        // is what flips the bubble from "⏳ pending" to "✓ sent" / "✓✓ delivered".
        outgoingChat.put(id, m);

        if (signaling == null || !registered) {
            // Not connected — queue and let onRegistered drain it.
            pendingOutgoing.add(m);
            try { connectAndRegister(); } catch (Throwable ignored) {}
            return m;
        }

        // Prefer the per-conversation key. If we don't have it, request it
        // and stash the message until the response arrives.
        if (keys != null && !keys.hasPeerKey(peer)) {
            try { signaling.requestPublicKey(peer); } catch (Throwable ignored) {}
            waitingForKey.computeIfAbsent(peer.toLowerCase(),
                    k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                    .add(m);
            return m;
        }

        try {
            byte[] k = keys != null ? keys.keyFor(peer) : null;
            if (k == null) throw new IllegalStateException("no key");
            String ct = MessageCrypto.encrypt(k, text);
            String replyCt = null;
            if (replyToPreview != null) {
                replyCt = MessageCrypto.encrypt(k, replyToPreview);
            }
            signaling.sendChat(peer, id, ct, replyToId, replyCt, forwarded, null);
        } catch (Throwable t) {
            Log.e(TAG, "chat send failed, queueing", t);
            pendingOutgoing.add(m);
        }
        return m;
    }

    /** Toggle a reaction by the local user on a previously-seen message.
     *  Pass empty/null emoji to remove the local user's reaction.
     *  Also persists the change to local history immediately so the UI updates
     *  without waiting for the (best-effort) server echo. */
    public void sendReaction(String peer, String targetId, String emoji) {
        ensureKeys();
        String me = store.getUsername();
        if (me == null || me.isEmpty()) return;
        ChatMessage target = findMessage(peer, targetId);
        if (target == null) return;
        if (emoji == null || emoji.isEmpty()) {
            target.reactions.remove(me);
        } else {
            target.reactions.put(me, emoji);
        }
        store.putMessage(target);
        for (ChatListener l : chatListeners) {
            post(() -> l.onReactionUpdated(peer, targetId));
        }
        if (signaling == null || !registered) return;

        if (Group.isGroupPeerId(peer)) {
            // Fan out to every group member except ourselves so they all see
            // the same reaction state. groupId is plaintext on the wire (just
            // a routing id) — fine since the message bodies are already E2EE.
            Group g = store.getGroup(Group.groupIdFromPeerId(peer));
            if (g == null) return;
            String emojiSafe = emoji == null ? "" : emoji;
            for (String member : g.members) {
                if (member.equalsIgnoreCase(me)) continue;
                try { signaling.sendReaction(member, targetId, emojiSafe, g.id); }
                catch (Throwable t) { Log.w(TAG, "group reaction fanout failed", t); }
            }
            return;
        }
        try { signaling.sendReaction(peer, targetId, emoji == null ? "" : emoji, null); }
        catch (Throwable t) { Log.w(TAG, "reaction send failed", t); }
    }

    /** Apply an incoming reaction from `from` on a message we hold locally.
     *  When `groupId` is set the target message lives in that group's
     *  conversation rather than in the pairwise thread with `from`. */
    private void handleIncomingReaction(String from, String targetId, String emoji,
                                        String groupId) {
        ChatMessage target;
        if (groupId != null && !groupId.isEmpty()) {
            target = findMessage("group:" + groupId, targetId);
        } else {
            target = findMessage(from, targetId);
        }
        if (target == null) {
            Log.d(TAG, "reaction for unknown message " + targetId);
            return;
        }
        if (emoji == null || emoji.isEmpty()) {
            target.reactions.remove(from);
        } else {
            target.reactions.put(from, emoji);
        }
        store.putMessage(target);
        String peerForUi = groupId != null && !groupId.isEmpty()
                ? "group:" + groupId : from;
        for (ChatListener l : chatListeners) {
            post(() -> l.onReactionUpdated(peerForUi, targetId));
        }
    }

    /** Edit a previously-sent outgoing text message. Re-encrypts the new text
     *  with the same key the original used, sends a chat_edit event to the
     *  peer(s), and updates the local copy. For groups, fans out one edit
     *  per member exactly like the original send did. Returns true if the
     *  edit was dispatched, false if not allowed (wrong direction, wrong
     *  kind, etc.). */
    public boolean editChatMessage(ChatMessage m, String newText) {
        if (m == null || newText == null) return false;
        if (m.direction != ChatMessage.Direction.OUTGOING) return false;
        if (m.kind != ChatMessage.Kind.TEXT && m.kind != ChatMessage.Kind.CONTACT
                && m.kind != ChatMessage.Kind.LOCATION) {
            // Captions on media are stored differently — restrict to text-y
            // payloads for v1.
            return false;
        }
        if (m.deleted) return false;
        ensureKeys();

        boolean isGroup = Group.isGroupPeerId(m.peer);
        if (isGroup) {
            Group g = store.getGroup(Group.groupIdFromPeerId(m.peer));
            if (g == null || g.groupKey == null) return false;
            String ct;
            try { ct = MessageCrypto.encrypt(g.groupKey, newText); }
            catch (Throwable t) { Log.e(TAG, "edit encrypt failed", t); return false; }
            String me = store.getUsername();
            for (String member : g.members) {
                if (me != null && member.equalsIgnoreCase(me)) continue;
                signaling.sendChatEdit(member, m.id, ct, g.id);
            }
        } else {
            byte[] convKey = keys != null ? keys.keyFor(m.peer) : null;
            if (convKey == null) return false;
            String ct;
            try { ct = MessageCrypto.encrypt(convKey, newText); }
            catch (Throwable t) { Log.e(TAG, "edit encrypt failed", t); return false; }
            signaling.sendChatEdit(m.peer, m.id, ct, null);
        }
        // Update local copy.
        m.text = newText;
        m.editedAt = System.currentTimeMillis();
        m.kind = RichContent.kindOf(newText); // in case sentinel changed
        store.putMessage(m);
        return true;
    }

    /** Unsend a previously-sent outgoing message of any kind. Sends a
     *  chat_delete event to peer(s) and marks the local copy deleted.
     *  Returns true if dispatched, false on wrong direction. */
    public boolean deleteChatMessage(ChatMessage m) {
        if (m == null) return false;
        if (m.direction != ChatMessage.Direction.OUTGOING) return false;
        boolean isGroup = Group.isGroupPeerId(m.peer);
        if (isGroup) {
            Group g = store.getGroup(Group.groupIdFromPeerId(m.peer));
            if (g != null) {
                String me = store.getUsername();
                for (String member : g.members) {
                    if (me != null && member.equalsIgnoreCase(me)) continue;
                    signaling.sendChatDelete(member, m.id, g.id);
                }
            }
        } else {
            signaling.sendChatDelete(m.peer, m.id, null);
        }
        m.deleted = true;
        store.putMessage(m);
        return true;
    }

    // ===================================================================
    // Read receipts / typing / presence
    // ===================================================================

    /** Send a "I've read these messages" ack to the original sender of
     *  each. Respects the local user's "send read receipts" preference —
     *  if off, this is a no-op. 1-on-1 only for v1.
     *
     *  Caller passes a list of OUTGOING-on-sender messages from a single
     *  peer; we filter to ones whose status is < READ so we don't spam.
     *  Returns the number of receipts dispatched. */
    public int markChatRead(String peer, java.util.List<ChatMessage> messages) {
        if (!store.getSendReadReceipts()) return 0;
        if (peer == null || Group.isGroupPeerId(peer)) return 0;
        if (signaling == null || !registered) return 0;
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (ChatMessage m : messages) {
            if (m.direction != ChatMessage.Direction.INCOMING) continue;
            if (m.id == null || m.id.isEmpty()) continue;
            // Track which incoming messages we've already acked so we don't
            // re-send on every chat open. We piggyback on the message's own
            // status field: once we send a read receipt for an INCOMING
            // message, we promote its local status to READ.
            if (m.status == ChatMessage.Status.READ) continue;
            ids.add(m.id);
        }
        if (ids.isEmpty()) return 0;
        signaling.sendChatRead(peer, ids);
        // Mark local incoming messages as READ so we don't re-ack them later.
        for (ChatMessage m : messages) {
            if (m.direction != ChatMessage.Direction.INCOMING) continue;
            if (ids.contains(m.id)) {
                m.status = ChatMessage.Status.READ;
                store.putMessage(m);
            }
        }
        return ids.size();
    }

    /** Send a typing-started or typing-stopped signal. Throttling is the
     *  caller's responsibility (cheap server call, but spamming is rude). */
    public void sendTyping(String peer, boolean typing) {
        if (peer == null || signaling == null || !registered) return;
        if (Group.isGroupPeerId(peer)) {
            // Fan out to every member of the group except ourselves. Same
            // pattern as chat fan-out.
            Group g = store.getGroup(Group.groupIdFromPeerId(peer));
            if (g == null) return;
            String me = store.getUsername();
            for (String member : g.members) {
                if (me != null && member.equalsIgnoreCase(me)) continue;
                signaling.sendTyping(member, typing, g.id);
            }
        } else {
            signaling.sendTyping(peer, typing, null);
        }
    }

    /** Ask the server for presence updates on this 1-on-1 peer. The server
     *  replies immediately with current state and pushes future changes. */
    public void subscribePresence(String peer) {
        if (peer == null || Group.isGroupPeerId(peer)) return;
        if (signaling == null || !registered) return;
        signaling.subscribePresence(peer);
    }

    public void unsubscribePresence(String peer) {
        if (peer == null || Group.isGroupPeerId(peer)) return;
        if (signaling == null || !registered) return;
        signaling.unsubscribePresence(peer);
    }

    /** Cached presence for a peer. Returns {@code null} when nothing known. */
    public long[] getPresence(String peer) {
        if (peer == null) return null;
        return presenceCache.get(peer.toLowerCase());
    }

    // ===================================================================
    // Contact requests (friend-request-style adding)
    // ===================================================================

    /** Send a contact-add request to a peer. Returns true on dispatch,
     *  false if the request is blocked locally (already a contact, self,
     *  invalid name, etc.). The caller can show a "request sent" toast on
     *  true and an explanatory message on false. */
    public boolean requestContact(String username) {
        if (username == null) return false;
        final String u = username.trim().toLowerCase();
        if (u.isEmpty()) return false;
        if (u.equalsIgnoreCase(store.getUsername())) return false;
        // Already added → nothing to do.
        for (Contact c : store.getContacts()) {
            if (c.username.equalsIgnoreCase(u)) return false;
        }
        // Already pending outgoing → don't spam.
        if (store.hasOutgoingContactRequest(u)) return false;
        // If THEY already requested US, accept theirs rather than send a
        // duplicate in the other direction — same effect, but no race.
        for (Store.PendingContactRequest r : store.getIncomingContactRequests()) {
            if (r.from.equalsIgnoreCase(u)) {
                acceptContactRequest(u);
                return true;
            }
        }
        store.addOutgoingContactRequest(u);
        if (signaling != null && registered) {
            signaling.sendContactRequest(u, store.getMyDisplayName());
        } else {
            // No live socket yet — the request is persisted and will be
            // sent automatically the moment we register (see
            // replayPendingContactRequests). This is common when the user
            // taps "Send Request" right after app launch, before the
            // WebSocket has finished the register handshake.
            Log.d(TAG, "contact_request to " + u + " deferred (signaling not ready); "
                    + "will resend on register");
        }
        for (ChatListener l : chatListeners) {
            post(() -> l.onContactRequestsChanged());
        }
        return true;
    }

    /** Accept an incoming contact request — adds the requester to our
     *  contact list and notifies them that we agreed. */
    public void acceptContactRequest(String from) {
        if (from == null) return;
        Store.PendingContactRequest match = null;
        for (Store.PendingContactRequest r : store.getIncomingContactRequests()) {
            if (r.from.equalsIgnoreCase(from)) { match = r; break; }
        }
        String displayName = match != null && match.displayName != null
                && !match.displayName.isEmpty() ? match.displayName : from;
        store.removeIncomingContactRequest(from);
        Contact c = new Contact(from.toLowerCase(), displayName);
        store.addContact(c);
        if (signaling != null && registered) {
            signaling.sendContactAccept(from, store.getMyDisplayName());
        }
        for (ChatListener l : chatListeners) {
            post(() -> l.onContactRequestsChanged());
        }
    }

    /** Decline an incoming contact request. Silent — we don't tell the
     *  requester explicitly, both for simplicity and to avoid leaking the
     *  decision. They'll see their request as still-pending. */
    public void declineContactRequest(String from) {
        if (from == null) return;
        store.removeIncomingContactRequest(from);
        for (ChatListener l : chatListeners) {
            post(() -> l.onContactRequestsChanged());
        }
    }

    // ===================================================================
    // Profile sync (display name + avatar)
    // ===================================================================

    /**
     * Update the local user's profile and push it to every saved contact.
     * Either parameter may be null to indicate "no change":
     *
     * @param displayName new display name, or null to keep current
     * @param avatarUri   URI to a fresh avatar image, or null to keep current
     *
     * The avatar (if provided) is uploaded once, encrypted with a random key.
     * The key is then wrapped per-contact with that contact's conversation key
     * and shipped as a profile_msg. This means the encrypted blob lives once
     * on the server, but only people we share the wrapper key with can decode
     * it — same security model as one-to-one media.
     */
    public void updateMyProfile(String displayName, android.net.Uri avatarUri) {
        ensureKeys();
        if (displayName != null) store.setMyDisplayName(displayName.trim());

        if (avatarUri == null) {
            // Name-only change: still push out so peers refresh.
            broadcastProfile(false);
            return;
        }

        ProfileManager.uploadOwnAvatar(ctx, avatarUri, store.getServer(),
                new ProfileManager.UploadCallback() {
            @Override public void onSuccess(String localPath, String url, String keyB64) {
                store.setMyAvatarPath(localPath);
                store.setMyAvatarUrl(url);
                store.setMyAvatarKeyB64(keyB64);
                broadcastProfile(true);
            }
            @Override public void onFailure(Throwable t) {
                Log.e(TAG, "avatar upload failed", t);
                post(() -> android.widget.Toast.makeText(ctx,
                        "Avatar upload failed: " + t.getMessage(),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Push current profile (name + avatar metadata) to every saved contact.
     *  Each payload is encrypted with the per-contact conversation key.
     *  Contacts we don't have a key for are skipped silently — they'll get
     *  the update next time we have one. */
    private void broadcastProfile(boolean includeAvatar) {
        long version = System.currentTimeMillis();
        store.setMyProfileVersion(version);
        String name = store.getMyDisplayName();
        String url  = includeAvatar ? store.getMyAvatarUrl()    : null;
        String key  = includeAvatar ? store.getMyAvatarKeyB64() : null;
        for (Contact c : store.getContacts()) {
            sendProfileTo(c.username, name, url, key, version);
        }
    }

    /** Push current profile to a single peer (e.g. just-added contact). */
    public void pushProfileTo(String peer) {
        long version = store.getMyProfileVersion();
        if (version == 0) version = System.currentTimeMillis();
        sendProfileTo(peer,
                store.getMyDisplayName(),
                store.getMyAvatarUrl(),
                store.getMyAvatarKeyB64(),
                version);
    }

    private void sendProfileTo(String peer, String name, String url, String keyB64,
                               long version) {
        if (signaling == null || !registered) return;
        ensureKeys();
        byte[] convKey = keys != null ? keys.keyFor(peer) : null;
        if (convKey == null) {
            // We don't have a key yet — kick a fetch and bail. The peer will
            // get the profile next time we update or when they request it.
            try { signaling.requestPublicKey(peer); } catch (Throwable ignored) {}
            return;
        }
        try {
            org.json.JSONObject payload = new org.json.JSONObject();
            if (name != null) payload.put("name", name);
            if (url  != null) payload.put("avatarUrl", url);
            if (keyB64 != null) payload.put("avatarKey", keyB64);
            String ct = MessageCrypto.encrypt(convKey, payload.toString());
            signaling.sendProfile(peer, ct, version);
        } catch (Throwable t) {
            Log.w(TAG, "profile send to " + peer + " failed", t);
        }
    }

    private void handleIncomingProfile(String from, String payloadCt, long version) {
        ensureKeys();
        if (keys == null || payloadCt == null) return;

        // Skip stale updates — peers may resend on reconnect.
        Store.PeerProfile existing = store.getPeerProfile(from);
        if (existing != null && version != 0 && version <= existing.version) {
            Log.d(TAG, "ignoring stale profile from " + from
                    + " (got v" + version + ", have v" + existing.version + ")");
            return;
        }

        // Try conversation key first, then legacy — same fallback as text.
        String plain = null;
        byte[] convKey = keys.conversationKey(from);
        if (convKey != null) plain = MessageCrypto.decrypt(convKey, payloadCt);
        if (plain == null) plain = MessageCrypto.decrypt(keys.legacyKey(), payloadCt);
        if (plain == null) {
            Log.w(TAG, "profile decrypt failed from " + from);
            // Same self-heal as image decrypt: refresh peer key in case it
            // rotated and a fresh push is coming.
            try { signaling.requestPublicKey(from); } catch (Throwable ignored) {}
            return;
        }

        String name = null, avatarUrl = null, avatarKey = null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(plain);
            name      = o.optString("name", null);
            avatarUrl = o.optString("avatarUrl", null);
            avatarKey = o.optString("avatarKey", null);
            if ("null".equals(name))      name = null;
            if ("null".equals(avatarUrl)) avatarUrl = null;
            if ("null".equals(avatarKey)) avatarKey = null;
        } catch (Throwable t) {
            Log.w(TAG, "profile payload parse failed", t);
            return;
        }

        Store.PeerProfile p = existing != null ? existing : new Store.PeerProfile();
        if (name != null) p.displayName = name;
        p.version = version;
        // If avatar URL+key are present and changed since last cache, download.
        if (avatarUrl != null && avatarKey != null) {
            final String fName = name;
            ProfileManager.downloadPeerAvatar(ctx, from, avatarUrl, avatarKey,
                    new ProfileManager.DownloadCallback() {
                @Override public void onSuccess(String localPath) {
                    Store.PeerProfile np = store.getPeerProfile(from);
                    if (np == null) np = new Store.PeerProfile();
                    np.displayName = fName != null ? fName : np.displayName;
                    np.avatarPath = localPath;
                    np.version    = version;
                    store.putPeerProfile(from, np);
                    for (ChatListener l : chatListeners) {
                        post(() -> l.onPeerProfileUpdated(from));
                    }
                }
                @Override public void onFailure(Throwable t) {
                    // Save name even if avatar fetch failed; user can still see
                    // the new display name.
                    Log.w(TAG, "peer avatar fetch failed for " + from, t);
                    store.putPeerProfile(from, p);
                    for (ChatListener l : chatListeners) {
                        post(() -> l.onPeerProfileUpdated(from));
                    }
                }
            });
        } else {
            // Name-only update.
            store.putPeerProfile(from, p);
            for (ChatListener l : chatListeners) {
                post(() -> l.onPeerProfileUpdated(from));
            }
        }
    }

    // ===================================================================
    // Group chats (3+ people)
    // ===================================================================
    //
    // Cryptographic model:
    //   - Each group has a random 32-byte AES-GCM key. All group chat / media
    //     payloads are encrypted with this key.
    //   - The group key is delivered to each member via a pairwise-encrypted
    //     group_event_msg with event="invite". A member who hasn't received
    //     their invite cannot decrypt group traffic.
    //   - The server only sees ciphertext.
    //
    // Routing model:
    //   - There is no multicast at the protocol level. To send to a group of
    //     N members, the sender does N pairwise chat_send/media_send calls,
    //     each carrying the same ciphertext and a groupId field.
    //   - The recipient stores the message under the group's peerId
    //     ("group:<uuid>") rather than under the actual sender's username.

    /** Buffer of group messages that arrived before we received the invite
     *  (race condition). Drained from handleGroupEvent on receipt of an invite. */
    private final java.util.Map<String, java.util.List<Runnable>> pendingGroupReplay =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Create a new group with the given members. Generates a fresh group key
     *  and sends a group_event_msg "invite" to each member with the roster
     *  + key, all encrypted with that member's pairwise conversation key.
     *
     *  @return the persisted Group on success, or null if creation failed */
    public Group createGroup(String name, java.util.List<String> memberUsernames) {
        ensureKeys();
        String myUsername = store.getUsername();
        if (myUsername == null || myUsername.isEmpty()) return null;

        // Generate a fresh 32-byte AES key for the group.
        byte[] groupKey = new byte[32];
        new java.security.SecureRandom().nextBytes(groupKey);

        Group g = new Group();
        g.id   = java.util.UUID.randomUUID().toString();
        g.name = name == null ? "" : name.trim();
        g.groupKey = groupKey;
        g.version  = System.currentTimeMillis();
        g.members  = new java.util.ArrayList<>();
        g.members.add(myUsername.toLowerCase());
        for (String u : memberUsernames) {
            if (u == null) continue;
            String low = u.trim().toLowerCase();
            if (!low.isEmpty() && !g.members.contains(low)) g.members.add(low);
        }
        store.putGroup(g);

        // Invite each other member via pairwise encryption.
        for (String member : g.members) {
            if (member.equalsIgnoreCase(myUsername)) continue;
            sendGroupEventTo(member, g, "invite");
        }
        return g;
    }

    /** Leave a group: remove it locally and notify remaining members. */
    public void leaveGroup(String groupId) {
        Group g = store.getGroup(groupId);
        if (g == null) return;
        String me = store.getUsername();
        for (String member : g.members) {
            if (me != null && member.equalsIgnoreCase(me)) continue;
            sendGroupEventTo(member, g, "leave");
        }
        store.removeGroup(groupId);
    }

    /** Add new members to an existing group. Pushes an "update" with the new
     *  roster to ALL current members (including the newcomers, who also need
     *  the group key). */
    public void addGroupMembers(String groupId, java.util.List<String> newUsernames) {
        Group g = store.getGroup(groupId);
        if (g == null || g.groupKey == null) return;
        boolean changed = false;
        for (String u : newUsernames) {
            String low = u == null ? null : u.trim().toLowerCase();
            if (low == null || low.isEmpty()) continue;
            if (!g.members.contains(low)) { g.members.add(low); changed = true; }
        }
        if (!changed) return;
        g.version = System.currentTimeMillis();
        store.putGroup(g);

        String me = store.getUsername();
        for (String member : g.members) {
            if (me != null && member.equalsIgnoreCase(me)) continue;
            // Existing and new members alike get "update". For new members the
            // payload also contains the group key (since they don't have it).
            sendGroupEventTo(member, g, "update");
        }
    }

    /** Build + send a single group_event_msg to one peer. The payload (name,
     *  members, optional groupKey) is encrypted with our pairwise key. */
    private void sendGroupEventTo(String peer, Group g, String event) {
        // Leave is special: it has no payload (no pair-key needed), and even
        // when we're offline we still want it to ride out on next reconnect,
        // because we've already removed the group locally. Persist the
        // (peer, groupId) and flush from replayPendingLeaves on register.
        if ("leave".equals(event)) {
            if (signaling == null || !registered) {
                store.addPendingLeave(peer, g.id);
                Log.w(TAG, "group leave to " + peer + " deferred (offline); "
                        + "will resend on register");
                return;
            }
            try {
                signaling.sendGroupEvent(peer, g.id, "leave", null);
            } catch (Throwable t) {
                Log.w(TAG, "group leave send failed; queuing for replay", t);
                store.addPendingLeave(peer, g.id);
            }
            return;
        }
        if (signaling == null || !registered) {
            Log.w(TAG, "group_event " + event + " to " + peer + " skipped (offline)");
            return;
        }

        ensureKeys();
        byte[] pairKey = keys != null ? keys.keyFor(peer) : null;
        if (pairKey == null) {
            // No conversation key yet — try to fetch and bail. Recipient won't
            // get this update until next time we send a group event for them.
            try { signaling.requestPublicKey(peer); } catch (Throwable ignored) {}
            Log.w(TAG, "group_event to " + peer + " skipped (no pair key)");
            return;
        }
        try {
            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("name", g.name == null ? "" : g.name);
            payload.put("version", g.version);
            org.json.JSONArray members = new org.json.JSONArray();
            for (String m : g.members) members.put(m);
            payload.put("members", members);
            if (g.groupKey != null) {
                payload.put("groupKey", android.util.Base64.encodeToString(
                        g.groupKey, android.util.Base64.NO_WRAP));
            }
            String ct = MessageCrypto.encrypt(pairKey, payload.toString());
            signaling.sendGroupEvent(peer, g.id, event, ct);
        } catch (Throwable t) {
            Log.w(TAG, "group_event " + event + " to " + peer + " failed", t);
        }
    }

    /** Process an incoming group_event_msg. Updates local group roster/key. */
    private void handleGroupEvent(String from, String groupId, String event, String payloadCt) {
        if (groupId == null || groupId.isEmpty()) return;
        Group existing = store.getGroup(groupId);

        if ("leave".equals(event)) {
            if (existing == null) return;
            existing.members.removeIf(u -> u.equalsIgnoreCase(from));
            existing.version = Math.max(existing.version + 1, System.currentTimeMillis());
            store.putGroup(existing);
            postGroupSystemMessage(existing, from + " left the group");
            // Notify any open UI (GroupInfoActivity member list, chat header
            // subtitle, main contact list) that the roster changed. Without
            // this the departed member stayed visible everywhere on every
            // other device until the next manual refresh.
            for (ChatListener l : chatListeners) {
                post(() -> l.onGroupChanged(existing.id));
            }
            return;
        }

        if ("reject".equals(event)) {
            // The invitee declined our invitation. Remove them from our local
            // member list so we stop fanning messages to them. Also push an
            // "update" to remaining members so their rosters converge.
            if (existing == null) return;
            existing.members.removeIf(u -> u.equalsIgnoreCase(from));
            existing.version = Math.max(existing.version + 1, System.currentTimeMillis());
            store.putGroup(existing);
            postGroupSystemMessage(existing, from + " declined to join");
            String me = store.getUsername();
            for (String member : existing.members) {
                if (me != null && member.equalsIgnoreCase(me)) continue;
                sendGroupEventTo(member, existing, "update");
            }
            for (ChatListener l : chatListeners) {
                post(() -> l.onGroupChanged(existing.id));
            }
            return;
        }

        // invite / update: decrypt the payload with the sender's pairwise key.
        ensureKeys();
        byte[] pairKey = keys != null ? keys.keyFor(from) : null;
        if (pairKey == null || payloadCt == null) {
            Log.w(TAG, "group_event " + event + " from " + from + " — no pair key");
            try { if (signaling != null) signaling.requestPublicKey(from); } catch (Throwable ignored) {}
            return;
        }
        String plain = MessageCrypto.decrypt(pairKey, payloadCt);
        if (plain == null) plain = MessageCrypto.decrypt(keys.legacyKey(), payloadCt);
        if (plain == null) {
            Log.w(TAG, "group_event payload decrypt failed from " + from);
            return;
        }

        try {
            org.json.JSONObject o = new org.json.JSONObject(plain);
            long version = o.optLong("version", 0L);
            if (existing != null && version != 0 && version <= existing.version
                    && existing.groupKey != null) {
                Log.d(TAG, "ignoring stale group_event v" + version
                        + " (have v" + existing.version + ")");
                return;
            }
            String name = o.optString("name", "");
            org.json.JSONArray membersArr = o.optJSONArray("members");
            java.util.List<String> members = new java.util.ArrayList<>();
            if (membersArr != null) {
                for (int i = 0; i < membersArr.length(); i++) members.add(membersArr.optString(i));
            }
            byte[] groupKey = existing != null ? existing.groupKey : null;
            String keyB64 = o.optString("groupKey", null);
            if (keyB64 != null && !keyB64.isEmpty() && !"null".equals(keyB64)) {
                groupKey = android.util.Base64.decode(keyB64, android.util.Base64.NO_WRAP);
            }

            // **New** behaviour: a first-time "invite" to a group we are NOT
            // already a member of becomes a PendingInvite, not a Group. The
            // user must explicitly accept before we join. Updates to an
            // existing group still apply immediately.
            String me = store.getUsername();
            boolean alreadyAMember = existing != null && me != null
                    && existing.members.stream().anyMatch(u -> u.equalsIgnoreCase(me));
            if ("invite".equals(event) && !alreadyAMember) {
                Store.PendingInvite pi = new Store.PendingInvite();
                pi.groupId   = groupId;
                pi.groupName = name;
                pi.groupKey  = groupKey;
                pi.members   = members;
                pi.inviter   = from;
                pi.version   = version != 0 ? version : System.currentTimeMillis();
                pi.receivedAt = System.currentTimeMillis();
                store.putPendingInvite(pi);
                // Surface a notification + main-screen banner refresh.
                ChatNotifier.show(ctx, "invite:" + groupId,
                        from + " invited you to \"" + name + "\"");
                for (ChatListener l : chatListeners) {
                    post(() -> l.onGroupChanged(groupId));
                }
                return;
            }

            Group g = existing != null ? existing : new Group();
            g.id = groupId;
            g.name = name;
            g.members = members;
            g.groupKey = groupKey;
            g.version = version != 0 ? version : System.currentTimeMillis();
            store.putGroup(g);

            boolean addedToGroup = existing == null
                    || (me != null && existing.members.stream().noneMatch(u -> u.equalsIgnoreCase(me)));
            if (addedToGroup) {
                postGroupSystemMessage(g, from + " added you to \"" + g.name + "\"");
            }

            for (ChatListener l : chatListeners) {
                post(() -> l.onGroupChanged(g.id));
            }

            // Drain any group messages that arrived before this invite.
            java.util.List<Runnable> drain = pendingGroupReplay.remove(groupId);
            if (drain != null) {
                for (Runnable r : drain) {
                    try { r.run(); } catch (Throwable t) { Log.w(TAG, "group replay failed", t); }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "group_event payload parse failed", t);
        }
    }

    /** Accept a pending group invite: promote it to a real Group so the user
     *  starts receiving messages. The inviter and existing members find out
     *  about the new member organically via the next chat message or roster
     *  update — no explicit "accept" event in v1. */
    public Group acceptInvite(String groupId) {
        Store.PendingInvite pi = store.getPendingInvite(groupId);
        if (pi == null) return null;
        Group g = new Group();
        g.id = pi.groupId;
        g.name = pi.groupName;
        g.groupKey = pi.groupKey;
        g.members = new java.util.ArrayList<>(pi.members);
        g.version = pi.version;
        store.putGroup(g);
        store.removePendingInvite(groupId);
        ChatNotifier.clear(ctx, "invite:" + groupId);
        postGroupSystemMessage(g, "You joined \"" + g.name + "\"");
        for (ChatListener l : chatListeners) {
            post(() -> l.onGroupChanged(g.id));
        }
        // If any group messages arrived before accept, drain them now.
        java.util.List<Runnable> drain = pendingGroupReplay.remove(groupId);
        if (drain != null) {
            for (Runnable r : drain) {
                try { r.run(); } catch (Throwable t) { Log.w(TAG, "group replay failed", t); }
            }
        }
        return g;
    }

    /** Decline a pending invite: tell the inviter we're not joining. They'll
     *  remove us from their local member list so messages stop fanning out
     *  to us. */
    public void rejectInvite(String groupId) {
        Store.PendingInvite pi = store.getPendingInvite(groupId);
        if (pi == null) return;
        store.removePendingInvite(groupId);
        ChatNotifier.clear(ctx, "invite:" + groupId);
        if (signaling != null && registered && pi.inviter != null) {
            try { signaling.sendGroupEvent(pi.inviter, groupId, "reject", null); }
            catch (Throwable t) { Log.w(TAG, "reject send failed", t); }
        }
        for (ChatListener l : chatListeners) {
            post(() -> l.onGroupChanged(groupId));
        }
    }


    /** Insert a synthetic INCOMING message into a group's history to communicate
     *  membership events ("X joined", "Y left") to the user inline. */
    private void postGroupSystemMessage(Group g, String text) {
        if (g == null) return;
        ChatMessage m = new ChatMessage(
                java.util.UUID.randomUUID().toString(), g.peerId(),
                ChatMessage.Direction.INCOMING, text, System.currentTimeMillis(),
                ChatMessage.Status.DELIVERED);
        m.groupId = g.id;
        m.senderUsername = "system";
        store.putMessage(m);
        for (ChatListener l : chatListeners) {
            post(() -> l.onChatMessage(m));
        }
    }

    /** Send a text message to a group. Identical persistence model to 1-on-1
     *  (one local ChatMessage), but the wire send is fanned out across
     *  members. */
    private ChatMessage sendGroupChatMessage(String groupPeerId, String text,
                                             String replyToId, String replyToPreview,
                                             boolean forwarded) {
        Group g = store.getGroup(Group.groupIdFromPeerId(groupPeerId));
        if (g == null) return null;

        String me = store.getUsername();
        String id = java.util.UUID.randomUUID().toString();
        long ts = System.currentTimeMillis();
        ChatMessage m = new ChatMessage(
                id, g.peerId(), ChatMessage.Direction.OUTGOING, text, ts,
                ChatMessage.Status.PENDING);
        m.senderUsername = me;
        m.groupId = g.id;
        m.replyToId = replyToId;
        m.replyToPreview = replyToPreview;
        m.forwarded = forwarded;
        store.putMessage(m);
        // Track for chat_ack updates; group fan-out produces N acks that all
        // map to this single id.
        outgoingChat.put(id, m);

        if (g.groupKey == null) {
            Log.w(TAG, "no groupKey for " + g.id + " — message stays PENDING");
            return m;
        }

        try {
            String ct = MessageCrypto.encrypt(g.groupKey, text == null ? "" : text);
            String replyCt = null;
            if (replyToPreview != null) {
                replyCt = MessageCrypto.encrypt(g.groupKey, replyToPreview);
            }
            if (signaling != null && registered) {
                fanOutChat(g, me, id, ct, replyToId, replyCt, forwarded);
            } else {
                pendingOutgoing.add(m);
            }
        } catch (Throwable t) {
            Log.e(TAG, "group chat encrypt failed", t);
            m.status = ChatMessage.Status.FAILED;
            store.putMessage(m);
        }
        return m;
    }

    /** Fan out a chat ciphertext to every group member except the sender. */
    private void fanOutChat(Group g, String me, String id, String ct,
                            String replyToId, String replyCt, boolean forwarded) {
        for (String member : g.members) {
            if (me != null && member.equalsIgnoreCase(me)) continue;
            try {
                signaling.sendChat(member, id, ct, replyToId, replyCt, forwarded, g.id);
            } catch (Throwable t) {
                Log.w(TAG, "group fanout to " + member + " failed", t);
            }
        }
    }

    /** Receive a chat message that belongs to a group conversation. */
    private void handleGroupChatMessage(String from, String id, String ciphertext,
                                        String replyToId, String replyToPreviewCt,
                                        boolean forwarded, String groupId, long ts) {
        Group g = store.getGroup(groupId);
        if (g == null || g.groupKey == null) {
            // We don't have the invite yet. Buffer for retry.
            Log.d(TAG, "buffering group chat from " + from + " for unknown group " + groupId);
            pendingGroupReplay.computeIfAbsent(groupId, k ->
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                    .add(() -> handleGroupChatMessage(from, id, ciphertext, replyToId,
                            replyToPreviewCt, forwarded, groupId, ts));
            return;
        }
        String text = MessageCrypto.decrypt(g.groupKey, ciphertext);
        if (text == null) {
            Log.w(TAG, "group chat decrypt failed for group " + groupId);
            return;
        }
        String replyPreview = null;
        if (replyToPreviewCt != null && !replyToPreviewCt.isEmpty()) {
            replyPreview = MessageCrypto.decrypt(g.groupKey, replyToPreviewCt);
        }
        ChatMessage m = new ChatMessage(
                id, g.peerId(), ChatMessage.Direction.INCOMING, text, ts,
                ChatMessage.Status.DELIVERED);
        m.kind = RichContent.kindOf(text);
        m.senderUsername = from;
        m.groupId = g.id;
        m.replyToId = replyToId;
        m.replyToPreview = replyPreview;
        m.forwarded = forwarded;
        store.putMessage(m);
        store.incrementUnread(g.peerId());
        if (chatListeners.isEmpty() && !store.isMuted(g.peerId())) {
            String preview = m.kind == ChatMessage.Kind.CONTACT ? "\uD83D\uDC64 Contact card"
                    : m.kind == ChatMessage.Kind.LOCATION ? "\uD83D\uDCCD Location" : text;
            ChatNotifier.show(ctx, g.peerId(), g.name + " — " + from + ": " + preview);
        }
        for (ChatListener l : chatListeners) {
            post(() -> l.onChatMessage(m));
        }
    }

    /** Receive a media message that belongs to a group conversation. */
    private void handleGroupMediaMessage(String from, String id, String url, String mediaType,
                                          int width, int height, String thumbnailB64,
                                          String captionCt, int durationMs,
                                          String replyToId, String replyToPreviewCt,
                                          boolean forwarded, String groupId, long ts) {
        Group g = store.getGroup(groupId);
        if (g == null || g.groupKey == null) {
            pendingGroupReplay.computeIfAbsent(groupId, k ->
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                    .add(() -> handleGroupMediaMessage(from, id, url, mediaType, width, height,
                            thumbnailB64, captionCt, durationMs, replyToId,
                            replyToPreviewCt, forwarded, groupId, ts));
            return;
        }
        String captionText = "";
        if (captionCt != null && !captionCt.isEmpty()) {
            captionText = MessageCrypto.decrypt(g.groupKey, captionCt);
            if (captionText == null) captionText = "";
        }
        String replyPreview = null;
        if (replyToPreviewCt != null && !replyToPreviewCt.isEmpty()) {
            replyPreview = MessageCrypto.decrypt(g.groupKey, replyToPreviewCt);
        }
        boolean isVoice = mediaType != null && mediaType.startsWith("audio/");
        boolean isImage = mediaType != null && mediaType.startsWith("image/");
        ChatMessage m = new ChatMessage(
                id, g.peerId(), ChatMessage.Direction.INCOMING, captionText, ts,
                ChatMessage.Status.DELIVERED);
        if (isVoice)      m.kind = ChatMessage.Kind.VOICE;
        else if (isImage) m.kind = ChatMessage.Kind.IMAGE;
        else              m.kind = ChatMessage.Kind.DOCUMENT;
        if (m.kind == ChatMessage.Kind.DOCUMENT) {
            m.fileName = captionText;
            m.fileSize = (long) width;
            m.text = "";
        }
        m.senderUsername = from;
        m.groupId = g.id;
        m.mediaUrl = url;
        m.mediaType = mediaType;
        m.mediaWidth = width;
        m.mediaHeight = height;
        m.thumbnailB64 = thumbnailB64;
        m.durationMs = durationMs;
        m.replyToId = replyToId;
        m.replyToPreview = replyPreview;
        m.forwarded = forwarded;
        store.putMessage(m);
        store.incrementUnread(g.peerId());
        for (ChatListener l : chatListeners) {
            post(() -> l.onChatMessage(m));
        }
    }

    /** Send an image to a group. The encrypted blob is uploaded once (encrypted
     *  with the group key) and then a media_send is fanned out to each member. */
    public ChatMessage sendImageToGroup(android.net.Uri sourceUri, String groupPeerId,
                                        String caption) {
        Group g = store.getGroup(Group.groupIdFromPeerId(groupPeerId));
        if (g == null || g.groupKey == null) return null;

        final String me = store.getUsername();
        final String id = java.util.UUID.randomUUID().toString();
        final long ts = System.currentTimeMillis();
        final ChatMessage m = new ChatMessage(
                id, g.peerId(), ChatMessage.Direction.OUTGOING,
                caption == null ? "" : caption, ts, ChatMessage.Status.PENDING);
        m.kind = ChatMessage.Kind.IMAGE;
        m.mediaType = "image/jpeg";
        m.senderUsername = me;
        m.groupId = g.id;
        store.putMessage(m);
        outgoingMedia.put(id, m);

        final byte[] groupKey = g.groupKey;
        MediaTransfer.uploadImage(ctx, sourceUri, groupKey, store.getServer(),
                new MediaTransfer.UploadCallback() {
            @Override public void onSuccess(MediaTransfer.UploadResult r) {
                m.mediaUrl = r.downloadUrl;
                m.mediaWidth = r.width;
                m.mediaHeight = r.height;
                m.thumbnailB64 = r.thumbnailB64;
                if (r.localCachePath != null) m.localPath = r.localCachePath;
                store.putMessage(m);

                String captionCt = null;
                if (caption != null && !caption.isEmpty()) {
                    try { captionCt = MessageCrypto.encrypt(groupKey, caption); }
                    catch (Throwable ignored) {}
                }
                if (signaling != null && registered) {
                    for (String member : g.members) {
                        if (me != null && member.equalsIgnoreCase(me)) continue;
                        try {
                            signaling.sendMedia(member, id, r.downloadUrl, "image/jpeg",
                                    r.width, r.height, r.thumbnailB64, captionCt, 0,
                                    null, null, false, g.id);
                        } catch (Throwable t) {
                            Log.w(TAG, "group image fanout to " + member + " failed", t);
                        }
                    }
                }
            }
            @Override public void onFailure(Throwable t) {
                Log.e(TAG, "group image upload failed", t);
                m.status = ChatMessage.Status.FAILED;
                store.putMessage(m);
                outgoingMedia.remove(id);
                post(() -> android.widget.Toast.makeText(
                        ctx, "Image upload failed: " + t.getMessage(),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
        return m;
    }

    /** Send a voice message to a group. Mirrors sendVoiceMessage but with the
     *  group key and fan-out to all members. */
    public ChatMessage sendVoiceToGroup(String groupPeerId, String audioFilePath,
                                        int durationMs) {
        Group g = store.getGroup(Group.groupIdFromPeerId(groupPeerId));
        if (g == null || g.groupKey == null) return null;

        final String me = store.getUsername();
        final String id = java.util.UUID.randomUUID().toString();
        final long ts = System.currentTimeMillis();
        final ChatMessage m = new ChatMessage(
                id, g.peerId(), ChatMessage.Direction.OUTGOING, "", ts,
                ChatMessage.Status.PENDING);
        m.kind = ChatMessage.Kind.VOICE;
        m.mediaType = "audio/mp4";
        m.durationMs = durationMs;
        m.senderUsername = me;
        m.groupId = g.id;
        store.putMessage(m);
        outgoingMedia.put(id, m);

        final byte[] groupKey = g.groupKey;
        MediaTransfer.uploadAudio(ctx, audioFilePath, groupKey, store.getServer(),
                new MediaTransfer.AudioUploadCallback() {
            @Override public void onSuccess(String downloadUrl, String localCachePath) {
                m.mediaUrl = downloadUrl;
                if (localCachePath != null) m.localPath = localCachePath;
                store.putMessage(m);
                if (signaling != null && registered) {
                    for (String member : g.members) {
                        if (me != null && member.equalsIgnoreCase(me)) continue;
                        try {
                            signaling.sendMedia(member, id, downloadUrl, "audio/mp4",
                                    0, 0, null, null, durationMs,
                                    null, null, false, g.id);
                        } catch (Throwable t) {
                            Log.w(TAG, "group voice fanout to " + member + " failed", t);
                        }
                    }
                }
                try { new java.io.File(audioFilePath).delete(); } catch (Throwable ignored) {}
            }
            @Override public void onFailure(Throwable t) {
                Log.e(TAG, "group voice upload failed", t);
                m.status = ChatMessage.Status.FAILED;
                store.putMessage(m);
                outgoingMedia.remove(id);
            }
        });
        return m;
    }

    /** Returns the right decryption key for a peer ID — group key if it's a
     *  group conversation, conversation key otherwise. */
    public byte[] keyForConversation(String peer) {
        if (Group.isGroupPeerId(peer)) {
            Group g = store.getGroup(Group.groupIdFromPeerId(peer));
            return g != null ? g.groupKey : null;
        }
        ensureKeys();
        return keys != null ? keys.keyFor(peer) : null;
    }


    /** Look up a message by peer + id. We search per-peer first (fast) but
     *  fall back to scanning every conversation because a reaction's "from"
     *  is the reactor, not necessarily the message's original sender. */
    private ChatMessage findMessage(String peer, String id) {
        for (ChatMessage m : store.getMessages(peer)) {
            if (m.id.equals(id)) return m;
        }
        // Reactions can come from either side of a 1:1 chat, so the peer the
        // reaction came from is always the right conversation to search. But
        // outgoing messages may have come from a forward — search the user's
        // contact list too just in case.
        for (Contact c : store.getContacts()) {
            if (c.username.equalsIgnoreCase(peer)) continue;
            for (ChatMessage m : store.getMessages(c.username)) {
                if (m.id.equals(id)) return m;
            }
        }
        return null;
    }

    /**
     * Forward an existing message into a different conversation. Re-encrypts
     * with the destination peer's conversation key — the original ciphertext
     * is never reused, so the destination peer can't tell from the bytes
     * alone where the message came from.
     *
     * Text: re-sent via sendChatMessage with forwarded=true.
     * Image/voice: re-uploaded from the local cached copy (sender always has
     *              one). The peer cannot tell this is the same blob as the
     *              original because it's re-encrypted with a different key.
     */
    public ChatMessage forwardMessage(ChatMessage source, String toPeer) {
        if (source == null || toPeer == null) return null;
        if (source.kind == ChatMessage.Kind.TEXT) {
            return sendChatMessage(toPeer, source.text == null ? "" : source.text,
                    null, null, true);
        }
        if (source.kind == ChatMessage.Kind.IMAGE) {
            if (source.localPath == null || !new java.io.File(source.localPath).exists()) {
                // Encourage user to view it first so it gets cached.
                post(() -> android.widget.Toast.makeText(ctx,
                        "Open the image once before forwarding it",
                        android.widget.Toast.LENGTH_SHORT).show());
                return null;
            }
            return sendImage(android.net.Uri.fromFile(new java.io.File(source.localPath)),
                    toPeer, source.text == null ? "" : source.text, true);
        }
        if (source.kind == ChatMessage.Kind.VOICE) {
            if (source.localPath == null || !new java.io.File(source.localPath).exists()) {
                post(() -> android.widget.Toast.makeText(ctx,
                        "Play the voice message once before forwarding it",
                        android.widget.Toast.LENGTH_SHORT).show());
                return null;
            }
            // sendVoiceMessage deletes its source after upload, which would
            // erase the original. Copy to a temp file first.
            java.io.File tmp;
            try {
                java.io.File dir = new java.io.File(ctx.getCacheDir(), "voice-fwd");
                if (!dir.exists()) dir.mkdirs();
                tmp = new java.io.File(dir, "fwd-" + System.currentTimeMillis() + ".m4a");
                try (java.io.FileInputStream in = new java.io.FileInputStream(source.localPath);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            } catch (Throwable t) {
                Log.e(TAG, "voice forward copy failed", t);
                return null;
            }
            return sendVoiceMessage(toPeer, tmp.getAbsolutePath(), source.durationMs, true);
        }
        return null;
    }

    /** Returns a 6-digit Short Authentication String for the conversation
     *  with `peer`, or null if we don't yet have their public key. Users can
     *  read this out-of-band to detect a MITM. */
    public String getSAS(String peer) {
        ensureKeys();
        return keys != null ? keys.shortAuthString(peer) : null;
    }

    /** Has a per-conversation key been established with this peer yet? */
    public boolean hasSecureKey(String peer) {
        ensureKeys();
        return keys != null && keys.hasPeerKey(peer);
    }

    /** Force-request a peer's public key, e.g. when opening a chat screen. */
    public void requestPeerKey(String peer) {
        if (signaling != null && registered) {
            try { signaling.requestPublicKey(peer); } catch (Throwable ignored) {}
        }
    }

    /** Returns the 32-byte AES key currently used for talking to `peer`,
     *  preferring per-conversation key then legacy. Null if KeyManager
     *  isn't initialized yet (no username set). For group peer IDs
     *  ("group:<uuid>") returns the group's symmetric key. */
    public byte[] keyForPeer(String peer) {
        if (Group.isGroupPeerId(peer)) {
            Group g = store.getGroup(Group.groupIdFromPeerId(peer));
            return g != null ? g.groupKey : null;
        }
        ensureKeys();
        return keys != null ? keys.keyFor(peer) : null;
    }

    /** After a fresh pubkey arrives for `peer`, try to decrypt any messages
     *  we had buffered as undecryptable. Anything that still fails is dropped. */
    private void drainUndecryptedFor(String peer) {
        if (keys == null) return;
        java.util.List<Object[]> list = undecryptedIncoming.remove(peer.toLowerCase());
        if (list == null) return;
        byte[] convKey = keys.conversationKey(peer);
        byte[] legacy = keys.legacyKey();
        for (Object[] row : list) {
            String id = (String) row[0];
            String ciphertext = (String) row[1];
            long ts = (Long) row[2];
            // Reply/forward fields added when buffering — handle older shorter
            // rows defensively in case they slip in from a different code path.
            String replyToId       = row.length > 3 ? (String) row[3] : null;
            String replyToPreviewCt= row.length > 4 ? (String) row[4] : null;
            boolean forwarded      = row.length > 5 && Boolean.TRUE.equals(row[5]);

            String text = null;
            byte[] usedKey = null;
            if (convKey != null) {
                text = MessageCrypto.decrypt(convKey, ciphertext);
                if (text != null) usedKey = convKey;
            }
            if (text == null) {
                text = MessageCrypto.decrypt(legacy, ciphertext);
                if (text != null) usedKey = legacy;
            }
            if (text == null) {
                Log.w(TAG, "buffered message from " + peer + " still undecryptable after key refresh, dropping");
                continue;
            }
            String replyPreview = null;
            if (replyToPreviewCt != null && !replyToPreviewCt.isEmpty() && usedKey != null) {
                replyPreview = MessageCrypto.decrypt(usedKey, replyToPreviewCt);
            }
            ChatMessage m = new ChatMessage(
                    id, peer, ChatMessage.Direction.INCOMING, text, ts,
                    ChatMessage.Status.DELIVERED);
            m.replyToId = replyToId;
            m.replyToPreview = replyPreview;
            m.forwarded = forwarded;
            store.putMessage(m);
            store.incrementUnread(peer);
            if (chatListeners.isEmpty() && !store.isMuted(peer)) {
                ChatNotifier.show(ctx, peer, text);
            }
            for (ChatListener l : chatListeners) {
                post(() -> l.onChatMessage(m));
            }
        }
    }

    /** Re-send every still-pending outgoing contact request. Called from
     *  {@link #onRegistered} so the first WS message after sign-in or
     *  reconnect picks up requests that were created while the socket was
     *  unavailable. The recipient's client dedups by {@code from} when the
     *  same request arrives more than once. */
    private void replayPendingContactRequests() {
        if (signaling == null || !registered) return;
        java.util.List<String> outgoing = store.getOutgoingContactRequests();
        if (outgoing.isEmpty()) return;
        String myName = store.getMyDisplayName();
        for (String to : outgoing) {
            try { signaling.sendContactRequest(to, myName); }
            catch (Throwable t) { Log.w(TAG, "replay contact_request to " + to + " failed: " + t); }
        }
        Log.d(TAG, "replayed " + outgoing.size() + " pending contact request(s) on register");
    }

    /** Re-send every still-pending group leave (groups we already removed
     *  locally while offline). Idempotent on the recipient: if they've
     *  already removed us, their leave handler is a no-op. */
    private void replayPendingLeaves() {
        if (signaling == null || !registered) return;
        java.util.List<String[]> leaves = store.getPendingLeaves();
        if (leaves.isEmpty()) return;
        int sent = 0;
        for (String[] entry : leaves) {
            String peer = entry[0];
            String groupId = entry[1];
            try {
                signaling.sendGroupEvent(peer, groupId, "leave", null);
                store.removePendingLeave(peer, groupId);
                sent++;
            } catch (Throwable t) {
                Log.w(TAG, "replay group leave to " + peer + " failed: " + t);
            }
        }
        if (sent > 0) Log.d(TAG, "replayed " + sent + " pending group leave(s) on register");
    }

    private void replayPendingOutgoing() {
        ensureKeys();
        java.util.List<ChatMessage> snapshot;
        synchronized (pendingOutgoing) {
            snapshot = new java.util.ArrayList<>(pendingOutgoing);
            pendingOutgoing.clear();
        }
        for (ChatMessage m : snapshot) {
            // Re-use sendChatMessage's logic so per-peer key fetch still
            // happens on replay. Avoid re-persisting the message: temporarily
            // remove it so sendChatMessage's putMessage is a no-op overwrite.
            try {
                if (keys != null && !keys.hasPeerKey(m.peer)) {
                    if (signaling != null) signaling.requestPublicKey(m.peer);
                    waitingForKey.computeIfAbsent(m.peer.toLowerCase(),
                            k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                            .add(m);
                    continue;
                }
                byte[] k = keys != null ? keys.keyFor(m.peer) : null;
                if (k == null) throw new IllegalStateException("no key");
                String ct = MessageCrypto.encrypt(k, m.text);
                String replyCt = null;
                if (m.replyToPreview != null) {
                    replyCt = MessageCrypto.encrypt(k, m.replyToPreview);
                }
                if (signaling != null) {
                    signaling.sendChat(m.peer, m.id, ct,
                            m.replyToId, replyCt, m.forwarded, m.groupId);
                }
            } catch (Throwable t) {
                pendingOutgoing.add(m);
            }
        }
    }

    public void acceptIncoming() {
        if (signaling == null || activeCallId == null) return;
        signaling.accept(activeCallId);
    }

    public void rejectIncoming() {
        if (signaling == null || activeCallId == null) return;
        signaling.reject(activeCallId);
        endLocal("rejected");
    }

    public void hangup() {
        if (signaling != null && activeCallId != null) signaling.hangup(activeCallId);
        endLocal("ended");
    }

    public void setMuted(boolean muted) {
        final RtcEngine r = rtc;
        if (r != null) rtcPost(() -> { try { r.setMicEnabled(!muted); } catch (Throwable ignored) {} });
    }

    // --- Video controls + renderer attachment -----------------------------
    // The activity creates SurfaceViewRenderers and asks us to attach them.
    // Order is racy because rtc is created asynchronously on the rtc thread,
    // so we cache the renderers in `pendingLocalRenderer`/`pendingRemoteRenderer`
    // and flush them once rtc exists.
    private org.webrtc.SurfaceViewRenderer pendingLocalRenderer;
    private org.webrtc.SurfaceViewRenderer pendingRemoteRenderer;

    public void attachVideoRenderers(org.webrtc.SurfaceViewRenderer local,
                                     org.webrtc.SurfaceViewRenderer remote) {
        rtcPost(() -> {
            pendingLocalRenderer = local;
            pendingRemoteRenderer = remote;
            if (rtc != null) {
                try {
                    if (local  != null) rtc.attachLocalRenderer(local);
                    if (remote != null) rtc.attachRemoteRenderer(remote);
                } catch (Throwable t) {
                    Log.e(TAG, "attachVideoRenderers failed", t);
                }
            }
        });
    }

    public void switchCamera() {
        final RtcEngine r = rtc;
        if (r != null) rtcPost(() -> { try { r.switchCamera(); } catch (Throwable ignored) {} });
    }

    public void setVideoEnabled(boolean enabled) {
        final RtcEngine r = rtc;
        if (r != null) rtcPost(() -> { try { r.setVideoEnabled(enabled); } catch (Throwable ignored) {} });
    }

    public boolean isActiveCallVideo() { return activeIsVideo; }

    // ------------------------------------------------------------------
    // Media (WebRTC)
    // ------------------------------------------------------------------
    private void startMedia(boolean initiator) {
        inCall = true;
        CallService.start(ctx);
        // Per-conversation E2EE key (X25519-derived) if we have the peer's
        // public key, otherwise the legacy hardcoded key for backward compat
        // with older clients that haven't published a pubkey yet.
        ensureKeys();
        final byte[] key;
        if (keys != null && keys.hasPeerKey(activePeer)) {
            key = keys.conversationKey(activePeer);
            Log.d(TAG, "call using per-conversation E2EE key for " + activePeer);
        } else {
            key = RtcEngine.deriveKey(store.getSecret());
            Log.w(TAG, "call using legacy E2EE key for " + activePeer
                    + " (peer pubkey not available)");
            // Kick off a pubkey request so future calls upgrade.
            if (signaling != null) {
                try { signaling.requestPublicKey(activePeer); } catch (Throwable ignored) {}
            }
        }
        final String callId = activeCallId;

        rtcPost(() -> {
        try {
            rtc = new RtcEngine(ctx, key, new RtcEngine.Events() {
            @Override public void onLocalDescription(SessionDescription sdp) {
                JSONObject msg = new JSONObject();
                JSONObject sdpObj = new JSONObject();
                try {
                    sdpObj.put("type", sdp.type.canonicalForm());
                    sdpObj.put("sdp", sdp.description);
                    msg.put("type", sdp.type == SessionDescription.Type.OFFER ? "offer" : "answer");
                    msg.put("callId", callId);
                    msg.put("sdp", sdpObj);
                } catch (Exception e) { Log.e(TAG, "pack sdp", e); }
                signaling.sendRaw(msg);
            }
            @Override public void onIceCandidate(IceCandidate candidate) {
                JSONObject msg = new JSONObject();
                JSONObject c = new JSONObject();
                try {
                    c.put("sdpMid", candidate.sdpMid);
                    c.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    c.put("candidate", candidate.sdp);
                    msg.put("type", "candidate");
                    msg.put("callId", callId);
                    msg.put("candidate", c);
                } catch (Exception e) { Log.e(TAG, "pack ice", e); }
                signaling.sendRaw(msg);
            }
            @Override public void onConnectionState(PeerConnection.PeerConnectionState state) {
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    if (activeConnectedMs == 0) activeConnectedMs = System.currentTimeMillis();
                    if (ui != null) post(() -> ui.onCallConnected(activePeer));
                } else if (state == PeerConnection.PeerConnectionState.FAILED
                        || state == PeerConnection.PeerConnectionState.CLOSED) {
                    endLocal("disconnected");
                }
            }
            @Override public void onIceState(PeerConnection.IceConnectionState state) {
                // Surface ICE progress so the user can see whether NAT traversal is
                // making progress (checking / connected) or stuck (disconnected / failed).
                final String label = "ICE: " + state.toString().toLowerCase();
                if (ui != null) post(() -> ui.onConnectionState(label));
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    Log.e(TAG, "ICE failed - peers couldn't reach each other (NAT/firewall). " +
                            "A TURN relay is required.");
                }
            }
        });

            rtc.createPeerConnection(iceServers(), activeIsVideo);
            // If the activity asked us to attach renderers before rtc existed,
            // flush them now.
            if (pendingLocalRenderer != null) {
                try { rtc.attachLocalRenderer(pendingLocalRenderer); } catch (Throwable ignored) {}
            }
            if (pendingRemoteRenderer != null) {
                try { rtc.attachRemoteRenderer(pendingRemoteRenderer); } catch (Throwable ignored) {}
            }
            if (initiator) rtc.createOffer();
        } catch (Throwable t) {
            Log.e(TAG, "startMedia failed", t);
            final String err = t.getClass().getSimpleName();
            if (ui != null) post(() -> ui.onConnectionState("media error: " + err));
            endLocal("media_error");
        }
        });
    }

    private void endLocal(String reason) {
        boolean was = inCall || activeCallId != null;

        // Persist a call log entry before clearing state.
        if (was && activePeer != null) {
            long ts = activeRingStartMs > 0 ? activeRingStartMs : System.currentTimeMillis();
            long durSec = 0;
            CallLogEntry.Direction dir;
            if (activeConnectedMs > 0) {
                // The call actually went through.
                dir = activeIsOutgoing ? CallLogEntry.Direction.OUTGOING
                                       : CallLogEntry.Direction.INCOMING;
                durSec = Math.max(0, (System.currentTimeMillis() - activeConnectedMs) / 1000);
            } else if (!activeIsOutgoing) {
                // Incoming call that never connected = missed/declined.
                dir = CallLogEntry.Direction.MISSED;
            } else {
                // Outgoing that never connected — still log as outgoing with 0 duration.
                dir = CallLogEntry.Direction.OUTGOING;
            }
            try {
                store.addCallLogEntry(new CallLogEntry(activePeer, dir, ts, durSec, activeIsVideo));
            } catch (Throwable ignored) {}
        }

        inCall = false;
        activeCallId = null;
        activePeer = null;
        activeIsOutgoing = false;
        activeIsVideo = false;
        activeRingStartMs = 0;
        activeConnectedMs = 0;
        pendingLocalRenderer = null;
        pendingRemoteRenderer = null;
        // Always cancel the incoming-call notification on call termination —
        // covers the case where the user dismissed without picking either
        // action (e.g. swiped it away on some OEM ROMs) and we want any
        // lingering ring sound to stop.
        try { IncomingCallNotifier.cancel(ctx); } catch (Throwable ignored) {}
        final RtcEngine toDispose = rtc;
        rtc = null;
        if (toDispose != null) {
            rtcPost(() -> { try { toDispose.dispose(); } catch (Throwable ignored) {} });
        }
        CallService.stop(ctx);
        if (was && ui != null) post(() -> ui.onCallEnded(reason));
    }

    private List<PeerConnection.IceServer> iceServers() {
        List<PeerConnection.IceServer> list = new ArrayList<>();

        // STUN servers — let each peer discover its public address.
        list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer());

        // Free public TURN servers. These rotate / disappear over time, so
        // multiple providers maximize the chance at least one is reachable.
        // TLS-over-443 (turns:...:443) is the most firewall-friendly because
        // it looks identical to HTTPS traffic.
        // For reliable production use deploy your own coturn — see README.
        java.util.List<String[]> turnUrls = java.util.Arrays.asList(
                new String[]{"turn:openrelay.metered.ca:80",                "openrelayproject", "openrelayproject"},
                new String[]{"turn:openrelay.metered.ca:443",               "openrelayproject", "openrelayproject"},
                new String[]{"turn:openrelay.metered.ca:443?transport=tcp", "openrelayproject", "openrelayproject"},
                new String[]{"turns:openrelay.metered.ca:443",              "openrelayproject", "openrelayproject"}
        );
        for (String[] s : turnUrls) {
            list.add(PeerConnection.IceServer.builder(s[0])
                    .setUsername(s[1]).setPassword(s[2]).createIceServer());
        }
        return list;
    }

    public void shutdown() {
        endLocal("shutdown");
        if (signaling != null) { signaling.close(); signaling = null; }
        registered = false;
        // Drop per-user state so a fresh sign-in (possibly with a different
        // username) gets a clean slate. The KeyManager in particular is keyed
        // to a username — keeping the old one around would cause the next
        // user to send their messages signed by the previous user's identity.
        keys = null;
        presenceCache.clear();
        // Reset the in-memory store reference so the next call to any hub
        // method re-reads the active user from the (newly-cleared) global SP.
        store = new Store(ctx);
    }
}
