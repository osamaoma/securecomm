package com.example.p2pvoice;

import android.app.Application;
import android.util.Log;

/**
 * Application-level init.
 *
 * Firebase Cloud Messaging (used to wake the phone for incoming call pushes)
 * normally auto-initializes via a ContentProvider that the google-services
 * Gradle plugin injects from google-services.json. We call initializeApp here
 * explicitly as a safety net so push works even if the auto-init provider
 * runs later than expected.
 *
 * Note: image transfer in this build uses the local signaling server, not
 * Firebase Storage. See MediaTransfer for details.
 */
public class P2PApp extends Application {

    private static final String TAG = "P2PApp";

    @Override
    public void onCreate() {
        super.onCreate();
        // Apply the saved theme preference BEFORE the first activity is created,
        // otherwise it'd flash with the wrong theme briefly. Default is "system"
        // — follow the device's day/night setting.
        try {
            String mode = new Store(this).getThemeMode();
            int night;
            switch (mode == null ? "system" : mode) {
                case "light": night = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO; break;
                case "dark":  night = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES; break;
                default:      night = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(night);
        } catch (Throwable t) {
            Log.w(TAG, "theme init failed: " + t.getMessage());
        }

        try {
            com.google.firebase.FirebaseApp.initializeApp(this);
            Log.d(TAG, "Firebase initialized (for FCM push)");
        } catch (Throwable t) {
            Log.w(TAG, "Firebase init failed: " + t.getMessage());
        }
    }
}
