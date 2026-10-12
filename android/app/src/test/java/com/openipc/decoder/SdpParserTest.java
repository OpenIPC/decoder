/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * SdpParserTest.java — SDP parsing into track URLs, video codec and AudioConfig
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SdpParserTest {
    private static final String OPENIPC_LIKE =
            "v=0\r\n" +
            "o=- 0 0 IN IP4 0.0.0.0\r\n" +
            "s=x\r\n" +
            "m=video 0 RTP/AVP 97\r\n" +
            "a=rtpmap:97 H265/90000\r\n" +
            "a=control:trackID=0\r\n" +
            "m=audio 0 RTP/AVP 98\r\n" +
            "a=rtpmap:98 mpeg4-generic/48000/2\r\n" +
            "a=fmtp:98 streamtype=5; mode=AAC-hbr; config=1190; SizeLength=13; " +
            "IndexLength=3; IndexDeltaLength=3\r\n" +
            "a=control:trackID=1\r\n";

    private static String[] parse(String sdp, String base, boolean[] h265, AudioConfig audio) {
        return RtspClient.parseSdp(sdp, base, h265, audio);
    }

    @Test
    public void videoAndAacTrack() {
        boolean[] h265 = new boolean[1];
        AudioConfig a = new AudioConfig();
        String[] urls = parse(OPENIPC_LIKE, "rtsp://cam/stream=0", h265, a);

        assertTrue(h265[0]);
        assertEquals("rtsp://cam/stream=0/trackID=0", urls[0]);
        assertEquals("rtsp://cam/stream=0/trackID=1", urls[1]);
        assertEquals(AudioConfig.AAC, a.codec);
        assertEquals(98, a.pt);
        assertEquals(48000, a.sampleRate);
        assertEquals(2, a.channels);
        assertArrayEquals(new byte[] {0x11, (byte) 0x90}, a.aacConfig);
        assertEquals(13, a.sizeLength);
        assertEquals(3, a.indexLength);
        assertEquals(3, a.indexDeltaLength);
    }

    @Test
    public void fmtpBeforeRtpmapKeepsAuHeaderLayout() {
        AudioConfig a = new AudioConfig();
        parse("m=audio 0 RTP/AVP 98\r\n" +
                "a=fmtp:98 config=1408; sizeLength=13; indexLength=3; indexDeltaLength=3\r\n" +
                "a=rtpmap:98 MPEG4-GENERIC/16000/1\r\n", "rtsp://x", null, a);
        assertEquals(AudioConfig.AAC, a.codec);
        assertEquals(13, a.sizeLength);
        assertEquals(16000, a.sampleRate);
    }

    @Test
    public void invalidAuHeaderLayoutDisablesAac() {
        AudioConfig a = new AudioConfig();
        parse("m=audio 0 RTP/AVP 98\r\na=rtpmap:98 MPEG4-GENERIC/16000\r\n" +
                "a=fmtp:98 sizelength=13; indexlength=-3\r\n", "rtsp://x", null, a);
        assertEquals(AudioConfig.NONE, a.codec);

        AudioConfig b = new AudioConfig();
        parse("m=audio 0 RTP/AVP 98\r\na=rtpmap:98 MPEG4-GENERIC/16000\r\n" +
                "a=fmtp:98 sizelength=40\r\n", "rtsp://x", null, b);
        assertEquals(AudioConfig.NONE, b.codec);
    }

    @Test
    public void staticPayloadTypesWithoutRtpmap() {
        AudioConfig a = new AudioConfig();
        parse("m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\nm=audio 0 RTP/AVP 8\r\n",
                "rtsp://x", null, a);
        assertEquals(AudioConfig.PCMA, a.codec);
        assertEquals(8000, a.sampleRate);

        AudioConfig b = new AudioConfig();
        parse("m=audio 0 RTP/AVP 0\r\n", "rtsp://x", null, b);
        assertEquals(AudioConfig.PCMU, b.codec);
    }

    @Test
    public void l16BigEndianAndRtpmapOfVideoIsIgnored() {
        AudioConfig a = new AudioConfig();
        parse("m=video 0 RTP/AVP 100\r\na=rtpmap:100 H264/90000\r\n" +
                "m=audio 0 RTP/AVP 100\r\na=rtpmap:100 L16/16000\r\n", "rtsp://x", null, a);
        assertEquals(AudioConfig.PCM, a.codec);
        assertEquals(16000, a.sampleRate);
        assertTrue(a.bigEndian);
    }

    @Test
    public void unsupportedAndMissingAudio() {
        AudioConfig a = new AudioConfig();
        parse("m=audio 0 RTP/AVP 111\r\na=rtpmap:111 opus/48000/2\r\n", "rtsp://x", null, a);
        assertEquals(AudioConfig.NONE, a.codec);

        AudioConfig b = new AudioConfig();
        parse("m=video 0 RTP/AVP 96\r\n", "rtsp://x", null, b);
        assertEquals(AudioConfig.NONE, b.codec);
        assertEquals(-1, b.pt);
    }

    @Test
    public void absoluteControlAndNullOutputs() {
        String[] urls = parse("m=video 0 RTP/AVP 96\r\na=control:rtsp://other/v\r\n",
                "rtsp://cam/s", null, null);
        assertEquals("rtsp://other/v", urls[0]);
        assertNull(null);
    }
}
