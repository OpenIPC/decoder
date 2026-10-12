/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtspResponseTest.java — RTSP status line and header parsing
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class RtspResponseTest {
    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static RtspSession.Response read(String s) throws IOException {
        return RtspSession.readResponse(new BufferedInputStream(in(s)));
    }

    @Test
    public void headersAreCaseInsensitive() throws IOException {
        RtspSession.Response r = read(
                "RTSP/1.0 200 OK\r\nCSeq: 2\r\nsession: 12345678;timeout=60\r\n\r\n");
        assertTrue(r.ok());
        assertEquals("12345678;timeout=60", r.header("Session"));
        assertNull(r.header("Content-Base"));
    }

    @Test
    public void bodyIsReadByContentLength() throws IOException {
        InputStream s = new BufferedInputStream(in(
                "RTSP/1.0 200 OK\r\nContent-Length: 5\r\n\r\nv=0\r\n$rest"));
        RtspSession.Response r = RtspSession.readResponse(s);
        assertEquals("v=0\r\n", r.body);
        assertEquals('$', s.read()); // the stream continues right after the body
    }

    @Test
    public void interleavedFramesBeforeResponseAreSkipped() throws IOException {
        RtspSession.Response r = read("$\u0000\u0000\u0003abc$\u0001\u0000\u0001xRTSP/1.0 200 OK\r\n\r\n");
        assertEquals(200, r.code);
    }

    @Test
    public void errorStatusIsNotOk() throws IOException {
        RtspSession.Response r = read("RTSP/1.0 401 Unauthorized\r\n\r\n");
        assertEquals(401, r.code);
        assertThrows(IOException.class, () -> r.require("DESCRIBE"));
    }

    @Test
    public void malformedResponsesThrow() {
        assertThrows(IOException.class, () -> read("garbage\r\n\r\n"));
        assertThrows(IOException.class, () -> read(""));
        assertThrows(IOException.class, () -> read("RTSP/1.0 200 OK\r\nContent-Length: -1\r\n\r\n"));
        assertThrows(IOException.class, () -> read("RTSP/1.0 200 OK\r\nContent-Length: 10\r\n\r\nshort"));
    }

    @Test
    public void tooLongLineIsRejected() {
        StringBuilder sb = new StringBuilder("RTSP/1.0 200 OK\r\nX: ");
        for (int i = 0; i < 9000; i++) sb.append('a');
        assertThrows(IOException.class, () -> read(sb + "\r\n\r\n"));
    }
}
