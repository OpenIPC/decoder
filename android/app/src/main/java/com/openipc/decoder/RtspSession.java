/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtspSession.java — one RTSP session with automatic reconnect
 *
 */

package com.openipc.decoder;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handshake (DESCRIBE, SETUP, PLAY), SDP parsing, the receive loop (RTP interleaved in the
 * RTSP connection, or over UDP, #67), a watchdog and reconnection with capped back-off. Used
 * by the single view (RtspClient) and by every Quad cell. Packets are delivered on the
 * network thread; channel 0 is video, channel 2 audio. A udp:// or rtp:// URL is not RTSP:
 * the camera pushes plain RTP to us (Majestic "outgoing", #68), see {@link RtpListener}.
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
    /** Lower bound for the keepalive interval (half the session timeout). */
    private static final int MIN_KEEPALIVE_MS = 250;
    static volatile int maxRetryMs = 30000;
    /** Auto transport: how long to wait for the first UDP video packet before using TCP. */
    static volatile long udpFirstPacketMs = 3000;

    interface Callback {
        /** "disconnected", "buffering" (after PLAY) or "connected" (first video packet). */
        void onStatus(String status);

        /** Why the last connection attempt failed, for display (#80). */
        default void onFailure(String message) {}

        /** Session parameters, before PLAY. Audio is NONE when it was not set up. */
        void onSdp(Sdp sdp);

        /**
         * One RTP packet: {@code length} bytes of {@code buf} starting at {@code offset}.
         * Returns true if a video packet was usable; only those feed the watchdog.
         */
        boolean onPacket(int channel, byte[] buf, int offset, int length);

        /**
         * One whole JPEG frame of an http:// MJPEG slot (#121), after onSdp with videoJpeg.
         * Returns true if it was usable; only those feed the watchdog.
         */
        default boolean onJpegFrame(byte[] buf, int offset, int length) { return false; }

        /**
         * What a flowufo:// slot waits for while it has no picture (#187), e.g. "Waiting for
         * the toy: probing 192.168.169.1:8800"; shown instead of the plain status.
         */
        default void onDetail(String text) {}
    }

    private final String tag;
    private final Callback callback;

    private volatile String url;
    private volatile String userAgent = "";
    private volatile boolean withAudio;
    private volatile Transport.Mode mode = Transport.Mode.TCP;
    /**
     * Auto transport: URLs whose UDP failed, with the time, kept for the process so that
     * a resume or a rebuilt grid does not try UDP again at once (#67).
     */
    static final Map<String, Long> udpFailedAt = new ConcurrentHashMap<>();
    static volatile long udpRetryMs = 10 * 60 * 1000;

    // Incremented by start()/stop(); threads and callbacks of an older generation do nothing
    private volatile int generation;
    private final Object lock = new Object();
    private Socket socket;
    /** UDP receiver of the current connection, else null. */
    private UdpReceiver udp;
    /** The UDP receive loop is running: closing ends it, the network thread closes the rest. */
    private boolean udpPlaying;
    /** Listener of a udp:// slot, else null. */
    private RtpListener listener;
    /** A udp:// slot is listening: silence is reported, never "fixed" by a rebind. */
    private volatile boolean listening;
    /** Client of a flowufo:// slot (#187), else null: it probes on its own when silent. */
    private volatile FlowUfo.Client ufo;
    /** Client of a jieli:// slot (#187), else null: it asks for the stream again on its own. */
    private volatile Jieli.Client jieli;
    /** Heartbeat of the RTSP connection now (#187), else null; counts of the earlier ones. */
    private volatile UdpHeartbeat heartbeat;
    private volatile int heartbeatsBefore;
    private volatile int heartbeatErrorsBefore;
    private volatile boolean heartbeatUsed;
    private volatile boolean heartbeatLogged;
    /** Degrees counter-clockwise the MJPEG frames of this connection are turned (#200). */
    private volatile int rotate;
    private volatile boolean rotateLogged;
    private volatile boolean rotateIgnoredLogged;
    private Thread networkThread;
    private Thread watchdogThread;

    private volatile boolean active;
    /** Keepalive state of the connection that is playing; sent by the watchdog thread. */
    private volatile Keepalive keepalive;
    private volatile long lastPacketMs;
    private volatile long streamBytes;
    private volatile long bitrateMarkNs;

    RtspSession(String tag, Callback callback) {
        this.tag = tag;
        this.callback = callback;
    }

    /** @param userAgent full header line, e.g. "User-Agent: x\r\n" */
    void configure(String url, String userAgent, boolean withAudio) {
        configure(url, userAgent, withAudio, Transport.Mode.TCP);
    }

    void configure(String url, String userAgent, boolean withAudio, Transport.Mode mode) {
        if (!java.util.Objects.equals(url, this.url)) {
            // Another camera: its rotation is logged again (#200), and the heartbeat counts
            // of the previous one leave /stats.json (Qodo on #188). A heartbeat still running
            // is closed by its connection, without adding to the new totals.
            rotateLogged = false;
            rotateIgnoredLogged = false;
            synchronized (lock) {
                heartbeat = null;
                heartbeatUsed = false;
                heartbeatsBefore = 0;
                heartbeatErrorsBefore = 0;
                heartbeatLogged = false;
            }
        }
        this.url = url;
        this.userAgent = userAgent != null ? userAgent : "";
        this.withAudio = withAudio;
        this.mode = mode != null ? mode : Transport.Mode.TCP;
    }

    /** Keyed by mode too: a failed multicast says nothing about unicast UDP. */
    private static boolean udpFailed(Transport.Mode mode, String url) {
        String key = mode + " " + url;
        Long at = udpFailedAt.get(key);
        if (at == null) return false;
        if (System.nanoTime() / 1_000_000 - at < udpRetryMs) return true;
        udpFailedAt.remove(key);
        return false;
    }

    private void markUdpFailed(Transport.Mode mode) {
        udpFailedAt.put(mode + " " + url, System.nanoTime() / 1_000_000);
    }

    /** Why the stream over UDP is not usable although packets arrive, or null (#113). */
    private volatile String udpBroken;

    /** RTSP is playing with RTP over UDP or multicast (not TCP, not a udp:// URL). */
    boolean playsOverUdp() { return udpPlaying; }

    /** A udp:// slot listening for a pushed stream (#133): UDP too, without RTSP. */
    boolean listens() { return listening; }

    /**
     * Packets arrive over UDP but the stream cannot be decoded (#113: key frames keep losing
     * a piece). With Auto or Multicast the session goes over to TCP, as when no video comes
     * at all; an explicit UDP setting stays.
     */
    void reportUdpBroken(String why) { udpBroken = why; }

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

    /**
     * Transport of the stream that plays now, as negotiated (#112, #115): "TCP", "UDP",
     * "multicast" for RTSP; "UDP push" / "multicast push" for udp:// URLs; null when not
     * playing. Unlike the mode of the slot, it shows a fallback (Auto → TCP).
     */
    private volatile String inUse;

    String transportInUse() { return inUse; }

    /** Stop without waiting: threads exit on their own and deliver no more callbacks. */
    void stop() {
        synchronized (lock) {
            inUse = null;
            generation++;
            active = false;
            keepalive = null;
            closeSocketLocked();
            if (networkThread != null) networkThread.interrupt();
            if (watchdogThread != null) watchdogThread.interrupt();
            networkThread = null;
            watchdogThread = null;
        }
    }

    /**
     * End the current connection. While playing over UDP only the receiver is closed: the
     * network thread then sends TEARDOWN, so the server stops sending to our ports.
     */
    private void closeSocketLocked() {
        if (listener != null) listener.close();
        if (ufo != null) ufo.close();
        if (jieli != null) jieli.close();
        if (heartbeat != null) heartbeat.close();
        if (udp != null) {
            udp.close();
            if (udpPlaying) return;
        }
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
                // stop() closes the socket on purpose: not a connection failure
                if (gen != generation) break;
                failures++;
                callback.onFailure(e.getMessage());
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
            Keepalive k = keepalive;
            long nowMs = System.nanoTime() / 1_000_000;
            if (active && k != null && nowMs - k.lastSentMs >= k.intervalMs) {
                // From this thread, so that an idle stream (reader blocked) still gets them
                k.pending = k.useOptions ? "OPTIONS" : "GET_PARAMETER";
                k.lastSentMs = nowMs;
                try {
                    send(k.conn, k.pending, k.url, "Session: " + k.session + "\r\n");
                } catch (IOException e) {
                    Log.w(TAG, tag + ": keepalive failed: " + e.getMessage());
                    synchronized (lock) {
                        if (gen == generation) closeSocketLocked();
                    }
                }
            }
            if (active && !listening && ufo == null && jieli == null
                    && System.nanoTime() / 1_000_000 - lastPacketMs > watchdogMs) {
                Log.w(TAG, tag + ": stream is inactive, reconnecting");
                active = false;
                synchronized (lock) {
                    if (gen == generation) closeSocketLocked();
                }
            }
            try {
                Thread.sleep(Math.min(250, watchdogMs / 4 + 1));
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** One session from connect to disconnect. Returns true if PLAY succeeded. */
    private boolean connect(int gen) throws IOException {
        // Only an RTSP MJPEG stream is turned (#200); the other transports draw it as it comes
        rotate = 0;
        if (RtpListener.Url.matches(url)) return listen(gen, RtpListener.Url.parse(url));
        if (HttpMjpeg.matches(url)) return httpMjpeg(gen, HttpMjpeg.Url.parse(url));
        if (FlowUfo.Url.matches(url)) return flowUfo(gen, FlowUfo.Url.parse(url));
        if (Jieli.Url.matches(url)) return jieli(gen, Jieli.Url.parse(url));
        Url u = Url.parse(url);
        Socket s = new Socket();
        synchronized (lock) {
            if (gen != generation) return false;
            socket = s;
        }
        Conn c = null;
        UdpReceiver rx = null;
        UdpHeartbeat hb = null;
        String session = null;
        try {
            s.connect(new InetSocketAddress(u.host, u.port), CONNECT_TIMEOUT_MS);
            if (u.heartbeatPort > 0) hb = startHeartbeat(gen, s, u.heartbeatPort);
            s.setTcpNoDelay(true);
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = s.getOutputStream();

            c = new Conn(in, out, new RtspAuth(u.userInfo));

            Response r = exchange(c, "DESCRIBE", u.requestUrl, "Accept: application/sdp\r\n");
            r.require("DESCRIBE");
            if (r.header("content-length") == null && r.body.isEmpty()) {
                // Some servers omit Content-Length: take what arrives until the line goes quiet
                r.body = readUntilIdle(s, in);
            }
            String base = r.header("content-base");
            Sdp sdp = Sdp.parse(r.body, base != null ? base : u.requestUrl);
            if (sdp.videoPt < 0) {
                // Nothing we could decode: fail so that the back-off applies, not a black view
                throw new IOException("No H.264/H.265/MJPEG video track in SDP");
            }

            Transport.Mode m = mode;
            boolean auto = m == Transport.Mode.AUTO;
            boolean mc = m == Transport.Mode.MULTICAST && !udpFailed(m, url);
            if (mc && !UdpReceiver.multicastSupported()) {
                Log.i(TAG, tag + ": multicast needs Android 8, using TCP");
                mc = false;
            }
            // Auto and Multicast fall back to TCP; UDP does not
            boolean fallback = auto || m == Transport.Mode.MULTICAST;
            if (m == Transport.Mode.UDP || (auto && !udpFailed(m, url)) || mc) {
                rx = new UdpReceiver(tag);
                synchronized (lock) {
                    if (gen != generation) return false;
                    udp = rx;
                }
            }

            int videoPort = -1;
            if (rx != null && !mc) {
                try {
                    videoPort = rx.addTrack(CHANNEL_VIDEO);
                } catch (IOException e) {
                    // No local UDP ports: with Auto the next attempt goes over TCP (Qodo on #128)
                    if (fallback) markUdpFailed(m);
                    throw e;
                }
            }
            String transport = rx == null ? Transport.tcpRequest(CHANNEL_VIDEO)
                    : mc ? Transport.multicastRequest() : Transport.udpRequest(videoPort);
            r = exchange(c, "SETUP", sdp.videoUrl, "Transport: " + transport + "\r\n");
            if (rx != null && fallback && !r.ok()) {
                // 461 Unsupported Transport and the like: Majestic answers a multicast
                // request with 400, XM cameras with 451
                Log.i(TAG, tag + ": " + (mc ? "multicast" : "UDP") + " SETUP failed ("
                        + r.code + "), using TCP");
                markUdpFailed(m);
                rx = dropUdp(rx);
                r = exchange(c, "SETUP", sdp.videoUrl,
                        "Transport: " + Transport.tcpRequest(CHANNEL_VIDEO) + "\r\n");
            }
            r.require("SETUP video");
            String sessionHeader = r.header("session");
            if (sessionHeader == null) throw new IOException("No Session header in SETUP reply");
            session = sessionHeader.split(";")[0].trim();
            long keepaliveMs = keepaliveInterval(sessionHeader);
            Transport reply = Transport.parse(r.header("transport"));
            if (rx != null && reply.tcp) {
                // Asked for UDP, answered with interleaved TCP: take what the server chose
                Log.i(TAG, tag + ": server chose TCP instead of UDP");
                rx = dropUdp(rx);
            } else if (rx != null && mc) {
                // A server may also answer with unicast (both Majestic and XM do when the
                // request carries client_port): not what was asked, use TCP next time
                if (!joinGroup(rx, CHANNEL_VIDEO, reply, s.getLocalAddress())) {
                    markUdpFailed(m);
                    throw new IOException("No usable multicast group in SETUP reply: "
                            + r.header("transport"));
                }
            } else if (rx != null && !udpReplyUsable(reply, videoPort)) {
                // Multicast or other ports than ours: nothing would reach the receiver
                if (auto) markUdpFailed(m);
                throw new IOException("Unusable UDP transport in SETUP reply: "
                        + r.header("transport"));
            }
            InetAddress audioSource = null;

            boolean audio = withAudio && sdp.audioUrl != null
                    && !AudioConfig.NONE.equals(sdp.audio.codec);
            if (audio) {
                int audioPort = rx != null && !mc ? rx.addTrack(CHANNEL_AUDIO) : -1;
                transport = rx == null ? Transport.tcpRequest(CHANNEL_AUDIO)
                        : mc ? Transport.multicastRequest() : Transport.udpRequest(audioPort);
                r = exchange(c, "SETUP", sdp.audioUrl,
                        "Transport: " + transport + "\r\n" +
                        "Session: " + session + "\r\n");
                Transport audioReply = Transport.parse(r.header("transport"));
                if (!r.ok() && auto && rx != null) {
                    // Auto: rather TCP for both tracks than UDP video without sound (#128)
                    markUdpFailed(m);
                    throw new IOException("audio SETUP over UDP failed (" + r.code
                            + "), using TCP");
                } else if (!r.ok()) {
                    // Not fatal: play video without audio
                    Log.w(TAG, tag + ": audio SETUP failed (" + r.code + "), no audio");
                    audio = false;
                } else if (rx != null && mc) {
                    if (!joinGroup(rx, CHANNEL_AUDIO, audioReply, s.getLocalAddress())) {
                        Log.w(TAG, tag + ": no usable multicast group for audio, no audio");
                        audio = false;
                    } else {
                        audioSource = sourceAddress(audioReply.source);
                    }
                } else if (rx != null && (audioReply.tcp
                        || !udpReplyUsable(audioReply, audioPort))) {
                    if (auto) {
                        markUdpFailed(m);
                        throw new IOException("audio not over UDP like video, using TCP");
                    }
                    // Video over UDP, audio elsewhere: the UDP loop cannot play it
                    Log.w(TAG, tag + ": audio not over UDP like video, no audio");
                    audio = false;
                } else if (rx != null) {
                    audioSource = sourceAddress(audioReply.source);
                }
            }
            if (!audio) sdp.audio = new AudioConfig();

            if (rx != null) {
                // Only the camera may send to our ports; "source" may be its own view of
                // its address (NAT), so the address we connected to is accepted as well
                if (mc) {
                    // The group already selects the stream, and a server may send it from
                    // another interface than the RTSP one: only a named source restricts it
                    rx.setSources(sourceAddress(reply.source), audioSource);
                } else {
                    rx.setSources(s.getInetAddress(), sourceAddress(reply.source), audioSource);
                }
            }

            if (gen != generation) return false;
            rotate = rotation(u.rotate, sdp.videoJpeg);
            callback.onSdp(sdp);

            // PLAY on the request URL, as before: accepted by the servers seen so far
            r = exchange(c, "PLAY", u.requestUrl, "Session: " + session + "\r\n");
            r.require("PLAY");

            synchronized (lock) {
                if (gen != generation) return false;
                lastPacketMs = System.nanoTime() / 1_000_000;
                streamBytes = 0;
                bitrateMarkNs = System.nanoTime();
                active = true;
                keepalive = new Keepalive(c, u.requestUrl, session, keepaliveMs);
                udpPlaying = rx != null;
                inUse = rx == null ? "TCP" : mc ? "multicast" : "UDP";
            }
            Log.i(TAG, tag + ": playing over " + inUse);
            status(gen, "buffering");
            s.setSoTimeout(0); // the watchdog handles silence
            if (rx != null) {
                receiveUdp(gen, c, rx, fallback, m);
            } else {
                receive(gen, c);
            }
            return true;
        } finally {
            synchronized (lock) {
                if (socket == s) {
                    socket = null;
                    inUse = null;
                }
                // A newer connection may own these by now
                if (udp == rx) {
                    udp = null;
                    udpPlaying = false;
                }
                Keepalive k = keepalive;
                if (k != null && k.conn == c) keepalive = null;
            }
            stopHeartbeat(hb);
            if (rx != null) {
                rx.close();
                if (session != null && !s.isClosed()) {
                    // Over UDP the server keeps sending until told to stop or until the
                    // session times out; cameras that allow one client need it gone (#67)
                    try {
                        send(c, "TEARDOWN", u.requestUrl, "Session: " + session + "\r\n");
                    } catch (IOException ignored) {}
                }
            }
            try {
                s.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Receive RTP pushed to us (#68). The codec follows the payload type unless the URL
     * forces one: 96 is H.264 and 97 H.265, and a switch between them on the camera is
     * followed. Returns true once listening, like a PLAY that succeeded.
     */
    /**
     * An http:// slot (#121): GET the URL and read its multipart/x-mixed-replace body, one
     * JPEG per part. Basic credentials go with the first request, Digest after a challenge.
     * Returns true once the stream was playing, like a PLAY that succeeded.
     */
    private boolean httpMjpeg(int gen, HttpMjpeg.Url u) throws IOException {
        RtspAuth auth = new RtspAuth(u.userInfo);
        for (int attempt = 0; attempt < 3; attempt++) {
            Socket s = new Socket();
            synchronized (lock) {
                if (gen != generation) return false;
                socket = s;
            }
            try {
                s.connect(new InetSocketAddress(u.host, u.port), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(CONNECT_TIMEOUT_MS);
                String host = (u.host.indexOf(':') >= 0 ? "[" + u.host + "]" : u.host)
                        + (u.port == 80 ? "" : ":" + u.port);
                String request = "GET " + u.path + " HTTP/1.1\r\n"
                        + "Host: " + host + "\r\n"
                        // userAgent is the whole header line already, CRLF included
                        + userAgent
                        + auth.header("GET", u.path)
                        + "Connection: close\r\n\r\n";
                OutputStream out = s.getOutputStream();
                out.write(request.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
                HttpMjpeg.Response r = HttpMjpeg.readResponse(in);
                if (r.status == 401) {
                    if (auth.onUnauthorized(r.challenges)) continue;
                    throw new IOException("401 Unauthorized");
                }
                if (r.status != 200) throw new IOException("HTTP " + r.status);
                String boundary = r.boundary();
                if (boundary == null) {
                    throw new IOException("Not an MJPEG stream (" + r.contentType + ")");
                }
                InputStream body = r.chunked ? new HttpMjpeg.Chunked(in) : in;
                synchronized (lock) {
                    if (gen != generation) return false;
                    lastPacketMs = System.nanoTime() / 1_000_000;
                    streamBytes = 0;
                    bitrateMarkNs = System.nanoTime();
                    active = true;
                    inUse = "HTTP";
                }
                Log.i(TAG, tag + ": MJPEG over HTTP from " + u.host + ":" + u.port);
                status(gen, "buffering");
                Sdp sdp = new Sdp();
                sdp.videoJpeg = true;
                sdp.videoPt = Sdp.PT_JPEG;
                callback.onSdp(sdp);
                // The watchdog handles silence from here on
                s.setSoTimeout(0);
                final boolean[] first = {true};
                HttpMjpeg.readParts(body, boundary, (buf, offset, length) -> {
                    if (gen != generation) return false;
                    streamBytes += length;
                    if (callback.onJpegFrame(buf, offset, length)) {
                        lastPacketMs = System.nanoTime() / 1_000_000;
                        if (first[0]) {
                            first[0] = false;
                            status(gen, "connected");
                        }
                    }
                    return true;
                });
                return true;
            } finally {
                synchronized (lock) {
                    if (socket == s) {
                        socket = null;
                        inUse = null;
                    }
                }
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
        throw new IOException("401 Unauthorized");
    }

    /**
     * The cooingdv heartbeat (#187): 01 01 to the camera's UDP port every second, from the
     * address of the RTSP connection, while the connection lasts. Logged at the first start.
     */
    private UdpHeartbeat startHeartbeat(int gen, Socket s, int port) {
        UdpHeartbeat h = new UdpHeartbeat(tag, s.getLocalAddress(),
                new InetSocketAddress(s.getInetAddress(), port));
        synchronized (lock) {
            if (gen != generation) return null;
            stopHeartbeat(heartbeat);
            heartbeat = h;
            heartbeatUsed = true;
        }
        try {
            h.start(!heartbeatLogged);
            heartbeatLogged = true;
        } catch (IOException e) {
            Log.w(TAG, tag + ": no heartbeat socket: " + e.getMessage());
        }
        return h;
    }

    /**
     * Ends the heartbeat {@code h} of a connection; when it is the current one its counts
     * go to the totals of the stats. A newer connection's heartbeat is left alone.
     */
    private void stopHeartbeat(UdpHeartbeat h) {
        if (h == null) return;
        h.close();
        synchronized (lock) {
            if (heartbeat != h) return;
            heartbeatsBefore += h.sent();
            heartbeatErrorsBefore += h.errors();
            heartbeat = null;
        }
    }

    /**
     * The turn for frames of this connection: {@code wanted} for MJPEG, 0 for H.264/H.265,
     * which have no rotation yet (#201). Logged once each.
     */
    private int rotation(int wanted, boolean jpeg) {
        if (wanted == 0) return 0;
        if (!jpeg) {
            if (!rotateIgnoredLogged) {
                rotateIgnoredLogged = true;
                Log.i(TAG, tag + ": rotate= only applies to MJPEG streams for now (#201)");
            }
            return 0;
        }
        if (!rotateLogged) {
            rotateLogged = true;
            Log.i(TAG, tag + ": MJPEG frames turned " + wanted + "\u00b0 counter-clockwise"
                    + " (#200; ?rotate=0 turns it off)");
        }
        return wanted;
    }

    /** Degrees counter-clockwise the MJPEG frames are turned now (#200), 0 for none. */
    int rotate() { return rotate; }

    /** Heartbeat statistics for /stats.json once an RTSP slot used one (#187), else null. */
    Map<String, Object> heartbeatStats() {
        if (!heartbeatUsed) return null;
        synchronized (lock) {
            return UdpHeartbeat.stats(heartbeat, heartbeatsBefore, heartbeatErrorsBefore);
        }
    }

    /** FLOW-UFO statistics for /stats.json while a flowufo:// slot runs (#187), else null. */
    Map<String, Object> flowUfoStats() {
        FlowUfo.Client c = ufo;
        return c != null ? c.stats() : null;
    }

    /**
     * A flowufo:// slot (#187): the toy drone's own protocol over UDP, JPEG frames to
     * onJpegFrame. The client probes until the drone answers and again after it went quiet,
     * so the watchdog leaves it alone. Returns true once it ran, like a PLAY that succeeded.
     */
    private boolean flowUfo(int gen, FlowUfo.Url u) throws IOException {
        FlowUfo.Client c = new FlowUfo.Client(tag, u, NetInfo::bindToNetworkOf);
        synchronized (lock) {
            if (gen != generation) return false;
            ufo = c;
            keepalive = null;
            lastPacketMs = System.nanoTime() / 1_000_000;
            streamBytes = 0;
            bitrateMarkNs = System.nanoTime();
            active = true;
        }
        try {
            Sdp sdp = new Sdp();
            sdp.videoJpeg = true;
            sdp.videoPt = Sdp.PT_JPEG;
            callback.onSdp(sdp);
            final boolean[] first = {true};
            final boolean[] started = {false};
            c.run(() -> active && gen == generation, new FlowUfo.Listener() {
                @Override
                public void onState(boolean receiving, String text) {
                    if (gen != generation) return;
                    if (receiving) {
                        inUse = "FLOW-UFO UDP";
                    } else {
                        inUse = null;
                        // The toy went quiet: say so, the next frame reports "connected" again
                        status(gen, started[0] ? "disconnected" : "buffering");
                        started[0] = true;
                        first[0] = true;
                    }
                    callback.onDetail(text);
                }

                @Override
                public void onFrame(byte[] buf, int length) {
                    if (gen != generation) return;
                    streamBytes += length;
                    if (callback.onJpegFrame(buf, 0, length)) {
                        lastPacketMs = System.nanoTime() / 1_000_000;
                        if (first[0]) {
                            first[0] = false;
                            status(gen, "connected");
                        }
                    }
                }
            });
            return true;
        } finally {
            synchronized (lock) {
                if (ufo == c) {
                    ufo = null;
                    inUse = null;
                }
            }
            c.close();
        }
    }

    /** Jieli statistics for /stats.json while a jieli:// slot runs (#187), else null. */
    Map<String, Object> jieliStats() {
        Jieli.Client c = jieli;
        return c != null ? c.stats() : null;
    }

    /** Raw bytes of a jieli:// slot for the debug page (#187), see Jieli.Client.raw(). */
    byte[] jieliRaw(String which) {
        Jieli.Client c = jieli;
        return c != null ? c.raw(which) : null;
    }

    /**
     * A jieli:// slot (#187): CTP over TCP asks the toy for the stream, which comes as RTP to
     * a fixed local UDP port: payload type 26 goes to the RTP/JPEG path, 96 to the H.264 one,
     * as on a udp:// slot. The client asks again when no video comes, so the watchdog leaves
     * it alone. Returns true once it ran, like a PLAY that succeeded.
     */
    private boolean jieli(int gen, Jieli.Url u) throws IOException {
        Jieli.Client c = new Jieli.Client(tag, u, NetInfo::bindToNetworkOf,
                NetInfo::bindToNetworkOf);
        synchronized (lock) {
            if (gen != generation) return false;
            jieli = c;
            keepalive = null;
            lastPacketMs = System.nanoTime() / 1_000_000;
            streamBytes = 0;
            bitrateMarkNs = System.nanoTime();
            active = true;
        }
        try {
            final int[] pt = {u.format == Jieli.FORMAT_H264 ? Jieli.PT_H264 : Jieli.PT_JPEG};
            rotate = rotation(u.rotate, true);
            callback.onSdp(jieliSdp(pt[0]));
            // onState comes from the CTP thread, the video from the UDP one
            final AtomicBoolean first = new AtomicBoolean(true);
            final AtomicBoolean received = new AtomicBoolean(false);
            final Object order = new Object();
            c.run(() -> active && gen == generation, new Jieli.Listener() {
                @Override
                public void onState(boolean receiving, String text) {
                    if (gen != generation) return;
                    // In order with usable() on the UDP thread: a packet that came in since
                    // the CTP thread saw the video stop wins (Qodo on #198)
                    synchronized (order) {
                        if (receiving) {
                            inUse = "Jieli RTP";
                            received.set(true);
                        } else {
                            if (c.videoAgeMs() < Jieli.quietMs) return;
                            inUse = null;
                            // Video that stopped says so; the next frame reports "connected"
                            status(gen, received.get() ? "disconnected" : "buffering");
                            first.set(true);
                        }
                        callback.onDetail(text);
                    }
                }

                @Override
                public void onRtp(byte[] buf, int offset, int length) {
                    if (gen != generation) return;
                    int type = buf[offset + 1] & 0x7F;
                    if (type != pt[0]) {
                        // The toy sends the other codec than asked for: follow it
                        pt[0] = type;
                        Log.i(TAG, tag + ": Jieli: video is "
                                + (type == Jieli.PT_H264 ? "H.264" : "JPEG") + " (RTP PT " + type
                                + ")");
                        callback.onSdp(jieliSdp(type));
                    }
                    streamBytes += length;
                    if (callback.onPacket(CHANNEL_VIDEO, buf, offset, length)) usable();
                }

                @Override
                public void onJpeg(byte[] buf, int offset, int length) {
                    if (gen != generation) return;
                    if (pt[0] != Jieli.PT_JPEG) {
                        pt[0] = Jieli.PT_JPEG;
                        callback.onSdp(jieliSdp(Jieli.PT_JPEG));
                    }
                    streamBytes += length;
                    if (callback.onJpegFrame(buf, offset, length)) usable();
                }

                private void usable() {
                    lastPacketMs = System.nanoTime() / 1_000_000;
                    synchronized (order) {
                        if (first.compareAndSet(true, false)) status(gen, "connected");
                    }
                }
            });
            return true;
        } finally {
            synchronized (lock) {
                if (jieli == c) {
                    jieli = null;
                    inUse = null;
                }
            }
            c.close();
        }
    }

    private static Sdp jieliSdp(int pt) {
        Sdp sdp = new Sdp();
        sdp.videoPt = pt;
        sdp.videoJpeg = pt == Jieli.PT_JPEG;
        return sdp;
    }

    private boolean listen(int gen, RtpListener.Url u) throws IOException {
        RtpListener l = new RtpListener(tag, u);
        synchronized (lock) {
            if (gen != generation) {
                l.close();
                return false;
            }
            listener = l;
            listening = true;
            // No RTSP here: nothing of an older connection may be sent by the watchdog
            keepalive = null;
            lastPacketMs = System.nanoTime() / 1_000_000;
            streamBytes = 0;
            bitrateMarkNs = System.nanoTime();
            active = true;
        }
        try {
            Log.i(TAG, tag + ": listening for RTP on UDP port " + u.port
                    + (u.multicast() ? ", group " + u.address.getHostAddress() : ""));
            status(gen, "buffering");
            final Sdp[] current = new Sdp[1];
            final boolean[] first = {true};
            l.run(() -> {
                if (!active || gen != generation) return false;
                // The sender went quiet: say so, keep listening (a rebind would only lose
                // packets), and report "connected" again with the next usable one
                if (!first[0] && System.nanoTime() / 1_000_000 - lastPacketMs > watchdogMs) {
                    first[0] = true;
                    inUse = null; // listening, but nothing is being pushed (Qodo on #117)
                    status(gen, "disconnected");
                }
                return true;
            }, (buf, offset, length) -> {
                if (gen != generation || length < 12) return;
                streamBytes += length;
                int pt = buf[offset + 1] & 0x7F;
                Sdp sdp = current[0];
                // Video is the forced type, or 96/97; anything else (audio) is ignored
                boolean video = u.pt >= 0 ? pt == u.pt : pt == Sdp.PT_H264 || pt == Sdp.PT_H265;
                if (video && (sdp == null || sdp.videoPt != pt)) {
                    sdp = new Sdp();
                    sdp.videoPt = pt;
                    sdp.videoH265 = u.h265(pt);
                    current[0] = sdp;
                    Log.i(TAG, tag + ": RTP payload type " + pt
                            + (sdp.videoH265 ? ", H.265" : ", H.264"));
                    callback.onSdp(sdp);
                }
                if (sdp == null || pt != sdp.videoPt) return; // not video, or none yet
                boolean usable = callback.onPacket(CHANNEL_VIDEO, buf, offset, length);
                if (usable) {
                    lastPacketMs = System.nanoTime() / 1_000_000;
                    if (first[0]) {
                        first[0] = false;
                        // In use only once something arrives: a quiet port is not a stream
                        inUse = u.multicast() ? "multicast push" : "UDP push";
                        status(gen, "connected");
                    }
                }
            });
            return true;
        } finally {
            synchronized (lock) {
                if (listener == l) {
                    listener = null;
                    listening = false;
                    inUse = null;
                }
            }
            l.close();
        }
    }

    /**
     * Join the group of a multicast SETUP reply (#69): a literal multicast destination and
     * a port are required. False when the reply is not usable or the join fails.
     */
    private boolean joinGroup(UdpReceiver rx, int channel, Transport reply, InetAddress local) {
        InetAddress group = sourceAddress(reply.destination);
        if (!reply.multicast || group == null || !group.isMulticastAddress()
                || reply.port == null || reply.port[0] <= 0) {
            return false;
        }
        try {
            rx.addMulticastTrack(channel, group, reply.port[0], local);
            Log.i(TAG, tag + ": joined " + group.getHostAddress() + ":" + reply.port[0]
                    + (reply.ttl >= 0 ? ", ttl " + reply.ttl : ""));
            return true;
        } catch (IOException e) {
            Log.w(TAG, tag + ": cannot join " + group.getHostAddress() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * A UDP reply we can receive: unicast, and the client ports, when given, are ours.
     * A missing Transport header is taken as agreement.
     */
    static boolean udpReplyUsable(Transport reply, int rtpPort) {
        if (reply.multicast) return false;
        return reply.clientPort == null || reply.clientPort[0] == rtpPort;
    }

    private UdpReceiver dropUdp(UdpReceiver rx) {
        rx.close();
        synchronized (lock) {
            if (udp == rx) udp = null;
        }
        return null;
    }

    /** The "source" of a Transport reply as an address; null unless it is a literal IP. */
    static InetAddress sourceAddress(String source) {
        if (source == null || !source.matches("[0-9.]+|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*")) {
            return null; // never resolve a host name here
        }
        try {
            return InetAddress.getByName(source);
        } catch (IOException e) {
            return null;
        }
    }

    /** Receive loop; keepalive replies are recognised between interleaved packets. */
    private void receive(int gen, Conn c) throws IOException {
        byte[] buf = new byte[65535 + 4];
        boolean first = true;
        while (active && gen == generation) {
            if (isResponseNext(c.in)) {
                handleReply(c, readResponse(c.in));
                continue;
            }

            int total = readInterleavedPacket(c.in, buf);
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

    /**
     * Receive over UDP on this thread while a second thread reads the RTSP replies
     * (keepalives). With {@code auto}, no video within udpFirstPacketMs switches the
     * URL to TCP for the next connection.
     */
    private void receiveUdp(int gen, Conn c, UdpReceiver rx, boolean auto, Transport.Mode m)
            throws IOException {
        AtomicReference<IOException> controlError = new AtomicReference<>();
        Thread control = new Thread(() -> {
            try {
                while (gen == generation) {
                    Response r = readResponse(c.in);
                    handleReply(c, r);
                }
            } catch (IOException e) {
                controlError.set(e);
            }
            // Ends the receive loop; harmless when it already ended
            rx.close();
        }, tag + "-ctl");
        control.setDaemon(true);
        control.start();

        final boolean[] first = {true};
        long playMs = System.nanoTime() / 1_000_000;
        udpBroken = null;
        rx.run(() -> {
            if (!active || gen != generation) return false;
            String broken = udpBroken;
            if (broken != null && (auto || m == Transport.Mode.MULTICAST)) {
                Log.w(TAG, tag + ": " + broken + " over " + (m == Transport.Mode.MULTICAST
                        ? "multicast" : "UDP") + ", using TCP");
                udpBroken = null;
                markUdpFailed(m);
                return false;
            }
            if (auto && first[0]
                    && System.nanoTime() / 1_000_000 - playMs > udpFirstPacketMs) {
                Log.w(TAG, tag + ": no video over " + (m == Transport.Mode.MULTICAST
                        ? "multicast" : "UDP") + ", using TCP");
                markUdpFailed(m);
                return false;
            }
            return true;
        }, (channel, buf, offset, length) -> {
            if (gen != generation) return;
            streamBytes += length;
            boolean usable = callback.onPacket(channel, buf, offset, length);
            if (channel == CHANNEL_VIDEO && usable) {
                lastPacketMs = System.nanoTime() / 1_000_000;
                if (first[0]) {
                    first[0] = false;
                    status(gen, "connected");
                }
            }
        });
        IOException e = controlError.get();
        if (e != null && gen == generation && active) {
            throw new IOException("RTSP connection lost: " + e.getMessage(), e);
        }
    }

    /** A reply that arrived while playing: keepalive answers and session errors. */
    private void handleReply(Conn c, Response r) throws IOException {
        Keepalive k = keepalive;
        if (r.code == 401) {
            if (!c.auth.onUnauthorized(r.authenticate)) {
                throw new IOException("Keepalive rejected: authentication failed");
            }
            if (k != null) k.lastSentMs = 0; // repeat it now with the new nonce
        } else if (r.code == 454) {
            throw new IOException("Session expired on the server");
        } else if (!r.ok() && k != null && "GET_PARAMETER".equals(k.pending)) {
            Log.i(TAG, tag + ": GET_PARAMETER rejected (" + r.code + "), using OPTIONS");
            k.useOptions = true;
        }
    }

    private static final class Keepalive {
        final Conn conn;
        final String url;
        final String session;
        final long intervalMs;
        volatile long lastSentMs = System.nanoTime() / 1_000_000;
        volatile boolean useOptions;
        volatile String pending;

        Keepalive(Conn conn, String url, String session, long intervalMs) {
            this.conn = conn;
            this.url = url;
            this.session = session;
            this.intervalMs = intervalMs;
        }
    }

    /** True if the next bytes are an RTSP reply ("RTSP/") rather than interleaved data. */
    static boolean isResponseNext(BufferedInputStream in) throws IOException {
        in.mark(5);
        try {
            if (in.read() != 'R') return false;
            return in.read() == 'T' && in.read() == 'S' && in.read() == 'P' && in.read() == '/';
        } finally {
            in.reset();
        }
    }

    /** Half the server's session timeout (default 60 s), at least 250 ms. */
    static long keepaliveInterval(String sessionHeader) {
        int timeoutSec = 60;
        for (String part : sessionHeader.split(";")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "timeout=", 0, 8)) {
                try {
                    int t = Integer.parseInt(p.substring(8).trim());
                    if (t > 0) timeoutSec = t;
                } catch (NumberFormatException ignored) {}
            }
        }
        return Math.max(MIN_KEEPALIVE_MS, timeoutSec * 1000L / 2);
    }

    // ====================== Protocol helpers ======================

    static final class Response {
        int code;
        final Map<String, String> headers = new HashMap<>();
        /** Every WWW-Authenticate value; a server may offer several schemes. */
        final List<String> authenticate = new ArrayList<>();
        String body = "";

        boolean ok() { return code >= 200 && code < 300; }

        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }

        void require(String what) throws IOException {
            if (!ok()) throw new IOException(what + " failed: " + code);
        }
    }

    /** Connection state for one session: streams, CSeq counter and authentication. */
    private final class Conn {
        final BufferedInputStream in;
        final OutputStream out;
        final RtspAuth auth;
        int cseq = 1;

        Conn(BufferedInputStream in, OutputStream out, RtspAuth auth) {
            this.in = in;
            this.out = out;
            this.auth = auth;
        }
    }

    /** Write one request. Synchronized: keepalives are sent from the watchdog thread. */
    private void send(Conn c, String method, String target, String headers) throws IOException {
        synchronized (c) {
            String req = method + " " + target + " RTSP/1.0\r\n" +
                    "CSeq: " + c.cseq++ + "\r\n" + c.auth.header(method, target) + userAgent +
                    headers + "\r\n";
            c.out.write(req.getBytes(StandardCharsets.UTF_8));
            c.out.flush();
        }
    }

    /** Send a request and read its reply, answering one authentication challenge. */
    private Response exchange(Conn c, String method, String target, String headers)
            throws IOException {
        send(c, method, target, headers);
        Response r = readResponse(c.in);
        if (r.code == 401 && c.auth.onUnauthorized(r.authenticate)) {
            send(c, method, target, headers);
            r = readResponse(c.in);
        }
        if (r.code == 401) {
            throw new IOException(method + ": authentication failed"
                    + (c.auth.hasCredentials() ? "" : " (no user:password in the URL)"));
        }
        return r;
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
        Log.i(TAG, redact(status));
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
            Log.d(TAG, redact(line));
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                r.headers.put(name, value);
                if (name.equals("www-authenticate")) r.authenticate.add(value);
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

    private static final java.util.regex.Pattern USERINFO =
            java.util.regex.Pattern.compile("(?i)(rtsps?|https?)://[^/@\\s]*@");
    private static final java.util.regex.Pattern PASSWORD_PARAM =
            java.util.regex.Pattern.compile("(?i)(pass(?:word)?|pwd)=[^_&;/?\\s]*");

    /**
     * Text safe for logs: "user:password@" and password parameters (some cameras put
     * them in the URL path, e.g. user=admin_password=...) are replaced (#55).
     */
    static String redact(String s) {
        if (s == null) return null;
        s = USERINFO.matcher(s).replaceAll("$1://***@");
        return PASSWORD_PARAM.matcher(s).replaceAll("$1=***");
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
        return rtpPayloadEnd(data, 0, length);
    }

    /** Payload end relative to {@code offset}: length minus RTP padding, or -1 if malformed. */
    static int rtpPayloadEnd(byte[] data, int offset, int length) {
        if (length < 1) return -1;
        if ((data[offset] & 0x20) == 0) return length;
        int pad = data[offset + length - 1] & 0xFF;
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
        /**
         * UDP port for the cooingdv heartbeat (#187), or 0 for none: 7099 for
         * rtsp://{host}:7070/webcam, else ?heartbeat={port}; ?heartbeat=off turns it off. The
         * option is ours: it is taken out of the URL sent to the camera.
         */
        int heartbeatPort;
        /**
         * Degrees counter-clockwise to turn MJPEG frames when drawing them (#200): 270 for
         * rtsp://{host}:7070/webcam, whose toy camera is mounted sideways, else 0;
         * ?rotate=0|90|180|270 overrides it. Ours as well: not sent to the camera.
         */
        int rotate;

        static Url parse(String url) throws IOException {
            if (url == null) throw new IOException("Camera slot not configured");
            url = url.trim();
            int schemeEnd = url.indexOf("://");
            if (schemeEnd <= 0) throw new IOException("Invalid RTSP URL: " + redact(url));
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
                // A decoded CR/LF would end the Authorization header and inject new ones
                for (int i = 0; i < u.userInfo.length(); i++) {
                    if (u.userInfo.charAt(i) < 0x20 || u.userInfo.charAt(i) == 0x7F) {
                        throw new IOException("Invalid character in user name or password");
                    }
                }
            }
            String hostPort = authority;
            if (hostPort.startsWith("[")) {
                int close = hostPort.indexOf(']');
                if (close < 0) throw new IOException("Invalid IPv6 host: " + redact(url));
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
            rest = takeOptions(u, rest);
            u.requestUrl = scheme + "://" + authority + rest;
            return u;
        }

        /**
         * Sets heartbeatPort and rotate from path and query; returns them without our
         * options.
         */
        private static String takeOptions(Url u, String rest) throws IOException {
            int q = rest.indexOf('?');
            String path = q >= 0 ? rest.substring(0, q) : rest;
            if (u.port == 7070 && (path.equalsIgnoreCase("/webcam")
                    || path.equalsIgnoreCase("/webcam/"))) {
                // The cooingdv toys (#187): heartbeat, and the K200's sideways camera (#200)
                u.heartbeatPort = UdpHeartbeat.DEFAULT_PORT;
                u.rotate = 270;
            }
            if (q < 0) return rest;
            StringBuilder kept = new StringBuilder();
            boolean found = false;
            for (String param : rest.substring(q + 1).split("&", -1)) {
                int eq = param.indexOf('=');
                String name = (eq < 0 ? param : param.substring(0, eq)).toLowerCase(Locale.ROOT);
                if (!name.equals("heartbeat") && !name.equals("rotate")) {
                    if (kept.length() > 0) kept.append('&');
                    kept.append(param);
                    continue;
                }
                found = true;
                String v = eq < 0 ? "" : param.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                if (name.equals("rotate")) {
                    u.rotate = parseRotate(v);
                } else if (v.equals("off") || v.equals("0")) {
                    u.heartbeatPort = 0;
                } else {
                    try {
                        u.heartbeatPort = Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        u.heartbeatPort = -1;
                    }
                    if (u.heartbeatPort <= 0 || u.heartbeatPort > 65535) {
                        throw new IOException("Invalid heartbeat port: " + v
                                + " (a UDP port, e.g. heartbeat=7099, or heartbeat=off)");
                    }
                }
            }
            // Untouched unless our option was there
            if (!found) return rest;
            return kept.length() > 0 ? path + "?" + kept : path;
        }

        /** ?rotate= in degrees counter-clockwise: 0, 90, 180 or 270 (#200). */
        static int parseRotate(String v) throws IOException {
            switch (v) {
                case "0": return 0;
                case "90": return 90;
                case "180": return 180;
                case "270": return 270;
                default:
                    throw new IOException("Invalid rotate: " + v
                            + " (degrees counter-clockwise: rotate=0, 90, 180 or 270)");
            }
        }

        private static int parsePort(String s, String url) throws IOException {
            if (s.isEmpty()) return 554;
            try {
                int p = Integer.parseInt(s);
                if (p > 0 && p < 65536) return p;
            } catch (NumberFormatException ignored) {}
            throw new IOException("Invalid port in URL: " + redact(url));
        }

        static String percentDecode(String s) {
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
