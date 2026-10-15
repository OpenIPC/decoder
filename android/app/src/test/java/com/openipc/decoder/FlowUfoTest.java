/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * FlowUfoTest.java — flowufo:// slots: packets, acknowledgements, JPEG headers, a fake drone
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FlowUfoTest {
    /** Zig-zag position i to natural (row-major) position. */
    private static final int[] NATURAL = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
    };

    @After
    public void restoreTimers() {
        FlowUfo.resetMs = 3000;
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

    /** A type-1 packet as the drone sends it. */
    static byte[] fragment(long seq, long base, int index, int total, int bodyLength, int width,
                           int height, int quality, byte[] data, int dataOff, int dataLen) {
        byte[] p = new byte[FlowUfo.FRAGMENT_HEADER + dataLen];
        p[0] = (byte) 0x93;
        p[1] = 0x01;
        FlowUfo.le16(p, 2, p.length);
        FlowUfo.le64(p, 0x08, seq);
        FlowUfo.le64(p, 0x18, base);
        FlowUfo.le32(p, 0x20, index);
        FlowUfo.le32(p, 0x24, total);
        FlowUfo.le32(p, 0x28, bodyLength);
        FlowUfo.le16(p, 0x2C, width);
        FlowUfo.le16(p, 0x2E, height);
        p[0x30] = (byte) quality;
        p[0x34] = 1;
        p[0x35] = 1;
        System.arraycopy(data, dataOff, p, FlowUfo.FRAGMENT_HEADER, dataLen);
        return p;
    }

    /** Fragment {@code index} of {@code body} for frame {@code seq}. */
    static byte[] piece(long seq, byte[] body, int index) {
        return piece(seq, body, index, 640, 360, 50);
    }

    static byte[] piece(long seq, byte[] body, int index, int width, int height, int quality) {
        int total = (body.length + FlowUfo.FRAGMENT_DATA - 1) / FlowUfo.FRAGMENT_DATA;
        int at = index * FlowUfo.FRAGMENT_DATA;
        int len = Math.min(FlowUfo.FRAGMENT_DATA, body.length - at);
        return fragment(seq, 0, index, total, body.length, width, height, quality, body, at,
                len);
    }

    private static FlowUfo.Fragment parsed(byte[] p) {
        FlowUfo.Fragment f = new FlowUfo.Fragment();
        assertTrue(f.parse(p, 0, p.length));
        return f;
    }

    private static byte[] ack(FlowUfo.Window w, long nowMs) {
        byte[] out = new byte[FlowUfo.ACK_CAPACITY];
        int len = w.buildAck(out, nowMs);
        return Arrays.copyOf(out, len);
    }

    /** {seq, status, record length} of each record of an acknowledgement. */
    private static List<long[]> records(byte[] a) {
        List<long[]> list = new ArrayList<>();
        int n = (int) FlowUfo.u32(a, 0x08);
        int pos = FlowUfo.ACK_HEADER;
        for (int i = 0; i < n; i++) {
            long seq = FlowUfo.u32(a, pos) | FlowUfo.u32(a, pos + 4) << 32;
            long status = FlowUfo.u32(a, pos + 8);
            long len = FlowUfo.u32(a, pos + 12);
            list.add(new long[] {seq, status, len, pos});
            pos += (int) len;
        }
        assertEquals("records fill the packet", a.length, pos);
        return list;
    }

    // ====================== URL ======================

    @Test
    public void urlDefaultsAndPort() throws IOException {
        FlowUfo.Url u = FlowUfo.Url.parse("flowufo://192.168.169.1:8800");
        assertEquals("192.168.169.1", u.host);
        assertEquals(8800, u.port);
        u = FlowUfo.Url.parse("flowufo://");
        assertEquals(FlowUfo.DEFAULT_HOST, u.host);
        assertEquals(FlowUfo.DEFAULT_PORT, u.port);
        u = FlowUfo.Url.parse(" FLOWUFO://10.0.0.5/ ");
        assertEquals("10.0.0.5", u.host);
        assertEquals(8800, u.port);
        u = FlowUfo.Url.parse("flowufo://:9000");
        assertEquals(FlowUfo.DEFAULT_HOST, u.host);
        assertEquals(9000, u.port);
        assertEquals("192.168.169.1:9000", u.endpoint());
        assertTrue(FlowUfo.Url.matches("flowufo://"));
        assertFalse(FlowUfo.Url.matches("udp://@:5600"));
    }

    @Test
    public void badUrlsAreRefused() {
        for (String bad : new String[] {"flowufo://host:0", "flowufo://host:x",
                "flowufo://host:70000", "flowufo://a b", "flowufo://u@host", "http://x"}) {
            try {
                FlowUfo.Url.parse(bad);
                fail(bad);
            } catch (IOException expected) {
                // ok
            }
        }
    }

    @Test
    public void notCheckedBySingleViewProbes() {
        assertEquals("toy drone slot: not checked in single view",
                StreamProbes.skipReason("flowufo://", "rtsp://default", null));
    }

    // ====================== Packets ======================

    @Test
    public void packetTypeNeedsMagicAndLength() {
        byte[] service = bytes("93 04 0E 00  01 00 00 00  02 00 00 00  55 5A");
        assertEquals(FlowUfo.TYPE_SERVICE, FlowUfo.packetType(service, 0, service.length));
        // The length field must be the datagram size
        assertEquals(-1, FlowUfo.packetType(service, 0, service.length - 1));
        byte[] other = service.clone();
        other[0] = (byte) 0x94;
        assertEquals(-1, FlowUfo.packetType(other, 0, other.length));
        byte[] big = new byte[FlowUfo.MAX_PACKET + 1];
        big[0] = (byte) 0x93;
        FlowUfo.le16(big, 2, big.length);
        assertEquals(-1, FlowUfo.packetType(big, 0, big.length));
    }

    @Test
    public void fragmentFields() {
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        byte[] p = fragment(7, 3, 2, 5, 4500, 640, 360, 75, data, 0, 1024);
        // An unknown field at 0x0C (if 0x08 is a u32): the frame number stays 7
        p[0x0C] = 0x55;
        p[0x0F] = 0x66;
        FlowUfo.Fragment f = parsed(p);
        assertEquals(7, f.seq);
        assertEquals(3, f.base);
        assertEquals(2, f.index);
        assertEquals(5, f.total);
        assertEquals(4500, f.bodyLength);
        assertEquals(640, f.width);
        assertEquals(360, f.height);
        assertEquals(75, f.quality);
        assertTrue(f.gl);
        assertEquals(FlowUfo.FRAGMENT_HEADER, f.dataOffset);
        assertEquals(1024, f.dataLength);
        assertSame(p, f.buf);
    }

    @Test
    public void malformedFragmentsAreRefused() {
        byte[] d = new byte[1024];
        FlowUfo.Fragment f = new FlowUfo.Fragment();
        // Index beyond the count, no fragments, body longer than the fragments, no frame number
        byte[][] bad = {
                fragment(1, 0, 3, 3, 3000, 640, 360, 50, d, 0, 10),
                fragment(1, 0, 0, 0, 3000, 640, 360, 50, d, 0, 10),
                fragment(1, 0, 0, 2, 2049, 640, 360, 50, d, 0, 10),
                fragment(0, 0, 0, 1, 10, 640, 360, 50, d, 0, 10),
                fragment(1, 0, 0, FlowUfo.MAX_FRAGMENTS + 1, 10, 640, 360, 50, d, 0, 10),
        };
        for (byte[] p : bad) assertFalse(f.parse(p, 0, p.length));
        byte[] shortOne = Arrays.copyOf(fragment(1, 0, 0, 1, 10, 0, 0, 50, d, 0, 10), 0x30);
        FlowUfo.le16(shortOne, 2, shortOne.length);
        assertFalse(f.parse(shortOne, 0, shortOne.length));
        byte[] service = fragment(1, 0, 0, 1, 10, 0, 0, 50, d, 0, 10);
        service[1] = 4;
        assertFalse(f.parse(service, 0, service.length));
    }

    // ====================== Window and acknowledgements ======================

    @Test
    public void ackBytesExact() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] d = new byte[3 * 1024];
        // Frame 1 of two fragments, complete; frame 2 of three with the middle one missing
        assertNull(w.add(parsed(piece(1, Arrays.copyOf(d, 2000), 0)), 1000));
        assertNotNull(w.add(parsed(piece(1, Arrays.copyOf(d, 2000), 1)), 1000));
        assertNull(w.add(parsed(piece(2, Arrays.copyOf(d, 2100), 0)), 1010));
        assertNull(w.add(parsed(piece(2, Arrays.copyOf(d, 2100), 2)), 1010));

        // Fragments still coming: frame 2 is the one arriving, left out
        String header = "EF 02 %s  02 02 00 01  %s  00 00 00 00  00 00"
                + " 00000000000000000000000000000000 00000000000000000000000000000000"
                + " 00000000000000000000000000000000 00000000000000000000000000000000"
                + " 19 32 23 4B  00 00";
        byte[] expected = bytes(String.format(header, "6C 00", "01 00 00 00")
                + " 01 00 00 00 00 00 00 00  01 00 00 00  14 00 00 00  FF FF FF FF");
        assertArrayEquals(expected, ack(w, 1015));

        // A pause: frame 2 is in again, collecting, fragment 1 missing (bits past 3 set)
        expected = bytes(String.format(header, "80 00", "02 00 00 00")
                + " 01 00 00 00 00 00 00 00  01 00 00 00  14 00 00 00  FF FF FF FF"
                + " 02 00 00 00 00 00 00 00  00 00 00 00  14 00 00 00  FD FF FF FF");
        assertArrayEquals(expected, ack(w, 1030));

        // All complete and quiet: frame 3 is asked for whole (status 3, no mask)
        assertNotNull(w.add(parsed(piece(2, Arrays.copyOf(d, 2100), 1)), 1040));
        expected = bytes(String.format(header, "90 00", "03 00 00 00")
                + " 01 00 00 00 00 00 00 00  01 00 00 00  14 00 00 00  FF FF FF FF"
                + " 02 00 00 00 00 00 00 00  01 00 00 00  14 00 00 00  FF FF FF FF"
                + " 03 00 00 00 00 00 00 00  03 00 00 00  10 00 00 00");
        assertArrayEquals(expected, ack(w, 1060));
        assertEquals(1, w.nextRequests);
    }

    @Test
    public void maskOfManyFragments() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] body = new byte[40 * 1024 - 100];
        // 40 fragments: two mask words; only 0 and 33 arrive
        w.add(parsed(piece(1, body, 0)), 0);
        w.add(parsed(piece(1, body, 33)), 0);
        byte[] a = ack(w, 100);
        List<long[]> r = records(a);
        assertEquals(1, r.size());
        assertEquals(16 + 8, r.get(0)[2]);
        int pos = (int) r.get(0)[3] + 16;
        assertEquals(0x00000001L, FlowUfo.u32(a, pos));
        // Bit 1 is fragment 33; bits 8.. are past fragment 39
        assertEquals(0xFFFFFF02L, FlowUfo.u32(a, pos + 4));
    }

    @Test
    public void lateSlotAsksForItsNextFrameWhole() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] body = new byte[100];
        // Frames 1..4 and 6, each one fragment; frame 5 (slot of frame 1) never comes
        for (long seq : new long[] {1, 2, 3, 4, 6}) {
            assertNotNull(w.add(parsed(piece(seq, body, 0)), 0));
        }
        List<long[]> r = records(ack(w, 5));
        // Slot 0 (frame 1) is 5 behind: frame 5 whole; 3 and 4 complete; 6 is arriving
        assertEquals(3, r.size());
        assertArrayEquals(new long[] {5, FlowUfo.STATUS_WHOLE, 16}, Arrays.copyOf(r.get(0), 3));
        assertArrayEquals(new long[] {3, FlowUfo.STATUS_COMPLETE, 20}, Arrays.copyOf(r.get(1), 3));
        assertArrayEquals(new long[] {4, FlowUfo.STATUS_COMPLETE, 20}, Arrays.copyOf(r.get(2), 3));
        assertEquals(1, w.wholeRequests);
        // Frame 9 takes slot 0: frame 1 was complete, frame 5 never came
        w.add(parsed(piece(9, body, 0)), 10);
        assertEquals(1, w.lost);
    }

    @Test
    public void baseHidesOldFrames() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] body = new byte[100];
        w.add(parsed(piece(1, body, 0)), 0);
        w.add(parsed(piece(2, body, 0)), 0);
        byte[] p = fragment(3, 1, 0, 1, body.length, 640, 360, 50, body, 0, body.length);
        w.add(parsed(p), 0);
        List<long[]> r = records(ack(w, 1));
        // 1 is at or below the base, 3 is arriving: only 2
        assertEquals(1, r.size());
        assertEquals(2, r.get(0)[0]);
    }

    @Test
    public void incompleteFrameReplacedCountsAsLost() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] body = new byte[2000];
        w.add(parsed(piece(1, body, 0)), 0);
        // Frame 5 takes slot 0 while frame 1 misses a fragment
        assertNull(w.add(parsed(piece(5, body, 0)), 1));
        assertEquals(1, w.lost);
        // A fragment of frame 1 now is stale
        assertNull(w.add(parsed(piece(1, body, 1)), 2));
        assertEquals(1, w.stale);
        assertNotNull(w.add(parsed(piece(5, body, 1)), 3));
    }

    @Test
    public void duplicatesAndLateFrames() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] body = new byte[2000];
        w.add(parsed(piece(1, body, 0)), 0);
        w.add(parsed(piece(1, body, 0)), 0);
        assertEquals(1, w.duplicates);
        // Frame 2 completes first, frame 1 after it is not shown
        assertNotNull(w.add(parsed(piece(2, Arrays.copyOf(body, 10), 0)), 1));
        assertNull(w.add(parsed(piece(1, body, 1)), 2));
        assertEquals(1, w.late);
        assertEquals(2, w.completed);
        assertEquals(1, w.delivered);
    }

    @Test
    public void bodyIsAssembledInOrderOfIndex() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] body = new byte[2500];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 7);
        w.add(parsed(piece(1, body, 2)), 0);
        w.add(parsed(piece(1, body, 0)), 0);
        FlowUfo.Slot s = w.add(parsed(piece(1, body, 1)), 0);
        assertNotNull(s);
        assertArrayEquals(body, Arrays.copyOf(s.body, s.bodyLength));
    }

    @Test
    public void restartedDroneCountsFromOne() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] small = new byte[10];
        assertNotNull(w.add(parsed(piece(500, small, 0)), 0));
        // A run of fragments far behind: the drone started again from 1
        byte[] body = new byte[FlowUfo.RESTART_RUN * 1024];
        for (int i = 0; i < FlowUfo.RESTART_RUN; i++) w.add(parsed(piece(1, body, i)), 1);
        assertEquals(1, w.restarts);
        assertEquals(1, w.maxSeq);
        assertNotNull(w.add(parsed(piece(2, small, 0)), 2));
    }

    @Test
    public void oneStragglerDoesNotReset() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] body = new byte[2000];
        assertNotNull(w.add(parsed(piece(100, Arrays.copyOf(body, 10), 0)), 0));
        assertNull(w.add(parsed(piece(101, body, 0)), 10));
        // A delayed fragment of frame 35 is only stale
        assertNull(w.add(parsed(piece(35, body, 0)), 15));
        assertEquals(0, w.restarts);
        assertEquals(1, w.stale);
        assertEquals(101, w.maxSeq);
        assertNotNull(w.add(parsed(piece(101, body, 1)), 20));
    }

    @Test
    public void staleFragmentsDoNotHoldBackTheMask() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] body = new byte[2000];
        w.add(parsed(piece(1, body, 0)), 0);
        w.add(parsed(piece(5, body, 0)), 0);
        // Fragments of the replaced frame 1 keep coming: frame 5 is still due its mask
        for (int t = 5; t <= 40; t += 5) w.add(parsed(piece(1, body, 1)), t);
        List<long[]> r = records(ack(w, 40));
        assertEquals(1, r.size());
        assertEquals(5, r.get(0)[0]);
        assertEquals(FlowUfo.STATUS_COLLECTING, r.get(0)[1]);
    }

    @Test
    public void baseHiddenFrameDoesNotHoldUpTheNextRequest() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] body = new byte[2000];
        byte[] one = Arrays.copyOf(body, 10);
        // Frame 1 incomplete; frame 2 complete and says base 1
        w.add(parsed(piece(1, body, 0)), 0);
        w.add(parsed(fragment(2, 1, 0, 1, one.length, 640, 360, 50, one, 0, one.length)), 0);
        List<long[]> r = records(ack(w, 100));
        assertEquals(2, r.size());
        assertArrayEquals(new long[] {2, FlowUfo.STATUS_COMPLETE, 20}, Arrays.copyOf(r.get(0), 3));
        assertArrayEquals(new long[] {3, FlowUfo.STATUS_WHOLE, 16}, Arrays.copyOf(r.get(1), 3));
    }

    @Test
    public void shortFragments() {
        FlowUfo.Window w = new FlowUfo.Window();
        byte[] body = new byte[2500];
        // Fragment 0 with 100 bytes instead of 1024: stays missing
        byte[] p = fragment(1, 0, 0, 3, body.length, 640, 360, 50, body, 0, 100);
        assertNull(w.add(parsed(p), 0));
        assertEquals(1, w.shortFragments);
        assertEquals(0, w.slots[0].received);
        w.add(parsed(piece(1, body, 0)), 0);
        w.add(parsed(piece(1, body, 1)), 0);
        // The last one with 2 of its 452 bytes: the body ends there
        p = fragment(1, 0, 2, 3, body.length, 640, 360, 50, body, 2048, 2);
        FlowUfo.Slot s = w.add(parsed(p), 0);
        assertNotNull(s);
        assertEquals(2050, s.bodyLength);
        assertEquals(2, w.shortFragments);
    }

    // ====================== JPEG headers ======================

    @Test
    public void headerLayout() {
        byte[] h = FlowUfo.jpegHeader(50, 1280, 720);
        assertEquals(605, h.length);
        // Height and width in SOF0 at 0x91 and 0x93, big-endian
        assertEquals(720, (h[0x91] & 0xFF) << 8 | h[0x92] & 0xFF);
        assertEquals(1280, (h[0x93] & 0xFF) << 8 | h[0x94] & 0xFF);
        assertEquals(0xFF, h[0] & 0xFF);
        assertEquals(0xD8, h[1] & 0xFF);
        // Segments by their lengths
        List<Integer> markers = new ArrayList<>();
        int i = 2;
        while (i < h.length) {
            assertEquals(0xFF, h[i] & 0xFF);
            int marker = h[i + 1] & 0xFF;
            int len = (h[i + 2] & 0xFF) << 8 | h[i + 3] & 0xFF;
            markers.add(marker);
            if (marker == 0xC0) {
                assertEquals(17, len);
                assertEquals(8, h[i + 4]);
                assertEquals(3, h[i + 9]);
                // Every component 1x1 (4:4:4); Y on table 0, Cb and Cr on table 1
                assertArrayEquals(bytes("00 11 00  01 11 01  02 11 01"),
                        Arrays.copyOfRange(h, i + 10, i + 19));
            }
            if (marker == 0xDB) assertEquals(67, len);
            if (marker == 0xDA) {
                assertArrayEquals(bytes("03 00 00 01 11 02 11 00 3F 00"),
                        Arrays.copyOfRange(h, i + 4, i + 14));
            }
            i += 2 + len;
        }
        assertEquals(h.length, i);
        assertEquals(Arrays.asList(0xDB, 0xDB, 0xC0, 0xC4, 0xC4, 0xC4, 0xC4, 0xDA), markers);
        // DHT: Y DC, Y AC, chroma DC, chroma AC
        assertEquals(0x00, h[159 + 4]);
        assertEquals(0x10, h[159 + 33 + 4]);
    }

    @Test
    public void ijgQualityScaling() {
        // Zig-zag order: luma 16 11 12 14 ..., chroma 17 18 18 24 ...
        byte[] q50 = JpegAssembler.ijgTables(50);
        assertEquals(16, q50[0]);
        assertEquals(11, q50[1]);
        assertEquals(99, q50[63] & 0xFF);
        assertEquals(17, q50[64]);
        assertEquals(99, q50[127] & 0xFF);
        // libjpeg: scale 5000/q below 50, else 200 - 2q; (v * scale + 50) / 100 in 1..255
        assertEquals(8, JpegAssembler.ijgTables(75)[0]);
        assertEquals(6, JpegAssembler.ijgTables(75)[1]);
        assertEquals(32, JpegAssembler.ijgTables(25)[0] & 0xFF);
        assertEquals(80, JpegAssembler.ijgTables(10)[0] & 0xFF);
        assertEquals(160, JpegAssembler.ijgTables(5)[0] & 0xFF);
        assertEquals(170, JpegAssembler.ijgTables(5)[64] & 0xFF);
        assertEquals(255, JpegAssembler.ijgTables(5)[127] & 0xFF);
        for (byte v : JpegAssembler.ijgTables(100)) assertEquals(1, v);
        // The headers carry them: luma after "FF DB 00 43 00", chroma after "FF DB 00 43 01"
        byte[] h = FlowUfo.jpegHeader(75, 0, 0);
        assertArrayEquals(Arrays.copyOf(JpegAssembler.ijgTables(75), 64),
                Arrays.copyOfRange(h, 7, 71));
        assertArrayEquals(Arrays.copyOfRange(JpegAssembler.ijgTables(75), 64, 128),
                Arrays.copyOfRange(h, 76, 140));
        // No size: 640x360; no usable quality: Q50
        assertEquals(360, (h[0x91] & 0xFF) << 8 | h[0x92] & 0xFF);
        assertEquals(640, (h[0x93] & 0xFF) << 8 | h[0x94] & 0xFF);
        assertArrayEquals(FlowUfo.jpegHeader(50, 640, 360), FlowUfo.jpegHeader(0, 640, 360));
        // RFC 2435 keeps its own clamp to 99
        assertArrayEquals(JpegAssembler.ijgTables(99), JpegAssembler.tables(100));
    }

    @Test
    public void jpegAddsEoiOnceAndPassesWholeJpegs() {
        byte[] header = FlowUfo.jpegHeader(50, 8, 8);
        byte[][] out = new byte[1][];
        int n = FlowUfo.jpeg(header, new byte[] {1, 2, 3}, 3, out);
        assertEquals(header.length + 5, n);
        assertEquals(0xD9, out[0][n - 1] & 0xFF);
        n = FlowUfo.jpeg(header, new byte[] {1, (byte) 0xFF, (byte) 0xD9}, 3, out);
        assertEquals(header.length + 3, n);
        byte[] whole = {(byte) 0xFF, (byte) 0xD8, 5, (byte) 0xFF, (byte) 0xD9};
        n = FlowUfo.jpeg(header, whole, whole.length, out);
        assertArrayEquals(whole, Arrays.copyOf(out[0], n));
    }

    // ====================== Round trip through a real JPEG codec ======================

    // Unit tests compile against android.jar, which has no java.awt or javax.imageio: the
    // JDK's ImageIO (there at run time) is reached by reflection

    /**
     * Calls the public method {@code name} whose parameters take {@code args}, through a
     * public type: the classes behind ImageIO's interfaces are in packages of the JDK that are
     * not exported, so their own Method objects cannot be invoked.
     */
    private static Object call(Object target, String name, Object... args) throws Exception {
        boolean statik = target instanceof Class;
        Class<?> c = statik ? (Class<?>) target : target.getClass();
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            types.add(k);
            Collections.addAll(types, k.getInterfaces());
        }
        Exception last = new NoSuchMethodException(c.getName() + "." + name);
        for (Class<?> k : types) {
            if (!java.lang.reflect.Modifier.isPublic(k.getModifiers())) continue;
            for (java.lang.reflect.Method m : k.getMethods()) {
                if (!m.getName().equals(name) || !fits(m.getParameterTypes(), args)) continue;
                try {
                    return m.invoke(statik ? null : target, args);
                } catch (IllegalAccessException e) {
                    last = e;
                }
            }
        }
        throw last;
    }

    private static Object create(String className, Object... args) throws Exception {
        for (java.lang.reflect.Constructor<?> k : Class.forName(className).getConstructors()) {
            if (fits(k.getParameterTypes(), args)) return k.newInstance(args);
        }
        throw new NoSuchMethodException(className);
    }

    private static boolean fits(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) return false;
                continue;
            }
            Class<?> t = types[i] == int.class ? Integer.class
                    : types[i] == boolean.class ? Boolean.class : types[i];
            if (!t.isInstance(args[i])) return false;
        }
        return true;
    }

    private static Class<?> imageIo() throws Exception {
        return Class.forName("javax.imageio.ImageIO");
    }

    /** A java.awt.image.BufferedImage. */
    static final class Img {
        final Object image;

        Img(Object image) {
            this.image = image;
        }

        int width() throws Exception {
            return (Integer) call(image, "getWidth");
        }

        int height() throws Exception {
            return (Integer) call(image, "getHeight");
        }

        int[] pixels() throws Exception {
            int w = width();
            return (int[]) call(image, "getRGB", 0, 0, w, height(), null, 0, w);
        }

        /** Decoded by ImageIO, or null when it cannot. */
        static Img read(byte[] jpeg, int length) throws Exception {
            Object img = call(imageIo(), "read", new ByteArrayInputStream(jpeg, 0, length));
            return img != null ? new Img(img) : null;
        }
    }

    private static Img picture(int w, int h) throws Exception {
        // BufferedImage.TYPE_INT_RGB
        Object img = create("java.awt.image.BufferedImage", w, h, 1);
        int[] rgb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = x * 255 / Math.max(1, w - 1);
                int g = y * 255 / Math.max(1, h - 1);
                int b = ((x / 8 + y / 8) & 1) != 0 ? 200 : 40;
                rgb[y * w + x] = r << 16 | g << 8 | b;
            }
        }
        call(img, "setRGB", 0, 0, w, h, rgb, 0, w);
        return new Img(img);
    }

    /** Baseline 4:4:4 JPEG with the IJG tables at {@code quality}, by ImageIO. */
    static byte[] encode444(Img img, int quality) throws Exception {
        Object writer = ((java.util.Iterator<?>) call(imageIo(), "getImageWritersByFormatName",
                "jpeg")).next();
        Object param = call(writer, "getDefaultWriteParam");
        Object type = call(Class.forName("javax.imageio.ImageTypeSpecifier"),
                "createFromRenderedImage", img.image);
        Object meta = call(writer, "getDefaultImageMetadata", type, param);
        String format = "javax_imageio_jpeg_image_1.0";
        org.w3c.dom.Element root = (org.w3c.dom.Element) call(meta, "getAsTree", format);
        byte[] tables = JpegAssembler.ijgTables(quality);
        NodeList dqt = root.getElementsByTagName("dqtable");
        for (int i = 0; i < dqt.getLength(); i++) {
            org.w3c.dom.Element n = (org.w3c.dom.Element) dqt.item(i);
            int id = Integer.parseInt(n.getAttribute("qtableId"));
            int[] natural = new int[64];
            for (int k = 0; k < 64; k++) natural[NATURAL[k]] = tables[id * 64 + k] & 0xFF;
            call(n, "setUserObject", create("javax.imageio.plugins.jpeg.JPEGQTable",
                    (Object) natural));
        }
        NodeList comps = root.getElementsByTagName("componentSpec");
        for (int i = 0; i < comps.getLength(); i++) {
            org.w3c.dom.Element n = (org.w3c.dom.Element) comps.item(i);
            n.setAttribute("HsamplingFactor", "1");
            n.setAttribute("VsamplingFactor", "1");
        }
        call(meta, "setFromTree", format, root);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Object out = call(imageIo(), "createImageOutputStream", bytes);
        try {
            call(writer, "setOutput", out);
            Object image = create("javax.imageio.IIOImage", img.image, null, meta);
            call(writer, "write", null, image, param);
        } finally {
            call(out, "close");
            call(writer, "dispose");
        }
        return bytes.toByteArray();
    }

    /** The entropy-coded data between SOS and EOI: what the drone sends. */
    static byte[] scanOf(byte[] jpeg) {
        int i = 2;
        while (i + 4 <= jpeg.length) {
            int marker = jpeg[i + 1] & 0xFF;
            int len = (jpeg[i + 2] & 0xFF) << 8 | jpeg[i + 3] & 0xFF;
            if (marker == 0xC0) {
                // The encoder really wrote 4:4:4
                for (int c = 0; c < 3; c++) assertEquals(0x11, jpeg[i + 11 + 3 * c]);
            }
            if (marker == 0xDA) {
                int start = i + 2 + len;
                assertEquals(0xD9, jpeg[jpeg.length - 1] & 0xFF);
                return Arrays.copyOfRange(jpeg, start, jpeg.length - 2);
            }
            i += 2 + len;
        }
        throw new AssertionError("no SOS");
    }

    /**
     * ImageIO's own guess for a JPEG without JFIF or Adobe marker and no chroma subsampling is
     * RGB; libjpeg(-turbo), as on Android, takes YCbCr. For ImageIO the headers get a JFIF
     * APP0 after SOI, which says YCbCr; the rest is left as it is.
     */
    static byte[] withJfif(byte[] jpeg, int length) {
        byte[] app0 = bytes("FF E0 00 10 4A 46 49 46 00 01 01 00 00 01 00 01 00 00");
        byte[] out = new byte[length + app0.length];
        System.arraycopy(jpeg, 0, out, 0, 2);
        System.arraycopy(app0, 0, out, 2, app0.length);
        System.arraycopy(jpeg, 2, out, 2 + app0.length, length - 2);
        return out;
    }

    private static Img decode(byte[] jpeg, int length) throws Exception {
        byte[] j = withJfif(jpeg, length);
        return Img.read(j, j.length);
    }

    @Test
    public void scanWithOurHeadersDecodesLikeTheOriginal() throws Exception {
        for (int q : new int[] {5, 10, 25, 50, 75, 100}) {
            for (int[] size : new int[][] {{64, 48}, {50, 30}}) {
                Img src = picture(size[0], size[1]);
                byte[] original = encode444(src, q);
                byte[] scan = scanOf(original);
                byte[][] out = new byte[1][];
                int n = FlowUfo.jpeg(FlowUfo.jpegHeader(q, size[0], size[1]), scan, scan.length,
                        out);
                Img a = Img.read(original, original.length);
                Img b = decode(out[0], n);
                assertNotNull("Q" + q, b);
                assertEquals(size[0], b.width());
                assertEquals(size[1], b.height());
                assertArrayEquals("Q" + q + " " + size[0] + "x" + size[1], a.pixels(), b.pixels());
                if (q >= 75) {
                    // And close to the picture itself: colours are not mixed up
                    int[] p = src.pixels();
                    int[] r = b.pixels();
                    long err = 0;
                    for (int k = 0; k < p.length; k++) {
                        for (int sh = 0; sh <= 16; sh += 8) {
                            err += Math.abs((p[k] >> sh & 0xFF) - (r[k] >> sh & 0xFF));
                        }
                    }
                    assertTrue("Q" + q + " error " + err, err / (p.length * 3.0) < 8);
                }
            }
        }
    }

    // ====================== A fake drone on loopback ======================

    /** Next packet from the client that starts with EF 02, skipping probes. */
    private static byte[] nextAck(DatagramSocket drone, long deadlineMs) throws IOException {
        byte[] buf = new byte[4096];
        while (System.currentTimeMillis() < deadlineMs) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                drone.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (p.getLength() >= FlowUfo.ACK_HEADER && (buf[0] & 0xFF) == 0xEF && buf[1] == 2) {
                return Arrays.copyOf(buf, p.getLength());
            }
        }
        throw new AssertionError("no acknowledgement");
    }

    @Test
    public void fakeDroneOnLoopback() throws Exception {
        FlowUfo.resetMs = 400;
        Img src = picture(160, 120);
        byte[] original = encode444(src, 75);
        byte[] body = scanOf(original);
        int total = (body.length + 1023) / 1024;
        assertTrue("several fragments: " + total, total >= 3);

        try (DatagramSocket drone = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            drone.setSoTimeout(200);
            FlowUfo.Url u = FlowUfo.Url.parse("flowufo://127.0.0.1:" + drone.getLocalPort());
            FlowUfo.Client client = new FlowUfo.Client("test", u, null);
            List<byte[]> frames = Collections.synchronizedList(new ArrayList<>());
            List<String> states = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch frame = new CountDownLatch(1);
            CountDownLatch again = new CountDownLatch(1);
            Throwable[] error = new Throwable[1];
            Thread t = new Thread(() -> {
                try {
                    client.run(() -> true, new FlowUfo.Listener() {
                        @Override
                        public void onState(boolean receiving, String text) {
                            states.add(text);
                            if (text.contains("went quiet")) again.countDown();
                        }

                        @Override
                        public void onFrame(byte[] buf, int length) {
                            frames.add(Arrays.copyOf(buf, length));
                            frame.countDown();
                        }
                    });
                } catch (Throwable e) {
                    error[0] = e;
                }
            });
            t.start();
            try {
                // Probes first, every 100 ms
                byte[] buf = new byte[64];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                drone.setSoTimeout(2000);
                drone.receive(p);
                assertArrayEquals(FlowUfo.PROBE, Arrays.copyOf(buf, p.getLength()));
                SocketAddress phone = p.getSocketAddress();
                drone.setSoTimeout(200);
                assertTrue(states.get(0).startsWith("Waiting for the toy: probing 127.0.0.1:"));

                // Frame 1 without fragment 1
                for (int i = 0; i < total; i++) {
                    if (i == 1) continue;
                    byte[] f = piece(1, body, i, 160, 120, 75);
                    drone.send(new DatagramPacket(f, f.length, phone));
                }
                // A service message is only logged
                byte[] service = bytes("93 04 0E 00  01 00 00 00  02 00 00 00  55 5A");
                drone.send(new DatagramPacket(service, service.length, phone));

                // After the pause the acknowledgement asks for the missing fragment
                long deadline = System.currentTimeMillis() + 3000;
                long[] rec;
                byte[] a;
                do {
                    a = nextAck(drone, deadline);
                    List<long[]> r = records(a);
                    rec = r.isEmpty() ? null : r.get(0);
                } while (rec == null);
                assertEquals(1, rec[0]);
                assertEquals(FlowUfo.STATUS_COLLECTING, rec[1]);
                long mask = FlowUfo.u32(a, (int) rec[3] + 16);
                assertEquals("fragment 1 missing", 0, mask & 2);
                assertEquals(1, mask & 1);
                assertEquals(1, frame.getCount());

                byte[] f = piece(1, body, 1, 160, 120, 75);
                drone.send(new DatagramPacket(f, f.length, phone));
                assertTrue(frame.await(3, TimeUnit.SECONDS));
                byte[] jpeg = frames.get(0);
                byte[][] expected = new byte[1][];
                int n = FlowUfo.jpeg(FlowUfo.jpegHeader(75, 160, 120), body, body.length, expected);
                assertArrayEquals(Arrays.copyOf(expected[0], n), jpeg);
                Img got = decode(jpeg, jpeg.length);
                assertEquals(160, got.width());
                assertArrayEquals(Img.read(original, original.length).pixels(), got.pixels());

                // Then: frame 1 complete, frame 2 asked for whole
                boolean asked = false;
                deadline = System.currentTimeMillis() + 3000;
                while (!asked) {
                    for (long[] r : records(nextAck(drone, deadline))) {
                        if (r[0] == 2 && r[1] == FlowUfo.STATUS_WHOLE) asked = true;
                    }
                }
                assertEquals(1, client.window.delivered);
                assertEquals(1, client.stats().get("service_messages"));
                assertEquals("playing", client.stats().get("state"));

                // Silence: probing again
                assertTrue(again.await(3, TimeUnit.SECONDS));
                deadline = System.currentTimeMillis() + 2000;
                boolean probed = false;
                while (!probed && System.currentTimeMillis() < deadline) {
                    DatagramPacket q = new DatagramPacket(buf, buf.length);
                    try {
                        drone.receive(q);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    probed = Arrays.equals(FlowUfo.PROBE, Arrays.copyOf(buf, q.getLength()));
                }
                assertTrue(probed);
                assertEquals("probing", client.stats().get("state"));
            } finally {
                client.close();
                t.join(2000);
            }
            assertFalse(t.isAlive());
            assertNull(error[0]);
        }
    }

    @Test
    public void sameSubnet() throws Exception {
        InetAddress phone = InetAddress.getByName("192.168.169.2");
        InetAddress toy = InetAddress.getByName("192.168.169.1");
        assertTrue(NetInfo.sameSubnet(phone, 24, toy));
        assertFalse(NetInfo.sameSubnet(InetAddress.getByName("192.168.1.2"), 24, toy));
        assertTrue(NetInfo.sameSubnet(InetAddress.getByName("192.168.1.2"), 16, toy));
        assertFalse(NetInfo.sameSubnet(InetAddress.getByName("fe80::1"), 64, toy));
        assertFalse(NetInfo.sameSubnet(phone, 33, toy));
    }
}
