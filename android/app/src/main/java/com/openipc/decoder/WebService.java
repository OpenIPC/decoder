/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * WebService.java — the web page alone after an app update, until the app is opened (#288)
 *
 */

package com.openipc.decoder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * An update stops the app and nothing of it runs until someone opens it. The owner's rule
 * (2026-11-19): after an update bring back by itself only what draws no traffic and starts no
 * video — the web page ({@link DebugServer}); no cameras, no camex tunnel, no activity. A
 * process with only a receiver is cached and may be frozen or killed, so this foreground
 * service with a quiet notification keeps it. It ends when the app's screen opens
 * ({@link #stop}) or when Allow remote access is off.
 */
public final class WebService extends Service {
    private static final String TAG = "OpenIPCDecoder";
    private static final String CHANNEL = "web";
    /** Not the camex tunnel's (its library uses its own id). */
    private static final int NOTIFICATION_ID = 0x5745;

    /** After {@code MY_PACKAGE_REPLACED}: the page back, when it is allowed and the app is shut. */
    static void startAfterUpdate(Context c) {
        if (!WebPort.allowed(c)) {
            Log.i(TAG, "Updated: remote access is off, nothing is started");
            return;
        }
        if (Decoder.openOrOpening()) return;
        Intent i = new Intent(c, WebService.class);
        try {
            // exempt from the background limits on foreground services: the update broadcast
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            Log.i(TAG, "Updated: the web page is back, nothing else (no video, no tunnel)");
        } catch (RuntimeException e) {
            Log.w(TAG, "Updated: the web page could not be started: " + e.getMessage());
        }
    }

    /** The app's screen is open: its own process keeps the page from now on. */
    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, WebService.class));
        } catch (RuntimeException e) {
            // not running
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        if (!WebPort.allowed(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        DebugServer.get();   // binds its port on a thread of its own
        // killed by the system: not brought back (the next update or the app's start will)
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")   // Notification.Builder(Context) before Android 8
    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Web page",
                    NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, Decoder.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        b.setContentIntent(PendingIntent.getActivity(this, 0, open,
                (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
                        | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Decoder")
                .setContentText("Web page on port " + WebPort.get(this) + " (updated; open to play)")
                .setOngoing(true)
                .build();
    }
}
