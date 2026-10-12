/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtspAuth.java — RTSP Basic and Digest (RFC 2617) authentication
 *
 */

package com.openipc.decoder;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the Authorization header for each request. Basic is sent pre-emptively, as cameras
 * running OpenIPC expect; after a Digest challenge every request is signed with Digest.
 */
class RtspAuth {
    private final String user;
    private final String password;

    private boolean digest;
    private String realm;
    private String nonce;
    private String opaque;
    private String algorithm;
    private boolean qopAuth;
    private int nonceCount;
    /** MD5-sess: one client nonce per server nonce, used in H(A1) and sent every time. */
    private String sessCnonce;
    /** Fixed client nonce for tests; random when null. */
    String testCnonce;

    /** @param userInfo decoded "user:password" from the URL, or null */
    RtspAuth(String userInfo) {
        if (userInfo == null) {
            user = null;
            password = null;
        } else {
            int colon = userInfo.indexOf(':');
            user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
            password = colon >= 0 ? userInfo.substring(colon + 1) : "";
        }
    }

    boolean hasCredentials() { return user != null; }

    /** "Authorization: ...\r\n" for this request, or "" without credentials. */
    String header(String method, String uri) {
        if (user == null) return "";
        if (!digest) {
            String token = Base64.encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            return "Authorization: Basic " + token + "\r\n";
        }

        String ha1 = md5(user + ":" + realm + ":" + password);
        String cnonce = null;
        String nc = null;
        if ("MD5-SESS".equals(algorithm)) {
            if (sessCnonce == null) sessCnonce = newCnonce();
            cnonce = sessCnonce;
            ha1 = md5(ha1 + ":" + nonce + ":" + cnonce);
        } else if (qopAuth) {
            cnonce = newCnonce();
        }
        String ha2 = md5(method + ":" + uri);
        String response;
        if (qopAuth) {
            nc = String.format(Locale.ROOT, "%08x", ++nonceCount);
            response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
        }

        StringBuilder h = new StringBuilder("Authorization: Digest ");
        h.append("username=").append(quote(user)).append(", ");
        h.append("realm=").append(quote(realm)).append(", ");
        h.append("nonce=").append(quote(nonce)).append(", ");
        h.append("uri=").append(quote(uri)).append(", ");
        h.append("response=\"").append(response).append('"');
        if (algorithm != null) h.append(", algorithm=").append(algorithm);
        if (opaque != null) h.append(", opaque=").append(quote(opaque));
        if (qopAuth) h.append(", qop=auth, nc=").append(nc);
        // MD5-sess needs the cnonce even without qop, or the server cannot verify H(A1)
        if (cnonce != null) h.append(", cnonce=").append(quote(cnonce));
        return h.append("\r\n").toString();
    }

    /**
     * Handle a 401 reply. Returns true if the request should be repeated: a first Digest
     * challenge, or a stale nonce. Returns false when retrying cannot help (no
     * credentials, an unsupported scheme, or the same credentials were already rejected).
     */
    boolean onUnauthorized(List<String> challenges) {
        if (user == null || challenges == null) return false;
        for (String c : challenges) {
            String trimmed = c.trim();
            if (!trimmed.regionMatches(true, 0, "Digest", 0, 6)) continue;
            Map<String, String> p = parseParams(trimmed.substring(6));
            String alg = p.get("algorithm");
            if (alg != null && !alg.equalsIgnoreCase("MD5") && !alg.equalsIgnoreCase("MD5-sess")) {
                continue; // SHA-256 etc. are not supported
            }
            String qop = p.get("qop");
            boolean hasAuth = false;
            if (qop != null) {
                for (String q : qop.split(",")) {
                    if (q.trim().equalsIgnoreCase("auth")) hasAuth = true;
                }
                // Only auth-int offered: we cannot produce that, look for another challenge
                if (!hasAuth) continue;
            }
            boolean stale = "true".equalsIgnoreCase(p.get("stale"));
            boolean retry = !digest || stale;
            digest = true;
            realm = p.containsKey("realm") ? p.get("realm") : "";
            nonce = p.containsKey("nonce") ? p.get("nonce") : "";
            opaque = p.get("opaque");
            algorithm = alg != null ? alg.toUpperCase(Locale.ROOT) : null;
            qopAuth = hasAuth;
            nonceCount = 0;
            sessCnonce = null;
            return retry;
        }
        // Basic was already sent pre-emptively: another attempt would fail the same way
        return false;
    }

    /** Parse "k=v, k="quoted, value", ..." into lower-case keys. */
    static Map<String, String> parseParams(String s) {
        Map<String, String> out = new HashMap<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && (s.charAt(i) == ',' || Character.isWhitespace(s.charAt(i)))) i++;
            int eq = s.indexOf('=', i);
            if (eq < 0) break;
            String key = s.substring(i, eq).trim().toLowerCase(Locale.ROOT);
            i = eq + 1;
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            StringBuilder value = new StringBuilder();
            if (i < n && s.charAt(i) == '"') {
                i++;
                while (i < n && s.charAt(i) != '"') {
                    if (s.charAt(i) == '\\' && i + 1 < n) i++;
                    value.append(s.charAt(i++));
                }
                i++; // closing quote
            } else {
                while (i < n && s.charAt(i) != ',') value.append(s.charAt(i++));
            }
            out.put(key, value.toString().trim());
        }
        return out;
    }

    private String newCnonce() {
        return testCnonce != null ? testCnonce : randomHex(8);
    }

    /** Quoted-string with '"' and '\\' escaped (RFC 2616 2.2). */
    static String quote(String v) {
        return '"' + v.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : d) hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder hex = new StringBuilder(bytes * 2);
        for (byte x : b) hex.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        return hex.toString();
    }
}
