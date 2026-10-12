/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtspUrlTest.java — lenient rtsp:// URL parsing
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;

public class RtspUrlTest {
    @Test
    public void credentialsAreStrippedFromRequestUrl() throws IOException {
        RtspSession.Url u = RtspSession.Url.parse("rtsp://root:12345@192.168.1.10:8554/stream=0?a=1");
        assertEquals("192.168.1.10", u.host);
        assertEquals(8554, u.port);
        assertEquals("root:12345", u.userInfo);
        assertEquals("rtsp://192.168.1.10:8554/stream=0?a=1", u.requestUrl);
    }

    @Test
    public void defaultsAndNoCredentials() throws IOException {
        RtspSession.Url u = RtspSession.Url.parse("rtsp://cam");
        assertEquals("cam", u.host);
        assertEquals(554, u.port);
        assertNull(u.userInfo);
        assertEquals("rtsp://cam", u.requestUrl);
    }

    @Test
    public void passwordWithAtSignAndPercentEncoding() throws IOException {
        assertEquals("admin:p@ss", RtspSession.Url.parse("rtsp://admin:p@ss@cam/s").userInfo);
        assertEquals("admin:p@ss w", RtspSession.Url.parse("rtsp://admin:p%40ss%20w@cam/s").userInfo);
    }

    @Test
    public void ipv6Host() throws IOException {
        RtspSession.Url u = RtspSession.Url.parse("rtsp://u:p@[fe80::1]:10554/live");
        assertEquals("fe80::1", u.host);
        assertEquals(10554, u.port);
        assertEquals("rtsp://[fe80::1]:10554/live", u.requestUrl);
    }

    @Test
    public void invalidUrls() {
        assertThrows(IOException.class, () -> RtspSession.Url.parse(null));
        assertThrows(IOException.class, () -> RtspSession.Url.parse("192.168.1.10/stream"));
        assertThrows(IOException.class, () -> RtspSession.Url.parse("rtsp://:554/s"));
        assertThrows(IOException.class, () -> RtspSession.Url.parse("rtsp://cam:99999/s"));
    }
}
