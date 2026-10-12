/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * DecodePlanner.java — which multi-view cells decode every frame and which only key frames (#85)
 *
 */

package com.openipc.decoder;

import java.util.List;

/**
 * Decides, once per second, which cells decode at full rate and which only key frames, so
 * that every camera stays on screen while the hardware decoder keeps up.
 *
 * <p>Load is counted in macroblocks per second (16x16 blocks x frames per second), the unit
 * decoders declare their throughput in. The capacity starts as the decoder's declared budget,
 * or the value learnt on this device before, and is then learnt by measurement: decoder losses
 * at some load lower it, a load that plays without losses for a while raises it.
 *
 * <p>Cells come in priority order, highest first. Pure logic, no Android types.
 */
final class DecodePlanner {
    /** Consecutive lossy samples before a full cell is demoted. */
    static final int LOSS_SAMPLES = 3;
    /** Time after a change before the next one, so the decoder load can settle. */
    static final long SETTLE_MS = 3000;
    /** Quiet time after which a load counts as proven and a promotion beyond capacity is tried. */
    static final long PROBE_QUIET_MS = 30_000;

    static final class Cell {
        int index;
        boolean full;
        boolean paused;
        /** Resolution and frame rate known, so fullLoad means something. */
        boolean known;
        /** Macroblocks per second when decoding every frame. */
        long fullLoad;
        /** The decoder lost frames of this cell in the last sample. */
        boolean lossy;
        /** Connected and decoding: only such cells are demoted or paused (Qodo on #84). */
        boolean playing = true;

        Cell(int index, boolean full, boolean known, long fullLoad, boolean lossy) {
            this.index = index;
            this.full = full;
            this.known = known;
            this.fullLoad = fullLoad;
            this.lossy = lossy;
        }
    }

    enum Action { NONE, PROMOTE, DEMOTE, PAUSE }

    static final class Decision {
        final Action action;
        final int index;

        Decision(Action action, int index) {
            this.action = action;
            this.index = index;
        }

        static final Decision NONE = new Decision(Action.NONE, -1);
    }

    private long capacity;
    private boolean learnt;
    /** Largest full-rate load that played without losses for PROBE_QUIET_MS. */
    private long proven;
    /** Smallest load at which the decoder lost frames: probes stay below it. */
    private long failedLoad = Long.MAX_VALUE;
    private int lossySamples;
    private long quietSinceMs = -1;
    private long lastChangeMs = -1;

    DecodePlanner(long capacity, boolean learnt) {
        this.capacity = capacity;
        this.learnt = learnt;
    }

    long capacity() { return capacity; }
    boolean isLearnt() { return learnt; }

    /** The decoder's declared budget, once known; ignored when a learnt value exists. */
    void offerDeclared(long declared) {
        if (!learnt && declared > 0) capacity = declared;
    }

    static long fullLoad(int width, int height, float fps) {
        long blocks = (long) ((width + 15) / 16) * ((height + 15) / 16);
        return Math.round(blocks * (double) fps);
    }

    Decision step(List<Cell> cells, long nowMs) {
        if (quietSinceMs < 0) quietSinceMs = nowMs;
        long load = 0;
        boolean anyLossy = false;
        for (Cell c : cells) {
            if (c.paused) continue;
            if (c.full && c.known) load += c.fullLoad;
            anyLossy |= c.lossy;
        }
        if (anyLossy) {
            lossySamples++;
            quietSinceMs = nowMs;
        } else {
            lossySamples = 0;
            // A load that played without losses long enough is within this device's means
            if (nowMs - quietSinceMs >= PROBE_QUIET_MS && load > proven) {
                proven = load;
                if (load > capacity) {
                    capacity = load;
                    learnt = true;
                }
            }
        }
        if (lastChangeMs >= 0 && nowMs - lastChangeMs < SETTLE_MS) return Decision.NONE;

        if (lossySamples >= LOSS_SAMPLES) {
            lossySamples = 0;
            for (int i = cells.size() - 1; i >= 0; i--) {
                Cell c = cells.get(i);
                if (c.full && !c.paused && c.playing && c.known) {
                    // Losses at this load: the capacity is below it, but not below a proven load
                    failedLoad = Math.min(failedLoad, load);
                    capacity = Math.max(proven, Math.round(load * 0.9));
                    learnt = true;
                    return change(nowMs, Action.DEMOTE, c.index);
                }
            }
            // Even key frames are too much: pause the lowest-priority cell as a last resort
            for (int i = cells.size() - 1; i >= 0; i--) {
                Cell c = cells.get(i);
                if (!c.paused && c.playing && countActive(cells) > 1) {
                    return change(nowMs, Action.PAUSE, c.index);
                }
            }
            return Decision.NONE;
        }
        if (anyLossy) return Decision.NONE;

        // Promote the highest-priority key-frame cell whose load is known
        for (Cell c : cells) {
            if (c.full || c.paused) continue;
            if (!c.known) return Decision.NONE; // keep the priority order: wait for it
            boolean fits = load + c.fullLoad <= capacity;
            // Beyond the capacity only after a quiet period, and never up to a load that failed
            boolean probe = nowMs - quietSinceMs >= PROBE_QUIET_MS
                    && load + c.fullLoad < failedLoad;
            if (fits || probe) {
                if (!fits) quietSinceMs = nowMs; // a probe starts a new quiet period
                return change(nowMs, Action.PROMOTE, c.index);
            }
            return Decision.NONE;
        }
        return Decision.NONE;
    }

    private static int countActive(List<Cell> cells) {
        int n = 0;
        for (Cell c : cells) if (!c.paused) n++;
        return n;
    }

    private Decision change(long nowMs, Action action, int index) {
        lastChangeMs = nowMs;
        return new Decision(action, index);
    }
}
