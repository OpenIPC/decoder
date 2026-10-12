/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * CredentialStore.java — camera URLs (with passwords) encrypted at rest
 *
 */

package com.openipc.decoder;

import android.os.Build;
import android.util.Log;

import java.nio.charset.StandardCharsets;

/**
 * Seals camera URLs before they go to SharedPreferences and opens them on load (#16).
 * The key lives in the Android Keystore (Android 6.0+) and never leaves it; on older
 * versions, or if the Keystore fails, URLs are stored as before.
 */
class CredentialStore {
    private static final String TAG = "OpenIPCDecoder";
    static final String PREFIX = "enc1:";

    /** Authenticated encryption; the output carries everything needed to decrypt it. */
    interface Cipherer {
        byte[] encrypt(byte[] plain) throws Exception;

        byte[] decrypt(byte[] sealed) throws Exception;
    }

    private final Cipherer cipher;

    CredentialStore(Cipherer cipher) {
        this.cipher = cipher;
    }

    static CredentialStore create() {
        Cipherer c = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                c = new KeystoreCipher();
            } catch (Exception e) {
                Log.w(TAG, "Android Keystore unavailable, URLs stored unencrypted: " + e);
            }
        }
        return new CredentialStore(c);
    }

    boolean encrypts() { return cipher != null; }

    /** Value to store for {@code url}; null stays null. */
    String seal(String url) {
        if (url == null || cipher == null) return url;
        try {
            return PREFIX + toHex(cipher.encrypt(url.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.w(TAG, "Cannot encrypt camera URL, storing it unencrypted: " + e);
            return url;
        }
    }

    /**
     * URL from a stored value: sealed values are decrypted, older plain values are returned
     * as they are. Returns null if a sealed value cannot be opened (e.g. the key is gone
     * after a restore to another device), so that the slot shows as not configured.
     */
    String open(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        if (cipher == null) return null;
        try {
            byte[] plain = cipher.decrypt(fromHex(stored.substring(PREFIX.length())));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Cannot decrypt a stored camera URL: " + e);
            return null;
        }
    }

    /**
     * Value to store for a slot. If the slot's stored value could not be opened
     * ({@code unreadable} != null) and the slot still shows the placeholder, the original
     * stored value is kept, so a temporarily unavailable key does not destroy it when
     * another slot is saved. A slot the user edited is sealed as usual.
     */
    String sealForSlot(String url, String unreadable, String placeholder) {
        if (unreadable != null && (url == null || url.equals(placeholder))) return unreadable;
        return seal(url);
    }

    /** True if {@code stored} is a plain value that should be re-saved sealed. */
    boolean needsMigration(String stored) {
        return cipher != null && stored != null && !stored.startsWith(PREFIX);
    }

    static String toHex(byte[] b) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = digits[(b[i] >> 4) & 0xF];
            out[2 * i + 1] = digits[b[i] & 0xF];
        }
        return new String(out);
    }

    static byte[] fromHex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("odd length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("not hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
