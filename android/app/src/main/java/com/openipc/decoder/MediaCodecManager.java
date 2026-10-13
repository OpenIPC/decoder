/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * MediaCodecManager.java — video decoder lifecycle management
 *
 */

package com.openipc.decoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

class MediaCodecManager {
    private static final String TAG = "OpenIPCDecoder";

    // NAL type constants — package-private for sharing with QuadCell
    static final int H265_NAL_VPS = 32;
    static final int H265_NAL_SPS = 33;
    static final int H265_NAL_PPS = 34;
    static final int H264_NAL_SPS = 7;
    static final int H264_NAL_PPS = 8;

    /** Largest NAL unit (single view); 4K key frames at high bitrate can exceed 1 MiB. */
    static final int MAX_NAL_SIZE = 2 * 1024 * 1024;

    private volatile MediaCodec decoder;
    private final Object decoderLock = new Object();
    /** Back-off after failures, instead of giving up for good (#21). */
    private final DecoderRetry retry = new DecoderRetry();
    /** While false no decoder is created, so none can appear after a pause or stop. */
    private volatile boolean enabled = true;
    /** Parameter sets seen since the decoder was created (bit per NAL type). */
    private int configSeen;
    /** A fresh decoder skips non-config input until all parameter sets arrived. */
    private boolean awaitingConfig;
    /** Bumped by setEnabled(); a creation started in an older generation is discarded. */
    private volatile int lifecycle;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    // Surface set by the owning activity
    private volatile Surface surface;

    /** Frames rendered so far (#82): with the frames received this gives the real decode rate. */
    private volatile int framesOut;
    /** When the last frame was rendered (nowMs()), 0 if none since the decoder was closed (#98). */
    private volatile long lastFrameMs;
    /** NAL units not fed because the decoder had no free input buffer: it is not keeping up. */
    private volatile int inputDropped;
    private volatile int outWidth;
    private volatile int outHeight;
    /** MIME type whose instance slot this manager holds while it has a decoder (#83). */
    private String heldMime;

    interface Listener {
        void onResolutionChanged(int width, int height);
        void onDecoderStarted();
        void onDecoderFailed();
        /** No decoder was created: {@code limit} decoders of {@code mime} already exist (#83). */
        default void onDecoderLimit(String mime, int limit) {}
    }

    int framesOut() { return framesOut; }
    /** True when a frame was rendered within the last {@code ms} milliseconds (#98). */
    boolean renderedWithin(long ms) {
        long last = lastFrameMs;
        return last > 0 && nowMs() - last <= ms;
    }
    int inputDropped() { return inputDropped; }
    int outWidth() { return outWidth; }
    int outHeight() { return outHeight; }
    boolean hasDecoder() { return decoder != null; }

    /**
     * Decoders alive in this process per MIME type, against the declared limit of the codec
     * (MediaCodecInfo.CodecCapabilities#getMaxSupportedInstances, Android 6+). The limit is
     * learnt from the first decoder of a type, so it is the codec that createDecoderByType
     * really picks (#83).
     */
    static final class InstanceBudget {
        private static final Map<String, Integer> used = new HashMap<>();
        private static final Map<String, Integer> limit = new HashMap<>();

        static synchronized boolean tryAcquire(String mime) {
            int u = used.containsKey(mime) ? used.get(mime) : 0;
            Integer max = limit.get(mime);
            if (max != null && u >= max) return false;
            used.put(mime, u + 1);
            return true;
        }

        static synchronized void release(String mime) {
            int u = used.containsKey(mime) ? used.get(mime) : 0;
            if (u > 0) used.put(mime, u - 1);
        }

        static synchronized void learn(String mime, int max) {
            if (max > 0 && !limit.containsKey(mime)) limit.put(mime, max);
        }

        /** Declared limit, or -1 while unknown. */
        static synchronized int limit(String mime) {
            Integer max = limit.get(mime);
            return max != null ? max : -1;
        }

        static synchronized void reset() {
            used.clear();
            limit.clear();
        }
    }

    /** Give the instance slot back; called wherever the decoder is detached (decoderLock held). */
    private void releaseSlot() {
        if (heldMime != null) {
            InstanceBudget.release(heldMime);
            heldMime = null;
        }
    }

    /** Learn the codec's instance limit and log its declared throughput, once per type. */
    private static void learnLimits(MediaCodec codec, String mime) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (InstanceBudget.limit(mime) >= 0) return;
        try {
            MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo().getCapabilitiesForType(mime);
            int max = caps.getMaxSupportedInstances();
            InstanceBudget.learn(mime, max);
            MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
            String fps1080 = video != null && video.isSizeSupported(1920, 1080)
                    ? String.valueOf(video.getSupportedFrameRatesFor(1920, 1080).getUpper()) : "?";
            Log.i(TAG, "Decoder " + codec.getName() + " (" + mime + "): up to " + max
                    + " instances, declared 1080p frame rate in total " + fps1080);
        } catch (Exception e) {
            Log.w(TAG, "Decoder limits unknown: " + e.getMessage());
        }
    }

    private Listener listener;

    MediaCodecManager(Listener listener) {
        this.listener = listener;
    }

    void setSurface(Surface s) {
        this.surface = s;
    }

    /** Enable or disable decoding; disabling also releases the decoder. */
    /**
     * Enable or disable decoding. Never blocks the caller (the UI thread): disabling only
     * sets flags; the decoder is detached and released on a short-lived thread, which is
     * the only one that waits for decoderLock.
     */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        lifecycle++;
        if (enabled) return;
        final MediaCodec target = decoder;
        if (target == null) return;
        new Thread(() -> {
            synchronized (decoderLock) {
                if (decoder != target) return; // already replaced or released
                decoder = null;
                releaseSlot();
                retry.onSuccess();
            }
            releaseNow(target);
        }, "decoder-release").start();
    }

    private static void releaseNow(MediaCodec codec) {
        try { codec.stop(); } catch (Exception ignored) {}
        try { codec.release(); } catch (Exception ignored) {}
    }

    private static void releaseAsync(MediaCodec codec) {
        new Thread(() -> releaseNow(codec), "decoder-release").start();
    }

    /** Release the decoder on purpose (codec switch, camera switch): no back-off. */
    void closeDecoder() {
        synchronized (decoderLock) {
            retry.onSuccess();
            MediaCodec codec = decoder;
            // The next stream must not look live because of this one's last frame
            lastFrameMs = 0;
            if (codec == null) return;
            decoder = null;
            releaseSlot();
            Log.i(TAG, "Close video decoder");
            try {
                codec.stop();
                codec.release();
            } catch (Exception e) {
                Log.e(TAG, "Decoder close exception", e);
            }
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    void createDecoder(boolean codecH265) {
        final int gen;
        synchronized (decoderLock) {
            if (decoder != null || !enabled || !retry.mayCreate(nowMs())) return;
            gen = lifecycle;
        }

        Surface s = surface;
        if (s == null || !s.isValid()) {
            Log.w(TAG, "Cannot create decoder: surface not ready");
            return;
        }

        String type = codecH265 ? "video/hevc" : "video/avc";

        MediaFormat format = MediaFormat.createVideoFormat(type, 1280, 720);
        // Must hold the largest NAL unit the assembler can deliver
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_NAL_SIZE);

        if (!InstanceBudget.tryAcquire(type)) {
            int limit = InstanceBudget.limit(type);
            synchronized (decoderLock) {
                retry.onFailure(nowMs());
            }
            Log.w(TAG, "Decoder limit reached: " + limit + " × " + type);
            if (listener != null) listener.onDecoderLimit(type, limit);
            return;
        }

        MediaCodec local;
        try {
            Log.i(TAG, "Start video decoder (" + type + ")");
            local = MediaCodec.createDecoderByType(type);
            learnLimits(local, type);
            try {
                local.configure(format, s, null, 0);
                local.start();
                if (listener != null) listener.onDecoderStarted();
            } catch (Exception e) {
                local.release();
                throw e;
            }
        } catch (Exception e) {
            InstanceBudget.release(type);
            synchronized (decoderLock) {
                // A creation cancelled by a pause must not delay the next start
                if (gen != lifecycle || !enabled) return;
                retry.onFailure(nowMs());
            }
            Log.e(TAG, "Cannot setup decoder (attempt " + retry.failures() + "): " + e.getMessage());
            return;
        }

        synchronized (decoderLock) {
            // Disabled meanwhile (pause/stop) or another thread was faster
            if (decoder != null || !enabled || gen != lifecycle) {
                InstanceBudget.release(type);
                releaseAsync(local);
                return;
            }
            decoder = local;
            heldMime = type;
            awaitingConfig = true;
            configSeen = 0;
        }
    }

    /**
     * Decode one NAL frame. Returns true if there is no decoder and one may be created now:
     * the caller then creates it and passes the same frame again.
     */
    boolean decodeFrame(Frame buffer, boolean codecH265, long watchdogRef) {
        if (buffer.length() < 5) {
            Log.w(TAG, "NAL frame too short: " + buffer.length());
            return false;
        }

        int flag = 0;
        int fragment = NalAssembler.fragment(buffer.data()[4], codecH265);
        boolean isConfigNal = codecH265
                ? (fragment == H265_NAL_VPS || fragment == H265_NAL_SPS || fragment == H265_NAL_PPS)
                : (fragment == H264_NAL_SPS || fragment == H264_NAL_PPS);
        if (isConfigNal) {
            flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
        }

        boolean needCreate = false;
        synchronized (decoderLock) {
            MediaCodec codec = decoder;
            if (!enabled) {
                // Paused: the decoder is being released, feed nothing
                return false;
            } else if (codec == null) {
                needCreate = retry.mayCreate(nowMs());
            } else if (awaitingConfig && !isConfigNal) {
                // Frames before the full parameter set only produce errors in a new decoder
                return false;
            } else {
                if (isConfigNal) {
                    configSeen |= 1 << fragment;
                    int needed = codecH265
                            ? (1 << H265_NAL_VPS) | (1 << H265_NAL_SPS) | (1 << H265_NAL_PPS)
                            : (1 << H264_NAL_SPS) | (1 << H264_NAL_PPS);
                    if ((configSeen & needed) == needed) awaitingConfig = false;
                }
                try {
                    int inputBufferId = codec.dequeueInputBuffer(5_000);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(buffer.data(), 0, buffer.length());
                            codec.queueInputBuffer(inputBufferId, 0,
                                    buffer.length(), System.nanoTime() / 1000, flag);
                        }
                    } else {
                        inputDropped++;
                    }

                    MediaCodec.BufferInfo info = bufferInfo;
                    int outputBufferId;
                    while ((outputBufferId = codec.dequeueOutputBuffer(info, 0)) >= 0
                            || outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat format = codec.getOutputFormat();
                            int mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                            int mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                            outWidth = mWidth;
                            outHeight = mHeight;
                            if (listener != null) {
                                listener.onResolutionChanged(mWidth, mHeight);
                            }
                        } else {
                            codec.releaseOutputBuffer(outputBufferId, true);
                            framesOut++;
                            lastFrameMs = nowMs();
                            retry.onSuccess(); // a decoded frame: the decoder works
                        }
                    }
                } catch (Exception e) {
                    decoder = null;
                    releaseSlot();
                    retry.onFailure(nowMs());
                    Log.e(TAG, "Codec exception, retry in "
                            + DecoderRetry.delayMs(retry.failures()) + " ms: " + e.getMessage());
                    try { codec.stop(); } catch (Exception ignored) {}
                    try { codec.release(); } catch (Exception ignored) {}
                    if (listener != null) listener.onDecoderFailed();
                }
            }
        }
        return needCreate;
    }
}
