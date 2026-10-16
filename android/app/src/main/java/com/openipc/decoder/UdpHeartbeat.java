/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * UdpHeartbeat.java — the "01 01" heartbeat that cooingdv RTSP toys expect on UDP 7099 (#187)
 *
 */

package com.openipc.decoder;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Toys of the cooingdv apps (KY UFO, RC UFO, ...; Wi-Fi "FLY-UFO-…" at 192.168.1.1) stream
 * RTSP from rtsp://192.168.1.1:7070/webcam, and their apps send {@code 01 01} to UDP port
 * 7099 once a second while they show it (described by turbodrone, docs/research/cooingdv.md).
 * This sends exactly that heartbeat, from an ephemeral socket on the address the RTSP
 * connection uses, and nothing else: no flight command and no "08 01" either. One thread per
 * heartbeat, until close().
 */
final class UdpHeartbeat implements Closeable {
    private static final String TAG = "OpenIPCDecoder";

    static final byte[] PAYLOAD = {0x01, 0x01};
    static final int DEFAULT_PORT = 7099;
    /** Not final so unit tests can shorten it. */
    static volatile long intervalMs = 1000;

    private final String tag;
    private final InetAddress local;
    private final InetSocketAddress target;
    private volatile boolean closed;
    private volatile DatagramSocket socket;
    private Thread thread;
    private volatile int sent;
    private volatile int errors;
    private volatile String lastError;

    /** @param local address to send from (the RTSP connection's), or null for any. */
    UdpHeartbeat(String tag, InetAddress local, InetSocketAddress target) {
        this.tag = tag;
        this.local = local;
        this.target = target;
    }

    /** Opens the socket and starts sending, the first heartbeat at once. */
    synchronized void start(boolean log) throws IOException {
        if (closed || thread != null) return;
        DatagramSocket s = local != null
                ? new DatagramSocket(new InetSocketAddress(local, 0)) : new DatagramSocket();
        String network = NetInfo.bindToNetworkOf(s, target.getAddress());
        socket = s;
        if (log) {
            Log.i(TAG, tag + ": heartbeat 01 01 every " + intervalMs + " ms to "
                    + target.getAddress().getHostAddress() + ":" + target.getPort()
                    + " from local port " + s.getLocalPort() + " (network: " + network + ")");
        }
        thread = new Thread(() -> loop(s), tag + "-hb");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop(DatagramSocket s) {
        DatagramPacket p = new DatagramPacket(PAYLOAD, PAYLOAD.length, target);
        long next = System.nanoTime() / 1_000_000;
        while (!closed) {
            try {
                s.send(p);
                sent++;
            } catch (IOException e) {
                if (closed) break;
                errors++;
                lastError = e.getMessage();
                if (errors <= 3 || errors % 100 == 0) {
                    Log.w(TAG, tag + ": heartbeat failed (" + errors + "): " + e.getMessage());
                }
            }
            // A fixed schedule: a slow send does not shift the following ones
            next += intervalMs;
            long wait = next - System.nanoTime() / 1_000_000;
            if (wait < 0) {
                next = System.nanoTime() / 1_000_000;
                wait = 0;
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                break;
            }
        }
        s.close();
    }

    int sent() { return sent; }

    int errors() { return errors; }

    String lastError() { return lastError; }

    InetSocketAddress target() { return target; }

    @Override
    public synchronized void close() {
        closed = true;
        DatagramSocket s = socket;
        if (s != null) s.close();
        if (thread != null) thread.interrupt();
    }

    /** Running or not: true until close(). */
    boolean running() { return !closed && thread != null; }

    static Map<String, Object> stats(UdpHeartbeat h, int sentBefore, int errorsBefore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payload", "01 01");
        if (h != null) {
            m.put("target", h.target.getAddress().getHostAddress() + ":" + h.target.getPort());
            m.put("running", h.running());
            if (h.lastError != null) m.put("last_error", h.lastError);
        } else {
            m.put("running", false);
        }
        m.put("sent", sentBefore + (h != null ? h.sent : 0));
        int e = errorsBefore + (h != null ? h.errors : 0);
        if (e > 0) m.put("errors", e);
        return m;
    }
}
