/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtspAuthTest.java — Basic and Digest authentication
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public class RtspAuthTest {
    private static final String RFC_CHALLENGE = "Digest realm=\"testrealm@host.com\", "
            + "qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", "
            + "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"";

    @Test
    public void rfc2617ExampleResponse() {
        // RFC 2617 section 3.5
        RtspAuth auth = new RtspAuth("Mufasa:Circle Of Life");
        auth.testCnonce = "0a4f113b";
        assertTrue(auth.onUnauthorized(Collections.singletonList(RFC_CHALLENGE)));
        String h = auth.header("GET", "/dir/index.html");
        assertTrue(h, h.startsWith("Authorization: Digest "));
        assertTrue(h, h.contains("response=\"6629fae49393a05397450978507c4ef1\""));
        assertTrue(h, h.contains("nc=00000001"));
        assertTrue(h, h.contains("qop=auth"));
        assertTrue(h, h.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""));
        // The nonce count increases with every request
        assertTrue(auth.header("GET", "/dir/index.html").contains("nc=00000002"));
    }

    @Test
    public void digestWithoutQop() {
        RtspAuth auth = new RtspAuth("admin:12345");
        auth.onUnauthorized(Collections.singletonList("Digest realm=\"cam\", nonce=\"abc\""));
        String h = auth.header("DESCRIBE", "rtsp://cam/s");
        String ha1 = RtspAuth.md5("admin:cam:12345");
        String ha2 = RtspAuth.md5("DESCRIBE:rtsp://cam/s");
        assertTrue(h.contains("response=\"" + RtspAuth.md5(ha1 + ":abc:" + ha2) + "\""));
        assertFalse(h.contains("qop="));
    }

    @Test
    public void basicIsPreemptiveAndDigestIsPreferred() {
        RtspAuth auth = new RtspAuth("admin:pw");
        assertTrue(auth.header("DESCRIBE", "rtsp://cam/s").startsWith("Authorization: Basic "));
        // Basic offered first, Digest second: Digest is chosen
        assertTrue(auth.onUnauthorized(Arrays.asList("Basic realm=\"cam\"",
                "Digest realm=\"cam\", nonce=\"n1\"")));
        assertTrue(auth.header("DESCRIBE", "rtsp://cam/s").startsWith("Authorization: Digest "));
    }

    @Test
    public void noEndlessRetries() {
        RtspAuth auth = new RtspAuth("admin:wrong");
        String c = "Digest realm=\"cam\", nonce=\"n1\"";
        assertTrue(auth.onUnauthorized(Collections.singletonList(c)));
        // Same credentials rejected again: do not retry
        assertFalse(auth.onUnauthorized(Collections.singletonList(c)));
        // ...unless the server says the nonce was stale
        assertTrue(auth.onUnauthorized(Collections.singletonList(c + ", stale=TRUE")));
        // Basic only: Basic was already sent
        assertFalse(new RtspAuth("a:b").onUnauthorized(
                Collections.singletonList("Basic realm=\"cam\"")));
        // No credentials at all
        assertFalse(new RtspAuth(null).onUnauthorized(Collections.singletonList(c)));
        assertEquals("", new RtspAuth(null).header("DESCRIBE", "rtsp://cam/s"));
    }

    @Test
    public void unsupportedAlgorithmIsSkipped() {
        RtspAuth auth = new RtspAuth("a:b");
        assertFalse(auth.onUnauthorized(Collections.singletonList(
                "Digest realm=\"cam\", nonce=\"n\", algorithm=SHA-256")));
        assertTrue(auth.onUnauthorized(Arrays.asList(
                "Digest realm=\"cam\", nonce=\"n\", algorithm=SHA-256",
                "Digest realm=\"cam\", nonce=\"n\", algorithm=MD5")));
    }

    @Test
    public void md5SessUsesClientNonce() {
        RtspAuth auth = new RtspAuth("u:p");
        auth.testCnonce = "c1";
        auth.onUnauthorized(Collections.singletonList(
                "Digest realm=\"r\", nonce=\"n\", algorithm=MD5-sess"));
        String ha1 = RtspAuth.md5(RtspAuth.md5("u:r:p") + ":n:c1");
        String ha2 = RtspAuth.md5("PLAY:rtsp://x");
        String h = auth.header("PLAY", "rtsp://x");
        assertTrue(h, h.contains("response=\"" + RtspAuth.md5(ha1 + ":n:" + ha2) + "\""));
        assertTrue(h, h.contains("algorithm=MD5-SESS"));
    }

    @Test
    public void parseParamsHandlesQuotedCommas() {
        Map<String, String> p = RtspAuth.parseParams(
                " realm=\"a, b\", nonce=xyz ,qop=\"auth,auth-int\", empty=\"\"");
        assertEquals("a, b", p.get("realm"));
        assertEquals("xyz", p.get("nonce"));
        assertEquals("auth,auth-int", p.get("qop"));
        assertEquals("", p.get("empty"));
    }
}
