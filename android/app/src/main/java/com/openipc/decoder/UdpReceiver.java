/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * UdpReceiver.java — RTP over UDP: local port pairs and the receive loop
 *
 */

package com.openipc.decoder;

import android.annotation.TargetApi;
import android.os.Build;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;

/**
 * One RTP/RTCP port pair per track, bound to ephemeral ports (never fixed ones, see #40),
 * and one loop that receives every track on the calling thread, so packets reach the
 * callback from a single thread as they do over TCP (#67). RTCP is read and discarded.
 */
class UdpReceiver implements Closeable {
    private static final String TAG = "OpenIPCDecoder";

    interface Handler {
        void onPacket(int channel, byte[] buf, int offset, int length);
    }

    /** java.util.function.BooleanSupplier needs API 24. */
    interface Condition {
        boolean holds();
    }

    /** Hold time of the reorder buffer; only spent when a packet is missing. */
    static final int REORDER_MS = 40;
    private static final int REORDER_PACKETS = 64;
    private static final int VIDEO_RCVBUF = 2 * 1024 * 1024;
    private static final int AUDIO_RCVBUF = 256 * 1024;
    private static final int BIND_ATTEMPTS = 32;

    private static final class Track {
        final int channel;
        final DatagramChannel rtp;
        /** Null for a multicast track: its RTCP is not received. */
        final DatagramChannel rtcp;
        final RtpReorder reorder = new RtpReorder(REORDER_PACKETS, REORDER_MS);
        /** Delivers to the handler of the running loop; one per track, not per packet. */
        RtpReorder.Sink sink;

        Track(int channel, DatagramChannel rtp, DatagramChannel rtcp) {
            this.channel = channel;
            this.rtp = rtp;
            this.rtcp = rtcp;
        }
    }

    private final String tag;
    private final Selector selector;
    private final List<Track> tracks = new ArrayList<>();
    /** Accepted senders; empty means any. */
    private final List<InetAddress> sources = new ArrayList<>();
    private volatile boolean closed;
    private boolean running;
    private boolean released;
    private boolean multicastLocked;
    private long rejected;

    UdpReceiver(String tag) throws IOException {
        this.tag = tag;
        this.selector = Selector.open();
    }

    /**
     * Bind an even RTP port and the odd RTCP port above it for {@code channel}
     * (RtspSession.CHANNEL_VIDEO or CHANNEL_AUDIO). Returns the RTP port.
     */
    synchronized int addTrack(int channel) throws IOException {
        if (closed) throw new IOException("Receiver closed");
        for (int attempt = 0; attempt < BIND_ATTEMPTS; attempt++) {
            DatagramChannel rtp = DatagramChannel.open();
            DatagramChannel rtcp = null;
            try {
                // Through socket(): DatagramChannel.bind() and setOption() need API 24
                rtp.socket().bind(new InetSocketAddress(0));
                int port = rtp.socket().getLocalPort();
                if ((port & 1) != 0 || port >= 65535) {
                    rtp.close();
                    continue;
                }
                rtcp = DatagramChannel.open();
                rtcp.socket().bind(new InetSocketAddress(port + 1));
                int size = channel == RtspSession.CHANNEL_VIDEO ? VIDEO_RCVBUF : AUDIO_RCVBUF;
                rtp.socket().setReceiveBufferSize(size);
                rtp.configureBlocking(false);
                rtcp.configureBlocking(false);
                Track t = new Track(channel, rtp, rtcp);
                rtp.register(selector, SelectionKey.OP_READ, t);
                rtcp.register(selector, SelectionKey.OP_READ, null);
                tracks.add(t);
                return port;
            } catch (IOException e) {
                // Port above taken by someone else: try another pair
                rtp.close();
                if (rtcp != null) rtcp.close();
            }
        }
        throw new IOException("No free UDP port pair");
    }

    /** Joining a group from a DatagramChannel needs API 24 (Android 7). Tests override it. */
    static volatile int sdkInt = Build.VERSION.SDK_INT;

    static boolean multicastSupported() {
        return sdkInt >= 24;
    }

    /**
     * Join {@code group} on {@code port} for {@code channel}, on the interface that owns
     * {@code local} (the local address of the RTSP connection, #69). Only when
     * {@link #multicastSupported()}.
     */
    @TargetApi(24)
    synchronized void addMulticastTrack(int channel, InetAddress group, int port,
                                        InetAddress local) throws IOException {
        if (closed) throw new IOException("Receiver closed");
        if (!group.isMulticastAddress()) throw new IOException("Not a multicast group: " + group);
        NetworkInterface nif = NetworkInterface.getByInetAddress(local);
        if (nif == null) throw new IOException("No network interface for " + local);
        DatagramChannel ch = DatagramChannel.open(group instanceof Inet6Address
                ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
        try {
            // Other receivers of the group on this device bind the same port
            ch.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            ch.bind(new InetSocketAddress(port));
            ch.setOption(StandardSocketOptions.SO_RCVBUF,
                    channel == RtspSession.CHANNEL_VIDEO ? VIDEO_RCVBUF : AUDIO_RCVBUF);
            ch.join(group, nif);
            ch.configureBlocking(false);
            Track t = new Track(channel, ch, null);
            ch.register(selector, SelectionKey.OP_READ, t);
            tracks.add(t);
        } catch (IOException e) {
            ch.close();
            throw e;
        }
        if (!multicastLocked) {
            multicastLocked = true;
            RtpListener.MulticastLock.acquire();
        }
    }

    /** Accept datagrams only from these addresses (nulls are ignored). */
    synchronized void setSources(InetAddress... addresses) {
        sources.clear();
        for (InetAddress a : addresses) {
            if (a != null && !sources.contains(a)) sources.add(a);
        }
    }

    /**
     * Receive until {@code keepGoing} turns false or {@link #close} is called. The
     * condition is checked at least every 250 ms.
     */
    void run(Condition keepGoing, Handler handler) throws IOException {
        List<InetAddress> accepted;
        synchronized (this) {
            if (closed) return;
            running = true;
            accepted = new ArrayList<>(sources);
        }
        for (Track t : tracks) {
            t.sink = (b, off, len) -> handler.onPacket(t.channel, b, off, len);
        }
        ByteBuffer buf = ByteBuffer.allocate(65536);
        byte[] array = buf.array();
        try {
            while (!closed && keepGoing.holds()) {
                long now = System.nanoTime();
                long timeout = 250;
                for (Track t : tracks) {
                    long ms = t.reorder.msUntilDeadline(now);
                    if (ms >= 0) timeout = Math.max(1, Math.min(timeout, ms));
                }
                selector.select(timeout);
                for (SelectionKey key : selector.selectedKeys()) {
                    if (!key.isValid()) continue;
                    DatagramChannel ch = (DatagramChannel) key.channel();
                    Track t = (Track) key.attachment();
                    SocketAddress from;
                    while ((from = receive(ch, buf)) != null) {
                        if (t == null) continue; // RTCP: not used
                        if (!accepted.isEmpty() && !accepted.contains(
                                ((InetSocketAddress) from).getAddress())) {
                            if (rejected++ % 100 == 0) {
                                Log.w(TAG, tag + ": UDP from an unexpected sender ignored ("
                                        + rejected + ")");
                            }
                            continue;
                        }
                        t.reorder.push(array, 0, buf.position(), System.nanoTime(), t.sink);
                    }
                }
                selector.selectedKeys().clear();
                now = System.nanoTime();
                for (Track t : tracks) {
                    t.reorder.poll(now, t.sink);
                }
            }
        } catch (ClosedSelectorException e) {
            // close() from another thread
        } finally {
            for (Track t : tracks) {
                if (t.reorder.dropped() > 0 || t.reorder.skipped() > 0) {
                    Log.i(TAG, tag + ": UDP channel " + t.channel + ": " + t.reorder.skipped()
                            + " packets lost, " + t.reorder.dropped() + " late or duplicate");
                }
            }
            release();
        }
    }

    private static SocketAddress receive(DatagramChannel ch, ByteBuffer buf) throws IOException {
        buf.clear();
        return ch.receive(buf);
    }

    /** Ends {@link #run} from any thread; releases the ports when it is not running. */
    @Override
    public void close() {
        boolean releaseNow;
        synchronized (this) {
            closed = true;
            releaseNow = !running;
        }
        selector.wakeup();
        if (releaseNow) release();
    }

    private synchronized void release() {
        if (released) return;
        released = true;
        closed = true;
        for (Track t : tracks) {
            closeQuietly(t.rtp);
            if (t.rtcp != null) closeQuietly(t.rtcp);
        }
        closeQuietly(selector);
        if (multicastLocked) {
            multicastLocked = false;
            RtpListener.MulticastLock.release();
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {}
    }
}
