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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
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
    public void shownAddressesSkipLoopbackAndLinkLocal() throws Exception {
        assertTrue(NetInfo.isShown(ip("192.168.1.20")));
        assertTrue(NetInfo.isShown(ip("172.17.32.144")));
        assertTrue(NetInfo.isShown(ip("8.8.8.8")));
        assertFalse(NetInfo.isShown(ip("127.0.0.1")));
        assertFalse(NetInfo.isShown(ip("127.1.2.3")));
        assertFalse(NetInfo.isShown(ip("169.254.1.1")));
        assertFalse(NetInfo.isShown(ip("0.0.0.0")));
        assertFalse(NetInfo.isShown(ip("fd12::1")));
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

    // ====================== Save report (#207) ======================

    @Test
    public void reportHasHeaderStatsAndLogRedacted() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("app", "OpenIPC Decoder");
        // A source that forgot to redact: the whole text is redacted once more
        stats.put("url", "rtsp://root:hunter2@192.168.1.10:554/stream=0");
        String log = "10-15 12:00:00.000  1  2 I OpenIPCDecoder: Connecting to "
                + "rtsp://admin:s3cret@cam/stream=0?password=pw42\n"
                + "10-15 12:00:01.000  1  2 D OpenIPCDecoder: > Authorization: Basic cm9vdDox\n";
        String r = DebugServer.reportText(stats, log, new Date(0), 3 * 3600 + 62);

        assertTrue(r, r.startsWith("OpenIPC Decoder report\n"));
        assertTrue(r, r.contains("\nApp: OpenIPC Decoder " + BuildConfig.VERSION_NAME + " ("
                + BuildConfig.VERSION_CODE + "), git " + BuildConfig.GIT_HASH + "\n"));
        assertTrue(r, r.contains("\nDevice: "));
        assertTrue(r, r.contains("\nAndroid: "));
        assertTrue(r, r.contains("\nTime: "));
        assertTrue(r, r.contains("\nUptime: 3h 1m 2s\n"));

        int json = r.indexOf("\n== stats.json ==\n{\n  \"app\": \"OpenIPC Decoder\",\n");
        int logAt = r.indexOf("\n== log ==\n");
        assertTrue(r, json > 0);
        assertTrue(r, logAt > json);
        assertTrue(r, r.contains("192.168.1.10:554/stream=0"));
        assertTrue(r, r.endsWith("Authorization: Basic ***\n"));
        for (String secret : new String[] {"hunter2", "s3cret", "admin", "pw42", "cm9vdDox",
                "root:"}) {
            assertFalse(secret + " in\n" + r, r.contains(secret));
        }
    }

    @Test
    public void reportNameAndUptime() {
        String name = DebugServer.reportName(new Date());
        assertTrue(name, name.matches("report-\\d{8}-\\d{6}\\.txt"));
        assertEquals("0s", DebugServer.uptime(0));
        assertEquals("5m 0s", DebugServer.uptime(300));
        assertEquals("1d 0h 0m 1s", DebugServer.uptime(86401));
    }

    @Test
    public void reportIsServedAsADownload() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DebugServer().serve(out, "/report.txt", "");
        String reply = out.toString("UTF-8");
        int end = reply.indexOf("\r\n\r\n");
        assertTrue(reply, end > 0);
        String head = reply.substring(0, end + 2);
        String body = reply.substring(end + 4);
        assertTrue(head, head.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(head, head.contains("\r\nContent-Type: text/plain; charset=utf-8\r\n"));
        assertTrue(head, head.matches("(?s).*\r\nContent-Disposition: attachment; "
                + "filename=\"report-\\d{8}-\\d{6}\\.txt\"\r\n.*"));
        assertTrue(head, head.contains("\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"));
        assertTrue(body, body.startsWith("OpenIPC Decoder report\n"));
        assertTrue(body, body.contains("\n== stats.json ==\n"));
        assertTrue(body, body.contains("\n== log ==\n"));
    }

    @Test
    public void pageLinksTheReport() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DebugServer().serve(out, "/", "");
        String page = out.toString("UTF-8");
        assertTrue(page.contains("href=\"/report.txt\""));
        assertFalse(page.substring(page.indexOf("\r\n\r\n") + 4).contains("\n"));
    }
}
