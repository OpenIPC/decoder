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

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class RtspResponseTest {
    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void returnsRequestedHeaderValueWithoutParameters() throws IOException {
        String session = RtspClient.readRtspResponse(
                in("RTSP/1.0 200 OK\r\nCSeq: 2\r\nsession: 12345678;timeout=60\r\n\r\n"),
                "Session:");
        assertEquals("12345678", session);
    }

    @Test
    public void missingHeaderIsNull() throws IOException {
        assertNull(RtspClient.readRtspResponse(in("RTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\n"), "Session:"));
    }

    @Test
    public void errorAndMalformedStatusThrow() {
        assertThrows(IOException.class, () -> RtspClient.readRtspResponse(
                in("RTSP/1.0 401 Unauthorized\r\n\r\n"), null));
        assertThrows(IOException.class, () -> RtspClient.readRtspResponse(in("garbage\r\n\r\n"), null));
        assertThrows(IOException.class, () -> RtspClient.readRtspResponse(in(""), null));
    }

    @Test
    public void tooLongLineIsRejected() {
        StringBuilder sb = new StringBuilder("RTSP/1.0 200 OK\r\nX: ");
        for (int i = 0; i < 9000; i++) sb.append('a');
        assertThrows(IOException.class, () -> RtspClient.readRtspResponse(in(sb + "\r\n\r\n"), null));
    }
}
