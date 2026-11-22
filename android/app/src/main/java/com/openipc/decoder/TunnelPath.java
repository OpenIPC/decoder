/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * TunnelPath.java — whether a connection goes through a VPN tunnel (#272)
 *
 */

package com.openipc.decoder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * A camera reached through a VPN tunnel (camex or any other): Auto then plays it over TCP. A
 * tunnel's MTU is often below a camera's RTP packets (about 1440 bytes; camex over UDP has 1420
 * by default), so over UDP
 * each packet travels in two IP fragments and a lost fragment loses it; over TCP the stream fits
 * the MTU, and a path too slow for the stream costs whole frames, not broken ones.
 */
final class TunnelPath {
    private TunnelPath() {
    }

    /** The local addresses of the VPN networks of the device (none without a VPN). */
    static List<InetAddress> vpnAddresses(Context c) {
        List<InetAddress> out = new ArrayList<>();
        if (c == null) return out;
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return out;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress a : lp.getLinkAddresses()) out.add(a.getAddress());
            }
        } catch (RuntimeException e) {
            // no answer from the system: take the path as not tunnelled
        }
        return out;
    }

    /** True when {@code local}, the local address of a connection, is a VPN address. */
    static boolean isTunnelled(InetAddress local, List<InetAddress> vpn) {
        return local != null && !local.isAnyLocalAddress() && vpn.contains(local);
    }
}
