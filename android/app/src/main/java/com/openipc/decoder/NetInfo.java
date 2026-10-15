/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * NetInfo.java — addresses of this device and the "local network" test for clients
 *
 */

package com.openipc.decoder;

import android.util.Log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class NetInfo {
    private static final String TAG = "OpenIPCDecoder";

    private NetInfo() {}

    /** Interface name prefixes of mobile data (#203): never a LAN the debug page answers. */
    private static final String[] CELLULAR = {
            "rmnet", "rev_rmnet", "ccmni", "v4-", "clat", "seth", "pdp", "wwan"};

    /**
     * The debug page's addresses (#87, #96): private IPv4 of every interface that is up and
     * not mobile data, Wi-Fi first, then Ethernet, then the rest (#203).
     */
    static List<String> addresses() {
        Map<String, List<InetAddress>> byInterface = new LinkedHashMap<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp()) continue;
                byInterface.put(nif.getName(), Collections.list(nif.getInetAddresses()));
            }
        } catch (Exception e) {
            Log.w(TAG, "No local address: " + e.getMessage());
        }
        return lanAddresses(byInterface);
    }

    /**
     * Every IPv4 address a pushed RTP stream can arrive on (the UDP receiver listens on all of
     * them), mobile data and public ones too, for the U view's hint (#68, Qodo on #206):
     * no loopback, link-local or wildcard address.
     */
    static List<String> udpAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (isUsable(a) && !out.contains(a.getHostAddress())) out.add(a.getHostAddress());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "No local address: " + e.getMessage());
        }
        return out;
    }

    static boolean isUsable(InetAddress a) {
        return a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()
                && !a.isAnyLocalAddress();
    }

    /**
     * The addresses a LAN client can reach the debug server on, from the interfaces in their
     * system order (#203): no mobile-data interface and only private IPv4 (isShown), as the
     * server answers LAN clients only. Wi-Fi (also a hotspot) first, then Ethernet.
     */
    static List<String> lanAddresses(Map<String, List<InetAddress>> byInterface) {
        List<String> out = new ArrayList<>();
        for (int rank = 0; rank <= 2; rank++) {
            for (Map.Entry<String, List<InetAddress>> e : byInterface.entrySet()) {
                String name = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
                if (isCellular(name) || rank(name) != rank) continue;
                for (InetAddress a : e.getValue()) {
                    if (isShown(a) && !out.contains(a.getHostAddress())) out.add(a.getHostAddress());
                }
            }
        }
        return out;
    }

    static boolean isCellular(String name) {
        for (String prefix : CELLULAR) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 0 Wi-Fi, 1 Ethernet, 2 anything else (USB tethering, a bridge ...). */
    private static int rank(String name) {
        if (name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap")
                || name.startsWith("wifi")) return 0;
        if (name.startsWith("eth") || name.startsWith("en")) return 1;
        return 2;
    }

    /**
     * An address to show: private IPv4 (10/8, 172.16/12, 192.168/16) — no loopback, no
     * link-local and nothing public or carrier-grade NAT, which the debug server never
     * answers (#203).
     */
    static boolean isShown(InetAddress a) {
        return a instanceof Inet4Address && a.isSiteLocalAddress();
    }

    /**
     * A client on the local network: loopback, private IPv4 (10/8, 172.16/12, 192.168/16),
     * link-local (169.254/16, fe80::/10) or IPv6 unique-local (fc00::/7). An IPv4 client on a
     * dual-stack socket arrives as ::ffff:a.b.c.d, which Java already turns into IPv4.
     * The deprecated IPv6 site-local range (fec0::/10) is not local here.
     */
    static boolean isLocalClient(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet4Address) return a.isSiteLocalAddress();
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
    }
}
