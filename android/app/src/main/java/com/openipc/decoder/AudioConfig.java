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
        } else if (name.equals("PCMU")) {
            codec = PCMU;
        } else if (name.equals("PCMA")) {
            codec = PCMA;
        } else if (name.equals("L16")) {
            codec = PCM;
            bigEndian = true;
        } else {
            // MP4A-LATM, OPUS, G726, ... are not supported
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
