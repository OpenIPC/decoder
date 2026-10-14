/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * StreamProbeTest.java — stream checks of the slots not on screen (#100)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StreamProbeTest {
    private static final String DEF = "rtsp://root:12345@192.168.1.10:554/stream=0";

    /** RTP packet: version 2, payload type, marker, then the payload bytes. */
    private static byte[] rtp(int pt, boolean marker, int... payload) {
        byte[] p = new byte[12 + payload.length];
        p[0] = (byte) 0x80;
        p[1] = (byte) ((marker ? 0x80 : 0) | pt);
        for (int i = 0; i < payload.length; i++) p[12 + i] = (byte) payload[i];
        return p;
    }

    private static StreamProbe.Counter counter(int pt, boolean h265, boolean jpeg) {
        StreamProbe.Counter c = new StreamProbe.Counter();
        Sdp sdp = new Sdp();
        sdp.videoPt = pt;
        sdp.videoH265 = h265;
        sdp.videoJpeg = jpeg;
        c.onSdp(sdp);
        return c;
    }

    private static void feed(StreamProbe.Counter c, byte[] p, long ms) {
        c.onPacket(p, 0, p.length, ms);
    }

    @Test
    public void framesAreCountedByTheMarkerBit() {
        StreamProbe.Counter c = counter(96, false, false);
        for (int i = 0; i < 26; i++) {
            feed(c, rtp(96, false, 0x41, 1, 2), i * 40);
            feed(c, rtp(96, true, 0x41, 3, 4), i * 40);
        }
        assertEquals(52, c.packets);
        assertEquals(26, c.frames);
        assertEquals(25f, c.fps(), 0.01f);
        assertEquals("H.264 · 25 fps · 6 kbit/s", c.summary());
    }

    @Test
    public void otherPayloadTypesAreIgnored() {
        StreamProbe.Counter c = counter(96, false, false);
        feed(c, rtp(97, true, 0x65), 0);
        feed(c, rtp(96, true, 0x41), 0);
        assertEquals(1, c.packets);
        assertEquals(1, c.frames);
    }

    @Test
    public void h264KeyFramesAloneFragmentedOrAggregated() {
        StreamProbe.Counter c = counter(96, false, false);
        // IDR alone
        feed(c, rtp(96, true, 0x65, 0), 0);
        // FU-A carrying an IDR: start piece, then the end piece
        feed(c, rtp(96, false, 0x7C, 0x85, 0), 40);
        feed(c, rtp(96, true, 0x7C, 0x45, 0), 40);
        // STAP-A with SPS and IDR
        feed(c, rtp(96, true, 0x78, 0, 2, 0x67, 0, 0, 2, 0x65, 0), 80);
        // A P frame
        feed(c, rtp(96, true, 0x41, 0), 120);
        assertEquals(4, c.frames);
        assertEquals(3, c.keyFrames);
    }

    @Test
    public void h265KeyFrames() {
        StreamProbe.Counter c = counter(97, true, false);
        // IDR_W_RADL (19) alone: header (19 << 1), 1
        feed(c, rtp(97, true, 19 << 1, 1, 0), 0);
        // FU (49) with the start bit and type 19
        feed(c, rtp(97, true, 49 << 1, 1, 0x80 | 19, 0), 40);
        // TRAIL_R (1)
        feed(c, rtp(97, true, 1 << 1, 1, 0), 80);
        assertEquals(3, c.frames);
        assertEquals(2, c.keyFrames);
        assertEquals("H.265", c.codec());
    }

    @Test
    public void rtpJpegSizeFromItsHeader() {
        StreamProbe.Counter c = counter(26, false, true);
        // type-specific, offset (3), type, Q, width / 8, height / 8
        feed(c, rtp(26, true, 0, 0, 0, 0, 1, 255, 160, 90), 0);
        feed(c, rtp(26, true, 0, 0, 0, 0, 1, 255, 160, 90), 200);
        assertEquals(1280, c.width);
        assertEquals(720, c.height);
        assertTrue(c.summary(), c.summary().startsWith("1280×720 MJPEG · 5.0 fps"));
    }

    @Test
    public void httpMjpegFramesAndSize() {
        StreamProbe.Counter c = new StreamProbe.Counter();
        byte[] jpeg = JpegAssembler.headers(1, 640, 480, JpegAssembler.tables(50), 0);
        c.onJpegFrame(jpeg, 0, jpeg.length, 0);
        c.onJpegFrame(jpeg, 0, jpeg.length, 100);
        assertEquals(2, c.frames);
        assertEquals(640, c.width);
        assertEquals(480, c.height);
        assertEquals("MJPEG", c.codec());
        assertEquals(10f, c.fps(), 0.01f);
    }

    @Test
    public void resultTexts() {
        StreamProbe.Result noAnswer = StreamProbe.result("u", 1000, false, null, new StreamProbe.Counter());
        assertFalse(noAnswer.ok);
        assertEquals("No answer from camera · checked 12 s ago", noAnswer.text(13_000));
        StreamProbe.Result login = StreamProbe.result("u", 0, false, "RTSP DESCRIBE failed: 401", null);
        assertEquals("Wrong login or password", login.summary);
        StreamProbe.Result silent = StreamProbe.result("u", 0, true, null, counter(96, false, false));
        assertEquals("PLAY OK, but no video", silent.summary);
        StreamProbe.Counter c = counter(96, false, false);
        feed(c, rtp(96, true, 0x65), 0);
        feed(c, rtp(96, true, 0x41), 1000);
        StreamProbe.Result r = StreamProbe.result("u", 0, true, null, c);
        assertTrue(r.ok);
        assertTrue(r.text(180_000), r.text(180_000).startsWith("Stream OK · H.264 · 1.0 fps"));
        assertTrue(r.text(180_000).endsWith("checked 3 min ago"));
        assertEquals(1, r.keyFrames);
    }

    @Test
    public void skipReasons() {
        assertEquals("not configured", StreamProbes.skipReason(null, DEF, null));
        assertEquals("not configured", StreamProbes.skipReason(DEF, DEF, null));
        assertEquals("UDP listen slot: not checked in single view",
                StreamProbes.skipReason("udp://@:5600", DEF, null));
        assertEquals("same camera as on screen", StreamProbes.skipReason(
                "rtsp://a:b@10.0.0.53:554/x", DEF, "rtsp://10.0.0.53/y"));
        assertNull(StreamProbes.skipReason("rtsp://10.0.0.53:8554/x", DEF, "rtsp://10.0.0.53/y"));
        assertNull(StreamProbes.skipReason("http://u:p@10.0.0.133/mjpeg", DEF, "rtsp://10.0.0.133/y"));
        assertEquals("same camera as on screen", StreamProbes.skipReason(
                "http://10.0.0.133/mjpeg", DEF, "http://u:p@10.0.0.133:80/other"));
    }

    @Test
    public void endpoints() {
        assertEquals("10.0.0.5:554", StreamProbes.endpoint("rtsp://u:p@10.0.0.5/stream=0"));
        assertEquals("cam:8554", StreamProbes.endpoint("rtsp://CAM:8554/x"));
        assertEquals("10.0.0.5:80", StreamProbes.endpoint("http://10.0.0.5/mjpeg"));
        assertNull(StreamProbes.endpoint("udp://@:5600"));
        assertNull(StreamProbes.endpoint(null));
    }

    @Test
    public void nextSlotWaitsLongestAndNeverTwiceToOneCamera() {
        String[] urls = {"rtsp://a/1", "rtsp://b/1", "rtsp://a/2", "rtsp://c/1"};
        String[] skip = new String[4];
        long[] last = new long[4];
        boolean[] running = new boolean[4];
        // Never checked: the lowest slot first
        assertEquals(0, StreamProbes.next(urls, skip, last, running, 100_000));
        // Slot 0 runs: slot 2 is the same camera, so slot 1
        running[0] = true;
        last[0] = 100_000;
        assertEquals(1, StreamProbes.next(urls, skip, last, running, 100_000));
        running[1] = true;
        last[1] = 100_000;
        assertEquals(3, StreamProbes.next(urls, skip, last, running, 100_000));
        // All done; slot 2 never checked comes first, then the oldest once the period passed
        running[0] = running[1] = false;
        last[3] = 101_000;
        assertEquals(2, StreamProbes.next(urls, skip, last, running, 102_000));
        last[2] = 102_000;
        assertEquals(-1, StreamProbes.next(urls, skip, last, running, 102_000 + 1000));
        assertEquals(0, StreamProbes.next(urls, skip, last, running,
                100_000 + StreamProbes.PERIOD_MS));
        // A skipped slot is never chosen
        skip[0] = "same camera as on screen";
        assertEquals(1, StreamProbes.next(urls, skip, last, running,
                100_000 + StreamProbes.PERIOD_MS));
    }
}
