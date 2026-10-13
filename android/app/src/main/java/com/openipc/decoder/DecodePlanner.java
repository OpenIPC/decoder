/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * DecodePlanner.java — which multi-view cells decode every frame and which only key frames (#85)
 *
 */

package com.openipc.decoder;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    /** Lossy samples within LOSS_WINDOW_MS before a cell is demoted or paused. */
    static final int LOSS_SAMPLES = 3;
    /**
     * Key-frame cells lose a frame only now and then (one key frame per GOP), so lossy samples
     * are counted over a window rather than in a row.
     */
    static final long LOSS_WINDOW_MS = 10_000;
    /** How long a playing cell with an unknown load keeps the lower cells waiting. */
    static final long UNKNOWN_WAIT_MS = 10_000;
    /** Time after a change before the next one, so the decoder load can settle. */
    static final long SETTLE_MS = 3000;
    /** Quiet time after which a load counts as proven and a promotion beyond capacity is tried. */
    static final long PROBE_QUIET_MS = 30_000;
    /** Longest wait between probes: it doubles after every failure. */
    static final long PROBE_MAX_WAIT_MS = 10 * 60_000;
    /** Promotions stay this far below a load that failed: the load estimates are noisy. */
    static final double FAILED_MARGIN = 0.9;
    /** Quiet time after which a load that failed is tried once more: it may have been a fluke. */
    static final long FAILED_RETRY_MS = 60 * 60_000;

    static final class Cell {
        int index;
        boolean full;
        boolean paused;
        /** Resolution and frame rate known, so fullLoad means something. */
        boolean known;
        /** Macroblocks per second when decoding every frame. */
        long fullLoad;
        /** Macroblocks per second it costs on key frames only (counts in the load too). */
        long keyLoad;
        /** The decoder lost frames of this cell in the last sample. */
        boolean lossy;
        /** Connected and decoding: only such cells are demoted or paused (Qodo on #84). */
        boolean playing = true;
        /** On a software decoder beyond the hardware limit: never promoted (#88). */
        boolean pinned;

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
    /** Smallest load at which the decoder lost frames: promotions stay below it. */
    private long failedLoad;
    /** Quiet time before the next probe; grows after failures, resets after a success. */
    private long probeWaitMs = PROBE_QUIET_MS;
    /** Times of the recent lossy samples. */
    private final ArrayDeque<Long> lossyTimes = new ArrayDeque<>();
    /** Since when a playing cell's load is unknown, by cell index. */
    private final Map<Integer, Long> unknownSince = new HashMap<>();
    private long quietSinceMs = -1;
    private long lastChangeMs = -1;

    /**
     * Never learnt below this (#126): losses at a lower load come from a stalled system (a hung
     * media service), not from the decoder's means, and would keep every cell on key frames.
     */
    private final long minCapacity;

    DecodePlanner(long capacity, boolean learnt) {
        this(capacity, learnt, 0, 0);
    }

    DecodePlanner(long capacity, boolean learnt, long failedLoad) {
        this(capacity, learnt, failedLoad, 0);
    }

    /**
     * With the failed load stored with the learnt capacity (0: none), not tried again; stored
     * values below minCapacity are not trusted.
     */
    DecodePlanner(long capacity, boolean learnt, long failedLoad, long minCapacity) {
        this.minCapacity = Math.max(0, minCapacity);
        this.capacity = Math.max(capacity, this.minCapacity);
        this.learnt = learnt;
        this.failedLoad = failedLoad > this.minCapacity ? failedLoad : Long.MAX_VALUE;
    }

    long capacity() { return capacity; }
    boolean isLearnt() { return learnt; }
    /** Smallest load that made the decoder lose frames, or 0 if none. */
    long failedLoad() { return failedLoad == Long.MAX_VALUE ? 0 : failedLoad; }

    /** The decoder's declared budget, once known; ignored when a learnt value exists. */
    void offerDeclared(long declared) {
        if (!learnt && declared > 0) capacity = Math.max(declared, minCapacity);
    }

    /**
     * A second not planned because nothing decoded at all (#126): the time without frames
     * must not count as a quiet, proven period, so the quiet and probe timers start again.
     */
    void skipped(long nowMs) {
        quietSinceMs = nowMs;
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
            // A software cell neither loads the hardware decoder nor shows its limits (#88)
            if (c.paused || c.pinned) continue;
            if (c.full && c.known) load += c.fullLoad;
            else if (!c.full) load += c.keyLoad;
            anyLossy |= c.lossy;
        }
        while (!lossyTimes.isEmpty() && nowMs - lossyTimes.peekFirst() >= LOSS_WINDOW_MS) {
            lossyTimes.pollFirst();
        }
        if (anyLossy) {
            lossyTimes.addLast(nowMs);
            quietSinceMs = nowMs;
        } else if (nowMs - quietSinceMs >= PROBE_QUIET_MS && load > proven) {
            // A load that played without losses long enough is within this device's means
            proven = load;
            probeWaitMs = PROBE_QUIET_MS;
            if (load > capacity) {
                capacity = load;
                learnt = true;
            }
            // A failure at about this load was a fluke: do not hold promotions back by it
            if (load >= failedLoad * FAILED_MARGIN) failedLoad = Long.MAX_VALUE;
        }
        if (lastChangeMs >= 0 && nowMs - lastChangeMs < SETTLE_MS) return Decision.NONE;

        if (lossyTimes.size() >= LOSS_SAMPLES) {
            lossyTimes.clear();
            for (int i = cells.size() - 1; i >= 0; i--) {
                Cell c = cells.get(i);
                if (c.full && !c.paused && c.playing && c.known) {
                    // Losses at this load: the capacity is below it, but not below a proven load.
                    // Remembered for this session even below the floor; only stored values at
                    // or below it are distrusted after a restart (#126)
                    failedLoad = Math.min(failedLoad, load);
                    // A "proven" load at or above a failing one was not proven after all
                    if (proven >= load) proven = Math.round(load * FAILED_MARGIN);
                    probeWaitMs = Math.min(probeWaitMs * 2, PROBE_MAX_WAIT_MS);
                    // Never raised by a failure; not below a proven load
                    capacity = Math.max(minCapacity,
                            Math.max(proven, Math.min(capacity, Math.round(load * 0.9))));
                    learnt = true;
                    return change(nowMs, Action.DEMOTE, c.index);
                }
            }
            // Even key frames are too much: pause the lowest-priority cell as a last resort, but
            // never the last one that plays
            if (countPlaying(cells) < 2) return Decision.NONE;
            for (int i = cells.size() - 1; i >= 0; i--) {
                Cell c = cells.get(i);
                if (!c.paused && c.playing && !c.pinned) {
                    return change(nowMs, Action.PAUSE, c.index);
                }
            }
            return Decision.NONE;
        }
        if (anyLossy) return Decision.NONE;

        // Promote the highest-priority key-frame cell whose load is known
        for (Cell c : cells) {
            if (c.full || c.paused || !c.playing || c.pinned) {
                // Not connected (an offline camera) or paused: nothing to wait for
                unknownSince.remove(c.index);
                continue;
            }
            if (!c.known) {
                // Keep the priority order: wait for its load, but not for ever
                Long since = unknownSince.get(c.index);
                if (since == null) unknownSince.put(c.index, since = nowMs);
                if (nowMs - since < UNKNOWN_WAIT_MS) return Decision.NONE;
                continue;
            }
            unknownSince.remove(c.index);
            long after = load - c.keyLoad + c.fullLoad;
            // Never up to a load that failed, not even within the capacity, unless it has been
            // quiet for very long
            if (after >= failedLoad * FAILED_MARGIN
                    && nowMs - quietSinceMs < FAILED_RETRY_MS) return Decision.NONE;
            boolean fits = after <= capacity;
            // Beyond the capacity only after a quiet period
            boolean probe = nowMs - quietSinceMs >= probeWaitMs;
            if (fits || probe) {
                // Every promotion starts a new quiet period: a load counts as proven only
                // after it has itself played for PROBE_QUIET_MS without losses
                quietSinceMs = nowMs;
                return change(nowMs, Action.PROMOTE, c.index);
            }
            return Decision.NONE;
        }
        return Decision.NONE;
    }

    private static int countPlaying(List<Cell> cells) {
        int n = 0;
        for (Cell c : cells) if (!c.paused && c.playing) n++;
        return n;
    }

    private Decision change(long nowMs, Action action, int index) {
        lastChangeMs = nowMs;
        return new Decision(action, index);
    }
}
