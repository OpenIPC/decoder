/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * SpsParser.java — picture size from an H.264 / H.265 sequence parameter set
 *
 */

package com.openipc.decoder;

/**
 * Reads the visible picture size (after the cropping / conformance window) from an SPS, so a
 * decoder can be configured for the stream's real size before the first frame (#166).
 */
final class SpsParser {
    private SpsParser() {}

    /**
     * Size {width, height} from one NAL unit (no start code, header included), or null if it
     * is not an SPS or cannot be read.
     */
    static int[] size(byte[] nal, int offset, int length, boolean h265) {
        if (nal == null || length < (h265 ? 3 : 2) || offset < 0
                || offset + length > nal.length) return null;
        try {
            Bits b = new Bits(unescape(nal, offset, length));
            return h265 ? h265(b) : h264(b);
        } catch (RuntimeException e) {
            // Truncated or malformed: the size stays unknown
            return null;
        }
    }

    private static int[] h264(Bits b) {
        if ((b.u(8) & 0x1f) != MediaCodecManager.H264_NAL_SPS) return null;
        int profile = b.u(8);
        b.u(8); // constraint flags
        b.u(8); // level
        b.ue(); // seq_parameter_set_id
        int chroma = 1;
        boolean separatePlanes = false;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244
                || profile == 44 || profile == 83 || profile == 86 || profile == 118
                || profile == 128 || profile == 138 || profile == 139 || profile == 134
                || profile == 135) {
            chroma = b.ue();
            if (chroma == 3) separatePlanes = b.u(1) == 1;
            b.ue(); // bit_depth_luma_minus8
            b.ue(); // bit_depth_chroma_minus8
            b.u(1); // qpprime_y_zero_transform_bypass_flag
            if (b.u(1) == 1) {
                for (int i = 0; i < (chroma != 3 ? 8 : 12); i++) {
                    if (b.u(1) == 1) skipScalingList(b, i < 6 ? 16 : 64);
                }
            }
        }
        b.ue(); // log2_max_frame_num_minus4
        int pocType = b.ue();
        if (pocType == 0) {
            b.ue(); // log2_max_pic_order_cnt_lsb_minus4
        } else if (pocType == 1) {
            b.u(1); // delta_pic_order_always_zero_flag
            b.se(); // offset_for_non_ref_pic
            b.se(); // offset_for_top_to_bottom_field
            int n = b.ue();
            for (int i = 0; i < n; i++) b.se();
        }
        b.ue(); // max_num_ref_frames
        b.u(1); // gaps_in_frame_num_value_allowed_flag
        int widthMbs = b.ue() + 1;
        int heightUnits = b.ue() + 1;
        int frameMbsOnly = b.u(1);
        if (frameMbsOnly == 0) b.u(1); // mb_adaptive_frame_field_flag
        b.u(1); // direct_8x8_inference_flag
        int width = widthMbs * 16;
        int height = (2 - frameMbsOnly) * heightUnits * 16;
        if (b.u(1) == 1) {
            int left = b.ue(), right = b.ue(), top = b.ue(), bottom = b.ue();
            int arrayType = separatePlanes ? 0 : chroma;
            int cropX = arrayType == 1 || arrayType == 2 ? 2 : 1;
            int cropY = (arrayType == 1 ? 2 : 1) * (2 - frameMbsOnly);
            width -= cropX * (left + right);
            height -= cropY * (top + bottom);
        }
        return valid(width, height);
    }

    private static void skipScalingList(Bits b, int size) {
        int last = 8, next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) next = (last + b.se() + 256) % 256;
            last = next == 0 ? last : next;
        }
    }

    private static int[] h265(Bits b) {
        if (((b.u(8) >> 1) & 0x3f) != MediaCodecManager.H265_NAL_SPS) return null;
        b.u(8); // rest of the NAL header
        b.u(4); // sps_video_parameter_set_id
        int subLayers = b.u(3); // sps_max_sub_layers_minus1
        b.u(1); // sps_temporal_id_nesting_flag
        // profile_tier_level(1, subLayers): general part, 96 bits
        b.skip(96);
        boolean[] profile = new boolean[subLayers];
        boolean[] level = new boolean[subLayers];
        for (int i = 0; i < subLayers; i++) {
            profile[i] = b.u(1) == 1;
            level[i] = b.u(1) == 1;
        }
        if (subLayers > 0) b.skip(2 * (8 - subLayers));
        for (int i = 0; i < subLayers; i++) {
            if (profile[i]) b.skip(88);
            if (level[i]) b.skip(8);
        }
        b.ue(); // sps_seq_parameter_set_id
        int chroma = b.ue();
        boolean separatePlanes = chroma == 3 && b.u(1) == 1;
        int width = b.ue();
        int height = b.ue();
        if (b.u(1) == 1) {
            int left = b.ue(), right = b.ue(), top = b.ue(), bottom = b.ue();
            int arrayType = separatePlanes ? 0 : chroma;
            int subW = arrayType == 1 || arrayType == 2 ? 2 : 1;
            int subH = arrayType == 1 ? 2 : 1;
            width -= subW * (left + right);
            height -= subH * (top + bottom);
        }
        return valid(width, height);
    }

    private static int[] valid(int width, int height) {
        // Beyond 8K or empty: not a size worth configuring a decoder for
        return width > 0 && height > 0 && width <= 8192 && height <= 8192
                ? new int[]{width, height} : null;
    }

    /** The NAL unit without its emulation prevention bytes (00 00 03 → 00 00). */
    static byte[] unescape(byte[] data, int offset, int length) {
        byte[] out = new byte[length];
        int n = 0, zeros = 0;
        for (int i = offset; i < offset + length; i++) {
            int v = data[i] & 0xff;
            if (zeros >= 2 && v == 3) {
                zeros = 0;
                continue;
            }
            zeros = v == 0 ? zeros + 1 : 0;
            out[n++] = (byte) v;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** MSB-first bit reader with Exp-Golomb codes; reading past the end throws. */
    private static final class Bits {
        private final byte[] data;
        private int pos;

        Bits(byte[] data) { this.data = data; }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (pos >= data.length * 8) throw new IllegalStateException("SPS truncated");
                v = (v << 1) | ((data[pos >> 3] >> (7 - (pos & 7))) & 1);
                pos++;
            }
            return v;
        }

        void skip(int n) {
            if (pos + n > data.length * 8) throw new IllegalStateException("SPS truncated");
            pos += n;
        }

        int ue() {
            int zeros = 0;
            while (u(1) == 0) {
                if (++zeros > 31) throw new IllegalStateException("bad Exp-Golomb code");
            }
            return zeros == 0 ? 0 : (int) ((1L << zeros) - 1 + u(zeros));
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }
}
