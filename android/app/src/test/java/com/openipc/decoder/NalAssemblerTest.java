/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * NalAssemblerTest.java — NAL units from RTP: single, FU, STAP-A/AP, headers and loss
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NalAssemblerTest {
    private int codecSwitches;
    private final List<byte[]> out = new ArrayList<>();
    private final NalAssembler.Sink sink = nal -> out.add(Arrays.copyOf(nal.data(), nal.length()));
    private NalAssembler nal = new NalAssembler(1024 * 1024, () -> codecSwitches++, new FramePool(4));
    private int seq = 100;

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    /** RTP packet with the next sequence number: 12-byte header + payload. */
    private byte[] rtp(int... payload) {
        return rtpWithHeader(b(0x80, 96, (seq >> 8) & 0xFF, seq++ & 0xFF, 0, 0, 0, 0, 0, 0, 0, 0),
                payload);
    }

    private static byte[] rtpWithHeader(byte[] header, int... payload) {
        byte[] p = Arrays.copyOf(header, header.length + payload.length);
        for (int i = 0; i < payload.length; i++) p[header.length + i] = (byte) payload[i];
        return p;
    }

    private boolean feed(byte[] packet, boolean h265) {
        return nal.assemble(packet, 0, packet.length, h265, sink);
    }

    @Test
    public void h264SingleNal() {
        feed(rtp(0x67, 0x42, 0x00, 0x1F), false);
        assertEquals(1, out.size());
        assertArrayEquals(b(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F), out.get(0));
    }

    @Test
    public void h264FuA() {
        // FU indicator: NRI 3, type 28; FU header: S/E + type 5 (IDR)
        feed(rtp(0x7C, 0x85, 0xA1, 0xA2), false);
        feed(rtp(0x7C, 0x05, 0xB1), false);
        assertEquals(0, out.size());
        feed(rtp(0x7C, 0x45, 0xC1, 0xC2), false);
        assertArrayEquals(b(0, 0, 0, 1, 0x65, 0xA1, 0xA2, 0xB1, 0xC1, 0xC2), out.get(0));
    }

    @Test
    public void h265SingleNal() {
        feed(rtp(0x40, 0x01, 0x0C, 0x01), true);
        assertArrayEquals(b(0, 0, 0, 1, 0x40, 0x01, 0x0C, 0x01), out.get(0));
    }

    @Test
    public void h265Fu() {
        // Payload header: type 49, layer 0, TID 1 (0x62 0x01); FU header: S/E + type 19
        feed(rtp(0x62, 0x01, 0x93, 0xA1, 0xA2), true);
        feed(rtp(0x62, 0x01, 0x13, 0xB1), true);
        feed(rtp(0x62, 0x01, 0x53, 0xC1), true);
        assertEquals(1, out.size());
        // NAL header must be 0x26 0x01 (type 19, layer 0, TID 1)
        assertArrayEquals(b(0, 0, 0, 1, 0x26, 0x01, 0xA1, 0xA2, 0xB1, 0xC1), out.get(0));
    }

    @Test
    public void h264StapA() {
        // STAP-A (NRI 3, type 24): SPS (3 bytes) + PPS (2 bytes)
        feed(rtp(0x78, 0x00, 0x03, 0x67, 0x42, 0x1F, 0x00, 0x02, 0x68, 0xCE), false);
        assertEquals(2, out.size());
        assertArrayEquals(b(0, 0, 0, 1, 0x67, 0x42, 0x1F), out.get(0));
        assertArrayEquals(b(0, 0, 0, 1, 0x68, 0xCE), out.get(1));
    }

    @Test
    public void h265AggregationPacket() {
        // AP (type 48 -> 0x60 0x01): VPS (3 bytes) + SPS (2 bytes)
        feed(rtp(0x60, 0x01, 0x00, 0x03, 0x40, 0x01, 0x0C, 0x00, 0x02, 0x42, 0x01), true);
        assertEquals(2, out.size());
        assertArrayEquals(b(0, 0, 0, 1, 0x40, 0x01, 0x0C), out.get(0));
        assertArrayEquals(b(0, 0, 0, 1, 0x42, 0x01), out.get(1));
    }

    @Test
    public void truncatedAggregationStopsAtTheBadUnit() {
        feed(rtp(0x78, 0x00, 0x02, 0x67, 0x42, 0x00, 0x09, 0x68), false);
        assertEquals(1, out.size());
    }

    @Test
    public void csrcExtensionAndPaddingAreSkipped() {
        // V=2, P=1, X=1, CC=1: 12 + 4 (CSRC) + 4 + 4 (extension, 1 word) header, 2 bytes padding
        byte[] header = b(0xB1, 96, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
                1, 2, 3, 4,
                0xBE, 0xDE, 0x00, 0x01, 9, 9, 9, 9);
        byte[] p = rtpWithHeader(header, 0x67, 0x42, 0x1F, 0x00, 0x02);
        assertTrue(feed(p, false));
        assertArrayEquals(b(0, 0, 0, 1, 0x67, 0x42, 0x1F), out.get(0));
    }

    @Test
    public void malformedHeaderIsRejected() {
        assertFalse(feed(b(0x40, 96, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0x67), false));
        // CSRC count larger than the packet
        assertFalse(feed(b(0x8F, 96, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0x67), false));
        assertEquals(0, out.size());
    }

    @Test
    public void lostMiddleFragmentDropsTheNal() {
        feed(rtp(0x7C, 0x85, 0xA1), false);
        seq++; // one packet lost
        feed(rtp(0x7C, 0x05, 0xB1), false);
        feed(rtp(0x7C, 0x45, 0xC1), false);
        assertEquals(0, out.size());
        assertEquals(1, nal.droppedCount());
        // The next complete unit is assembled normally
        feed(rtp(0x7C, 0x85, 0xD1), false);
        feed(rtp(0x7C, 0x45, 0xD2), false);
        assertArrayEquals(b(0, 0, 0, 1, 0x65, 0xD1, 0xD2), out.get(0));
    }

    @Test
    public void continuationWithoutStartIsIgnoredAndCountedOnce() {
        feed(rtp(0x7C, 0x05, 0xB1), false);
        feed(rtp(0x7C, 0x05, 0xB2), false);
        feed(rtp(0x7C, 0x45, 0xC1), false);
        assertEquals(0, out.size());
        assertEquals("one lost unit, not one per piece", 1, nal.droppedCount());
        // The next orphan unit is a new loss
        feed(rtp(0x7C, 0x05, 0xD1), false);
        assertEquals(2, nal.droppedCount());
    }

    @Test
    public void otherPacketInterruptsAFragment() {
        feed(rtp(0x7C, 0x85, 0xA1), false);       // FU start
        feed(rtp(0x41, 0x01), false);             // single NAL in between
        feed(rtp(0x7C, 0x45, 0xA2), false);       // continuation must not reuse stale bytes
        assertEquals(1, out.size());
        assertArrayEquals(b(0, 0, 0, 1, 0x41, 0x01), out.get(0));
        assertEquals(1, nal.droppedCount());      // the interrupted unit, counted once
    }

    @Test
    public void startWithoutEndIsDroppedOnNextStart() {
        feed(rtp(0x7C, 0x85, 0xA1), false);
        feed(rtp(0x7C, 0x85, 0xB1), false); // new start, previous never ended
        feed(rtp(0x7C, 0x45, 0xB2), false);
        assertEquals(1, out.size());
        assertArrayEquals(b(0, 0, 0, 1, 0x65, 0xB1, 0xB2), out.get(0));
        assertEquals(1, nal.droppedCount());
    }

    @Test
    public void lossBetweenSingleNalsIsHarmless() {
        feed(rtp(0x41, 0x01), false);
        seq += 5;
        feed(rtp(0x41, 0x02), false);
        assertEquals(2, out.size());
        assertEquals(0, nal.droppedCount());
    }

    @Test
    public void sequenceNumberWrapIsNotALoss() {
        seq = 65535;
        feed(rtp(0x7C, 0x85, 0xA1), false);
        feed(rtp(0x7C, 0x45, 0xA2), false); // seq 0
        assertEquals(1, out.size());
        assertEquals(0, nal.droppedCount());
    }

    @Test
    public void oversizedNalIsDroppedNotTruncated() {
        nal = new NalAssembler(16, () -> codecSwitches++, new FramePool(4));
        feed(rtp(0x7C, 0x85, 1, 2, 3, 4, 5, 6, 7, 8), false);
        feed(rtp(0x7C, 0x45, 1, 2, 3, 4, 5, 6, 7, 8), false);
        assertEquals(0, out.size());
        assertEquals(1, nal.droppedCount());
    }

    @Test
    public void codecSwitchResetsAndNotifies() {
        feed(rtp(0x67, 0x42), false);
        assertEquals(0, codecSwitches);
        feed(rtp(0x40, 0x01, 0x0C), true);
        assertEquals(1, codecSwitches);
    }

    @Test
    public void emptyPayloadIsIgnored() {
        assertTrue(feed(rtp(), false));
        assertEquals(0, out.size());
    }

    @Test
    public void packetAtAnOffset() {
        byte[] packet = rtp(0x67, 0x42);
        byte[] buf = new byte[packet.length + 4];
        System.arraycopy(packet, 0, buf, 4, packet.length);
        assertTrue(nal.assemble(buf, 4, packet.length, false, sink));
        assertArrayEquals(b(0, 0, 0, 1, 0x67, 0x42), out.get(0));
    }
}
