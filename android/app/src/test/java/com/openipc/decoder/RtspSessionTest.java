/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtspSessionTest.java — RtspSession against a scripted RTSP server on localhost
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RtspSessionTest {
    private static final String SDP_AV =
            "v=0\r\n" +
            "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H265/90000\r\na=control:trackID=0\r\n" +
            "m=audio 0 RTP/AVP 98\r\na=rtpmap:98 L16/16000\r\na=control:trackID=1\r\n";
    private static final String SDP_VIDEO_ONLY =
            "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:trackID=0\r\n";

    /** Scripted RTSP server: answers each connection with the configured behaviour. */
    private static final class FakeServer implements AutoCloseable {
        final ServerSocket server;
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger connections = new AtomicInteger();
        volatile String sdp = SDP_AV;
        volatile int audioSetupStatus = 200;
        /** Connections closed right after accept, before any reply. */
        volatile int failFirst;
        /** Close the connection after sending the packets instead of staying open. */
        volatile boolean closeAfterPackets;
        volatile int videoPackets = 3;
        /** Send the DESCRIBE body without a Content-Length header. */
        volatile boolean omitContentLength;
        volatile int audioPackets = 1;

        FakeServer() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread t = new Thread(this::acceptLoop, "fake-rtsp");
            t.setDaemon(true);
            t.start();
        }

        String url(String credentials) {
            return "rtsp://" + (credentials != null ? credentials + "@" : "")
                    + "127.0.0.1:" + server.getLocalPort() + "/stream=0";
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    int n = connections.incrementAndGet();
                    Thread t = new Thread(() -> serve(s, n));
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(Socket s, int n) {
            try (Socket socket = s) {
                if (n <= failFirst) return;
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                while (true) {
                    String line = RtspSession.readLine(in);
                    if (line == null) return;
                    String requestLine = line;
                    String cseq = "0";
                    StringBuilder headers = new StringBuilder();
                    while ((line = RtspSession.readLine(in)) != null && !line.isEmpty()) {
                        headers.append(line).append('\n');
                        if (line.startsWith("CSeq:")) cseq = line.substring(5).trim();
                    }
                    requests.add(requestLine + "\n" + headers);
                    String method = requestLine.split(" ")[0];
                    String reply;
                    switch (method) {
                        case "DESCRIBE":
                            byte[] body = sdp.getBytes(StandardCharsets.UTF_8);
                            reply = "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\nContent-Type: "
                                    + "application/sdp\r\n"
                                    + (omitContentLength ? "" : "Content-Length: " + body.length + "\r\n")
                                    + "\r\n" + sdp;
                            break;
                        case "SETUP":
                            int code = requestLine.contains("trackID=1") ? audioSetupStatus : 200;
                            reply = "RTSP/1.0 " + code + (code == 200 ? " OK" : " Not Found")
                                    + "\r\nCSeq: " + cseq + "\r\nSession: ABC123;timeout=60\r\n\r\n";
                            break;
                        default:
                            reply = "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
                            break;
                    }
                    out.write(reply.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (method.equals("PLAY")) {
                        for (int i = 0; i < videoPackets; i++) out.write(packet(0, 96));
                        for (int i = 0; i < audioPackets; i++) out.write(packet(2, 98));
                        out.flush();
                        if (closeAfterPackets) return;
                        // Keep the session open until the client goes away
                        while (in.read() >= 0) { /* ignore */ }
                        return;
                    }
                }
            } catch (IOException ignored) {
            }
        }

        static byte[] packet(int channel, int pt) {
            byte[] p = new byte[4 + 14];
            p[0] = '$';
            p[1] = (byte) channel;
            p[3] = 14;
            p[4] = (byte) 0x80;
            p[5] = (byte) pt;
            p[16] = 0x40;
            p[17] = 0x01;
            return p;
        }

        List<String> methods() {
            List<String> m = new ArrayList<>();
            synchronized (requests) {
                for (String r : requests) m.add(r.substring(0, r.indexOf('\n')));
            }
            return m;
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    /** Records callbacks. */
    private static final class Recorder implements RtspSession.Callback {
        final List<String> statuses = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger video = new AtomicInteger();
        final AtomicInteger audio = new AtomicInteger();
        volatile Sdp sdp;
        final AtomicInteger sdpCount = new AtomicInteger();
        volatile CountDownLatch videoLatch = new CountDownLatch(3);
        /** What onPacket reports for video packets. */
        volatile boolean usable = true;

        @Override public void onStatus(String status) { statuses.add(status); }

        @Override public void onSdp(Sdp s) {
            sdp = s;
            sdpCount.incrementAndGet();
        }

        @Override public boolean onPacket(int channel, byte[] buf, int offset, int length) {
            if (channel == RtspSession.CHANNEL_VIDEO) {
                video.incrementAndGet();
                videoLatch.countDown();
                return usable;
            } else if (channel == RtspSession.CHANNEL_AUDIO) {
                audio.incrementAndGet();
            }
            return false;
        }
    }

    private FakeServer server;
    private RtspSession session;
    private final Recorder rec = new Recorder();

    @Before
    public void setUp() throws IOException {
        RtspSession.minRetryMs = 5;
        RtspSession.maxRetryMs = 20;
        RtspSession.watchdogMs = 8000;
        server = new FakeServer();
        session = new RtspSession("test", rec);
    }

    @After
    public void tearDown() throws IOException {
        session.stop();
        server.close();
        RtspSession.minRetryMs = 1000;
        RtspSession.maxRetryMs = 30000;
        RtspSession.watchdogMs = 8000;
    }

    private void runUntilVideo(String url, boolean withAudio) throws InterruptedException {
        session.configure(url, "User-Agent: test\r\n", withAudio);
        session.start();
        assertTrue("video packets received", rec.videoLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void handshakeWithAudio() throws Exception {
        runUntilVideo(server.url("root:12345"), true);

        String base = "rtsp://127.0.0.1:" + server.server.getLocalPort() + "/stream=0";
        List<String> m = server.methods();
        assertEquals("DESCRIBE " + base + " RTSP/1.0", m.get(0));
        assertEquals("SETUP " + base + "/trackID=0 RTSP/1.0", m.get(1));
        assertEquals("SETUP " + base + "/trackID=1 RTSP/1.0", m.get(2));
        assertEquals("PLAY " + base + " RTSP/1.0", m.get(3));

        String describe = server.requests.get(0);
        assertTrue(describe.contains("Authorization: Basic"));
        assertTrue(describe.contains("User-Agent: test"));
        assertTrue(server.requests.get(3).contains("Session: ABC123"));

        assertTrue(rec.sdp.videoH265);
        assertEquals(AudioConfig.PCM, rec.sdp.audio.codec);
        assertTrue(rec.statuses.contains("buffering"));
        assertTrue(rec.statuses.contains("connected"));
        assertTrue(session.isActive());
        Thread.sleep(100);
        assertEquals(1, rec.audio.get());
    }

    @Test
    public void noAudioTrackMeansNoAudioSetup() throws Exception {
        // #18
        server.sdp = SDP_VIDEO_ONLY;
        runUntilVideo(server.url(null), true);
        List<String> m = server.methods();
        assertEquals(3, m.size());
        assertTrue(m.get(2).startsWith("PLAY"));
        assertFalse(rec.sdp.videoH265);
        assertEquals(AudioConfig.NONE, rec.sdp.audio.codec);
    }

    @Test
    public void failedAudioSetupIsNotFatal() throws Exception {
        // #18
        server.audioSetupStatus = 404;
        runUntilVideo(server.url(null), true);
        assertTrue(server.methods().get(3).startsWith("PLAY"));
        assertEquals(AudioConfig.NONE, rec.sdp.audio.codec);
        assertEquals(1, server.connections.get());
    }

    @Test
    public void quadModeSkipsAudio() throws Exception {
        runUntilVideo(server.url(null), false);
        List<String> m = server.methods();
        assertEquals(3, m.size());
        assertEquals(AudioConfig.NONE, rec.sdp.audio.codec);
    }

    @Test
    public void neverGivesUpAfterManyFailures() throws Exception {
        // #27: more than 10 failed attempts in a row, then the camera comes back
        server.failFirst = 15;
        runUntilVideo(server.url(null), true);
        assertTrue(server.connections.get() > 15);
    }

    @Test
    public void reconnectsWhenServerClosesStream() throws Exception {
        server.closeAfterPackets = true;
        rec.videoLatch = new CountDownLatch(9); // 3 packets per session, 3 sessions
        runUntilVideo(server.url(null), true);
        assertTrue(server.connections.get() >= 3);
        assertTrue(rec.statuses.contains("disconnected"));
    }

    @Test
    public void stopIsNonBlockingAndSilent() throws Exception {
        runUntilVideo(server.url(null), true);
        long t0 = System.nanoTime();
        session.stop();
        assertTrue("stop() returns quickly", System.nanoTime() - t0 < 200_000_000L);
        assertFalse(session.isActive());
        int sdpCount = rec.sdpCount.get();
        int statusCount = rec.statuses.size();
        Thread.sleep(300);
        assertEquals("no reconnect after stop", sdpCount, rec.sdpCount.get());
        assertEquals("no status after stop", statusCount, rec.statuses.size());
    }

    @Test
    public void describeWithoutContentLength() throws Exception {
        server.omitContentLength = true;
        runUntilVideo(server.url(null), true);
        assertTrue(rec.sdp.videoH265);
        assertTrue(server.methods().get(1).startsWith("SETUP"));
    }

    @Test
    public void noSupportedVideoTrackFailsAndRetries() throws Exception {
        server.sdp = "v=0\r\nm=video 0 RTP/AVP 26\r\na=rtpmap:26 JPEG/90000\r\n";
        session.configure(server.url(null), "", true);
        session.start();
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (server.connections.get() < 3 && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("retried", server.connections.get() >= 3);
        assertEquals("no SETUP without a usable video track", 0,
                server.methods().stream().filter(m -> m.startsWith("SETUP")).count());
        assertEquals(0, rec.sdpCount.get());
    }

    @Test
    public void unusableVideoDoesNotKeepSessionAlive() throws Exception {
        RtspSession.watchdogMs = 200;
        rec.usable = false;
        rec.videoLatch = new CountDownLatch(6); // packets from at least two sessions
        runUntilVideo(server.url(null), true);
        assertTrue("watchdog reconnected", server.connections.get() >= 2);
        assertFalse("never connected", rec.statuses.contains("connected"));
    }

    @Test
    public void retryDelayNeverExceedsMaximum() {
        RtspSession.maxRetryMs = 30000;
        for (int i = 0; i < 1000; i++) {
            assertTrue(RtspSession.retryDelay(30000) <= 30000);
            long d = RtspSession.retryDelay(1000);
            assertTrue(d >= 1000 && d <= 1500);
        }
    }
}
