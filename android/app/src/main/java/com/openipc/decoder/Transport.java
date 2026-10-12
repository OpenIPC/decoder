/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * Transport.java — RTP transport choice of a camera slot and the RTSP Transport header
 *
 */

package com.openipc.decoder;

import java.util.Locale;

/**
 * The Transport header of a SETUP reply (RFC 2326 12.39). Servers word it differently:
 * Majestic answers "RTP/AVP/UDP;unicast;client_port=a-b;server_port=c-d", XM cameras
 * "RTP/AVP;unicast;mode=PLAY;source=ip;client_port=a-b;server_port=c-d;ssrc=x", and both
 * downgrade a multicast request to unicast, so the reply decides what is received (#67).
 */
class Transport {
    /**
     * Slot setting: RTP over the RTSP connection, over UDP, UDP with fallback to TCP, or
     * RTSP multicast with fallback to TCP (#69).
     */
    enum Mode {
        TCP, UDP, AUTO, MULTICAST;

        /** Stored or shared value ("tcp", "udp", "auto"); unknown values mean TCP. */
        static Mode parse(String s) {
            if (s != null) {
                for (Mode m : values()) {
                    if (m.name().equalsIgnoreCase(s.trim())) return m;
                }
            }
            return TCP;
        }
    }

    /** RTP over the RTSP connection ("RTP/AVP/TCP" or an interleaved= parameter). */
    boolean tcp;
    boolean multicast;
    int[] interleaved;
    int[] clientPort;
    int[] serverPort;
    /** Multicast group, or null. */
    String destination;
    /** Multicast ports. */
    int[] port;
    int ttl = -1;
    /** Address the server sends from, or null when not given. */
    String source;

    static Transport parse(String header) {
        Transport t = new Transport();
        if (header == null) return t;
        // A server may list several transports; the reply carries the chosen one first
        String first = header.split(",")[0];
        String[] parts = first.split(";");
        String spec = parts[0].trim().toUpperCase(Locale.ROOT);
        t.tcp = spec.endsWith("/TCP");
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim();
            int eq = p.indexOf('=');
            String name = (eq < 0 ? p : p.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
            String value = eq < 0 ? "" : p.substring(eq + 1).trim();
            switch (name) {
                case "multicast": t.multicast = true; break;
                case "unicast": t.multicast = false; break;
                case "interleaved":
                    t.interleaved = range(value);
                    if (t.interleaved != null) t.tcp = true;
                    break;
                case "client_port": t.clientPort = range(value); break;
                case "server_port": t.serverPort = range(value); break;
                case "port": t.port = range(value); break;
                case "destination": t.destination = unquote(value); break;
                case "source": t.source = unquote(value); break;
                case "ttl":
                    try {
                        t.ttl = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {}
                    break;
                default: break;
            }
        }
        return t;
    }

    /** "a-b" or "a" as {a, b} (b = a + 1 when absent), or null if not a port pair. */
    static int[] range(String value) {
        String[] ab = value.split("-", 2);
        try {
            int a = Integer.parseInt(ab[0].trim());
            int b = ab.length > 1 ? Integer.parseInt(ab[1].trim()) : a + 1;
            if (a < 0 || a > 65535 || b < 0 || b > 65535) return null;
            return new int[] {a, b};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unquote(String v) {
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")
                ? v.substring(1, v.length() - 1) : v;
    }

    /** Request header value for interleaved RTP on channels {@code ch} and {@code ch + 1}. */
    static String tcpRequest(int ch) {
        return "RTP/AVP/TCP;unicast;interleaved=" + ch + "-" + (ch + 1);
    }

    /** Request header value for multicast; the server picks group, ports and TTL. */
    static String multicastRequest() {
        return "RTP/AVP;multicast";
    }

    /** Request header value for UDP unicast to local ports {@code rtp} and {@code rtp + 1}. */
    static String udpRequest(int rtp) {
        return "RTP/AVP;unicast;client_port=" + rtp + "-" + (rtp + 1);
    }
}
