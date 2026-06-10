package com.example.p2pvoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Handles taps on the Decline button of the incoming-call notification.
 *
 * The Answer action launches CallActivity directly via its PendingIntent
 * (with EXTRA_AUTO_ACCEPT) — no need to route through a broadcast for that.
 * Decline, however, is a no-UI action: we want to send the reject signal
 * and dismiss the notification without opening any screen, which a
 * BroadcastReceiver does cleanly.
 *
 * Registered in AndroidManifest as not-exported, so only our own
 * PendingIntents can invoke it.
 */
public class CallActionReceiver extends BroadcastReceiver {

    private static final String TAG = "CallActionReceiver";
    public static final String ACTION_DECLINE = "com.example.p2pvoice.DECLINE_CALL";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "received " + action);
        if (ACTION_DECLINE.equals(action)) {
            // Route through the singleton hub so signaling + state stay in sync.
            try {
                SignalingHub.get(ctx).rejectIncoming();
            } catch (Throwable t) {
                Log.e(TAG, "rejectIncoming failed", t);
            }
            IncomingCallNotifier.cancel(ctx);
        }
    }
}
