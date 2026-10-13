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
import java.util.List;

final class NetInfo {
    private static final String TAG = "OpenIPCDecoder";

    private NetInfo() {}

    /**
     * IPv4 addresses of every interface that is up, the ones worth showing to a user who has
     * to type one in: no loopback (127.0.0.0/8) and no link-local (169.254.0.0/16) (#87, #96).
     */
    static List<String> addresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (isShown(a) && !out.contains(a.getHostAddress())) out.add(a.getHostAddress());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "No local address: " + e.getMessage());
        }
        return out;
    }

    static boolean isShown(InetAddress a) {
        return a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()
                && !a.isAnyLocalAddress();
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
