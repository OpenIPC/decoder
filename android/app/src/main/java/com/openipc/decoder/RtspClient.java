/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtspClient.java — single-view stream: RTSP session, NAL assembly, audio and quality
 *
 */

package com.openipc.decoder;

import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.BlockingQueue;

class RtspClient implements RtspSession.Callback {
    private static final String TAG = "OpenIPCDecoder";

    private final FramePool framePool;
    private final NalAssembler nalAssembler;
    private final BlockingQueue<Frame> nalQueue;
    private final RtspSession session = new RtspSession("rtsp", this);

    // Session parameters from the SDP (network thread)
    private volatile int videoPt = -1;
    private volatile boolean codecH265;

    // Quality tracking (network thread)
    private long lastQualityUpdateTime;
    private long lastRtpTimestamp = -1;
    private long lastRtpArrivalNs = -1;
    private long jitterAccumulator;
    private int jitterSampleCount;
    private int lastUnknownPayload = -1;

    private AudioManager audioManager;
    /** Audio route of the current stream (see AudioManager.newRoute()). */
    private volatile int audioRoute;

    interface Listener {
        void onStatusChanged(String status);
        void onCodecChanged(boolean codecH265, AudioConfig audio);
        void onJitterSample(int avgJitter);
    }

    private volatile Listener listenerCb;

    RtspClient(FramePool framePool, NalAssembler nalAssembler,
               BlockingQueue<Frame> nalQueue) {
        this.framePool = framePool;
        this.nalAssembler = nalAssembler;
        this.nalQueue = nalQueue;
        this.nalSink = nal -> {
            if (!nalQueue.offer(nal)) {
                // Rate-limited: one aggregation packet can carry many NAL units
                if (queueFullDrops++ % 100 == 0) {
                    Log.w(TAG, "Video queue full, NAL dropped (" + queueFullDrops + ")");
                }
                framePool.recycle(nal);
            }
        };
    }

    void setListener(Listener cb) { this.listenerCb = cb; }
    void setAudioManager(AudioManager am) { this.audioManager = am; }
    void setAudioRoute(int route) { this.audioRoute = route; }

    void configure(String host, String userAgent) {
        session.configure(host, userAgent, true);
    }

    boolean isActive() { return session.isActive(); }
    long getStreamBytes() { return session.getStreamBytes(); }
    long getBitrateMarkNs() { return session.getBitrateMarkNs(); }

    void start() {
        nalAssembler.reset();
        session.start();
    }

    /** Non-blocking: the session threads exit on their own. */
    void stop() {
        session.stop();
        nalAssembler.reset();
    }

    // ====================== RtspSession.Callback ======================

    @Override
    public void onStatus(String status) {
        Listener l = listenerCb;
        if (l != null) l.onStatusChanged(status);
    }

    @Override
    public void onSdp(Sdp sdp) {
        nalAssembler.reset();
        lastUnknownPayload = -1;
        lastRtpTimestamp = -1;
        lastRtpArrivalNs = -1;
        videoPt = sdp.videoPt;
        codecH265 = sdp.videoH265;
        Log.i(TAG, "Video pt " + sdp.videoPt + (sdp.videoH265 ? " H.265" : " H.264")
                + ", audio " + sdp.audio.codec);
        if (audioManager != null) audioManager.configure(sdp.audio, audioRoute);
        Listener l = listenerCb;
        if (l != null) l.onCodecChanged(codecH265, sdp.audio);
    }

    private final NalAssembler.Sink nalSink;
    private int queueFullDrops;

    @Override
    public boolean onPacket(int channel, byte[] buf, int offset, int length) {
        if (length < 12) return false;
        if (channel == RtspSession.CHANNEL_VIDEO) {
            return processVideo(buf, offset, length);
        }
        if (channel == RtspSession.CHANNEL_AUDIO && audioManager != null) {
            Frame frame = framePool.obtain(length);
            System.arraycopy(buf, offset, frame.data(), 0, length);
            frame.setLength(length);
            audioManager.onAudioFrame(frame, audioRoute);
            framePool.recycle(frame);
        }
        return false;
    }

    /** Returns false for packets of the wrong payload type or with a malformed header. */
    private boolean processVideo(byte[] data, int offset, int length) {
        int payload = data[offset + 1] & 0x7F;
        if (payload != videoPt) {
            if (payload != lastUnknownPayload) {
                lastUnknownPayload = payload;
                Log.w(TAG, "Unexpected video rtp type: " + payload + " (expected " + videoPt + ")");
            }
            return false;
        }

        if (!nalAssembler.assemble(data, offset, length, codecH265, nalSink)) return false;
        updateJitter(data, offset);
        return true;
    }

    private void updateJitter(byte[] data, int offset) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastQualityUpdateTime > 1000) {
            lastQualityUpdateTime = now;
            int avgJitter = jitterSampleCount > 0
                    ? (int) (jitterAccumulator / jitterSampleCount) : -1;
            Listener l = listenerCb;
            if (l != null) l.onJitterSample(avgJitter);
            jitterAccumulator = 0;
            jitterSampleCount = 0;
        }
        long rtpTs = Integer.toUnsignedLong(((data[offset + 4] & 0xFF) << 24)
                | ((data[offset + 5] & 0xFF) << 16)
                | ((data[offset + 6] & 0xFF) << 8)
                | (data[offset + 7] & 0xFF));
        long arrivalNs = System.nanoTime();
        if (lastRtpTimestamp >= 0 && lastRtpArrivalNs >= 0) {
            long rtpDeltaMs = (rtpTs - lastRtpTimestamp) / 90L;
            long arrivalDeltaMs = (arrivalNs - lastRtpArrivalNs) / 1_000_000;
            long jitter = Math.abs(arrivalDeltaMs - rtpDeltaMs);
            if (jitter < 5000) {
                jitterAccumulator += jitter;
                jitterSampleCount++;
            }
        }
        lastRtpTimestamp = rtpTs;
        lastRtpArrivalNs = arrivalNs;
    }
}
