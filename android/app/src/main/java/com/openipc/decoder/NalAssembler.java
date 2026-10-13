/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * NalAssembler.java — H.264/H.265 NAL units from RTP packets (RFC 6184 / RFC 7798)
 *
 */

package com.openipc.decoder;

import android.util.Log;

/**
 * Turns RTP video packets into Annex B NAL units (with a 4-byte start code). Handles single
 * NAL unit packets, fragmentation units (FU-A, FU) and aggregation packets (STAP-A, AP),
 * any RTP header length (CSRC, extension, padding) and packet loss: a fragmented NAL unit
 * with a missing piece is dropped instead of being passed on corrupted.
 */
class NalAssembler {
    private static final String TAG = "OpenIPCDecoder";

    private static final int H264_STAP_A = 24;
    private static final int H264_FU_A = 28;
    private static final int H265_AP = 48;
    private static final int H265_FU = 49;
    private static final int H265_PACI = 50;

    interface Sink {
        /** A complete NAL unit; the sink owns the frame (recycle it when done). */
        void onNal(Frame nal);
    }

    private final byte[] nalBuffer;
    private final Runnable onCodecSwitch;
    private final FramePool framePool;

    private int nalSize;
    /** A fragmented NAL unit is being collected in nalBuffer. */
    private boolean inFragment;
    /** Skipping the rest of a fragmented NAL unit whose start was not seen (counted once). */
    private boolean orphan;
    private int lastSeq = -1;
    /** -1 until the first packet, then 0 for H.264 and 1 for H.265. */
    private int lastCodec = -1;
    private int dropped;
    /** Of those, dropped because a packet of them was lost (not for size) (#113). */
    private int lostDropped;
    private int gaps;

    NalAssembler(int bufferSize, Runnable onCodecSwitch, FramePool framePool) {
        this.nalBuffer = new byte[bufferSize];
        this.onCodecSwitch = onCodecSwitch;
        this.framePool = framePool;
    }

    void reset() {
        nalSize = 0;
        inFragment = false;
        orphan = false;
        lastSeq = -1;
    }

    /** NAL units dropped so far because of packet loss or size (for diagnostics). */
    int droppedCount() { return dropped; }
    /** NAL units dropped because one of their packets was lost (#113). */
    int lossCount() { return lostDropped; }
    /** Gaps in the RTP sequence, any packet lost (#145). */
    int gapCount() { return gaps; }

    /**
     * Process one RTP packet ({@code length} bytes at {@code offset}, header included).
     * Returns false if the RTP header is malformed.
     */
    boolean assemble(byte[] buf, int offset, int length, boolean codecH265, Sink sink) {
        int header = RtspSession.rtpHeaderLength(buf, offset, length);
        if (header < 0) return false;
        int end = RtspSession.rtpPayloadEnd(buf, offset, length);
        if (end < header) return false;
        int start = offset + header;
        end += offset;

        int codec = codecH265 ? 1 : 0;
        if (lastCodec != codec) {
            // The first packet only sets the codec; a real switch releases the decoder
            boolean switched = lastCodec >= 0;
            lastCodec = codec;
            reset();
            if (switched) onCodecSwitch.run();
        }

        int seq = ((buf[offset + 2] & 0xFF) << 8) | (buf[offset + 3] & 0xFF);
        boolean gap = lastSeq >= 0 && seq != ((lastSeq + 1) & 0xFFFF);
        if (gap) gaps++;
        lastSeq = seq;
        if (gap && inFragment) {
            lostDropped++;
            drop("packet loss inside a fragmented NAL unit");
            orphan = true; // its remaining pieces belong to the unit just counted
        }

        int nalHeader = codecH265 ? 2 : 1;
        if (end - start < nalHeader) return true;
        int type = fragment(buf[start], codecH265);

        if (codecH265 ? type == H265_FU : type == H264_FU_A) {
            fragmentUnit(buf, start, end, codecH265, sink);
            return true;
        }
        // Any other packet ends a fragmented NAL unit that did not finish; its remaining
        // pieces, if any arrive, belong to the unit counted here
        if (inFragment) {
            drop("fragmented NAL unit interrupted by another packet");
            orphan = true;
        } else {
            orphan = false;
        }
        if (codecH265 ? type == H265_AP : type == H264_STAP_A) {
            aggregation(buf, start + nalHeader, end, sink);
        } else if (codecH265 ? type == H265_PACI : (type >= 25 && type <= 29)) {
            // PACI, STAP-B, MTAP and FU-B are not used by cameras in non-interleaved mode
            return true;
        } else {
            emit(buf, start, end - start, sink);
        }
        return true;
    }

    private void fragmentUnit(byte[] buf, int start, int end, boolean h265, Sink sink) {
        int payloadHeader = h265 ? 2 : 1;
        if (end - start <= payloadHeader) return;
        int fu = buf[start + payloadHeader] & 0xFF;
        boolean first = (fu & 0x80) != 0;
        boolean last = (fu & 0x40) != 0;
        int pos = start + payloadHeader + 1;

        if (first) {
            if (inFragment) {
                lostDropped++;
                drop("fragmented NAL unit without its end");
            }
            orphan = false;
            nalSize = 0;
            inFragment = true;
            nalBuffer[nalSize++] = 0;
            nalBuffer[nalSize++] = 0;
            nalBuffer[nalSize++] = 0;
            nalBuffer[nalSize++] = 1;
            if (h265) {
                // 2-byte NAL header: F + FU type + layer-id MSB, then layer-id/TID as sent
                nalBuffer[nalSize++] = (byte) ((buf[start] & 0x81) | ((fu & 0x3F) << 1));
                nalBuffer[nalSize++] = buf[start + 1];
            } else {
                nalBuffer[nalSize++] = (byte) ((buf[start] & 0xE0) | (fu & 0x1F));
            }
        } else if (!inFragment) {
            // The start was lost (or we joined mid-unit): nothing to attach this piece to.
            // Count the lost unit once, not once per remaining piece.
            if (!orphan) {
                orphan = true;
                dropped++;
                lostDropped++;
                logDrop("fragmented NAL unit without its start");
            }
            if (last) orphan = false;
            return;
        }

        int len = end - pos;
        if (nalSize + len > nalBuffer.length) {
            drop("NAL unit larger than " + nalBuffer.length + " bytes");
            return;
        }
        System.arraycopy(buf, pos, nalBuffer, nalSize, len);
        nalSize += len;

        if (last) {
            Frame out = framePool.obtain(nalSize);
            System.arraycopy(nalBuffer, 0, out.data(), 0, nalSize);
            out.setLength(nalSize);
            nalSize = 0;
            inFragment = false;
            sink.onNal(out);
        }
    }

    /** STAP-A / AP: a sequence of (16-bit size, NAL unit). */
    private void aggregation(byte[] buf, int pos, int end, Sink sink) {
        while (end - pos >= 2) {
            int size = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            pos += 2;
            if (size == 0 || pos + size > end) break;
            emit(buf, pos, size, sink);
            pos += size;
        }
    }

    private void emit(byte[] buf, int pos, int size, Sink sink) {
        Frame out = framePool.obtain(size + 4);
        byte[] d = out.data();
        d[0] = 0;
        d[1] = 0;
        d[2] = 0;
        d[3] = 1;
        System.arraycopy(buf, pos, d, 4, size);
        out.setLength(size + 4);
        sink.onNal(out);
    }

    private void drop(String reason) {
        nalSize = 0;
        inFragment = false;
        dropped++;
        logDrop(reason);
    }

    private void logDrop(String reason) {
        if (dropped <= 3 || dropped % 100 == 0) {
            Log.w(TAG, "NAL unit dropped (" + dropped + "): " + reason);
        }
    }

    /** Extract NAL unit type from a byte at the FU indicator / NAL header position. */
    static int fragment(byte data, boolean codecH265) {
        return codecH265 ? (data >> 1) & 0x3F : data & 0x1F;
    }
}
