/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * TunnelPathTest.java — a connection's local address against the VPN addresses (#272)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TunnelPathTest {
    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    public void vpnAddressIsTunnelled() throws Exception {
        List<InetAddress> vpn = Arrays.asList(ip("10.255.94.168"));
        assertTrue(TunnelPath.isTunnelled(ip("10.255.94.168"), vpn));
        assertFalse(TunnelPath.isTunnelled(ip("172.17.32.157"), vpn));
    }

    @Test
    public void noVpnNoTunnel() throws Exception {
        assertFalse(TunnelPath.isTunnelled(ip("10.255.94.168"), Collections.emptyList()));
        assertFalse(TunnelPath.isTunnelled(null, Arrays.asList(ip("10.255.94.168"))));
        assertFalse(TunnelPath.isTunnelled(ip("0.0.0.0"), Arrays.asList(ip("0.0.0.0"))));
    }
}
