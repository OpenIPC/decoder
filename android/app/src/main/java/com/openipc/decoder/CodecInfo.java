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

    static List<Map<String, Object>> videoDecoders() {
        List<Map<String, Object>> c = cached;
        if (c != null) return c;
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.ALL_CODECS)
                    .getCodecInfos()) {
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
            }
        } catch (RuntimeException ignored) {
        }
        cached = out;
        return out;
    }
}
