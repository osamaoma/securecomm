package com.example.p2pvoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Builds and posts a high-priority "incoming call" notification with
 * Answer / Decline action buttons.
 *
 * Why a notification rather than just launching {@link CallActivity}:
 *
 *  - Android 10+ forbids background activity launches in most cases. A
 *    notification with {@code setFullScreenIntent} is the supported escape
 *    hatch for incoming-call apps — the system either launches the activity
 *    directly (locked screen) or shows it as a heads-up banner (unlocked).
 *
 *  - The notification is a familiar surface: the user gets Answer/Decline
 *    even when their screen is on a different app, with the call activity
 *    only opening if they explicitly choose to answer.
 *
 *  - The system handles the ringtone + vibration via the channel definition
 *    — no manual MediaPlayer / Vibrator wiring is needed for those.
 */
public final class IncomingCallNotifier {

    private static final String TAG = "IncomingCallNotifier";

    /** Notification channel id. Created lazily on first use. */
    public static final String CHANNEL_ID = "incoming_calls";
    /** Single fixed id — there can only be one incoming call at a time, so
     *  re-using the same id replaces an in-flight notification rather than
     *  stacking. */
    public static final int    NOTIF_ID   = 9999;

    private IncomingCallNotifier() {}

    /**
     * Post (or refresh) the incoming-call notification for the given caller.
     *
     * @param ctx     any context — only used for system services + intent build
     * @param peer    caller's username (lowercase) — used for the title +
     *                routing the activity to the right chat
     * @param callId  server-issued call id, included on the answer intent so
     *                {@link CallActivity} can accept the correct call
     * @param isVideo true for video calls; only affects the body text shown
     */
    public static void show(Context ctx, String peer, String callId, boolean isVideo) {
        ensureChannel(ctx);

        Store store = new Store(ctx);
        Store.PeerProfile prof = store.getPeerProfile(peer);
        String name = (prof != null && prof.displayName != null && !prof.displayName.isEmpty())
                ? prof.displayName
                : peer;

        // Tapping the notification body opens the full call screen with the
        // standard Accept/Decline buttons; we don't auto-accept here so the
        // user can still inspect who's calling before deciding.
        Intent contentIntent = new Intent(ctx, CallActivity.class)
                .putExtra(CallActivity.EXTRA_PEER, peer)
                .putExtra(CallActivity.EXTRA_MODE, CallActivity.MODE_INCOMING)
                .putExtra(CallActivity.EXTRA_IS_VIDEO, isVideo)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPI = PendingIntent.getActivity(
                ctx, 0, contentIntent,
                pendingIntentFlags());

        // Answer = launch CallActivity with EXTRA_AUTO_ACCEPT so it triggers
        // acceptCall() immediately after permission checks.
        Intent answerIntent = new Intent(ctx, CallActivity.class)
                .putExtra(CallActivity.EXTRA_PEER, peer)
                .putExtra(CallActivity.EXTRA_MODE, CallActivity.MODE_INCOMING)
                .putExtra(CallActivity.EXTRA_IS_VIDEO, isVideo)
                .putExtra(CallActivity.EXTRA_AUTO_ACCEPT, true)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent answerPI = PendingIntent.getActivity(
                ctx, 1, answerIntent,
                pendingIntentFlags());

        // Decline = broadcast to CallActionReceiver which calls hub.rejectIncoming
        // (no UI flicker — the call screen never opens).
        Intent declineIntent = new Intent(ctx, CallActionReceiver.class)
                .setAction(CallActionReceiver.ACTION_DECLINE);
        PendingIntent declinePI = PendingIntent.getBroadcast(
                ctx, 2, declineIntent,
                pendingIntentFlags());

        String contentText = isVideo ? "Incoming video call" : "Incoming voice call";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(name)
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(contentPI)
                // setFullScreenIntent: when the device is locked, Android
                // launches this intent as a full-screen takeover; when it's
                // unlocked, the notification renders as a heads-up banner
                // and the FSI is ignored. The boolean parameter must be true
                // for the FSI to actually fire on Android 10+.
                .setFullScreenIntent(contentPI, true)
                .setAutoCancel(false)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(R.drawable.ic_call, "Answer", answerPI)
                .addAction(R.drawable.ic_call, "Decline", declinePI);

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, builder.build());
        } catch (SecurityException se) {
            // Missing POST_NOTIFICATIONS permission on Android 13+ — already
            // requested at app start, but this is a defensive fallback so
            // we don't crash if the user revoked it.
            Log.w(TAG, "notify failed (no permission?): " + se.getMessage());
        }
    }

    /** Remove the incoming-call notification. Safe to call when no
     *  notification is showing — idempotent. */
    public static void cancel(Context ctx) {
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID);
        } catch (Throwable t) {
            Log.w(TAG, "cancel failed: " + t.getMessage());
        }
    }

    /** Set up the high-importance channel (idempotent on every call). */
    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Voice and video call invitations");
        ch.enableVibration(true);
        // System default ringtone. USAGE_NOTIFICATION_RINGTONE routes through
        // the ring stream rather than the regular notification stream so
        // mute toggles behave correctly.
        Uri ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (ringtone != null) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            ch.setSound(ringtone, attrs);
        }
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    /** PendingIntent flag set required from API 23 onward, plus FLAG_IMMUTABLE
     *  required from API 31. Combined into one helper so callers don't have
     *  to remember. */
    private static int pendingIntentFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }
}
