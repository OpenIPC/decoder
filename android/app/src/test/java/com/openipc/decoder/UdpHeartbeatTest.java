/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * UdpHeartbeatTest.java — the cooingdv heartbeat: when RTSP slots send it, and its schedule
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Map;

public class UdpHeartbeatTest {
    @After
    public void restoreInterval() {
        UdpHeartbeat.intervalMs = 1000;
    }

    @Test
    public void onForTheCooingdvWebcamPath() throws IOException {
        // Any host: the toy is usually 192.168.1.1, but not always
        RtspSession.Url u = RtspSession.Url.parse("rtsp://192.168.1.1:7070/webcam");
        assertEquals(7099, u.heartbeatPort);
        assertEquals("rtsp://192.168.1.1:7070/webcam", u.requestUrl);
        assertEquals(7099, RtspSession.Url.parse("rtsp://10.0.0.9:7070/webcam").heartbeatPort);
        assertEquals(7099, RtspSession.Url.parse("rtsp://10.0.0.9:7070/webcam/").heartbeatPort);
        // Not the path or not the port: off
        assertEquals(0, RtspSession.Url.parse("rtsp://192.168.1.1:554/webcam").heartbeatPort);
        assertEquals(0, RtspSession.Url.parse("rtsp://192.168.1.1:7070/live").heartbeatPort);
        assertEquals(0, RtspSession.Url.parse("rtsp://192.168.1.1/webcam").heartbeatPort);
        assertEquals(0, RtspSession.Url.parse("rtsp://192.168.1.10:554/stream=0").heartbeatPort);
    }

    @Test
    public void optionTurnsItOnOrOffAndIsNotSentToTheCamera() throws IOException {
        RtspSession.Url u = RtspSession.Url.parse("rtsp://192.168.1.1:554/live?heartbeat=7099");
        assertEquals(7099, u.heartbeatPort);
        assertEquals("rtsp://192.168.1.1:554/live", u.requestUrl);
        u = RtspSession.Url.parse("rtsp://192.168.1.1:7070/webcam?heartbeat=off");
        assertEquals(0, u.heartbeatPort);
        assertEquals("rtsp://192.168.1.1:7070/webcam", u.requestUrl);
        u = RtspSession.Url.parse("rtsp://192.168.1.1:7070/webcam?HeartBeat=0");
        assertEquals(0, u.heartbeatPort);
        // Other parameters stay, in their order
        u = RtspSession.Url.parse("rtsp://u:p@cam/x?a=1&heartbeat=7000&b=2#frag");
        assertEquals(7000, u.heartbeatPort);
        assertEquals("rtsp://cam/x?a=1&b=2", u.requestUrl);
        u = RtspSession.Url.parse("rtsp://cam/x?a=1");
        assertEquals(0, u.heartbeatPort);
        assertEquals("rtsp://cam/x?a=1", u.requestUrl);
        for (String bad : new String[] {"rtsp://cam/x?heartbeat=abc", "rtsp://cam/x?heartbeat=",
                "rtsp://cam/x?heartbeat=70000", "rtsp://cam/x?heartbeat=-5"}) {
            try {
                RtspSession.Url.parse(bad);
                fail(bad);
            } catch (IOException expected) {
                // ok
            }
        }
    }

    @Test
    public void sendsOneOneEverySecondUntilClosed() throws Exception {
        UdpHeartbeat.intervalMs = 100;
        InetAddress lo = InetAddress.getLoopbackAddress();
        try (DatagramSocket toy = new DatagramSocket(0, lo)) {
            toy.setSoTimeout(2000);
            UdpHeartbeat h = new UdpHeartbeat("test", lo,
                    new InetSocketAddress(lo, toy.getLocalPort()));
            h.start(true);
            assertTrue(h.running());
            long[] at = new long[4];
            byte[] buf = new byte[16];
            int port = -1;
            for (int i = 0; i < at.length; i++) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                toy.receive(p);
                at[i] = System.nanoTime() / 1_000_000;
                assertArrayEquals(UdpHeartbeat.PAYLOAD, Arrays.copyOf(buf, p.getLength()));
                // Always from the same ephemeral port
                if (port < 0) port = p.getPort();
                assertEquals(port, p.getPort());
            }
            // About one interval apart: the scheduler neither bursts nor stalls
            long span = at[at.length - 1] - at[0];
            assertTrue("span " + span, span >= 3 * 100 - 40 && span < 3 * 100 + 400);
            assertTrue(h.sent() >= 4);
            h.close();
            assertFalse(h.running());
            // What was on its way is read; then nothing more
            toy.setSoTimeout(50);
            try {
                while (true) toy.receive(new DatagramPacket(buf, buf.length));
            } catch (SocketTimeoutException expected) {
                // drained
            }
            toy.setSoTimeout(400);
            try {
                toy.receive(new DatagramPacket(buf, buf.length));
                fail("heartbeat after close");
            } catch (SocketTimeoutException expected) {
                // ok
            }
            Map<String, Object> m = UdpHeartbeat.stats(h, 3, 0);
            assertEquals(3 + h.sent(), m.get("sent"));
            assertEquals(false, m.get("running"));
            assertEquals("01 01", m.get("payload"));
            assertEquals(5, UdpHeartbeat.stats(null, 5, 0).get("sent"));
        }
    }
}
