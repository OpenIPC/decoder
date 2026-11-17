/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * WebPassword.java — the password of the web page on port 8085, set in Network
 *
 */

package com.openipc.decoder;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * The password {@link DebugServer} asks for, the same from the local network and the tunnel.
 * Empty (the default): the pages are open. Kept sealed with {@link CredentialStore}, like the
 * camera URLs and the psk. Read by the server itself, so it holds from the first request, before
 * any activity has started.
 */
final class WebPassword {
    private static final String TAG = "OpenIPCDecoder";
    private static final String PREFS = "settings";
    static final String KEY = "web_password";

    private static final Object lock = new Object();
    private static boolean loaded;
    /** "" none, null stored but unreadable (the pages stay locked until it is entered again). */
    private static String cached;

    private WebPassword() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The password the server checks: "" none, null stored but unreadable. */
    static String get() {
        synchronized (lock) {
            if (!loaded) {
                Context c = App.context;
                if (c == null) return null;   // too early to know: nothing is served
                cached = read(c);
                loaded = true;
            }
            return cached;
        }
    }

    /** The stored value, opened: "" none, null stored but unreadable. */
    static String read(Context c) {
        String stored = prefs(c).getString(KEY, "");
        if (stored == null || stored.isEmpty()) return "";
        String open = CredentialStore.create().open(stored);
        if (open == null) Log.w(TAG, "Network: the web page password cannot be read, enter it again");
        return open;
    }

    /** The sealed value as stored, for a Save that keeps an unreadable one. */
    static String stored(Context c) {
        return prefs(c).getString(KEY, "");
    }

    /**
     * Saves {@code password} ("" removes it); {@code keepStored}: an unreadable stored value
     * the form left empty, kept as it is.
     */
    static void save(Context c, String password, String keepStored) {
        String value;
        if (password == null || password.isEmpty()) {
            value = keepStored != null ? keepStored : "";
        } else {
            value = CredentialStore.create().seal(password);
        }
        prefs(c).edit().putString(KEY, value).apply();
        synchronized (lock) {
            cached = value.isEmpty() ? "" : (keepStored != null && value.equals(keepStored)
                    ? null : password);
            loaded = true;
        }
    }
}
