/*
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * AudioManager.java — audio playback and AAC decoding
 *
 */

package com.openipc.decoder;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class AudioManager {
    private static final String TAG = "OpenIPCDecoder";

    private volatile AudioTrack audioTrack;
    private volatile MediaCodec aacDecoder;
    private final Object aacDecoderLock = new Object();
    private volatile boolean aacRunning;
    volatile boolean audioFailed;

    private final BlockingQueue<Frame> pcmQueue = new ArrayBlockingQueue<>(32);
    private final BlockingQueue<byte[]> aacQueue = new ArrayBlockingQueue<>(32);
    private final ExecutorService executor;

    // Audio params set by the RTSP client via configure()
    private volatile int audioSampleRate = 8000;
    private volatile int audioPt;
    private volatile boolean audioBigEndian = true;
    private volatile String audioCodec = "PCM"; // "PCM", "AAC", "G711"

    AudioManager() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rtsp-audio-playback");
            return t;
        });
    }

    void shutdown() {
        closeAudio();
        executor.shutdownNow();
    }

    void configure(int sampleRate, int pt, boolean bigEndian, String codec) {
        this.audioSampleRate = sampleRate;
        this.audioPt = pt;
        this.audioBigEndian = bigEndian;
        this.audioCodec = codec;
    }

    /** Called from the network thread to deliver an audio RTP frame. */
    void onAudioFrame(Frame frame) {
        processAudio(frame);
    }

    void closeAudio() {
        aacRunning = false;
        aacQueue.clear();
        AudioTrack track = audioTrack;
        if (track != null) {
            audioTrack = null;
            Log.i(TAG, "Close audio decoder");
            try {
                track.stop();
                track.release();
            } catch (Exception e) {
                Log.e(TAG, "Audio close exception", e);
            }
        }
        synchronized (aacDecoderLock) {
            MediaCodec aac = aacDecoder;
            if (aac != null) {
                aacDecoder = null;
                try { aac.stop(); } catch (Exception ignored) {}
                try { aac.release(); } catch (Exception ignored) {}
            }
        }
        aacQueue.clear();
        audioFailed = false;
    }

    private void processAudio(Frame frame) {
        int header = 12;
        int length = frame.length() - header;
        if (length <= 0) return;

        byte[] audioData = new byte[length];
        System.arraycopy(frame.data(), header, audioData, 0, length);

        if ("AAC".equals(audioCodec)) {
            processAacFrame(audioData);
        } else if ("G711".equals(audioCodec)) {
            Log.d(TAG, "G.711 audio not yet implemented");
        } else {
            // PCM
            if (audioBigEndian) {
                for (int i = 0; i + 1 < length; i += 2) {
                    byte tmp = audioData[i];
                    audioData[i] = audioData[i + 1];
                    audioData[i + 1] = tmp;
                }
            }
            if (!pcmQueue.offer(new Frame(audioData, length))) {
                Log.w(TAG, "Audio queue full, frame dropped");
            }
        }
    }

    private void processAacFrame(byte[] aacData) {
        if (!aacQueue.offer(aacData)) {
            Log.w(TAG, "AAC audio queue full, frame dropped");
        }
    }

    private void playAudio(Frame data) {
        AudioTrack track = audioTrack;
        if (track == null) {
            if (!audioFailed) {
                createAudio();
            }
        } else {
            byte[] buf = data.data();
            int offset = 0;
            int remaining = data.length();
            while (remaining > 0) {
                AudioTrack t = audioTrack;
                if (t == null) break;
                int written = t.write(buf, offset, remaining);
                if (written < 0) {
                    Log.e(TAG, "AudioTrack.write() error: " + written);
                    break;
                }
                offset += written;
                remaining -= written;
            }
        }
    }

    private void createAudio() {
        if ("AAC".equals(audioCodec)) {
            createAacDecoder();
        } else {
            createPcmAudio();
        }
    }

    private void createPcmAudio() {
        int sample = audioSampleRate;
        int format = AudioFormat.CHANNEL_OUT_MONO;

        Log.d(TAG, "Create PCM audio (" + sample + "hz)");
        int size = AudioTrack.getMinBufferSize(sample, format, AudioFormat.ENCODING_PCM_16BIT);
        if (size <= 0) {
            Log.e(TAG, "Invalid PCM audio parameters: sample=" + sample + " bufSize=" + size);
            audioFailed = true;
            return;
        }

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sample)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize, releasing");
            track.release();
            audioFailed = true;
            return;
        }

        audioTrack = track;
        try {
            track.play();
        } catch (Exception e) {
            audioTrack = null;
            track.release();
            Log.e(TAG, "AudioTrack.play() failed", e);
            audioFailed = true;
        }
    }

    private void createAacDecoder() {
        Log.d(TAG, "Creating AAC decoder");
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                audioSampleRate, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_IS_ADTS, 1);

        MediaCodec codec;
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            try {
                codec.configure(fmt, null, null, 0);
                codec.start();
            } catch (Exception e) {
                codec.release();
                throw e;
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot create AAC decoder, falling back to PCM", e);
            createPcmAudio();
            return;
        }

        synchronized (aacDecoderLock) {
            MediaCodec old = aacDecoder;
            if (old != null) {
                try { old.stop(); } catch (Exception ignored) {}
                old.release();
            }
            aacDecoder = codec;
        }

        aacRunning = true;
        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-aac-decode");
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (aacRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] aacData = aacQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (aacData == null) continue;

                    synchronized (aacDecoderLock) {
                        if (aacDecoder != codec) break;
                    }

                    boolean hasAdts = (aacData.length >= 2
                            && (aacData[0] & 0xFF) == 0xFF
                            && (aacData[1] & 0xF0) == 0xF0);

                    int inputId = codec.dequeueInputBuffer(10_000);
                    if (inputId < 0) continue;

                    ByteBuffer inBuf = codec.getInputBuffer(inputId);
                    if (inBuf == null) continue;
                    inBuf.clear();
                    if (hasAdts) {
                        inBuf.put(aacData, 0, aacData.length);
                    } else {
                        inBuf.put(aacData);
                    }
                    codec.queueInputBuffer(inputId, 0, aacData.length,
                            System.nanoTime() / 1000, 0);

                    int outId;
                    while ((outId = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                        if (outId >= 0) {
                            ByteBuffer outBuf = codec.getOutputBuffer(outId);
                            int size = info.size;
                            if (outBuf != null && size > 0) {
                                byte[] pcm = new byte[size];
                                outBuf.get(pcm);
                                codec.releaseOutputBuffer(outId, false);
                                Frame data = new Frame(pcm, pcm.length);
                                playAudio(data);
                            } else {
                                codec.releaseOutputBuffer(outId, false);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "AAC decode error", e);
                }
            }
        });
    }

    /** Called from Decoder's audio playback thread for PCM data. */
    void playPcmFrame() {
        try {
            Frame buffer = pcmQueue.poll(5, TimeUnit.MILLISECONDS);
            if (buffer != null) playAudio(buffer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
