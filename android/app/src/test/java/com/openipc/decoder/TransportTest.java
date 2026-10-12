/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * TransportTest.java — RTSP Transport header parsing and the slot transport setting
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TransportTest {
    @Test
    public void majesticUdpReply() {
        Transport t = Transport.parse(
                "RTP/AVP/UDP;unicast;client_port=50000-50001;server_port=46666-46667");
        assertFalse(t.tcp);
        assertFalse(t.multicast);
        assertArrayEquals(new int[] {50000, 50001}, t.clientPort);
        assertArrayEquals(new int[] {46666, 46667}, t.serverPort);
        assertNull(t.source);
    }

    @Test
    public void xmUdpReply() {
        Transport t = Transport.parse("RTP/AVP;unicast;mode=PLAY;source=172.17.32.53;"
                + "client_port=50000-50001;server_port=40008-40009;ssrc=00001BCC");
        assertFalse(t.tcp);
        assertFalse(t.multicast);
        assertEquals("172.17.32.53", t.source);
        assertArrayEquals(new int[] {40008, 40009}, t.serverPort);
    }

    @Test
    public void interleavedReplyIsTcp() {
        Transport t = Transport.parse("RTP/AVP/TCP;unicast;interleaved=0-1");
        assertTrue(t.tcp);
        assertArrayEquals(new int[] {0, 1}, t.interleaved);
        // Interleaved without the /TCP profile still means the RTSP connection
        assertTrue(Transport.parse("RTP/AVP;unicast;interleaved=2-3").tcp);
    }

    @Test
    public void multicastReply() {
        Transport t = Transport.parse(
                "RTP/AVP;multicast;destination=\"239.1.2.3\";port=5000-5001;ttl=4");
        assertTrue(t.multicast);
        assertEquals("239.1.2.3", t.destination);
        assertArrayEquals(new int[] {5000, 5001}, t.port);
        assertEquals(4, t.ttl);
    }

    @Test
    public void onlyTheFirstOfSeveralTransportsCounts() {
        Transport t = Transport.parse(
                "RTP/AVP;unicast;client_port=4000-4001,RTP/AVP/TCP;interleaved=0-1");
        assertFalse(t.tcp);
        assertArrayEquals(new int[] {4000, 4001}, t.clientPort);
    }

    @Test
    public void malformedValuesAreIgnored() {
        Transport t = Transport.parse("RTP/AVP;client_port=abc;server_port=70000-70001;ttl=x");
        assertNull(t.clientPort);
        assertNull(t.serverPort);
        assertEquals(-1, t.ttl);
        assertFalse(Transport.parse(null).tcp);
        assertArrayEquals(new int[] {6970, 6971}, Transport.range("6970"));
    }

    @Test
    public void requests() {
        assertEquals("RTP/AVP/TCP;unicast;interleaved=2-3", Transport.tcpRequest(2));
        assertEquals("RTP/AVP;unicast;client_port=50000-50001", Transport.udpRequest(50000));
        assertEquals("RTP/AVP;multicast", Transport.multicastRequest());
    }

    @Test
    public void modeFromSettings() {
        assertEquals(Transport.Mode.UDP, Transport.Mode.parse("udp"));
        assertEquals(Transport.Mode.AUTO, Transport.Mode.parse(" Auto "));
        assertEquals(Transport.Mode.TCP, Transport.Mode.parse("TCP"));
        assertEquals(Transport.Mode.TCP, Transport.Mode.parse(null));
        assertEquals(Transport.Mode.TCP, Transport.Mode.parse("quic"));
        assertEquals(Transport.Mode.MULTICAST, Transport.Mode.parse("multicast"));
    }

    @Test
    public void sourceIsOnlyALiteralAddress() {
        assertEquals("172.17.32.53", RtspSession.sourceAddress("172.17.32.53").getHostAddress());
        assertTrue(RtspSession.sourceAddress("::1").isLoopbackAddress());
        assertNull(RtspSession.sourceAddress("camera.example"));
        assertNull(RtspSession.sourceAddress(null));
    }

    @Test
    public void usableUdpReplies() {
        assertTrue(RtspSession.udpReplyUsable(Transport.parse(
                "RTP/AVP/UDP;unicast;client_port=50000-50001;server_port=1-2"), 50000));
        // No ports or no header at all: taken as agreement
        assertTrue(RtspSession.udpReplyUsable(Transport.parse("RTP/AVP;unicast"), 50000));
        assertTrue(RtspSession.udpReplyUsable(Transport.parse(null), 50000));
        assertFalse(RtspSession.udpReplyUsable(Transport.parse(
                "RTP/AVP;unicast;client_port=4000-4001"), 50000));
        assertFalse(RtspSession.udpReplyUsable(Transport.parse(
                "RTP/AVP;multicast;destination=239.1.2.3;port=5000-5001"), 50000));
    }
}
