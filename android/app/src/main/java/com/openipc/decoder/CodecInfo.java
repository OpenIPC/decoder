/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * CodecInfo.java — the device's video decoders and their abilities, for /stats.json (#88)
 *
 */

package com.openipc.decoder;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a shared key-frame decoder (#88) would rely on, per video decoder: the instance limit,
 * adaptive playback (a new size mid-stream without a reconfigure) and the 1080p frame rate it
 * declares. Built once; it does not change while the app runs.
 */
final class CodecInfo {
    private static volatile List<Map<String, Object>> cached;

    private CodecInfo() {}

    /** The list, or null while it is still being built (Qodo on #137: never on the UI thread). */
    static List<Map<String, Object>> videoDecodersIfReady() {
        return cached;
    }

    /** Builds the list on a background thread, once. */
    static void warmUp() {
        if (cached != null) return;
        Thread t = new Thread(CodecInfo::videoDecoders, "codec-info");
        t.setDaemon(true);
        t.start();
    }

    static synchronized List<Map<String, Object>> videoDecoders() {
        List<Map<String, Object>> c = cached;
        if (c != null) return c;
        List<Map<String, Object>> out = new ArrayList<>();
        MediaCodecInfo[] infos;
        try {
            infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        } catch (RuntimeException e) {
            return out; // not cached: the next request tries again
        }
        for (MediaCodecInfo ci : infos) {
            // One broken entry must not hide the others (Qodo on #137)
            try {
                if (ci.isEncoder()) continue;
                for (String type : ci.getSupportedTypes()) {
                    if (!type.startsWith("video/")) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", ci.getName());
                    m.put("type", type);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        m.put("hardware", ci.isHardwareAccelerated());
                    }
                    try {
                        MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType(type);
                        m.put("adaptive", caps.isFeatureSupported(
                                MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback));
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            m.put("max_instances", caps.getMaxSupportedInstances());
                        }
                        MediaCodecInfo.VideoCapabilities v = caps.getVideoCapabilities();
                        if (v != null && v.isSizeSupported(1920, 1080)) {
                            m.put("fps_1080p", Math.round(
                                    v.getSupportedFrameRatesFor(1920, 1080).getUpper()));
                        }
                    } catch (RuntimeException ignored) {
                        // Capabilities of a broken entry: the name and type are still worth it
                    }
                    out.add(m);
                }
            } catch (RuntimeException ignored) {
                // Skip this codec
            }
        }
        cached = out;
        return out;
    }
}
