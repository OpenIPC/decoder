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
import android.view.View;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class QuadCell implements RtspSession.Callback {
    private static final String TAG = "OpenIPCDecoder";

    private final int index;
    private final String host;
    private final Transport.Mode transport;
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
    private volatile int queueFullDrops;
    private int lastUnknownPayload = -1;

    private ExecutorService executor;

    // Stats and status shown in the cell's corner (#80, #82)
    /** Complete video frames received (RTP marker bit). */
    private volatile int framesIn;
    /** Session state: "connecting", "buffering", "connected" or "disconnected". */
    private volatile String state = "connecting";
    /** What keeps the cell from playing (connection error, decoder limit), or null. */
    private volatile String problem;
    /** Why the cell was paused to relieve the decoder (#83), or null. */
    private volatile String pausedReason;
    private volatile long decoderStartedMs;
    // Sampled on the UI thread
    private int lastIn;
    private int lastOut;
    private long lastSampleMs;
    private int lastLost;
    private float inFps;
    private float outFps;
    /** Frames per second lost because the decoder did not keep up (queue full, no input buffer). */
    private float lostFps;
    /** Smoothed rates (about 3 s): the per-second ones jump too much to plan with (#85). */
    private float inAvg;
    private float outAvg;
    /** Decode only key frames and parameter sets (#85): a fraction of the load. */
    private volatile boolean keyFramesOnly = true;
    /** The camera sends MJPEG, which the grid does not decode yet (#119). */
    private volatile boolean jpeg;
    /** Just promoted to full rate: skip until the next key frame, the references are missing. */
    private volatile boolean waitingKeyFrame;

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

    QuadCell(int index, String host, Transport.Mode transport, SurfaceView view, FramePool pool,
             String userAgent, Listener listener) {
        this.index = index;
        this.host = host;
        this.transport = transport;
        this.view = view;
        this.tag = "Quad-" + index;
        this.cellFramePool = pool;
        this.userAgent = userAgent;
        this.listener = listener;
        this.session = new RtspSession(tag, this);
        this.decoder = new MediaCodecManager(new MediaCodecManager.Listener() {
            @Override public void onResolutionChanged(int w, int h) {}
            @Override public void onDecoderStarted() {
                problem = null;
                decoderStartedMs = System.nanoTime() / 1_000_000;
                if (listener != null) listener.onRemoveOverlay(index);
            }
            @Override public void onDecoderFailed() {
                // A decoder limit message says more; keep it
                if (problem == null || !problem.startsWith("Decoder limit")) problem = "Decoder error";
            }
            @Override public void onDecoderLimit(String mime, int limit) {
                problem = "Decoder limit: " + (limit > 0 ? limit + " × " : "")
                        + (mime.contains("hevc") ? "H.265" : "H.264");
            }
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
        session.configure(host, userAgent, true, transport);
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
    public void onStatus(String status) {
        // A new connection (or a lost one) starts the UDP stall count afresh (Qodo on #125)
        if (!status.equals(state)) udpStallSamples = 0;
        state = status;
        // A connection that works again clears the last connection error
        if ("connected".equals(status) && problem != null && !problem.startsWith("Decoder")) {
            problem = null;
        }
    }

    @Override
    public void onFailure(String message) {
        // A decoder problem stays visible; the connection retries on its own
        String p = problem;
        if (p == null || !p.startsWith("Decoder")) problem = shortError(message);
    }

    /** A connection error short enough for a cell corner. */
    static String shortError(String message) {
        if (message == null) return "Connection failed";
        String m = message;
        if (m.contains("EADDRINUSE")) return "Port in use";
        if (m.contains("ECONNREFUSED")) return "Connection refused";
        if (m.contains("EHOSTUNREACH") || m.contains("ENETUNREACH")) return "Camera unreachable";
        if (m.contains("ETIMEDOUT") || m.contains("timed out") || m.contains("after ")) {
            return "No answer from camera";
        }
        if (m.contains("401") || m.toLowerCase(java.util.Locale.ROOT).contains("authentication")) {
            return "Wrong login or password";
        }
        if (m.contains("404")) return "Stream not found";
        return m.length() > 40 ? m.substring(0, 40) + "…" : m;
    }

    /**
     * One line for the cell's corner, sampled about once per second on the UI thread:
     * resolution, codec and decoded/received frames per second while playing, otherwise
     * what the cell is waiting for or why it does not play (#80, #82).
     */
    String sample(long nowMs) {
        int in = framesIn;
        int out = decoder.framesOut();
        int lost = queueFullDrops + decoder.inputDropped();
        if (lastSampleMs > 0 && nowMs > lastSampleMs) {
            float sec = (nowMs - lastSampleMs) / 1000f;
            inFps = Math.max(0, in - lastIn) / sec;
            outFps = Math.max(0, out - lastOut) / sec;
            lostFps = Math.max(0, lost - lastLost) / sec;
            inAvg = inAvg == 0 ? inFps : 0.7f * inAvg + 0.3f * inFps;
            outAvg = outAvg == 0 ? outFps : 0.7f * outAvg + 0.3f * outFps;
        }
        lastIn = in;
        lastOut = out;
        lastLost = lost;
        lastSampleMs = nowMs;
        checkUdpStall();

        if (pausedReason != null) return pausedReason;
        String p = problem;
        if (p != null) return p;
        String st = state;
        if (!"connected".equals(st)) {
            if ("disconnected".equals(st)) return "Disconnected";
            return "buffering".equals(st) ? "Buffering…" : "Connecting…";
        }
        int w = decoder.outWidth();
        int h = decoder.outHeight();
        String size = w > 0 ? w + "×" + h + " " : "";
        String codec = codecH265 ? "H.265" : "H.264";
        if (keyFramesOnly) {
            String rate = outAvg < 10 ? String.format(java.util.Locale.ROOT, "%.1f", outAvg)
                    : String.valueOf(Math.round(outAvg));
            return size + codec + " key frames · " + rate + " fps";
        }
        return size + codec + " " + Math.round(outFps) + "/" + Math.round(inFps) + " fps";
    }

    boolean isKeyFramesOnly() { return keyFramesOnly; }

    /** Full rate or key frames only (#85). Going to full rate waits for the next key frame. */
    void setKeyFramesOnly(boolean on) {
        if (on == keyFramesOnly) return;
        if (!on) waitingKeyFrame = true;
        keyFramesOnly = on;
    }

    /** Resolution and frame rate are known, so the full-rate load can be estimated. */
    boolean knowsLoad() {
        return decoder.outWidth() > 0 && inAvg > 0;
    }

    /** Macroblocks per second at full rate (#85). */
    long fullLoad() {
        return DecodePlanner.fullLoad(decoder.outWidth(), decoder.outHeight(), inAvg);
    }

    /** Macroblocks per second it costs now on key frames only (#85). */
    long keyLoad() {
        return DecodePlanner.fullLoad(decoder.outWidth(), decoder.outHeight(), outAvg);
    }

    /** A parameter set or a key frame (H.264 IDR; H.265 IRAP). */
    static boolean isKeyNal(int type, boolean h265) {
        if (h265) return (type >= 16 && type <= 21) || (type >= 32 && type <= 34);
        return type == 5 || type == 7 || type == 8;
    }

    static boolean isKeyFrameNal(int type, boolean h265) {
        return h265 ? type >= 16 && type <= 21 : type == 5;
    }

    /** Transport in use, with the mode after a fallback (#112). */
    String transportLabel() { return Transport.label(session.transportInUse(), transport); }

    /** Samples (seconds) in a row with packets but no decoded frame over UDP (#113). */
    private volatile int udpStallSamples;
    /** Longer than the key frame interval of any lab camera (up to ~5 s on .167). */
    static final int UDP_STALL_SAMPLES = 8;
    /** Video RTP packets received, for the stall check (frames need a marker bit). */
    private volatile int videoPackets;
    private int lastVideoPackets;
    private int lastLossCount;

    /**
     * Over UDP a lost packet drops the whole NAL unit; on key frames only (#85) a camera that
     * keeps losing a piece of every key frame shows nothing at all although its packets arrive.
     * A second counts when packets arrive, nothing is decoded, NAL units are lost to packet
     * loss and the decoder itself is fine (no decoder error, a surface). After
     * UDP_STALL_SAMPLES such seconds the session is told; with Auto it goes to TCP.
     */
    private void checkUdpStall() {
        int packets = videoPackets;
        int loss = nalAssembler.lossCount();
        boolean arriving = packets - lastVideoPackets >= 10;
        boolean losing = loss > lastLossCount;
        lastVideoPackets = packets;
        lastLossCount = loss;
        Surface s = surface;
        boolean stalled = session.playsOverUdp() && "connected".equals(state)
                && pausedReason == null && problem == null && s != null && s.isValid()
                && arriving && outFps == 0;
        if (!stalled) {
            udpStallSamples = 0;
            return;
        }
        // Silent seconds (between key frames) keep the count; only a lossless decoded stream
        // or a non-stalled sample resets it. Loss must have been seen within the window
        if (losing || udpStallSamples > 0) udpStallSamples++;
        if (udpStallSamples >= UDP_STALL_SAMPLES) {
            udpStallSamples = 0;
            session.reportUdpBroken("packets but no decoded frame for " + UDP_STALL_SAMPLES
                    + " s (" + loss + " NAL units lost)");
        }
    }

    /** NAL units dropped so far because a packet of them was lost (#113). */
    int nalDropped() { return nalAssembler.lossCount(); }

    float inFps() { return inFps; }
    float outFps() { return outFps; }
    float lostFps() { return lostFps; }

    boolean isPaused() { return pausedReason != null; }

    /** On key frames only, a frame comes once per GOP, which can be several seconds (#85). */
    static final long KEY_FRAMES_LIVE_MS = 10_000;

    /**
     * Connected, not paused and a frame shown within the last {@code ms} milliseconds (#98);
     * on key frames within KEY_FRAMES_LIVE_MS, or the cell would blink between key frames.
     */
    boolean showsVideo(long ms) {
        long window = keyFramesOnly ? Math.max(ms, KEY_FRAMES_LIVE_MS) : ms;
        return pausedReason == null && "connected".equals(state) && decoder.renderedWithin(window);
    }

    /** Connected and decoding: its load is real, so demoting or pausing it helps (#85). */
    boolean isPlaying() {
        return pausedReason == null && "connected".equals(state) && decoder.hasDecoder();
    }

    /** Playing long enough for its decode rate to mean something (#83). */
    boolean isSettled(long nowMs) {
        long started = decoderStartedMs;
        return pausedReason == null && "connected".equals(state) && decoder.hasDecoder()
                && started > 0 && nowMs - started > 5000;
    }

    /** Stop the stream to relieve the decoder; the reason stays visible in the cell (#83). */
    void pause(String reason) {
        pausedReason = reason;
        stop();
    }

    @Override
    public void onSdp(Sdp sdp) {
        // MJPEG plays in the single view only for now (#119)
        jpeg = sdp.videoJpeg;
        if (jpeg) {
            // Paused rather than pulling a stream it cannot show (Qodo on #131)
            problem = "Decoder: MJPEG in the single view only";
            View v = view;
            if (v != null) v.post(() -> pause("MJPEG: single view only"));
        }
        udpStallSamples = 0;
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
        if (jpeg) return true; // dropped, but no reconnect loop before the pause
        int pt = buf[offset + 1] & 0x7F;
        if (pt != videoPt) {
            if (pt != lastUnknownPayload) {
                lastUnknownPayload = pt;
                Log.w(TAG, tag + " unexpected PT: " + pt + " (expected " + videoPt + ")");
            }
            return false;
        }
        // The marker bit ends an access unit: one received frame
        videoPackets++;
        if ((buf[offset + 1] & 0x80) != 0) framesIn++;

        return nalAssembler.assemble(buf, offset, length, codecH265, nalSink);
    }


    private void decode(Frame buffer) {
        if (buffer.length() < 5) return;
        if (keyFramesOnly || waitingKeyFrame) {
            int type = NalAssembler.fragment(buffer.data()[4], codecH265);
            if (!isKeyNal(type, codecH265)) return;
            if (!keyFramesOnly && isKeyFrameNal(type, codecH265)) waitingKeyFrame = false;
        }
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
