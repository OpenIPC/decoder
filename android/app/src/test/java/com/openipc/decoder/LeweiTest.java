/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * LeweiTest.java — lewei:// slots: framing, RTP of Annex B frames, the session against a fake toy
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class LeweiTest {
    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static byte[] message(int cmd, byte[] body) {
        byte[] m = Lewei.command(cmd);
        m = Arrays.copyOf(m, Lewei.HEADER + body.length);
        m[11] = (byte) (cmd >> 8);
        m[14] = 0;
        m[22] = (byte) body.length;
        m[23] = (byte) (body.length >> 8);
        m[24] = (byte) (body.length >> 16);
        System.arraycopy(body, 0, m, Lewei.HEADER, body.length);
        return m;
    }

    private static byte[] video(boolean key, int timeMs, byte[] annexB) {
        byte[] body = new byte[Lewei.VIDEO_HEADER + annexB.length];
        body[0] = (byte) (key ? 1 : 0);
        body[4] = (byte) annexB.length;
        body[5] = (byte) (annexB.length >> 8);
        body[6] = (byte) (annexB.length >> 16);
        body[8] = (byte) timeMs;
        body[9] = (byte) (timeMs >> 8);
        body[10] = (byte) (timeMs >> 16);
        System.arraycopy(annexB, 0, body, Lewei.VIDEO_HEADER, annexB.length);
        return message(Lewei.CMD_VIDEO, body);
    }

    private static void feed(Lewei.Reader r, byte[] b, int from, int to) {
        while (from < to) {
            int n = Math.min(r.room(), to - from);
            System.arraycopy(b, from, r.buffer(), r.writeAt(), n);
            r.wrote(n);
            from += n;
        }
    }

    @Test
    public void urlDefaultsAndPort() throws IOException {
        assertTrue(Lewei.Url.matches(" LEWEI://192.168.0.1"));
        assertFalse(Lewei.Url.matches("rtsp://192.168.0.1"));
        Lewei.Url d = Lewei.Url.parse("lewei://");
        assertEquals("192.168.0.1", d.host);
        assertEquals(7060, d.port);
        Lewei.Url u = Lewei.Url.parse("lewei://10.0.2.2:17060/x?y=1");
        assertEquals("10.0.2.2", u.host);
        assertEquals(17060, u.port);
        assertEquals("10.0.2.2:17060", u.endpoint());
    }

    @Test
    public void bracketedHosts() throws IOException {
        Lewei.Url plain = Lewei.Url.parse("lewei://[::1]");
        assertEquals("::1", plain.host);
        assertEquals(Lewei.Url.parse("lewei://").port, plain.port);
        Lewei.Url port = Lewei.Url.parse("lewei://[::1]:17060");
        assertEquals("::1", port.host);
        assertEquals(17060, port.port);
    }

    @Test
    public void badUrls() {
        for (String s : new String[]{"lewei://h:0", "lewei://h:x", "lewei://a b", "lewei://[::1",
                "lewei://[192.168.0.1]garbage:1234", "lewei://[::1]x"}) {
            try {
                Lewei.Url.parse(s);
                fail(s);
            } catch (IOException e) {
                // expected
            }
        }
    }

    /** The bytes the vendor app sends, from the capture in #262. */
    @Test
    public void commandsAreThoseOfTheVendorApp() {
        String zeros = "00000000000000000000000000000000000000000000000000000000000000";
        assertArrayEquals(hex("6c657765695f636d64000200000001" + zeros), Lewei.command(2));
        assertArrayEquals(hex("6c657765695f636d64000100000000" + zeros), Lewei.command(1));
        assertArrayEquals(hex("6c657765695f636d64000300000000" + zeros), Lewei.command(3));
    }

    @Test
    public void readerTakesAVideoMessageInSmallPieces() {
        byte[] m = video(true, 94950, new byte[]{0, 0, 0, 1, 0x27, 1, 2});
        Lewei.Reader r = new Lewei.Reader();
        for (int i = 0; i < m.length - 1; i++) {
            feed(r, m, i, i + 1);
            assertFalse(r.next());
        }
        feed(r, m, m.length - 1, m.length);
        assertTrue(r.next());
        assertTrue(r.isVideo());
        assertTrue(r.isKeyFrame());
        assertEquals(94950, r.timeMs());
        assertEquals(Lewei.VIDEO_HEADER + 7, r.bodyLength);
        assertFalse(r.next());
    }

    /** Length, counter and the place of the inverted byte of frames in the capture of #262. */
    @Test
    public void mungedByteOfCapturedFrames() {
        assertEquals(6297, Lewei.mungedByte(94900, 10301));
        assertEquals(44633, Lewei.mungedByte(94950, 76625));
        assertEquals(6671, Lewei.mungedByte(95000, 10270));
    }

    @Test
    public void streamTypeOneRestoresTheInvertedByte() {
        byte[] annexB = new byte[64];
        annexB[2] = 1;
        annexB[3] = 0x21;
        int i = Lewei.mungedByte(5000, annexB.length);
        annexB[i] = (byte) 0xA5;
        byte[] m = video(false, 5000, annexB);
        m[26] = Lewei.STREAM_ONE_BYTE;
        m[Lewei.HEADER + Lewei.VIDEO_HEADER + i] = (byte) ~0xA5;
        Lewei.Reader r = new Lewei.Reader();
        feed(r, m, 0, m.length);
        assertTrue(r.next());
        assertTrue(r.restoreFrame());
        assertEquals((byte) 0xA5, r.buffer()[r.bodyAt + Lewei.VIDEO_HEADER + i]);
    }

    @Test
    public void plainStreamIsLeftAloneAndAnUnknownTypeIsSaid() {
        byte[] annexB = {0, 0, 1, 0x21, 1, 2, 3, 4};
        byte[] m = video(false, 5000, annexB);
        Lewei.Reader r = new Lewei.Reader();
        feed(r, m, 0, m.length);
        assertTrue(r.next());
        assertTrue(r.restoreFrame());
        assertArrayEquals(annexB, Arrays.copyOfRange(r.buffer(),
                r.bodyAt + Lewei.VIDEO_HEADER, r.bodyAt + r.bodyLength));
        m[26] = (byte) 129;
        feed(r, m, 0, m.length);
        assertTrue(r.next());
        assertFalse(r.restoreFrame());
    }

    @Test
    public void heartbeatReplyIsNotVideo() {
        byte[] m = message(Lewei.CMD_HEARTBEAT, new byte[64]);
        Lewei.Reader r = new Lewei.Reader();
        feed(r, m, 0, m.length);
        assertTrue(r.next());
        assertEquals(1, r.cmd);
        assertFalse(r.isVideo());
    }

    @Test
    public void readerFindsTheNextHeaderAfterDamage() {
        byte[] good = video(false, 1, new byte[]{0, 0, 1, 0x21});
        byte[] tooLong = message(Lewei.CMD_VIDEO, new byte[0]);
        tooLong[25] = 0x7F;
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(new byte[]{1, 2, 'l', 'e', 'w', 3}, 0, 6);
        s.write(tooLong, 0, tooLong.length);
        s.write(good, 0, good.length);
        byte[] b = s.toByteArray();
        Lewei.Reader r = new Lewei.Reader();
        feed(r, b, 0, b.length);
        assertTrue(r.next());
        assertTrue(r.isVideo());
        assertEquals(Lewei.VIDEO_HEADER + 4, r.bodyLength);
        assertEquals(6 + tooLong.length, r.skipped);
    }

    /** A plausible but wrong length must not hold back the frames behind it (Qodo on #267). */
    @Test
    public void wrongVideoLengthIsSeenBeforeItsBodyArrives() {
        byte[] damaged = video(false, 1, new byte[]{0, 0, 1, 0x21});
        damaged[23] = 0x10;
        byte[] good = video(false, 2, new byte[]{0, 0, 1, 0x21, 1});
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(damaged, 0, damaged.length);
        s.write(good, 0, good.length);
        byte[] b = s.toByteArray();
        Lewei.Reader r = new Lewei.Reader();
        feed(r, b, 0, b.length);
        assertTrue(r.next());
        assertEquals(Lewei.VIDEO_HEADER + 5, r.bodyLength);
        assertEquals(2, r.timeMs());
    }

    @Test
    public void largeMessageGrowsTheBuffer() {
        byte[] big = new byte[1024 * 1024];
        big[2] = 1;
        byte[] m = video(true, 5, big);
        m[24] = (byte) ((big.length + Lewei.VIDEO_HEADER) >> 16);
        Lewei.Reader r = new Lewei.Reader();
        feed(r, m, 0, m.length);
        assertTrue(r.next());
        assertEquals(big.length + Lewei.VIDEO_HEADER, r.bodyLength);
    }

    /** SPS, PPS and an IDR too long for one packet, as the toy sends them, through the app's path. */
    @Test
    public void frameComesOutOfTheNalAssemblerUnchanged() {
        byte[] sps = {0x27, 0x4D, (byte) 0xE0, 0x28};
        byte[] pps = {0x28, (byte) 0xEE, 0x0D};
        byte[] idr = new byte[5000];
        Arrays.fill(idr, (byte) 0x55);
        idr[0] = 0x25;
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        byte[] sc4 = {0, 0, 0, 1};
        f.write(sc4, 0, 4);
        f.write(sps, 0, sps.length);
        f.write(sc4, 0, 4);
        f.write(pps, 0, pps.length);
        f.write(new byte[]{0, 0, 1}, 0, 3);
        f.write(idr, 0, idr.length);
        byte[] b = f.toByteArray();

        List<byte[]> out = new ArrayList<>();
        NalAssembler a = new NalAssembler(1024 * 1024, () -> { }, new FramePool(4));
        NalAssembler.Sink sink = nal -> out.add(Arrays.copyOf(nal.data(), nal.length()));
        final int[] packets = {0};
        final int[] markers = {0};
        new Lewei.Packetizer().frame(b, 0, b.length, 95000, (buf, off, len) -> {
            packets[0]++;
            if ((buf[off + 1] & 0x80) != 0) markers[0]++;
            assertEquals(96, buf[off + 1] & 0x7F);
            assertTrue(len <= 1412);
            return a.assemble(buf, off, len, false, sink);
        });
        assertEquals(1, markers[0]);
        assertTrue(packets[0] > 4);
        assertEquals(3, out.size());
        assertTrue(endsWith(out.get(0), sps));
        assertTrue(endsWith(out.get(1), pps));
        assertTrue(endsWith(out.get(2), idr));
    }

    private static boolean endsWith(byte[] b, byte[] tail) {
        return b.length >= tail.length && Arrays.equals(
                Arrays.copyOfRange(b, b.length - tail.length, b.length), tail);
    }

    @Test
    public void rtpTimeIs90kHzAndSequenceRuns() {
        byte[] f = {0, 0, 1, 0x21, 1, 0, 0, 1, 0x21, 2};
        List<byte[]> p = new ArrayList<>();
        new Lewei.Packetizer().frame(f, 0, f.length, 1000, (buf, off, len) -> {
            p.add(Arrays.copyOfRange(buf, off, off + len));
            return true;
        });
        assertEquals(2, p.size());
        assertEquals(90000, (p.get(0)[5] & 0xFF) << 16 | (p.get(0)[6] & 0xFF) << 8
                | (p.get(0)[7] & 0xFF));
        assertEquals(1, p.get(1)[3] - p.get(0)[3]);
        assertEquals(0, p.get(0)[1] & 0x80);
        assertEquals(0x80, p.get(1)[1] & 0x80);
    }

    /** The session of the capture: start, video at once, heartbeats, stop at the end. */
    @Test(timeout = 15000)
    public void sessionAgainstAFakeToy() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            AtomicInteger starts = new AtomicInteger();
            AtomicInteger beats = new AtomicInteger();
            AtomicInteger stops = new AtomicInteger();
            CountDownLatch beat = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread toy = new Thread(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    byte[] m = new byte[Lewei.HEADER];
                    while (true) {
                        in.readFully(m);
                        if (m[10] == Lewei.CMD_START) {
                            starts.incrementAndGet();
                            out.write(video(true, 1000, new byte[]{0, 0, 1, 0x27, 1}));
                        } else if (m[10] == Lewei.CMD_HEARTBEAT) {
                            beats.incrementAndGet();
                            out.write(message(Lewei.CMD_HEARTBEAT, new byte[64]));
                            out.write(video(false, 1050, new byte[]{0, 0, 1, 0x21, 1}));
                            beat.countDown();
                        } else if (m[10] == Lewei.CMD_STOP) {
                            stops.incrementAndGet();
                            return;
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    failed.set(e);
                } finally {
                    done.countDown();
                }
            });
            toy.setDaemon(true);
            toy.start();

            Lewei.Url u = Lewei.Url.parse("lewei://127.0.0.1:" + server.getLocalPort());
            AtomicInteger rtp = new AtomicInteger();
            List<Boolean> states = new ArrayList<>();
            try (Lewei.Client c = new Lewei.Client("t", u, (sock, target) -> "default")) {
                c.run(() -> beat.getCount() > 0, new Lewei.Listener() {
                    @Override
                    public void onState(boolean receiving, String text) {
                        states.add(receiving);
                    }

                    @Override
                    public boolean onRtp(byte[] buf, int offset, int length) {
                        rtp.incrementAndGet();
                        return true;
                    }
                });
                assertTrue(c.streamRequested);
            }
            assertTrue(done.await(5, TimeUnit.SECONDS));
            if (failed.get() != null) throw new AssertionError(failed.get());
            assertEquals(1, starts.get());
            assertTrue(beats.get() >= 1);
            assertEquals(1, stops.get());
            assertTrue(rtp.get() >= 1);
            assertTrue(states.contains(true));
        }
    }

    @Test(timeout = 15000)
    public void toyThatClosesEndsTheSession() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread toy = new Thread(() -> {
                try (Socket s = server.accept()) {
                    s.getInputStream().read(new byte[Lewei.HEADER]);
                } catch (IOException e) {
                    // the test sees it
                }
            });
            toy.setDaemon(true);
            toy.start();
            Lewei.Url u = Lewei.Url.parse("lewei://127.0.0.1:" + server.getLocalPort());
            try (Lewei.Client c = new Lewei.Client("t", u, (sock, target) -> "default")) {
                try {
                    c.run(() -> true, new Lewei.Listener() {
                        @Override
                        public void onState(boolean receiving, String text) {
                        }

                        @Override
                        public boolean onRtp(byte[] buf, int offset, int length) {
                            return true;
                        }
                    });
                    fail("run() must end with an exception");
                } catch (IOException e) {
                    assertTrue(e.getMessage(), c.streamRequested);
                }
            }
        }
    }
}
