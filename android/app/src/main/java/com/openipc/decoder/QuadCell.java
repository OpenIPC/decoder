/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * QuadCell.java — single camera stream in quad mode
 *
 */

package com.openipc.decoder;

import android.media.MediaCodec;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class QuadCell implements RtspSession.Callback {
    private static final String TAG = "OpenIPCDecoder";

    private final int index;
    private final String host;
    private final TextureView view;
    private final String tag;
    private final FramePool cellFramePool;
    private final String userAgent;
    private final RtspSession session;

    private volatile boolean running;
    private volatile boolean codecH265;
    private volatile int videoPt = -1;
    private volatile boolean started;
    private volatile boolean threadsStarted;

    private volatile Surface surface;
    private MediaCodecManager mediaCodecManager;
    private final Object decoderLock = new Object();
    private volatile boolean decoderFailed;

    private final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(30);
    private final NalAssembler nalAssembler;
    private final NalAssembler.Sink nalSink;
    private int lastUnknownPayload = -1;

    private ExecutorService executor;

    interface Listener {
        void onRemoveOverlay(int index);
    }

    private final Listener listener;

    QuadCell(int index, String host, TextureView view, FramePool pool, String userAgent, Listener listener) {
        this.index = index;
        this.host = host;
        this.view = view;
        this.tag = "Quad-" + index;
        this.cellFramePool = pool;
        this.userAgent = userAgent;
        this.listener = listener;
        this.session = new RtspSession(tag, this);
        this.nalSink = nal -> {
            if (!nalQueue.offer(nal)) {
                Log.w(TAG, tag + " queue full, dropping frame");
                pool.recycle(nal);
            }
        };
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
        executor = Executors.newSingleThreadExecutor(r -> new Thread(r, tag + "-dec"));
        executor.execute(() -> {
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

        // Video only: Quad mode has no audio (#12)
        session.configure(host, userAgent, false);
        session.start();
    }

    void stop() {
        started = false;
        threadsStarted = false;
        running = false;
        session.stop();
        nalAssembler.reset();
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

    // ====================== RtspSession.Callback ======================

    @Override
    public void onStatus(String status) {}

    @Override
    public void onSdp(Sdp sdp) {
        nalAssembler.reset();
        lastUnknownPayload = -1;
        videoPt = sdp.videoPt;
        codecH265 = sdp.videoH265;
        initDecoder();
    }

    @Override
    public boolean onPacket(int channel, byte[] buf, int offset, int length) {
        if (channel != RtspSession.CHANNEL_VIDEO || length < 12) return false;
        int pt = buf[offset + 1] & 0x7F;
        if (pt != videoPt) {
            if (pt != lastUnknownPayload) {
                lastUnknownPayload = pt;
                Log.w(TAG, tag + " unexpected PT: " + pt + " (expected " + videoPt + ")");
            }
            return false;
        }

        return nalAssembler.assemble(buf, offset, length, codecH265, nalSink);
    }


    private void decode(Frame buffer) {
        if (buffer.length() < 5) return;

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
            // Another thread was faster, or stop() ran meanwhile (onSdp is on the network thread)
            if (mediaCodecManager != null || !running) {
                mgr.closeDecoder();
                return;
            }
            mediaCodecManager = mgr;
        }
        if (listener != null) listener.onRemoveOverlay(index);
    }
}
