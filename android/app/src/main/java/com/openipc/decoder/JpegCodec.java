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
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .getCodecInfos()) {
                if (ci.isEncoder()) continue;
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
        if (result.length > 0) Log.i(TAG, "MJPEG decoder: " + result[0] + " (" + result[1] + ")");
        return result.length == 0 ? null : result;
    }

    /**
     * Decodes one JPEG of {@code w}×{@code h} to {@code target}; (re)creates the decoder when
     * the size or surface changes. Returns true when a frame was rendered. Throws when the
     * decoder cannot be used: the caller then switches to the software path.
     */
    synchronized boolean decode(Frame frame, Surface target, int w, int h) throws Exception {
        String[] dec = find();
        if (dec == null) throw new IllegalStateException("no JPEG decoder");
        if (codec == null || target != surface || w != width || h != height) {
            releaseLocked();
            MediaCodec c = MediaCodec.createByCodecName(dec[0]);
            try {
                MediaFormat format = MediaFormat.createVideoFormat(dec[1], w, h);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(frame.length() * 2,
                        512 * 1024));
                c.configure(format, target, null, 0);
                c.start();
            } catch (Exception e) {
                c.release();
                throw e;
            }
            codec = c;
            surface = target;
            width = w;
            height = h;
            Log.i(TAG, "Start MJPEG decoder " + dec[0] + " " + w + "x" + h);
        }
        int in = codec.dequeueInputBuffer(40_000);
        if (in >= 0) {
            ByteBuffer b = codec.getInputBuffer(in);
            if (b != null && b.capacity() >= frame.length()) {
                b.clear();
                b.put(frame.data(), 0, frame.length());
                codec.queueInputBuffer(in, 0, frame.length(), System.nanoTime() / 1000, 0);
            } else {
                codec.queueInputBuffer(in, 0, 0, 0, 0);
            }
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
