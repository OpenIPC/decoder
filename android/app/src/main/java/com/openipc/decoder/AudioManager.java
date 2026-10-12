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
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Audio pipeline: RTP payload → (G.711 / L16 conversion | AAC MediaCodec) → pcmQueue →
 * AudioTrack. The AudioTrack is written only from the playback thread via playPcmFrame().
 */
class AudioManager {
    private static final String TAG = "OpenIPCDecoder";

    private static final int[] AAC_SAMPLE_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350
    };

    private static final short[] ULAW_TABLE = new short[256];
    private static final short[] ALAW_TABLE = new short[256];

    static {
        for (int i = 0; i < 256; i++) {
            ULAW_TABLE[i] = ulawToLinear(i);
            ALAW_TABLE[i] = alawToLinear(i);
        }
    }

    private final AtomicReference<AudioTrack> audioTrackRef = new AtomicReference<>();
    // Parameters of the current AudioTrack; touched only by the playback thread
    private int trackSampleRate;
    private int trackChannels;
    // Parameters of the PCM currently being queued
    private volatile int pcmSampleRate = 8000;
    private volatile int pcmChannels = 1;

    private volatile MediaCodec aacDecoder;
    private final Object aacDecoderLock = new Object();
    private volatile boolean aacRunning;
    private ExecutorService aacExecutor;
    volatile boolean audioFailed;

    private final BlockingQueue<Frame> pcmQueue = new ArrayBlockingQueue<>(32);
    private final BlockingQueue<byte[]> aacQueue = new ArrayBlockingQueue<>(32);

    private volatile AudioConfig config = new AudioConfig();
    private String lastUnsupported;

    void shutdown() {
        closeAudio();
        synchronized (aacDecoderLock) {
            if (aacExecutor != null) {
                aacExecutor.shutdownNow();
                aacExecutor = null;
            }
        }
    }

    void configure(AudioConfig audio) {
        config = audio;
        if (!AudioConfig.AAC.equals(audio.codec)) {
            pcmSampleRate = audio.sampleRate;
            pcmChannels = audio.channels;
        }
    }

    /** Called from the network thread to deliver an audio RTP packet. */
    void onAudioFrame(Frame frame) {
        byte[] data = frame.data();
        int start = RtspClient.rtpHeaderLength(data, frame.length());
        if (start < 0) return;
        int end = RtspClient.rtpPayloadEnd(data, frame.length());
        if (end <= start) return;

        AudioConfig c = config;
        switch (c.codec) {
            case AudioConfig.AAC:
                processAac(c, data, start, end);
                break;
            case AudioConfig.PCMU:
                offerPcm(decodeG711(data, start, end, ULAW_TABLE));
                break;
            case AudioConfig.PCMA:
                offerPcm(decodeG711(data, start, end, ALAW_TABLE));
                break;
            case AudioConfig.PCM:
                offerPcm(decodeL16(data, start, end, c.bigEndian));
                break;
            default:
                if (!c.codec.equals(lastUnsupported)) {
                    lastUnsupported = c.codec;
                    Log.w(TAG, "Audio codec not supported, audio disabled (pt " + c.pt + ")");
                }
                break;
        }
    }

    void closeAudio() {
        aacRunning = false;
        aacQueue.clear();
        pcmQueue.clear();
        AudioTrack track = audioTrackRef.getAndSet(null);
        if (track != null) {
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
        lastUnsupported = null;
        audioFailed = false;
    }

    /** Called from Decoder's audio playback thread. */
    void playPcmFrame() {
        try {
            Frame buffer = pcmQueue.poll(5, TimeUnit.MILLISECONDS);
            if (buffer != null) playAudio(buffer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ====================== PCM ======================

    private void offerPcm(byte[] pcm) {
        if (pcm.length == 0) return;
        if (!pcmQueue.offer(new Frame(pcm, pcm.length))) {
            Log.w(TAG, "Audio queue full, frame dropped");
        }
    }

    static byte[] decodeG711(byte[] data, int start, int end, short[] table) {
        byte[] pcm = new byte[(end - start) * 2];
        for (int i = start, o = 0; i < end; i++, o += 2) {
            short s = table[data[i] & 0xFF];
            pcm[o] = (byte) s;
            pcm[o + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    static byte[] decodeL16(byte[] data, int start, int end, boolean bigEndian) {
        int len = (end - start) & ~1;
        byte[] pcm = Arrays.copyOfRange(data, start, start + len);
        if (bigEndian) {
            for (int i = 0; i + 1 < len; i += 2) {
                byte tmp = pcm[i];
                pcm[i] = pcm[i + 1];
                pcm[i + 1] = tmp;
            }
        }
        return pcm;
    }

    static short ulawToLinear(int u) {
        u = ~u & 0xFF;
        int t = ((u & 0x0F) << 3) + 0x84;
        t <<= (u & 0x70) >> 4;
        return (short) ((u & 0x80) != 0 ? 0x84 - t : t - 0x84);
    }

    static short alawToLinear(int a) {
        a ^= 0x55;
        int t = (a & 0x0F) << 4;
        int seg = (a & 0x70) >> 4;
        if (seg == 0) {
            t += 8;
        } else {
            t += 0x108;
            if (seg > 1) t <<= seg - 1;
        }
        return (short) ((a & 0x80) != 0 ? t : -t);
    }

    private void playAudio(Frame data) {
        int rate = pcmSampleRate;
        int channels = pcmChannels;
        AudioTrack track = audioTrackRef.get();
        if (track == null || rate != trackSampleRate || channels != trackChannels) {
            if (audioFailed) return;
            track = createPcmAudio(rate, channels);
            if (track == null) return;
        }

        byte[] buf = data.data();
        int offset = 0;
        int remaining = data.length();
        while (remaining > 0) {
            if (audioTrackRef.get() != track) break; // closed concurrently
            int written = track.write(buf, offset, remaining);
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write() error: " + written);
                break;
            }
            offset += written;
            remaining -= written;
        }
    }

    private AudioTrack createPcmAudio(int sample, int channels) {
        int mask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;

        Log.d(TAG, "Create PCM audio (" + sample + "hz, " + channels + "ch)");
        int size = AudioTrack.getMinBufferSize(sample, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (size <= 0) {
            Log.e(TAG, "Invalid PCM audio parameters: sample=" + sample + " bufSize=" + size);
            audioFailed = true;
            return null;
        }

        // AudioTrack.Builder needs API 23; this constructor works from API 21 (minSdk)
        AudioTrack track = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sample)
                        .setChannelMask(mask)
                        .build(),
                size, AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize, releasing");
            track.release();
            audioFailed = true;
            return null;
        }

        try {
            track.play();
        } catch (Exception e) {
            track.release();
            Log.e(TAG, "AudioTrack.play() failed", e);
            audioFailed = true;
            return null;
        }

        AudioTrack old = audioTrackRef.getAndSet(track);
        if (old != null) {
            try { old.stop(); } catch (Exception ignored) {}
            old.release();
        }
        trackSampleRate = sample;
        trackChannels = channels;
        return track;
    }

    // ====================== AAC ======================

    private void processAac(AudioConfig c, byte[] data, int start, int end) {
        if (c.sizeLength > 0) {
            // RFC 3640 mpeg4-generic: AU-headers-length (bits), AU headers, then AU data
            if (end - start < 2) return;
            int headerBits = ((data[start] & 0xFF) << 8) | (data[start + 1] & 0xFF);
            int bitPos = (start + 2) * 8;
            int bitEnd = bitPos + headerBits;
            int pos = start + 2 + (headerBits + 7) / 8;
            if (pos > end) return;
            ensureAacDecoder(c, false);
            boolean first = true;
            while (bitPos + c.sizeLength <= bitEnd) {
                int size = readBits(data, bitPos, c.sizeLength);
                bitPos += c.sizeLength + (first ? c.indexLength : c.indexDeltaLength);
                first = false;
                // AUs fragmented across packets are not supported
                if (size <= 0 || pos + size > end) break;
                offerAac(Arrays.copyOfRange(data, pos, pos + size));
                pos += size;
            }
        } else if (end - start >= 7 && isAdtsSync(data, start)) {
            ensureAacDecoder(c, true);
            int pos = start;
            while (end - pos >= 7 && isAdtsSync(data, pos)) {
                int frameLen = ((data[pos + 3] & 0x03) << 11)
                        | ((data[pos + 4] & 0xFF) << 3)
                        | ((data[pos + 5] & 0xE0) >>> 5);
                if (frameLen < 7 || pos + frameLen > end) break;
                offerAac(Arrays.copyOfRange(data, pos, pos + frameLen));
                pos += frameLen;
            }
        } else {
            // Raw access unit without AU headers
            ensureAacDecoder(c, false);
            offerAac(Arrays.copyOfRange(data, start, end));
        }
    }

    private static boolean isAdtsSync(byte[] data, int pos) {
        return (data[pos] & 0xFF) == 0xFF && (data[pos + 1] & 0xF6) == 0xF0;
    }

    static int readBits(byte[] data, int bitPos, int count) {
        int value = 0;
        for (int i = 0; i < count; i++, bitPos++) {
            int bit = (data[bitPos >> 3] >> (7 - (bitPos & 7))) & 1;
            value = (value << 1) | bit;
        }
        return value;
    }

    private void offerAac(byte[] au) {
        if (!aacQueue.offer(au)) {
            Log.w(TAG, "AAC audio queue full, frame dropped");
        }
    }

    /** AAC-LC AudioSpecificConfig built from the sample rate and channel count. */
    static byte[] buildAacConfig(int sampleRate, int channels) {
        int index = 4; // 44100
        for (int i = 0; i < AAC_SAMPLE_RATES.length; i++) {
            if (AAC_SAMPLE_RATES[i] == sampleRate) { index = i; break; }
        }
        int objectType = 2; // AAC LC
        return new byte[] {
                (byte) ((objectType << 3) | (index >> 1)),
                (byte) (((index & 1) << 7) | (channels << 3))
        };
    }

    private void ensureAacDecoder(AudioConfig c, boolean adts) {
        if (aacDecoder != null || audioFailed) return;
        synchronized (aacDecoderLock) {
            if (aacDecoder != null || audioFailed) return;

            Log.d(TAG, "Creating AAC decoder (" + c.sampleRate + "hz, " + c.channels + "ch, "
                    + (adts ? "ADTS" : "raw") + ")");
            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                    c.sampleRate, c.channels);
            if (adts) {
                fmt.setInteger(MediaFormat.KEY_IS_ADTS, 1);
            } else {
                byte[] asc = c.aacConfig != null ? c.aacConfig
                        : buildAacConfig(c.sampleRate, c.channels);
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
                fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            }

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
                Log.e(TAG, "Cannot create AAC decoder, audio disabled", e);
                audioFailed = true;
                return;
            }

            aacDecoder = codec;
            pcmSampleRate = c.sampleRate;
            pcmChannels = c.channels;
            aacRunning = true;
            if (aacExecutor == null) {
                aacExecutor = Executors.newSingleThreadExecutor(
                        r -> new Thread(r, "rtsp-aac-decode"));
            }
            aacExecutor.execute(() -> aacLoop(codec));
        }
    }

    private void aacLoop(MediaCodec codec) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (aacRunning && !Thread.currentThread().isInterrupted()) {
            byte[] au;
            try {
                au = aacQueue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (au == null) {
                if (aacDecoder != codec) break;
                continue;
            }

            synchronized (aacDecoderLock) {
                if (aacDecoder != codec) break;
                try {
                    int inputId = codec.dequeueInputBuffer(10_000);
                    if (inputId >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inputId);
                        if (inBuf != null) {
                            inBuf.clear();
                            inBuf.put(au);
                            codec.queueInputBuffer(inputId, 0, au.length,
                                    System.nanoTime() / 1000, 0);
                        }
                    }

                    int outId;
                    while ((outId = codec.dequeueOutputBuffer(info, 0))
                            != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (outId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat f = codec.getOutputFormat();
                            pcmSampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            pcmChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            continue;
                        }
                        if (outId < 0) continue;
                        ByteBuffer outBuf = codec.getOutputBuffer(outId);
                        if (outBuf != null && info.size > 0) {
                            byte[] pcm = new byte[info.size];
                            outBuf.position(info.offset);
                            outBuf.get(pcm);
                            offerPcm(pcm);
                        }
                        codec.releaseOutputBuffer(outId, false);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "AAC decode error", e);
                }
            }
        }
    }
}
