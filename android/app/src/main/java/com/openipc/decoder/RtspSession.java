/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtspSession.java — one RTSP-over-TCP session with automatic reconnect
 *
 */

package com.openipc.decoder;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Handshake (DESCRIBE, SETUP, PLAY), SDP parsing, the interleaved receive loop, a watchdog
 * and reconnection with capped back-off. Used by the single view (RtspClient) and by every
 * Quad cell. Packets are delivered on the network thread; channel 0 is video, channel 2 audio.
 */
class RtspSession {
    private static final String TAG = "OpenIPCDecoder";

    static final int CHANNEL_VIDEO = 0;
    static final int CHANNEL_AUDIO = 2;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;
    // Not final so unit tests can shorten them
    static volatile long watchdogMs = 8000;
    static volatile int minRetryMs = 1000;
    static volatile int maxRetryMs = 30000;

    interface Callback {
        /** "disconnected", "buffering" (after PLAY) or "connected" (first video packet). */
        void onStatus(String status);

        /** Session parameters, before PLAY. Audio is NONE when it was not set up. */
        void onSdp(Sdp sdp);

        /**
         * One RTP packet: {@code length} bytes of {@code buf} starting at {@code offset}.
         * Returns true if a video packet was usable; only those feed the watchdog.
         */
        boolean onPacket(int channel, byte[] buf, int offset, int length);
    }

    private final String tag;
    private final Callback callback;

    private volatile String url;
    private volatile String userAgent = "";
    private volatile boolean withAudio;

    // Incremented by start()/stop(); threads and callbacks of an older generation do nothing
    private volatile int generation;
    private final Object lock = new Object();
    private Socket socket;
    private Thread networkThread;
    private Thread watchdogThread;

    private volatile boolean active;
    private volatile long lastPacketMs;
    private volatile long streamBytes;
    private volatile long bitrateMarkNs;

    RtspSession(String tag, Callback callback) {
        this.tag = tag;
        this.callback = callback;
    }

    /** @param userAgent full header line, e.g. "User-Agent: x\r\n" */
    void configure(String url, String userAgent, boolean withAudio) {
        this.url = url;
        this.userAgent = userAgent != null ? userAgent : "";
        this.withAudio = withAudio;
    }

    boolean isActive() { return active; }
    long getStreamBytes() { return streamBytes; }
    long getBitrateMarkNs() { return bitrateMarkNs; }

    void start() {
        synchronized (lock) {
            if (networkThread != null) return;
            final int gen = ++generation;
            networkThread = new Thread(() -> networkLoop(gen), tag + "-net");
            watchdogThread = new Thread(() -> watchdogLoop(gen), tag + "-wd");
            networkThread.start();
            watchdogThread.start();
        }
    }

    /** Stop without waiting: threads exit on their own and deliver no more callbacks. */
    void stop() {
        synchronized (lock) {
            generation++;
            active = false;
            closeSocketLocked();
            if (networkThread != null) networkThread.interrupt();
            if (watchdogThread != null) watchdogThread.interrupt();
            networkThread = null;
            watchdogThread = null;
        }
    }

    private void closeSocketLocked() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void status(int gen, String status) {
        if (gen == generation) callback.onStatus(status);
    }

    private void networkLoop(int gen) {
        int retryMs = minRetryMs;
        int failures = 0;
        while (gen == generation) {
            boolean played = false;
            try {
                played = connect(gen);
            } catch (Exception e) {
                failures++;
                if (failures <= 3 || failures % 10 == 0) {
                    Log.w(TAG, tag + ": RTSP connection failed (" + failures + "): "
                            + e.getMessage());
                }
            }
            if (gen != generation) break;
            active = false;
            status(gen, "disconnected");

            // A session that reached PLAY resets the back-off; never give up
            if (played) {
                retryMs = minRetryMs;
                failures = 0;
            }
            try {
                Thread.sleep(retryDelay(retryMs));
            } catch (InterruptedException e) {
                break;
            }
            if (!played) retryMs = Math.min(retryMs * 2, maxRetryMs);
        }
    }

    /** Back-off delay with jitter, never beyond maxRetryMs. */
    static long retryDelay(int retryMs) {
        return Math.min(maxRetryMs, retryMs + (long) (Math.random() * retryMs / 2));
    }

    private void watchdogLoop(int gen) {
        while (gen == generation) {
            if (active && System.nanoTime() / 1_000_000 - lastPacketMs > watchdogMs) {
                Log.w(TAG, tag + ": stream is inactive, reconnecting");
                active = false;
                synchronized (lock) {
                    if (gen == generation) closeSocketLocked();
                }
            }
            try {
                Thread.sleep(Math.min(1000, watchdogMs / 4 + 1));
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** One session from connect to disconnect. Returns true if PLAY succeeded. */
    private boolean connect(int gen) throws IOException {
        Url u = Url.parse(url);
        Socket s = new Socket();
        synchronized (lock) {
            if (gen != generation) return false;
            socket = s;
        }
        try {
            s.connect(new InetSocketAddress(u.host, u.port), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = s.getOutputStream();

            String auth = u.userInfo != null
                    ? "Authorization: Basic " + Base64.encodeToString(
                            u.userInfo.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\r\n"
                    : "";
            String common = auth + userAgent;
            int cseq = 1;

            Response r = request(in, out, "DESCRIBE", u.requestUrl, cseq++,
                    common + "Accept: application/sdp\r\n");
            r.require("DESCRIBE");
            if (r.header("content-length") == null && r.body.isEmpty()) {
                // Some servers omit Content-Length: take what arrives until the line goes quiet
                r.body = readUntilIdle(s, in);
            }
            String base = r.header("content-base");
            Sdp sdp = Sdp.parse(r.body, base != null ? base : u.requestUrl);
            if (sdp.videoPt < 0) {
                // Nothing we could decode: fail so that the back-off applies, not a black view
                throw new IOException("No H.264/H.265 video track in SDP");
            }

            r = request(in, out, "SETUP", sdp.videoUrl, cseq++,
                    common + "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
            r.require("SETUP video");
            String session = r.header("session");
            if (session == null) throw new IOException("No Session header in SETUP reply");
            session = session.split(";")[0].trim();

            boolean audio = withAudio && sdp.audioUrl != null
                    && !AudioConfig.NONE.equals(sdp.audio.codec);
            if (audio) {
                r = request(in, out, "SETUP", sdp.audioUrl, cseq++, common +
                        "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n" +
                        "Session: " + session + "\r\n");
                if (!r.ok()) {
                    // Not fatal: play video without audio
                    Log.w(TAG, tag + ": audio SETUP failed (" + r.code + "), no audio");
                    audio = false;
                }
            }
            if (!audio) sdp.audio = new AudioConfig();

            if (gen != generation) return false;
            callback.onSdp(sdp);

            // PLAY on the request URL, as before: accepted by the servers seen so far
            r = request(in, out, "PLAY", u.requestUrl, cseq++,
                    common + "Session: " + session + "\r\n");
            r.require("PLAY");

            synchronized (lock) {
                if (gen != generation) return false;
                lastPacketMs = System.nanoTime() / 1_000_000;
                streamBytes = 0;
                bitrateMarkNs = System.nanoTime();
                active = true;
            }
            status(gen, "buffering");
            s.setSoTimeout(0); // the watchdog handles silence
            receive(gen, in);
            return true;
        } finally {
            synchronized (lock) {
                if (socket == s) socket = null;
            }
            try {
                s.close();
            } catch (IOException ignored) {}
        }
    }

    private void receive(int gen, BufferedInputStream in) throws IOException {
        byte[] buf = new byte[65535 + 4];
        boolean first = true;
        while (active && gen == generation) {
            int total = readInterleavedPacket(in, buf);
            if (total < 0) throw new IOException("Connection closed by server");
            streamBytes += total;
            int channel = buf[1] & 0xFF;
            if (gen != generation) break;
            boolean usable = callback.onPacket(channel, buf, 4, total - 4);
            // Only usable video keeps the session alive and counts as connected
            if (channel == CHANNEL_VIDEO && usable) {
                lastPacketMs = System.nanoTime() / 1_000_000;
                if (first) {
                    first = false;
                    status(gen, "connected");
                }
            }
        }
    }

    // ====================== Protocol helpers ======================

    static final class Response {
        int code;
        final Map<String, String> headers = new HashMap<>();
        String body = "";

        boolean ok() { return code >= 200 && code < 300; }

        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }

        void require(String what) throws IOException {
            if (!ok()) throw new IOException(what + " failed: " + code);
        }
    }

    private Response request(InputStream in, OutputStream out, String method, String target,
                             int cseq, String headers) throws IOException {
        String req = method + " " + target + " RTSP/1.0\r\n" +
                "CSeq: " + cseq + "\r\n" + headers + "\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();
        return readResponse(in);
    }

    /**
     * Read one RTSP response: status line, headers and a Content-Length body. Interleaved
     * RTP frames that some servers send before the PLAY reply are skipped.
     */
    static Response readResponse(InputStream in) throws IOException {
        if (in.markSupported()) {
            in.mark(1);
            while (in.read() == '$') {
                int channel = in.read();
                int hi = in.read();
                int lo = in.read();
                if ((channel | hi | lo) < 0) throw new IOException("Connection closed");
                skipFully(in, (hi << 8) | lo);
                in.mark(1);
            }
            in.reset();
        }

        String status = readLine(in);
        if (status == null) throw new IOException("Server closed connection during handshake");
        Log.i(TAG, status);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("RTSP/")) {
            throw new IOException("Malformed RTSP response: " + status);
        }
        Response r = new Response();
        try {
            r.code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed RTSP status code: " + status);
        }

        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            Log.d(TAG, line);
            int colon = line.indexOf(':');
            if (colon > 0) {
                r.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        String length = r.header("content-length");
        if (length != null) {
            int len;
            try {
                len = Integer.parseInt(length);
            } catch (NumberFormatException e) {
                throw new IOException("Bad Content-Length: " + length);
            }
            if (len < 0 || len > 65536) throw new IOException("Bad Content-Length: " + len);
            ByteArrayOutputStream body = new ByteArrayOutputStream(len);
            byte[] chunk = new byte[Math.min(len, 4096)];
            while (body.size() < len) {
                int n = in.read(chunk, 0, Math.min(chunk.length, len - body.size()));
                if (n < 0) throw new IOException("Truncated response body");
                body.write(chunk, 0, n);
            }
            r.body = body.toString("UTF-8");
        }
        return r;
    }

    /** Read a body of unknown length: everything until 300 ms of silence, at most 64 KiB. */
    private static String readUntilIdle(Socket s, InputStream in) throws IOException {
        int timeout = s.getSoTimeout();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        s.setSoTimeout(300);
        try {
            while (body.size() < 65536) {
                int n = in.read(chunk, 0, Math.min(chunk.length, 65536 - body.size()));
                if (n < 0) break;
                body.write(chunk, 0, n);
            }
        } catch (java.net.SocketTimeoutException ignored) {
            // Line went quiet: the body is complete
        } finally {
            s.setSoTimeout(timeout);
        }
        return body.toString("UTF-8");
    }

    private static void skipFully(InputStream in, int len) throws IOException {
        while (len > 0) {
            long n = in.skip(len);
            if (n <= 0) {
                if (in.read() < 0) throw new IOException("Connection closed");
                n = 1;
            }
            len -= (int) n;
        }
    }

    /** Read a CRLF/LF-terminated line byte by byte; null at end of stream. */
    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') {
                if (sb.length() >= 8192) throw new IOException("RTSP header line too long");
                sb.append((char) c);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** Read one RTP-over-TCP interleaved packet into pktBuf; -1 on a clean end of stream. */
    static int readInterleavedPacket(BufferedInputStream input, byte[] pktBuf) throws IOException {
        while (true) {
            int b0 = input.read();
            if (b0 < 0) return -1;
            if (b0 != '$') continue;
            int b1 = input.read();
            int b2 = input.read();
            int b3 = input.read();
            if ((b1 | b2 | b3) < 0) throw new IOException("Truncated interleaved header");
            int len = (b2 << 8) | b3;
            if (len + 4 > pktBuf.length) throw new IOException("RTP packet too large: " + len);
            pktBuf[0] = '$';
            pktBuf[1] = (byte) b1;
            pktBuf[2] = (byte) b2;
            pktBuf[3] = (byte) b3;
            int offset = 4;
            int remaining = len;
            while (remaining > 0) {
                int n = input.read(pktBuf, offset, remaining);
                if (n < 0) throw new IOException("Truncated RTP body");
                remaining -= n;
                offset += n;
            }
            return len + 4;
        }
    }

    /** Length of the RTP header including CSRC list and extension, or -1 if malformed. */
    static int rtpHeaderLength(byte[] data, int length) {
        return rtpHeaderLength(data, 0, length);
    }

    static int rtpHeaderLength(byte[] data, int offset, int length) {
        if (length < 12 || (data[offset] & 0xC0) != 0x80) return -1;
        int len = 12 + 4 * (data[offset] & 0x0F);
        if ((data[offset] & 0x10) != 0) {
            if (len + 4 > length) return -1;
            int extWords = ((data[offset + len + 2] & 0xFF) << 8) | (data[offset + len + 3] & 0xFF);
            len += 4 + 4 * extWords;
        }
        return len <= length ? len : -1;
    }

    /** Payload end: packet length minus RTP padding, or -1 if malformed. */
    static int rtpPayloadEnd(byte[] data, int length) {
        if ((data[0] & 0x20) == 0) return length;
        int pad = data[length - 1] & 0xFF;
        return pad <= length ? length - pad : -1;
    }

    // ====================== URL ======================

    /** rtsp://[user[:password]@]host[:port][/path][?query], parsed leniently. */
    static final class Url {
        String host;
        int port = 554;
        /** Decoded "user:password", or null. */
        String userInfo;
        /** URL without credentials, used in requests. */
        String requestUrl;

        static Url parse(String url) throws IOException {
            if (url == null) throw new IOException("Camera slot not configured");
            url = url.trim();
            int schemeEnd = url.indexOf("://");
            if (schemeEnd <= 0) throw new IOException("Invalid RTSP URL: " + url);
            String scheme = url.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            int authStart = schemeEnd + 3;
            int pathStart = authStart;
            while (pathStart < url.length() && "/?#".indexOf(url.charAt(pathStart)) < 0) {
                pathStart++;
            }
            String authority = url.substring(authStart, pathStart);
            String rest = url.substring(pathStart);
            int fragment = rest.indexOf('#');
            if (fragment >= 0) rest = rest.substring(0, fragment);

            Url u = new Url();
            // The last '@' separates credentials; passwords may contain '@'
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                u.userInfo = percentDecode(authority.substring(0, at));
                authority = authority.substring(at + 1);
            }
            String hostPort = authority;
            if (hostPort.startsWith("[")) {
                int close = hostPort.indexOf(']');
                if (close < 0) throw new IOException("Invalid IPv6 host: " + url);
                u.host = hostPort.substring(1, close);
                hostPort = hostPort.substring(close + 1);
                if (hostPort.startsWith(":")) u.port = parsePort(hostPort.substring(1), url);
            } else {
                int colon = hostPort.lastIndexOf(':');
                if (colon >= 0) {
                    u.port = parsePort(hostPort.substring(colon + 1), url);
                    hostPort = hostPort.substring(0, colon);
                }
                u.host = hostPort;
            }
            if (u.host.isEmpty()) throw new IOException("Invalid RTSP URL: host is missing");
            u.requestUrl = scheme + "://" + authority + rest;
            return u;
        }

        private static int parsePort(String s, String url) throws IOException {
            if (s.isEmpty()) return 554;
            try {
                int p = Integer.parseInt(s);
                if (p > 0 && p < 65536) return p;
            } catch (NumberFormatException ignored) {}
            throw new IOException("Invalid port in URL: " + url);
        }

        private static String percentDecode(String s) {
            if (s.indexOf('%') < 0) return s;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()
                        && Character.digit(s.charAt(i + 1), 16) >= 0
                        && Character.digit(s.charAt(i + 2), 16) >= 0) {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                } else {
                    byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                    out.write(b, 0, b.length);
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
