/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * QuadCell.java — single camera stream in quad mode
 *
 */

package com.openipc.decoder;

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
    /** One decoder manager for the cell's lifetime; decoders are created on the decode thread. */
    private final MediaCodecManager decoder;

    private final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(30);
    private final NalAssembler nalAssembler;
    private final NalAssembler.Sink nalSink;
    private int queueFullDrops;
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
        this.decoder = new MediaCodecManager(new MediaCodecManager.Listener() {
            @Override public void onResolutionChanged(int w, int h) {}
            @Override public void onDecoderStarted() {
                if (listener != null) listener.onRemoveOverlay(index);
            }
            @Override public void onDecoderFailed() {}
        });
        this.nalSink = nal -> {
            if (!nalQueue.offer(nal)) {
                // Rate-limited: one aggregation packet can carry many NAL units
                if (queueFullDrops++ % 100 == 0) {
                    Log.w(TAG, tag + " queue full, NAL dropped (" + queueFullDrops + ")");
                }
                pool.recycle(nal);
            }
        };
        // On a codec switch the decoder is released; the decode thread creates a new one (#20)
        this.nalAssembler = new NalAssembler(512 * 1024, () -> {
            nalQueue.clear();
            decoder.closeDecoder();
        }, pool);
    }

    void start() {
        if (started) return;
        started = true;
        running = true;
        decoder.setEnabled(true);
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
                    if (f != null) {
                        try {
                            decode(f);
                        } finally {
                            // The decoder copied the data: give the buffer back to the pool
                            cellFramePool.recycle(f);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, tag + " decode: " + e.getMessage());
                }
            }
        });

        Log.i(TAG, tag + ": surface ready, starting session");
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
        // Disabling releases the decoder and stops the decode thread from creating another,
        // so there is nothing to wait for on the UI thread (#32)
        decoder.setEnabled(false);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
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
        if (decoder.decodeFrame(buffer, codecH265, 0)) {
            Surface s = surface;
            if (s == null || !s.isValid()) return;
            decoder.setSurface(s);
            decoder.createDecoder(codecH265);
            // Feed the frame that triggered creation (often the SPS) to the new decoder
            decoder.decodeFrame(buffer, codecH265, 0);
        }
    }
}
