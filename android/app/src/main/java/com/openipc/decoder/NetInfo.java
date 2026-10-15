/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * NetInfo.java — addresses of this device and the "local network" test for clients
 *
 */

package com.openipc.decoder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.util.Log;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
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

    /**
     * Binds {@code socket} to the network whose own subnet holds {@code target} (#187). A toy's
     * Wi-Fi has no internet: with mobile data on, Android may send even on-link traffic over the
     * default network instead. Returns what was done, for the log: "default" when no such
     * network is found (or before Android 5.1), else the interface bound to.
     */
    static String bindToNetworkOf(DatagramSocket socket, InetAddress target) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return "default";
        try {
            Found f = networkOf(target);
            if (f == null) return "default";
            f.network.bindSocket(socket);
            return "bound to " + f.name;
        } catch (Exception e) {
            Log.w(TAG, "No network bound for " + target.getHostAddress() + ": " + e.getMessage());
            return "default";
        }
    }

    /**
     * The same for a TCP socket that is not connected yet (the CTP connection of a jieli://
     * slot, #187); binding one needs Android 6.
     */
    static String bindToNetworkOf(Socket socket, InetAddress target) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "default";
        try {
            Found f = networkOf(target);
            if (f == null) return "default";
            f.network.bindSocket(socket);
            return "bound to " + f.name;
        } catch (Exception e) {
            Log.w(TAG, "No network bound for " + target.getHostAddress() + ": " + e.getMessage());
            return "default";
        }
    }

    private static final class Found {
        final Network network;
        final String name;

        Found(Network network, String name) {
            this.network = network;
            this.name = name;
        }
    }

    /** The network whose own subnet holds {@code target}, or null. */
    private static Found networkOf(InetAddress target) {
        Context ctx = App.context;
        if (ctx == null) return null;
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (sameSubnet(la.getAddress(), la.getPrefixLength(), target)) {
                    return new Found(n, lp.getInterfaceName());
                }
            }
        }
        return null;
    }

    /** {@code a} and {@code b} share the first {@code prefix} bits (and the address family). */
    static boolean sameSubnet(InetAddress a, int prefix, InetAddress b) {
        if (a == null || b == null) return false;
        byte[] x = a.getAddress();
        byte[] y = b.getAddress();
        if (x.length != y.length || prefix < 0 || prefix > x.length * 8) return false;
        for (int i = 0; i < prefix; i++) {
            int mask = 0x80 >> (i & 7);
            if ((x[i >> 3] & mask) != (y[i >> 3] & mask)) return false;
        }
        return true;
    }
}
