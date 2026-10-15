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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Cheap Wi-Fi toy drones with a "FLOW-UFO-xxxxxx" access point (a BL60x camera) send MJPEG
 * over UDP from 192.168.169.1:8800 in a protocol of their own (#187). A short probe wakes the
 * drone; it then sends every JPEG frame, cut into fragments of 1024 bytes, to the address the
 * probe came from, and the client acknowledges the last four frames every 25 ms: what arrived,
 * what is missing, and which frame to send whole. A frame carries only the entropy-coded data
 * of the scan: the client puts the JPEG headers in front (standard tables at the quality the
 * fragment names). Video only: flight commands are never sent. Written from a description of
 * the client side and not yet checked against a drone: what is a guess says so, and the log
 * shows the raw bytes that would settle it. Pure Java apart from the log.
 */
final class FlowUfo {
    private static final String TAG = "OpenIPCDecoder";

    static final String SCHEME = "flowufo://";
    static final String DEFAULT_HOST = "192.168.169.1";
    static final int DEFAULT_PORT = 8800;

    /** Wakes the drone up; sent every PROBE_MS until the first fragment. */
    static final byte[] PROBE = {(byte) 0xEF, 0x00, 0x04, 0x00};
    static final long PROBE_MS = 100;
    static final long ACK_MS = 25;
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
    /** Quality parameters the vendor app sends by default (25, 50, 35, 75). */
    static final byte[] QPARA = {0x19, 0x32, 0x23, 0x4B};
    static final int SLOTS = 4;
    /** A slot this many frames behind the newest asks for its next frame whole. */
    static final int LAG = 5;
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

    private FlowUfo() {}

    // ====================== URL ======================

    /** flowufo://[host][:port][/...]: the drone's address, 192.168.169.1:8800 when left out. */
    static final class Url {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

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
            return u;
        }

        String endpoint() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
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
        /** 01 01 at 0x34: the camera family whose layout this is. */
        boolean gl;
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
            gl = b[off + 0x34] == 1 && b[off + 0x35] == 1;
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
        long lastFragmentMs;
        volatile int fragments;
        volatile int duplicates;
        volatile int stale;
        volatile int mismatched;
        volatile int completed;
        volatile int delivered;
        volatile int lost;
        volatile int late;
        volatile int wholeRequests;
        volatile int nextRequests;
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
        }

        static int slotOf(long seq) {
            return (int) ((seq - 1) & (SLOTS - 1));
        }

        /** Adds a fragment; returns its slot when the fragment completed a frame to show. */
        Slot add(Fragment f, long nowMs) {
            lastFragmentMs = nowMs;
            // Far behind the newest frame: the drone counts from 1 again
            if (maxSeq > 64 && f.seq + 64 < maxSeq) {
                restarts++;
                reset();
            }
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
            current = f.seq;
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
            int n = Math.min(f.dataLength, s.total * FRAGMENT_DATA - at);
            System.arraycopy(f.buf, f.dataOffset, s.body, at, n);
            s.mask[f.index >> 5] |= 1 << (f.index & 31);
            s.received++;
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
         * fragments received; a slot LAG or more frames behind the newest asks for its next
         * frame whole instead (3, no mask). The frame coming in now is left out until the
         * fragments pause; after QUIET_MS without any, when every frame is complete, a record
         * asks for the frame after the newest. Unverified: whether the drone needs or uses any
         * of it, the record order, and that the paused frame is included again.
         */
        int buildAck(byte[] out, long nowMs) {
            boolean quiet = nowMs - lastFragmentMs > QUIET_MS;
            int pos = ACK_HEADER;
            int records = 0;
            boolean any = false;
            boolean allComplete = true;
            for (Slot s : slots) {
                if (s.seq == 0) continue;
                any = true;
                if (s.status != STATUS_COMPLETE) allComplete = false;
                if (s.seq == current && !quiet) continue;
                if (s.seq <= base) continue;
                if (maxSeq - s.seq >= LAG) {
                    pos = wholeRecord(out, pos, s.seq + SLOTS);
                    records++;
                    wholeRequests++;
                    continue;
                }
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
            ackHeader(out, pos, records);
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
     * command (counter 0, length 0, 0x40 zero bytes), QPARA and active camera 0.
     */
    static void ackHeader(byte[] out, int length, int records) {
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
        System.arraycopy(QPARA, 0, out, 0x52, QPARA.length);
        out[0x56] = 0;
        out[0x57] = 0;
    }

    // ====================== JPEG ======================

    /** The headers in front of a body: SOI, DQT, SOF0, DHT, SOS (605 bytes as configured). */
    static byte[] jpegHeader(int quality, int width, int height) {
        int q = quality >= 1 && quality <= 100 ? quality : DEFAULT_QUALITY;
        return JpegAssembler.headersSampled(LUMA_SAMPLING, width > 0 ? width : DEFAULT_WIDTH,
                height > 0 ? height : DEFAULT_HEIGHT, JpegAssembler.ijgTables(q), 0,
                RESTART_INTERVAL);
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
     * timers between them, until {@code keepGoing} fails or close().
     */
    static final class Client implements Closeable {
        private final String tag;
        private final Url url;
        private final SocketSetup setup;
        private volatile DatagramSocket socket;
        private volatile boolean closed;

        final Window window = new Window();
        private final Fragment fragment = new Fragment();
        private final byte[] ack = new byte[ACK_CAPACITY];
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
        private volatile int quality;
        private volatile boolean gl = true;
        private volatile float fragmentsPerS;
        private volatile float framesPerS;

        Client(String tag, Url url, SocketSetup setup) {
            this.tag = tag;
            this.url = url;
            this.setup = setup;
        }

        @Override
        public void close() {
            closed = true;
            DatagramSocket s = socket;
            if (s != null) s.close();
        }

        String probingText(boolean again) {
            return (again ? "The toy went quiet: probing " : "Waiting for the toy: probing ")
                    + url.endpoint() + (again ? " again" : "");
        }

        private DatagramSocket open(InetAddress target) throws IOException {
            DatagramSocket s = new DatagramSocket();
            try {
                s.setReceiveBufferSize(1024 * 1024);
            } catch (IOException ignored) {}
            if (setup != null) network = setup.prepare(s, target);
            socket = s;
            if (closed) s.close();
            return s;
        }

        void run(UdpReceiver.Condition keepGoing, Listener listener) throws IOException {
            InetAddress address = InetAddress.getByName(url.host);
            InetSocketAddress target = new InetSocketAddress(address, url.port);
            DatagramSocket s = open(address);
            Log.i(TAG, tag + ": FLOW-UFO: probing " + url.endpoint() + " from local port "
                    + s.getLocalPort() + " (network: " + network + ")");
            listener.onState(false, probingText(false));
            byte[] rx = new byte[2048];
            DatagramPacket in = new DatagramPacket(rx, rx.length);
            DatagramPacket probe = new DatagramPacket(PROBE, PROBE.length, target);
            DatagramPacket out = new DatagramPacket(ack, 0, target);
            long nextProbe = 0;
            long nextAck = 0;
            long rateMark = now();
            int rateFragments = 0;
            int rateFrames = 0;
            long nextSummary = rateMark + 30_000;
            int failedSends = 0;
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
                    if (receiving && t - window.lastFragmentMs > resetMs) {
                        resets++;
                        receiving = false;
                        playing = false;
                        window.reset();
                        nextProbe = t;
                        Log.w(TAG, tag + ": FLOW-UFO: no fragment for " + resetMs
                                + " ms, probing again (" + summary() + ")");
                        listener.onState(false, probingText(true));
                    }
                    if (receiving && t >= nextSummary) {
                        nextSummary = t + 30_000;
                        Log.i(TAG, tag + ": FLOW-UFO: " + summary());
                    }
                    boolean sendFailed = false;
                    if (!receiving && t >= nextProbe) {
                        nextProbe = t + PROBE_MS;
                        sendFailed = !send(s, probe);
                        if (!sendFailed) probes++;
                    } else if (receiving && t >= nextAck) {
                        // Behind schedule (a slow thread) is caught up, not sent in a burst
                        nextAck = Math.max(nextAck + ACK_MS, t);
                        int len = window.buildAck(ack, t);
                        out.setLength(len);
                        sendFailed = !send(s, out);
                        if (!sendFailed) acks++;
                    }
                    if (sendFailed) {
                        // Off the toy's Wi-Fi, or its network went away: a new socket after
                        // 3 s of failures, bound again to whatever network reaches it now
                        if (++failedSends >= 30) {
                            failedSends = 0;
                            s.close();
                            s = open(address);
                        }
                    } else {
                        failedSends = 0;
                    }
                    long wake = receiving ? nextAck : nextProbe;
                    int timeout = (int) Math.max(1, Math.min(100, wake - now()));
                    try {
                        s.setSoTimeout(timeout);
                        in.setLength(rx.length);
                        s.receive(in);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    if (!in.getAddress().equals(address)) {
                        if (++foreign <= 3) {
                            Log.w(TAG, tag + ": FLOW-UFO: packet from " + in.getAddress()
                                    + ", not the toy: ignored");
                        }
                        continue;
                    }
                    handle(rx, in.getLength(), listener);
                }
            } catch (IOException e) {
                if (closed) return;
                throw e;
            } finally {
                s.close();
            }
        }

        private boolean send(DatagramSocket s, DatagramPacket p) {
            try {
                s.send(p);
                return true;
            } catch (IOException e) {
                sendErrors++;
                lastError = e.getMessage();
                if (sendErrors <= 3 || sendErrors % 100 == 0) {
                    Log.w(TAG, tag + ": FLOW-UFO: cannot send to " + url.endpoint() + " ("
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
            if (!receiving) {
                receiving = true;
                if (firstHeader == null) firstHeader = hex(b, 0, FRAGMENT_HEADER);
                Log.i(TAG, tag + ": FLOW-UFO: first fragment after " + probes + " probes: "
                        + hex(b, 0, FRAGMENT_HEADER));
                listener.onState(true, "Receiving from the toy at " + url.endpoint() + "…");
            }
            int n = window.fragments;
            if (n < 5) {
                Log.i(TAG, tag + ": FLOW-UFO: fragment seq " + fragment.seq + " base "
                        + fragment.base + " " + fragment.index + "/" + fragment.total + ", body "
                        + fragment.bodyLength + ", " + fragment.width + "x" + fragment.height
                        + " Q" + fragment.quality + ", bytes 0x08-0x17: " + hex(b, 0x08, 16));
            }
            if (!fragment.gl && gl) {
                gl = false;
                Log.w(TAG, tag + ": FLOW-UFO: bytes 0x34/0x35 are " + hex(b, 0x34, 2)
                        + ", not 01 01: another camera family, frames may not decode");
            }
            Slot done = window.add(fragment, t);
            if (done == null) return;
            if (header == null || done.quality != headerQuality || done.width != headerWidth
                    || done.height != headerHeight) {
                header = jpegHeader(done.quality, done.width, done.height);
                headerQuality = done.quality;
                headerWidth = done.width;
                headerHeight = done.height;
                quality = done.quality;
                size = done.width + "x" + done.height;
                Log.i(TAG, tag + ": FLOW-UFO: frames " + size + " Q" + done.quality
                        + (done.quality >= 1 && done.quality <= 100 ? ""
                                : " (not a quality, Q" + DEFAULT_QUALITY + " tables)"));
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
                            + " %d late, %d whole requests, %d next requests, %d acks, %d bad",
                    framesPerS, fragmentsPerS, w.delivered, w.lost, w.late, w.wholeRequests,
                    w.nextRequests, acks, badPackets);
        }

        /** For /stats.json. */
        Map<String, Object> stats() {
            Window w = window;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", playing ? "playing" : receiving ? "receiving" : "probing");
            m.put("target", url.endpoint());
            m.put("network", network);
            m.put("frames_per_s", Math.round(framesPerS * 10) / 10f);
            m.put("fragments_per_s", Math.round(fragmentsPerS));
            if (size != null) m.put("size", size);
            if (size != null) m.put("quality", quality);
            m.put("gl", gl);
            m.put("probes", probes);
            m.put("acks", acks);
            m.put("packets", packets);
            m.put("bytes", bytes);
            m.put("fragments", w.fragments);
            m.put("duplicates", w.duplicates);
            m.put("stale", w.stale);
            m.put("mismatched", w.mismatched);
            m.put("bad_packets", badPackets);
            m.put("frames", w.delivered);
            m.put("frames_complete", w.completed);
            m.put("frames_lost", w.lost);
            m.put("frames_late", w.late);
            m.put("whole_requests", w.wholeRequests);
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
