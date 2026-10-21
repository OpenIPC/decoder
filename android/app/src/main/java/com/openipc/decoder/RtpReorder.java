/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtpReorder.java — puts RTP packets received over UDP back into sequence order
 *
 */

package com.openipc.decoder;

/**
 * UDP may reorder datagrams; the depacketizer expects them in sequence order and treats a
 * gap as loss (#67). In-order packets pass straight through, so a clean network adds no
 * delay. A packet that arrives early is held until the missing ones arrive, the hold time
 * runs out, or too many are held; then the gap is skipped. Late duplicates are dropped.
 * Single-threaded: called from the network thread only.
 */
class RtpReorder {
    interface Sink {
        void deliver(byte[] buf, int offset, int length);
    }

    /** Ring size; a jump of this many packets or more is not waited for. */
    private static final int SLOTS = 128;
    /** A sequence number this far behind is a restarted stream, not a late packet. */
    private static final int RESTART_BEHIND = 1000;

    private final int maxHeld;
    private final long maxHoldNs;

    private final byte[][] data = new byte[SLOTS][];
    private final int[] length = new int[SLOTS];
    private final long[] heldAtNs = new long[SLOTS];
    private final boolean[] present = new boolean[SLOTS];
    private int held;
    private int expected = -1;
    private long dropped;
    private long skipped;

    RtpReorder(int maxHeld, long maxHoldMs) {
        this.maxHeld = Math.min(maxHeld, SLOTS - 1);
        this.maxHoldNs = maxHoldMs * 1_000_000L;
    }

    void reset() {
        for (int i = 0; i < SLOTS; i++) present[i] = false;
        held = 0;
        expected = -1;
    }

    /** Late or duplicate packets dropped so far. */
    long dropped() { return dropped; }
    /** Missing packets given up on so far. */
    long skipped() { return skipped; }
    int held() { return held; }

    static int seq(byte[] buf, int offset) {
        return ((buf[offset + 2] & 0xFF) << 8) | (buf[offset + 3] & 0xFF);
    }

    void push(byte[] buf, int offset, int len, long nowNs, Sink sink) {
        if (len < 12) return;
        int seq = seq(buf, offset);
        if (expected < 0) {
            expected = seq;
        }
        int d = (short) (seq - expected); // signed distance, wraps at 16 bits
        if (d == 0) {
            sink.deliver(buf, offset, len);
            expected = (seq + 1) & 0xFFFF;
            drain(sink);
        } else if (d < 0) {
            if (d > -RESTART_BEHIND) {
                dropped++;
                return;
            }
            restart(seq, buf, offset, len, sink);
        } else if (d >= SLOTS) {
            // Too far ahead to wait for: hand over what is held, then continue from here
            flushAll(sink);
            // The rest of the jump is lost too, unless it is so far that the stream restarted
            int gap = (short) (seq - expected);
            if (gap > 0 && gap < RESTART_BEHIND) skipped += gap;
            restart(seq, buf, offset, len, sink);
        } else {
            int i = seq & (SLOTS - 1);
            if (present[i]) {
                dropped++;
                return;
            }
            if (data[i] == null || data[i].length < len) data[i] = new byte[Math.max(len, 2048)];
            System.arraycopy(buf, offset, data[i], 0, len);
            length[i] = len;
            heldAtNs[i] = nowNs;
            present[i] = true;
            held++;
            if (held > maxHeld) skipGap(sink);
        }
    }

    /** Give up on a gap whose oldest held packet has waited too long. */
    void poll(long nowNs, Sink sink) {
        while (held > 0 && nowNs - oldestHeldNs() >= maxHoldNs) skipGap(sink);
    }

    /** Time until {@link #poll} has something to do, in ms; -1 when nothing is held. */
    long msUntilDeadline(long nowNs) {
        if (held == 0) return -1;
        long ns = oldestHeldNs() + maxHoldNs - nowNs;
        return ns <= 0 ? 0 : Math.max(1, ns / 1_000_000);
    }

    private long oldestHeldNs() {
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < SLOTS; i++) {
            if (present[i] && heldAtNs[i] < oldest) oldest = heldAtNs[i];
        }
        return oldest;
    }

    private void restart(int seq, byte[] buf, int offset, int len, Sink sink) {
        reset();
        sink.deliver(buf, offset, len);
        expected = (seq + 1) & 0xFFFF;
    }

    /** Deliver the held packets that now follow on. */
    private void drain(Sink sink) {
        while (held > 0) {
            int i = expected & (SLOTS - 1);
            if (!present[i]) return;
            present[i] = false;
            held--;
            sink.deliver(data[i], 0, length[i]);
            expected = (expected + 1) & 0xFFFF;
        }
    }

    /** Skip to the first held packet. */
    private void skipGap(Sink sink) {
        for (int k = 1; k < SLOTS; k++) {
            if (present[(expected + k) & (SLOTS - 1)]) {
                skipped += k;
                expected = (expected + k) & 0xFFFF;
                drain(sink);
                return;
            }
        }
    }

    private void flushAll(Sink sink) {
        while (held > 0) skipGap(sink);
    }
}
