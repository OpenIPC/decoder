/*
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * NalAssembler.java — reusable NAL unit reassembler from RTP fragmentation units
 *
 */

package com.openipc.decoder;

import android.util.Log;

/**
 * Reassembles NAL units from RTP fragmentation units (FU-A for H.264, FU for H.265).
 * Shared by the main decoder and QuadCell streams. Replaces duplicated buildFrame()/assembleNal().
 */
class NalAssembler {
    private static final String TAG = "OpenIPCDecoder";

    private static final int RTP_FU_H264 = 28;
    private static final int RTP_FU_H265 = 49;

    private final byte[] nalBuffer;
    private int nalSize;
    private boolean lastCodec;
    private final Runnable onCodecSwitch;
    private final FramePool framePool;

    NalAssembler(int bufferSize, Runnable onCodecSwitch, FramePool framePool) {
        this.nalBuffer = new byte[bufferSize];
        this.onCodecSwitch = onCodecSwitch;
        this.framePool = framePool;
    }

    void reset() { nalSize = 0; }

    /** Reassemble one RTP packet into (possibly null) NAL frame. */
    Frame assemble(Frame frame, boolean codecH265) {
        byte[] rx = frame.data();
        int rxSize = frame.length();
        int cp = 12;
        rxSize -= cp;
        if (rxSize <= 0) return null;

        if (lastCodec != codecH265) {
            lastCodec = codecH265;
            nalSize = 0;
            onCodecSwitch.run();
        }

        int frag = fragment(rx[cp], codecH265);
        if (frag == RTP_FU_H264 || frag == RTP_FU_H265) {
            int minPayload = codecH265 ? 3 : 2;
            if (rxSize < minPayload) return null;

            int staBit, endBit;
            if (codecH265) {
                staBit = rx[cp + 2] & 0x80;
                endBit = rx[cp + 2] & 0x40;
            } else {
                staBit = rx[cp + 1] & 0x80;
                endBit = rx[cp + 1] & 0x40;
            }

            if (staBit != 0) {
                nalSize = 0;
                if (codecH265) {
                    nalBuffer[nalSize++] = 0;
                    nalBuffer[nalSize++] = 0;
                    nalBuffer[nalSize++] = 0;
                    nalBuffer[nalSize++] = 1;
                    nalBuffer[nalSize++] = (byte)((rx[cp] & 0x81) | ((rx[cp + 2] & 0x7F) << 1));
                    int donlField = (rx[cp + 1] & 0x80) >> 7;
                    cp += donlField != 0 ? 3 : 2;
                } else {
                    nalBuffer[nalSize++] = 0;
                    nalBuffer[nalSize++] = 0;
                    nalBuffer[nalSize++] = 0;
                    nalBuffer[nalSize++] = 1;
                    nalBuffer[nalSize++] = (byte)((rx[cp] & 0xE0) | (rx[cp + 1] & 0x1F));
                    cp += 2;
                }
                rxSize = frame.length() - cp;
            } else {
                cp += codecH265 ? 3 : 2;
                rxSize = frame.length() - cp;
            }

            int copyLen = Math.min(rxSize, nalBuffer.length - nalSize);
            if (copyLen > 0) {
                System.arraycopy(rx, cp, nalBuffer, nalSize, copyLen);
                nalSize += copyLen;
            }

            if (endBit != 0) {
                Frame out = framePool.obtain(nalSize);
                System.arraycopy(nalBuffer, 0, out.data(), 0, nalSize);
                out.setLength(nalSize);
                nalSize = 0;
                return out;
            }
            return null;
        } else {
            nalSize = 0;
            int copyLen = rxSize + 4;
            if (copyLen > nalBuffer.length || copyLen < 5) return null;
            nalBuffer[0] = 0;
            nalBuffer[1] = 0;
            nalBuffer[2] = 0;
            nalBuffer[3] = 1;
            System.arraycopy(rx, cp, nalBuffer, 4, rxSize);
            Frame out = framePool.obtain(copyLen);
            System.arraycopy(nalBuffer, 0, out.data(), 0, copyLen);
            out.setLength(copyLen);
            return out;
        }
    }

    /** Extract NAL unit type from a byte at the FU indicator / NAL header position. */
    static int fragment(byte data, boolean codecH265) {
        return codecH265 ? (data >> 1) & 0x3F : data & 0x1F;
    }
}
