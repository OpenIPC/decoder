/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * AudioFormatsTest.java — Opus, MP4A-LATM and fragmented AAC (#14)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
    private static final String SMC_16K_MONO =
            "0 1 000000 0000 000 00010 1000 0001 000 000 00000000";

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
        assertEquals(0, a.push(auPacket(10, 4, 1), 0, 8, c, false).size());
        assertEquals(0, a.push(auPacket(10, 4, 2), 0, 8, c, false).size());
        List<byte[]> out = a.push(auPacket(10, 2, 3), 0, 6, c, true);
        assertEquals(1, out.size());
        assertArrayEquals(new byte[] {1, 1, 1, 1, 2, 2, 2, 2, 3, 3}, out.get(0));
    }

    @Test
    public void incompleteFragmentIsDropped() {
        AudioManager.AuAssembler a = new AudioManager.AuAssembler();
        AudioConfig c = aacHbr();
        a.push(auPacket(10, 4, 1), 0, 8, c, false);
        // Marker on the next piece, but 2 bytes are still missing
        assertEquals(0, a.push(auPacket(10, 4, 2), 0, 8, c, true).size());
        // A normal, complete AU afterwards is delivered
        List<byte[]> out = a.push(auPacket(3, 3, 7), 0, 7, c, true);
        assertEquals(1, out.size());
        assertArrayEquals(new byte[] {7, 7, 7}, out.get(0));
    }

    @Test
    public void wholeAusPassThrough() {
        AudioManager.AuAssembler a = new AudioManager.AuAssembler();
        // Two AUs (3 and 2 bytes) in one packet
        byte[] p = {0x00, 0x20, 0x00, 0x18, 0x00, 0x10, 1, 1, 1, 2, 2};
        List<byte[]> out = a.push(p, 0, p.length, aacHbr(), true);
        assertEquals(2, out.size());
        assertArrayEquals(new byte[] {2, 2}, out.get(1));
    }
}
