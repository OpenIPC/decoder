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
import java.util.concurrent.atomic.AtomicInteger;

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
    /** One wait for a free input buffer, in microseconds. */
    private static final long INPUT_POLL_US = 5_000;
    /**
     * How long a NAL unit waits for a free input buffer before it is dropped (#89): longer
     * than a frame at 30 fps, so a decoder that only falls behind for a moment loses nothing;
     * meanwhile the network queue of the stream takes the backlog.
     */
    static final long INPUT_WAIT_MS = 40;

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
    /**
     * Single view (#166): the decoder is configured for the stream's size from its SPS, and
     * KEY_MAX_WIDTH/HEIGHT covers it. Configured at 1280×720, some hardware decoders (Rockchip
     * OMX) never give a frame for streams above 1080p, e.g. 2304×1296. Off in the grid: nine
     * decoders with large buffers would not fit in memory.
     */
    private volatile boolean largeFrames;
    /** Picture size from the last SPS (largeFrames only); 0 while unknown. */
    private volatile int streamWidth;
    private volatile int streamHeight;
    /** An SPS arrived (decoderLock): with largeFrames no decoder is created before one. */
    private boolean spsSeen;
    /** The last H.265 VPS (decoderLock): a decoder created at the SPS needs it first. */
    private byte[] lastVps;
    /** Largest picture the current decoder was set up for; 0 when not known. */
    private int confWidth;
    private int confHeight;
    /**
     * The hardware decoder runs at a size it does not declare: without a frame by
     * {@link #probationUntilMs} the stream goes to a software decoder (#166).
     */
    private boolean probation;
    /** "mime WxH" the decoder on probation was set up for (Qodo on #220). */
    private String probationKey;
    private long probationUntilMs;
    private int probationFrames;
    /** How long a decoder on probation may go without a frame once it has its parameter sets. */
    static final long PROBATION_MS = 5_000;
    /** "mime WxH" of a stream the hardware decoder gave no frame for: software from then on. */
    private volatile String softwareFor;
    /** Name of the current decoder, for /stats.json. */
    private volatile String decoderName;
    /** Degrees counter-clockwise the decoder turns the picture (#201); 0 for none. */
    private volatile int rotation;
    /** The turn the current decoder applies: {@link #rotation} where Android 6+ can, else 0. */
    private volatile int appliedRotation;
    /** Mirror the picture (#226): the decoder renders through {@link GlMirror}. */
    private volatile boolean mirror;
    /** The GL path of the current decoder (decoderLock), released after the decoder. */
    private volatile GlMirror glMirror;
    /** The current decoder draws mirrored. */
    private volatile boolean mirrored;

    /** Frames rendered so far (#82): with the frames received this gives the real decode rate. */
    private volatile int framesOut;
    /** When the last frame was rendered (nowMs()), 0 if none since the decoder was closed (#98). */
    private volatile long lastFrameMs;
    /** NAL units not fed because the decoder had no free input buffer: it is not keeping up. */
    private volatile int inputDropped;
    /** closeDecoder() calls waiting for decoderLock: the input wait gives way to them. */
    private final AtomicInteger closeWaiting = new AtomicInteger();
    private volatile int outWidth;
    private volatile int outHeight;
    /** MIME type whose instance slot this manager holds while it has a decoder (#83). */
    private String heldMime;
    /**
     * Beyond the hardware instance limit, a software decoder may be used (#88): set while the
     * cell decodes key frames only, about one frame per second, which a software decoder
     * manages at little CPU cost.
     */
    private volatile boolean allowSoftware;
    private volatile boolean softwareInUse;
    private static final java.util.Map<String, String> SOFTWARE = new java.util.HashMap<>();

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
    /** The picture on the surface has its sides swapped: a quarter turn applied (#201). */
    boolean swapsSides() {
        int t = appliedRotation;
        return t == 90 || t == 270;
    }
    boolean hasDecoder() { return decoder != null; }
    /** Picture size from the stream's SPS ({width, height}), or null while unknown (#166). */
    int[] streamSize() {
        int w = streamWidth, h = streamHeight;
        return w > 0 && h > 0 ? new int[]{w, h} : null;
    }
    /** Name of the decoder in use, or null. */
    String decoderName() { return decoder != null ? decoderName : null; }

    /**
     * Decoders alive in this process per MIME type, against the declared limit of the codec
     * (MediaCodecInfo.CodecCapabilities#getMaxSupportedInstances, Android 6+). The limit is
     * learnt from the first decoder of a type, so it is the codec that createDecoderByType
     * really picks (#83).
     */
    /** Allow a software decoder beyond the hardware limit (key-frame cells, #88). */
    void setSoftwareFallback(boolean allow) { allowSoftware = allow; }

    /** The current decoder is a software one (it is never promoted to full rate). */
    boolean isSoftware() { return softwareInUse && decoder != null; }

    /** Budget key of the software decoders of a mime type: they have their own limit. */
    static String softwareSlot(String mime) { return "software:" + mime; }

    /**
     * A software decoder for the mime type (c2.android.* first), or null. Looked up once, with
     * its instance limit; a failed codec list is not cached, so a later call tries again.
     */
    static String softwareDecoder(String mime) {
        synchronized (SOFTWARE) {
            if (SOFTWARE.containsKey(mime)) return SOFTWARE.get(mime);
            MediaCodecInfo[] infos;
            try {
                infos = new android.media.MediaCodecList(
                        android.media.MediaCodecList.ALL_CODECS).getCodecInfos();
            } catch (RuntimeException e) {
                Log.w(TAG, "Codec list unavailable: " + e.getMessage());
                return null;
            }
            String found = null;
            int max = Integer.MAX_VALUE;
            for (MediaCodecInfo ci : infos) {
                // One broken entry must not hide the others
                try {
                    if (ci.isEncoder()) continue;
                    String n = ci.getName();
                    boolean sw = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? ci.isSoftwareOnly()
                            : n.startsWith("c2.android.") || n.startsWith("OMX.google.");
                    if (!sw) continue;
                    for (String t : ci.getSupportedTypes()) {
                        if (!t.equalsIgnoreCase(mime)
                                || (found != null && !n.startsWith("c2.android."))) continue;
                        // The limit first: a failed query keeps the previous candidate
                        int m = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                ? ci.getCapabilitiesForType(t).getMaxSupportedInstances()
                                : Integer.MAX_VALUE;
                        found = n;
                        max = m;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            if (found != null) {
                InstanceBudget.learn(softwareSlot(mime), max > 0 ? max : Integer.MAX_VALUE);
                Log.i(TAG, "Software decoder " + found + " (" + mime + "): up to " + max
                        + " instances");
            }
            SOFTWARE.put(mime, found);
            return found;
        }
    }

    static final class InstanceBudget {
        private static final Map<String, Integer> used = new HashMap<>();
        private static final Map<String, Integer> limit = new HashMap<>();

        static synchronized boolean tryAcquire(String mime) {
            int u = used.containsKey(mime) ? used.get(mime) : 0;
            Integer max = limit.get(mime);
            // Until the first decoder of a type has told its limit, start one at a time
            if (max == null ? u > 0 : u >= max) return false;
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

    /**
     * The decoder's declared throughput in 16x16 macroblocks per second, from the first decoder
     * created (0 while unknown). The starting capacity for multi-view planning (#85).
     */
    static volatile long declaredBlocksPerSec;

    /**
     * Detach the instance slot from this manager (decoderLock held). The caller gives it back
     * with {@link InstanceBudget#release} only after the codec itself is released, so another
     * cell cannot start a decoder while the device still holds the maximum.
     */
    private String takeSlot() {
        String m = heldMime;
        heldMime = null;
        return m;
    }

    private static void releaseSlot(String mime) {
        if (mime != null) InstanceBudget.release(mime);
    }

    /** Learn the codec's instance limit and log its declared throughput, once per type. */
    private static void learnLimits(MediaCodec codec, String mime) {
        if (InstanceBudget.limit(mime) >= 0) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            InstanceBudget.learn(mime, Integer.MAX_VALUE); // not declared before Android 6
            return;
        }
        try {
            MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo().getCapabilitiesForType(mime);
            int max = caps.getMaxSupportedInstances();
            InstanceBudget.learn(mime, max);
            MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
            String fps1080 = "?";
            if (video != null && video.isSizeSupported(1920, 1080)) {
                double fps = video.getSupportedFrameRatesFor(1920, 1080).getUpper();
                fps1080 = String.valueOf(fps);
                long blocks = DecodePlanner.fullLoad(1920, 1080, (float) fps);
                if (declaredBlocksPerSec == 0 || blocks < declaredBlocksPerSec) {
                    declaredBlocksPerSec = blocks;
                }
            }
            Log.i(TAG, "Decoder " + codec.getName() + " (" + mime + "): up to " + max
                    + " instances, declared 1080p frame rate in total " + fps1080);
        } catch (Exception e) {
            InstanceBudget.learn(mime, Integer.MAX_VALUE);
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

    /** See {@link #largeFrames}; takes effect with the next decoder. */
    void allowLargeFrames(boolean on) {
        largeFrames = on;
    }

    /** Largest supported size up to 3840×2160 (a few steps down), or null if none above 1080p. */
    static int[] maxFrameSize(MediaCodec codec, String mime) {
        try {
            MediaCodecInfo.VideoCapabilities video =
                    codec.getCodecInfo().getCapabilitiesForType(mime).getVideoCapabilities();
            if (video == null) return null;
            int[][] sizes = {{3840, 2160}, {2560, 1920}, {2560, 1440}, {2304, 1296}};
            for (int[] size : sizes) {
                if (video.isSizeSupported(size[0], size[1])) return size;
            }
        } catch (Exception ignored) {
            // Capabilities unknown: keep the decoder's defaults
        }
        return null;
    }

    /** The codec declares the size; unknown capabilities count as declared. */
    static boolean declaresSize(MediaCodec codec, String mime, int width, int height) {
        try {
            MediaCodecInfo.VideoCapabilities video =
                    codec.getCodecInfo().getCapabilitiesForType(mime).getVideoCapabilities();
            return video == null || video.isSizeSupported(width, height);
        } catch (Exception e) {
            return true;
        }
    }

    /** Above 1080p: a size some hardware decoders cannot be left to discover (#166). */
    static boolean aboveFullHd(int width, int height) {
        return (long) width * height > 1920L * 1088;
    }

    /** See {@link #rotation}; takes effect with the next decoder. */
    void setRotation(int degreesCcw) {
        rotation = ((degreesCcw % 360) + 360) % 360;
    }

    /** See {@link #mirror}; takes effect with the next decoder. */
    void setMirror(boolean on) {
        mirror = on;
    }

    /** Mirror is on, but the current decoder draws unmirrored: the GL path failed (#226). */
    boolean mirrorFailed() {
        GlMirror g = glMirror;
        return mirror && decoder != null && (!mirrored || (g != null && g.drawFailed()));
    }

    /** Detach the GL path of the current decoder (decoderLock held), like {@link #takeSlot}. */
    private GlMirror takeMirror() {
        GlMirror g = glMirror;
        glMirror = null;
        return g;
    }

    private static void releaseMirror(GlMirror g) {
        if (g != null) g.release();
    }

    /** Enable or disable decoding; disabling also releases the decoder. */
    /**
     * Enable or disable decoding. Never blocks the caller (the UI thread): disabling only
     * sets flags; the decoder is detached and released on a short-lived thread, which is
     * the only one that waits for decoderLock.
     */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        final int gen = ++lifecycle;
        if (enabled) return;
        final MediaCodec target = decoder;
        new Thread(() -> {
            String slot;
            GlMirror gl;
            synchronized (decoderLock) {
                // The stream that comes after the pause sends its own SPS (Qodo on #220)
                if (gen == lifecycle && !this.enabled) forgetStream();
                if (target == null || decoder != target) return; // already replaced or released
                decoder = null;
                slot = takeSlot();
                gl = takeMirror();
                retry.onSuccess();
            }
            releaseNow(target);
            // After the decoder: its output must be gone before GL leaves the surface
            releaseMirror(gl);
            releaseSlot(slot);
        }, "decoder-release").start();
    }

    private static void releaseNow(MediaCodec codec) {
        try { codec.stop(); } catch (Exception ignored) {}
        try { codec.release(); } catch (Exception ignored) {}
    }

    private static void releaseAsync(MediaCodec codec, GlMirror gl) {
        new Thread(() -> {
            releaseNow(codec);
            releaseMirror(gl);
        }, "decoder-release").start();
    }

    /** Release the decoder on purpose (codec switch, camera switch): no back-off. */
    void closeDecoder() {
        closeWaiting.incrementAndGet();
        try {
            closeDecoderLocked();
        } finally {
            closeWaiting.decrementAndGet();
        }
    }

    private void closeDecoderLocked() {
        synchronized (decoderLock) {
            // A creation still running (another thread, older settings) is discarded when it
            // ends: it must not replace the decoder the caller will make next (#226)
            lifecycle++;
            retry.onSuccess();
            MediaCodec codec = decoder;
            // The next stream must not look live because of this one's last frame
            lastFrameMs = 0;
            // Nor be set up for this one's size (#166)
            forgetStream();
            if (codec == null) return;
            decoder = null;
            String slot = takeSlot();
            GlMirror gl = takeMirror();
            Log.i(TAG, "Close video decoder");
            try {
                codec.stop();
                codec.release();
            } catch (Exception e) {
                Log.e(TAG, "Decoder close exception", e);
            }
            releaseMirror(gl);
            releaseSlot(slot);
        }
    }

    /** Stream size and parameter sets of the last stream (decoderLock held). */
    private void forgetStream() {
        spsSeen = false;
        streamWidth = 0;
        streamHeight = 0;
        lastVps = null;
        probation = false;
        probationKey = null;
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    void createDecoder(boolean codecH265) {
        createDecoder(codecH265, null);
    }

    /**
     * Like {@link #createDecoder(boolean)}; {@code current} (may be null) is checked under the
     * lock when the decoder is installed: false once the caller's stream was restarted with
     * other settings, and the decoder made with the old ones is dropped (Qodo on #246).
     */
    void createDecoder(boolean codecH265, JpegCodec.Current current) {
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

        // Single view: the stream's own size from its SPS (#166)
        final int width = largeFrames ? streamWidth : 0;
        final int height = largeFrames ? streamHeight : 0;
        final boolean sized = width > 0 && height > 0;
        MediaFormat format = MediaFormat.createVideoFormat(type,
                sized ? width : 1280, sized ? height : 720);
        // Must hold the largest NAL unit the assembler can deliver
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_NAL_SIZE);

        String softwareName = null;
        boolean noHardwareFrame = false;
        final String key = sizeKey(type, width, height);
        if (sized && key.equals(softwareFor)) {
            // The hardware decoder gave no frame at this size: a software one, once it has room
            String sw = softwareDecoder(type);
            if (sw != null && InstanceBudget.tryAcquire(softwareSlot(type))) {
                softwareName = sw;
                noHardwareFrame = true;
            } else if (sw != null) {
                // Not the hardware one again: it showed nothing at this size (Qodo on #220)
                synchronized (decoderLock) {
                    retry.onFailure(nowMs());
                }
                Log.w(TAG, "No room for a software decoder at " + width + "×" + height
                        + ", retrying");
                return;
            }
        }
        if (softwareName == null && !InstanceBudget.tryAcquire(type)) {
            int limit = InstanceBudget.limit(type);
            softwareName = limit >= 0 && allowSoftware ? softwareDecoder(type) : null;
            // Software decoders have their own limit: beyond it, the hardware one is reported
            if (softwareName != null && !InstanceBudget.tryAcquire(softwareSlot(type))) {
                softwareName = null;
            }
            if (softwareName == null) {
                synchronized (decoderLock) {
                    retry.onFailure(nowMs());
                }
                // Limit still unknown: another cell is creating the first decoder, just retry
                if (limit < 0) return;
                Log.w(TAG, "Decoder limit reached: " + limit + " × " + type);
                if (listener != null) listener.onDecoderLimit(type, limit);
                return;
            }
        }
        final boolean software = softwareName != null;
        final String slot = software ? softwareSlot(type) : type;

        MediaCodec local;
        boolean onProbation = false;
        int maxW = 0, maxH = 0;
        GlMirror gl = null;
        int turnApplied = 0;
        try {
            if (software) {
                Log.i(TAG, "Start software video decoder " + softwareName + (noHardwareFrame
                        ? " (no frame from the hardware one at " + width + "×" + height + ")"
                        : " (hardware limit reached, key frames only)"));
                local = MediaCodec.createByCodecName(softwareName);
            } else {
                Log.i(TAG, "Start video decoder (" + type + ")");
                local = MediaCodec.createDecoderByType(type);
                learnLimits(local, type);
            }
            if (largeFrames) {
                // Room for the stream and for the largest declared size, so the picture may
                // still grow without a new decoder
                int[] max = maxFrameSize(local, type);
                maxW = Math.max(sized ? width : 0, max != null ? max[0] : 0);
                maxH = Math.max(sized ? height : 0, max != null ? max[1] : 0);
                if (maxW > 0 && maxH > 0) {
                    format.setInteger(MediaFormat.KEY_MAX_WIDTH, maxW);
                    format.setInteger(MediaFormat.KEY_MAX_HEIGHT, maxH);
                }
                boolean declared = !sized || declaresSize(local, type, width, height);
                // Probation only where a software decoder can take over, and once per size
                onProbation = !software && sized && !declared && aboveFullHd(width, height)
                        && !key.equals(softwareFor)
                        && softwareDecoder(type) != null;
                Log.i(TAG, "Decoder " + local.getName() + ": stream "
                        + (sized ? width + "×" + height : "size unknown")
                        + (maxW > 0 ? ", pictures up to " + maxW + "×" + maxH : "")
                        + (declared ? "" : ", a size it does not declare"));
            }
            int turn = rotation;
            Surface target = s;
            if (mirror) {
                // Mirrored (#226): drawn with GL, which turns the picture as well
                try {
                    gl = GlMirror.create(s, turn, true);
                    target = gl.input();
                    Log.i(TAG, "Mirrored picture" + (turn != 0 ? ", turned " + turn
                            + "\u00b0 counter-clockwise" : "") + " (drawn with OpenGL ES)");
                } catch (Exception e) {
                    Log.w(TAG, "Mirror unavailable, the picture is shown as it comes: "
                            + e.getMessage());
                    gl = null;
                }
            }
            if (gl == null && turn != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // The decoder turns its output on the surface: clockwise degrees here (#201)
                format.setInteger(MediaFormat.KEY_ROTATION, (360 - turn) % 360);
            } else if (gl == null) {
                turn = 0;
            }
            turnApplied = turn;
            try {
                local.configure(format, target, null, 0);
                local.start();
                if (listener != null) listener.onDecoderStarted();
            } catch (Exception e) {
                try {
                    local.release();
                } finally {
                    releaseMirror(gl);
                    gl = null;
                }
                throw e;
            }
        } catch (Exception e) {
            InstanceBudget.release(slot);
            synchronized (decoderLock) {
                // A creation cancelled by a pause must not delay the next start
                if (gen != lifecycle || !enabled) return;
                retry.onFailure(nowMs());
                // The hardware decoder refused a size it does not declare: software next time
                if (onProbation) softwareFor = key;
            }
            Log.e(TAG, "Cannot setup decoder (attempt " + retry.failures() + "): " + e.getMessage());
            if (listener != null) listener.onDecoderFailed();
            return;
        }

        synchronized (decoderLock) {
            // Disabled meanwhile (pause/stop) or another thread was faster
            if (decoder != null || !enabled || gen != lifecycle
                    || (current != null && !current.holds())) {
                InstanceBudget.release(slot);
                releaseAsync(local, gl);
                return;
            }
            decoder = local;
            glMirror = gl;
            mirrored = gl != null;
            // Only the decoder that stays sets these: a creation that lost must not (#226)
            appliedRotation = turnApplied;
            // A software decoder holds a slot of its own budget, not of the hardware one
            heldMime = slot;
            softwareInUse = software;
            awaitingConfig = true;
            configSeen = 0;
            decoderName = local.getName();
            confWidth = maxW;
            confHeight = maxH;
            probation = onProbation;
            probationKey = onProbation ? key : null;
            probationUntilMs = 0;
            probationFrames = framesOut;
        }
    }

    private static String sizeKey(String mime, int width, int height) {
        return mime + " " + width + "x" + height;
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

        boolean isSps = fragment == (codecH265 ? H265_NAL_SPS : H264_NAL_SPS);
        boolean isVps = codecH265 && fragment == H265_NAL_VPS;
        // Single view: the stream's size, before any decoder is set up for it (#166)
        int[] spsSize = largeFrames && isSps
                ? SpsParser.size(buffer.data(), 4, buffer.length() - 4, codecH265) : null;

        boolean needCreate = false;
        synchronized (decoderLock) {
            MediaCodec codec = decoder;
            if (largeFrames) {
                if (isSps) {
                    spsSeen = true;
                    if (spsSize != null) {
                        streamWidth = spsSize[0];
                        streamHeight = spsSize[1];
                    }
                } else if (isVps) {
                    lastVps = java.util.Arrays.copyOf(buffer.data(), buffer.length());
                }
            }
            if (!enabled) {
                // Paused: the decoder is being released, feed nothing
                return false;
            } else if (codec == null) {
                // Single view: the decoder waits for the SPS, which gives its size (#166)
                if (largeFrames && !spsSeen) return false;
                needCreate = retry.mayCreate(nowMs());
            } else if (awaitingConfig && !isConfigNal) {
                // Frames before the full parameter set only produce errors in a new decoder
                return false;
            } else if (largeFrames && spsSize != null && confWidth > 0
                    && (spsSize[0] > confWidth || spsSize[1] > confHeight)) {
                // The picture outgrew the decoder: a new one set up for it takes this SPS
                Log.i(TAG, "Stream " + spsSize[0] + "×" + spsSize[1] + " is larger than "
                        + confWidth + "×" + confHeight + ": new decoder");
                dropDecoder(codec);
                return retry.mayCreate(nowMs());
            } else if (probation && probationExpired()) {
                // The size the decoder was set up for, not a later SPS's (Qodo on #220)
                Log.w(TAG, "No frame from " + decoderName + " (" + probationKey + ") in "
                        + PROBATION_MS / 1000 + " s: software decoder");
                softwareFor = probationKey;
                dropDecoder(codec);
                // The next decoder starts at the next SPS, which comes with a key frame
                return false;
            } else {
                if (isConfigNal) {
                    int needed = codecH265
                            ? (1 << H265_NAL_VPS) | (1 << H265_NAL_SPS) | (1 << H265_NAL_PPS)
                            : (1 << H264_NAL_SPS) | (1 << H264_NAL_PPS);
                    // A decoder created at the SPS has missed the VPS before it (#166)
                    if (isSps && awaitingConfig && (configSeen & (1 << H265_NAL_VPS)) == 0
                            && codecH265 && lastVps != null) {
                        configSeen |= 1 << H265_NAL_VPS;
                        try {
                            feed(codec, lastVps, lastVps.length, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                        } catch (Exception e) {
                            failDecoder(codec, e);
                            return false;
                        }
                    }
                    configSeen |= 1 << fragment;
                    if ((configSeen & needed) == needed && awaitingConfig) {
                        awaitingConfig = false;
                        // The probation runs from the first key frame on
                        if (probation) probationUntilMs = nowMs() + PROBATION_MS;
                    }
                }
                try {
                    feed(codec, buffer.data(), buffer.length(), flag);
                } catch (Exception e) {
                    failDecoder(codec, e);
                }
            }
        }
        return needCreate;
    }

    /**
     * Queue one NAL unit (decoderLock held). No free input buffer at once: give decoded frames
     * back (the decoder may be waiting for output space) and try again, for up to
     * INPUT_WAIT_MS. Dropping a NAL unit corrupts the picture until the next key frame (#89).
     */
    private void feed(MediaCodec codec, byte[] data, int length, int flag) {
        int inputBufferId = codec.dequeueInputBuffer(INPUT_POLL_US);
        long waitUntil = nowMs() + INPUT_WAIT_MS;
        // A close or a pause waiting for the lock ends the wait at once (UI thread)
        while (inputBufferId < 0 && nowMs() < waitUntil && enabled
                && closeWaiting.get() == 0) {
            drainOutput(codec);
            inputBufferId = codec.dequeueInputBuffer(INPUT_POLL_US);
        }
        if (inputBufferId >= 0) {
            ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
            if (inputBuffer != null) {
                inputBuffer.clear();
                inputBuffer.put(data, 0, length);
                codec.queueInputBuffer(inputBufferId, 0, length, System.nanoTime() / 1000, flag);
            }
        } else if (enabled && closeWaiting.get() == 0) {
            // Only a full wait is a decoder loss; a close or pause cut it short
            inputDropped++;
        }
        drainOutput(codec);
    }

    /** The decoder failed while decoding (decoderLock held): release it, back off. */
    private void failDecoder(MediaCodec codec, Exception e) {
        // A hardware decoder that fails at a size it does not declare: software next time
        if (probation && probationKey != null) softwareFor = probationKey;
        probation = false;
        decoder = null;
        String slot = takeSlot();
        GlMirror gl = takeMirror();
        retry.onFailure(nowMs());
        Log.e(TAG, "Codec exception, retry in "
                + DecoderRetry.delayMs(retry.failures()) + " ms: " + e.getMessage());
        releaseNow(codec);
        // After the decoder: its output must be gone before GL leaves the surface
        releaseMirror(gl);
        releaseSlot(slot);
        if (listener != null) listener.onDecoderFailed();
    }

    /** Release a working decoder to replace it (decoderLock held): no back-off. */
    private void dropDecoder(MediaCodec codec) {
        decoder = null;
        probation = false;
        String slot = takeSlot();
        GlMirror gl = takeMirror();
        releaseNow(codec);
        releaseMirror(gl);
        releaseSlot(slot);
    }

    /** The decoder on probation has its parameter sets but no frame past the deadline. */
    private boolean probationExpired() {
        if (framesOut != probationFrames) {
            // A frame came: the hardware decoder manages the size
            probation = false;
            return false;
        }
        return probationUntilMs > 0 && nowMs() > probationUntilMs;
    }

    /**
     * The picture's size: the crop rectangle when the decoder gives one. KEY_WIDTH / KEY_HEIGHT
     * are the buffer, padded to the decoder's alignment (1080 → 1088; one H.265 decoder made
     * 1920 wide 2304), which skewed the picture's aspect ratio and the size shown (#166).
     */
    static int[] visibleSize(MediaFormat format) {
        return visibleSize(format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT),
                cropKey(format, "crop-left"), cropKey(format, "crop-top"),
                cropKey(format, "crop-right"), cropKey(format, "crop-bottom"));
    }

    private static int cropKey(MediaFormat format, String key) {
        return format.containsKey(key) ? format.getInteger(key) : -1;
    }

    /** As above from plain values; a crop value < 0 is missing. Crop right / bottom are inclusive. */
    static int[] visibleSize(int width, int height, int left, int top, int right, int bottom) {
        // A crop edge outside the buffer is not believed: that axis keeps the buffer size
        int w = left >= 0 && right > left && right < width ? right - left + 1 : width;
        int h = top >= 0 && bottom > top && bottom < height ? bottom - top + 1 : height;
        return new int[]{w, h};
    }

    /** Renders every decoded frame that is ready; caller holds decoderLock. */
    private void drainOutput(MediaCodec codec) {
        MediaCodec.BufferInfo info = bufferInfo;
        int outputBufferId;
        while ((outputBufferId = codec.dequeueOutputBuffer(info, 0)) >= 0
                || outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                int[] size = visibleSize(codec.getOutputFormat());
                outWidth = size[0];
                outHeight = size[1];
                if (listener != null) {
                    listener.onResolutionChanged(size[0], size[1]);
                }
            } else {
                codec.releaseOutputBuffer(outputBufferId, true);
                framesOut++;
                lastFrameMs = nowMs();
                retry.onSuccess(); // a decoded frame: the decoder works
            }
        }
    }
}
