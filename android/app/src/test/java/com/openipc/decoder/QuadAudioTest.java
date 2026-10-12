/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * QuadAudioTest.java — audio routing of a Quad cell (#12)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class QuadAudioTest {
    /** Counts the audio frames it is given. */
    private static final class CountingAudio extends AudioManager {
        final AtomicInteger frames = new AtomicInteger();

        @Override void onAudioFrame(Frame frame) { frames.incrementAndGet(); }
    }

    private static byte[] rtp(int pt) {
        byte[] p = new byte[20];
        p[0] = (byte) 0x80;
        p[1] = (byte) pt;
        return p;
    }

    @Test
    public void onlyTheSelectedCellPlaysAudio() {
        QuadCell cell = new QuadCell(1, "rtsp://cam/s", null, new FramePool(4), "", index -> {});
        CountingAudio audio = new CountingAudio();
        byte[] a = rtp(98);

        cell.onPacket(RtspSession.CHANNEL_AUDIO, a, 0, a.length);
        assertEquals("muted by default", 0, audio.frames.get());

        cell.setAudioSink(audio);
        cell.onPacket(RtspSession.CHANNEL_AUDIO, a, 0, a.length);
        cell.onPacket(RtspSession.CHANNEL_AUDIO, a, 0, a.length);
        assertEquals(2, audio.frames.get());

        // Video packets never reach the audio sink
        byte[] v = rtp(96);
        cell.onPacket(RtspSession.CHANNEL_VIDEO, v, 0, v.length);
        assertEquals(2, audio.frames.get());

        cell.setAudioSink(null);
        cell.onPacket(RtspSession.CHANNEL_AUDIO, a, 0, a.length);
        assertEquals(2, audio.frames.get());
    }

    @Test
    public void audioConfigIsReportedOnConnect() {
        AtomicReference<AudioConfig> reported = new AtomicReference<>();
        AtomicInteger reportedIndex = new AtomicInteger(-1);
        QuadCell cell = new QuadCell(2, "rtsp://cam/s", null, new FramePool(4), "",
                new QuadCell.Listener() {
                    @Override public void onRemoveOverlay(int index) {}
                    @Override public void onAudioConfig(int index, AudioConfig audio) {
                        reportedIndex.set(index);
                        reported.set(audio);
                    }
                });
        Sdp sdp = Sdp.parse("m=video 0 RTP/AVP 96\r\nm=audio 0 RTP/AVP 0\r\n", "rtsp://cam/s");
        cell.onSdp(sdp);
        assertEquals(2, reportedIndex.get());
        assertSame(sdp.audio, reported.get());
        assertSame(sdp.audio, cell.getAudioConfig());
        assertEquals(AudioConfig.PCMU, cell.getAudioConfig().codec);
    }
}
