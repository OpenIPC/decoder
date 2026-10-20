/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * SpsParserTest.java — picture size from H.264 / H.265 SPS (#166)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SpsParserTest {
    // SPS from ffmpeg 6.1 (libx264 / libx265), testsrc, emulation prevention bytes included
    private static final String H264_2304 = "67640032acd9402400a3b011000003000100000300320f183196";
    private static final String H264_1080 = "674d4028eca03c0113f2e022000003000200000300641e30632c";
    private static final String H264_720 = "6742c01fd9005005bb011000000300100000030320f1832480";
    private static final String H264_444 = "67f40028919b280f0044fc4e0220000003002000000641e30632c0";
    private static final String H264_INTERLACED =
            "67640028acd94078044fde0220000003002000000643e2c5b2c0";
    private static final String H265_2304 = "420101016000000300900000030000030096a0012020051165959a"
            + "4932bc05a02000000300200000030321";
    private static final String H265_1080 = "420101016000000300900000030000030078a003c08010e5965669"
            + "24caf0168080000003008000000c84";

    private static int[] size(String hex, boolean h265) {
        byte[] b = hex(hex);
        return SpsParser.size(b, 0, b.length, h265);
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    @Test
    public void h264Sizes() {
        // High, 2304×1296: 144×81 macroblocks, no cropping
        assertArrayEquals(new int[]{2304, 1296}, size(H264_2304, false));
        // Main, 1080p: 1088 coded, cropped to 1080
        assertArrayEquals(new int[]{1920, 1080}, size(H264_1080, false));
        assertArrayEquals(new int[]{1280, 720}, size(H264_720, false));
        // High 4:4:4: crop units of one line
        assertArrayEquals(new int[]{1920, 1080}, size(H264_444, false));
        // Interlaced: map units are field macroblock pairs
        assertArrayEquals(new int[]{1920, 1080}, size(H264_INTERLACED, false));
    }

    @Test
    public void h265Sizes() {
        assertArrayEquals(new int[]{2304, 1296}, size(H265_2304, true));
        // Coded 1920×1088 with a conformance window
        assertArrayEquals(new int[]{1920, 1080}, size(H265_1080, true));
    }

    @Test
    public void notAnSpsOrBroken() {
        // PPS
        assertNull(size("68ebe3cb22c0", false));
        // H.265 VPS
        assertNull(size("40010c01ffff016000000300900000030000030078959809", true));
        // Truncated
        assertNull(size(H264_2304.substring(0, 16), false));
        assertNull(size(H265_2304.substring(0, 40), true));
        // Codec mixed up
        assertNull(size(H264_1080, true));
        assertNull(SpsParser.size(null, 0, 0, false));
        byte[] b = hex(H264_720);
        assertNull(SpsParser.size(b, 4, b.length, false));
    }

    /** A baseline H.264 SPS with the given size fields (Exp-Golomb values as written). */
    private static int[] h264Sps(long widthMbsMinus1, long heightMinus1, long[] crop) {
        StringBuilder bits = new StringBuilder();
        bits.append(bin(0x67, 8)).append(bin(66, 8)).append(bin(0, 8)).append(bin(31, 8));
        ue(bits, 0); // seq_parameter_set_id
        ue(bits, 0); // log2_max_frame_num_minus4
        ue(bits, 2); // pic_order_cnt_type
        ue(bits, 1); // max_num_ref_frames
        bits.append('0'); // gaps_in_frame_num_value_allowed_flag
        ue(bits, widthMbsMinus1);
        ue(bits, heightMinus1);
        bits.append("11"); // frame_mbs_only_flag, direct_8x8_inference_flag
        if (crop != null) {
            bits.append('1');
            for (long c : crop) ue(bits, c);
        } else {
            bits.append('0');
        }
        bits.append("01"); // no VUI, then the stop bit
        while (bits.length() % 8 != 0) bits.append('0');
        byte[] b = new byte[bits.length() / 8];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(bits.substring(8 * i, 8 * i + 8), 2);
        }
        return SpsParser.size(b, 0, b.length, false);
    }

    private static String bin(long v, int n) {
        StringBuilder s = new StringBuilder(Long.toBinaryString(v));
        while (s.length() < n) s.insert(0, '0');
        return s.toString();
    }

    private static void ue(StringBuilder bits, long v) {
        String code = Long.toBinaryString(v + 1);
        for (int i = 1; i < code.length(); i++) bits.append('0');
        bits.append(code);
    }

    @Test
    public void malformedSizesDoNotWrapRound() {
        // The writer itself: 1280×720
        assertArrayEquals(new int[]{1280, 720}, h264Sps(79, 44, null));
        // 2^28 + 1 macroblocks wide is 2^32 + 16 pixels: 16 in 32-bit arithmetic (Qodo on #220)
        assertNull(h264Sps(1L << 28, 44, null));
        // Crops larger than the picture, and one that overflows an int when doubled
        assertNull(h264Sps(79, 44, new long[]{700, 0, 0, 0}));
        assertNull(h264Sps(79, 44, new long[]{1L << 31, 0, 0, 0}));
        // A sane crop still works: 1920×1088 coded, 1080 shown
        assertArrayEquals(new int[]{1920, 1080}, h264Sps(119, 67, new long[]{0, 0, 0, 4}));
    }

    @Test
    public void emulationPreventionBytesAreRemoved() {
        assertArrayEquals(new byte[]{0, 0, 1, 0, 0, 0},
                SpsParser.unescape(new byte[]{0, 0, 3, 1, 0, 0, 3, 0}, 0, 8));
        // 00 00 03 03: only the first 03 goes
        assertArrayEquals(new byte[]{0, 0, 3},
                SpsParser.unescape(new byte[]{0, 0, 3, 3}, 0, 4));
    }
}
