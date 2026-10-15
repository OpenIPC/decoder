/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * Jieli.java — Jieli toy drones ("HF_xxxxxxxx" Wi-Fi): CTP over TCP, RTP to UDP 6666 (#187)
 *
 */

package com.openipc.decoder;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Toy drones with an "HF_xxxxxxxx" access point (Jieli AC79 camera, vendor app "DVRunning 2")
 * at 192.168.80.1 (#187, #196). The app talks CTP to TCP port 3333: "CTP:", the topic length
 * (u16 LE), the topic, the JSON length (u32 LE) and the JSON, which may be empty. The session
 * as a PCAPdroid capture of the vendor app shows it: APP_ACCESS; the toy announces
 * KEEP_ALIVE_INTERVAL, VIDEO_PARAM and more; DATE_TIME; CTP_KEEP_ALIVE every 7.5 s;
 * OPEN_RT_STREAM; CLOSE_RT_STREAM at the end. The video itself was not in the capture: after
 * turbodrone's research on the same SDK (marshallrichards/turbodrone, Apache-2.0, used as a
 * description only) it is RTP/JPEG (payload type 26) or H.264 (96) to the phone's UDP port
 * 6666. The packets go into the app's normal RTP paths. Unverified, so the log shows what
 * really arrives on 6666, and on the audio and stream ports 1234 and 2224 too when 6666 stays
 * silent. Video only: FLIGHT_CONTROL is never sent, nothing goes to the toy's UDP port 3456.
 * Pure Java apart from the log.
 */
final class Jieli {
    private static final String TAG = "OpenIPCDecoder";

    static final String SCHEME = "jieli://";
    static final String DEFAULT_HOST = "192.168.80.1";
    static final int CTP_PORT = 3333;
    static final int RTP_PORT = 6666;
    static final int DEFAULT_WIDTH = 640;
    static final int DEFAULT_HEIGHT = 480;
    static final int DEFAULT_FPS = 25;
    static final int FORMAT_JPEG = 0;
    static final int FORMAT_H264 = 1;
    static final int PT_JPEG = 26;
    static final int PT_H264 = 96;

    static final byte[] MAGIC = {'C', 'T', 'P', ':'};
    /** Longer topics or JSON than this are not CTP: the reader looks for the next "CTP:". */
    static final int MAX_TOPIC = 256;
    static final int MAX_JSON = 256 * 1024;

    static final String APP_ACCESS = "APP_ACCESS";
    static final String DATE_TIME = "DATE_TIME";
    static final String KEEP_ALIVE = "CTP_KEEP_ALIVE";
    static final String KEEP_ALIVE_INTERVAL = "KEEP_ALIVE_INTERVAL";
    static final String VIDEO_PARAM = "VIDEO_PARAM";
    static final String OPEN_RT_STREAM = "OPEN_RT_STREAM";
    static final String CLOSE_RT_STREAM = "CLOSE_RT_STREAM";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    /** Read timeout of the CTP socket: how often the timers are looked at. */
    private static final int TICK_MS = 100;
    /** The keepalive of the vendor app; a shorter announced timeout makes it timeout / 4. */
    static final long KEEP_ALIVE_MAX_MS = 7500;
    private static final long KEEP_ALIVE_MIN_MS = 250;
    // Not final so unit tests can shorten them
    /** How long APP_ACCESS waits for VIDEO_PARAM before the stream is asked for anyway. */
    static volatile long paramWaitMs = 1500;
    /** No video this long after OPEN_RT_STREAM: said in the log and status, diagnostics on. */
    static volatile long noVideoMs = 5000;
    /** No video this long (after a request or the last packet): OPEN_RT_STREAM again. */
    static volatile long reopenMs = 10_000;
    /** Video that stops for this long is "quiet". */
    static volatile long quietMs = 3000;
    /** The toy says nothing on CTP for this long (at least): the connection is given up. */
    static volatile long ctpSilenceMs = 20_000;
    /**
     * Ports opened only to log what arrives when 6666 stays silent: 1234 (audio in the
     * vendor SDK) and 2224 (its RT stream port). Nothing received there is played.
     */
    static volatile int[] diagnosticPorts = {1234, 2224};

    private Jieli() {}

    // ====================== URL ======================

    /**
     * jieli://[host][:port][/...][?option=value&...]: the toy's CTP address, 192.168.80.1:3333
     * when left out. Options, as in udp:// URLs (unknown ones are ignored, bad values
     * refused): w, h, fps (the stream asked for; default what the toy announces in
     * VIDEO_PARAM, else 640x480 at 25), rtp (the local UDP port for the video, 6666),
     * format (0 JPEG, the default, or 1 H.264).
     */
    static final class Url {
        String host = DEFAULT_HOST;
        int port = CTP_PORT;
        int rtpPort = RTP_PORT;
        /** 0: from VIDEO_PARAM, else the default. */
        int width;
        int height;
        int fps;
        int format = FORMAT_JPEG;

        static boolean matches(String url) {
            return url != null && url.trim().toLowerCase(Locale.ROOT).startsWith(SCHEME);
        }

        static Url parse(String url) throws IOException {
            if (!matches(url)) throw new IOException("Not a jieli:// URL");
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
            if (colon >= 0) u.port = number(authority.substring(colon + 1), 1, 65535, "port");
            if (!host.isEmpty()) u.host = host;
            for (int i = 0; i < u.host.length(); i++) {
                char ch = u.host.charAt(i);
                if (ch <= 0x20 || ch == 0x7F || ch == '@') throw new IOException("Bad host in URL");
            }
            for (String param : query.split("&")) {
                int eq = param.indexOf('=');
                if (eq < 0) continue;
                String name = param.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = param.substring(eq + 1).trim();
                switch (name) {
                    case "w":
                        u.width = number(value, 16, 4096, "w");
                        break;
                    case "h":
                        u.height = number(value, 16, 4096, "h");
                        break;
                    case "fps":
                        u.fps = number(value, 1, 120, "fps");
                        break;
                    case "rtp":
                        u.rtpPort = number(value, 1, 65535, "rtp");
                        break;
                    case "format":
                        u.format = number(value, 0, 1, "format (0 JPEG, 1 H.264)");
                        break;
                    default:
                        break;
                }
            }
            return u;
        }

        private static int number(String s, int min, int max, String what) throws IOException {
            int v;
            try {
                v = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Bad " + what + " in URL: " + s);
            }
            if (v < min || v > max) throw new IOException("Bad " + what + " in URL: " + s);
            return v;
        }

        String endpoint() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }
    }

    // ====================== CTP messages ======================

    /** One CTP message: a topic and its JSON ("" when there is none). */
    static final class Message {
        final String topic;
        final String json;

        Message(String topic, String json) {
            this.topic = topic;
            this.json = json;
        }

        @Override
        public String toString() {
            return json.isEmpty() ? topic : topic + " " + json;
        }
    }

    /** "CTP:" + topic length (u16 LE) + topic + JSON length (u32 LE) + JSON. */
    static byte[] encode(String topic, String json) {
        byte[] t = topic.getBytes(StandardCharsets.UTF_8);
        byte[] j = json != null ? json.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] out = new byte[MAGIC.length + 2 + t.length + 4 + j.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        int pos = MAGIC.length;
        out[pos] = (byte) t.length;
        out[pos + 1] = (byte) (t.length >> 8);
        pos += 2;
        System.arraycopy(t, 0, out, pos, t.length);
        pos += t.length;
        for (int i = 0; i < 4; i++) out[pos + i] = (byte) (j.length >> (8 * i));
        pos += 4;
        System.arraycopy(j, 0, out, pos, j.length);
        return out;
    }

    /**
     * Cuts the TCP byte stream into messages: a message may arrive in pieces or several in
     * one read. Bytes that are not a message (no "CTP:", a length out of range) are skipped
     * up to the next "CTP:" and counted.
     */
    static final class Reader {
        private byte[] buf = new byte[4096];
        private int start;
        private int end;
        long skipped;

        void feed(byte[] b, int off, int len) {
            if (end + len > buf.length) {
                int have = end - start;
                if (have + len > buf.length) {
                    byte[] n = new byte[Math.max(buf.length * 2, have + len)];
                    System.arraycopy(buf, start, n, 0, have);
                    buf = n;
                } else {
                    System.arraycopy(buf, start, buf, 0, have);
                }
                start = 0;
                end = have;
            }
            System.arraycopy(b, off, buf, end, len);
            end += len;
        }

        /** The next whole message, or null until more bytes arrive. */
        Message next() {
            while (true) {
                int have = end - start;
                if (have < MAGIC.length) return null;
                if (!startsWithMagic(start)) {
                    skipToMagic();
                    continue;
                }
                if (have < MAGIC.length + 2) return null;
                int topicLen = (buf[start + 4] & 0xFF) | (buf[start + 5] & 0xFF) << 8;
                if (topicLen > MAX_TOPIC) {
                    skipOne();
                    continue;
                }
                int jsonAt = start + MAGIC.length + 2 + topicLen;
                if (end < jsonAt + 4) return null;
                long jsonLen = (buf[jsonAt] & 0xFFL) | (buf[jsonAt + 1] & 0xFFL) << 8
                        | (buf[jsonAt + 2] & 0xFFL) << 16 | (buf[jsonAt + 3] & 0xFFL) << 24;
                if (jsonLen > MAX_JSON) {
                    skipOne();
                    continue;
                }
                int total = MAGIC.length + 2 + topicLen + 4 + (int) jsonLen;
                if (have < total) return null;
                String topic = new String(buf, start + 6, topicLen, StandardCharsets.UTF_8);
                String json = new String(buf, jsonAt + 4, (int) jsonLen, StandardCharsets.UTF_8);
                start += total;
                if (start == end) start = end = 0;
                return new Message(topic, json);
            }
        }

        private boolean startsWithMagic(int at) {
            for (int i = 0; i < MAGIC.length; i++) {
                if (buf[at + i] != MAGIC[i]) return false;
            }
            return true;
        }

        /** Drops the first byte (a "CTP:" with bad lengths) and looks for the next one. */
        private void skipOne() {
            start++;
            skipped++;
            skipToMagic();
        }

        private void skipToMagic() {
            int i = start;
            while (i + MAGIC.length <= end && !startsWithMagic(i)) i++;
            if (i + MAGIC.length > end) {
                // Keep a tail that may be the start of "CTP:"
                i = Math.max(start, end - (MAGIC.length - 1));
            }
            skipped += i - start;
            start = i;
        }
    }

    // ====================== JSON ======================

    /** {"op":"PUT"}, or with "param" from name/value pairs, in their order. */
    static String put(String... nameValues) {
        StringBuilder sb = new StringBuilder("{\"op\":\"PUT\"");
        if (nameValues.length > 0) {
            sb.append(",\"param\":{");
            for (int i = 0; i + 1 < nameValues.length; i += 2) {
                if (i > 0) sb.append(',');
                quote(sb, nameValues[i]);
                sb.append(':');
                quote(sb, nameValues[i + 1]);
            }
            sb.append('}');
        }
        return sb.append('}').toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    /**
     * A small JSON reader (org.json is a stub in unit tests): objects become maps, arrays
     * lists, strings strings, and numbers, true, false and null their text. Null when the
     * text is not one JSON object.
     */
    static Map<String, Object> parseJson(String s) {
        if (s == null) return null;
        try {
            Json j = new Json(s);
            j.space();
            if (j.peek() != '{') return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) j.value(0);
            j.space();
            return j.pos == s.length() ? m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Json {
        final String s;
        int pos;

        Json(String s) {
            this.s = s;
        }

        char peek() {
            if (pos >= s.length()) throw new IllegalArgumentException("end");
            return s.charAt(pos);
        }

        void space() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        void expect(char c) {
            space();
            if (peek() != c) throw new IllegalArgumentException("expected " + c);
            pos++;
        }

        Object value(int depth) {
            if (depth > 16) throw new IllegalArgumentException("too deep");
            space();
            char c = peek();
            if (c == '{') {
                pos++;
                Map<String, Object> m = new LinkedHashMap<>();
                space();
                if (peek() == '}') {
                    pos++;
                    return m;
                }
                while (true) {
                    space();
                    String key = string();
                    expect(':');
                    m.put(key, value(depth + 1));
                    space();
                    if (peek() == ',') {
                        pos++;
                        continue;
                    }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                pos++;
                List<Object> l = new ArrayList<>();
                space();
                if (peek() == ']') {
                    pos++;
                    return l;
                }
                while (true) {
                    l.add(value(depth + 1));
                    space();
                    if (peek() == ',') {
                        pos++;
                        continue;
                    }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return string();
            int from = pos;
            while (pos < s.length() && ",}] \t\r\n".indexOf(s.charAt(pos)) < 0) pos++;
            if (pos == from) throw new IllegalArgumentException("value");
            return s.substring(from, pos);
        }

        String string() {
            if (peek() != '"') throw new IllegalArgumentException("string");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = peek();
                pos++;
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: sb.append(e); break;
                }
            }
        }
    }

    /** {@code name} in the message's "param" object, else at the top, as text; or null. */
    static String param(Map<String, Object> json, String name) {
        if (json == null) return null;
        Object p = json.get("param");
        if (p instanceof Map) {
            Object v = ((Map<?, ?>) p).get(name);
            if (v instanceof String) return (String) v;
        }
        Object v = json.get(name);
        return v instanceof String ? (String) v : null;
    }

    /** The integer {@code name} of a message, or -1. */
    static int intParam(Map<String, Object> json, String name) {
        String v = param(json, name);
        if (v == null) return -1;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** min(timeout / 4, 7.5 s), not below 250 ms; 7.5 s when the toy announced none. */
    static long keepAliveMs(long timeoutMs) {
        if (timeoutMs <= 0) return KEEP_ALIVE_MAX_MS;
        return Math.max(KEEP_ALIVE_MIN_MS, Math.min(timeoutMs / 4, KEEP_ALIVE_MAX_MS));
    }

    /** The request for the stream, in the field order of the vendor app. */
    static String openStream(int format, int width, int height, int fps) {
        return put("format", String.valueOf(format), "h", String.valueOf(height),
                "w", String.valueOf(width), "fps", String.valueOf(fps));
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ====================== Subnet hint ======================

    /**
     * A hint when a jieli:// slot cannot be right for the phone's networks, or null: the
     * target is the phone itself, or the phone is on another toy family's network.
     */
    static String subnetHint(InetAddress target, List<InetAddress> local) {
        if (!(target instanceof Inet4Address) || local == null) return null;
        boolean own = local.contains(target);
        boolean on1 = false;
        boolean on169 = false;
        boolean on80 = false;
        for (InetAddress a : local) {
            on1 |= FlowUfo.in24(a, 1);
            on169 |= FlowUfo.in24(a, 169);
            on80 |= FlowUfo.in24(a, 80);
        }
        StringBuilder parts = new StringBuilder();
        byte[] b = target.getAddress();
        if (own) {
            FlowUfo.sentence(parts, target.getHostAddress() + " is this phone; the toy is usually "
                    + (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".1");
        }
        if (on80 && !FlowUfo.in24(target, 80)) {
            FlowUfo.sentence(parts, "This phone is on 192.168.80.x: the toy is usually"
                    + " jieli://192.168.80.1");
        }
        if (!on80 && on169) {
            FlowUfo.sentence(parts, "This network looks like a FLOW-UFO toy: try"
                    + " flowufo://192.168.169.1");
        }
        if (!on80 && on1) {
            FlowUfo.sentence(parts, "This network looks like a KY UFO RTSP toy: try"
                    + " rtsp://192.168.1.1:7070/webcam");
        }
        return parts.length() > 0 ? parts.toString() : null;
    }

    // ====================== Client ======================

    interface Listener {
        /** Waiting (no video) or receiving; text for the status. */
        void onState(boolean receiving, String text);

        /** One RTP packet with payload type 26 (JPEG) or 96 (H.264), valid during the call. */
        void onRtp(byte[] buf, int offset, int length);

        /** One whole JPEG in a datagram without RTP, valid during the call. */
        default void onJpeg(byte[] buf, int offset, int length) {}
    }

    /** Binds the CTP socket to the toy's Wi-Fi before it connects; returns what was done. */
    interface TcpSetup {
        String prepare(Socket socket, InetAddress target);
    }

    /**
     * One CTP session on the calling thread (connect, the commands, keepalives, the timers),
     * and the video socket on a thread of its own, until {@code keepGoing} fails or close().
     * The stream is closed with CLOSE_RT_STREAM on the way out. A lost connection ends run()
     * with an exception: the session starts again.
     */
    static final class Client implements Closeable {
        private final String tag;
        private final Url url;
        private final TcpSetup tcpSetup;
        private final FlowUfo.SocketSetup udpSetup;
        private volatile boolean closed;
        /** The phone's addresses for the subnet hint; null: the real ones. For tests. */
        List<InetAddress> localForHint;

        private final Object lock = new Object();
        private Socket tcp;
        private boolean tcpConnected;
        private final List<DatagramSocket> udpSockets = new ArrayList<>();
        private volatile InetAddress address;

        // For the stats
        private volatile String state = "connecting";
        private volatile String hint;
        private volatile String networkTcp = "default";
        private volatile String networkUdp = "default";
        private volatile int ctpSent;
        private volatile int ctpReceived;
        private volatile int keepAlives;
        private volatile int keepAliveReplies;
        private volatile long keepAliveIntervalMs = KEEP_ALIVE_MAX_MS;
        private volatile long announcedTimeoutMs = -1;
        private volatile int openRequests;
        private volatile String lastError;
        private volatile String videoParam;
        private volatile String requested;
        private final ArrayDeque<String> lastNotifies = new ArrayDeque<>();
        private final Map<String, Integer> topics = new LinkedHashMap<>();
        private final Reader reader = new Reader();
        private int ctpLogged;

        // Video socket; written by its thread
        private volatile long lastVideoAt;
        private volatile int packets;
        private volatile long bytes;
        private volatile int rtpPackets;
        private volatile int rtpFrames;
        private volatile int otherRtp;
        private volatile int nonRtp;
        private volatile int rawJpegs;
        private volatile int foreign;
        private volatile String firstPacket;
        private volatile String lastSource;
        private volatile boolean h264Logged;
        private final Map<Integer, Integer> payloadTypes = new LinkedHashMap<>();
        private final Map<String, Integer> sources = new LinkedHashMap<>();
        private final Map<Integer, Integer> diagnostic = new LinkedHashMap<>();
        private final Map<Integer, String> diagnosticFirst = new LinkedHashMap<>();
        private int logged;

        Client(String tag, Url url, TcpSetup tcpSetup, FlowUfo.SocketSetup udpSetup) {
            this.tag = tag;
            this.url = url;
            this.tcpSetup = tcpSetup;
            this.udpSetup = udpSetup;
        }

        /**
         * Ends run() from any thread. The video sockets close at once; a connected CTP socket
         * is left to the session thread, which sends CLOSE_RT_STREAM first (within TICK_MS).
         */
        @Override
        public void close() {
            closed = true;
            synchronized (lock) {
                for (DatagramSocket s : udpSockets) s.close();
                if (tcp != null && !tcpConnected) {
                    try {
                        tcp.close();
                    } catch (IOException ignored) {}
                }
            }
        }

        private String withHint(String text) {
            String h = hint;
            return h != null ? text + ". " + h : text;
        }

        String connectingText() {
            return withHint("Connecting to the toy (CTP " + url.endpoint() + ")");
        }

        String requestedText() {
            return withHint("Stream requested, waiting for video on UDP " + url.rtpPort);
        }

        String noVideoText() {
            return withHint("No video on UDP " + url.rtpPort + " yet: the toy accepted the"
                    + " request; see /log");
        }

        String quietText() {
            return withHint("The toy's video stopped: waiting on UDP " + url.rtpPort);
        }

        void run(UdpReceiver.Condition keepGoing, Listener listener) throws IOException {
            InetAddress a = InetAddress.getByName(url.host);
            address = a;
            hint = subnetHint(a, localForHint != null ? localForHint : FlowUfo.localAddresses());
            if (hint != null) Log.w(TAG, tag + ": Jieli: " + hint);
            OutputStream out = null;
            boolean requestedStream = false;
            Socket s = new Socket();
            try {
                // The video port first: nothing the toy sends after OPEN_RT_STREAM is lost
                DatagramSocket video = openUdp(url.rtpPort, a);
                startThread(video, true, listener);
                state = "connecting";
                listener.onState(false, connectingText());
                if (tcpSetup != null) networkTcp = tcpSetup.prepare(s, a);
                synchronized (lock) {
                    tcp = s;
                    if (closed) return;
                }
                Log.i(TAG, tag + ": Jieli: connecting to CTP " + url.endpoint() + " (network: "
                        + networkTcp + "), video on UDP " + url.rtpPort + " (network: "
                        + networkUdp + ")");
                try {
                    s.connect(new InetSocketAddress(a, url.port), CONNECT_TIMEOUT_MS);
                } catch (IOException e) {
                    if (closed) return;
                    throw new IOException("Cannot reach the toy's CTP port " + url.endpoint()
                            + ": " + e.getMessage());
                }
                synchronized (lock) {
                    tcpConnected = true;
                    if (closed) return;
                }
                s.setTcpNoDelay(true);
                s.setSoTimeout(TICK_MS);
                out = s.getOutputStream();
                InputStream in = s.getInputStream();
                state = "ctp";
                Log.i(TAG, tag + ": Jieli: CTP connected to " + url.endpoint());
                send(out, APP_ACCESS, put("ver", "25", "type", "0"));
                long t0 = now();
                long lastRx = t0;
                long nextKeepAlive = 0;
                long openAt = 0;
                boolean diagnostics = false;
                boolean receiving = false;
                int reopens = 0;
                byte[] buf = new byte[8192];
                while (!closed && keepGoing.holds()) {
                    long t = now();
                    if (!requestedStream && (videoParam != null || t - t0 >= paramWaitMs)) {
                        send(out, DATE_TIME, put("date", new SimpleDateFormat("yyyyMMddHHmmss",
                                Locale.ROOT).format(new Date())));
                        keepAlive(out);
                        nextKeepAlive = t + keepAliveIntervalMs;
                        requestStream(out);
                        requestedStream = true;
                        openAt = t;
                        state = "requested";
                        listener.onState(false, requestedText());
                    }
                    if (requestedStream && t >= nextKeepAlive) {
                        keepAlive(out);
                        nextKeepAlive = t + keepAliveIntervalMs;
                    }
                    if (requestedStream) {
                        long last = lastVideoAt;
                        boolean flowing = last > 0 && t - last < quietMs;
                        if (flowing && !receiving) {
                            receiving = true;
                            state = "playing";
                            Log.i(TAG, tag + ": Jieli: video arrives on UDP " + url.rtpPort
                                    + " from " + lastSource);
                            listener.onState(true, "Receiving from the toy on UDP "
                                    + url.rtpPort + "…");
                        } else if (!flowing && receiving) {
                            receiving = false;
                            state = "quiet";
                            Log.w(TAG, tag + ": Jieli: no video for " + quietMs + " ms ("
                                    + summary() + ")");
                            listener.onState(false, quietText());
                        }
                        if (!diagnostics && last == 0 && t - openAt >= noVideoMs) {
                            diagnostics = true;
                            Log.w(TAG, tag + ": Jieli: no video on UDP " + url.rtpPort + " "
                                    + noVideoMs + " ms after OPEN_RT_STREAM (" + summary()
                                    + "); listening on " + ports(diagnosticPorts)
                                    + " too, for the log only");
                            for (int p : diagnosticPorts) {
                                if (p == url.rtpPort) continue;
                                try {
                                    startThread(openUdp(p, a), false, listener);
                                } catch (IOException e) {
                                    Log.w(TAG, tag + ": Jieli: cannot listen on UDP " + p + ": "
                                            + e.getMessage());
                                }
                            }
                            listener.onState(false, noVideoText());
                        }
                        if (!receiving && t - Math.max(openAt, last) >= reopenMs) {
                            reopens++;
                            if (reopens <= 3 || reopens % 10 == 0) {
                                Log.w(TAG, tag + ": Jieli: still no video, OPEN_RT_STREAM again ("
                                        + reopens + ")");
                            }
                            requestStream(out);
                            openAt = t;
                        }
                    }
                    if (t - lastRx > Math.max(ctpSilenceMs, announcedTimeoutMs)) {
                        throw new IOException("The toy stopped answering on CTP "
                                + url.endpoint());
                    }
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    if (n < 0) throw new IOException("The toy closed the CTP connection");
                    lastRx = now();
                    reader.feed(buf, 0, n);
                    Message m;
                    while ((m = reader.next()) != null) handle(m);
                }
            } catch (IOException e) {
                lastError = e.getMessage();
                if (closed) return;
                throw e;
            } finally {
                if (out != null && requestedStream && !s.isClosed()) {
                    try {
                        send(out, CLOSE_RT_STREAM, put("status", "1"));
                    } catch (IOException ignored) {}
                }
                try {
                    s.close();
                } catch (IOException ignored) {}
                synchronized (lock) {
                    for (DatagramSocket d : udpSockets) d.close();
                    udpSockets.clear();
                    tcp = null;
                    tcpConnected = false;
                }
                Log.i(TAG, tag + ": Jieli: session ended (" + summary() + ")");
            }
        }

        private static String ports(int[] p) {
            StringBuilder sb = new StringBuilder("UDP");
            for (int i = 0; i < p.length; i++) sb.append(i == 0 ? " " : ", ").append(p[i]);
            return sb.toString();
        }

        private DatagramSocket openUdp(int port, InetAddress target) throws IOException {
            DatagramSocket d = new DatagramSocket(null);
            try {
                d.bind(new InetSocketAddress(port));
            } catch (IOException e) {
                d.close();
                throw new IOException("UDP port " + port + " is in use (another jieli:// slot"
                        + " or app?): " + e.getMessage());
            }
            try {
                d.setReceiveBufferSize(1024 * 1024);
            } catch (IOException ignored) {}
            String n = udpSetup != null ? udpSetup.prepare(d, target) : "default";
            if (port == url.rtpPort) networkUdp = n;
            synchronized (lock) {
                udpSockets.add(d);
                if (closed) d.close();
            }
            return d;
        }

        private void startThread(DatagramSocket d, boolean video, Listener listener) {
            int port = d.getLocalPort();
            Thread th = new Thread(() -> {
                byte[] b = new byte[65536];
                DatagramPacket p = new DatagramPacket(b, b.length);
                try {
                    while (!closed && !d.isClosed()) {
                        p.setLength(b.length);
                        d.receive(p);
                        if (video) {
                            video(p, listener);
                        } else {
                            diagnostic(port, p);
                        }
                    }
                } catch (IOException e) {
                    // Closed
                }
            }, tag + "-jieli-" + port);
            th.setDaemon(true);
            th.start();
        }

        private void send(OutputStream out, String topic, String json) throws IOException {
            out.write(encode(topic, json));
            out.flush();
            ctpSent++;
            if (!KEEP_ALIVE.equals(topic) || keepAlives < 2) logCtp("→", topic, json);
        }

        private void keepAlive(OutputStream out) throws IOException {
            send(out, KEEP_ALIVE, put());
            keepAlives++;
        }

        private void requestStream(OutputStream out) throws IOException {
            int[] p = videoParams();
            int w = url.width > 0 ? url.width : p[0] > 0 ? p[0] : DEFAULT_WIDTH;
            int h = url.height > 0 ? url.height : p[1] > 0 ? p[1] : DEFAULT_HEIGHT;
            int fps = url.fps > 0 ? url.fps : p[2] > 0 ? p[2] : DEFAULT_FPS;
            requested = w + "x" + h + " at " + fps + " fps, format " + url.format
                    + (url.format == FORMAT_H264 ? " (H.264)" : " (JPEG)");
            send(out, OPEN_RT_STREAM, openStream(url.format, w, h, fps));
            openRequests++;
        }

        /** Width, height and fps of VIDEO_PARAM, -1 each when not received. */
        private int[] videoParams() {
            Map<String, Object> j = parseJson(videoParamJson);
            return new int[] {intParam(j, "w"), intParam(j, "h"), intParam(j, "fps")};
        }

        private volatile String videoParamJson;

        private void logCtp(String dir, String topic, String json) {
            int n = ++ctpLogged;
            if (n <= 60 || n % 100 == 0) {
                Log.i(TAG, tag + ": Jieli: CTP " + dir + " " + topic
                        + (json.isEmpty() ? "" : " " + truncate(json, 200)));
            }
        }

        private void handle(Message m) {
            ctpReceived++;
            synchronized (topics) {
                Integer c = topics.get(m.topic);
                topics.put(m.topic, c == null ? 1 : c + 1);
                if (!KEEP_ALIVE.equals(m.topic)) {
                    lastNotifies.addLast(truncate(m.toString(), 200));
                    while (lastNotifies.size() > 8) lastNotifies.removeFirst();
                }
            }
            if (KEEP_ALIVE.equals(m.topic)) {
                keepAliveReplies++;
                if (keepAliveReplies <= 2) logCtp("←", m.topic, m.json);
                return;
            }
            logCtp("←", m.topic, m.json);
            Map<String, Object> j = parseJson(m.json);
            if (KEEP_ALIVE_INTERVAL.equals(m.topic)) {
                int timeout = intParam(j, "timeout");
                if (timeout > 0) {
                    announcedTimeoutMs = timeout;
                    keepAliveIntervalMs = keepAliveMs(timeout);
                    Log.i(TAG, tag + ": Jieli: keepalive every " + keepAliveIntervalMs
                            + " ms (timeout " + timeout + " ms)");
                }
            } else if (VIDEO_PARAM.equals(m.topic)) {
                int w = intParam(j, "w");
                int h = intParam(j, "h");
                if (w > 0 && h > 0) {
                    videoParamJson = m.json;
                    videoParam = w + "x" + h + " at " + intParam(j, "fps") + " fps, format "
                            + intParam(j, "format");
                }
            }
        }

        private void video(DatagramPacket p, Listener listener) {
            byte[] b = p.getData();
            int len = p.getLength();
            packets++;
            bytes += len;
            String from = p.getAddress().getHostAddress() + ":" + p.getPort();
            lastSource = from;
            synchronized (sources) {
                Integer c = sources.get(from);
                if (c != null || sources.size() < 8) {
                    sources.put(from, c == null ? 1 : c + 1);
                } else {
                    Integer o = sources.get("other");
                    sources.put("other", o == null ? 1 : o + 1);
                }
            }
            boolean rtp = len >= 12 && (b[0] & 0xC0) == 0x80;
            int pt = rtp ? b[1] & 0x7F : -1;
            boolean toy = p.getAddress().equals(address);
            if (firstPacket == null) {
                firstPacket = from + ", " + len + " bytes: " + FlowUfo.hex(b, 0, Math.min(len, 32));
            }
            int n = ++logged;
            if (n <= 8 || n % 1000 == 0) {
                Log.i(TAG, tag + ": Jieli: UDP " + url.rtpPort + " packet " + n + " from " + from
                        + (toy ? "" : " (not the toy: ignored)") + ", " + len + " bytes, "
                        + (rtp ? "RTP PT " + pt + " seq " + ((b[2] & 0xFF) << 8 | (b[3] & 0xFF))
                        + ((b[1] & 0x80) != 0 ? " marker" : "") : "not RTP")
                        + ": " + FlowUfo.hex(b, 0, Math.min(len, 32)));
            }
            if (!toy) {
                foreign++;
                return;
            }
            if (rtp) {
                synchronized (payloadTypes) {
                    Integer c = payloadTypes.get(pt);
                    payloadTypes.put(pt, c == null ? 1 : c + 1);
                }
            }
            if (rtp && (pt == PT_JPEG || pt == PT_H264)) {
                rtpPackets++;
                if ((b[1] & 0x80) != 0) rtpFrames++;
                if (pt == PT_H264 && !h264Logged) {
                    h264Logged = true;
                    Log.i(TAG, tag + ": Jieli: RTP payload type 96: played as H.264");
                }
                lastVideoAt = now();
                listener.onRtp(b, 0, len);
            } else if (!rtp && len >= 4 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8
                    && (b[len - 2] & 0xFF) == 0xFF && (b[len - 1] & 0xFF) == 0xD9) {
                // A whole JPEG without RTP: turbodrone takes those too
                rawJpegs++;
                lastVideoAt = now();
                listener.onJpeg(b, 0, len);
            } else if (rtp) {
                if (++otherRtp <= 3) {
                    Log.w(TAG, tag + ": Jieli: RTP payload type " + pt + " on UDP "
                            + url.rtpPort + ": not video we know, ignored");
                }
            } else {
                nonRtp++;
            }
        }

        private void diagnostic(int port, DatagramPacket p) {
            int count;
            synchronized (diagnostic) {
                Integer c = diagnostic.get(port);
                count = c == null ? 1 : c + 1;
                diagnostic.put(port, count);
                if (count == 1) {
                    diagnosticFirst.put(port, p.getAddress().getHostAddress() + ":" + p.getPort()
                            + ", " + p.getLength() + " bytes: "
                            + FlowUfo.hex(p.getData(), 0, Math.min(p.getLength(), 32)));
                }
            }
            if (count <= 5 || count % 1000 == 0) {
                byte[] b = p.getData();
                int len = p.getLength();
                boolean rtp = len >= 12 && (b[0] & 0xC0) == 0x80;
                Log.i(TAG, tag + ": Jieli: diagnostic UDP " + port + " packet " + count + " from "
                        + p.getAddress().getHostAddress() + ":" + p.getPort() + ", " + len
                        + " bytes" + (rtp ? ", RTP PT " + (b[1] & 0x7F) : "") + ": "
                        + FlowUfo.hex(b, 0, Math.min(len, 32)) + " (not played)");
            }
        }

        private String summary() {
            return String.format(Locale.ROOT, "CTP %d sent, %d received, %d keepalives;"
                            + " UDP %d: %d packets, %d RTP video, %d frames, %d not RTP,"
                            + " %d from others", ctpSent, ctpReceived, keepAlives, url.rtpPort,
                    packets, rtpPackets, rtpFrames, nonRtp, foreign);
        }

        /** For /stats.json. */
        Map<String, Object> stats() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", state);
            m.put("target", url.endpoint());
            m.put("network", networkTcp);
            if (hint != null) m.put("hint", hint);
            Map<String, Object> ctp = new LinkedHashMap<>();
            ctp.put("sent", ctpSent);
            ctp.put("received", ctpReceived);
            ctp.put("keepalives", keepAlives);
            ctp.put("keepalive_replies", keepAliveReplies);
            ctp.put("keepalive_ms", keepAliveIntervalMs);
            if (announcedTimeoutMs > 0) ctp.put("announced_timeout_ms", announcedTimeoutMs);
            ctp.put("open_requests", openRequests);
            if (reader.skipped > 0) ctp.put("skipped_bytes", reader.skipped);
            synchronized (topics) {
                ctp.put("topics", new LinkedHashMap<>(topics));
                ctp.put("last_notifies", new ArrayList<>(lastNotifies));
            }
            m.put("ctp", ctp);
            if (videoParam != null) m.put("video_param", videoParam);
            if (requested != null) m.put("requested", requested);
            Map<String, Object> rtp = new LinkedHashMap<>();
            rtp.put("port", url.rtpPort);
            rtp.put("network", networkUdp);
            rtp.put("packets", packets);
            rtp.put("bytes", bytes);
            rtp.put("video_packets", rtpPackets);
            rtp.put("frames", rtpFrames);
            if (rawJpegs > 0) rtp.put("raw_jpegs", rawJpegs);
            rtp.put("not_rtp", nonRtp);
            if (otherRtp > 0) rtp.put("other_rtp", otherRtp);
            if (foreign > 0) rtp.put("from_others", foreign);
            synchronized (payloadTypes) {
                Map<String, Object> pts = new LinkedHashMap<>();
                for (Map.Entry<Integer, Integer> e : payloadTypes.entrySet()) {
                    pts.put(String.valueOf(e.getKey()), e.getValue());
                }
                rtp.put("payload_types", pts);
            }
            if (firstPacket != null) rtp.put("first_packet", firstPacket);
            m.put("rtp", rtp);
            synchronized (sources) {
                m.put("sources", new LinkedHashMap<>(sources));
            }
            synchronized (diagnostic) {
                if (!diagnostic.isEmpty()) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    for (Map.Entry<Integer, Integer> e : diagnostic.entrySet()) {
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("packets", e.getValue());
                        one.put("first", diagnosticFirst.get(e.getKey()));
                        d.put(String.valueOf(e.getKey()), one);
                    }
                    m.put("diagnostic_ports", d);
                }
            }
            if (lastError != null) m.put("last_error", lastError);
            return m;
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }
}
