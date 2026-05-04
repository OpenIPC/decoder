/*
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * QuadCell.java — single camera stream in quad mode
 *
 */

package com.openipc.decoder;

import android.media.MediaCodec;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import android.net.Uri;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class QuadCell {
    private static final String TAG = "OpenIPCDecoder";

    private final int index;
    private final String host;
    private final TextureView view;
    private final String tag;
    private final FramePool cellFramePool;
    private final String userAgent;

    private volatile boolean running;
    private volatile boolean activeStream;
    private volatile long lastFrame;
    private volatile boolean codecH265;
    private volatile boolean started;
    private volatile boolean threadsStarted;

    private volatile Surface surface;
    private MediaCodecManager mediaCodecManager;
    private final Object decoderLock = new Object();
    private volatile boolean decoderFailed;

    private final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(30);
    private NalAssembler nalAssembler;
    private int lastUnknownPayload = -1;

    private volatile Socket tcpSocket;
    private ExecutorService executor;
    interface Listener {
        void onRemoveOverlay(int index);
    }

    private Listener listener;

    QuadCell(int index, String host, TextureView view, FramePool pool, String userAgent, Listener listener) {
        this.index = index;
        this.host = host;
        this.view = view;
        this.tag = "Quad-" + index;
        this.cellFramePool = pool;
        this.userAgent = userAgent;
        this.listener = listener;
        this.nalAssembler = new NalAssembler(512 * 1024, () -> {
            nalQueue.clear();
            synchronized (decoderLock) {
                if (mediaCodecManager != null) {
                    mediaCodecManager.closeDecoder();
                    decoderFailed = false;
                }
            }
        }, pool);
    }

    void start() {
        if (started) return;
        started = true;
        running = true;
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                if (threadsStarted) return;
                Surface old = surface;
                surface = new Surface(st);
                if (old != null) {
                    try { old.release(); } catch (Exception e) {
                        Log.e(TAG, tag + " Error releasing old surface", e);
                    }
                }
                startThreads();
            }
            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
                Surface old = surface;
                surface = new Surface(st);
                if (old != null) {
                    try { old.release(); } catch (Exception e) {
                        Log.e(TAG, tag + " Error releasing surface on size change", e);
                    }
                }
            }
            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                Surface s = surface;
                surface = null;
                if (s != null) {
                    try { s.release(); } catch (Exception e) {
                        Log.e(TAG, tag + " Error releasing surface on destroy", e);
                    }
                }
                return true;
            }
            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
        });
        if (view.isAvailable()) {
            if (surface == null) {
                surface = new Surface(view.getSurfaceTexture());
            }
            if (!threadsStarted) startThreads();
        }
    }

    private void startThreads() {
        if (threadsStarted) return;
        threadsStarted = true;
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        executor = Executors.newFixedThreadPool(3);

        executor.execute(() -> {
            Thread.currentThread().setName(tag + "-net");
            int retryDelay = 1000;
            int consecutiveFailures = 0;
            final int MAX_RETRY_DELAY = 30000;
            final int MAX_CONSECUTIVE_FAILURES = 10;
            while (running) {
                try {
                    connect();
                    retryDelay = 1000;
                    consecutiveFailures = 0;
                    SystemClock.sleep(1000);
                } catch (Exception e) {
                    activeStream = false;
                    Log.w(TAG, tag + ": " + e.getMessage());
                    consecutiveFailures++;
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Log.e(TAG, tag + ": too many consecutive failures, giving up");
                        running = false;
                        break;
                    }
                    SystemClock.sleep(retryDelay);
                    retryDelay = Math.min(retryDelay * 2, MAX_RETRY_DELAY);
                }
            }
        });

        executor.execute(() -> {
            Thread.currentThread().setName(tag + "-wd");
            final long WATCHDOG_MS = 8000;
            while (running) {
                if (activeStream && lastFrame > 0
                        && SystemClock.elapsedRealtime() - lastFrame > WATCHDOG_MS) {
                    activeStream = false;
                    Socket tcp = tcpSocket;
                    if (tcp != null) {
                        try { tcp.close(); } catch (Exception e) {
                            Log.e(TAG, tag + " Error closing socket in watchdog", e);
                        }
                    }
                }
                SystemClock.sleep(1000);
            }
        });

        executor.execute(() -> {
            Thread.currentThread().setName(tag + "-dec");
            while (running) {
                try {
                    Frame f = nalQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (f != null) decode(f);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, tag + " decode: " + e.getMessage());
                }
            }
        });
    }

    void stop() {
        started = false;
        threadsStarted = false;
        running = false;
        activeStream = false;
        nalAssembler.reset();
        Socket tcp = tcpSocket;
        if (tcp != null) {
            try { tcp.close(); } catch (Exception e) {
                Log.e(TAG, tag + " Error closing TCP socket", e);
            }
        }
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
        synchronized (decoderLock) {
            if (mediaCodecManager != null) {
                mediaCodecManager.closeDecoder();
            }
            decoderFailed = false;
        }
        nalQueue.clear();
        surface = null;
    }

    private void connect() throws Exception {
        nalAssembler.reset();
        lastUnknownPayload = -1;
        if (host == null || host.isEmpty()) {
            SystemClock.sleep(5000);
            return;
        }

        Uri uri = Uri.parse(host);
        String h = uri.getHost();
        if (h == null || h.isEmpty()) throw new IOException("Invalid host");

        Socket s = null;
        try {
            s = new Socket();
            int port = uri.getPort();
            s.connect(new InetSocketAddress(h, port < 0 ? 554 : port), 5000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(5000);

            InputStream input = s.getInputStream();
            OutputStream w = s.getOutputStream();

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

            // DESCRIBE
            int seq = 1;
            w.write(("DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + userAgent +
                    "Accept: application/sdp\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            w.flush();

            String contentLenStr = RtspClient.readRtspResponse(input, "Content-Length:");
            int sdpLen = 0;
            if (contentLenStr != null) {
                try { sdpLen = Integer.parseInt(contentLenStr); } catch (NumberFormatException ignored) {}
            }
            StringBuilder sdpBuf = new StringBuilder();
            byte[] skipBuf = new byte[512];
            while (sdpLen > 0) {
                int n = input.read(skipBuf, 0, Math.min(sdpLen, skipBuf.length));
                if (n <= 0) break;
                if (sdpBuf.length() < 4096)
                    sdpBuf.append(new String(skipBuf, 0, n, StandardCharsets.UTF_8));
                sdpLen -= n;
            }

            // Parse SDP for video track only — reuse RtspClient's parser
            boolean[] h265Out = new boolean[1];
            int[] audioPtOut = new int[1];
            audioPtOut[0] = -1;
            String[] audioInfo = new String[3];
            String[] trackUrls = RtspClient.parseSdp(sdpBuf.toString(), rtspUrl,
                    h265Out, audioPtOut, audioInfo);
            codecH265 = h265Out[0];
            String videoControl = trackUrls[0];

            // SETUP
            seq++;
            w.write(("SETUP " + videoControl + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + userAgent +
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            w.flush();

            String session = RtspClient.readRtspResponse(input, "Session:");
            if (session == null) throw new IOException("No Session");
            session = session.replaceAll("[\r\n]", "");

            // PLAY
            seq++;
            w.write(("PLAY " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + userAgent +
                    "Session: " + session + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            w.flush();

            RtspClient.readRtspResponse(input, null);

            s.setSoTimeout(0);
            lastFrame = SystemClock.elapsedRealtime();
            activeStream = true;
            initDecoder();

            tcpSocket = s;
            try {
                readTcp(input);
            } finally {
                tcpSocket = null;
                try {
                    w.write(("TEARDOWN " + rtspUrl + " RTSP/1.0\r\n" +
                            "CSeq: " + (seq + 1) + "\r\n" + auth + userAgent +
                            "Session: " + session + "\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    w.flush();
                } catch (Exception e) {
                    Log.e(TAG, tag + " Error sending TEARDOWN", e);
                }
            }
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception e) {
                    Log.e(TAG, tag + " Error closing socket in connect()", e);
                }
            }
        }
    }

    private void readTcp(InputStream rawInput) throws IOException {
        BufferedInputStream input = new BufferedInputStream(rawInput, 65536);
        byte[] pktBuf = new byte[65535];
        while (activeStream && running) {
            int total = RtspClient.readInterleavedPacket(input, pktBuf);
            if (total < 0) { activeStream = false; break; }

            int channel = pktBuf[1];
            int len = total - 4;
            if (channel == 0) {
                Frame f = cellFramePool.obtain(len);
                System.arraycopy(pktBuf, 4, f.data(), 0, len);
                f.setLength(len);
                handlePacket(f);
            }
        }
    }

    private void handlePacket(Frame frame) {
        if (frame.length() < 12) { cellFramePool.recycle(frame); return; }
        byte[] data = frame.data();
        if ((data[0] & 0x0F) != 0 || (data[0] & 0x10) != 0) { cellFramePool.recycle(frame); return; }

        int pt = data[1] & 0x7F;
        if (pt == 97 || pt == 96) {
            lastFrame = SystemClock.elapsedRealtime();
            codecH265 = (pt == 97);
            Frame output = nalAssembler.assemble(frame, codecH265);
            cellFramePool.recycle(frame);
            if (output != null && !nalQueue.offer(output)) {
                Log.w(TAG, tag + " queue full, dropping frame");
                cellFramePool.recycle(output);
            }
        } else if (pt != lastUnknownPayload) {
            lastUnknownPayload = pt;
            Log.w(TAG, tag + " unknown PT: " + pt);
            cellFramePool.recycle(frame);
        } else {
            cellFramePool.recycle(frame);
        }
    }

    private void decode(Frame buffer) {
        if (buffer.length() < 5) return;
        lastFrame = SystemClock.elapsedRealtime();

        int flag = 0;
        int frag = NalAssembler.fragment(buffer.data()[4], codecH265);
        boolean config = codecH265
                ? (frag == MediaCodecManager.H265_NAL_VPS || frag == MediaCodecManager.H265_NAL_SPS || frag == MediaCodecManager.H265_NAL_PPS)
                : (frag == MediaCodecManager.H264_NAL_SPS || frag == MediaCodecManager.H264_NAL_PPS);
        if (config) flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;

        boolean needCreate = false;
        synchronized (decoderLock) {
            if (mediaCodecManager == null) {
                needCreate = !decoderFailed;
            } else {
                needCreate = mediaCodecManager.decodeFrame(buffer, codecH265, 0);
            }
        }
        if (needCreate) initDecoder();
    }

    private void initDecoder() {
        synchronized (decoderLock) {
            if (mediaCodecManager != null) return;
        }
        Surface s = surface;
        if (s == null || !s.isValid()) return;

        MediaCodecManager mgr = new MediaCodecManager(new MediaCodecManager.Listener() {
            @Override public void onResolutionChanged(int w, int h) {}
            @Override public void onDecoderStarted() {}
            @Override public void onDecoderFailed() { decoderFailed = true; }
        });
        mgr.setSurface(s);
        mgr.createDecoder(codecH265);

        synchronized (decoderLock) {
            if (mediaCodecManager != null) {
                mgr.closeDecoder();
                return;
            }
            mediaCodecManager = mgr;
        }
        lastFrame = SystemClock.elapsedRealtime();
        if (listener != null) listener.onRemoveOverlay(index);
    }
}
