/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * JpegCodec.java — hardware MJPEG decoding through MediaCodec, where the device has it (#132)
 *
 */

package com.openipc.decoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * A JPEG decoder from the device's codec list ({@code video/jpeg} on Allwinner boxes such as
 * the AG-216, {@code video/mjpeg} elsewhere) fed with the whole frames of JpegAssembler and
 * rendering to the video surface like the H.264/H.265 decoder. The caller falls back to
 * BitmapFactory when there is none or it fails. Used by one decode thread; release() may come
 * from others, hence synchronized.
 */
final class JpegCodec {
    private static final String TAG = "OpenIPCDecoder";
    private static final String[] MIMES = {"video/jpeg", "video/mjpeg", "video/x-motion-jpeg"};
    private static volatile String[] found;
    /**
     * Vendor JPEG decoders a platform may leave out of its codec list although it can create
     * them by name: the AG-216 declares this one in /vendor/etc/media_codecs.xml, but Android
     * 10 does not list video/jpeg (#132). Tried once when the list has none.
     */
    private static final String[][] BY_NAME = {{"OMX.allwinner.video.decoder.mjpeg", "video/jpeg"}};
    /** The BY_NAME decoder could not be created: no hardware JPEG decoder on this device. */
    private static volatile boolean byNameFailed;
    /** The decoder that worked, once one did. */
    private static volatile String[] chosen;

    private MediaCodec codec;
    private Surface surface;
    private int width;
    private int height;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    /** {name, mime} of a hardware-or-vendor JPEG decoder, or null; looked up once. */
    static String[] find() {
        String[] f = found;
        if (f != null) return f.length == 0 ? null : f;
        String[] result = new String[0];
        try {
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.ALL_CODECS)
                    .getCodecInfos()) {
                if (ci.isEncoder() || softwareOnly(ci)) continue;
                for (String type : ci.getSupportedTypes()) {
                    for (String m : MIMES) {
                        if (m.equalsIgnoreCase(type) && result.length == 0) {
                            result = new String[] {ci.getName(), type};
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Codec list: " + e.getMessage());
        }
        found = result;
        if (result.length > 0) {
            Log.i(TAG, "MJPEG decoder: " + result[0] + " (" + result[1] + ")");
        } else {
            // Which video decoders the codec list offers instead (the vendor list may name a
            // JPEG decoder that the platform does not expose)
            StringBuilder all = new StringBuilder();
            try {
                for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.ALL_CODECS)
                        .getCodecInfos()) {
                    if (ci.isEncoder()) continue;
                    for (String type : ci.getSupportedTypes()) {
                        if (type.startsWith("video/")) all.append(' ').append(type);
                    }
                }
            } catch (RuntimeException ignored) {
            }
            Log.i(TAG, "No MJPEG decoder; video decoders:" + all);
        }
        return result.length == 0 ? null : result;
    }

    /**
     * A software codec does no better than BitmapFactory and its load is not the hardware's:
     * the software path takes the stream instead (Qodo on #139).
     */
    private static boolean softwareOnly(MediaCodecInfo ci) {
        if (android.os.Build.VERSION.SDK_INT >= 29) return ci.isSoftwareOnly();
        String n = ci.getName().toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.");
    }

    /**
     * Decodes one JPEG of {@code w}×{@code h} to {@code target}; (re)creates the decoder when
     * the size or surface changes. Returns true when a frame was rendered. Throws when the
     * decoder cannot be used: the caller then switches to the software path.
     */
    /** A listed decoder, or one of BY_NAME once; null when there is none at all. */
    static String[] candidate() {
        if (chosen != null) return chosen;
        String[] dec = find();
        if (dec != null) return dec;
        // Also while another stream is still trying it (Qodo on #139)
        return byNameFailed ? null : BY_NAME[0];
    }

    /** Frames queued in a row without any output: the decoder is not working (Qodo on #135). */
    static final int MAX_SILENT_FRAMES = 15;
    private int silentFrames;
    /** Frames the decoder had no input buffer for: decoder losses for the planner (#85). */
    private volatile int inputDropped;

    int inputDropped() { return inputDropped; }

    /** Checked under the lock: false once the stream that called decode() has stopped. */
    interface Current {
        boolean holds();
    }

    synchronized boolean decode(Frame frame, Surface target, int w, int h, Current current)
            throws Exception {
        if (!current.holds()) return false;
        if (codec == null || target != surface || w != width || h != height) {
            releaseLocked();
            MediaCodec c;
            String[] dec;
            // One stream at a time chooses the decoder, so the cells that start together do
            // not take a probe in progress for "no decoder" (Qodo on #139)
            synchronized (JpegCodec.class) {
                dec = chosen != null ? chosen : find();
                boolean byName = dec == null;
                if (byName) {
                    if (byNameFailed) throw new IllegalStateException("no JPEG decoder");
                    dec = BY_NAME[0];
                }
                try {
                    c = MediaCodec.createByCodecName(dec[0]);
                } catch (Exception e) {
                    if (byName) byNameFailed = true;
                    throw e;
                }
                try {
                    MediaFormat format = MediaFormat.createVideoFormat(dec[1], w, h);
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
                            Math.max(frame.length() * 2, 512 * 1024));
                    c.configure(format, target, null, 0);
                    c.start();
                } catch (Exception e) {
                    c.release();
                    // Never worked on this device: the name is not a usable decoder
                    if (byName && chosen == null) byNameFailed = true;
                    throw e;
                }
                chosen = dec;
            }
            codec = c;
            silentFrames = 0;
            surface = target;
            width = w;
            height = h;
            Log.i(TAG, "Start MJPEG decoder " + dec[0] + " " + w + "x" + h);
        }
        int in = codec.dequeueInputBuffer(40_000);
        if (in >= 0) {
            ByteBuffer b = codec.getInputBuffer(in);
            if (b == null || b.capacity() < frame.length()) {
                // A frame larger than the decoder's input buffers: let the software path have it
                codec.queueInputBuffer(in, 0, 0, 0, 0);
                throw new IllegalStateException("JPEG frame of " + frame.length()
                        + " bytes does not fit the input buffer");
            }
            b.clear();
            b.put(frame.data(), 0, frame.length());
            codec.queueInputBuffer(in, 0, frame.length(), System.nanoTime() / 1000, 0);
        } else {
            inputDropped++;
        }
        boolean rendered = false;
        int out;
        while ((out = codec.dequeueOutputBuffer(info, in >= 0 ? 20_000 : 0)) >= 0
                || out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (out >= 0) {
                codec.releaseOutputBuffer(out, true);
                rendered = true;
            }
            in = -1; // after the first output, only take what is ready
        }
        silentFrames = rendered ? 0 : silentFrames + 1;
        if (silentFrames > MAX_SILENT_FRAMES) {
            throw new IllegalStateException(MAX_SILENT_FRAMES + " frames without output");
        }
        return rendered;
    }

    synchronized void release() {
        releaseLocked();
    }

    private void releaseLocked() {
        MediaCodec c = codec;
        codec = null;
        surface = null;
        if (c == null) return;
        try {
            c.stop();
        } catch (Exception ignored) {
        }
        try {
            c.release();
        } catch (Exception ignored) {
        }
    }
}
