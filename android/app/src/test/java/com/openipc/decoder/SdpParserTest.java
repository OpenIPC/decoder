/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * SdpParserTest.java — SDP parsing into track URLs, video codec and AudioConfig
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    private static Sdp parse(String sdp, String base) {
        return Sdp.parse(sdp, base);
    }

    @Test
    public void videoAndAacTrack() {
        Sdp sdp = parse(OPENIPC_LIKE, "rtsp://cam/stream=0");
        AudioConfig a = sdp.audio;

        assertTrue(sdp.videoH265);
        assertEquals(97, sdp.videoPt);
        assertEquals("rtsp://cam/stream=0/trackID=0", sdp.videoUrl);
        assertEquals("rtsp://cam/stream=0/trackID=1", sdp.audioUrl);
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
        AudioConfig a = parse("m=audio 0 RTP/AVP 98\r\n" +
                "a=fmtp:98 config=1408; sizeLength=13; indexLength=3; indexDeltaLength=3\r\n" +
                "a=rtpmap:98 MPEG4-GENERIC/16000/1\r\n", "rtsp://x").audio;
        assertEquals(AudioConfig.AAC, a.codec);
        assertEquals(13, a.sizeLength);
        assertEquals(16000, a.sampleRate);
    }

    @Test
    public void invalidAuHeaderLayoutDisablesAac() {
        AudioConfig a = parse("m=audio 0 RTP/AVP 98\r\na=rtpmap:98 MPEG4-GENERIC/16000\r\n" +
                "a=fmtp:98 sizelength=13; indexlength=-3\r\n", "rtsp://x").audio;
        assertEquals(AudioConfig.NONE, a.codec);

        AudioConfig b = parse("m=audio 0 RTP/AVP 98\r\na=rtpmap:98 MPEG4-GENERIC/16000\r\n" +
                "a=fmtp:98 sizelength=40\r\n", "rtsp://x").audio;
        assertEquals(AudioConfig.NONE, b.codec);
    }

    @Test
    public void staticPayloadTypesWithoutRtpmap() {
        AudioConfig a = parse("m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\nm=audio 0 RTP/AVP 8\r\n",
                "rtsp://x").audio;
        assertEquals(AudioConfig.PCMA, a.codec);
        assertEquals(8000, a.sampleRate);

        AudioConfig b = parse("m=audio 0 RTP/AVP 0\r\n", "rtsp://x").audio;
        assertEquals(AudioConfig.PCMU, b.codec);
    }

    @Test
    public void l16BigEndianAndRtpmapOfVideoIsIgnored() {
        AudioConfig a = parse("m=video 0 RTP/AVP 100\r\na=rtpmap:100 H264/90000\r\n" +
                "m=audio 0 RTP/AVP 100\r\na=rtpmap:100 L16/16000\r\n", "rtsp://x").audio;
        assertEquals(AudioConfig.PCM, a.codec);
        assertEquals(16000, a.sampleRate);
        assertTrue(a.bigEndian);
    }

    @Test
    public void unsupportedAndMissingAudio() {
        AudioConfig a = parse("m=audio 0 RTP/AVP 111\r\na=rtpmap:111 G726-32/8000\r\n", "rtsp://x").audio;
        assertEquals(AudioConfig.NONE, a.codec);

        AudioConfig b = parse("m=video 0 RTP/AVP 96\r\n", "rtsp://x").audio;
        assertEquals(AudioConfig.NONE, b.codec);
        assertEquals(-1, b.pt);
    }

    @Test
    public void absoluteControl() {
        Sdp sdp = parse("m=video 0 RTP/AVP 96\r\na=control:rtsp://other/v\r\n", "rtsp://cam/s");
        assertEquals("rtsp://other/v", sdp.videoUrl);
    }

    @Test
    public void sessionControlStarAndContentBase() {
        // #19: a session-level "*" means the base URL itself
        Sdp sdp = parse("a=control:*\r\nm=video 0 RTP/AVP 96\r\na=control:trackID=0\r\n",
                "rtsp://cam/stream=0/");
        assertEquals("rtsp://cam/stream=0/trackID=0", sdp.videoUrl);
    }

    @Test
    public void rootRelativeControl() {
        Sdp sdp = parse("m=video 0 RTP/AVP 96\r\na=control:/trackID=0\r\n",
                "rtsp://cam:8554/live/main");
        assertEquals("rtsp://cam:8554/trackID=0", sdp.videoUrl);
    }

    @Test
    public void noAudioTrackMeansNoAudioUrl() {
        // #18: no m=audio, so no audio SETUP
        Sdp sdp = parse("m=video 0 RTP/AVP 96\r\n", "rtsp://cam/s");
        assertNull(sdp.audioUrl);
        assertEquals("rtsp://cam/s/trackID=0", sdp.videoUrl);
    }

    @Test
    public void videoCodecComesFromRtpmap() {
        // #9: H.265 on PT 96 and H.264 on PT 97
        assertTrue(parse("m=video 0 RTP/AVP 96\r\na=rtpmap:96 H265/90000\r\n", "rtsp://x").videoH265);
        Sdp h264 = parse("m=video 0 RTP/AVP 97\r\na=rtpmap:97 H264/90000\r\n", "rtsp://x");
        assertEquals(97, h264.videoPt);
        assertFalse(h264.videoH265);
        // Without rtpmap the legacy 96/97 convention applies
        assertTrue(parse("m=video 0 RTP/AVP 97\r\n", "rtsp://x").videoH265);
        assertFalse(parse("m=video 0 RTP/AVP 96\r\n", "rtsp://x").videoH265);
    }

    @Test
    public void firstSupportedVideoPayloadIsChosen() {
        Sdp sdp = parse("m=video 0 RTP/AVP 26 98 99\r\na=rtpmap:26 JPEG/90000\r\n" +
                "a=rtpmap:98 VP8/90000\r\na=rtpmap:99 H265/90000\r\n", "rtsp://x");
        assertEquals(99, sdp.videoPt);
        assertTrue(sdp.videoH265);
        assertEquals(-1, parse("m=video 0 RTP/AVP 26\r\na=rtpmap:26 JPEG/90000\r\n",
                "rtsp://x").videoPt);
    }

    @Test
    public void payloadAndControlComeFromTheSameVideoSection() {
        Sdp sdp = parse("m=video 0 RTP/AVP 26\r\na=rtpmap:26 JPEG/90000\r\na=control:mjpeg\r\n" +
                "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:h264\r\n" +
                "m=video 0 RTP/AVP 97\r\na=rtpmap:97 H265/90000\r\na=control:h265\r\n",
                "rtsp://cam/s");
        assertEquals(96, sdp.videoPt);
        assertFalse(sdp.videoH265);
        assertEquals("rtsp://cam/s/h264", sdp.videoUrl);
    }
}
