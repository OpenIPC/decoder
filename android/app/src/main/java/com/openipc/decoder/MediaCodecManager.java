/*
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * MediaCodecManager.java — video decoder lifecycle management
 *
 */

package com.openipc.decoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

class MediaCodecManager {
    private static final String TAG = "OpenIPCDecoder";

    // NAL type constants
    private static final int H265_NAL_VPS = 32;
    private static final int H265_NAL_SPS = 33;
    private static final int H265_NAL_PPS = 34;
    private static final int H264_NAL_SPS = 7;
    private static final int H264_NAL_PPS = 8;

    private volatile MediaCodec decoder;
    private final Object decoderLock = new Object();
    private volatile boolean decoderFailed;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    // Surface set by the owning activity
    private volatile Surface surface;

    interface Listener {
        void onResolutionChanged(int width, int height);
        void onDecoderStarted();
        void onDecoderFailed();
    }

    private Listener listener;

    MediaCodecManager(Listener listener) {
        this.listener = listener;
    }

    void setSurface(Surface s) {
        this.surface = s;
    }

    void closeDecoder() {
        synchronized (decoderLock) {
            MediaCodec codec = decoder;
            if (codec == null) { decoderFailed = false; return; }
            decoder = null;
            Log.i(TAG, "Close video decoder");
            try {
                codec.stop();
                codec.release();
            } catch (Exception e) {
                Log.e(TAG, "Decoder close exception", e);
            }
            decoderFailed = false;
        }
    }

    void createDecoder(boolean codecH265) {
        synchronized (decoderLock) {
            if (decoder != null) return;
        }

        Surface s = surface;
        if (s == null || !s.isValid()) {
            Log.w(TAG, "Cannot create decoder: surface not ready");
            return;
        }

        String type = codecH265 ? "video/hevc" : "video/avc";

        MediaFormat format = MediaFormat.createVideoFormat(type, 1280, 720);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);

        MediaCodec local;
        try {
            Log.i(TAG, "Start video decoder (" + type + ")");
            local = MediaCodec.createDecoderByType(type);
            try {
                local.configure(format, s, null, 0);
                local.start();
                if (listener != null) listener.onDecoderStarted();
            } catch (Exception e) {
                local.release();
                throw e;
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot setup decoder: " + e.getMessage());
            decoderFailed = true;
            return;
        }

        synchronized (decoderLock) {
            if (decoder != null) {
                local.release();
                return;
            }
            decoder = local;
        }
    }

    /** Decode one NAL frame. Returns true if the decoder was successfully used. */
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
            if (codec == null) {
                needCreate = !decoderFailed;
            } else {
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
                    }

                    MediaCodec.BufferInfo info = bufferInfo;
                    int outputBufferId;
                    while ((outputBufferId = codec.dequeueOutputBuffer(info, 0)) >= 0
                            || outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat format = codec.getOutputFormat();
                            int mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                            int mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                            if (listener != null) {
                                listener.onResolutionChanged(mWidth, mHeight);
                            }
                        } else {
                            codec.releaseOutputBuffer(outputBufferId, true);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Codec exception: " + e.getMessage());
                    decoder = null;
                    decoderFailed = true;
                    try { codec.stop(); } catch (Exception ignored) {}
                    try { codec.release(); } catch (Exception ignored) {}
                    if (listener != null) listener.onDecoderFailed();
                }
            }
        }
        return needCreate;
    }
}
