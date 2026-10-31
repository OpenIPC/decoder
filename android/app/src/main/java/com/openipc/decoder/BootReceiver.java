/*
 *
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * BootReceiver.java — opens the app after the device boots, when Start on boot is on (#255)
 *
 */

package com.openipc.decoder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "OpenIPCDecoder";
    /** In the "settings" preferences, so Reset (#171) turns it off too. */
    static final String PREF = "start_on_boot";

    static boolean enabled(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(PREF, false);
    }

    static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean(PREF, on).apply();
    }

    /**
     * Android 10+ starts an activity from the background only for an app that may draw over
     * other apps; many TV boxes have no screen to allow it, adb does (appops).
     */
    static boolean overlayNeeded() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    static boolean overlayAllowed(Context context) {
        return !overlayNeeded() || Settings.canDrawOverlays(context);
    }

    /** For /stats.json, with or without an activity (Qodo on #256). */
    static Map<String, Object> state(Context context) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("on", enabled(context));
        m.put("overlay", !overlayNeeded() ? "not needed"
                : overlayAllowed(context) ? "allowed" : "missing");
        return m;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!enabled(context)) return;
        // A firmware autorun opened it already: another start would pause and resume it,
        // which restarts every stream
        if (Decoder.sAlive.get() > 0) {
            Log.i(TAG, "Start on boot: the app is open already");
            return;
        }
        if (!overlayAllowed(context)) {
            Log.w(TAG, "Start on boot: no permission to draw over other apps, Android may block the start");
        }
        Log.i(TAG, "Start on boot: opening the app");
        Intent start = new Intent(context, Decoder.class);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(start);
        } catch (RuntimeException e) {
            Log.w(TAG, "Start on boot failed", e);
        }
    }
}
