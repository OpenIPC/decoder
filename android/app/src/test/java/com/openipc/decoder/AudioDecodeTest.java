/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * AudioDecodeTest.java — G.711 / L16 conversion and AAC helpers
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class AudioDecodeTest {
    @Test
    public void ulawReferenceValues() {
        assertEquals(0, AudioManager.ulawToLinear(0xFF));
        assertEquals(0, AudioManager.ulawToLinear(0x7F));
        assertEquals(-32124, AudioManager.ulawToLinear(0x00));
        assertEquals(32124, AudioManager.ulawToLinear(0x80));
        for (int i = 0x80; i < 0xFF; i++) {
            assertTrue("monotonic at " + i,
                    AudioManager.ulawToLinear(i) > AudioManager.ulawToLinear(i + 1));
        }
    }

    @Test
    public void alawReferenceValues() {
        assertEquals(8, AudioManager.alawToLinear(0xD5));
        assertEquals(-8, AudioManager.alawToLinear(0x55));
        assertEquals(32256, AudioManager.alawToLinear(0xAA));
        assertEquals(-32256, AudioManager.alawToLinear(0x2A));
    }

    @Test
    public void g711OutputIsLittleEndian16Bit() {
        short[] table = new short[256];
        table[0x10] = 0x1234;
        byte[] pcm = AudioManager.decodeG711(new byte[] {9, 0x10}, 1, 2, table);
        assertArrayEquals(new byte[] {0x34, 0x12}, pcm);
    }

    @Test
    public void l16SwapsBigEndianAndDropsOddByte() {
        byte[] in = {9, 1, 2, 3, 4, 5};
        assertArrayEquals(new byte[] {2, 1, 4, 3}, AudioManager.decodeL16(in, 1, 6, true));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, AudioManager.decodeL16(in, 1, 6, false));
    }

    @Test
    public void aacAudioSpecificConfig() {
        assertArrayEquals(new byte[] {0x11, (byte) 0x88}, AudioManager.buildAacConfig(48000, 1));
        assertArrayEquals(new byte[] {0x12, 0x10}, AudioManager.buildAacConfig(44100, 2));
        assertArrayEquals(new byte[] {0x15, (byte) 0x88}, AudioManager.buildAacConfig(8000, 1));
    }

    @Test
    public void readBitsAcrossBytes() {
        // AU header: size = 5 (13 bits), index = 0 (3 bits)
        byte[] h = {0x00, 0x28};
        assertEquals(5, AudioManager.readBits(h, 0, 13));
        assertEquals(0, AudioManager.readBits(h, 13, 3));
        assertEquals(0b1011, AudioManager.readBits(new byte[] {(byte) 0b0101_1000}, 1, 4));
    }

    /** ADTS frame of the given total length (7-byte header without CRC + payload). */
    private static byte[] adts(int length, int fill) {
        byte[] f = new byte[length];
        f[0] = (byte) 0xFF;
        f[1] = (byte) 0xF1;
        f[2] = 0x50;
        f[3] = (byte) (0x80 | ((length >> 11) & 0x03));
        f[4] = (byte) ((length >> 3) & 0xFF);
        f[5] = (byte) (((length & 0x07) << 5) | 0x1F);
        f[6] = (byte) 0xFC;
        for (int i = 7; i < length; i++) f[i] = (byte) fill;
        return f;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    @Test
    public void adtsSingleAndMultipleFrames() {
        byte[] one = adts(20, 1);
        List<int[]> f = AudioManager.splitAdts(one, 0, one.length);
        assertEquals(1, f.size());
        assertArrayEquals(new int[] {0, 20}, f.get(0));

        // Two frames after a 12-byte RTP header
        byte[] two = concat(new byte[12], adts(10, 2), adts(15, 3));
        f = AudioManager.splitAdts(two, 12, two.length);
        assertEquals(2, f.size());
        assertArrayEquals(new int[] {12, 10}, f.get(0));
        assertArrayEquals(new int[] {22, 15}, f.get(1));
    }

    @Test
    public void adtsStopsAtBadSyncLengthOrTruncation() {
        // Trailing truncated frame is dropped
        byte[] truncated = concat(adts(10, 1), java.util.Arrays.copyOf(adts(30, 2), 20));
        assertEquals(1, AudioManager.splitAdts(truncated, 0, truncated.length).size());

        // Garbage after a valid frame stops the split
        byte[] garbage = concat(adts(10, 1), new byte[] {0x12, 0x34, 0, 0, 0, 0, 0, 0});
        assertEquals(1, AudioManager.splitAdts(garbage, 0, garbage.length).size());

        // Frame length smaller than the header is rejected
        byte[] bad = adts(10, 1);
        bad[4] = 0;
        bad[5] = (byte) ((3 << 5) | 0x1F); // length 3
        assertEquals(0, AudioManager.splitAdts(bad, 0, bad.length).size());

        // Layer bits must be zero
        byte[] layer = adts(10, 1);
        layer[1] = (byte) 0xF7;
        assertFalse(AudioManager.isAdtsSync(layer, 0));
        assertTrue(AudioManager.isAdtsSync(adts(10, 1), 0));
    }

    @Test
    public void rfc3640TwoAccessUnits() {
        // AU-headers-length 32 bits: two 16-bit headers (size 13 bits + index 3 bits)
        byte[] p = concat(new byte[] {0x00, 0x20, 0x00, 0x18, 0x00, 0x10},
                new byte[] {1, 1, 1}, new byte[] {2, 2});
        List<int[]> u = AudioManager.splitAuHeaders(p, 0, p.length, 13, 3, 3);
        assertEquals(2, u.size());
        assertArrayEquals(new int[] {6, 3}, u.get(0));
        assertArrayEquals(new int[] {9, 2}, u.get(1));
    }

    @Test
    public void rfc3640FragmentedOrShortPayload() {
        // AU size 100 but only 3 bytes present: fragmented AU is not delivered
        byte[] frag = {0x00, 0x10, 0x03, 0x20, 1, 2, 3};
        assertEquals(0, AudioManager.splitAuHeaders(frag, 0, frag.length, 13, 3, 3).size());
        // Header length points past the packet
        byte[] past = {0x01, 0x00, 0x00};
        assertEquals(0, AudioManager.splitAuHeaders(past, 0, past.length, 13, 3, 3).size());
        // Too short for AU-headers-length
        assertEquals(0, AudioManager.splitAuHeaders(new byte[] {0}, 0, 1, 13, 3, 3).size());
    }
}
