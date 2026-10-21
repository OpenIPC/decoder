/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtpListener.java — plain RTP pushed to us over UDP, unicast or multicast, without RTSP
 *
 */

package com.openipc.decoder;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Receives the stream that Majestic sends to "outgoing" destinations (udp://host:port):
 * RTP with payload type 96 (H.264) or 97 (H.265) and the parameter sets repeated in band
 * before every IDR, so it can be joined at any time without an SDP (#68).
 * A MulticastSocket is used for unicast too: joining a group from a DatagramChannel needs
 * API 24.
 * <p>
 * An app cannot get a socket buffer above {@code rmem_max} (256 KiB on many boxes), which is
 * about one 1080p key frame, so a thread of its own does nothing but read the socket into a
 * ring; reordering, assembly and the decoder never hold up the receive (#251).
 */
class RtpListener implements Closeable {
    private static final String TAG = "OpenIPCDecoder";
    private static final int RCVBUF = 2 * 1024 * 1024;
    /** Datagrams held between the receive thread and the rest: a few key frames. */
    static final int RING = 1024;
    /** Size of a ring slot; a longer datagram gets a slot of its own, not kept (Qodo on #252). */
    private static final int SLOT = 1600;
    /** Datagrams handled before the stop condition and the reorder deadlines again. */
    private static final int BATCH = 64;

    interface Handler {
        void onPacket(byte[] buf, int offset, int length);
    }

    /**
     * udp://[source@][address]:port[?codec=h264|h265][&pt=N], as VLC writes it; rtp:// is
     * accepted too. An empty or local address listens on that port, a multicast address
     * joins the group, and a source accepts datagrams from that sender only. Video is
     * payload type 96 or 97 unless pt= names another; codec= overrides the codec.
     */
    static final class Url {
        /** Multicast group or local address to bind; null for every local address. */
        InetAddress address;
        int port;
        /** Only sender accepted, or null. */
        InetAddress source;
        /** Codec forced by the URL: null (by payload type), "H264" or "H265". */
        String codec;
        /** Video payload type forced by the URL, or -1. */
        int pt = -1;

        boolean multicast() { return address != null && address.isMulticastAddress(); }

        static boolean matches(String url) {
            if (url == null) return false;
            String u = url.trim().toLowerCase(Locale.ROOT);
            return u.startsWith("udp://") || u.startsWith("rtp://");
        }

        static Url parse(String url) throws IOException {
            if (!matches(url)) throw new IOException("Not a udp:// or rtp:// URL");
            String s = url.trim();
            s = s.substring(s.indexOf("://") + 3);
            String query = "";
            int q = s.indexOf('?');
            if (q >= 0) {
                query = s.substring(q + 1);
                s = s.substring(0, q);
            }
            if (s.endsWith("/")) s = s.substring(0, s.length() - 1);

            Url u = new Url();
            int at = s.lastIndexOf('@');
            if (at >= 0) {
                String src = s.substring(0, at);
                if (!src.isEmpty()) u.source = literal(stripBrackets(src), "source");
                s = s.substring(at + 1);
            }
            String host;
            String port;
            if (s.startsWith("[")) {
                int close = s.indexOf(']');
                if (close < 0 || close + 1 >= s.length() || s.charAt(close + 1) != ':') {
                    throw new IOException("Invalid address in " + s);
                }
                host = s.substring(1, close);
                port = s.substring(close + 2);
            } else {
                int colon = s.lastIndexOf(':');
                if (colon < 0) throw new IOException("A port is required, e.g. udp://@:5600");
                host = s.substring(0, colon);
                port = s.substring(colon + 1);
            }
            try {
                u.port = Integer.parseInt(port);
            } catch (NumberFormatException e) {
                u.port = -1;
            }
            if (u.port <= 0 || u.port > 65535) throw new IOException("Invalid port: " + port);
            if (!host.isEmpty()) u.address = literal(host, "address");

            for (String param : query.split("&")) {
                int eq = param.indexOf('=');
                if (eq < 0) continue;
                String name = param.substring(0, eq).toLowerCase(Locale.ROOT);
                String value = param.substring(eq + 1).toLowerCase(Locale.ROOT);
                if (name.equals("codec")) {
                    if (value.equals("h264") || value.equals("avc")) {
                        u.codec = "H264";
                    } else if (value.equals("h265") || value.equals("hevc")) {
                        u.codec = "H265";
                    } else {
                        throw new IOException("Unknown codec: " + value);
                    }
                } else if (name.equals("pt")) {
                    try {
                        u.pt = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {}
                    if (u.pt < 0 || u.pt > 127) throw new IOException("Invalid pt: " + value);
                }
            }
            return u;
        }

        private static String stripBrackets(String s) {
            return s.startsWith("[") && s.endsWith("]") ? s.substring(1, s.length() - 1) : s;
        }

        /** Numeric addresses only: no name lookup on a slot URL. */
        private static InetAddress literal(String s, String what) throws IOException {
            if (!s.matches("[0-9.]+|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*")) {
                throw new IOException("The " + what + " must be an IP address: " + s);
            }
            return InetAddress.getByName(s);
        }

        /** Codec of a stream with payload type {@code pt}: Majestic sends 96 = H.264, 97 = H.265. */
        boolean h265(int pt) {
            if (codec != null) return codec.equals("H265");
            return pt == Sdp.PT_H265;
        }
    }

    private final String tag;
    private final Url url;
    private final MulticastSocket socket;
    /**
     * One reorder buffer per payload type: audio sharing the port has its own sequence
     * numbers, which must not push video packets out as late ones.
     */
    private final RtpReorder[] reorders = new RtpReorder[128];
    private final ArrayBlockingQueue<byte[]> free = new ArrayBlockingQueue<>(RING);
    private final ArrayBlockingQueue<byte[]> filled = new ArrayBlockingQueue<>(RING);
    private volatile boolean closed;
    private boolean joined;
    private long rejected;
    /** The buffer the kernel actually gave, as getReceiveBufferSize() says. */
    private final int rcvbuf;
    private volatile long received;
    /** Datagrams thrown away because the ring was full. */
    private volatile long ringDropped;
    private volatile IOException readError;
    /**
     * Published by the session thread for /stats.json (Qodo on #252): the video payload type
     * (-1 until known), the gaps in its sequence, the kernel's drop count (-1: unreadable).
     */
    private volatile int videoPt;
    /** Every payload type that was video in this listener: losses before a codec change stay. */
    private final boolean[] videoTypes = new boolean[128];
    private volatile long rtpLost;
    private volatile long socketDrops = -1;
    private boolean dropsReadable = true;
    private long dropsReadNs;

    RtpListener(String tag, Url url) throws IOException {
        this.tag = tag;
        this.url = url;
        this.videoPt = url.pt;
        MulticastSocket s = new MulticastSocket(null);
        try {
            if (url.multicast()) {
                // Several receivers of one group on this device each get a copy. Bound to
                // the group, not the wildcard: on Linux a wildcard socket also receives every
                // other group joined on this port, e.g. another channel of the same NVR
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(url.address, url.port));
                s.joinGroup(url.address);
                joined = true;
            } else {
                // Unicast: a second slot on the same port fails here instead of starving
                s.setReuseAddress(false);
                s.bind(url.address != null
                        ? new InetSocketAddress(url.address, url.port)
                        : new InetSocketAddress(url.port));
            }
            s.setReceiveBufferSize(RCVBUF);
            s.setSoTimeout(250);
        } catch (IOException e) {
            s.close();
            throw e;
        }
        this.socket = s;
        int got;
        try {
            got = s.getReceiveBufferSize();
        } catch (IOException e) {
            got = -1;
        }
        rcvbuf = got;
        Log.i(TAG, tag + ": UDP port " + url.port + ", receive buffer " + got + " bytes (asked "
                + RCVBUF + ")");
        for (int i = 0; i < RING; i++) free.add(new byte[SLOT + 4]);
        if (joined) MulticastLock.acquire();
    }

    /**
     * Reads the socket until it is closed. A slot is a datagram with its length in the first
     * four bytes, copied from one large buffer: datagrams are short, slots stay small.
     */
    private void readLoop() {
        byte[] buf = new byte[65536];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        InetAddress source = url.source;
        try {
            while (!closed) {
                try {
                    p.setLength(buf.length);
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (source != null && !source.equals(p.getAddress())) {
                    if (rejected++ % 100 == 0) {
                        Log.w(TAG, tag + ": UDP from an unexpected sender ignored ("
                                + rejected + ")");
                    }
                    continue;
                }
                int len = p.getLength();
                if (len < 12) continue;
                received++;
                byte[] slot = free.poll();
                if (slot == null) {
                    if (ringDropped++ % 100 == 0) {
                        Log.w(TAG, tag + ": UDP ring full, datagram dropped (" + ringDropped + ")");
                    }
                    continue;
                }
                if (slot.length < len + 4) slot = new byte[len + 4];
                slot[0] = (byte) (len >>> 24);
                slot[1] = (byte) (len >>> 16);
                slot[2] = (byte) (len >>> 8);
                slot[3] = (byte) len;
                System.arraycopy(buf, p.getOffset(), slot, 4, len);
                filled.offer(slot);
            }
        } catch (IOException e) {
            if (!closed) readError = e;
        }
    }

    /** Receive until {@code keepGoing} turns false or {@link #close} is called. */
    void run(UdpReceiver.Condition keepGoing, Handler handler) throws IOException {
        RtpReorder.Sink sink = handler::onPacket;
        Thread reader = new Thread(this::readLoop, tag + "-udp");
        reader.setDaemon(true);
        // Above the decoder and the UI: a stalled receive is a lost packet
        reader.setPriority(Thread.MAX_PRIORITY);
        reader.start();
        try {
            while (!closed && keepGoing.holds()) {
                IOException e = readError;
                if (e != null) throw e;
                long now = System.nanoTime();
                long ms = 250;
                for (RtpReorder r : reorders) {
                    long d = r != null ? r.msUntilDeadline(now) : -1;
                    if (d >= 0) ms = Math.min(ms, d);
                }
                byte[] slot;
                try {
                    slot = filled.poll(Math.max(1, ms), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // A batch of what is waiting, then the condition and the reorder deadlines
                for (int n = 0; slot != null; n++) {
                    int len = ((slot[0] & 0xFF) << 24) | ((slot[1] & 0xFF) << 16)
                            | ((slot[2] & 0xFF) << 8) | (slot[3] & 0xFF);
                    int pt = slot[5] & 0x7F;
                    RtpReorder r = reorders[pt];
                    if (r == null) r = reorders[pt] = new RtpReorder(64, UdpReceiver.REORDER_MS);
                    r.push(slot, 4, len, System.nanoTime(), sink);
                    // A slot grown for a long datagram is not kept: the ring stays small
                    free.offer(slot.length > SLOT + 4 ? new byte[SLOT + 4] : slot);
                    slot = n + 1 < BATCH ? filled.poll() : null;
                }
                now = System.nanoTime();
                for (RtpReorder r : reorders) {
                    if (r != null) r.poll(now, sink);
                }
                publishStats(now);
            }
        } catch (IOException e) {
            if (!closed) throw e;
        } finally {
            for (int pt = 0; pt < reorders.length; pt++) {
                RtpReorder r = reorders[pt];
                if (r != null && (r.dropped() > 0 || r.skipped() > 0)) {
                    Log.i(TAG, tag + ": UDP pt " + pt + ": " + r.skipped() + " packets lost, "
                            + r.dropped() + " late or duplicate");
                }
            }
            close();
        }
    }

    /**
     * For /stats.json (#251): the buffer the kernel gave, datagrams read, dropped by a full
     * ring, missing from the sequence of the video payload type (0 until it is known), and
     * the kernel's drop count of the port when the app may read it.
     */
    Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rcvbuf", rcvbuf);
        m.put("received", received);
        m.put("ring_dropped", ringDropped);
        m.put("rtp_lost", rtpLost);
        long drops = socketDrops;
        if (drops >= 0) m.put("socket_drops", drops);
        return m;
    }

    /** The video payload type, once the session knows it; its gaps are {@code rtp_lost}. */
    void setVideoPt(int pt) { videoPt = pt; }

    /** On the session thread: the loss counters for stats(), /proc at most once a second. */
    private void publishStats(long now) {
        int pt = videoPt;
        if (pt >= 0) videoTypes[pt] = true;
        long lost = 0;
        for (int i = 0; i < reorders.length; i++) {
            if (videoTypes[i] && reorders[i] != null) lost += reorders[i].skipped();
        }
        rtpLost = lost;
        if (dropsReadable && now - dropsReadNs >= 1_000_000_000L) {
            dropsReadNs = now;
            long d = socketDrops(url.address, url.port);
            // Not readable now is not readable later: no more tries
            if (d < 0) dropsReadable = false;
            socketDrops = d;
        }
    }

    /**
     * The kernel's drop count of this listener's socket: the port and its bound address (the
     * group of a multicast slot, or the wildcard), from /proc/net/udp and udp6; -1 when apps
     * may not read them (Qodo on #252: other groups on the port are not counted).
     */
    static long socketDrops(InetAddress bound, int port) {
        long total = -1;
        String[][] files = {
            {"/proc/net/udp", procAddress(bound, false)},
            {"/proc/net/udp6", procAddress(bound, true)},
        };
        for (String[] f : files) {
            if (f[1] == null) continue;
            long d = socketDrops(f[0], f[1] + String.format(Locale.ROOT, ":%04X", port));
            if (d >= 0) total = Math.max(0, total) + d;
        }
        return total;
    }

    /**
     * An address as /proc/net/udp{,6} prints it: 32-bit words in host (little-endian) order;
     * an IPv4 address in udp6 is IPv4-mapped. Null when it cannot be in that file.
     */
    static String procAddress(InetAddress a, boolean v6File) {
        byte[] b;
        if (a == null) {
            b = new byte[v6File ? 16 : 4];
        } else if (a.getAddress().length == 4) {
            if (!v6File) {
                b = a.getAddress();
            } else {
                b = new byte[16];
                b[10] = (byte) 0xFF;
                b[11] = (byte) 0xFF;
                System.arraycopy(a.getAddress(), 0, b, 12, 4);
            }
        } else {
            if (!v6File) return null;
            b = a.getAddress();
        }
        StringBuilder sb = new StringBuilder();
        for (int w = 0; w < b.length; w += 4) {
            for (int i = 3; i >= 0; i--) {
                sb.append(String.format(Locale.ROOT, "%02X", b[w + i] & 0xFF));
            }
        }
        return sb.toString();
    }

    /** Sum of the drops column of the rows whose local address is {@code local}, or -1. */
    static long socketDrops(String file, String local) {
        long total = -1;
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            in.readLine(); // the header
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                // sl local_address rem_address st ... drops (the last column)
                if (f.length < 13 || !f[1].equalsIgnoreCase(local)) continue;
                try {
                    total = Math.max(0, total) + Long.parseLong(f[f.length - 1]);
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        return total;
    }

    /** From any thread; a blocked receive ends with the socket. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (joined) {
            try {
                socket.leaveGroup(url.address);
            } catch (IOException ignored) {}
            MulticastLock.release();
        }
        socket.close();
    }

    /**
     * Wi-Fi drivers drop multicast unless an app holds a MulticastLock. Counted, so it is
     * held while any slot receives a group; a no-op without an app context (unit tests).
     */
    static final class MulticastLock {
        private static WifiManager.MulticastLock lock;
        private static int users;

        static synchronized void acquire() {
            if (users++ > 0) return;
            Context ctx = App.context;
            if (ctx == null) return;
            WifiManager wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            try {
                lock = wifi.createMulticastLock("OpenIPCDecoder");
                lock.setReferenceCounted(false);
                lock.acquire();
            } catch (RuntimeException e) {
                Log.w(TAG, "No Wi-Fi multicast lock: " + e.getMessage());
                lock = null;
            }
        }

        static synchronized void release() {
            if (users == 0 || --users > 0) return;
            if (lock != null) {
                lock.release();
                lock = null;
            }
        }
    }
}
