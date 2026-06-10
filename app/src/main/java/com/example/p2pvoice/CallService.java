package com.example.p2pvoice;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Foreground service that keeps the mic + call alive when backgrounded.
 *
 * IMPORTANT (Android 14+): starting a "microphone" foreground service requires
 * the RECORD_AUDIO permission to already be granted, and startForegroundService
 * must not be called from a disallowed background state. Both can throw and would
 * otherwise crash the app, so every start path here is guarded.
 */
public class CallService extends Service {

    private static final String TAG = "CallService";
    private static final String CHANNEL_ID = "p2p_call";
    private static final int NOTIF_ID = 42;

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, CallService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            // e.g. ForegroundServiceStartNotAllowedException on Android 12+.
            // The call can still proceed without the service; just log it.
            Log.e(TAG, "startForegroundService failed: " + t, t);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, CallService.class));
        } catch (Throwable t) {
            Log.e(TAG, "stopService failed: " + t, t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Encrypted call in progress")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true)
                .build();

        boolean micGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && micGranted) {
                // Only declare the microphone type if we actually hold the permission,
                // otherwise Android 14+ throws and kills the app.
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                // Fallback: a plain foreground notification with no special type.
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed: " + t, t);
            try { startForeground(NOTIF_ID, n); } catch (Throwable ignored) {}
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
