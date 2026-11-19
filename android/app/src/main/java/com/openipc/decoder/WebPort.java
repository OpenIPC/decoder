/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * WebPort.java — the port of the web page, set in Network (#284)
 *
 */

package com.openipc.decoder;

import android.content.Context;

/**
 * The port {@link DebugServer} asks for: 8085 unless Network sets another. When it is taken
 * (another of our apps on the device serves its page there), the server takes the next free
 * one of {@link #TRIES}, and the camex tunnel is told the port it really got.
 */
final class WebPort {
    static final int DEFAULT = 8085;
    /** The set port and the ports after it that are tried: 8085 to 8094 by default. */
    static final int TRIES = 10;
    /** No privileged ports: an app cannot open them. */
    static final int MIN = 1024;
    static final int MAX = 65535;
    private static final String PREFS = "settings";
    static final String KEY = "web_port";
    /** Settings → DECODER → Allow remote access (#286): off, the app opens no port at all. */
    static final String KEY_ALLOWED = "web_allowed";

    private WebPort() {
    }

    /** The set port: {@link #DEFAULT} when nothing (or nothing valid) is set, or no context yet. */
    static int get(Context c) {
        if (c == null) return DEFAULT;
        Integer p = parse(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""));
        return p != null ? p : DEFAULT;
    }

    /** Whether the web page is served at all: on unless turned off in Settings. */
    static boolean allowed(Context c) {
        return c == null || c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ALLOWED, true);
    }

    /** Saves Allow remote access; returns true when it changed. */
    static boolean setAllowed(Context c, boolean on) {
        if (allowed(c) == on) return false;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ALLOWED, on).apply();
        return true;
    }

    /** As the Network field shows it: empty for the default. */
    static String text(Context c) {
        int p = get(c);
        return p == DEFAULT ? "" : String.valueOf(p);
    }

    /** null when the text is a port the field takes (empty: the default), else what is wrong. */
    static String check(String text) {
        return parse(text) != null ? null : "A port from " + MIN + " to " + MAX + ", or empty for " + DEFAULT;
    }

    /** Saves a checked text; returns true when the set port changed. */
    static boolean save(Context c, String text) {
        int before = get(c);
        Integer p = parse(text);
        int after = p != null ? p : DEFAULT;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, after == DEFAULT ? "" : String.valueOf(after)).apply();
        return after != before;
    }

    /** The port of a text, the default for an empty one, null for anything else. */
    static Integer parse(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) return DEFAULT;
        if (s.length() > 5) return null;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return null;
        }
        int p = Integer.parseInt(s);
        return p >= MIN && p <= MAX ? p : null;
    }
}
