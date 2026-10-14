/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * StreamProbe.java — a short look at a camera's stream without decoding it (#100)
 *
 */

package com.openipc.decoder;

import android.os.SystemClock;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One check of a slot that is not on screen, for the debug page in single view: an ordinary
 * RTSP session (or an HTTP MJPEG request) without audio, a few seconds of packets counted,
 * then closed. Nothing is decoded and no decoder is taken from the stream on screen.
 */
final class StreamProbe {
    /** Connect, handshake and PLAY together; a camera slower than this counts as not answering. */
    static final long CONNECT_WINDOW_MS = 8000;
    /** Packets are counted this long after PLAY. */
    static final long RECEIVE_MS = 3000;

    /** What one probe found; immutable once made. */
    static final class Result {
        /** The slot's URL when it was checked: a result for another URL is not shown. */
        final String url;
        /** When the probe ended (elapsedRealtime). */
        final long atMs;
        final boolean ok;
        /** Codec, size, frame rate and bitrate when ok; otherwise why not. */
        final String summary;
        final String codec;
        final int width;
        final int height;
        final float fps;
        final int keyFrames;
        final long kbps;

        Result(String url, long atMs, boolean ok, String summary, Counter c) {
            this.url = url;
            this.atMs = atMs;
            this.ok = ok;
            this.summary = summary;
            this.codec = c != null ? c.codec() : null;
            this.width = c != null ? c.width : 0;
            this.height = c != null ? c.height : 0;
            this.fps = c != null ? c.fps() : 0;
            this.keyFrames = c != null ? c.keyFrames : 0;
            this.kbps = c != null ? c.kbps() : 0;
        }

        /** One line for a cell: the result and how long ago it was found. */
        String text(long nowMs) {
            return (ok ? "Stream OK \u00b7 " : "") + summary + " \u00b7 checked "
                    + age(nowMs - atMs) + " ago";
        }
    }

    /** "12 s", "3 min": the age of a result. */
    static String age(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s < 120 ? s + " s" : s / 60 + " min";
    }

    /**
     * Counts what arrives on the video channel: packets, frames (marker bits), key frames and
     * bytes. The size is known only for MJPEG: H.264/H.265 would need the SPS parsed or a
     * decoder, and the probe has none.
     */
    static final class Counter {
        int videoPt = -1;
        boolean h265;
        boolean jpeg;
        int packets;
        int frames;
        int keyFrames;
        long bytes;
        int width;
        int height;
        long firstMs = -1;
        long lastFrameMs = -1;
        long firstFrameMs = -1;
        private boolean keyInFrame;

        void onSdp(Sdp sdp) {
            videoPt = sdp.videoPt;
            h265 = sdp.videoH265;
            jpeg = sdp.videoJpeg;
        }

        /** One RTP packet of the video channel, at {@code nowMs}. */
        void onPacket(byte[] buf, int offset, int length, long nowMs) {
            int header = RtspSession.rtpHeaderLength(buf, offset, length);
            if (header < 0 || (buf[offset + 1] & 0x7F) != videoPt) return;
            int end = RtspSession.rtpPayloadEnd(buf, offset, length);
            if (end < header) return;
            packets++;
            bytes += length;
            if (firstMs < 0) firstMs = nowMs;
            int start = offset + header;
            end += offset;
            if (jpeg) {
                // RTP/JPEG header (RFC 2435): width and height in 8-pixel units at bytes 6 and 7
                if (end - start >= 8 && width == 0) {
                    width = (buf[start + 6] & 0xFF) * 8;
                    height = (buf[start + 7] & 0xFF) * 8;
                }
            } else if (keyNal(buf, start, end, h265)) {
                keyInFrame = true;
            }
            // The marker bit ends an access unit: one frame
            if ((buf[offset + 1] & 0x80) != 0) frameEnd(nowMs);
        }

        /** One whole JPEG of an HTTP MJPEG slot. */
        void onJpegFrame(byte[] buf, int offset, int length, long nowMs) {
            jpeg = true;
            packets++;
            bytes += length;
            if (firstMs < 0) firstMs = nowMs;
            if (width == 0) {
                byte[] head = offset == 0 ? buf
                        : java.util.Arrays.copyOfRange(buf, offset, offset + Math.min(length, 4096));
                int[] size = JpegAssembler.size(head, Math.min(length, head.length));
                if (size != null) {
                    width = size[0];
                    height = size[1];
                }
            }
            frameEnd(nowMs);
        }

        private void frameEnd(long nowMs) {
            frames++;
            if (keyInFrame) keyFrames++;
            keyInFrame = false;
            if (firstFrameMs < 0) firstFrameMs = nowMs;
            lastFrameMs = nowMs;
        }

        /** A key frame NAL unit in this payload: alone, first piece of a fragment, or aggregated. */
        static boolean keyNal(byte[] buf, int start, int end, boolean h265) {
            int nalHeader = h265 ? 2 : 1;
            if (end - start < nalHeader) return false;
            int type = NalAssembler.fragment(buf[start], h265);
            if (h265 ? type == 49 : type == 28) {
                // FU: the start bit and the type of the unit it carries
                if (end - start <= nalHeader) return false;
                int fu = buf[start + nalHeader] & 0xFF;
                int inner = h265 ? fu & 0x3F : fu & 0x1F;
                return (fu & 0x80) != 0 && QuadCell.isKeyFrameNal(inner, h265);
            }
            if (h265 ? type == 48 : type == 24) {
                // Aggregation: 16-bit size, then the unit, for each unit
                int pos = start + nalHeader;
                while (end - pos >= 2 + nalHeader) {
                    int size = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
                    pos += 2;
                    if (size < nalHeader || size > end - pos) return false;
                    if (QuadCell.isKeyFrameNal(NalAssembler.fragment(buf[pos], h265), h265)) {
                        return true;
                    }
                    pos += size;
                }
                return false;
            }
            return QuadCell.isKeyFrameNal(type, h265);
        }

        String codec() {
            return videoPt < 0 && !jpeg ? null : jpeg ? "MJPEG" : h265 ? "H.265" : "H.264";
        }

        /** Frames per second between the first and the last frame; 0 below two frames. */
        float fps() {
            if (frames < 2 || lastFrameMs <= firstFrameMs) return 0;
            return (frames - 1) * 1000f / (lastFrameMs - firstFrameMs);
        }

        /** Video bitrate in kbit/s from the first packet to the last frame. */
        long kbps() {
            if (firstMs < 0 || lastFrameMs <= firstMs) return 0;
            return bytes * 8 / (lastFrameMs - firstMs);
        }

        /** "1280×720 MJPEG · 5 fps · 2.1 Mbit/s"; size only when known. */
        String summary() {
            StringBuilder sb = new StringBuilder();
            if (width > 0) sb.append(width).append('\u00d7').append(height).append(' ');
            sb.append(codec() != null ? codec() : "video");
            float f = fps();
            if (f > 0) {
                sb.append(" \u00b7 ").append(f < 10 ? String.format(Locale.ROOT, "%.1f", f)
                        : String.valueOf(Math.round(f))).append(" fps");
            }
            long k = kbps();
            if (k > 0) {
                sb.append(" \u00b7 ").append(k < 1000 ? k + " kbit/s"
                        : String.format(Locale.ROOT, "%.1f Mbit/s", k / 1000f));
            }
            return sb.toString();
        }
    }

    /**
     * Result of a probe from what it saw: no PLAY (the last error, or no answer), PLAY but no
     * video packets, or the stream.
     */
    static Result result(String url, long atMs, boolean played, String failure, Counter c) {
        if (!played) {
            return new Result(url, atMs, false, failure != null ? QuadCell.shortError(failure)
                    : "No answer from camera", null);
        }
        if (c.frames == 0) {
            return new Result(url, atMs, false, c.packets > 0
                    ? "Packets but no whole frame" : "PLAY OK, but no video", c);
        }
        return new Result(url, atMs, true, c.summary(), c);
    }

    /** Asked between steps: the probe stops early, e.g. the TV switched to that camera. */
    interface Cancel {
        boolean cancelled();
    }

    private StreamProbe() {}

    /**
     * Runs one probe on the calling thread, at most CONNECT_WINDOW_MS + RECEIVE_MS. Returns
     * null when cancelled: a cut-short look says nothing.
     */
    static Result run(String tag, String url, Transport.Mode mode, String userAgent, Cancel cancel) {
        Counter counter = new Counter();
        CountDownLatch played = new CountDownLatch(1);
        String[] failure = new String[1];
        RtspSession session = new RtspSession(tag, new RtspSession.Callback() {
            @Override
            public void onStatus(String status) {
                if ("buffering".equals(status) || "connected".equals(status)) played.countDown();
            }

            @Override
            public void onFailure(String message) {
                failure[0] = message;
            }

            @Override
            public void onSdp(Sdp sdp) {
                synchronized (counter) {
                    counter.onSdp(sdp);
                }
            }

            @Override
            public boolean onPacket(int channel, byte[] buf, int offset, int length) {
                if (channel != RtspSession.CHANNEL_VIDEO) return false;
                synchronized (counter) {
                    counter.onPacket(buf, offset, length, SystemClock.elapsedRealtime());
                }
                return true;
            }

            @Override
            public boolean onJpegFrame(byte[] buf, int offset, int length) {
                synchronized (counter) {
                    counter.onJpegFrame(buf, offset, length, SystemClock.elapsedRealtime());
                }
                return true;
            }
        });
        // Auto would wait for UDP first: a probe takes TCP, the safe choice for any camera
        Transport.Mode m = mode == null || mode == Transport.Mode.AUTO ? Transport.Mode.TCP : mode;
        session.configure(url, userAgent, false, m);
        session.start();
        boolean ok = false;
        try {
            ok = waitFor(played, CONNECT_WINDOW_MS, cancel);
            if (ok && !cancel.cancelled()) sleep(RECEIVE_MS, cancel);
        } finally {
            session.stop();
        }
        if (cancel.cancelled()) return null;
        synchronized (counter) {
            return result(url, SystemClock.elapsedRealtime(), ok, failure[0], counter);
        }
    }

    private static boolean waitFor(CountDownLatch latch, long ms, Cancel cancel) {
        long end = SystemClock.elapsedRealtime() + ms;
        try {
            while (!cancel.cancelled()) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) return false;
                if (latch.await(Math.min(left, 200), TimeUnit.MILLISECONDS)) return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private static void sleep(long ms, Cancel cancel) {
        long end = SystemClock.elapsedRealtime() + ms;
        try {
            while (!cancel.cancelled()) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) return;
                Thread.sleep(Math.min(left, 200));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
