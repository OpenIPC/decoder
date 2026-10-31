/*
 *
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * BootReceiver.java — opens the app after the device boots, when Start on boot is on (#255)
 *
 */

package com.openipc.decoder;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

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
        // Android 15+ lets an app that targets 35+ start from the background with this
        // permission only while one of its overlay windows is visible (Qodo on #256)
        View overlay = Build.VERSION.SDK_INT >= 35 && overlayAllowed(context)
                ? showOverlay(context) : null;
        try {
            context.startActivity(start);
        } catch (RuntimeException e) {
            Log.w(TAG, "Start on boot failed", e);
        }
        if (overlay != null) {
            PendingResult pending = goAsync();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                removeOverlay(context, overlay);
                pending.finish();
            }, OVERLAY_MS);
        }
    }

    /** Long enough for the start to pass the check, short enough to stay well under 10 s. */
    private static final long OVERLAY_MS = 2000;

    /** A 1×1 transparent window that takes no touch or focus; null if it cannot be shown. */
    @TargetApi(35)
    private static View showOverlay(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        View v = new View(context.getApplicationContext());
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSPARENT);
        try {
            wm.addView(v, lp);
            return v;
        } catch (RuntimeException e) {
            Log.w(TAG, "Start on boot: no overlay window", e);
            return null;
        }
    }

    private static void removeOverlay(Context context, View v) {
        try {
            ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).removeView(v);
        } catch (RuntimeException e) {
            Log.w(TAG, "Start on boot: overlay window not removed", e);
        }
    }
}
