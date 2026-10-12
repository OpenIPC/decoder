/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * AudioFormatsTest.java — Opus, MP4A-LATM and fragmented AAC (#14)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

public class AudioFormatsTest {
    /** Builds a byte array from a string of '0'/'1' bits (other characters ignored). */
    private static byte[] bits(String s) {
        String b = s.replaceAll("[^01]", "");
        byte[] out = new byte[(b.length() + 7) / 8];
        for (int i = 0; i < b.length(); i++) {
            if (b.charAt(i) == '1') out[i / 8] |= (byte) (0x80 >> (i % 8));
        }
        return out;
    }

    // StreamMuxConfig: version 0, sameTimeFraming 1, numSubFrames 0, numProgram 0,
    // numLayer 0, then ASC: AAC LC (2), 16 kHz (index 8), mono (1), GASpecific 000
    // ... GASpecificConfig 000, frameLengthType 000, latmBufferFullness 0xFF,
    // otherDataPresent 0, crcCheckPresent 0
    private static final String SMC_16K_MONO =
            "0 1 000000 0000 000 00010 1000 0001 000 000 11111111 0 0";

    /** SMC_16K_MONO with the bits from {@code from} replaced by {@code bits}. */
    private static String smcWith(int from, String bits) {
        String b = SMC_16K_MONO.replaceAll("[^01]", "");
        return b.substring(0, from) + bits + b.substring(from + bits.length());
    }

    private static AudioConfig parse(String sdp) {
        return Sdp.parse(sdp, "rtsp://x").audio;
    }

    @Test
    public void opusFromSdp() {
        AudioConfig a = parse("m=audio 0 RTP/AVP 111\r\na=rtpmap:111 opus/48000/2\r\n");
        assertEquals(AudioConfig.OPUS, a.codec);
        assertEquals(48000, a.sampleRate);
        assertEquals(2, a.channels);
    }

    @Test
    public void opusHeaderLayout() {
        byte[] h = AudioManager.opusHead(2);
        assertEquals(19, h.length);
        assertEquals("OpusHead", new String(h, 0, 8, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(1, h[8]);
        assertEquals(2, h[9]);
        // 48000 little-endian at offset 12
        assertArrayEquals(new byte[] {(byte) 0x80, (byte) 0xBB, 0, 0},
                java.util.Arrays.copyOfRange(h, 12, 16));
        assertEquals(0, h[18]);
    }

    @Test
    public void latmStreamMuxConfigToAsc() {
        byte[] asc = AudioConfig.latmToAsc(bits(SMC_16K_MONO));
        assertArrayEquals(AudioManager.buildAacConfig(16000, 1), asc);
        assertArrayEquals(new int[] {16000, 1}, AudioConfig.ascInfo(asc));
    }

    @Test
    public void latmFromSdp() {
        StringBuilder hex = new StringBuilder();
        for (byte b : bits(SMC_16K_MONO)) hex.append(String.format("%02x", b & 0xFF));
        AudioConfig a = parse("m=audio 0 RTP/AVP 96\r\na=rtpmap:96 MP4A-LATM/16000/1\r\n"
                + "a=fmtp:96 profile-level-id=15; object=2; cpresent=0; config=" + hex + "\r\n");
        assertEquals(AudioConfig.LATM, a.codec);
        assertEquals(16000, a.sampleRate);
        assertEquals(1, a.channels);
        assertArrayEquals(AudioManager.buildAacConfig(16000, 1), a.aacConfig);
    }

    @Test
    public void unsupportedLatmLayouts() {
        assertEquals(AudioConfig.NONE, parse("m=audio 0 RTP/AVP 96\r\n"
                + "a=rtpmap:96 MP4A-LATM/16000\r\na=fmtp:96 cpresent=1\r\n").codec);
        assertEquals(AudioConfig.NONE, parse("m=audio 0 RTP/AVP 96\r\n"
                + "a=rtpmap:96 MP4A-LATM/16000\r\n").codec);
        // audioMuxVersion 1
        assertNull(AudioConfig.latmToAsc(bits("1" + SMC_16K_MONO.substring(1))));
        // two programs
        assertNull(AudioConfig.latmToAsc(bits("0 1 000000 0001 000 00010 1000 0001 000")));
    }

    @Test
    public void latmPayloadLengths() {
        byte[] p = new byte[2 + 1 + 3 + 2 + 257];
        p[2] = 3;                 // first AU: 3 bytes
        p[6] = (byte) 0xFF;       // second AU: 255 + 2 = 257 bytes
        p[7] = 2;
        List<int[]> u = AudioManager.splitLatm(p, 2, p.length);
        assertEquals(2, u.size());
        assertArrayEquals(new int[] {3, 3}, u.get(0));
        assertArrayEquals(new int[] {8, 257}, u.get(1));
        // Truncated: the length points past the end
        assertEquals(1, AudioManager.splitLatm(p, 2, p.length - 1).size());
    }

    private static AudioConfig aacHbr() {
        AudioConfig c = new AudioConfig();
        c.codec = AudioConfig.AAC;
        c.sizeLength = 13;
        c.indexLength = 3;
        c.indexDeltaLength = 3;
        return c;
    }

    /** RFC 3640 packet: one 16-bit AU header (size << 3) and the given piece. */
    private static byte[] auPacket(int auSize, int pieceLen, int fill) {
        byte[] p = new byte[4 + pieceLen];
        p[1] = 16;
        p[2] = (byte) ((auSize << 3) >> 8);
        p[3] = (byte) (auSize << 3);
        for (int i = 0; i < pieceLen; i++) p[4 + i] = (byte) fill;
        return p;
    }

    @Test
    public void fragmentedAuIsReassembled() {
        AudioManager.AuAssembler a = new AudioManager.AuAssembler();
        AudioConfig c = aacHbr();
        assertEquals(0, a.push(auPacket(10, 4, 1), 0, 8, c, false, 1).size());
        assertEquals(0, a.push(auPacket(10, 4, 2), 0, 8, c, false, 2).size());
        List<byte[]> out = a.push(auPacket(10, 2, 3), 0, 6, c, true, 3);
        assertEquals(1, out.size());
        assertArrayEquals(new byte[] {1, 1, 1, 1, 2, 2, 2, 2, 3, 3}, out.get(0));
    }

    @Test
    public void incompleteFragmentIsDropped() {
        AudioManager.AuAssembler a = new AudioManager.AuAssembler();
        AudioConfig c = aacHbr();
        a.push(auPacket(10, 4, 1), 0, 8, c, false, 4);
        // Marker on the next piece, but 2 bytes are still missing
        assertEquals(0, a.push(auPacket(10, 4, 2), 0, 8, c, true, 5).size());
        // A normal, complete AU afterwards is delivered
        List<byte[]> out = a.push(auPacket(3, 3, 7), 0, 7, c, true, 6);
        assertEquals(1, out.size());
        assertArrayEquals(new byte[] {7, 7, 7}, out.get(0));
    }

    @Test
    public void wholeAusPassThrough() {
        AudioManager.AuAssembler a = new AudioManager.AuAssembler();
        // Two AUs (3 and 2 bytes) in one packet
        byte[] p = {0x00, 0x20, 0x00, 0x18, 0x00, 0x10, 1, 1, 1, 2, 2};
        List<byte[]> out = a.push(p, 0, p.length, aacHbr(), true, 7);
        assertEquals(2, out.size());
        assertArrayEquals(new byte[] {2, 2}, out.get(1));
    }

    @Test
    public void lossBetweenFragmentsDropsTheStaleAu() {
        AudioManager.AuAssembler a = new AudioManager.AuAssembler();
        AudioConfig c = aacHbr();
        a.push(auPacket(10, 4, 1), 0, 8, c, false, 100);
        // Packet 101 lost; 102 is the start of a new AU of the same size
        assertEquals(0, a.push(auPacket(10, 4, 2), 0, 8, c, false, 102).size());
        assertEquals(0, a.push(auPacket(10, 4, 3), 0, 8, c, false, 103).size());
        List<byte[]> out = a.push(auPacket(10, 2, 4), 0, 6, c, true, 104);
        assertEquals(1, out.size());
        assertArrayEquals("only bytes of the new AU",
                new byte[] {2, 2, 2, 2, 3, 3, 3, 3, 4, 4}, out.get(0));
    }

    @Test
    public void latmElementAcrossPackets() {
        AudioManager.LatmAssembler a = new AudioManager.LatmAssembler();
        byte[] part1 = {5, 1, 1};        // length 5, first 2 bytes
        byte[] part2 = {1, 1, 1};        // remaining 3 bytes, marker set
        assertNull(a.push(part1, 0, 3, false, 10));
        byte[] element = a.push(part2, 0, 3, true, 11);
        assertArrayEquals(new byte[] {5, 1, 1, 1, 1, 1}, element);
        List<int[]> u = AudioManager.splitLatm(element, 0, element.length);
        assertArrayEquals(new int[] {1, 5}, u.get(0));
    }

    @Test
    public void latmElementWithALostPieceIsDropped() {
        AudioManager.LatmAssembler a = new AudioManager.LatmAssembler();
        assertNull(a.push(new byte[] {5, 1}, 0, 2, false, 10));
        assertNull("piece 11 lost", a.push(new byte[] {1, 1}, 0, 2, true, 12));
        // The next element is fine
        assertArrayEquals(new byte[] {1, 9}, a.push(new byte[] {1, 9}, 0, 2, true, 13));
    }

    @Test
    public void latmRejectsLayoutsTheRtpPathCannotHandle() {
        // bit offsets in SMC_16K_MONO: 1 sameTimeFraming, 2..7 numSubFrames,
        // 15..19 objectType, 20..23 freqIndex, 24..27 channels, 31..33 frameLengthType,
        // 42 otherDataPresent, 43 crcCheckPresent
        assertNotNull(AudioConfig.latmToAsc(bits(SMC_16K_MONO)));
        assertNull("not same time framing", AudioConfig.latmToAsc(bits(smcWith(1, "0"))));
        assertNull("two subframes", AudioConfig.latmToAsc(bits(smcWith(2, "000001"))));
        assertNull("AAC Main", AudioConfig.latmToAsc(bits(smcWith(15, "00001"))));
        assertNull("reserved rate", AudioConfig.latmToAsc(bits(smcWith(20, "1101"))));
        assertNull("5.1", AudioConfig.latmToAsc(bits(smcWith(24, "0110"))));
        assertNull("frameLengthType 1", AudioConfig.latmToAsc(bits(smcWith(31, "001"))));
        assertNull("other data", AudioConfig.latmToAsc(bits(smcWith(42, "1"))));
        assertNull("CRC", AudioConfig.latmToAsc(bits(smcWith(43, "1"))));
        assertNull("multichannel ASC info", AudioConfig.ascInfo(new byte[] {0x14, 0x30}));
    }
}
