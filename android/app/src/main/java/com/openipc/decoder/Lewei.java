/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * Lewei.java — Lewei toy drones (vendor app WiFi UFO): H.264 over TCP port 7060 (#262)
 *
 */

package com.openipc.decoder;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Locale;

/**
 * Toy drones with a Lewei camera (a VISUO, vendor app WiFi UFO, com.lewei.multiple.lewei) at
 * 192.168.0.1 (#262). A PCAPdroid capture of the vendor app shows the session on TCP port
 * 7060; pylwdrone (meekworth/pylwdrone, Apache-2.0, used as a description only) names the
 * fields. Every message starts with a 46-byte header: "lewei_cmd", a zero byte, then nine
 * little-endian 32-bit numbers: the command, an argument, one unused, the length of what
 * follows, the stream type, two keys. The client sends command 2 once with the argument 1 (the
 * high-definition stream); the toy answers with video at once, command 1 every second keeps it
 * going (the toy answers with 64 bytes of its own), command 3 ends it. Video comes as command
 * 257: 32 bytes of its own, little-endian (0-3 a flag, set on a frame with SPS and PPS, 4-7 the
 * length of the frame, 8-15 a counter, the time in ms), then one H.264 frame in Annex B form.
 * With stream type 1, as the capture has it, the camera has inverted one byte of the frame,
 * at a place that follows from the length and the counter: see {@link #mungedByte}. Type 129,
 * which needs tables, is not handled: the log says so and the picture will have errors. The NAL
 * units go to the normal H.264 path as RTP. The vendor app also talks to TCP port 8060 (the
 * time, a query) after the video has started, so the video does not need it, and sends flight
 * packets over UDP: neither is done here, this class only asks for video.
 * Pure Java apart from the log.
 */
final class Lewei {
    private static final String TAG = "OpenIPCDecoder";

    static final String SCHEME = "lewei://";
    static final String DEFAULT_HOST = "192.168.0.1";
    static final int PORT = 7060;
    static final int HEADER = 46;
    static final int VIDEO_HEADER = 32;
    static final int CMD_HEARTBEAT = 1;
    static final int CMD_START = 2;
    static final int CMD_STOP = 3;
    static final int CMD_VIDEO = 257;
    static final int STREAM_PLAIN = 0;
    static final int STREAM_ONE_BYTE = 1;
    /** The largest message accepted; a longer length is taken for a damaged header. */
    static final int MAX_MESSAGE = 4 * 1024 * 1024;
    static final long HEARTBEAT_MS = 1000;
    /** No video for this long: the session ends and the next one starts over. */
    static final long QUIET_MS = 5000;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TICK_MS = 200;
    /** How long close() lets the session send the stop command before it closes the TCP. */
    static volatile long closeGraceMs = 1000;
    /** Messages logged whole from the start of a session, for reports from the field. */
    private static final int LOGGED_MESSAGES = 6;

    private static final byte[] MAGIC = {'l', 'e', 'w', 'e', 'i', '_', 'c', 'm', 'd'};
    private static final int CMD_AT = 10;
    private static final int ARG_AT = 14;
    private static final int LENGTH_AT = 22;
    private static final int STREAM_TYPE_AT = 26;
    /** Where the length of a frame is, in the 32 bytes before it. */
    private static final int FRAME_LENGTH_AT = 4;
    /** Where the counter of a frame is, in the 32 bytes before it. */
    private static final int COUNTER_AT = 8;

    private Lewei() {
    }

    /** lewei://host[:port]; the host defaults to 192.168.0.1, the port to 7060. */
    static final class Url {
        String host = DEFAULT_HOST;
        int port = PORT;

        static boolean matches(String url) {
            return url != null && url.trim().toLowerCase(Locale.ROOT).startsWith(SCHEME);
        }

        static Url parse(String url) throws IOException {
            if (!matches(url)) throw new IOException("Not a lewei:// URL");
            String rest = url.trim().substring(SCHEME.length());
            for (int i = 0; i < rest.length(); i++) {
                char ch = rest.charAt(i);
                if (ch == '/' || ch == '?' || ch == '#') {
                    rest = rest.substring(0, i);
                    break;
                }
            }
            Url u = new Url();
            String host = rest;
            int colon = rest.lastIndexOf(':');
            if (rest.startsWith("[")) {
                int close = rest.indexOf(']');
                if (close < 0) throw new IOException("Bad host in URL");
                host = rest.substring(1, close);
                String tail = rest.substring(close + 1);
                if (!tail.isEmpty() && tail.charAt(0) != ':') {
                    throw new IOException("Bad host in URL");
                }
                colon = tail.isEmpty() ? -1 : close + 1;
            } else if (colon >= 0) {
                host = rest.substring(0, colon);
            }
            if (colon >= 0) u.port = port(rest.substring(colon + 1));
            if (!host.isEmpty()) u.host = host;
            for (int i = 0; i < u.host.length(); i++) {
                char ch = u.host.charAt(i);
                if (ch <= 0x20 || ch == 0x7F || ch == '@') throw new IOException("Bad host in URL");
            }
            return u;
        }

        private static int port(String s) throws IOException {
            try {
                int v = Integer.parseInt(s.trim());
                if (v >= 1 && v <= 65535) return v;
            } catch (NumberFormatException e) {
                // said below
            }
            throw new IOException("Bad port in URL: " + s);
        }

        String endpoint() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }
    }

    /** A message to the toy: the header alone. */
    static byte[] command(int cmd) {
        byte[] m = new byte[HEADER];
        System.arraycopy(MAGIC, 0, m, 0, MAGIC.length);
        m[CMD_AT] = (byte) cmd;
        if (cmd == CMD_START) m[ARG_AT] = 1;
        return m;
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16
                | (b[at + 3] & 0xFF) << 24;
    }

    private static long le64(byte[] b, int at) {
        return (le32(b, at) & 0xFFFFFFFFL) | (long) le32(b, at + 4) << 32;
    }

    /** Index of the byte the camera inverted in a frame of stream type 1. */
    static int mungedByte(long counter, int size) {
        long s = size & 0xFFFFFFFFL;
        // Only the low bits of the counter reach the result for any time a toy runs
        long c = counter & Long.MAX_VALUE;
        long v = (s & 1) == 0 ? (s + 1 + (s ^ c)) ^ s : ((s ^ c) + s) ^ s;
        return (int) ((v & Long.MAX_VALUE) % s);
    }

    /**
     * Takes the bytes of the TCP stream in pieces of any size and gives whole messages. After
     * damage it looks for the next "lewei_cmd". Pure Java, for the unit tests.
     */
    static final class Reader {
        private byte[] buf = new byte[256 * 1024];
        private int start;
        private int end;
        /** Bytes thrown away to find the next header. */
        long skipped;
        /** The message of the last next(), valid until the following call. */
        int cmd;
        int streamType;
        int bodyAt;
        int bodyLength;

        byte[] buffer() {
            return buf;
        }

        /** Room for at least one read; call before reading into buffer() at writeAt(). */
        int room() {
            if (start > 0) {
                System.arraycopy(buf, start, buf, 0, end - start);
                end -= start;
                start = 0;
            }
            if (end == buf.length) {
                byte[] bigger = new byte[buf.length * 2];
                System.arraycopy(buf, 0, bigger, 0, end);
                buf = bigger;
            }
            return buf.length - end;
        }

        int writeAt() {
            return end;
        }

        void wrote(int n) {
            end += n;
        }

        /** The next whole message in the buffer, or false when more bytes are needed. */
        boolean next() {
            while (end - start >= HEADER) {
                if (!magicAt(start)) {
                    resync();
                    continue;
                }
                int length = le32(buf, start + LENGTH_AT);
                if (length < 0 || length > MAX_MESSAGE) {
                    start++;
                    skipped++;
                    resync();
                    continue;
                }
                // A damaged length would hold back the good messages behind it: a video
                // message says its length twice
                if (le32(buf, start + CMD_AT) == CMD_VIDEO && length > VIDEO_HEADER
                        && end - start >= HEADER + FRAME_LENGTH_AT + 4
                        && le32(buf, start + HEADER + FRAME_LENGTH_AT) != length - VIDEO_HEADER) {
                    start++;
                    skipped++;
                    resync();
                    continue;
                }
                if (end - start < HEADER + length) return false;
                cmd = le32(buf, start + CMD_AT);
                streamType = le32(buf, start + STREAM_TYPE_AT);
                bodyAt = start + HEADER;
                bodyLength = length;
                start += HEADER + length;
                return true;
            }
            return false;
        }

        private boolean magicAt(int at) {
            for (int i = 0; i < MAGIC.length; i++) {
                if (buf[at + i] != MAGIC[i]) return false;
            }
            return true;
        }

        /** To the next possible header; a partial one at the end of the buffer stays. */
        private void resync() {
            while (start < end) {
                int n = Math.min(MAGIC.length, end - start);
                boolean match = true;
                for (int i = 0; i < n && match; i++) match = buf[start + i] == MAGIC[i];
                if (match) return;
                start++;
                skipped++;
            }
        }

        boolean isVideo() {
            return cmd == CMD_VIDEO && bodyLength > VIDEO_HEADER;
        }

        boolean isKeyFrame() {
            return le32(buf, bodyAt) != 0;
        }

        int timeMs() {
            return le32(buf, bodyAt + COUNTER_AT);
        }

        /** Undoes what the camera did to the frame, in place; false for an unknown type. */
        boolean restoreFrame() {
            if (streamType == STREAM_PLAIN) return true;
            if (streamType != STREAM_ONE_BYTE) return false;
            int size = bodyLength - VIDEO_HEADER;
            int i = mungedByte(le64(buf, bodyAt + COUNTER_AT), size);
            buf[bodyAt + VIDEO_HEADER + i] ^= (byte) 0xFF;
            return true;
        }
    }

    /** Gets RTP packets of a video stream. */
    interface RtpSink {
        boolean onRtp(byte[] buf, int offset, int length);
    }

    /**
     * Cuts an Annex B frame into NAL units and hands them on as RTP (RFC 6184: single NAL
     * units and FU-A), which is what the app's H.264 path takes. Pure Java, for the unit tests.
     */
    static final class Packetizer {
        static final int PT = Jieli.PT_H264;
        private static final int RTP_HEADER = 12;
        private static final int MAX_PAYLOAD = 1400;
        private static final int NAL_FU_A = 28;
        private final byte[] packet = new byte[RTP_HEADER + MAX_PAYLOAD];
        private int seq;

        /** Returns whether anything of the frame was accepted. */
        boolean frame(byte[] b, int from, int to, int timeMs, RtpSink sink) {
            boolean used = false;
            int nal = nextStart(b, from, to);
            while (nal >= 0) {
                int begin = nal + startLength(b, nal);
                int next = nextStart(b, begin, to);
                int end = next >= 0 ? next : to;
                // Zero bytes before the next start code are not part of the unit
                while (end > begin && b[end - 1] == 0) end--;
                if (end > begin) used |= nal(b, begin, end, timeMs, next < 0, sink);
                nal = next;
            }
            return used;
        }

        private boolean nal(byte[] b, int from, int to, int timeMs, boolean last, RtpSink sink) {
            int size = to - from;
            if (size <= MAX_PAYLOAD) {
                System.arraycopy(b, from, packet, RTP_HEADER, size);
                return send(RTP_HEADER + size, timeMs, last, sink);
            }
            boolean used = false;
            int header = b[from] & 0xFF;
            int pos = from + 1;
            boolean first = true;
            while (pos < to) {
                int n = Math.min(MAX_PAYLOAD - 2, to - pos);
                boolean end = pos + n == to;
                packet[RTP_HEADER] = (byte) (header & 0xE0 | NAL_FU_A);
                packet[RTP_HEADER + 1] = (byte) (header & 0x1F | (first ? 0x80 : 0)
                        | (end ? 0x40 : 0));
                System.arraycopy(b, pos, packet, RTP_HEADER + 2, n);
                used |= send(RTP_HEADER + 2 + n, timeMs, end && last, sink);
                pos += n;
                first = false;
            }
            return used;
        }

        private boolean send(int length, int timeMs, boolean marker, RtpSink sink) {
            long ts = (timeMs & 0xFFFFFFFFL) * 90;
            packet[0] = (byte) 0x80;
            packet[1] = (byte) (PT | (marker ? 0x80 : 0));
            packet[2] = (byte) (seq >> 8);
            packet[3] = (byte) seq;
            packet[4] = (byte) (ts >> 24);
            packet[5] = (byte) (ts >> 16);
            packet[6] = (byte) (ts >> 8);
            packet[7] = (byte) ts;
            seq = (seq + 1) & 0xFFFF;
            return sink.onRtp(packet, 0, length);
        }

        /** Position of the next start code in the range, or -1. */
        private static int nextStart(byte[] b, int from, int to) {
            for (int i = from; i + 3 <= to; i++) {
                if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                    return i > from && b[i - 1] == 0 ? i - 1 : i;
                }
            }
            return -1;
        }

        private static int startLength(byte[] b, int at) {
            return b[at + 2] == 1 ? 3 : 4;
        }
    }

    /** Whether the session should go on. */
    interface KeepGoing {
        boolean check();
    }

    /** What a session reports, on the thread of run(). */
    interface Listener {
        void onState(boolean receiving, String text);

        /** One RTP packet of H.264, valid during the call; returns whether it was taken. */
        boolean onRtp(byte[] buf, int offset, int length);
    }

    /**
     * One session on the calling thread until {@code keepGoing} fails or close(). A lost
     * connection or a toy that stopped sending ends run() with an exception: the session
     * starts again. Once the stream was asked for, the session ends with the stop command on
     * the same connection, as the vendor app and pylwdrone do (#269).
     */
    static final class Client implements Closeable {
        private final String tag;
        private final Url url;
        private final Jieli.TcpSetup setup;
        private final Socket socket = new Socket();
        private volatile boolean closed;
        private final Object lock = new Object();
        /** The socket is connected: close() leaves it to run(), which sends the stop first. */
        private boolean connected;
        private final java.util.concurrent.CountDownLatch ended =
                new java.util.concurrent.CountDownLatch(1);
        /** The stream was asked for on this connection. */
        volatile boolean streamRequested;

        Client(String tag, Url url, Jieli.TcpSetup setup) {
            this.tag = tag;
            this.url = url;
            this.setup = setup;
        }

        void run(KeepGoing keepGoing, Listener listener) throws IOException {
            try {
                session(keepGoing, listener);
            } finally {
                // A write blocked on a toy that stopped reading ends when close() closes the
                // socket after its grace period
                if (streamRequested && !socket.isClosed()) {
                    try {
                        socket.getOutputStream().write(command(CMD_STOP));
                    } catch (IOException ignored) {
                        // The connection is gone: the toy sees the TCP close
                    }
                }
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Closed is closed
                }
                ended.countDown();
            }
        }

        private void session(KeepGoing keepGoing, Listener listener) throws IOException {
            InetAddress target = InetAddress.getByName(url.host);
            String bound = setup.prepare(socket, target);
            listener.onState(false, "Waiting for the toy: connecting to " + url.endpoint());
            try {
                socket.connect(new InetSocketAddress(target, url.port), CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                throw closed ? e : new IOException("Cannot reach the toy's video port "
                        + url.endpoint() + ": " + e.getMessage(), e);
            }
            synchronized (lock) {
                if (closed) return;
                connected = true;
            }
            Log.i(TAG, tag + ": Lewei: connected to " + url.endpoint() + " (" + bound + ")");
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(READ_TICK_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            out.write(command(CMD_START));
            streamRequested = true;
            listener.onState(false, "Waiting for the toy: asked for video on " + url.endpoint());

            Reader reader = new Reader();
            Packetizer packetizer = new Packetizer();
            RtpSink sink = listener::onRtp;
            long now = nowMs();
            long nextHeartbeat = now + HEARTBEAT_MS;
            long lastVideo = now;
            boolean receiving = false;
            int logged = 0;
            int warnedType = STREAM_PLAIN;
            long skippedLogged = 0;
            try {
                while (keepGoing.check() && !closed) {
                    try {
                        int room = reader.room();
                        int n = in.read(reader.buffer(), reader.writeAt(), room);
                        if (n < 0) throw new IOException("The toy closed the connection");
                        reader.wrote(n);
                    } catch (SocketTimeoutException e) {
                        // Time to look at the clock
                    }
                    while (reader.next()) {
                        boolean video = reader.isVideo();
                        if (logged < LOGGED_MESSAGES) {
                            logged++;
                            Log.i(TAG, tag + ": Lewei: message " + describe(reader));
                        }
                        if (!video) continue;
                        if (!reader.restoreFrame() && reader.streamType != warnedType) {
                            warnedType = reader.streamType;
                            Log.w(TAG, tag + ": Lewei: stream type " + warnedType
                                    + " is not handled, the picture will have errors");
                        }
                        if (packetizer.frame(reader.buffer(), reader.bodyAt + VIDEO_HEADER,
                                reader.bodyAt + reader.bodyLength, reader.timeMs(), sink)) {
                            lastVideo = nowMs();
                            if (!receiving) {
                                receiving = true;
                                listener.onState(true, null);
                            }
                        }
                    }
                    if (reader.skipped != skippedLogged) {
                        skippedLogged = reader.skipped;
                        Log.w(TAG, tag + ": Lewei: " + skippedLogged + " bytes skipped in all");
                    }
                    now = nowMs();
                    if (now >= nextHeartbeat) {
                        out.write(command(CMD_HEARTBEAT));
                        nextHeartbeat = now + HEARTBEAT_MS;
                    }
                    if (now - lastVideo >= QUIET_MS) {
                        throw new IOException("No video from the toy for "
                                + QUIET_MS / 1000 + " s");
                    }
                }
            } catch (IOException e) {
                if (!closed && keepGoing.check()) throw e;
            }
        }

        private static String describe(Reader r) {
            StringBuilder sb = new StringBuilder("cmd ").append(r.cmd).append(" type ")
                    .append(r.streamType).append(", ").append(r.bodyLength).append(" bytes:");
            int n = Math.min(r.bodyLength, r.isVideo() ? VIDEO_HEADER + 8 : 64);
            for (int i = 0; i < n; i++) {
                sb.append(String.format(Locale.ROOT, " %02x",
                        r.buffer()[r.bodyAt + i] & 0xFF));
            }
            return sb.toString();
        }

        private static long nowMs() {
            return System.nanoTime() / 1_000_000;
        }

        /**
         * Ends run() from any thread, the UI thread too: no socket write here. An unconnected
         * socket closes at once; a connected one is left to the session thread, which sends
         * the stop command and closes it within one read tick (as Jieli does, #188). A stop
         * that comes that late is the one risk: a new session may already have connected.
         */
        @Override
        public void close() {
            closed = true;
            synchronized (lock) {
                if (!connected) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        // Closed is closed
                    }
                    return;
                }
            }
            if (ended.getCount() == 0) return;
            Thread t = new Thread(() -> {
                try {
                    if (!ended.await(closeGraceMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        socket.close();
                    }
                } catch (InterruptedException | IOException ignored) {
                    // Nothing more to do
                }
            }, tag + "-lewei-close");
            t.setDaemon(true);
            t.start();
        }
    }
}
