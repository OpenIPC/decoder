/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * Sdp.java — session description parsed from a DESCRIBE response
 *
 */

package com.openipc.decoder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class Sdp {
    /** Legacy payload types, used when the SDP has no rtpmap for the video track. */
    static final int PT_H264 = 96;
    static final int PT_H265 = 97;
    /** Static payload type of RTP/JPEG (RFC 3551), used without an rtpmap (#119). */
    static final int PT_JPEG = 26;

    String videoUrl;
    /** Null when the stream has no audio track. */
    String audioUrl;
    int videoPt = -1;
    boolean videoH265;
    /** MJPEG (RTP/JPEG, RFC 2435) instead of H.264/H.265 (#119). */
    boolean videoJpeg;
    AudioConfig audio = new AudioConfig();

    /**
     * Parse an SDP. {@code base} is the Content-Base of the DESCRIBE response, or the
     * request URL when the server did not send one.
     */
    static Sdp parse(String sdp, String base) {
        Sdp out = new Sdp();
        String session = null;
        String audioControl = null;
        int media = -1; // -1 session level, 0 video, 1 audio, 2 other
        boolean hasAudio = false;
        // Each m=video section keeps its own payloads, rtpmap and control
        List<VideoSection> videos = new ArrayList<>();
        VideoSection video = null;
        AudioConfig audio = out.audio;

        for (String line : sdp.split("[\r\n]+")) {
            if (line.startsWith("m=")) {
                String[] parts = line.split("\\s+");
                if (line.startsWith("m=video")) {
                    media = 0;
                    video = new VideoSection();
                    videos.add(video);
                    for (int i = 3; i < parts.length; i++) {
                        try {
                            video.pts.add(Integer.parseInt(parts[i]));
                        } catch (NumberFormatException ignored) {}
                    }
                } else if (line.startsWith("m=audio")) {
                    media = 1;
                    hasAudio = true;
                    if (parts.length >= 4) {
                        try {
                            audio.pt = Integer.parseInt(parts[3]);
                            audio.applyStaticPayloadType();
                        } catch (NumberFormatException ignored) {}
                    }
                } else {
                    media = 2;
                }
            } else if (line.startsWith("a=control:")) {
                String ctrl = line.substring("a=control:".length()).trim();
                if (media == -1) {
                    session = ctrl;
                } else if (media == 0) {
                    video.control = ctrl;
                } else if (media == 1 && audioControl == null) {
                    audioControl = ctrl;
                }
            } else if (media == 0 && line.startsWith("a=rtpmap:")) {
                // a=rtpmap:<pt> <encoding>/<clock rate>
                String[] parts = line.substring("a=rtpmap:".length()).trim().split("[\\s/]+");
                if (parts.length >= 2) {
                    try {
                        video.codecs.put(Integer.parseInt(parts[0]),
                                parts[1].toUpperCase(Locale.ROOT));
                    } catch (NumberFormatException ignored) {}
                }
            } else if (media == 1 && audio.pt >= 0
                    && line.startsWith("a=rtpmap:" + audio.pt + " ")) {
                audio.applyRtpmap(line.substring(("a=rtpmap:" + audio.pt + " ").length()));
            } else if (media == 1 && audio.pt >= 0
                    && line.startsWith("a=fmtp:" + audio.pt + " ")) {
                audio.applyFmtp(line.substring(("a=fmtp:" + audio.pt + " ").length()));
            }
        }
        audio.finish();

        // Video: the first section offering H.264/H.265 (legacy 96/97 without rtpmap) or, if
        // none does, MJPEG (#119);
        // payload type and control URL always come from that same section
        String sessionBase = resolve(base, session);
        String videoControl = null;
        outer:
        for (VideoSection v : videos) {
            for (int pt : v.pts) {
                String codec = v.codecs.get(pt);
                if (codec == null) {
                    codec = pt == PT_H265 ? "H265" : pt == PT_H264 ? "H264" : null;
                }
                if ("H264".equals(codec) || "H265".equals(codec) || "HEVC".equals(codec)) {
                    out.videoPt = pt;
                    out.videoH265 = !"H264".equals(codec);
                    out.videoJpeg = false;
                    videoControl = v.control;
                    break outer;
                }
                boolean jpeg = codec != null ? "JPEG".equals(codec) : pt == PT_JPEG;
                if (jpeg && out.videoPt < 0) {
                    out.videoPt = pt;
                    out.videoJpeg = true;
                    videoControl = v.control;
                }
            }
        }
        out.videoUrl = resolve(sessionBase, videoControl != null ? videoControl : "trackID=0");
        if (hasAudio) {
            out.audioUrl = resolve(sessionBase, audioControl != null ? audioControl : "trackID=1");
        }
        return out;
    }

    private static final class VideoSection {
        final List<Integer> pts = new ArrayList<>();
        final Map<Integer, String> codecs = new HashMap<>();
        String control;
    }

    /** Resolve an a=control value against a base URL (RFC 2326 C.1.1). */
    static String resolve(String base, String control) {
        if (control == null || control.isEmpty() || control.equals("*")) return base;
        String lower = control.toLowerCase(Locale.ROOT);
        if (lower.startsWith("rtsp://") || lower.startsWith("rtsps://")) return control;
        if (control.startsWith("/")) {
            // Root-relative: keep scheme://authority of the base
            int authorityStart = base.indexOf("://");
            int pathStart = authorityStart < 0 ? -1 : base.indexOf('/', authorityStart + 3);
            return (pathStart < 0 ? base : base.substring(0, pathStart)) + control;
        }
        return base.endsWith("/") ? base + control : base + "/" + control;
    }
}
