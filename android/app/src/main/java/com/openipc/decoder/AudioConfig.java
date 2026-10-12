/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * AudioConfig.java — audio track parameters parsed from the SDP
 *
 */

package com.openipc.decoder;

class AudioConfig {
    static final String NONE = "NONE"; // no audio track or unsupported codec
    static final String PCM  = "PCM";  // L16
    static final String PCMU = "PCMU"; // G.711 mu-law
    static final String PCMA = "PCMA"; // G.711 A-law
    static final String AAC  = "AAC";
    static final String LATM = "LATM"; // MP4A-LATM (RFC 6416): AAC in LATM framing
    static final String OPUS = "OPUS";

    String codec = NONE;
    int pt = -1;
    int sampleRate = 8000;
    int channels = 1;
    boolean bigEndian;

    // AAC: AudioSpecificConfig from fmtp "config=", null when absent
    byte[] aacConfig;
    // AAC RFC 3640 (mpeg4-generic) AU-header layout; sizeLength == 0 means no AU headers
    int sizeLength;
    int indexLength;
    int indexDeltaLength;
    // MP4A-LATM: "cpresent=1" puts the configuration in-band, which is not supported
    boolean latmConfigInBand;

    /** Fill defaults for static RTP payload types (RFC 3551), which may come without rtpmap. */
    void applyStaticPayloadType() {
        switch (pt) {
            case 0:  codec = PCMU; sampleRate = 8000;  channels = 1; break;
            case 8:  codec = PCMA; sampleRate = 8000;  channels = 1; break;
            case 10: codec = PCM;  sampleRate = 44100; channels = 2; bigEndian = true; break;
            case 11: codec = PCM;  sampleRate = 44100; channels = 1; bigEndian = true; break;
            default: break;
        }
    }

    /** Apply "a=rtpmap:<pt> <name>/<rate>[/<channels>]". */
    void applyRtpmap(String encoding) {
        String[] parts = encoding.trim().split("/");
        String name = parts[0].toUpperCase(java.util.Locale.ROOT);

        bigEndian = false;
        if (name.equals("MPEG4-GENERIC") || name.equals("AAC") || name.equals("MP4A")) {
            codec = AAC;
        } else if (name.equals("MP4A-LATM")) {
            codec = LATM;
        } else if (name.equals("OPUS")) {
            codec = OPUS;
        } else if (name.equals("PCMU")) {
            codec = PCMU;
        } else if (name.equals("PCMA")) {
            codec = PCMA;
        } else if (name.equals("L16")) {
            codec = PCM;
            bigEndian = true;
        } else {
            // G.726 and others are not supported
            codec = NONE;
        }

        if (parts.length > 1) {
            try {
                int rate = Integer.parseInt(parts[1].trim());
                if (rate > 0 && rate <= 192000) sampleRate = rate;
            } catch (NumberFormatException ignored) {}
        }
        channels = 1;
        if (parts.length > 2) {
            try {
                int ch = Integer.parseInt(parts[2].trim());
                if (ch == 1 || ch == 2) channels = ch;
            } catch (NumberFormatException ignored) {}
        }
    }

    /** Apply "a=fmtp:<pt> key=value; key=value". */
    void applyFmtp(String params) {
        for (String param : params.split(";")) {
            int eq = param.indexOf('=');
            if (eq <= 0) continue;
            String key = param.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
            String value = param.substring(eq + 1).trim();
            try {
                switch (key) {
                    case "config":           aacConfig = parseHex(value); break;
                    case "sizelength":       sizeLength = Integer.parseInt(value); break;
                    case "indexlength":      indexLength = Integer.parseInt(value); break;
                    case "indexdeltalength": indexDeltaLength = Integer.parseInt(value); break;
                    case "cpresent":         latmConfigInBand = !"0".equals(value); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    /**
     * Validate after all SDP attributes are applied (rtpmap and fmtp may come in any order).
     * AAC with an impossible AU-header layout is disabled rather than misparsed.
     */
    void finish() {
        if (AAC.equals(codec) && (sizeLength < 0 || sizeLength > 16
                || indexLength < 0 || indexLength > 16
                || indexDeltaLength < 0 || indexDeltaLength > 16)) {
            codec = NONE;
        }
        if (OPUS.equals(codec)) {
            // RFC 7587: always 48 kHz and "/2" in rtpmap; the decoder handles mono streams too
            sampleRate = 48000;
            channels = 2;
        }
        if (LATM.equals(codec)) {
            // fmtp config= is a StreamMuxConfig here; turn it into an AudioSpecificConfig
            byte[] asc = latmConfigInBand || aacConfig == null ? null : latmToAsc(aacConfig);
            if (asc == null) {
                codec = NONE;
            } else {
                aacConfig = asc;
                int[] info = ascInfo(asc);
                if (info != null) {
                    sampleRate = info[0];
                    channels = info[1];
                }
            }
        }
    }

    private static final int[] AAC_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350
    };

    /**
     * StreamMuxConfig (audioMuxVersion 0, one program, one layer) to a 2-byte
     * AudioSpecificConfig, or null if the layout is not supported.
     */
    static byte[] latmToAsc(byte[] smc) {
        BitReader r = new BitReader(smc);
        if (r.bits(1) != 0) return null;  // audioMuxVersion 1 is not supported
        r.bits(1);                         // allStreamsSameTimeFraming
        r.bits(6);                         // numSubFrames
        if (r.bits(4) != 0) return null;  // numProgram + 1 must be 1
        if (r.bits(3) != 0) return null;  // numLayer + 1 must be 1
        int objectType = r.bits(5);
        if (objectType == 31) objectType = 32 + r.bits(6);
        int freqIndex = r.bits(4);
        if (freqIndex == 15) return null;  // explicit frequency: not supported
        int channelConfig = r.bits(4);
        if (r.overrun() || objectType < 1 || objectType > 4 || channelConfig < 1
                || channelConfig > 7) {
            return null;
        }
        return new byte[] {
                (byte) ((objectType << 3) | (freqIndex >> 1)),
                (byte) (((freqIndex & 1) << 7) | (channelConfig << 3))
        };
    }

    /** {sample rate, channels} of a 2-byte AudioSpecificConfig, or null. */
    static int[] ascInfo(byte[] asc) {
        if (asc == null || asc.length < 2) return null;
        int freqIndex = ((asc[0] & 0x07) << 1) | ((asc[1] & 0x80) >> 7);
        int channelConfig = (asc[1] & 0x78) >> 3;
        if (freqIndex >= AAC_RATES.length || channelConfig < 1) return null;
        return new int[] {AAC_RATES[freqIndex], Math.min(channelConfig, 2)};
    }

    private static final class BitReader {
        private final byte[] data;
        private int pos;

        BitReader(byte[] data) { this.data = data; }

        int bits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++, pos++) {
                int bit = pos >> 3 < data.length ? (data[pos >> 3] >> (7 - (pos & 7))) & 1 : 0;
                v = (v << 1) | bit;
            }
            return v;
        }

        boolean overrun() { return pos > data.length * 8; }
    }

    private static byte[] parseHex(String hex) {
        if (hex.isEmpty() || hex.length() % 2 != 0) return null;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16);
            int lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
