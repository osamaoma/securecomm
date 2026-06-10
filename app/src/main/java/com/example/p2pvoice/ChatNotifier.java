package com.example.p2pvoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/** Posts a system notification when a chat message arrives and no in-app
 *  listener is attached to display it directly. Tapping the notification
 *  opens the ChatActivity for that peer. */
public final class ChatNotifier {

    private static final String CHANNEL_ID = "p2pvoice_chat";
    private static final String CHANNEL_NAME = "Chat messages";

    public static void show(Context ctx, String fromPeer, String preview) {
        ensureChannel(ctx);

        Intent open = new Intent(ctx, ChatActivity.class);
        open.putExtra(ChatActivity.EXTRA_PEER, fromPeer);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, fromPeer.hashCode(), open, piFlags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(fromPeer)
                .setContentText(preview)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(preview))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(pi);

        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            // Use one notification per peer (stable id from username).
            nm.notify(stableId(fromPeer), b.build());
        } catch (Throwable ignored) {}
    }

    public static void clear(Context ctx, String fromPeer) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(stableId(fromPeer));
        } catch (Throwable ignored) {}
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel c = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        c.setDescription("Incoming chat messages");
        nm.createNotificationChannel(c);
    }

    /** Per-peer stable notification id so multiple chats don't collide. */
    private static int stableId(String peer) {
        // Reserve a range distinct from CallService FGS id; just use peer hash.
        return 0x10000 | (peer.toLowerCase().hashCode() & 0xFFFF);
    }

    private ChatNotifier() {}
}
