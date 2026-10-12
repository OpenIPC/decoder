/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * UdpReceiver.java — RTP over UDP: local port pairs and the receive loop
 *
 */

package com.openipc.decoder;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

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

    /** Hold time of the reorder buffer; only spent when a packet is missing. */
    static final int REORDER_MS = 40;
    private static final int REORDER_PACKETS = 64;
    private static final int VIDEO_RCVBUF = 2 * 1024 * 1024;
    private static final int AUDIO_RCVBUF = 256 * 1024;
    private static final int BIND_ATTEMPTS = 32;

    private static final class Track {
        final int channel;
        final DatagramChannel rtp;
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
                rtp.bind(new InetSocketAddress(0));
                int port = ((InetSocketAddress) rtp.getLocalAddress()).getPort();
                if ((port & 1) != 0 || port >= 65535) {
                    rtp.close();
                    continue;
                }
                rtcp = DatagramChannel.open();
                rtcp.bind(new InetSocketAddress(port + 1));
                int size = channel == RtspSession.CHANNEL_VIDEO ? VIDEO_RCVBUF : AUDIO_RCVBUF;
                rtp.setOption(StandardSocketOptions.SO_RCVBUF, size);
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
    void run(BooleanSupplier keepGoing, Handler handler) throws IOException {
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
            while (!closed && keepGoing.getAsBoolean()) {
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
            closeQuietly(t.rtcp);
        }
        closeQuietly(selector);
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {}
    }
}
