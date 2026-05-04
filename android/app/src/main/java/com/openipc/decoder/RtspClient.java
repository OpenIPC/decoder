/*
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * RtspClient.java — RTSP handshake and RTP-over-TCP streaming client
 *
 */

package com.openipc.decoder;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class RtspClient {
    private static final String TAG = "OpenIPCDecoder";

    static final int RTP_PT_H265 = 97;
    static final int RTP_PT_H264 = 96;
    static final int RTP_PT_PCMU_DEFAULT = 100;

    private static final long WATCHDOG_MS = 3000;

    private volatile String host;
    private volatile String userAgent;
    private volatile int listenerGen;
    private volatile boolean listener;
    private volatile boolean activeStream;
    private volatile long lastFrame;

    private FramePool framePool;
    private NalAssembler nalAssembler;
    private BlockingQueue<Frame> nalQueue;
    private Socket mTcpSocket;
    private ExecutorService executor;

    // Decoder state
    private volatile boolean codecH265;
    private volatile String audioCodec = "PCM";
    private volatile int audioSampleRate = 8000;
    private volatile boolean audioBigEndian = true;
    private volatile int audioPt = RTP_PT_PCMU_DEFAULT;

    // Quality tracking
    private long lastQualityUpdateTime;
    private long lastRtpTimestamp = -1;
    private long lastRtpArrivalNs = -1;
    private long jitterAccumulator;
    private int jitterSampleCount;
    private int lastUnknownPayload = -1;

    // Bitrate tracking
    private volatile long streamBytes;
    private volatile long bitrateMarkNs;

    // Audio callback — set by owner
    private AudioManager audioManager;

    interface Listener {
        void onStatusChanged(String status);
        void onCodecChanged(boolean codecH265, String audioCodec, int audioSampleRate,
                            boolean audioBigEndian, int audioPt);
        void onJitterSample(int avgJitter);
    }

    private Listener listenerCb;

    RtspClient(FramePool framePool, NalAssembler nalAssembler,
               BlockingQueue<Frame> nalQueue) {
        this.framePool = framePool;
        this.nalAssembler = nalAssembler;
        this.nalQueue = nalQueue;
    }

    void setListener(Listener cb) { this.listenerCb = cb; }
    void setAudioManager(AudioManager am) { this.audioManager = am; }

    void configure(String host, String userAgent) {
        this.host = host;
        this.userAgent = userAgent;
    }

    boolean isCodecH265() { return codecH265; }
    boolean isActive() { return activeStream; }
    boolean isListening() { return listener; }
    long getLastFrame() { return lastFrame; }
    long getStreamBytes() { return streamBytes; }
    long getBitrateMarkNs() { return bitrateMarkNs; }

    void start() {
        if (listener) return;
        listener = true;
        listenerGen++;
        executor = Executors.newFixedThreadPool(5);

        final int gen = listenerGen;
        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-network");
            int retryDelay = 1000;
            int consecutiveFailures = 0;
            final int MAX_RETRY_DELAY = 30000;
            final int MAX_CONSECUTIVE_FAILURES = 10;

            while (gen == listenerGen) {
                try {
                    if (!activeStream) {
                        rtspConnect();
                        retryDelay = 1000;
                        consecutiveFailures = 0;
                        streamBytes = 0;
                        bitrateMarkNs = System.nanoTime();
                        if (listenerCb != null) listenerCb.onStatusChanged("connected");
                SystemClock.sleep(2000);
                    } else {
                        SystemClock.sleep(1000);
                    }
                } catch (Exception e) {
                    consecutiveFailures++;
                    activeStream = false;
                    if (listenerCb != null) listenerCb.onStatusChanged("disconnected");

                    if (consecutiveFailures <= 3 || (consecutiveFailures % 5 == 0)) {
                        Log.w(TAG, "RTSP connection failed (" + consecutiveFailures + "): " + e.getMessage());
                    }

                    int jitter = (int)(Math.random() * 500);
                    int delay = retryDelay + jitter;
                    if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                        delay = MAX_RETRY_DELAY;
                    }
                    SystemClock.sleep(delay);
                    retryDelay = Math.min(retryDelay * 2, MAX_RETRY_DELAY);

                    if (consecutiveFailures > 20) {
                        Log.i(TAG, "Resetting connection state after many failures");
                        closeSockets();
                        if (listenerCb != null) listenerCb.onStatusChanged("disconnected");
                        consecutiveFailures = 15;
                    }
                }
            }
        });

        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-watchdog");
            while (gen == listenerGen) {
                if (activeStream && lastFrame > 0
                        && SystemClock.elapsedRealtime() - lastFrame > WATCHDOG_MS) {
                    Log.w(TAG, "Stream is inactive");
                    activeStream = false;
                    Socket tcp = mTcpSocket;
                    if (tcp != null) try { tcp.close(); } catch (Exception ignored) {}
                }
                SystemClock.sleep(1000);
            }
        });
    }

    void stop() {
        listener = false;
        activeStream = false;
        closeSockets();
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        if (nalAssembler != null) nalAssembler.reset();
    }

    void invalidateGen() { listenerGen++; }

    private void closeSockets() {
        Socket tcp = mTcpSocket;
        if (tcp != null) {
            try { tcp.close(); } catch (Exception e) {
                Log.e(TAG, "Error closing TCP socket", e);
            }
        }
    }

    private void rtspConnect() throws Exception {
        nalAssembler.reset();
        lastUnknownPayload = -1;
        String currentHost = host;
        if (currentHost == null || currentHost.isEmpty()) {
            throw new IOException("Camera slot not configured");
        }
        Uri uri = Uri.parse(currentHost);
        String h = uri.getHost();
        if (h == null || h.isEmpty()) {
            throw new IOException("Invalid RTSP URL: host is missing or empty");
        }
        Socket s = null;
        try {
            s = new Socket();
            int port = uri.getPort();
            s.connect(new InetSocketAddress(h, port < 0 ? 554 : port), 1000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(1000);
            InputStream input = s.getInputStream();
            OutputStream w = s.getOutputStream();

            Log.d(TAG, "Start rtsp connection");

            String user = uri.getUserInfo();
            String auth = "";
            if (user != null) {
                auth = "Authorization: Basic " +
                        Base64.encodeToString(user.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\r\n";
            }
            String path = uri.getEncodedPath();
            String query = uri.getEncodedQuery();
            String rtspUrl = uri.getScheme() + "://" + h
                    + (port >= 0 ? ":" + port : "")
                    + (path  != null ? path         : "")
                    + (query != null ? "?" + query  : "");

            int seq = 1;
            String desc = "DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + userAgent + "Accept: application/sdp\r\n\r\n";
            w.write(desc.getBytes(StandardCharsets.UTF_8));
            w.flush();

            String contentLenStr = readRtspResponse(input, "Content-Length:");
            int sdpBodyLen = 0;
            if (contentLenStr != null) {
                try { sdpBodyLen = Integer.parseInt(contentLenStr); }
                catch (NumberFormatException ignored) {}
            }
            StringBuilder sdp = new StringBuilder();
            byte[] skipBuf = new byte[512];
            while (sdpBodyLen > 0) {
                int n = input.read(skipBuf, 0, Math.min(sdpBodyLen, skipBuf.length));
                if (n <= 0) break;
                if (sdp.length() < 4096)
                    sdp.append(new String(skipBuf, 0, n, StandardCharsets.UTF_8));
                sdpBodyLen -= n;
            }
            String[] trackUrls = parseSdp(sdp.toString(), rtspUrl);

            seq++;
            String video = "SETUP " + trackUrls[0] + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + userAgent +
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n";
            w.write(video.getBytes(StandardCharsets.UTF_8));
            w.flush();

            String session = readRtspResponse(input, "Session:");
            if (session == null) {
                throw new IOException("RTSP server did not return a Session header");
            }
            session = session.replaceAll("[\r\n]", "");

            seq++;
            String audio = "SETUP " + trackUrls[1] + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + userAgent +
                    "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n" +
                    "Session: " + session + "\r\n\r\n";
            w.write(audio.getBytes(StandardCharsets.UTF_8));
            w.flush();

            readRtspResponse(input, null);

            seq++;
            String play = "PLAY " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + userAgent + "Session: " + session + "\r\n\r\n";
            w.write(play.getBytes(StandardCharsets.UTF_8));
            w.flush();

            readRtspResponse(input, null);
            if (listenerCb != null) listenerCb.onStatusChanged("buffering");

            s.setSoTimeout(0);
            lastFrame = SystemClock.elapsedRealtime();
            activeStream = true;

            mTcpSocket = s;
            try {
                tcpStream(input);
            } finally {
                mTcpSocket = null;
            }
            try {
                String teardown = "TEARDOWN " + rtspUrl + " RTSP/1.0\r\n" +
                        "CSeq: " + (seq + 1) + "\r\n" + auth + userAgent +
                        "Session: " + session + "\r\n\r\n";
                w.write(teardown.getBytes(StandardCharsets.UTF_8));
                w.flush();
                Log.d(TAG, "RTSP TEARDOWN sent");
            } catch (Exception e) {
                Log.e(TAG, "Error sending TEARDOWN", e);
            }
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception e) {
                    Log.e(TAG, "Error closing TCP socket", e);
                }
            }
        }
    }

    private void tcpStream(InputStream rawInput) throws IOException {
        BufferedInputStream input = new BufferedInputStream(rawInput, 65536);
        byte[] pktBuf = new byte[65535];
        streamBytes = 0;
        bitrateMarkNs = System.nanoTime();
        while (activeStream) {
            int total = readInterleavedPacket(input, pktBuf);
            if (total < 0) { activeStream = false; break; }

            streamBytes += total;
            int channel = pktBuf[1];
            int len = total - 4;
            Frame frame = framePool.obtain(len);
            System.arraycopy(pktBuf, 4, frame.data(), 0, len);
            frame.setLength(len);

            if (channel == 0) {
                processPacket(frame);
            } else if (channel == 2) {
                if (audioManager != null) {
                    audioManager.onAudioFrame(frame);
                }
                framePool.recycle(frame);
            } else {
                framePool.recycle(frame);
            }
        }
    }

    /** Read one RTP-over-TCP interleaved packet. Shared between RtspClient and QuadCell. */
    static int readInterleavedPacket(BufferedInputStream input, byte[] pktBuf) throws IOException {
        while (true) {
            int b0 = input.read();
            if (b0 < 0) return -1; // clean EOF
            if (b0 != '$') continue;
            int b1 = input.read();
            if (b1 < 0) throw new IOException("Truncated RTP header (channel)");
            int b2 = input.read();
            if (b2 < 0) throw new IOException("Truncated RTP header (len hi)");
            int b3 = input.read();
            if (b3 < 0) throw new IOException("Truncated RTP header (len lo)");
            int len = (b2 << 8) | b3;
            if (len + 4 > pktBuf.length)
                throw new IOException("RTP packet too large: " + len);
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

    private void processPacket(Frame frame) {
        if (frame.length() < 12) { framePool.recycle(frame); return; }
        byte[] data = frame.data();
        if ((data[0] & 0x0F) != 0 || (data[0] & 0x10) != 0) { framePool.recycle(frame); return; }

        int payload = (data[1] & 0x7F);
        if (payload == audioPt) {
            if (audioManager != null) {
                audioManager.onAudioFrame(frame);
            }
            framePool.recycle(frame);
            return;
        } else if (payload == RTP_PT_H265 || payload == RTP_PT_H264) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastQualityUpdateTime > 1000) {
                lastQualityUpdateTime = now;
                int avgJitter = (jitterSampleCount > 0)
                        ? (int)(jitterAccumulator / jitterSampleCount)
                        : -1;
                if (listenerCb != null) listenerCb.onJitterSample(avgJitter);
                jitterAccumulator = 0;
                jitterSampleCount = 0;
            }
            if (lastRtpTimestamp >= 0 && lastRtpArrivalNs >= 0) {
                int rtpTs = ((data[4] & 0xFF) << 24)
                          | ((data[5] & 0xFF) << 16)
                          | ((data[6] & 0xFF) << 8)
                          | (data[7] & 0xFF);
                int rtpDeltaMs = (int)((rtpTs - lastRtpTimestamp) / 90L);
                long arrivalDeltaMs = (System.nanoTime() - lastRtpArrivalNs) / 1000000;
                long jitter = Math.abs(arrivalDeltaMs - rtpDeltaMs);
                if (jitter < 5000) {
                    jitterAccumulator += jitter;
                    jitterSampleCount++;
                }
            }
            lastRtpTimestamp = ((data[4] & 0xFF) << 24)
                             | ((data[5] & 0xFF) << 16)
                             | ((data[6] & 0xFF) << 8)
                             | (data[7] & 0xFF);
            lastRtpArrivalNs = System.nanoTime();

            codecH265 = payload == RTP_PT_H265;
            Frame output = nalAssembler.assemble(frame, codecH265);
            framePool.recycle(frame);
            if (output != null) {
                if (!nalQueue.offer(output)) {
                    Log.w(TAG, "Video queue full, frame dropped");
                    framePool.recycle(output);
                }
            }
            return;
        }

        if (payload != lastUnknownPayload) {
            lastUnknownPayload = payload;
            Log.w(TAG, "Unknown rtp type: " + payload);
        }
        framePool.recycle(frame);
    }

    private String[] parseSdp(String sdp, String baseUrl) {
        String base = baseUrl;
        String[] controls = { null, null };
        int track = -1;

        for (String line : sdp.split("[\r\n]+")) {
            if (line.startsWith("m=video")) {
                track = 0;
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    try {
                        int pt = Integer.parseInt(parts[3]);
                        codecH265 = (pt == RTP_PT_H265);
                    } catch (NumberFormatException ignored) {}
                }
            } else if (line.startsWith("m=audio")) {
                track = 1;
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    try {
                        audioPt = Integer.parseInt(parts[3]);
                    } catch (NumberFormatException ignored) {}
                }
            } else if (line.startsWith("a=control:")) {
                String ctrl = line.substring("a=control:".length()).trim();
                if (track == -1) {
                    base = ctrl.startsWith("rtsp://") ? ctrl : baseUrl + "/" + ctrl;
                } else {
                    controls[track] = ctrl.startsWith("rtsp://") ? ctrl : base + "/" + ctrl;
                }
            } else if (audioPt >= 0 && line.startsWith("a=rtpmap:" + audioPt + " ")) {
                String encoding = line.substring(("a=rtpmap:" + audioPt + " ").length());
                String codecName = encoding.split("/")[0].toUpperCase(Locale.ROOT);

                if (codecName.contains("MP4A") || codecName.contains("AAC")) {
                    audioCodec = "AAC";
                    audioBigEndian = false;
                    Log.d(TAG, "Audio codec: AAC detected");
                } else if (codecName.contains("PCMA") || codecName.contains("PCMU")) {
                    audioCodec = "G711";
                    audioBigEndian = false;
                    Log.d(TAG, "Audio codec: G.711 detected");
                } else if (codecName.startsWith("L16")) {
                    audioCodec = "PCM";
                    audioBigEndian = true;
                } else {
                    audioCodec = "PCM";
                    audioBigEndian = false;
                }

                int slash = encoding.indexOf('/');
                if (slash >= 0) {
                    int end = encoding.indexOf('/', slash + 1);
                    String rateStr = (end >= 0
                            ? encoding.substring(slash + 1, end)
                            : encoding.substring(slash + 1)).trim();
                    try {
                        int rate = Integer.parseInt(rateStr);
                        if (rate > 0 && rate <= 192000) {
                            audioSampleRate = rate;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (controls[0] == null) controls[0] = base + "/trackID=0";
        if (controls[1] == null) controls[1] = base + "/trackID=1";

        // Notify the listener about codec info
        if (listenerCb != null) {
            listenerCb.onCodecChanged(codecH265, audioCodec, audioSampleRate,
                    audioBigEndian, audioPt);
        }
        return controls;
    }

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

    static String readRtspResponse(InputStream in, String targetHeader) throws IOException {
        String status = readLine(in);
        if (status == null) throw new IOException("Server closed connection during handshake");
        Log.i(TAG, status);

        String[] parts = status.split(" ", 3);
        if (parts.length < 2) throw new IOException("Malformed RTSP response: " + status);
        try {
            int code = Integer.parseInt(parts[1]);
            if (code < 200 || code >= 300) throw new IOException("RTSP error: " + status.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Malformed RTSP status code: " + status);
        }

        String found = null;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            Log.i(TAG, line);
            if (targetHeader != null && line.toLowerCase(Locale.ROOT)
                    .startsWith(targetHeader.toLowerCase(Locale.ROOT))) {
                found = line.substring(targetHeader.length()).split(";")[0].trim();
            }
        }
        return found;
    }
}
