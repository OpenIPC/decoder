/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtpListenerTest.java — udp:// slots: URL parsing and plain RTP pushed by the camera
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RtpListenerTest {
    // ====================== URL ======================

    @Test
    public void listenOnAPort() throws Exception {
        RtpListener.Url u = RtpListener.Url.parse("udp://@:5600");
        assertNull(u.address);
        assertEquals(5600, u.port);
        assertFalse(u.multicast());
        assertNull(u.source);
        assertNull(u.codec);
        assertEquals(-1, u.pt);
        // Without the "@" too, and with rtp://
        assertEquals(5600, RtpListener.Url.parse("udp://:5600").port);
        assertEquals(5000, RtpListener.Url.parse("rtp://@:5000/").port);
    }

    @Test
    public void multicastGroupAndSource() throws Exception {
        RtpListener.Url u = RtpListener.Url.parse("udp://192.168.1.10@239.1.2.3:5600");
        assertTrue(u.multicast());
        assertEquals("239.1.2.3", u.address.getHostAddress());
        assertEquals("192.168.1.10", u.source.getHostAddress());

        u = RtpListener.Url.parse("udp://@[ff02::1234]:5600");
        assertTrue(u.multicast());
        assertEquals(5600, u.port);
    }

    @Test
    public void localAddressIsNotMulticast() throws Exception {
        RtpListener.Url u = RtpListener.Url.parse("udp://@127.0.0.1:5600");
        assertFalse(u.multicast());
        assertTrue(u.address.isLoopbackAddress());
    }

    @Test
    public void codecAndPayloadType() throws Exception {
        RtpListener.Url u = RtpListener.Url.parse("udp://@:5600?codec=hevc&pt=100");
        assertEquals("H265", u.codec);
        assertEquals(100, u.pt);
        assertTrue(u.h265(100));
        // By payload type, as Majestic sends them
        RtpListener.Url plain = RtpListener.Url.parse("udp://@:5600");
        assertTrue(plain.h265(97));
        assertFalse(plain.h265(96));
    }

    @Test
    public void badUrlsAreRefused() {
        String[] bad = {
            "udp://@239.1.2.3", "udp://@:0", "udp://@:70000", "udp://@:abc",
            "udp://@camera.local:5600", "udp://@:5600?codec=mjpeg", "udp://@:5600?pt=200",
            "rtsp://cam/stream", "udp://@[ff02::1:5600",
        };
        for (String url : bad) {
            try {
                RtpListener.Url.parse(url);
                fail("accepted " + url);
            } catch (IOException expected) {
            }
        }
        assertTrue(RtpListener.Url.matches(" UDP://@:5600"));
        assertFalse(RtpListener.Url.matches("rtsp://cam"));
        assertFalse(RtpListener.Url.matches(null));
    }

    // ====================== Session ======================

    private static final class Recorder implements RtspSession.Callback {
        final List<String> statuses = Collections.synchronizedList(new ArrayList<>());
        final List<Sdp> sdps = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> pts = Collections.synchronizedList(new ArrayList<>());
        volatile CountDownLatch latch = new CountDownLatch(3);

        @Override public void onStatus(String status) { statuses.add(status); }
        @Override public void onSdp(Sdp sdp) { sdps.add(sdp); }
        @Override public boolean onPacket(int channel, byte[] buf, int offset, int length) {
            assertEquals(RtspSession.CHANNEL_VIDEO, channel);
            pts.add(buf[offset + 1] & 0x7F);
            latch.countDown();
            return true;
        }
    }

    private final Recorder rec = new Recorder();
    private RtspSession session;
    private DatagramSocket sender;
    private int seq = 1000;

    @Before
    public void setUp() throws IOException {
        RtspSession.minRetryMs = 5;
        RtspSession.maxRetryMs = 20;
        session = new RtspSession("test", rec);
        sender = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    @After
    public void tearDown() {
        session.stop();
        sender.close();
        RtspSession.minRetryMs = 1000;
        RtspSession.maxRetryMs = 30000;
    }

    private static int freePort() throws IOException {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    private void send(int port, int pt, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            byte[] p = RtspSessionTest.udpPacket(pt, seq++);
            sender.send(new DatagramPacket(p, p.length, InetAddress.getLoopbackAddress(), port));
        }
    }

    /** Start listening and wait until the port is bound ("buffering"). */
    private void listen(String url) throws InterruptedException {
        session.configure(url, "", true, Transport.Mode.TCP);
        session.start();
        long deadline = System.currentTimeMillis() + 3000;
        while (!rec.statuses.contains("buffering") && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("listening", rec.statuses.contains("buffering"));
    }

    @Test
    public void receivesH264AndFollowsASwitchToH265() throws Exception {
        int port = freePort();
        listen("udp://@:" + port);
        send(port, 96, 3);
        assertTrue(rec.latch.await(3, TimeUnit.SECONDS));
        assertEquals(1, rec.sdps.size());
        assertEquals(96, rec.sdps.get(0).videoPt);
        assertFalse(rec.sdps.get(0).videoH265);
        assertTrue(rec.statuses.contains("connected"));

        // The camera switched to H.265: a new session description, then the packets
        rec.latch = new CountDownLatch(2);
        send(port, 97, 2);
        assertTrue(rec.latch.await(3, TimeUnit.SECONDS));
        assertEquals(2, rec.sdps.size());
        assertTrue(rec.sdps.get(1).videoH265);
    }

    @Test
    public void otherPayloadTypesAreNotVideo() throws Exception {
        int port = freePort();
        listen("udp://@:" + port);
        send(port, 98, 3); // audio or anything else: no codec, nothing delivered
        send(port, 96, 3);
        assertTrue(rec.latch.await(3, TimeUnit.SECONDS));
        assertEquals(1, rec.sdps.size());
        for (int pt : rec.pts) assertEquals(96, pt);
    }

    @Test
    public void forcedCodecAndPayloadType() throws Exception {
        int port = freePort();
        listen("udp://@:" + port + "?codec=h265&pt=110");
        send(port, 96, 2); // not the forced type: ignored
        send(port, 110, 3);
        assertTrue(rec.latch.await(3, TimeUnit.SECONDS));
        assertEquals(1, rec.sdps.size());
        assertEquals(110, rec.sdps.get(0).videoPt);
        assertTrue(rec.sdps.get(0).videoH265);
    }

    @Test
    public void wrongSourceIsIgnored() throws Exception {
        int port = freePort();
        listen("udp://192.0.2.1@:" + port);
        send(port, 96, 3);
        assertFalse(rec.latch.await(500, TimeUnit.MILLISECONDS));
        assertTrue(rec.sdps.isEmpty());
    }

    @Test
    public void portInUseIsRetriedNotShared() throws Exception {
        try (DatagramSocket busy = new DatagramSocket(0)) {
            int port = busy.getLocalPort();
            session.configure("udp://@:" + port, "", true, Transport.Mode.TCP);
            session.start();
            Thread.sleep(300);
            assertFalse(rec.statuses.contains("buffering"));
        }
    }

    @Test
    public void stopReleasesThePort() throws Exception {
        int port = freePort();
        listen("udp://@:" + port);
        session.stop();
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            try (DatagramSocket again = new DatagramSocket(port)) {
                return; // bound: released
            } catch (IOException stillBound) {
                Thread.sleep(20);
            }
        }
        fail("port still in use after stop()");
    }
}
