/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * JieliTest.java — jieli:// slots: CTP framing, the session against a fake toy, RTP/JPEG on UDP
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JieliTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Before
    public void shortTimers() {
        Jieli.paramWaitMs = 300;
        Jieli.diagnosticPorts = new int[0];
        Jieli.streamPort = freeUdpPortOrZero();
    }

    @After
    public void restoreTimers() {
        Jieli.paramWaitMs = 1500;
        Jieli.noVideoMs = 5000;
        Jieli.reopenMs = 10_000;
        Jieli.quietMs = 3000;
        Jieli.ctpSilenceMs = 20_000;
        Jieli.diagnosticPorts = new int[] {1234};
        Jieli.streamPort = Jieli.STREAM_PORT;
    }

    private static int freeUdpPortOrZero() {
        try (DatagramSocket d = new DatagramSocket(0, LOOPBACK)) {
            return d.getLocalPort();
        } catch (IOException e) {
            return 0;
        }
    }

    /** A datagram of the toy's stream on UDP 2224 as a YL3 sent it (#187). */
    private static byte[] streamPacket(int counter, int total, int offset, byte[] piece, int tail) {
        byte[] b = new byte[Jieli.RtStream.HEADER + piece.length + tail];
        b[0] = 2;
        b[2] = (byte) 0x94;
        b[3] = 0x0B;
        le32(b, 4, counter);
        le32(b, 8, total);
        le32(b, 12, offset);
        System.arraycopy(piece, 0, b, Jieli.RtStream.HEADER, piece.length);
        return b;
    }

    private static void le32(byte[] b, int at, int v) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) (v >>> (8 * i));
    }

    private static byte[] jpeg(int length) {
        byte[] j = new byte[length];
        j[0] = (byte) 0xFF;
        j[1] = (byte) 0xD8;
        for (int i = 2; i < length - 2; i++) j[i] = (byte) (i * 7);
        j[length - 2] = (byte) 0xFF;
        j[length - 1] = (byte) 0xD9;
        return j;
    }

    // ====================== RT stream ======================

    @Test
    public void rtStreamOneDatagramFrame() {
        // As seen from a YL3: 4600 bytes of JPEG, 20 more bytes after it in the datagram
        Jieli.RtStream st = new Jieli.RtStream();
        byte[] j = jpeg(4600);
        byte[] p = streamPacket(42, 4600, 0, j, 20);
        assertEquals(4640, p.length);
        int n = st.feed(p, 0, p.length);
        assertEquals(4600, n);
        assertArrayEquals(j, Arrays.copyOf(st.frame(), n));
        assertEquals(1, st.frames);
        assertEquals(0, st.dropped);
    }

    @Test
    public void rtStreamFrameInPieces() {
        Jieli.RtStream st = new Jieli.RtStream();
        byte[] j = jpeg(10000);
        assertEquals(0, st.feed(streamPacket(1, 10000, 0, Arrays.copyOfRange(j, 0, 4000), 0),
                0, Jieli.RtStream.HEADER + 4000));
        assertEquals(0, st.feed(streamPacket(2, 10000, 4000, Arrays.copyOfRange(j, 4000, 8000), 0),
                0, Jieli.RtStream.HEADER + 4000));
        byte[] last = streamPacket(3, 10000, 8000, Arrays.copyOfRange(j, 8000, 10000), 0);
        int n = st.feed(last, 0, last.length);
        assertEquals(10000, n);
        assertArrayEquals(j, Arrays.copyOf(st.frame(), n));
    }

    @Test
    public void rtStreamDropsBrokenFrames() {
        Jieli.RtStream st = new Jieli.RtStream();
        // Start lost: a middle piece alone gives nothing
        byte[] mid = streamPacket(5, 9000, 4000, new byte[4000], 0);
        assertEquals(0, st.feed(mid, 0, mid.length));
        // Not a JPEG
        byte[] junk = streamPacket(6, 100, 0, new byte[100], 0);
        assertEquals(0, st.feed(junk, 0, junk.length));
        assertEquals(1, st.dropped);
        // A frame cut short by the next one
        byte[] j = jpeg(8000);
        byte[] first = streamPacket(7, 8000, 0, Arrays.copyOfRange(j, 0, 4000), 0);
        assertEquals(0, st.feed(first, 0, first.length));
        byte[] next = streamPacket(8, 4600, 0, jpeg(4600), 0);
        assertEquals(4600, st.feed(next, 0, next.length));
        assertEquals(2, st.dropped);
        // Another type (audio?) and a bad length are ignored
        byte[] other = streamPacket(9, 4600, 0, jpeg(4600), 0);
        other[0] = 1;
        assertEquals(0, st.feed(other, 0, other.length));
        assertEquals(1, st.otherTypes);
        byte[] bad = streamPacket(10, -5, 0, jpeg(100), 0);
        assertEquals(0, st.feed(bad, 0, bad.length));
        assertEquals(1, st.frames);
    }

    // ====================== Helpers ======================

    private static byte[] bytes(String hex) {
        String h = hex.replaceAll("\\s", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    /** RTP/JPEG packets of a 64x32 test picture (see JpegAssemblerTest). */
    private static List<byte[]> jpegPackets() throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try (InputStream in = JieliTest.class.getResourceAsStream("/rtp/jpeg_q50_64x32.rtp")) {
            assertNotNull(in);
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) all.write(b, 0, n);
        }
        byte[] d = all.toByteArray();
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < d.length; ) {
            int len = ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
            out.add(Arrays.copyOfRange(d, i + 2, i + 2 + len));
            i += 2 + len;
        }
        return out;
    }

    private static byte[] resource(String name) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try (InputStream in = JieliTest.class.getResourceAsStream("/rtp/" + name)) {
            assertNotNull(in);
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) all.write(b, 0, n);
        }
        return all.toByteArray();
    }

    private static int freeUdpPort() throws IOException {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    // ====================== Framing ======================

    @Test
    public void encodesAsTheVendorApp() {
        // CTP_KEEP_ALIVE {"op":"PUT"} as in the capture of #196
        assertArrayEquals(concat("CTP:".getBytes(StandardCharsets.US_ASCII), bytes("0E 00"),
                "CTP_KEEP_ALIVE".getBytes(StandardCharsets.US_ASCII), bytes("0C 00 00 00"),
                "{\"op\":\"PUT\"}".getBytes(StandardCharsets.US_ASCII)),
                Jieli.encode(Jieli.KEEP_ALIVE, Jieli.put()));
        // An empty JSON has length 0
        assertArrayEquals(concat("CTP:".getBytes(StandardCharsets.US_ASCII), bytes("02 00"),
                "AB".getBytes(StandardCharsets.US_ASCII), bytes("00 00 00 00")),
                Jieli.encode("AB", ""));
    }

    @Test
    public void commandsAsTheVendorApp() {
        assertEquals("{\"op\":\"PUT\",\"param\":{\"ver\":\"25\",\"type\":\"0\"}}",
                Jieli.put("ver", "25", "type", "0"));
        assertEquals("{\"op\":\"PUT\",\"param\":{\"format\":\"0\",\"h\":\"480\",\"w\":\"640\","
                + "\"fps\":\"25\"}}", Jieli.openStream(0, 640, 480, 25));
        assertEquals("{\"op\":\"PUT\",\"param\":{\"status\":\"1\"}}", Jieli.put("status", "1"));
        assertEquals("{\"op\":\"PUT\",\"param\":{\"a\\\"b\":\"c\\\\\"}}", Jieli.put("a\"b", "c\\"));
    }

    @Test
    public void readsMessagesInPiecesAndTogether() {
        byte[] a = Jieli.encode("VIDEO_PARAM",
                "{\"op\":\"NOTIFY\",\"param\":{\"w\":\"640\",\"h\":\"480\"}}");
        byte[] b = Jieli.encode(Jieli.KEEP_ALIVE, "");
        byte[] c = Jieli.encode("KEEP_ALIVE_INTERVAL", "{\"param\":{\"timeout\":\"60000\"}}");
        byte[] all = concat(a, b, c);
        // Byte by byte
        Jieli.Reader r = new Jieli.Reader();
        List<Jieli.Message> got = new ArrayList<>();
        for (int i = 0; i < all.length; i++) {
            r.feed(all, i, 1);
            Jieli.Message m;
            while ((m = r.next()) != null) got.add(m);
        }
        assertEquals(3, got.size());
        assertEquals("VIDEO_PARAM", got.get(0).topic);
        assertEquals("{\"op\":\"NOTIFY\",\"param\":{\"w\":\"640\",\"h\":\"480\"}}",
                got.get(0).json);
        assertEquals(Jieli.KEEP_ALIVE, got.get(1).topic);
        assertEquals("", got.get(1).json);
        assertEquals("KEEP_ALIVE_INTERVAL", got.get(2).topic);
        assertEquals(0, r.skipped);
        // All in one read, split across a length field
        r = new Jieli.Reader();
        r.feed(all, 0, a.length + 5);
        assertEquals("VIDEO_PARAM", r.next().topic);
        assertNull(r.next());
        r.feed(all, a.length + 5, all.length - a.length - 5);
        assertEquals(Jieli.KEEP_ALIVE, r.next().topic);
        assertEquals("KEEP_ALIVE_INTERVAL", r.next().topic);
        assertNull(r.next());
    }

    @Test
    public void skipsWhatIsNotCtp() {
        byte[] good = Jieli.encode("SD_STATUS", "{\"online\":\"1\"}");
        // Garbage, then a "CTP:" with a topic length out of range, then a real message
        byte[] bad = concat("xyCT".getBytes(StandardCharsets.US_ASCII),
                "CTP:".getBytes(StandardCharsets.US_ASCII), bytes("FF FF"), good);
        Jieli.Reader r = new Jieli.Reader();
        r.feed(bad, 0, bad.length);
        Jieli.Message m = r.next();
        assertNotNull(m);
        assertEquals("SD_STATUS", m.topic);
        assertEquals(10, r.skipped);
        assertNull(r.next());
        // A large message grows the buffer
        StringBuilder big = new StringBuilder("{\"x\":\"");
        for (int i = 0; i < 20000; i++) big.append('a');
        big.append("\"}");
        byte[] large = Jieli.encode("BIG", big.toString());
        r.feed(large, 0, large.length);
        assertEquals(big.toString(), r.next().json);
    }

    @Test
    public void readsJson() {
        Map<String, Object> j = Jieli.parseJson("{\"errno\":0,\"op\":\"NOTIFY\",\"param\":"
                + "{\"w\":\"640\",\"h\":\"480\",\"format\":\"0\",\"fps\":\"25\",\"l\":[1,\"x\"]}}");
        assertNotNull(j);
        assertEquals("NOTIFY", j.get("op"));
        assertEquals("0", j.get("errno"));
        assertEquals("640", Jieli.param(j, "w"));
        assertEquals(25, Jieli.intParam(j, "fps"));
        assertEquals("NOTIFY", Jieli.param(j, "op"));
        assertEquals(-1, Jieli.intParam(j, "missing"));
        assertEquals("a\"b\u00e9", Jieli.parseJson("{\"k\":\"a\\\"b\\u00e9\"}").get("k"));
        assertNull(Jieli.parseJson(""));
        assertNull(Jieli.parseJson("{\"a\":"));
        assertNull(Jieli.parseJson("[1]"));
        assertNull(Jieli.parseJson("{} x"));
    }

    @Test
    public void keepAliveInterval() {
        assertEquals(7500, Jieli.keepAliveMs(60000));
        assertEquals(7500, Jieli.keepAliveMs(-1));
        assertEquals(2500, Jieli.keepAliveMs(10000));
        assertEquals(250, Jieli.keepAliveMs(100));
    }

    // ====================== URL ======================

    @Test
    public void urls() throws Exception {
        Jieli.Url u = Jieli.Url.parse("jieli://");
        assertEquals("192.168.80.1", u.host);
        assertEquals(3333, u.port);
        assertEquals(6666, u.rtpPort);
        assertEquals(0, u.width);
        assertEquals(Jieli.FORMAT_JPEG, u.format);
        u = Jieli.Url.parse(" JIELI://192.168.80.1:3334/?w=1280&h=720&fps=30&rtp=7000&format=1 ");
        assertEquals(3334, u.port);
        assertEquals(1280, u.width);
        assertEquals(720, u.height);
        assertEquals(30, u.fps);
        assertEquals(7000, u.rtpPort);
        assertEquals(Jieli.FORMAT_H264, u.format);
        assertEquals("192.168.80.1:3334", u.endpoint());
        assertTrue(Jieli.Url.matches("jieli://x"));
        assertTrue(!Jieli.Url.matches("flowufo://"));
        for (String bad : new String[] {"jieli://h:0", "jieli://h:x", "jieli://?w=8",
                "jieli://?fps=0", "jieli://?rtp=70000", "jieli://?format=2", "jieli://a b",
                "jieli://[::1"}) {
            try {
                Jieli.Url.parse(bad);
                fail(bad);
            } catch (IOException expected) {
                // refused
            }
        }
        assertEquals("toy drone slot: not checked in single view",
                StreamProbes.skipReason("jieli://", "rtsp://default", null));
    }

    @Test
    public void subnetHints() throws Exception {
        InetAddress toy = InetAddress.getByName("192.168.80.1");
        List<InetAddress> on80 = Collections.singletonList(InetAddress.getByName("192.168.80.2"));
        assertNull(Jieli.subnetHint(toy, on80));
        assertEquals("192.168.80.2 is this phone; the toy is usually 192.168.80.1",
                Jieli.subnetHint(InetAddress.getByName("192.168.80.2"), on80));
        assertEquals("This phone is on 192.168.80.x: the toy is usually jieli://192.168.80.1",
                Jieli.subnetHint(InetAddress.getByName("192.168.169.1"), on80));
        assertEquals("This network looks like a FLOW-UFO toy: try flowufo://192.168.169.1",
                Jieli.subnetHint(toy,
                        Collections.singletonList(InetAddress.getByName("192.168.169.3"))));
        assertEquals("This network looks like a KY UFO RTSP toy: try"
                        + " rtsp://192.168.1.1:7070/webcam",
                Jieli.subnetHint(toy,
                        Collections.singletonList(InetAddress.getByName("192.168.1.100"))));
        assertNull(Jieli.subnetHint(toy, Collections.emptyList()));
    }

    // ====================== A fake toy on loopback ======================

    /** One message the fake toy received, with the time. */
    private static final class Got {
        final String topic;
        final String json;
        final long at;

        Got(Jieli.Message m) {
            topic = m.topic;
            json = m.json;
            at = now();
        }
    }

    /**
     * Answers like the toy of #196: KEEP_ALIVE_INTERVAL and VIDEO_PARAM after APP_ACCESS,
     * empty keepalive replies, an OPEN_RT_STREAM notify; then the video to {@code rtpPort}.
     */
    private static final class FakeToy implements AutoCloseable {
        final ServerSocket server;
        final List<Got> got = new CopyOnWriteArrayList<>();
        final CountDownLatch closeStream = new CountDownLatch(1);
        final CountDownLatch opened = new CountDownLatch(1);
        final int timeoutMs;
        final boolean sendVideoParam;
        volatile int rtpPort;
        volatile boolean sendVideo = true;
        /** KEEP_ALIVE_INTERVAL only after the first keepalive, not after APP_ACCESS. */
        volatile boolean lateInterval;
        private boolean intervalSent;
        private final Thread thread;

        FakeToy(int timeoutMs, boolean sendVideoParam) throws IOException {
            this.timeoutMs = timeoutMs;
            this.sendVideoParam = sendVideoParam;
            server = new ServerSocket(0, 1, LOOPBACK);
            thread = new Thread(this::serve, "fake-jieli");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try (Socket s = server.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                Jieli.Reader r = new Jieli.Reader();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    r.feed(buf, 0, n);
                    Jieli.Message m;
                    while ((m = r.next()) != null) {
                        got.add(new Got(m));
                        switch (m.topic) {
                            case Jieli.APP_ACCESS:
                                if (!lateInterval) interval(out);
                                out.write(Jieli.encode("BAT_STATUS",
                                        "{\"op\":\"NOTIFY\",\"param\":{\"level\":\"4\"}}"));
                                if (sendVideoParam) {
                                    out.write(Jieli.encode(Jieli.VIDEO_PARAM,
                                            "{\"op\":\"NOTIFY\",\"param\":{\"w\":\"320\","
                                                    + "\"h\":\"240\",\"format\":\"0\","
                                                    + "\"fps\":\"15\"}}"));
                                }
                                break;
                            case Jieli.KEEP_ALIVE:
                                out.write(Jieli.encode(Jieli.KEEP_ALIVE, ""));
                                if (lateInterval && !intervalSent) interval(out);
                                break;
                            case Jieli.OPEN_RT_STREAM:
                                out.write(Jieli.encode(Jieli.OPEN_RT_STREAM,
                                        "{\"op\":\"NOTIFY\",\"param\":{\"status\":\"1\"}}"));
                                if (opened.getCount() > 0 && sendVideo) sendVideo();
                                opened.countDown();
                                break;
                            case Jieli.CLOSE_RT_STREAM:
                                closeStream.countDown();
                                break;
                            default:
                                break;
                        }
                        out.flush();
                    }
                }
            } catch (IOException e) {
                // Closed
            }
        }

        private void interval(OutputStream out) throws IOException {
            intervalSent = true;
            out.write(Jieli.encode(Jieli.KEEP_ALIVE_INTERVAL,
                    "{\"op\":\"NOTIFY\",\"param\":{\"timeout\":\"" + timeoutMs + "\"}}"));
        }

        private void sendVideo() throws IOException {
            try (DatagramSocket u = new DatagramSocket(0, LOOPBACK)) {
                InetSocketAddress to = new InetSocketAddress(LOOPBACK, rtpPort);
                // Something that is not RTP first: counted and logged, not played
                byte[] junk = {1, 2, 3};
                u.send(new DatagramPacket(junk, junk.length, to));
                for (byte[] p : jpegPackets()) u.send(new DatagramPacket(p, p.length, to));
            }
        }

        List<String> topics() {
            List<String> t = new ArrayList<>();
            for (Got g : got) t.add(g.topic);
            return t;
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    /** Runs the client on a thread; frames assembled from the RTP it delivers. */
    private static final class Running {
        final Jieli.Client client;
        final AtomicBoolean keepGoing = new AtomicBoolean(true);
        final List<byte[]> frames = new CopyOnWriteArrayList<>();
        final List<byte[]> jpegs = new CopyOnWriteArrayList<>();
        final List<String> states = new CopyOnWriteArrayList<>();
        final CountDownLatch frame = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        volatile Exception failure;
        final Thread thread;

        Running(String url) throws IOException {
            client = new Jieli.Client("test", Jieli.Url.parse(url), null, null);
            client.localForHint = Collections.emptyList();
            JpegAssembler assembler = new JpegAssembler(new FramePool(4));
            thread = new Thread(() -> {
                try {
                    client.run(keepGoing::get, new Jieli.Listener() {
                        @Override
                        public void onState(boolean receiving, String text) {
                            states.add(text);
                        }

                        @Override
                        public void onRtp(byte[] buf, int offset, int length) {
                            assembler.assemble(buf, offset, length, f -> {
                                frames.add(Arrays.copyOf(f.data(), f.length()));
                                frame.countDown();
                            });
                        }

                        @Override
                        public void onJpeg(byte[] buf, int offset, int length) {
                            jpegs.add(Arrays.copyOfRange(buf, offset, offset + length));
                            frame.countDown();
                        }
                    });
                } catch (Exception e) {
                    failure = e;
                } finally {
                    ended.countDown();
                }
            }, "jieli-client");
            thread.start();
        }
    }

    @Test
    public void sessionAsTheVendorApp() throws Exception {
        int rtp = freeUdpPort();
        try (FakeToy toy = new FakeToy(2000, true)) {
            toy.rtpPort = rtp;
            Running r = new Running("jieli://127.0.0.1:" + toy.port() + "?rtp=" + rtp);
            assertTrue("no frame", r.frame.await(5, TimeUnit.SECONDS));
            // A few keepalives at 2000 / 4 = 500 ms
            Thread.sleep(1700);
            r.keepGoing.set(false);
            assertTrue("no CLOSE_RT_STREAM", toy.closeStream.await(3, TimeUnit.SECONDS));
            assertTrue(r.ended.await(3, TimeUnit.SECONDS));
            assertNull(r.failure);

            List<String> t = toy.topics();
            assertEquals(Arrays.asList(Jieli.APP_ACCESS, Jieli.DATE_TIME, Jieli.KEEP_ALIVE,
                    Jieli.OPEN_RT_STREAM), t.subList(0, 4));
            assertEquals(Jieli.CLOSE_RT_STREAM, t.get(t.size() - 1));
            assertEquals("{\"op\":\"PUT\",\"param\":{\"ver\":\"25\",\"type\":\"0\"}}",
                    toy.got.get(0).json);
            assertTrue(toy.got.get(1).json,
                    toy.got.get(1).json.matches("\\{\"op\":\"PUT\",\"param\":\\{\"date\":"
                            + "\"\\d{14}\"}}"));
            // The size the toy announced in VIDEO_PARAM
            assertEquals(Jieli.openStream(0, 320, 240, 15), toy.got.get(3).json);
            assertEquals("{\"op\":\"PUT\",\"param\":{\"status\":\"1\"}}",
                    toy.got.get(t.size() - 1).json);
            assertEquals(1, Collections.frequency(t, Jieli.OPEN_RT_STREAM));

            List<Long> keepAlives = new ArrayList<>();
            for (Got g : toy.got) {
                if (g.topic.equals(Jieli.KEEP_ALIVE)) keepAlives.add(g.at);
            }
            assertTrue("keepalives: " + keepAlives.size(), keepAlives.size() >= 3);
            for (int i = 1; i < keepAlives.size(); i++) {
                long gap = keepAlives.get(i) - keepAlives.get(i - 1);
                assertTrue("gap " + gap, gap >= 400 && gap <= 900);
            }

            // The JPEG the RTP/JPEG path makes of the packets
            assertArrayEquals(resource("jpeg_q50_64x32.jpg"), r.frames.get(0));
            Map<String, Object> st = r.client.stats();
            @SuppressWarnings("unchecked")
            Map<String, Object> ctp = (Map<String, Object>) st.get("ctp");
            assertEquals(500L, ctp.get("keepalive_ms"));
            assertEquals("320x240 at 15 fps, format 0", st.get("video_param"));
            @SuppressWarnings("unchecked")
            Map<String, Object> rtpStats = (Map<String, Object>) st.get("rtp");
            assertEquals(1, rtpStats.get("not_rtp"));
            assertTrue((Integer) rtpStats.get("frames") >= 1);
            @SuppressWarnings("unchecked")
            Map<String, Object> sources = (Map<String, Object>) st.get("sources");
            assertEquals(1, sources.size());
            assertTrue(r.states.get(0), r.states.get(0).startsWith("Connecting to the toy (CTP"));
            assertTrue(r.states.contains("Stream requested, waiting for video on UDP " + rtp));
        }
    }

    @Test
    public void defaultSizeAndCloseOnStop() throws Exception {
        int rtp = freeUdpPort();
        try (FakeToy toy = new FakeToy(60000, false)) {
            toy.rtpPort = rtp;
            toy.sendVideo = false;
            Running r = new Running("jieli://127.0.0.1:" + toy.port() + "?rtp=" + rtp);
            assertTrue(toy.opened.await(5, TimeUnit.SECONDS));
            // No VIDEO_PARAM: 640x480 at 25 after the wait
            Got open = null;
            for (Got g : toy.got) if (g.topic.equals(Jieli.OPEN_RT_STREAM)) open = g;
            assertNotNull(open);
            assertEquals(Jieli.openStream(0, 640, 480, 25), open.json);
            assertTrue(open.at - toy.got.get(0).at >= 250);
            // close() as RtspSession.stop() does it: CLOSE_RT_STREAM still goes out
            r.client.close();
            assertTrue("no CLOSE_RT_STREAM", toy.closeStream.await(3, TimeUnit.SECONDS));
            assertTrue(r.ended.await(3, TimeUnit.SECONDS));
            assertNull(r.failure);
            @SuppressWarnings("unchecked")
            Map<String, Object> ctp = (Map<String, Object>) r.client.stats().get("ctp");
            assertEquals(7500L, ctp.get("keepalive_ms"));
        }
    }

    @Test
    public void lateIntervalAppliesAtOnce() throws Exception {
        int rtp = freeUdpPort();
        try (FakeToy toy = new FakeToy(2000, true)) {
            toy.rtpPort = rtp;
            toy.sendVideo = false;
            toy.lateInterval = true;
            Running r = new Running("jieli://127.0.0.1:" + toy.port() + "?rtp=" + rtp);
            assertTrue(toy.opened.await(5, TimeUnit.SECONDS));
            long until = now() + 3000;
            while (now() < until && Collections.frequency(toy.topics(), Jieli.KEEP_ALIVE) < 2) {
                Thread.sleep(20);
            }
            r.keepGoing.set(false);
            assertTrue(r.ended.await(3, TimeUnit.SECONDS));
            List<Long> keepAlives = new ArrayList<>();
            for (Got g : toy.got) {
                if (g.topic.equals(Jieli.KEEP_ALIVE)) keepAlives.add(g.at);
            }
            // The second one 500 ms after the first, not 7.5 s
            assertTrue("keepalives: " + keepAlives.size(), keepAlives.size() >= 2);
            long gap = keepAlives.get(1) - keepAlives.get(0);
            assertTrue("gap " + gap, gap >= 400 && gap <= 900);
        }
    }

    @Test
    public void noVideoListensElsewhereAndAsksAgain() throws Exception {
        int rtp = freeUdpPort();
        int diag = freeUdpPort();
        Jieli.noVideoMs = 300;
        Jieli.reopenMs = 700;
        Jieli.diagnosticPorts = new int[] {diag};
        try (FakeToy toy = new FakeToy(60000, true)) {
            toy.rtpPort = rtp;
            toy.sendVideo = false;
            Running r = new Running("jieli://127.0.0.1:" + toy.port() + "?rtp=" + rtp);
            assertTrue(toy.opened.await(5, TimeUnit.SECONDS));
            long until = now() + 3000;
            while (now() < until && !r.states.contains(r.client.noVideoText())) Thread.sleep(20);
            assertTrue(r.states.toString(), r.states.contains(r.client.noVideoText()));
            Thread.sleep(100);
            try (DatagramSocket u = new DatagramSocket(0, LOOPBACK)) {
                byte[] p = {(byte) 0x80, 97, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 9};
                u.send(new DatagramPacket(p, p.length, new InetSocketAddress(LOOPBACK, diag)));
            }
            until = now() + 3000;
            while (now() < until && (Collections.frequency(toy.topics(), Jieli.OPEN_RT_STREAM) < 2
                    || r.client.stats().get("diagnostic_ports") == null)) {
                Thread.sleep(20);
            }
            assertTrue(Collections.frequency(toy.topics(), Jieli.OPEN_RT_STREAM) >= 2);
            Map<String, Object> st = r.client.stats();
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) st.get("diagnostic_ports");
            assertNotNull(d);
            @SuppressWarnings("unchecked")
            Map<String, Object> one = (Map<String, Object>) d.get(String.valueOf(diag));
            assertEquals(1, one.get("packets"));
            r.keepGoing.set(false);
            assertTrue(r.ended.await(3, TimeUnit.SECONDS));
            assertNull(r.failure);
            // Nothing from a diagnostic port reaches the video path
            assertTrue(r.frames.isEmpty());
        }
    }

    @Test
    public void streamPortVideoIsPlayed() throws Exception {
        int rtp = freeUdpPort();
        try (FakeToy toy = new FakeToy(60000, true)) {
            toy.rtpPort = rtp;
            toy.sendVideo = false;
            Running r = new Running("jieli://127.0.0.1:" + toy.port() + "?rtp=" + rtp);
            assertTrue(toy.opened.await(5, TimeUnit.SECONDS));
            byte[] j = jpeg(4600);
            try (DatagramSocket u = new DatagramSocket(0, LOOPBACK)) {
                byte[] p = streamPacket(1, 4600, 0, j, 20);
                u.send(new DatagramPacket(p, p.length,
                        new InetSocketAddress(LOOPBACK, Jieli.streamPort)));
            }
            assertTrue(r.frame.await(3, TimeUnit.SECONDS));
            assertArrayEquals(j, r.jpegs.get(0));
            assertTrue(r.frames.isEmpty());
            long until = now() + 3000;
            while (now() < until && !r.client.stats().containsKey("video_port")) Thread.sleep(20);
            assertEquals(Jieli.streamPort, r.client.stats().get("video_port"));
            r.keepGoing.set(false);
            assertTrue(r.ended.await(3, TimeUnit.SECONDS));
            assertNull(r.failure);
        }
    }

    @Test
    public void unreachableToyFails() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, LOOPBACK)) {
            port = s.getLocalPort();
        }
        Jieli.Client c = new Jieli.Client("test",
                Jieli.Url.parse("jieli://127.0.0.1:" + port + "?rtp=" + freeUdpPort()), null, null);
        c.localForHint = Collections.emptyList();
        try {
            c.run(() -> true, new Jieli.Listener() {
                @Override
                public void onState(boolean receiving, String text) {}

                @Override
                public void onRtp(byte[] buf, int offset, int length) {}
            });
            fail("connected");
        } catch (IOException e) {
            assertTrue(e.getMessage(),
                    e.getMessage().startsWith("Cannot reach the toy's CTP port"));
        }
    }
}
