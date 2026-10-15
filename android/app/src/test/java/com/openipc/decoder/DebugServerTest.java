/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * DebugServerTest.java — debug HTTP server: LAN-only clients, redaction, JSON, requests (#96)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DebugServerTest {

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    public void localClientsAreServed() throws Exception {
        for (String a : new String[] {"127.0.0.1", "::1", "10.1.2.3", "172.16.0.1",
                "172.31.255.254", "192.168.1.20", "169.254.10.1", "fe80::1", "fd12:3456::1",
                "fc00::1", "::ffff:192.168.1.5"}) {
            assertTrue(a, NetInfo.isLocalClient(ip(a)));
        }
    }

    @Test
    public void otherClientsAreRejected() throws Exception {
        for (String a : new String[] {"8.8.8.8", "172.15.0.1", "172.32.0.1", "100.64.0.1",
                "2001:db8::1", "2a00:1450::1", "::ffff:8.8.8.8", "fec0::1"}) {
            assertFalse(a, NetInfo.isLocalClient(ip(a)));
        }
        assertFalse(NetInfo.isLocalClient(null));
    }

    @Test
    public void shownAddressesArePrivateIpv4Only() throws Exception {
        assertTrue(NetInfo.isShown(ip("192.168.1.20")));
        assertTrue(NetInfo.isShown(ip("172.17.32.144")));
        assertTrue(NetInfo.isShown(ip("10.0.0.5")));
        // Public and carrier-grade NAT: the debug server never answers there (#203)
        assertFalse(NetInfo.isShown(ip("8.8.8.8")));
        assertFalse(NetInfo.isShown(ip("33.91.55.135")));
        assertFalse(NetInfo.isShown(ip("100.64.1.2")));
        assertFalse(NetInfo.isShown(ip("127.0.0.1")));
        assertFalse(NetInfo.isShown(ip("127.1.2.3")));
        assertFalse(NetInfo.isShown(ip("169.254.1.1")));
        assertFalse(NetInfo.isShown(ip("0.0.0.0")));
        assertFalse(NetInfo.isShown(ip("fd12::1")));
    }

    private static Map<String, List<InetAddress>> interfaces(String... nameAndAddresses)
            throws Exception {
        Map<String, List<InetAddress>> m = new LinkedHashMap<>();
        for (String entry : nameAndAddresses) {
            String[] parts = entry.split(" ");
            List<InetAddress> addrs = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) addrs.add(ip(parts[i]));
            m.put(parts[0], addrs);
        }
        return m;
    }

    @Test
    public void lanAddressesSkipMobileData() throws Exception {
        // The OPPO in #187: two mobile-data addresses besides Wi-Fi (#203)
        assertEquals(Arrays.asList("192.168.1.42"), NetInfo.lanAddresses(interfaces(
                "lo 127.0.0.1 ::1",
                "rmnet_data0 33.91.55.135",
                "rmnet_data1 33.95.96.154 2a00:1fa0::5",
                "wlan0 192.168.1.42 fe80::1 fd00::42")));
        // Mobile data on private ranges (carrier NAT) is not shown either
        assertEquals(Arrays.asList("10.0.0.7"), NetInfo.lanAddresses(interfaces(
                "ccmni0 10.120.3.4",
                "v4-rmnet_data2 192.0.0.4",
                "clat4 192.0.0.4",
                "seth_lte0 10.11.12.13",
                "pdp0 172.20.0.1",
                "wwan0 192.168.200.2",
                "rev_rmnet0 10.9.9.9",
                "wlan0 10.0.0.7")));
        assertEquals(Arrays.asList(), NetInfo.lanAddresses(interfaces(
                "lo 127.0.0.1", "rmnet0 100.70.1.2")));
    }

    @Test
    public void lanAddressesWifiFirstThenEthernet() throws Exception {
        assertEquals(Arrays.asList("192.168.1.42", "192.168.43.1", "172.17.32.144", "10.42.0.1"),
                NetInfo.lanAddresses(interfaces(
                        "rndis0 10.42.0.1",
                        "eth0 172.17.32.144 169.254.3.3",
                        "wlan0 192.168.1.42",
                        "ap0 192.168.43.1 192.168.1.42")));
        // The system order stays within one kind
        assertEquals(Arrays.asList("192.168.2.2", "192.168.1.1"),
                NetInfo.lanAddresses(interfaces("eth1 192.168.2.2", "eth0 192.168.1.1")));
    }

    @Test
    public void cellularInterfaceNames() {
        for (String n : new String[] {"rmnet0", "rmnet_data0", "ccmni1", "v4-rmnet_data0",
                "clat4", "seth_lte1", "pdp0", "wwan0"}) {
            assertTrue(n, NetInfo.isCellular(n));
        }
        for (String n : new String[] {"wlan0", "eth0", "ap0", "swlan0", "rndis0", "lo"}) {
            assertFalse(n, NetInfo.isCellular(n));
        }
    }

    @Test
    public void credentialsAreRedacted() {
        String r = DebugServer.redact("rtsp://admin:secret@192.168.1.10:554/stream=0");
        assertFalse(r, r.contains("secret"));
        assertFalse(r, r.contains("admin"));
        assertTrue(r, r.contains("192.168.1.10:554/stream=0"));

        r = DebugServer.redact("http://cam/onvif?user=admin&password=secret");
        assertFalse(r, r.contains("secret"));
        assertFalse(r, r.contains("admin"));

        r = DebugServer.redact("rtsp://172.17.32.53/user=admin_password=12345_channel=1_stream=0.sdp");
        assertEquals("rtsp://172.17.32.53/user=***_password=***_channel=1_stream=0.sdp", r);
        assertEquals("login=*** username=***", DebugServer.redact("login=root username=root"));

        r = DebugServer.redact("D OpenIPCDecoder: > Authorization: Basic cm9vdDoxMjM0NQ==");
        assertFalse(r, r.contains("cm9vdDoxMjM0NQ"));
        assertTrue(r, r.contains("Authorization: Basic ***"));

        r = DebugServer.redact("Authorization: Digest username=\"root\", realm=\"x\", response=\"ab\"");
        assertFalse(r, r.contains("root"));
        assertFalse(r, r.contains("response"));

        assertNull(DebugServer.redact(null));
        assertEquals("udp://@:5600", DebugServer.redact("udp://@:5600"));
    }

    @Test
    public void jsonShape() {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("slot", 1);
        cell.put("in_fps", 24.5f);
        cell.put("paused", false);
        cell.put("text", "1920×1080 \"H.264\"\n<b>");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", "multi-view");
        m.put("uptime_s", 42L);
        m.put("none", null);
        m.put("nan", Float.NaN);
        m.put("cells", Arrays.asList(cell));
        assertEquals("{\"mode\":\"multi-view\",\"uptime_s\":42,\"none\":null,\"nan\":null,"
                + "\"cells\":[{\"slot\":1,\"in_fps\":24.50,\"paused\":false,"
                + "\"text\":\"1920×1080 \\\"H.264\\\"\\n\\u003cb\\u003e\"}]}",
                DebugServer.toJson(m));
    }

    @Test
    public void logBufferKeepsTheLastLinesRedacted() {
        LogBuffer log = new LogBuffer(3);
        log.add("one");
        log.add("rtsp://root:12345@cam/stream=0");
        log.add("three");
        log.add("four");
        String text = log.text();
        assertFalse(text, text.contains("one"));
        assertFalse(text, text.contains("12345"));
        assertTrue(text, text.contains("rtsp://***@cam/stream=0\nthree\nfour\n"));
    }

    @Test
    public void prettyJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "OpenIPC Decoder");
        m.put("addresses", Arrays.asList("a", "b"));
        m.put("empty", new java.util.ArrayList<>());
        assertEquals("{\n  \"app\": \"OpenIPC Decoder\",\n  \"addresses\": [\n    \"a\",\n"
                + "    \"b\"\n  ],\n  \"empty\": []\n}\n", DebugServer.toJson(m, true));
    }

    private static String head(String raw) throws IOException {
        return DebugServer.readRequestHead(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    public void requestLine() throws IOException {
        assertEquals("GET /stats.json HTTP/1.1",
                head("GET /stats.json HTTP/1.1\r\nHost: box\r\nAccept: */*\r\n\r\n"));
        assertEquals("GET / HTTP/1.0", head("GET / HTTP/1.0\n\n"));
        assertNull(head(""));
        assertNull(head("\r\n\r\n"));
    }
}
