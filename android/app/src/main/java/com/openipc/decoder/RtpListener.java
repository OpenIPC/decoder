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

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.Locale;

/**
 * Receives the stream that Majestic sends to "outgoing" destinations (udp://host:port):
 * RTP with payload type 96 (H.264) or 97 (H.265) and the parameter sets repeated in band
 * before every IDR, so it can be joined at any time without an SDP (#68).
 * A MulticastSocket is used for unicast too: joining a group from a DatagramChannel needs
 * API 24.
 */
class RtpListener implements Closeable {
    private static final String TAG = "OpenIPCDecoder";
    private static final int RCVBUF = 2 * 1024 * 1024;

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
    private volatile boolean closed;
    private boolean joined;
    private long rejected;

    RtpListener(String tag, Url url) throws IOException {
        this.tag = tag;
        this.url = url;
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
        if (joined) MulticastLock.acquire();
    }

    /** Receive until {@code keepGoing} turns false or {@link #close} is called. */
    void run(UdpReceiver.Condition keepGoing, Handler handler) throws IOException {
        byte[] buf = new byte[65536];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        RtpReorder.Sink sink = handler::onPacket;
        InetAddress source = url.source;
        try {
            while (!closed && keepGoing.holds()) {
                long now = System.nanoTime();
                long ms = 250;
                for (RtpReorder r : reorders) {
                    long d = r != null ? r.msUntilDeadline(now) : -1;
                    if (d >= 0) ms = Math.min(ms, d);
                }
                socket.setSoTimeout((int) Math.max(1, ms));
                try {
                    p.setLength(buf.length);
                    socket.receive(p);
                    if (source != null && !source.equals(p.getAddress())) {
                        if (rejected++ % 100 == 0) {
                            Log.w(TAG, tag + ": UDP from an unexpected sender ignored ("
                                    + rejected + ")");
                        }
                        continue;
                    }
                    if (p.getLength() < 12) continue;
                    int pt = buf[p.getOffset() + 1] & 0x7F;
                    RtpReorder r = reorders[pt];
                    if (r == null) r = reorders[pt] = new RtpReorder(64, UdpReceiver.REORDER_MS);
                    r.push(buf, p.getOffset(), p.getLength(), System.nanoTime(), sink);
                } catch (SocketTimeoutException ignored) {
                    // Check the condition and the reorder deadlines
                }
                now = System.nanoTime();
                for (RtpReorder r : reorders) {
                    if (r != null) r.poll(now, sink);
                }
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
