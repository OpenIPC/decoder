/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtpTest.java — RTP header helpers and RTP-over-TCP interleaved framing
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public class RtpTest {
    @Test
    public void headerLengthWithCsrcAndExtension() {
        byte[] p = new byte[40];
        p[0] = (byte) 0x80;
        assertEquals(12, RtspClient.rtpHeaderLength(p, 40));
        p[0] = (byte) 0x82; // 2 CSRC
        assertEquals(20, RtspClient.rtpHeaderLength(p, 40));
        p[0] = (byte) 0x90; // extension, length 2 words at bytes 14..15
        p[15] = 2;
        assertEquals(24, RtspClient.rtpHeaderLength(p, 40));
    }

    @Test
    public void malformedHeaders() {
        byte[] p = new byte[40];
        p[0] = (byte) 0x40; // version 1
        assertEquals(-1, RtspClient.rtpHeaderLength(p, 40));
        p[0] = (byte) 0x80;
        assertEquals(-1, RtspClient.rtpHeaderLength(p, 11));
        p[0] = (byte) 0x90;
        p[15] = 100; // extension longer than the packet
        assertEquals(-1, RtspClient.rtpHeaderLength(p, 40));
    }

    @Test
    public void paddingIsRemovedFromPayloadEnd() {
        byte[] p = new byte[40];
        p[0] = (byte) 0xA0;
        p[39] = 4;
        assertEquals(36, RtspClient.rtpPayloadEnd(p, 40));
        p[0] = (byte) 0x80;
        assertEquals(40, RtspClient.rtpPayloadEnd(p, 40));
    }

    private static BufferedInputStream stream(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) b[i] = (byte) bytes[i];
        return new BufferedInputStream(new ByteArrayInputStream(b));
    }

    @Test
    public void interleavedPacketAfterGarbage() throws IOException {
        byte[] buf = new byte[65535];
        // 'R' 'T' garbage, then $ channel 2 length 3
        int total = RtspClient.readInterleavedPacket(stream('R', 'T', '$', 2, 0, 3, 7, 8, 9), buf);
        assertEquals(7, total);
        assertEquals(2, buf[1]);
        assertEquals(9, buf[6]);
    }

    @Test
    public void cleanEofAndTruncation() throws IOException {
        byte[] buf = new byte[65535];
        assertEquals(-1, RtspClient.readInterleavedPacket(stream(), buf));
        assertThrows(IOException.class, () -> RtspClient.readInterleavedPacket(stream('$', 0), buf));
        assertThrows(IOException.class,
                () -> RtspClient.readInterleavedPacket(stream('$', 0, 0, 5, 1, 2), buf));
    }
}
