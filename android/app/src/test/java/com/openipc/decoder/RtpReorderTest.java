/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RtpReorderTest.java — sequence ordering of RTP packets received over UDP
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RtpReorderTest {
    private static final long MS = 1_000_000L;

    private final List<Integer> out = new ArrayList<>();
    private final RtpReorder.Sink sink = (buf, offset, length) -> {
        assertEquals(14, length);
        out.add(RtpReorder.seq(buf, offset));
    };

    private static byte[] packet(int seq) {
        byte[] p = new byte[14];
        p[0] = (byte) 0x80;
        p[1] = 96;
        p[2] = (byte) (seq >> 8);
        p[3] = (byte) seq;
        return p;
    }

    private void push(RtpReorder r, long nowNs, int... seqs) {
        for (int s : seqs) {
            byte[] p = packet(s);
            r.push(p, 0, p.length, nowNs, sink);
            // The caller's buffer is reused for the next datagram
            Arrays.fill(p, (byte) 0);
        }
    }

    @Test
    public void inOrderPassesStraightThrough() {
        RtpReorder r = new RtpReorder(64, 40);
        push(r, 0, 10, 11, 12);
        assertEquals(Arrays.asList(10, 11, 12), out);
        assertEquals(-1, r.msUntilDeadline(0));
    }

    @Test
    public void swappedPacketsAreReordered() {
        RtpReorder r = new RtpReorder(64, 40);
        push(r, 0, 1, 3, 2, 4);
        assertEquals(Arrays.asList(1, 2, 3, 4), out);
        assertEquals(0, r.skipped());
    }

    @Test
    public void lostPacketIsSkippedAfterTheHoldTime() {
        RtpReorder r = new RtpReorder(64, 40);
        push(r, 0, 1, 3, 4);
        assertEquals(Arrays.asList(1), out);
        assertEquals(40, r.msUntilDeadline(0));
        r.poll(39 * MS, sink);
        assertEquals(Arrays.asList(1), out);
        r.poll(40 * MS, sink);
        assertEquals(Arrays.asList(1, 3, 4), out);
        assertEquals(1, r.skipped());
        // The missing one arriving late is dropped
        push(r, 41 * MS, 2);
        assertEquals(Arrays.asList(1, 3, 4), out);
        assertEquals(1, r.dropped());
    }

    @Test
    public void tooManyHeldSkipsTheGapAtOnce() {
        RtpReorder r = new RtpReorder(3, 1000);
        push(r, 0, 1, 3, 4, 5);
        assertEquals(Arrays.asList(1), out);
        push(r, 0, 6);
        assertEquals(Arrays.asList(1, 3, 4, 5, 6), out);
    }

    @Test
    public void duplicatesAreDropped() {
        RtpReorder r = new RtpReorder(64, 40);
        push(r, 0, 1, 2, 2, 4, 4, 3);
        assertEquals(Arrays.asList(1, 2, 3, 4), out);
        assertEquals(2, r.dropped());
    }

    @Test
    public void sequenceWrapsAround() {
        RtpReorder r = new RtpReorder(64, 40);
        push(r, 0, 65534, 0, 65535, 1);
        assertEquals(Arrays.asList(65534, 65535, 0, 1), out);
    }

    @Test
    public void bigJumpRestartsWithoutWaiting() {
        RtpReorder r = new RtpReorder(64, 40);
        push(r, 0, 100, 102, 5000, 5001);
        assertEquals(Arrays.asList(100, 102, 5000, 5001), out);
    }

    @Test
    public void streamRestartFarBehindIsFollowed() {
        RtpReorder r = new RtpReorder(64, 40);
        push(r, 0, 30000, 30001, 7, 8);
        assertEquals(Arrays.asList(30000, 30001, 7, 8), out);
    }
}
