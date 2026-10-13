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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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

    /** RTP packet as sent over UDP: no interleaved header, a real sequence number. */
    static byte[] udpPacket(int pt, int seq) {
        byte[] p = FakeServer.packet(0, pt);
        p[6] = (byte) (seq >> 8);
        p[7] = (byte) seq;
        return java.util.Arrays.copyOfRange(p, 4, p.length);
    }

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
        /** When set, every request must carry a valid Digest response for these credentials. */
        volatile String digestUser;
        volatile String digestPassword;
        /** Session timeout announced in SETUP replies. */
        volatile int sessionTimeout = 60;
        /** Reply 501 to GET_PARAMETER. */
        volatile boolean rejectGetParameter;
        /** Reply 401 (Digest, never stale) to every GET_PARAMETER. */
        volatile boolean keepaliveUnauthorized;
        /** After PLAY keep sending video packets every 20 ms instead of a fixed burst. */
        volatile boolean streamContinuously;
        final AtomicInteger unauthorized = new AtomicInteger();
        volatile int audioPackets = 1;
        /** Reply 461 Unsupported Transport to a UDP SETUP. */
        volatile boolean udpUnsupported;
        /** Accept a UDP SETUP but never send a datagram. */
        volatile boolean udpSilent;
        /** Send the datagrams from this local address instead of 127.0.0.1. */
        volatile String udpFrom = "127.0.0.1";
        /** Answer a UDP SETUP with this Transport value instead of the client's ports. */
        volatile String udpReply;
        /**
         * Answer a multicast SETUP with this Transport value; null answers 400 as Majestic
         * does. A "destination=group;port=n" reply streams to that group after PLAY.
         */
        volatile String multicastReply;

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
                // Client RTP ports of this connection's UDP SETUPs: video, audio
                int[] udpPorts = {-1, -1};
                // Multicast group and port of this connection's SETUP, if any
                String[] mcast = {null, null};
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
                    String uri = requestLine.split(" ")[1];
                    String reply;
                    if (digestUser != null && !digestOk(headers.toString(), method, uri)) {
                        unauthorized.incrementAndGet();
                        reply = "RTSP/1.0 401 Unauthorized\r\nCSeq: " + cseq + "\r\n"
                                + "WWW-Authenticate: Basic realm=\"cam\"\r\n"
                                + "WWW-Authenticate: Digest realm=\"cam\", nonce=\"N0NCE\", "
                                + "qop=\"auth\"\r\n\r\n";
                        write(out, reply);
                        continue;
                    }
                    switch (method) {
                        case "DESCRIBE":
                            byte[] body = sdp.getBytes(StandardCharsets.UTF_8);
                            reply = "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\nContent-Type: "
                                    + "application/sdp\r\n"
                                    + (omitContentLength ? "" : "Content-Length: " + body.length + "\r\n")
                                    + "\r\n" + sdp;
                            break;
                        case "SETUP":
                            boolean audioTrack = requestLine.contains("trackID=1");
                            int code = audioTrack ? audioSetupStatus : 200;
                            Transport asked = Transport.parse(header(headers, "Transport"));
                            String transport = "";
                            if (asked.multicast && asked.clientPort == null) {
                                if (multicastReply == null) {
                                    code = 400;
                                } else {
                                    Transport t = Transport.parse(multicastReply);
                                    if (t.multicast && t.port != null) {
                                        mcast[0] = t.destination;
                                        mcast[1] = String.valueOf(t.port[0]);
                                    }
                                    transport = "Transport: " + multicastReply + "\r\n";
                                }
                            } else if (asked.clientPort != null) {
                                if (udpUnsupported) code = 461;
                                if (code == 200) udpPorts[audioTrack ? 1 : 0] = asked.clientPort[0];
                                transport = "Transport: " + (udpReply != null ? udpReply
                                        : "RTP/AVP/UDP;unicast;client_port="
                                        + asked.clientPort[0] + "-" + asked.clientPort[1]
                                        + ";server_port=6970-6971") + "\r\n";
                            } else if (asked.interleaved != null) {
                                transport = "Transport: RTP/AVP/TCP;unicast;interleaved="
                                        + asked.interleaved[0] + "-" + asked.interleaved[1]
                                        + "\r\n";
                            }
                            reply = "RTSP/1.0 " + code + (code == 200 ? " OK" : " Not Found")
                                    + "\r\nCSeq: " + cseq + "\r\n" + (code == 200 ? transport : "")
                                    + "Session: ABC123;timeout=" + sessionTimeout + "\r\n\r\n";
                            break;
                        case "GET_PARAMETER":
                            if (keepaliveUnauthorized) {
                                reply = "RTSP/1.0 401 Unauthorized\r\nCSeq: " + cseq + "\r\n"
                                        + "WWW-Authenticate: Digest realm=\"cam\", nonce=\"K\"\r\n\r\n";
                                break;
                            }
                            reply = rejectGetParameter
                                    ? "RTSP/1.0 501 Not Implemented\r\nCSeq: " + cseq + "\r\n\r\n"
                                    : "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\nSession: ABC123\r\n\r\n";
                            break;
                        default:
                            reply = "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
                            break;
                    }
                    write(out, reply);
                    if (method.equals("PLAY") && mcast[0] != null) {
                        sendMulticast(mcast[0], Integer.parseInt(mcast[1]));
                        continue;
                    }
                    if (method.equals("PLAY") && udpPorts[0] > 0) {
                        if (!udpSilent) sendUdp(udpPorts);
                        continue;
                    }
                    if (method.equals("PLAY") && streamContinuously) {
                        Thread writer = new Thread(() -> {
                            try {
                                while (!socket.isClosed()) {
                                    synchronized (out) {
                                        out.write(packet(0, 96));
                                        out.flush();
                                    }
                                    Thread.sleep(20);
                                }
                            } catch (Exception ignored) {
                            }
                        });
                        writer.setDaemon(true);
                        writer.start();
                        continue; // keep answering requests (keepalives) while streaming
                    }
                    if (method.equals("PLAY")) {
                        for (int i = 0; i < videoPackets; i++) out.write(packet(0, 96));
                        for (int i = 0; i < audioPackets; i++) out.write(packet(2, 98));
                        out.flush();
                        if (closeAfterPackets) return;
                        // Stay idle but keep answering requests (keepalives)
                        continue;
                    }
                }
            } catch (IOException ignored) {
            }
        }

        private void sendUdp(int[] ports) {
            Thread sender = new Thread(() -> {
                try (DatagramSocket ds = new DatagramSocket(
                        new InetSocketAddress(InetAddress.getByName(udpFrom), 0))) {
                    InetAddress to = InetAddress.getLoopbackAddress();
                    int seq = 100;
                    for (int i = 0; i < videoPackets; i++) {
                        byte[] p = udpPacket(96, seq++);
                        ds.send(new DatagramPacket(p, p.length, to, ports[0]));
                    }
                    for (int i = 0; i < audioPackets && ports[1] > 0; i++) {
                        byte[] p = udpPacket(98, 500 + i);
                        ds.send(new DatagramPacket(p, p.length, to, ports[1]));
                    }
                } catch (IOException ignored) {
                }
            });
            sender.setDaemon(true);
            sender.start();
        }

        private void sendMulticast(String group, int port) {
            Thread sender = new Thread(() -> {
                try (java.net.MulticastSocket ms = new java.net.MulticastSocket()) {
                    ms.setNetworkInterface(java.net.NetworkInterface.getByInetAddress(
                            InetAddress.getLoopbackAddress()));
                    ms.setTimeToLive(1);
                    InetAddress to = InetAddress.getByName(group);
                    int seq = 100;
                    for (int i = 0; i < videoPackets; i++) {
                        byte[] p = udpPacket(96, seq++);
                        ms.send(new DatagramPacket(p, p.length, to, port));
                    }
                } catch (IOException ignored) {
                }
            });
            sender.setDaemon(true);
            sender.start();
        }

        private static String header(CharSequence headers, String name) {
            for (String h : headers.toString().split("\n")) {
                if (h.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                    return h.substring(name.length() + 1).trim();
                }
            }
            return null;
        }

        private static void write(OutputStream out, String reply) throws IOException {
            synchronized (out) {
                out.write(reply.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }

        private boolean digestOk(String headers, String method, String uri) {
            for (String h : headers.split("\n")) {
                if (!h.startsWith("Authorization: Digest ")) continue;
                java.util.Map<String, String> p = RtspAuth.parseParams(h.substring(22));
                String ha1 = RtspAuth.md5(digestUser + ":cam:" + digestPassword);
                String ha2 = RtspAuth.md5(method + ":" + uri);
                String expected = RtspAuth.md5(ha1 + ":N0NCE:" + p.get("nc") + ":"
                        + p.get("cnonce") + ":auth:" + ha2);
                return expected.equals(p.get("response")) && uri.equals(p.get("uri"));
            }
            return false;
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
            for (String r : snapshot()) m.add(r.substring(0, r.indexOf('\n')));
            return m;
        }

        /** Copy of the requests: the server thread keeps adding while a test reads. */
        List<String> snapshot() {
            synchronized (requests) {
                return new ArrayList<>(requests);
            }
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
        RtspSession.udpFailedAt.clear();
        UdpReceiver.sdkInt = 29;
    }

    @After
    public void tearDown() throws IOException {
        session.stop();
        server.close();
        RtspSession.minRetryMs = 1000;
        RtspSession.maxRetryMs = 30000;
        RtspSession.watchdogMs = 8000;
        RtspSession.udpFirstPacketMs = 3000;
        UdpReceiver.sdkInt = android.os.Build.VERSION.SDK_INT;
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

        String describe = server.snapshot().get(0);
        assertTrue(describe.contains("Authorization: Basic"));
        assertTrue(describe.contains("User-Agent: test"));
        assertTrue(server.snapshot().get(3).contains("Session: ABC123"));

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
        server.sdp = "v=0\r\nm=video 0 RTP/AVP 98\r\na=rtpmap:98 VP8/90000\r\n";
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

    @Test
    public void digestAuthentication() throws Exception {
        // #8: the server offers Basic and Digest and accepts only Digest
        server.digestUser = "admin";
        server.digestPassword = "s3cr3t";
        runUntilVideo(server.url("admin:s3cr3t"), true);
        List<String> m = server.methods();
        assertTrue("DESCRIBE retried with Digest", m.get(1).startsWith("DESCRIBE"));
        assertTrue(m.get(m.size() - 1).startsWith("PLAY"));
        assertEquals("only the first request is challenged", 1, server.unauthorized.get());
    }

    @Test
    public void wrongPasswordDoesNotLoop() throws Exception {
        server.digestUser = "admin";
        server.digestPassword = "s3cr3t";
        session.configure(server.url("admin:wrong"), "", true);
        session.start();
        Thread.sleep(500);
        session.stop();
        Thread.sleep(100);
        assertEquals(0, rec.sdpCount.get());
        // Each connection: Basic DESCRIBE + one Digest DESCRIBE, no endless retries
        int connections = server.connections.get();
        assertTrue(connections >= 1);
        assertTrue("at most 2 requests per connection",
                server.methods().size() <= connections * 2);
    }

    @Test
    public void keepaliveUsesGetParameter() throws Exception {
        // #11: timeout=2 s -> a keepalive every second
        server.sessionTimeout = 2;
        server.streamContinuously = true;
        runUntilVideo(server.url(null), true);
        Thread.sleep(2500);
        long keepalives = server.methods().stream()
                .filter(x -> x.startsWith("GET_PARAMETER")).count();
        assertTrue("keepalives sent: " + keepalives, keepalives >= 2);
        assertTrue(session.isActive());
        assertEquals(1, server.connections.get());
        String ka = server.snapshot().stream().filter(x -> x.startsWith("GET_PARAMETER"))
                .findFirst().get();
        assertTrue(ka.contains("Session: ABC123"));
    }

    @Test
    public void keepaliveFallsBackToOptions() throws Exception {
        server.sessionTimeout = 2;
        server.streamContinuously = true;
        server.rejectGetParameter = true;
        runUntilVideo(server.url(null), true);
        Thread.sleep(3200);
        List<String> m = server.methods();
        assertEquals(1, m.stream().filter(x -> x.startsWith("GET_PARAMETER")).count());
        assertTrue(m.stream().anyMatch(x -> x.startsWith("OPTIONS")));
        assertTrue(session.isActive());
        assertEquals(1, server.connections.get());
    }

    @Test
    public void keepaliveIntervalFromSessionHeader() {
        assertEquals(30000, RtspSession.keepaliveInterval("ABC"));
        assertEquals(5000, RtspSession.keepaliveInterval("ABC;timeout=10"));
        // Half of a one-second session, not the whole second
        assertEquals(500, RtspSession.keepaliveInterval("ABC; timeout=1"));
        assertEquals(30000, RtspSession.keepaliveInterval("ABC;timeout=junk"));
        assertEquals(30000, RtspSession.keepaliveInterval("ABC;timeout=0"));
    }

    @Test
    public void keepaliveIsSentWhileTheStreamIsIdle() throws Exception {
        // Watchdog long enough not to interfere: only the keepalive is under test
        RtspSession.watchdogMs = 10000;
        server.sessionTimeout = 2; // keepalive every second
        runUntilVideo(server.url(null), true); // 3 packets, then the server goes quiet
        Thread.sleep(2600);
        long keepalives = server.methods().stream()
                .filter(x -> x.startsWith("GET_PARAMETER")).count();
        assertTrue("keepalives while idle: " + keepalives, keepalives >= 2);
        assertEquals(1, server.connections.get());
    }

    @Test
    public void rejectedKeepaliveEndsTheSession() throws Exception {
        server.sessionTimeout = 1;
        server.streamContinuously = true;
        server.keepaliveUnauthorized = true;
        runUntilVideo(server.url("admin:pw"), true);
        long deadline = System.nanoTime() + 4_000_000_000L;
        while (server.connections.get() < 2 && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue("reconnected after the keepalive was rejected", server.connections.get() >= 2);
        // Bounded: Basic, then one Digest retry per connection, no endless repetition
        long perFirstConnection = server.snapshot().stream()
                .filter(x -> x.startsWith("GET_PARAMETER")).count();
        assertTrue(perFirstConnection <= 2 * server.connections.get());
    }

    // ====================== UDP (#67) ======================

    private void runUdp(Transport.Mode mode, boolean withAudio) {
        session.configure(server.url(null), "User-Agent: test\r\n", withAudio, mode);
        session.start();
    }

    private List<String> setups() {
        List<String> out = new ArrayList<>();
        for (String r : server.snapshot()) if (r.startsWith("SETUP")) out.add(r);
        return out;
    }

    @Test
    public void udpHandshakeAndPackets() throws Exception {
        runUdp(Transport.Mode.UDP, true);
        assertTrue("video over UDP", rec.videoLatch.await(5, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 2000;
        while (rec.audio.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(1, rec.audio.get());

        List<String> s = setups();
        assertEquals(2, s.size());
        for (String r : s) {
            Transport t = Transport.parse(FakeServer.header(r, "Transport"));
            assertFalse(t.tcp);
            assertEquals("RTP port is even", 0, t.clientPort[0] % 2);
            assertEquals(t.clientPort[0] + 1, t.clientPort[1]);
        }
        assertTrue(rec.statuses.contains("connected"));
        assertEquals(1, server.connections.get());
    }

    @Test
    public void autoFallsBackToTcpWhenUdpIsRefused() throws Exception {
        server.udpUnsupported = true;
        runUdp(Transport.Mode.AUTO, false);
        assertTrue("video over TCP", rec.videoLatch.await(5, TimeUnit.SECONDS));
        List<String> s = setups();
        assertTrue(s.get(0).contains("client_port="));
        assertTrue(s.get(1).contains("interleaved=0-1"));
        // Same connection: the refusal is answered by a second SETUP, not a reconnect
        assertEquals(1, server.connections.get());
    }

    @Test
    public void udpModeDoesNotFallBack() throws Exception {
        server.udpUnsupported = true;
        runUdp(Transport.Mode.UDP, false);
        assertFalse(rec.videoLatch.await(500, TimeUnit.MILLISECONDS));
        for (String r : setups()) assertFalse(r.contains("interleaved"));
        assertTrue("keeps retrying", server.connections.get() > 1);
    }

    @Test
    public void autoFallsBackToTcpWhenNoDatagramArrives() throws Exception {
        server.udpSilent = true;
        RtspSession.udpFirstPacketMs = 300;
        runUdp(Transport.Mode.AUTO, false);
        assertTrue("video over TCP", rec.videoLatch.await(5, TimeUnit.SECONDS));
        List<String> m = server.methods();
        assertTrue("UDP session torn down", m.contains("TEARDOWN " + base() + " RTSP/1.0"));
        List<String> s = setups();
        assertTrue(s.get(0).contains("client_port="));
        assertTrue(s.get(s.size() - 1).contains("interleaved=0-1"));
        assertEquals(2, server.connections.get());
    }

    @Test
    public void stopTearsDownTheUdpSession() throws Exception {
        runUdp(Transport.Mode.UDP, false);
        assertTrue(rec.videoLatch.await(5, TimeUnit.SECONDS));
        session.stop();
        long deadline = System.currentTimeMillis() + 2000;
        while (!server.methods().contains("TEARDOWN " + base() + " RTSP/1.0")
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(server.methods().contains("TEARDOWN " + base() + " RTSP/1.0"));
    }

    @Test
    public void datagramsFromAnotherAddressAreIgnored() throws Exception {
        // 127.0.0.2 is a different loopback address on Linux (the CI runner)
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Linux"));
        server.udpFrom = "127.0.0.2";
        runUdp(Transport.Mode.UDP, false);
        assertFalse(rec.videoLatch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(0, rec.video.get());
    }

    private String base() {
        return "rtsp://127.0.0.1:" + server.server.getLocalPort() + "/stream=0";
    }

    @Test
    public void autoRemembersTheFallbackForTheUrl() throws Exception {
        server.udpUnsupported = true;
        runUdp(Transport.Mode.AUTO, false);
        assertTrue(rec.videoLatch.await(5, TimeUnit.SECONDS));
        session.stop();

        // A resumed view or a rebuilt grid cell goes straight to TCP
        server.requests.clear();
        RtspSession again = new RtspSession("again", new Recorder());
        again.configure(server.url(null), "User-Agent: test\r\n", false, Transport.Mode.AUTO);
        again.start();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (setups().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertTrue(setups().get(0).contains("interleaved=0-1"));
        } finally {
            again.stop();
        }
    }

    @Test
    public void autoFallsBackOnAMulticastReply() throws Exception {
        server.udpReply = "RTP/AVP;multicast;destination=239.1.2.3;port=5000-5001;ttl=4";
        runUdp(Transport.Mode.AUTO, false);
        assertTrue("video over TCP", rec.videoLatch.await(5, TimeUnit.SECONDS));
        List<String> s = setups();
        assertTrue(s.get(s.size() - 1).contains("interleaved=0-1"));
    }

    @Test
    public void autoFallsBackWhenTheReplyNamesOtherPorts() throws Exception {
        server.udpReply = "RTP/AVP/UDP;unicast;client_port=1000-1001;server_port=6970-6971";
        runUdp(Transport.Mode.AUTO, false);
        assertTrue("video over TCP", rec.videoLatch.await(5, TimeUnit.SECONDS));
        List<String> s = setups();
        assertTrue(s.get(s.size() - 1).contains("interleaved=0-1"));
    }

    // ====================== Multicast (#69) ======================

    @Test
    public void multicastRefusedLikeMajesticFallsBackToTcp() throws Exception {
        runUdp(Transport.Mode.MULTICAST, false);
        assertTrue("video over TCP", rec.videoLatch.await(5, TimeUnit.SECONDS));
        List<String> s = setups();
        assertTrue(s.get(0).contains("Transport: RTP/AVP;multicast"));
        assertTrue(s.get(1).contains("interleaved=0-1"));
        assertEquals(1, server.connections.get());
    }

    @Test
    public void multicastAnsweredWithUnicastFallsBackToTcp() throws Exception {
        server.multicastReply = "RTP/AVP/UDP;unicast;client_port=50020-50021;server_port=1-2";
        runUdp(Transport.Mode.MULTICAST, false);
        assertTrue("video over TCP", rec.videoLatch.await(5, TimeUnit.SECONDS));
        List<String> s = setups();
        assertTrue(s.get(s.size() - 1).contains("interleaved=0-1"));
        assertEquals(2, server.connections.get());
    }

    @Test
    public void multicastReplyWithoutAPortFallsBackToTcp() throws Exception {
        server.multicastReply = "RTP/AVP;multicast;destination=239.255.42.18;port=0-1;ttl=1";
        runUdp(Transport.Mode.MULTICAST, false);
        assertTrue("video over TCP", rec.videoLatch.await(5, TimeUnit.SECONDS));
        List<String> s = setups();
        assertTrue(s.get(s.size() - 1).contains("interleaved=0-1"));
    }

    @Test
    public void multicastNeedsAndroid8() throws Exception {
        UdpReceiver.sdkInt = 25;
        runUdp(Transport.Mode.MULTICAST, false);
        assertTrue("video over TCP", rec.videoLatch.await(5, TimeUnit.SECONDS));
        assertTrue(setups().get(0).contains("interleaved=0-1"));
    }

    @Test
    public void multicastGroupIsJoinedAndReceived() throws Exception {
        String group = "239.255.42.17";
        int port = freeUdpPort();
        boolean works = loopbackMulticastWorks(group, port);
        System.out.println("loopback multicast " + (works ? "works" : "does not work"));
        org.junit.Assume.assumeTrue("loopback multicast works here", works);
        server.multicastReply = "RTP/AVP;multicast;destination=" + group + ";port="
                + port + "-" + (port + 1) + ";ttl=1";
        runUdp(Transport.Mode.MULTICAST, false);
        assertTrue("video over multicast", rec.videoLatch.await(5, TimeUnit.SECONDS));
        for (String r : setups()) assertFalse(r.contains("interleaved"));
        assertEquals(1, server.connections.get());
    }

    private static int freeUdpPort() throws IOException {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort() & ~1;
        }
    }

    /** Whether this machine delivers a group joined on the loopback interface. */
    private static boolean loopbackMulticastWorks(String group, int port) {
        try (java.nio.channels.DatagramChannel ch = java.nio.channels.DatagramChannel.open(
                java.net.StandardProtocolFamily.INET);
             java.net.MulticastSocket tx = new java.net.MulticastSocket()) {
            java.net.NetworkInterface lo = java.net.NetworkInterface.getByInetAddress(
                    InetAddress.getLoopbackAddress());
            ch.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
            ch.bind(new InetSocketAddress(port));
            ch.join(InetAddress.getByName(group), lo);
            ch.socket().setSoTimeout(500);
            tx.setNetworkInterface(lo);
            byte[] p = {1, 2, 3};
            tx.send(new DatagramPacket(p, p.length, InetAddress.getByName(group), port));
            byte[] in = new byte[16];
            ch.socket().receive(new DatagramPacket(in, in.length));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
