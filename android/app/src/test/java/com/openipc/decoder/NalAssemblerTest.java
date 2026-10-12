/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * NalAssemblerTest.java — NAL unit reassembly from RTP single NAL and FU packets
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public class NalAssemblerTest {
    private int codecSwitches;
    private final NalAssembler nal = new NalAssembler(1024 * 1024, () -> codecSwitches++,
            new FramePool(4));

    /** RTP packet: 12-byte header followed by the given payload bytes. */
    private static Frame rtp(int... payload) {
        byte[] d = new byte[12 + payload.length];
        d[0] = (byte) 0x80;
        for (int i = 0; i < payload.length; i++) d[12 + i] = (byte) payload[i];
        return new Frame(d, d.length);
    }

    private static byte[] bytes(Frame f) {
        return Arrays.copyOf(f.data(), f.length());
    }

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    @Test
    public void h264SingleNal() {
        Frame out = nal.assemble(rtp(0x67, 0x42, 0x00, 0x1F), false);
        assertArrayEquals(b(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F), bytes(out));
    }

    @Test
    public void h264FuA() {
        // FU indicator: NRI 3, type 28; FU header: S/E + type 5 (IDR)
        assertNull(nal.assemble(rtp(0x7C, 0x85, 0xA1, 0xA2), false));
        assertNull(nal.assemble(rtp(0x7C, 0x05, 0xB1), false));
        Frame out = nal.assemble(rtp(0x7C, 0x45, 0xC1, 0xC2), false);
        assertArrayEquals(b(0, 0, 0, 1, 0x65, 0xA1, 0xA2, 0xB1, 0xC1, 0xC2), bytes(out));
    }

    @Test
    public void h265SingleNal() {
        Frame out = nal.assemble(rtp(0x40, 0x01, 0x0C, 0x01), true);
        assertArrayEquals(b(0, 0, 0, 1, 0x40, 0x01, 0x0C, 0x01), bytes(out));
    }

    @Test
    public void h265Fu() {
        // Payload header: type 49, layer 0, TID 1 (0x62 0x01); FU header: S/E + type 19 (IDR_W_RADL)
        assertNull(nal.assemble(rtp(0x62, 0x01, 0x93, 0xA1, 0xA2), true));
        assertNull(nal.assemble(rtp(0x62, 0x01, 0x13, 0xB1), true));
        Frame out = nal.assemble(rtp(0x62, 0x01, 0x53, 0xC1), true);
        // NAL header must be 0x26 0x01 (type 19, layer 0, TID 1)
        assertArrayEquals(b(0, 0, 0, 1, 0x26, 0x01, 0xA1, 0xA2, 0xB1, 0xC1), bytes(out));
    }

    @Test
    public void codecSwitchResetsAndNotifies() {
        nal.assemble(rtp(0x67, 0x42), false);
        assertEquals(0, codecSwitches);
        nal.assemble(rtp(0x40, 0x01, 0x0C), true);
        assertEquals(1, codecSwitches);
    }

    @Test
    public void emptyPayloadIsIgnored() {
        assertNull(nal.assemble(rtp(), false));
    }
}
