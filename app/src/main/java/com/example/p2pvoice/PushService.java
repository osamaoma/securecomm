package com.example.p2pvoice;

import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Receives FCM push messages. There are two kinds we care about:
 *
 *  1. New-token messages (onNewToken): the OS gave us a fresh device token.
 *     We push it to the signaling server so future calls can be routed to
 *     this device even when the app is closed.
 *
 *  2. Incoming-call data messages (onMessageReceived): the server-side
 *     "your friend is calling" wakeup. We launch the ringing CallActivity
 *     directly and let the WebSocket connection (which auto-reconnects
 *     when the hub initializes) carry the actual WebRTC signaling.
 *
 * The server sends a *data* message (no `notification` payload), so this
 * handler runs even when the app is in the background or killed.
 */
public class PushService extends FirebaseMessagingService {

    private static final String TAG = "PushService";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(TAG, "new FCM token");
        // Persist and let SignalingHub send it next time it connects.
        new Store(this).setFcmToken(token);
        try {
            SignalingHub.get(this).publishFcmToken(token);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);
        Map<String, String> data = message.getData();
        String type = data.get("type");
        if (type == null) return;

        if ("incoming_call".equals(type)) {
            handleIncomingCall(data);
        } else if ("chat".equals(type)) {
            handleChatWakeup(data);
        }
    }

    private void handleIncomingCall(Map<String, String> data) {
        String from = data.get("from");
        String callId = data.get("callId");
        if (from == null || callId == null) return;

        Log.d(TAG, "FCM incoming_call from=" + from + " callId=" + callId);

        // Make sure the signaling connection is alive so we can receive the
        // SDP offer that follows; the hub is idempotent if already connected.
        try {
            SignalingHub.get(this).connectAndRegister();
        } catch (Throwable ignored) {}

        // Launch the ringing screen. The WebSocket message that follows shortly
        // after will provide the actual call's SDP via SignalingHub.
        try {
            Intent i = new Intent(this, CallActivity.class);
            i.putExtra(CallActivity.EXTRA_PEER, from);
            i.putExtra(CallActivity.EXTRA_MODE, CallActivity.MODE_INCOMING);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "failed to launch CallActivity from push", t);
        }
    }

    private void handleChatWakeup(Map<String, String> data) {
        String from = data.get("from");
        if (from == null) return;
        Log.d(TAG, "FCM chat wakeup from=" + from);

        // The push is just a wakeup nudge — the real message bodies are
        // queued on the server. Reconnect signaling so the server flushes
        // them; SignalingHub.onChatMessage will persist + notify.
        try {
            SignalingHub.get(this).connectAndRegister();
        } catch (Throwable ignored) {}

        // Show a placeholder notification right away so the user sees that
        // a message arrived even before signaling reconnects and the actual
        // body decrypts. SignalingHub will then post the real notification
        // (which replaces this one via the same per-peer stable id) once
        // the message body is delivered.
        try {
            ChatNotifier.show(this, from, "New message");
            // Bump the unread count visually too.
            new Store(this).incrementUnread(from);
        } catch (Throwable ignored) {}
    }
}
