/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * JpegAssembler.java — RTP/JPEG (RFC 2435) to complete JPEG frames, for MJPEG over RTSP (#119)
 *
 */

package com.openipc.decoder;

import android.util.Log;

/**
 * RTP/JPEG carries only the entropy-coded scan of a baseline JPEG plus a small header (type,
 * Q, size; restart interval; quantization tables when Q &gt;= 128). This class collects the
 * fragments of one frame (the RTP marker bit ends it, fragment offsets must follow each
 * other) and rebuilds the JPEG headers: SOI, DQT (from the stream, or from Q with the
 * standard tables), optional DRI, SOF0, the standard Huffman tables of JPEG Annex K, SOS,
 * then the scan and EOI. A frame with a missing fragment is dropped.
 */
final class JpegAssembler {
    private static final String TAG = "OpenIPCDecoder";

    interface Sink {
        void onFrame(Frame jpeg);
    }

    /** Standard luminance and chrominance tables, zig-zag order (RFC 2435 Appendix A). */
    private static final int[] LUMA_Q = {
            16, 11, 12, 14, 12, 10, 16, 14, 13, 14, 18, 17, 16, 19, 24, 40,
            26, 24, 22, 22, 24, 49, 35, 37, 29, 40, 58, 51, 61, 60, 57, 51,
            56, 55, 64, 72, 92, 78, 64, 68, 87, 69, 55, 56, 80, 109, 81, 87,
            95, 98, 103, 104, 103, 62, 77, 113, 121, 112, 100, 120, 92, 101, 103, 99
    };
    private static final int[] CHROMA_Q = new int[64];

    static {
        int[] head = {17, 18, 18, 24, 21, 24, 47, 26, 26, 47, 99, 66, 56, 66};
        for (int i = 0; i < 64; i++) CHROMA_Q[i] = i < head.length ? head[i] : 99;
    }

    // Standard Huffman tables (JPEG Annex K.3): code counts per length, then the symbols
    private static final int[] LUM_DC_COUNTS = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] LUM_AC_COUNTS = {0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
    private static final int[] CHM_DC_COUNTS = {0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
    private static final int[] CHM_AC_COUNTS = {0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
    private static final int[] DC_SYMBOLS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int[] LUM_AC_SYMBOLS = {
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06,
            0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08,
            0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72,
            0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45,
            0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
            0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75,
            0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3,
            0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6,
            0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9,
            0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4,
            0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa
    };
    private static final int[] CHM_AC_SYMBOLS = {
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41,
            0x51, 0x07, 0x61, 0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
            0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1,
            0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44,
            0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
            0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74,
            0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a,
            0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4,
            0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7,
            0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4,
            0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa
    };

    private final FramePool pool;
    private byte[] scan = new byte[256 * 1024];
    private int scanLength;
    /** Offset the next fragment must have; -1 while no frame is being collected. */
    private int expectedOffset = -1;
    private byte[] header;
    /** Written by the receive thread, read by the UI thread (Qodo on #147). */
    private volatile int dropped;
    /** In-band tables by Q (128..254): a sender may send them once and then only the Q. */
    private final java.util.Map<Integer, byte[]> tablesByQ = new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> precisionByQ = new java.util.HashMap<>();

    JpegAssembler(FramePool pool) {
        this.pool = pool;
    }

    /** A new stream: its frames must not use the tables of the previous one (Qodo on #139). */
    void reset() {
        expectedOffset = -1;
        scanLength = 0;
        header = null;
        tablesByQ.clear();
        precisionByQ.clear();
    }

    /** Frames dropped because a fragment was missing or malformed. */
    int droppedCount() { return dropped; }

    /**
     * One RTP packet ({@code offset} at the RTP header). Returns false for a malformed
     * packet; true when it was usable (whether or not it completed a frame).
     */
    boolean assemble(byte[] buf, int offset, int length, Sink sink) {
        if (length < 12) return false;
        int cc = buf[offset] & 0x0F;
        boolean extension = (buf[offset] & 0x10) != 0;
        boolean marker = (buf[offset + 1] & 0x80) != 0;
        int pos = offset + 12 + cc * 4;
        int end = offset + length;
        if ((buf[offset] & 0x20) != 0) end -= buf[end - 1] & 0xFF; // padding
        if (extension) {
            if (pos + 4 > end) return false;
            pos += 4 + 4 * (((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF));
        }
        if (pos + 8 > end) return false;
        int fragment = ((buf[pos + 1] & 0xFF) << 16) | ((buf[pos + 2] & 0xFF) << 8)
                | (buf[pos + 3] & 0xFF);
        int type = buf[pos + 4] & 0xFF;
        int q = buf[pos + 5] & 0xFF;
        // 0 stands for 256 units: 2048 pixels (RFC 2435 3.1.5/3.1.6)
        int width = ((buf[pos + 6] & 0xFF) == 0 ? 256 : buf[pos + 6] & 0xFF) * 8;
        int height = ((buf[pos + 7] & 0xFF) == 0 ? 256 : buf[pos + 7] & 0xFF) * 8;
        pos += 8;
        int restartInterval = 0;
        if (type >= 64 && type <= 127) {
            if (pos + 4 > end) return false;
            restartInterval = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            pos += 4;
        }
        if ((type & 63) > 1) {
            // Only types 0 and 1 (YUV 4:2:2 / 4:2:0) are defined by RFC 2435
            return false;
        }
        if (fragment == 0) {
            byte[] tables;
            int precision = 0;
            if (q >= 128) {
                if (pos + 4 > end) return false;
                precision = buf[pos + 1] & 0xFF;
                int len = ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
                pos += 4;
                if (pos + len > end) return false;
                if (len == 0 && q < 255 && tablesByQ.containsKey(q)) {
                    // Tables sent once before for this Q (RFC 2435 3.1.8)
                    tables = tablesByQ.get(q);
                    precision = precisionByQ.get(q);
                } else if (len == 0 || !tablesFit(len, precision)) {
                    if (expectedOffset > 0) drop("frame without its end");
                    expectedOffset = -1;
                    return true;
                } else {
                    tables = new byte[len];
                    System.arraycopy(buf, pos, tables, 0, len);
                    pos += len;
                    if (q < 255) {
                        tablesByQ.put(q, tables);
                        precisionByQ.put(q, precision);
                    }
                }
            } else {
                tables = tables(q);
            }
            if (expectedOffset > 0) drop("frame without its end");
            header = headers(type, width, height, tables, precision, restartInterval);
            scanLength = 0;
            expectedOffset = 0;
        } else if (expectedOffset < 0) {
            // Joined mid-frame or the start was lost: wait for the next frame
            return true;
        }
        if (fragment != expectedOffset) {
            drop("packet loss inside a frame");
            return true;
        }
        int len = end - pos;
        if (len < 0) return false;
        if (scanLength + len > scan.length) {
            if (scanLength + len > 4 * 1024 * 1024) {
                drop("frame larger than 4 MiB");
                return true;
            }
            byte[] bigger = new byte[Math.max(scan.length * 2, scanLength + len)];
            System.arraycopy(scan, 0, bigger, 0, scanLength);
            scan = bigger;
        }
        System.arraycopy(buf, pos, scan, scanLength, len);
        scanLength += len;
        expectedOffset += len;
        if (marker) {
            boolean hasEoi = scanLength >= 2 && (scan[scanLength - 2] & 0xFF) == 0xFF
                    && (scan[scanLength - 1] & 0xFF) == 0xD9;
            int total = header.length + scanLength + (hasEoi ? 0 : 2);
            Frame out = pool.obtain(total);
            byte[] d = out.data();
            System.arraycopy(header, 0, d, 0, header.length);
            System.arraycopy(scan, 0, d, header.length, scanLength);
            if (!hasEoi) {
                d[total - 2] = (byte) 0xFF;
                d[total - 1] = (byte) 0xD9;
            }
            out.setLength(total);
            expectedOffset = -1;
            scanLength = 0;
            sink.onFrame(out);
        }
        return true;
    }

    private void drop(String reason) {
        dropped++;
        expectedOffset = -1;
        scanLength = 0;
        if (dropped <= 3 || dropped % 100 == 0) {
            Log.w(TAG, "JPEG frame dropped (" + dropped + "): " + reason);
        }
    }

    /** {width, height} from the SOF0 header of a JPEG, or null (#132). */
    static int[] size(byte[] d, int length) {
        // Walk the marker segments by their lengths: table bytes may contain FF C0 (Qodo on #135)
        int i = 2;
        while (i + 4 <= length) {
            if ((d[i] & 0xFF) != 0xFF) return null;
            int marker = d[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) return null; // scan or end before any SOF
            int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (len < 2) return null;
            if (marker == 0xC0) {
                if (i + 9 > length) return null;
                int h = ((d[i + 5] & 0xFF) << 8) | (d[i + 6] & 0xFF);
                int w = ((d[i + 7] & 0xFF) << 8) | (d[i + 8] & 0xFF);
                return new int[] {w, h};
            }
            i += 2 + len;
        }
        return null;
    }

    /** Quantization tables for Q 1..99 (RFC 2435 Appendix A), luminance then chrominance. */
    static byte[] tables(int q) {
        return ijgTables(Math.max(1, Math.min(99, q)));
    }

    /**
     * The standard tables scaled to an IJG quality 1..100 as libjpeg does it (100: all ones),
     * zig-zag order, luminance then chrominance (#187).
     */
    static byte[] ijgTables(int q) {
        q = Math.max(1, Math.min(100, q));
        int factor = q < 50 ? 5000 / q : 200 - q * 2;
        byte[] out = new byte[128];
        for (int i = 0; i < 64; i++) {
            out[i] = (byte) Math.max(1, Math.min(255, (LUMA_Q[i] * factor + 50) / 100));
            out[64 + i] = (byte) Math.max(1, Math.min(255, (CHROMA_Q[i] * factor + 50) / 100));
        }
        return out;
    }

    /**
     * Whether {@code len} bytes are a whole number of tables: table i has 64 entries of
     * 8 bits, or of 16 bits when bit i of {@code precision} is set.
     */
    static boolean tablesFit(int len, int precision) {
        int used = 0;
        for (int i = 0; used < len && i < 4; i++) used += (precision >> i & 1) != 0 ? 128 : 64;
        return used == len;
    }

    /** SOI, DQT, DRI, SOF0, DHT, SOS for a frame (RFC 2435 Appendix B). */
    static byte[] headers(int type, int width, int height, byte[] tables, int restartInterval) {
        return headers(type, width, height, tables, 0, restartInterval);
    }

    static byte[] headers(int type, int width, int height, byte[] tables, int precision,
                          int restartInterval) {
        return headersSampled((type & 63) == 0 ? 0x21 : 0x22, width, height, tables, precision,
                restartInterval);
    }

    /**
     * As headers(), with the luma sampling given ({@code 0x11} for 4:4:4, #187); chroma is
     * always 1x1.
     */
    static byte[] headersSampled(int lumaSampling, int width, int height, byte[] tables,
                                 int precision, int restartInterval) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(700);
        o.write(0xFF);
        o.write(0xD8);
        int count = 0;
        for (int at = 0; at < tables.length && count < 4; count++) {
            boolean wide = (precision >> count & 1) != 0;
            int size = wide ? 128 : 64;
            marker(o, 0xDB, 3 + size);
            o.write((wide ? 0x10 : 0) | count);
            o.write(tables, at, size);
            at += size;
        }
        if (restartInterval > 0) {
            marker(o, 0xDD, 4);
            word(o, restartInterval);
        }
        marker(o, 0xC0, 17);
        o.write(8);
        word(o, height);
        word(o, width);
        o.write(3);
        int chromaTable = count > 1 ? 1 : 0;
        o.write(0);
        o.write(lumaSampling);
        o.write(0);
        o.write(1);
        o.write(0x11);
        o.write(chromaTable);
        o.write(2);
        o.write(0x11);
        o.write(chromaTable);
        huffman(o, 0x00, LUM_DC_COUNTS, DC_SYMBOLS);
        huffman(o, 0x10, LUM_AC_COUNTS, LUM_AC_SYMBOLS);
        huffman(o, 0x01, CHM_DC_COUNTS, DC_SYMBOLS);
        huffman(o, 0x11, CHM_AC_COUNTS, CHM_AC_SYMBOLS);
        marker(o, 0xDA, 12);
        o.write(3);
        o.write(0);
        o.write(0x00);
        o.write(1);
        o.write(0x11);
        o.write(2);
        o.write(0x11);
        o.write(0);
        o.write(63);
        o.write(0);
        return o.toByteArray();
    }

    private static void marker(java.io.ByteArrayOutputStream o, int code, int length) {
        o.write(0xFF);
        o.write(code);
        word(o, length);
    }

    private static void word(java.io.ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void huffman(java.io.ByteArrayOutputStream o, int classAndId, int[] counts,
                                int[] symbols) {
        marker(o, 0xC4, 3 + 16 + symbols.length);
        o.write(classAndId);
        for (int c : counts) o.write(c);
        for (int s : symbols) o.write(s);
    }
}
