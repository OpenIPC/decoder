/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * FlowUfo.java — video of FLOW-UFO toy drones: JPEG frames in UDP fragments, with acks (#187)
 *
 */

package com.openipc.decoder;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cheap Wi-Fi toy drones with a "FLOW-UFO-xxxxxx" / "FLOW_xxxxxx" access point (a BL60x
 * camera) send MJPEG over UDP from 192.168.169.1 in a protocol of their own (#187). A short
 * probe to port 8800 (BL608 engine) or 8801 (BL618 engine) wakes the drone; it then sends
 * every JPEG frame, cut into fragments of 1024 bytes, from source port 1234 to the address the
 * probe came from, and the client acknowledges the last four frames: what arrived, what is
 * missing, and which frame to send next. A frame carries only the entropy-coded data of the
 * scan: the client puts the JPEG headers in front. Video only: flight commands are never sent.
 *
 * The same protocol is implemented by the open-source project turbodrone (Apache-2.0,
 * github.com/marshallrichards/turbodrone, "wifi_uav") and works there on real drones; the
 * values taken over from its observations (quality parameters, both ports, which slots go into
 * the acknowledgement, the raster-order tables of ?dqt=raster) say so where they are used.
 * No code of it is used. Pure Java apart from the log.
 */
final class FlowUfo {
    private static final String TAG = "OpenIPCDecoder";

    static final String SCHEME = "flowufo://";
    static final String DEFAULT_HOST = "192.168.169.1";
    static final int DEFAULT_PORT = 8800;
    /**
     * The port of the BL618 engine. The vendor library starts both engines, on 8800 and 8801,
     * and keeps the one that delivers frames (turbodrone docs/research/wifi_uav.md): a slot on
     * 8800 (the default) probes both and locks onto the first that answers.
     */
    static final int BL618_PORT = 8801;

    /** Wakes the drone up; sent every PROBE_MS (BL618: PROBE_MS_BL618) until it answers. */
    static final byte[] PROBE = {(byte) 0xEF, 0x00, 0x04, 0x00};
    static final long PROBE_MS = 100;
    static final long PROBE_MS_BL618 = 200;
    static final long ACK_MS = 25;
    /** The BL618 engine acknowledges every 50 ms, and only when there is a record to send. */
    static final long ACK_MS_BL618 = 50;
    /** No fragment for longer: the last frame is over, the acknowledgement asks for the next. */
    static final long QUIET_MS = 19;
    /**
     * No fragment for this long: everything is reset and the probes start again. Not final so
     * unit tests can shorten it.
     */
    static volatile long resetMs = 3000;

    // Drone packets: 0x93, type, length (u16 LE, the whole datagram)
    static final int MAGIC = 0x93;
    static final int TYPE_FRAGMENT = 1;
    static final int TYPE_SERVICE = 4;
    static final int MAX_PACKET = 0x438;
    /** Header of a fragment; its data follows. */
    static final int FRAGMENT_HEADER = 0x38;
    /** Fragment i holds the body bytes from i * FRAGMENT_DATA on. */
    static final int FRAGMENT_DATA = 1024;
    /** More fragments than this (1 MiB) are not a frame of these cameras. */
    static final int MAX_FRAGMENTS = 1024;

    // The acknowledgement: a fixed header, then one record per frame
    static final int ACK_HEADER = 0x58;
    /**
     * Quality parameters at 0x52 of the acknowledgement: 32 4B 14 2D, the bytes turbodrone
     * sends to real drones (#187). The vendor app's own default was read as 19 32 23 4B; that
     * one, or any other, can be given with ?qpara= (8 hex digits).
     */
    static final byte[] QPARA = {0x32, 0x4B, 0x14, 0x2D};
    static final int SLOTS = 4;
    /**
     * A slot this many frames behind the newest is left out of the acknowledgement, as
     * turbodrone does: the drone has moved on, asking for it again only holds it up.
     */
    static final int LAG = 5;
    /** Fragments in a row this far behind the newest frame: the drone counts from 1 again. */
    static final int RESTART_RUN = 8;
    static final int STATUS_COLLECTING = 0;
    static final int STATUS_COMPLETE = 1;
    static final int STATUS_WHOLE = 3;
    private static final int MAX_MASK_WORDS = (MAX_FRAGMENTS + 31) / 32;
    static final int ACK_CAPACITY = ACK_HEADER + (SLOTS + 1) * (16 + 4 * MAX_MASK_WORDS);

    // The JPEG headers put in front of a body. Unverified choices, easy to change here: the
    // luma sampling (0x11 = 4:4:4, like the chroma), no restart interval. With these the headers
    // are 605 bytes and SOF0 has the height at 0x91 and the width at 0x93, the layout the vendor
    // app is known to use. Cb and Cr share the second quantization and Huffman tables
    static final int LUMA_SAMPLING = 0x11;
    static final int RESTART_INTERVAL = 0;
    /** A frame that names no usable quality gets this one. */
    static final int DEFAULT_QUALITY = 50;
    static final int DEFAULT_WIDTH = 640;
    static final int DEFAULT_HEIGHT = 360;

    /**
     * Quantization tables of the header, ?dqt= (#187). ZIGZAG (default): the Annex K tables
     * scaled to the quality byte of the fragment, in zig-zag order as the JPEG standard has
     * DQT. RASTER: unscaled Annex K tables in raster (row-major) order, quality ignored: what
     * turbodrone writes, and it shows real drones' frames. Q50: unscaled, zig-zag. Which one
     * matches the drone's encoder is not known yet; the tester compares.
     */
    static final int DQT_ZIGZAG = 0;
    static final int DQT_RASTER = 1;
    static final int DQT_Q50 = 2;
    static final String[] DQT_NAMES = {"zigzag", "raster", "q50"};

    /** Zig-zag position i to raster (row-major) position. */
    private static final int[] RASTER_OF_ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
    };

    private FlowUfo() {}

    // ====================== URL ======================

    /**
     * flowufo://[host][:port][/...][?option=value&...]: the drone's address, 192.168.169.1:8800
     * when left out. Port 8800 (also when left out) probes 8801 as well; any other port only
     * itself. Options, as in udp:// URLs (unknown ones are ignored, bad values refused):
     * qpara=8 hex digits (the quality parameters of the acknowledgement, default 324B142D),
     * dqt=zigzag|raster|q50 (the quantization tables of the JPEG header, default zigzag).
     */
    static final class Url {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        /** Probed as well as port until one answers, or 0. Not final so tests can move it. */
        int alternatePort;
        byte[] qpara = QPARA.clone();
        int dqt = DQT_ZIGZAG;

        static boolean matches(String url) {
            return url != null && url.trim().toLowerCase(Locale.ROOT).startsWith(SCHEME);
        }

        static Url parse(String url) throws IOException {
            if (!matches(url)) throw new IOException("Not a flowufo:// URL");
            String rest = url.trim().substring(SCHEME.length());
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char ch = rest.charAt(i);
                if (ch == '/' || ch == '?' || ch == '#') {
                    end = i;
                    break;
                }
            }
            String authority = rest.substring(0, end).trim();
            String query = "";
            int q = rest.indexOf('?');
            int hash = rest.indexOf('#');
            if (q >= 0 && (hash < 0 || q < hash)) {
                query = rest.substring(q + 1, hash >= 0 ? hash : rest.length());
            }
            Url u = new Url();
            String host = authority;
            int colon = authority.lastIndexOf(':');
            if (authority.startsWith("[")) {
                int close = authority.indexOf(']');
                if (close < 0) throw new IOException("Bad host in URL");
                host = authority.substring(1, close);
                colon = authority.indexOf(':', close);
            } else if (colon >= 0) {
                host = authority.substring(0, colon);
            }
            if (colon >= 0) {
                try {
                    u.port = Integer.parseInt(authority.substring(colon + 1));
                } catch (NumberFormatException e) {
                    throw new IOException("Bad port in URL");
                }
                if (u.port <= 0 || u.port > 65535) throw new IOException("Bad port in URL");
            }
            if (!host.isEmpty()) u.host = host;
            for (int i = 0; i < u.host.length(); i++) {
                char ch = u.host.charAt(i);
                if (ch <= 0x20 || ch == 0x7F || ch == '@') throw new IOException("Bad host in URL");
            }
            u.alternatePort = u.port == DEFAULT_PORT ? BL618_PORT : 0;
            for (String param : query.split("&")) {
                int eq = param.indexOf('=');
                if (eq < 0) continue;
                String name = param.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = param.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                if (name.equals("qpara") || name.equals("q")) {
                    u.qpara = parseQpara(value);
                } else if (name.equals("dqt")) {
                    u.dqt = Arrays.asList(DQT_NAMES).indexOf(value);
                    if (u.dqt < 0) {
                        throw new IOException("Unknown dqt: " + value + " (zigzag, raster or q50)");
                    }
                }
            }
            return u;
        }

        /** Exactly 8 hex digits, e.g. 19322348, as 4 bytes. */
        static byte[] parseQpara(String v) throws IOException {
            if (v.length() != 8) throw new IOException("qpara needs 8 hex digits: " + v);
            byte[] b = new byte[4];
            for (int i = 0; i < 4; i++) {
                int hi = Character.digit(v.charAt(2 * i), 16);
                int lo = Character.digit(v.charAt(2 * i + 1), 16);
                if (hi < 0 || lo < 0) throw new IOException("qpara needs 8 hex digits: " + v);
                b[i] = (byte) (hi << 4 | lo);
            }
            return b;
        }

        /** The ports probed: the given one, then the alternate one if any. */
        int[] ports() {
            return alternatePort > 0 && alternatePort != port
                    ? new int[] {port, alternatePort} : new int[] {port};
        }

        String endpoint() {
            return endpoint(port);
        }

        String endpoint(int p) {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + p;
        }

        /** "192.168.169.1:8800 and :8801", or the one endpoint. */
        String probed() {
            int[] p = ports();
            return p.length > 1 ? endpoint(p[0]) + " and :" + p[1] : endpoint(p[0]);
        }
    }

    // ====================== Drone packets ======================

    /** Type of a drone packet, or -1 when it is none: wrong first byte or length. */
    static int packetType(byte[] b, int off, int len) {
        if (len < 4 || len > MAX_PACKET || (b[off] & 0xFF) != MAGIC) return -1;
        if (u16(b, off + 2) != len) return -1;
        return b[off + 1] & 0xFF;
    }

    /** One frame fragment (type 1); fields as little-endian numbers. */
    static final class Fragment {
        /**
         * Frame number, counting from 1. Read as u32 at 0x08: whether 0x08 is a u64 or a u32
         * followed by another field at 0x0C is not known, and both read the same while the
         * upper half is zero. The log shows 0x08..0x17 of the first fragments.
         */
        long seq;
        /** Frames up to this one are not acknowledged any more (low 32 bits of 0x18). */
        long base;
        int index;
        int total;
        int bodyLength;
        int width;
        int height;
        int quality;
        /**
         * Main and flow camera status at 0x34 and 0x35 (turbodrone docs: the vendor library
         * passes them on with the frame). Shown in the stats, not used.
         */
        int mainCamera;
        int flowCamera;
        /** The packet parsed last; its data starts at dataOffset. */
        byte[] buf;
        int dataOffset;
        int dataLength;

        /** Fills the fields from a type-1 packet; false when it is malformed. */
        boolean parse(byte[] b, int off, int len) {
            if (len < FRAGMENT_HEADER || packetType(b, off, len) != TYPE_FRAGMENT) return false;
            seq = u32(b, off + 0x08);
            base = u32(b, off + 0x18);
            long idx = u32(b, off + 0x20);
            long tot = u32(b, off + 0x24);
            long body = u32(b, off + 0x28);
            width = u16(b, off + 0x2C);
            height = u16(b, off + 0x2E);
            quality = b[off + 0x30] & 0xFF;
            mainCamera = b[off + 0x34] & 0xFF;
            flowCamera = b[off + 0x35] & 0xFF;
            buf = b;
            dataOffset = off + FRAGMENT_HEADER;
            dataLength = len - FRAGMENT_HEADER;
            if (seq == 0 || tot < 1 || tot > MAX_FRAGMENTS || idx >= tot) return false;
            if (body < 1 || body > tot * FRAGMENT_DATA || dataLength > FRAGMENT_DATA) return false;
            index = (int) idx;
            total = (int) tot;
            bodyLength = (int) body;
            return true;
        }
    }

    // ====================== Frames and acknowledgements ======================

    /** One frame being collected, in slot (seq - 1) & 3. */
    static final class Slot {
        long seq;
        int status;
        int total;
        int received;
        int bodyLength;
        int width;
        int height;
        int quality;
        int[] mask = new int[1];
        byte[] body = new byte[64 * 1024];

        void clear() {
            seq = 0;
            status = STATUS_COLLECTING;
            received = 0;
        }

        void start(Fragment f) {
            seq = f.seq;
            status = STATUS_COLLECTING;
            total = f.total;
            received = 0;
            bodyLength = f.bodyLength;
            width = f.width;
            height = f.height;
            quality = f.quality;
            int words = (total + 31) / 32;
            if (mask.length < words) mask = new int[words];
            Arrays.fill(mask, 0);
            if (body.length < total * FRAGMENT_DATA) body = new byte[total * FRAGMENT_DATA];
        }

        boolean has(int i) {
            return (mask[i >> 5] & (1 << (i & 31))) != 0;
        }

        /** Mask word w as acknowledged: fragments past the frame's last count as received. */
        int ackWord(int w) {
            int v = mask[w];
            int first = w * 32;
            if (first + 32 > total) v |= -1 << (total - first);
            return v;
        }
    }

    /**
     * The four frames in flight and the acknowledgement built from them. A frame goes into
     * slot (seq - 1) & 3; a newer frame of the same slot replaces the one there, which counts
     * as lost unless it was complete (the drone should only send seq + 4 there, frames skipped
     * on the way are lost too). Single-threaded; the counters are read by the stats.
     */
    static final class Window {
        final Slot[] slots = new Slot[SLOTS];
        long maxSeq;
        long base;
        /** The frame whose fragments arrive now: not acknowledged while it is coming in. */
        long current;
        long lastDelivered;
        /** When a fragment last added data: the quiet time of the acknowledgement. */
        long lastFragmentMs;
        /** Fragments in a row far behind the newest frame. */
        private int behind;
        volatile int fragments;
        volatile int duplicates;
        volatile int stale;
        volatile int mismatched;
        /** Fragments with less data than their place in the body needs. */
        volatile int shortFragments;
        volatile int completed;
        volatile int delivered;
        volatile int lost;
        volatile int late;
        /** Records left out because their slot lagged LAG or more frames behind. */
        volatile int lagging;
        volatile int nextRequests;
        /** Quality parameters of the acknowledgement. */
        byte[] qpara = QPARA;
        /** The frame numbers started from 1 again (the drone restarted). */
        volatile int restarts;

        Window() {
            for (int i = 0; i < SLOTS; i++) slots[i] = new Slot();
        }

        void reset() {
            for (Slot s : slots) s.clear();
            maxSeq = 0;
            base = 0;
            current = 0;
            lastDelivered = 0;
            behind = 0;
        }

        static int slotOf(long seq) {
            return (int) ((seq - 1) & (SLOTS - 1));
        }

        /**
         * Adds a fragment; returns its slot when the fragment completed a frame to show. Only a
         * fragment that adds data counts for the quiet time: stale ones and duplicates do not
         * (Qodo on #188).
         */
        Slot add(Fragment f, long nowMs) {
            if (maxSeq > 64 && f.seq + 64 < maxSeq) {
                // Far behind the newest frame: a straggler, or the drone counts from 1 again;
                // only a run of them is taken for a restart (Qodo on #188)
                if (++behind < RESTART_RUN) {
                    stale++;
                    return null;
                }
                restarts++;
                reset();
            }
            behind = 0;
            // Only a base behind the frame itself makes sense
            if (f.base > base && f.base < f.seq) base = f.base;
            Slot s = slots[slotOf(f.seq)];
            if (f.seq < s.seq) {
                stale++;
                return null;
            }
            if (f.seq > s.seq) {
                if (s.seq > 0) {
                    if (s.status != STATUS_COMPLETE) lost++;
                    // Frames of this slot that never came at all
                    lost += (int) Math.min(1000, (f.seq - s.seq) / SLOTS - 1);
                }
                s.start(f);
            }
            fragments++;
            if (f.seq > maxSeq) maxSeq = f.seq;
            if (s.status == STATUS_COMPLETE || (f.total == s.total && s.has(f.index))) {
                duplicates++;
                return null;
            }
            if (f.total != s.total) {
                mismatched++;
                return null;
            }
            if (f.bodyLength != s.bodyLength) {
                // Not seen as such; the longest is taken, the fragments fit either way
                mismatched++;
                s.bodyLength = Math.max(s.bodyLength, f.bodyLength);
            }
            int at = f.index * FRAGMENT_DATA;
            if (f.dataLength < Math.min(FRAGMENT_DATA, s.bodyLength - at)) {
                shortFragments++;
                // A short one inside the body stays missing (Qodo on #188); the last one ends
                // the body where its data ends
                if (f.index < s.total - 1) return null;
                s.bodyLength = at + f.dataLength;
            }
            int n = Math.min(f.dataLength, s.total * FRAGMENT_DATA - at);
            System.arraycopy(f.buf, f.dataOffset, s.body, at, n);
            s.mask[f.index >> 5] |= 1 << (f.index & 31);
            s.received++;
            lastFragmentMs = nowMs;
            current = f.seq;
            if (s.received < s.total) return null;
            s.status = STATUS_COMPLETE;
            completed++;
            if (s.seq <= lastDelivered) {
                // Completed after a newer frame was shown
                late++;
                return null;
            }
            lastDelivered = s.seq;
            delivered++;
            return s;
        }

        /**
         * The acknowledgement for now, into {@code out} (ACK_CAPACITY bytes); returns its
         * length. Per slot: complete (status 1) or still collecting (0) with the mask of the
         * fragments received; a slot LAG or more frames behind the newest is left out (as in
         * turbodrone), and does not hold up the request for the next frame either. The frame
         * coming in now is left out until the fragments pause; after QUIET_MS without any, when
         * every frame is complete, a record asks for the frame after the newest (status 3).
         * Unverified: the record order, and that the paused frame is included again.
         */
        int buildAck(byte[] out, long nowMs) {
            boolean quiet = nowMs - lastFragmentMs > QUIET_MS;
            int pos = ACK_HEADER;
            int records = 0;
            boolean any = false;
            boolean allComplete = true;
            for (Slot s : slots) {
                // At or below the base the drone is done with it: no record, and an incomplete
                // one does not hold up the request for the next frame (Qodo on #188)
                if (s.seq == 0 || s.seq <= base) continue;
                if (maxSeq - s.seq >= LAG) {
                    lagging++;
                    continue;
                }
                any = true;
                if (s.status != STATUS_COMPLETE) allComplete = false;
                if (s.seq == current && !quiet) continue;
                int words = (s.total + 31) / 32;
                le64(out, pos, s.seq);
                boolean complete = s.status == STATUS_COMPLETE;
                le32(out, pos + 8, complete ? STATUS_COMPLETE : STATUS_COLLECTING);
                le32(out, pos + 12, 16 + 4 * words);
                pos += 16;
                for (int w = 0; w < words; w++) {
                    le32(out, pos, complete ? -1 : s.ackWord(w));
                    pos += 4;
                }
                records++;
            }
            if (quiet && any && allComplete) {
                pos = wholeRecord(out, pos, maxSeq + 1);
                records++;
                nextRequests++;
            }
            ackHeader(out, pos, records, qpara);
            return pos;
        }

        private static int wholeRecord(byte[] out, int pos, long seq) {
            le64(out, pos, seq);
            le32(out, pos + 8, STATUS_WHOLE);
            le32(out, pos + 12, 16);
            return pos + 16;
        }
    }

    /**
     * The fixed part of an acknowledgement: EF 02, length, 02 02 00 01, record count, then no
     * command (counter 0, length 0, 0x40 zero bytes), the quality parameters and active camera
     * 0. The vendor app and turbodrone put the current stick command in there, a neutral one
     * when idle; this app never does: a viewer must not be able to move a toy, and a neutral
     * command is still a command (it can hold a drone that the remote is flying).
     */
    static void ackHeader(byte[] out, int length, int records, byte[] qpara) {
        out[0] = (byte) 0xEF;
        out[1] = 0x02;
        le16(out, 2, length);
        out[4] = 0x02;
        out[5] = 0x02;
        out[6] = 0x00;
        out[7] = 0x01;
        le32(out, 0x08, records);
        le32(out, 0x0C, 0);
        le16(out, 0x10, 0);
        Arrays.fill(out, 0x12, 0x52, (byte) 0);
        System.arraycopy(qpara, 0, out, 0x52, 4);
        out[0x56] = 0;
        out[0x57] = 0;
    }

    // ====================== JPEG ======================

    /** The headers in front of a body: SOI, DQT, SOF0, DHT, SOS (605 bytes as configured). */
    static byte[] jpegHeader(int quality, int width, int height) {
        return jpegHeader(quality, width, height, DQT_ZIGZAG);
    }

    static byte[] jpegHeader(int quality, int width, int height, int dqt) {
        return JpegAssembler.headersSampled(LUMA_SAMPLING, width > 0 ? width : DEFAULT_WIDTH,
                height > 0 ? height : DEFAULT_HEIGHT, dqtTables(dqt, quality), 0,
                RESTART_INTERVAL);
    }

    /** Luma then chroma table, 64 bytes each, in the order DQT carries them for {@code dqt}. */
    static byte[] dqtTables(int dqt, int quality) {
        if (dqt == DQT_Q50) return JpegAssembler.ijgTables(50);
        if (dqt == DQT_RASTER) {
            // Q50 is the unscaled Annex K table; put each value at its raster position
            byte[] zz = JpegAssembler.ijgTables(50);
            byte[] out = new byte[128];
            for (int t = 0; t < 128; t += 64) {
                for (int k = 0; k < 64; k++) out[t + RASTER_OF_ZIGZAG[k]] = zz[t + k];
            }
            return out;
        }
        return JpegAssembler.ijgTables(quality >= 1 && quality <= 100 ? quality : DEFAULT_QUALITY);
    }

    /** For the stats and the log: which tables, e.g. "zigzag Q75" or "raster (unscaled)". */
    static String dqtLabel(int dqt, int quality) {
        if (dqt == DQT_ZIGZAG) {
            return "zigzag Q" + (quality >= 1 && quality <= 100 ? quality : DEFAULT_QUALITY);
        }
        return DQT_NAMES[dqt] + " (unscaled)";
    }

    // ====================== Subnet hint ======================

    /**
     * A hint when the slot cannot be right for the phone's networks (#187), or null: the
     * target is the phone itself, or the phone is on a network of another toy family than the
     * address. {@code local}: the phone's IPv4 addresses.
     */
    static String subnetHint(InetAddress target, List<InetAddress> local) {
        if (!(target instanceof Inet4Address) || local == null) return null;
        boolean own = local.contains(target);
        boolean on1 = false;
        boolean on169 = false;
        boolean on80 = false;
        for (InetAddress a : local) {
            on1 |= in24(a, 1);
            on169 |= in24(a, 169);
            on80 |= in24(a, 80);
        }
        StringBuilder parts = new StringBuilder();
        byte[] b = target.getAddress();
        if (own) {
            sentence(parts, target.getHostAddress() + " is this phone; the toy is usually "
                    + (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".1");
        }
        if (on1 && !on169 && (own || in24(target, 169))) {
            sentence(parts, "This network looks like a KY UFO RTSP toy: try"
                    + " rtsp://192.168.1.1:7070/webcam");
        }
        if (on169 && !on1 && in24(target, 1)) {
            sentence(parts, "This phone is on 192.168.169.x: the toy is usually"
                    + " flowufo://192.168.169.1");
        }
        if (on80 && !on169 && !on1) {
            sentence(parts, "This phone is on 192.168.80.x (HF_ Wi-Fi), a Jieli toy: try"
                    + " jieli://192.168.80.1");
        }
        return parts.length() > 0 ? parts.toString() : null;
    }

    static void sentence(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(". ");
        sb.append(s);
    }

    /** {@code a} is in 192.168.{@code third}.0/24. */
    static boolean in24(InetAddress a, int third) {
        if (!(a instanceof Inet4Address)) return false;
        byte[] b = a.getAddress();
        return (b[0] & 0xFF) == 192 && (b[1] & 0xFF) == 168 && (b[2] & 0xFF) == third;
    }

    /** The phone's IPv4 addresses on interfaces that are up. */
    static List<InetAddress> localAddresses() {
        List<InetAddress> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface n = e.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress ia : n.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) list.add(ia.getAddress());
                }
            }
        } catch (Exception ignored) {
            // No hint then
        }
        return list;
    }

    /**
     * Header, body and EOI into {@code out} (grown when too small, returned in out[0]);
     * returns the length. A body that is a whole JPEG already goes out as it is.
     */
    static int jpeg(byte[] header, byte[] body, int bodyLength, byte[][] out) {
        boolean whole = bodyLength >= 2 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8;
        boolean eoi = bodyLength >= 2 && (body[bodyLength - 2] & 0xFF) == 0xFF
                && (body[bodyLength - 1] & 0xFF) == 0xD9;
        int head = whole ? 0 : header.length;
        int total = head + bodyLength + (eoi ? 0 : 2);
        if (out[0] == null || out[0].length < total) out[0] = new byte[total + 64 * 1024];
        byte[] d = out[0];
        if (!whole) System.arraycopy(header, 0, d, 0, head);
        System.arraycopy(body, 0, d, head, bodyLength);
        if (!eoi) {
            d[total - 2] = (byte) 0xFF;
            d[total - 1] = (byte) 0xD9;
        }
        return total;
    }

    // ====================== Client ======================

    interface Listener {
        /** Probing (no fragments, or none for resetMs) or receiving; text for the status. */
        void onState(boolean receiving, String text);

        /** One whole JPEG in {@code buf}, valid until the call returns. */
        void onFrame(byte[] buf, int length);
    }

    /** Called with the socket before the first send: binds it to the drone's Wi-Fi. */
    interface SocketSetup {
        String prepare(DatagramSocket socket, InetAddress target);
    }

    /**
     * The receive loop on the calling thread: probes, fragments, acknowledgements and the
     * timers between them, until {@code keepGoing} fails or close(). Each probed port has a
     * socket of its own: the drone answers from source port 1234 whichever port was probed,
     * so only the socket the packets arrive on tells which engine answered. The first 0x93
     * packet from the toy's address locks the client onto that socket and port (acks go
     * there); the other socket is closed, and opened again when the drone goes quiet and the
     * probes start over.
     */
    static final class Client implements Closeable {
        private final String tag;
        private final Url url;
        private final SocketSetup setup;
        private final int[] ports;
        /** One socket per probed port; null once another port is locked. Guarded by itself. */
        private final DatagramSocket[] sockets;
        private volatile boolean closed;
        /** Index of the port the drone answers on, or -1 while it has not. */
        private volatile int locked = -1;
        /** The phone's addresses for the subnet hint; null: the real ones. For tests. */
        List<InetAddress> localForHint;

        final Window window = new Window();
        private final Fragment fragment = new Fragment();
        private final byte[] ack = new byte[ACK_CAPACITY];
        private final DatagramPacket out = new DatagramPacket(ack, 0);
        private final byte[][] jpegOut = new byte[1][];
        private byte[] header;
        /** Quality and size the header was made for. */
        private int headerQuality;
        private int headerWidth;
        private int headerHeight;

        // For the stats; written by the loop thread only
        private volatile boolean receiving;
        private volatile boolean playing;
        private volatile String network = "default";
        private volatile String hint;
        private volatile int probes;
        private volatile int acks;
        private volatile int packets;
        private volatile long bytes;
        private volatile int badPackets;
        private volatile int foreign;
        private volatile int resets;
        private volatile int sendErrors;
        private volatile String lastError;
        private volatile int services;
        private volatile String lastService;
        private volatile String firstHeader;
        private volatile String size;
        private volatile String dqtUsed;
        private volatile int mainCamera = -1;
        private volatile int flowCamera = -1;
        private int cameraChanges;
        private volatile float fragmentsPerS;
        private volatile float framesPerS;
        /** Any fragment from the drone, usable or not: it is still there. */
        private long lastFragmentAt;

        Client(String tag, Url url, SocketSetup setup) {
            this.tag = tag;
            this.url = url;
            this.setup = setup;
            ports = url.ports();
            sockets = new DatagramSocket[ports.length];
            window.qpara = url.qpara;
        }

        @Override
        public void close() {
            closed = true;
            synchronized (sockets) {
                for (DatagramSocket s : sockets) {
                    if (s != null) s.close();
                }
            }
        }

        /** BL618 (port 8801) probes every 200 ms, BL608 and others every 100 ms. */
        static long probeInterval(int port) {
            return port == BL618_PORT ? PROBE_MS_BL618 : PROBE_MS;
        }

        /** BL618 acknowledges every 50 ms and only with a record; BL608 every 25 ms. */
        static long ackInterval(int port) {
            return port == BL618_PORT ? ACK_MS_BL618 : ACK_MS;
        }

        static boolean ackNeedsRecord(int port) {
            return port == BL618_PORT;
        }

        static String engine(int port) {
            return port == DEFAULT_PORT ? "BL608" : port == BL618_PORT ? "BL618" : "port " + port;
        }

        /** The port the drone answers on, or -1. */
        int lockedPort() {
            int i = locked;
            return i >= 0 ? ports[i] : -1;
        }

        String probingText(boolean again) {
            String t = (again ? "The toy went quiet: probing " : "Waiting for the toy: probing ")
                    + url.probed() + (again ? " again" : "");
            String h = hint;
            return h != null ? t + ". " + h : t;
        }

        private void open(int i, InetAddress target) throws IOException {
            DatagramSocket s = new DatagramSocket();
            try {
                s.setReceiveBufferSize(1024 * 1024);
            } catch (IOException ignored) {}
            if (setup != null) network = setup.prepare(s, target);
            synchronized (sockets) {
                if (sockets[i] != null) sockets[i].close();
                sockets[i] = s;
                if (closed) s.close();
            }
        }

        private void closeSocket(int i) {
            synchronized (sockets) {
                if (sockets[i] != null) sockets[i].close();
                sockets[i] = null;
            }
        }

        private DatagramSocket socket(int i) {
            synchronized (sockets) {
                return sockets[i];
            }
        }

        private String localPorts() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ports.length; i++) {
                DatagramSocket s = socket(i);
                if (s == null) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(s.getLocalPort()).append(" for ").append(ports[i]);
            }
            return sb.toString();
        }

        void run(UdpReceiver.Condition keepGoing, Listener listener) throws IOException {
            InetAddress address = InetAddress.getByName(url.host);
            hint = subnetHint(address, localForHint != null ? localForHint : localAddresses());
            if (hint != null) Log.w(TAG, tag + ": FLOW-UFO: " + hint);
            for (int i = 0; i < ports.length; i++) open(i, address);
            Log.i(TAG, tag + ": FLOW-UFO: probing " + url.probed() + " from local port "
                    + localPorts() + " (network: " + network + "), qpara "
                    + hex(url.qpara, 0, 4) + ", dqt " + DQT_NAMES[url.dqt]);
            listener.onState(false, probingText(false));
            byte[] rx = new byte[2048];
            DatagramPacket in = new DatagramPacket(rx, rx.length);
            long[] nextProbe = new long[ports.length];
            long nextAck = 0;
            long rateMark = now();
            int rateFragments = 0;
            int rateFrames = 0;
            long nextSummary = rateMark + 30_000;
            // Start of the current run of failed sends, or 0
            long failingSince = 0;
            try {
                while (!closed && keepGoing.holds()) {
                    long t = now();
                    if (t - rateMark >= 1000) {
                        float sec = (t - rateMark) / 1000f;
                        fragmentsPerS = (window.fragments - rateFragments) / sec;
                        framesPerS = (window.delivered - rateFrames) / sec;
                        rateFragments = window.fragments;
                        rateFrames = window.delivered;
                        rateMark = t;
                    }
                    if (receiving && t - lastFragmentAt > resetMs) {
                        Log.w(TAG, tag + ": FLOW-UFO: no fragment for " + resetMs
                                + " ms, probing again (" + summary() + ")");
                        probeAgain(listener, address);
                        Arrays.fill(nextProbe, t);
                    }
                    if (receiving && t >= nextSummary) {
                        nextSummary = t + 30_000;
                        Log.i(TAG, tag + ": FLOW-UFO: " + summary());
                    }
                    boolean sendFailed = false;
                    int lk = locked;
                    if (!receiving) {
                        for (int i = 0; i < ports.length; i++) {
                            DatagramSocket s = socket(i);
                            if (s == null || t < nextProbe[i]) continue;
                            nextProbe[i] = t + probeInterval(ports[i]);
                            if (send(s, PROBE, PROBE.length, address, ports[i])) {
                                probes++;
                            } else {
                                sendFailed = true;
                            }
                        }
                    } else if (lk >= 0 && t >= nextAck) {
                        // Behind schedule (a slow thread) is caught up, not sent in a burst
                        int port = ports[lk];
                        nextAck = Math.max(nextAck + ackInterval(port), t);
                        int len = window.buildAck(ack, t);
                        if (len > ACK_HEADER || !ackNeedsRecord(port)) {
                            if (send(socket(lk), ack, len, address, port)) {
                                acks++;
                            } else {
                                sendFailed = true;
                            }
                        }
                    }
                    if (sendFailed) {
                        // Off the toy's Wi-Fi, or its network went away: new sockets after
                        // 3 s of failures, bound again to whatever network reaches it now. The
                        // drone sends to the port of the probe: the new ports probe at once
                        // (Qodo on #188)
                        if (failingSince == 0) failingSince = t;
                        if (t - failingSince >= 3000) {
                            failingSince = 0;
                            if (receiving) probeAgain(listener, address);
                            locked = -1;
                            for (int i = 0; i < ports.length; i++) open(i, address);
                            Log.w(TAG, tag + ": FLOW-UFO: new sockets after send errors, local"
                                    + " port " + localPorts() + " (network: " + network + ")");
                            Arrays.fill(nextProbe, t);
                        }
                    } else {
                        failingSince = 0;
                    }
                    long wake = Long.MAX_VALUE;
                    if (receiving) {
                        wake = nextAck;
                    } else {
                        for (int i = 0; i < ports.length; i++) {
                            if (socket(i) != null) wake = Math.min(wake, nextProbe[i]);
                        }
                    }
                    int timeout = (int) Math.max(1, Math.min(100, wake - now()));
                    int got = -1;
                    lk = locked;
                    if (lk >= 0) {
                        if (receive(socket(lk), in, rx, timeout)) got = lk;
                    } else {
                        // Not locked yet: each socket in turn; what is not read waits in its
                        // buffer for the next turn
                        int each = Math.max(1, timeout / ports.length);
                        for (int i = 0; i < ports.length && got < 0; i++) {
                            DatagramSocket s = socket(i);
                            if (s != null && receive(s, in, rx, each)) got = i;
                        }
                    }
                    if (got < 0) continue;
                    // By source address only: the drone sends from port 1234, not the probed one
                    if (!address.equals(in.getAddress())) {
                        if (++foreign <= 3) {
                            Log.w(TAG, tag + ": FLOW-UFO: packet from " + in.getAddress()
                                    + ", not the toy: ignored");
                        }
                        continue;
                    }
                    if (locked < 0 && packetType(rx, 0, in.getLength()) >= 0) lock(got, in);
                    handle(rx, in.getLength(), listener);
                }
            } catch (IOException e) {
                if (closed) return;
                throw e;
            } finally {
                close();
            }
        }

        private boolean receive(DatagramSocket s, DatagramPacket in, byte[] rx, int timeout)
                throws IOException {
            if (s == null) return false;
            try {
                s.setSoTimeout(timeout);
                in.setData(rx);
                s.receive(in);
                return true;
            } catch (SocketTimeoutException e) {
                return false;
            }
        }

        /** The drone answered on ports[i]: acks go there, the other probes stop. */
        private void lock(int i, DatagramPacket from) {
            locked = i;
            for (int j = 0; j < ports.length; j++) {
                if (j != i) closeSocket(j);
            }
            Log.i(TAG, tag + ": FLOW-UFO: the toy answers the probe to port " + ports[i] + " ("
                    + engine(ports[i]) + "), from port " + from.getPort()
                    + (ports.length > 1 ? "; the other port is not probed any more" : ""));
        }

        /** Back to probing on every port: the stream is gone. */
        private void probeAgain(Listener listener, InetAddress address) throws IOException {
            resets++;
            receiving = false;
            playing = false;
            window.reset();
            locked = -1;
            for (int i = 0; i < ports.length; i++) {
                if (socket(i) == null) open(i, address);
            }
            listener.onState(false, probingText(true));
        }

        private boolean send(DatagramSocket s, byte[] data, int len, InetAddress a, int port) {
            if (s == null) return false;
            try {
                out.setData(data, 0, len);
                out.setAddress(a);
                out.setPort(port);
                s.send(out);
                return true;
            } catch (IOException e) {
                sendErrors++;
                lastError = e.getMessage();
                if (sendErrors <= 3 || sendErrors % 100 == 0) {
                    Log.w(TAG, tag + ": FLOW-UFO: cannot send to " + url.endpoint(port) + " ("
                            + sendErrors + "): " + e.getMessage()
                            + "; is this device on the toy's Wi-Fi?");
                }
                return false;
            }
        }

        private void handle(byte[] b, int len, Listener listener) {
            packets++;
            bytes += len;
            int type = packetType(b, 0, len);
            if (type == TYPE_SERVICE) {
                // Logged only: the vendor app answers some of them with commands, we do not
                services++;
                lastService = hex(b, 0, Math.min(len, 64));
                if (services <= 5 || services % 100 == 0) {
                    Log.i(TAG, tag + ": FLOW-UFO: service message (" + services + "): "
                            + lastService);
                }
                return;
            }
            if (type != TYPE_FRAGMENT || !fragment.parse(b, 0, len)) {
                badPackets++;
                if (badPackets <= 5 || badPackets % 500 == 0) {
                    Log.w(TAG, tag + ": FLOW-UFO: unknown or malformed packet (" + badPackets
                            + ", " + len + " bytes): " + hex(b, 0, Math.min(len, 64)));
                }
                return;
            }
            long t = now();
            lastFragmentAt = t;
            if (!receiving) {
                receiving = true;
                if (firstHeader == null) firstHeader = hex(b, 0, FRAGMENT_HEADER);
                Log.i(TAG, tag + ": FLOW-UFO: first fragment after " + probes + " probes: "
                        + hex(b, 0, FRAGMENT_HEADER));
                listener.onState(true, "Receiving from the toy at "
                        + url.endpoint(lockedPort() > 0 ? lockedPort() : url.port) + "…");
            }
            int n = window.fragments;
            if (n < 5) {
                Log.i(TAG, tag + ": FLOW-UFO: fragment seq " + fragment.seq + " base "
                        + fragment.base + " " + fragment.index + "/" + fragment.total + ", body "
                        + fragment.bodyLength + ", " + fragment.width + "x" + fragment.height
                        + " Q" + fragment.quality + ", bytes 0x08-0x17: " + hex(b, 0x08, 16));
            }
            if (fragment.mainCamera != mainCamera || fragment.flowCamera != flowCamera) {
                boolean first = mainCamera < 0;
                mainCamera = fragment.mainCamera;
                flowCamera = fragment.flowCamera;
                if (++cameraChanges <= 5) {
                    Log.i(TAG, tag + ": FLOW-UFO: camera status (0x34/0x35): main " + mainCamera
                            + ", flow " + flowCamera + (first ? "" : " (changed)"));
                }
            }
            Slot done = window.add(fragment, t);
            if (done == null) return;
            if (header == null || done.quality != headerQuality || done.width != headerWidth
                    || done.height != headerHeight) {
                header = jpegHeader(done.quality, done.width, done.height, url.dqt);
                headerQuality = done.quality;
                headerWidth = done.width;
                headerHeight = done.height;
                size = done.width + "x" + done.height;
                dqtUsed = dqtLabel(url.dqt, done.quality);
                Log.i(TAG, tag + ": FLOW-UFO: frames " + size + " Q" + done.quality
                        + (done.quality >= 1 && done.quality <= 100 ? ""
                                : " (not a quality)") + ", tables " + dqtUsed);
            }
            int length = jpeg(header, done.body, done.bodyLength, jpegOut);
            if (!playing) {
                playing = true;
                Log.i(TAG, tag + ": FLOW-UFO: first frame, seq " + done.seq + ", "
                        + done.total + " fragments, body " + done.bodyLength + " bytes: "
                        + hex(done.body, 0, Math.min(32, done.bodyLength)));
            }
            listener.onFrame(jpegOut[0], length);
        }

        private String summary() {
            Window w = window;
            return String.format(Locale.ROOT, "%.1f fps, %.0f fragments/s, %d frames, %d lost,"
                            + " %d late, %d lagging left out, %d next requests, %d acks, %d bad",
                    framesPerS, fragmentsPerS, w.delivered, w.lost, w.late, w.lagging,
                    w.nextRequests, acks, badPackets);
        }

        /** For /stats.json. */
        Map<String, Object> stats() {
            Window w = window;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", playing ? "playing" : receiving ? "receiving" : "probing");
            m.put("target", url.probed());
            int lp = lockedPort();
            if (lp > 0) {
                m.put("port", lp);
                m.put("engine", engine(lp));
            }
            m.put("network", network);
            if (hint != null) m.put("hint", hint);
            m.put("qpara", hex(url.qpara, 0, 4));
            m.put("dqt", dqtUsed != null ? dqtUsed : DQT_NAMES[url.dqt]);
            m.put("frames_per_s", Math.round(framesPerS * 10) / 10f);
            m.put("fragments_per_s", Math.round(fragmentsPerS));
            if (size != null) m.put("size", size);
            if (size != null) m.put("quality", headerQuality);
            if (mainCamera >= 0) {
                Map<String, Object> cam = new LinkedHashMap<>();
                cam.put("main", mainCamera);
                cam.put("flow", flowCamera);
                m.put("camera_status", cam);
            }
            m.put("probes", probes);
            m.put("acks", acks);
            m.put("packets", packets);
            m.put("bytes", bytes);
            m.put("fragments", w.fragments);
            m.put("duplicates", w.duplicates);
            m.put("stale", w.stale);
            m.put("mismatched", w.mismatched);
            m.put("short_fragments", w.shortFragments);
            m.put("bad_packets", badPackets);
            m.put("frames", w.delivered);
            m.put("frames_complete", w.completed);
            m.put("frames_lost", w.lost);
            m.put("frames_late", w.late);
            m.put("lagging_left_out", w.lagging);
            m.put("next_requests", w.nextRequests);
            m.put("resets", resets);
            m.put("seq_restarts", w.restarts);
            m.put("seq", w.maxSeq);
            m.put("base", w.base);
            m.put("service_messages", services);
            if (lastService != null) m.put("last_service", lastService);
            if (firstHeader != null) m.put("first_fragment_header", firstHeader);
            if (foreign > 0) m.put("foreign_packets", foreign);
            if (sendErrors > 0) {
                m.put("send_errors", sendErrors);
                m.put("last_error", lastError);
            }
            return m;
        }
    }

    // ====================== Bytes ======================

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%02X", b[off + i] & 0xFF));
        }
        return sb.toString();
    }

    static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    static long u32(byte[] b, int off) {
        return (u16(b, off) | (long) u16(b, off + 2) << 16) & 0xFFFFFFFFL;
    }

    static void le16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    static void le32(byte[] b, int off, int v) {
        le16(b, off, v);
        le16(b, off + 2, v >> 16);
    }

    static void le64(byte[] b, int off, long v) {
        le32(b, off, (int) v);
        le32(b, off + 4, (int) (v >>> 32));
    }
}
