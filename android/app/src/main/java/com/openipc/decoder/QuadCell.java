/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * QuadCell.java — single camera stream in quad mode
 *
 */

package com.openipc.decoder;

import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class QuadCell implements RtspSession.Callback {
    private static final String TAG = "OpenIPCDecoder";

    private final int index;
    private final String host;
    private final SurfaceView view;
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

    // Audio (#12): every cell sets up its audio track, only the selected one plays it
    private volatile AudioManager audioSink;
    private volatile int audioRoute;
    private volatile AudioConfig audioConfig = new AudioConfig();

    /** Route this cell's audio to {@code sink} on route {@code route}, or mute it with null. */
    void setAudioSink(AudioManager sink, int route) {
        this.audioRoute = route;
        this.audioSink = sink;
    }

    AudioConfig getAudioConfig() { return audioConfig; }

    private final Listener listener;

    QuadCell(int index, String host, SurfaceView view, FramePool pool, String userAgent, Listener listener) {
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
        // SurfaceView, not TextureView (#61): with a TextureView every decoded frame raises a
        // frame-available callback that the framework delivers by attaching a native thread to
        // the VM, which allocated about 1 MB/s of garbage with four streams
        view.getHolder().addCallback(holderCallback);
        Surface s = view.getHolder().getSurface();
        if (s != null && s.isValid()) {
            surface = s;
            if (!threadsStarted) startThreads();
        }
    }

    private final SurfaceHolder.Callback holderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            // The surface belongs to the SurfaceView: it is used, never released, here
            surface = holder.getSurface();
            if (!threadsStarted) startThreads();
        }
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            // Nothing may render into the surface once this returns, and a recreated surface
            // needs a decoder configured for it: close the decoder, the decode thread creates
            // a new one when the next surface is there
            surface = null;
            decoder.closeDecoder();
        }
    };

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
        // Audio is set up too, so switching the audible cell needs no reconnect (#12)
        session.configure(host, userAgent, true);
        session.start();
    }

    void stop() {
        started = false;
        threadsStarted = false;
        running = false;
        audioSink = null;
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
        if (view != null) view.getHolder().removeCallback(holderCallback);
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
        audioConfig = sdp.audio;
        // A stopped or deselected cell holds an old route: the AudioManager ignores it
        AudioManager sink = audioSink;
        if (sink != null) sink.configure(sdp.audio, audioRoute);
    }

    @Override
    public boolean onPacket(int channel, byte[] buf, int offset, int length) {
        if (length < 12) return false;
        if (channel == RtspSession.CHANNEL_AUDIO) {
            AudioManager sink = audioSink;
            if (sink != null) {
                Frame frame = cellFramePool.obtain(length);
                System.arraycopy(buf, offset, frame.data(), 0, length);
                frame.setLength(length);
                sink.onAudioFrame(frame, audioRoute);
                cellFramePool.recycle(frame);
            }
            return false;
        }
        if (channel != RtspSession.CHANNEL_VIDEO) return false;
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
