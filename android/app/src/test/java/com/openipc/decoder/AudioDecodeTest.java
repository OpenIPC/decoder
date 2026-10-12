/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * AudioDecodeTest.java — G.711 / L16 conversion and AAC helpers
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}
