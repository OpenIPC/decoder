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

        final AtomicReference<AudioConfig> configured = new AtomicReference<>();
        volatile int configuredRoute = -1;

        @Override void onAudioFrame(Frame frame, int route) { frames.incrementAndGet(); }

        @Override void configure(AudioConfig audio, int route) {
            configured.set(audio);
            configuredRoute = route;
        }
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

        cell.setAudioSink(audio, 7);
        cell.onPacket(RtspSession.CHANNEL_AUDIO, a, 0, a.length);
        cell.onPacket(RtspSession.CHANNEL_AUDIO, a, 0, a.length);
        assertEquals(2, audio.frames.get());

        // Video packets never reach the audio sink
        byte[] v = rtp(96);
        cell.onPacket(RtspSession.CHANNEL_VIDEO, v, 0, v.length);
        assertEquals(2, audio.frames.get());

        cell.setAudioSink(null, 0);
        cell.onPacket(RtspSession.CHANNEL_AUDIO, a, 0, a.length);
        assertEquals(2, audio.frames.get());
    }

    @Test
    public void audioConfigGoesToTheSinkWithTheCellsRoute() {
        QuadCell cell = new QuadCell(2, "rtsp://cam/s", null, new FramePool(4), "", index -> {});
        CountingAudio audio = new CountingAudio();
        Sdp sdp = Sdp.parse("m=video 0 RTP/AVP 96\r\nm=audio 0 RTP/AVP 0\r\n", "rtsp://cam/s");

        cell.onSdp(sdp); // not selected: nothing configured
        assertSame(null, audio.configured.get());
        assertSame(sdp.audio, cell.getAudioConfig());

        cell.setAudioSink(audio, 5);
        cell.onSdp(sdp);
        assertSame(sdp.audio, audio.configured.get());
        assertEquals(5, audio.configuredRoute);
        assertEquals(AudioConfig.PCMU, cell.getAudioConfig().codec);
    }

    @Test
    public void staleRoutesAreIgnored() {
        AudioManager audio = new AudioManager();
        int first = audio.newRoute();
        int second = audio.newRoute();
        AudioConfig pcmu = Sdp.parse("m=audio 0 RTP/AVP 0\r\n", "rtsp://x").audio;
        // A G.711 packet: 12-byte header + 4 samples
        byte[] p = {(byte) 0x80, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4};
        audio.configure(pcmu, first);   // from the old route: ignored
        audio.onAudioFrame(new Frame(p, p.length), second);
        assertEquals("not configured for G.711 by the stale route", 0, audio.queuedPcmFrames());
        audio.configure(pcmu, second);
        audio.onAudioFrame(new Frame(p, p.length), first);
        assertEquals("frame of the old route dropped", 0, audio.queuedPcmFrames());
        audio.onAudioFrame(new Frame(p, p.length), second);
        assertEquals(1, audio.queuedPcmFrames());
        audio.newRoute();
        assertEquals("a new route clears the queue", 0, audio.queuedPcmFrames());
    }
}
