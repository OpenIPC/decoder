/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
    /** While false no AudioTrack or AAC decoder is created (paused activity). */
    private volatile boolean enabled = true;
    private final Object trackLock = new Object();

    /** Disabling also closes audio; a worker still running cannot start playback again. */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) closeAudio();
    }
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

    // Audio routes: every source (single view, the audible Quad cell) gets a route number.
    // Frames, configs and track creation of an older route are ignored, so a stopped source
    // cannot leak into the next one.
    private final Object routeLock = new Object();
    private int route;

    /** Start a new route: closes current audio and invalidates all older producers. */
    int newRoute() {
        int r;
        synchronized (routeLock) {
            r = ++route;
            pcmQueue.clear();
            aacQueue.clear();
        }
        closeAudio();
        return r;
    }

    /** Number of decoded frames waiting for playback (for tests and diagnostics). */
    int queuedPcmFrames() { return pcmQueue.size(); }

    private boolean isRoute(int r) {
        synchronized (routeLock) {
            return r == route;
        }
    }

    /** Audio parameters of route {@code r}; ignored if that route is no longer current. */
    void configure(AudioConfig audio, int r) {
        synchronized (routeLock) {
            if (r != route) return;
            configure(audio);
        }
    }

    /** Called on every RTSP (re)connect, before PLAY. */
    private void configure(AudioConfig audio) {
        config = audio;
        // Parameters may differ after a reconnect: the AAC decoder is recreated on the next packet
        releaseAacDecoder();
        aacQueue.clear();
        auAssembler.reset();
        if (!AudioConfig.AAC.equals(audio.codec)) {
            pcmSampleRate = audio.sampleRate;
            pcmChannels = audio.channels;
        }
    }

    /** Called from a network thread to deliver an audio RTP packet of route {@code r}. */
    void onAudioFrame(Frame frame, int r) {
        if (!isRoute(r)) return;
        byte[] data = frame.data();
        int start = RtspSession.rtpHeaderLength(data, frame.length());
        if (start < 0) return;
        int end = RtspSession.rtpPayloadEnd(data, frame.length());
        if (end <= start) return;

        AudioConfig c = config;
        switch (c.codec) {
            case AudioConfig.AAC:
                processAac(c, data, start, end, (data[1] & 0x80) != 0, r);
                break;
            case AudioConfig.LATM:
                ensureAacDecoder(c, false);
                for (int[] u : splitLatm(data, start, end)) {
                    offerAac(Arrays.copyOfRange(data, u[0], u[0] + u[1]), r);
                }
                break;
            case AudioConfig.OPUS:
                // RFC 7587: one Opus packet per RTP packet
                ensureAacDecoder(c, false);
                offerAac(Arrays.copyOfRange(data, start, end), r);
                break;
            case AudioConfig.PCMU:
                offerPcm(decodeG711(data, start, end, ULAW_TABLE), r);
                break;
            case AudioConfig.PCMA:
                offerPcm(decodeG711(data, start, end, ALAW_TABLE), r);
                break;
            case AudioConfig.PCM:
                offerPcm(decodeL16(data, start, end, c.bigEndian), r);
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
        AudioTrack track;
        synchronized (trackLock) {
            track = audioTrackRef.getAndSet(null);
        }
        if (track != null) {
            Log.i(TAG, "Close audio decoder");
            try {
                track.stop();
                track.release();
            } catch (Exception e) {
                Log.e(TAG, "Audio close exception", e);
            }
        }
        releaseAacDecoder();
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

    private void offerPcm(byte[] pcm, int r) {
        if (pcm.length == 0) return;
        synchronized (routeLock) {
            // Checked under the lock that newRoute() clears the queue with: no leftovers
            if (r != route) return;
            if (!pcmQueue.offer(new Frame(pcm, pcm.length))) {
                Log.w(TAG, "Audio queue full, frame dropped");
            }
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
        if (!enabled) return null;
        final int trackRoute;
        synchronized (routeLock) {
            trackRoute = route;
        }
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

        AudioTrack old;
        synchronized (trackLock) {
            // Paused or switched to another source while the track was being created
            if (!enabled || !isRoute(trackRoute)) {
                try { track.stop(); } catch (Exception ignored) {}
                track.release();
                return null;
            }
            old = audioTrackRef.getAndSet(track);
        }
        if (old != null) {
            try { old.stop(); } catch (Exception ignored) {}
            old.release();
        }
        trackSampleRate = sample;
        trackChannels = channels;
        return track;
    }

    // ====================== AAC ======================

    private void releaseAacDecoder() {
        synchronized (aacDecoderLock) {
            MediaCodec aac = aacDecoder;
            if (aac != null) {
                aacDecoder = null;
                try { aac.stop(); } catch (Exception ignored) {}
                try { aac.release(); } catch (Exception ignored) {}
            }
        }
    }

    private final AuAssembler auAssembler = new AuAssembler();

    private void processAac(AudioConfig c, byte[] data, int start, int end, boolean marker,
                            int r) {
        List<int[]> units;
        if (c.sizeLength > 0) {
            ensureAacDecoder(c, false);
            for (byte[] au : auAssembler.push(data, start, end, c, marker)) offerAac(au, r);
            return;
        } else if (end - start >= 7 && isAdtsSync(data, start)) {
            ensureAacDecoder(c, true);
            units = splitAdts(data, start, end);
        } else {
            // Raw access unit without AU headers
            ensureAacDecoder(c, false);
            units = Collections.singletonList(new int[] {start, end - start});
        }
        for (int[] u : units) {
            offerAac(Arrays.copyOfRange(data, u[0], u[0] + u[1]), r);
        }
    }

    /**
     * RFC 3640 mpeg4-generic payload: AU-headers-length (bits), AU headers, then AU data.
     * Returns {offset, length} of each complete AU; AUs fragmented across packets are
     * not supported and end the list.
     */
    static List<int[]> splitAuHeaders(byte[] data, int start, int end,
                                      int sizeLength, int indexLength, int indexDeltaLength) {
        List<int[]> units = new ArrayList<>();
        if (end - start < 2 || sizeLength <= 0) return units;
        int headerBits = ((data[start] & 0xFF) << 8) | (data[start + 1] & 0xFF);
        int bitPos = (start + 2) * 8;
        int bitEnd = bitPos + headerBits;
        int pos = start + 2 + (headerBits + 7) / 8;
        if (pos > end) return units;
        boolean first = true;
        while (true) {
            int headerLen = sizeLength + (first ? indexLength : indexDeltaLength);
            if (bitPos + headerLen > bitEnd) break;
            int size = readBits(data, bitPos, sizeLength);
            bitPos += headerLen;
            first = false;
            if (size <= 0 || pos + size > end) break;
            units.add(new int[] {pos, size});
            pos += size;
        }
        return units;
    }

    /**
     * RFC 3640 with AUs that may span several RTP packets: complete AUs are returned as
     * soon as they are whole. A fragment whose pieces do not add up is dropped.
     */
    static final class AuAssembler {
        private byte[] fragment;
        private int have;

        void reset() { fragment = null; }

        List<byte[]> push(byte[] data, int start, int end, AudioConfig c, boolean marker) {
            List<byte[]> out = new ArrayList<>();
            if (end - start < 2) return out;
            int headerBits = ((data[start] & 0xFF) << 8) | (data[start + 1] & 0xFF);
            int pos = start + 2 + (headerBits + 7) / 8;
            if (pos > end || headerBits < c.sizeLength + c.indexLength) return out;
            int size = readBits(data, (start + 2) * 8, c.sizeLength);

            if (fragment != null) {
                // Continuation: one AU header with the full AU size, then the next piece
                int len = end - pos;
                if (size != fragment.length || have + len > fragment.length) {
                    fragment = null; // does not belong to the AU being collected
                } else {
                    System.arraycopy(data, pos, fragment, have, len);
                    have += len;
                    if (have == fragment.length) {
                        out.add(fragment);
                        fragment = null;
                    } else if (marker) {
                        fragment = null; // last packet of the AU, but pieces are missing
                    }
                    return out;
                }
            }

            if (size > end - pos && !marker) {
                // First piece of an AU larger than this packet
                fragment = new byte[size];
                have = end - pos;
                System.arraycopy(data, pos, fragment, 0, have);
                return out;
            }
            for (int[] u : splitAuHeaders(data, start, end,
                    c.sizeLength, c.indexLength, c.indexDeltaLength)) {
                out.add(Arrays.copyOfRange(data, u[0], u[0] + u[1]));
            }
            return out;
        }
    }

    /**
     * RFC 6416 MP4A-LATM with out-of-band config: each AudioMuxElement is a
     * PayloadLengthInfo (bytes of 255 plus a final byte, summed) and the AU.
     */
    static List<int[]> splitLatm(byte[] data, int start, int end) {
        List<int[]> units = new ArrayList<>();
        int pos = start;
        while (pos < end) {
            int len = 0;
            int b;
            do {
                if (pos >= end) return units;
                b = data[pos++] & 0xFF;
                len += b;
            } while (b == 0xFF);
            if (len == 0 || pos + len > end) break;
            units.add(new int[] {pos, len});
            pos += len;
        }
        return units;
    }

    /** ADTS frames: {offset, length} of each complete frame, stopping at bad sync or length. */
    static List<int[]> splitAdts(byte[] data, int start, int end) {
        List<int[]> frames = new ArrayList<>();
        int pos = start;
        while (end - pos >= 7 && isAdtsSync(data, pos)) {
            int frameLen = ((data[pos + 3] & 0x03) << 11)
                    | ((data[pos + 4] & 0xFF) << 3)
                    | ((data[pos + 5] & 0xE0) >>> 5);
            if (frameLen < 7 || pos + frameLen > end) break;
            frames.add(new int[] {pos, frameLen});
            pos += frameLen;
        }
        return frames;
    }

    static boolean isAdtsSync(byte[] data, int pos) {
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

    private void offerAac(byte[] au, int r) {
        synchronized (routeLock) {
            if (r != route) return;
            if (!aacQueue.offer(au)) {
                Log.w(TAG, "AAC audio queue full, frame dropped");
            }
        }
    }

    /** Opus identification header (RFC 7845) used as csd-0. */
    static byte[] opusHead(int channels) {
        ByteBuffer b = ByteBuffer.allocate(19).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.put((byte) 1);               // version
        b.put((byte) channels);
        b.putShort((short) 0);         // pre-skip
        b.putInt(48000);               // input sample rate
        b.putShort((short) 0);         // output gain
        b.put((byte) 0);               // mapping family 0: mono or stereo
        return b.array();
    }

    private static ByteBuffer nanos(long ns) {
        ByteBuffer b = ByteBuffer.allocate(8).order(java.nio.ByteOrder.nativeOrder());
        b.putLong(ns);
        b.flip();
        return b;
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
        if (aacDecoder != null || audioFailed || !enabled) return;
        synchronized (aacDecoderLock) {
            if (aacDecoder != null || audioFailed || !enabled) return;

            boolean opus = AudioConfig.OPUS.equals(c.codec);
            String mime = opus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
            Log.d(TAG, "Creating " + c.codec + " decoder (" + c.sampleRate + "hz, " + c.channels
                    + "ch" + (opus ? "" : adts ? ", ADTS" : ", raw") + ")");
            MediaFormat fmt = MediaFormat.createAudioFormat(mime, c.sampleRate, c.channels);
            if (opus) {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead(c.channels)));
                // Codec delay and seek pre-roll in ns; RTP does not carry the pre-skip
                fmt.setByteBuffer("csd-1", nanos(0));
                fmt.setByteBuffer("csd-2", nanos(80_000_000L));
            } else if (adts) {
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
                codec = MediaCodec.createDecoderByType(mime);
                try {
                    codec.configure(fmt, null, null, 0);
                    codec.start();
                } catch (Exception e) {
                    codec.release();
                    throw e;
                }
            } catch (Exception e) {
                Log.e(TAG, "Cannot create " + c.codec + " decoder, audio disabled", e);
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
            final int codecRoute = route;
            aacExecutor.execute(() -> aacLoop(codec, codecRoute));
        }
    }

    private void aacLoop(MediaCodec codec, int codecRoute) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pending = null;
        while (aacRunning && !Thread.currentThread().isInterrupted()) {
            if (pending == null) {
                try {
                    pending = aacQueue.poll(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            synchronized (aacDecoderLock) {
                if (aacDecoder != codec) break;
                try {
                    if (pending != null) {
                        int inputId = codec.dequeueInputBuffer(10_000);
                        // No free input buffer: keep the access unit and retry after draining
                        if (inputId >= 0) {
                            ByteBuffer inBuf = codec.getInputBuffer(inputId);
                            int len = 0;
                            if (inBuf != null) {
                                inBuf.clear();
                                inBuf.put(pending);
                                len = pending.length;
                            }
                            codec.queueInputBuffer(inputId, 0, len, System.nanoTime() / 1000, 0);
                            pending = null;
                        }
                    }
                    // Drain on idle iterations too, so the last decoded frame is not held back
                    drainAacOutput(codec, info, codecRoute);
                } catch (Exception e) {
                    Log.e(TAG, "AAC decode error", e);
                    pending = null;
                }
            }
        }
    }

    private void drainAacOutput(MediaCodec codec, MediaCodec.BufferInfo info, int codecRoute) {
        int outId;
        while ((outId = codec.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
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
                offerPcm(pcm, codecRoute);
            }
            codec.releaseOutputBuffer(outId, false);
        }
    }
}
