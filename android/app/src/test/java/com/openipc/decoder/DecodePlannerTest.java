/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * DecodePlannerTest.java — full rate or key frames per multi-view cell (#85)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DecodePlannerTest {
    /** 1080p at 30 fps: 120 x 68 blocks x 30. */
    private static final long P1080_30 = DecodePlanner.fullLoad(1920, 1080, 30);
    /** The AG-216's declared budget: 1080p at 60 fps. */
    private static final long BUDGET = DecodePlanner.fullLoad(1920, 1080, 60);

    private static List<DecodePlanner.Cell> keyCells(int n, long load) {
        List<DecodePlanner.Cell> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) cells.add(new DecodePlanner.Cell(i, false, true, load, false));
        return cells;
    }

    private static void apply(List<DecodePlanner.Cell> cells, DecodePlanner.Decision d) {
        for (DecodePlanner.Cell c : cells) {
            if (c.index != d.index) continue;
            if (d.action == DecodePlanner.Action.PROMOTE) c.full = true;
            if (d.action == DecodePlanner.Action.DEMOTE) c.full = false;
            if (d.action == DecodePlanner.Action.PAUSE) c.paused = true;
            if (d.action == DecodePlanner.Action.RESUME) c.paused = false;
        }
    }

    private static int full(List<DecodePlanner.Cell> cells) {
        int n = 0;
        for (DecodePlanner.Cell c : cells) if (c.full) n++;
        return n;
    }

    @Test
    public void loadIsBlocksTimesFps() {
        assertEquals(120L * 68 * 30, P1080_30);
    }

    @Test
    public void promotesInPriorityOrderUpToTheBudget() {
        DecodePlanner p = new DecodePlanner(BUDGET, false);
        List<DecodePlanner.Cell> cells = keyCells(8, P1080_30);
        long t = 0;
        for (int i = 0; i < 20; i++, t += 1000) apply(cells, p.step(cells, t));
        assertEquals("two 1080p30 cameras fit 1080p60", 2, full(cells));
        assertTrue(cells.get(0).full && cells.get(1).full);
    }

    @Test
    public void lossesDemoteTheLowestPriorityFullCellAndLowerTheCapacity() {
        DecodePlanner p = new DecodePlanner(BUDGET * 2, false);
        List<DecodePlanner.Cell> cells = keyCells(4, P1080_30);
        long t = 0;
        for (int i = 0; i < 20; i++, t += 1000) apply(cells, p.step(cells, t));
        assertEquals(4, full(cells));
        // The decoder starts losing frames
        cells.get(0).lossy = true;
        DecodePlanner.Decision d = DecodePlanner.Decision.NONE;
        for (int i = 0; i < DecodePlanner.LOSS_SAMPLES; i++, t += 1000) d = p.step(cells, t);
        assertEquals(DecodePlanner.Action.DEMOTE, d.action);
        assertEquals(3, d.index);
        assertTrue(p.isLearnt());
        assertEquals(Math.round(4 * P1080_30 * 0.9), p.capacity());
    }

    @Test
    public void keyFrameLossesWithNothingToDemotePauseAsLastResort() {
        DecodePlanner p = new DecodePlanner(1, false); // nothing fits: every cell stays on key frames
        List<DecodePlanner.Cell> cells = keyCells(3, P1080_30);
        for (DecodePlanner.Cell c : cells) c.lossy = true;
        DecodePlanner.Decision d = DecodePlanner.Decision.NONE;
        long t = 0;
        for (int i = 0; i < DecodePlanner.LOSS_SAMPLES; i++, t += 1000) d = p.step(cells, t);
        assertEquals(DecodePlanner.Action.PAUSE, d.action);
        assertEquals(2, d.index);
    }

    @Test
    public void lossesDuringTheWarmupDecideNothing() {
        DecodePlanner p = new DecodePlanner(1, false);
        p.setWarmup(DecodePlanner.WARMUP_MS);
        List<DecodePlanner.Cell> cells = keyCells(3, P1080_30);
        // Every decoder starting at once loses frames (#148)
        for (DecodePlanner.Cell c : cells) c.lossy = true;
        long t = 0;
        for (; t < DecodePlanner.WARMUP_MS; t += 1000) {
            assertEquals(DecodePlanner.Action.NONE, p.step(cells, t).action);
        }
        // The same losses after the warm-up still count
        DecodePlanner.Decision d = DecodePlanner.Decision.NONE;
        for (int i = 0; i < DecodePlanner.LOSS_SAMPLES; i++, t += 1000) d = p.step(cells, t);
        assertEquals(DecodePlanner.Action.PAUSE, d.action);
    }

    @Test
    public void aFittingCellIsPromotedDuringTheWarmup() {
        DecodePlanner p = new DecodePlanner(BUDGET, false);
        p.setWarmup(DecodePlanner.WARMUP_MS);
        List<DecodePlanner.Cell> cells = keyCells(4, P1080_30);
        // Losses of the start do not hold back what fits (Qodo on #149)
        for (DecodePlanner.Cell c : cells) c.lossy = true;
        assertEquals(DecodePlanner.Action.PROMOTE, p.step(cells, 0).action);
    }

    @Test
    public void aPausedCellPlaysAgainAfterAQuietPeriod() {
        DecodePlanner p = new DecodePlanner(1, false);
        List<DecodePlanner.Cell> cells = keyCells(3, P1080_30);
        for (DecodePlanner.Cell c : cells) c.lossy = true;
        long t = 0;
        DecodePlanner.Decision d = DecodePlanner.Decision.NONE;
        for (int i = 0; i < DecodePlanner.LOSS_SAMPLES; i++, t += 1000) d = p.step(cells, t);
        apply(cells, d);
        assertTrue(cells.get(2).paused);
        cells.get(2).playing = false;
        for (DecodePlanner.Cell c : cells) c.lossy = false;
        // Quiet: after the (doubled) wait the paused cell gets another try (#148)
        d = DecodePlanner.Decision.NONE;
        long pausedAt = t;
        for (; d.action != DecodePlanner.Action.RESUME && t < pausedAt + 10 * 60_000; t += 1000) {
            d = p.step(cells, t);
        }
        assertEquals(DecodePlanner.Action.RESUME, d.action);
        assertEquals(2, d.index);
        assertTrue(t - pausedAt >= DecodePlanner.PROBE_QUIET_MS);
    }

    @Test
    public void aQuietLoadIsProvenAndAProbeMayExceedTheCapacity() {
        DecodePlanner p = new DecodePlanner(P1080_30, false); // one camera fits
        List<DecodePlanner.Cell> cells = keyCells(2, P1080_30);
        long t = 0;
        apply(cells, p.step(cells, t));
        assertEquals(1, full(cells));
        DecodePlanner.Decision d = DecodePlanner.Decision.NONE;
        for (t = 1000; t <= DecodePlanner.PROBE_QUIET_MS + 1000 && d.action == DecodePlanner.Action.NONE; t += 1000) {
            d = p.step(cells, t);
        }
        assertEquals("after a quiet period the second camera is tried", DecodePlanner.Action.PROMOTE, d.action);
        apply(cells, d);
        // It plays without losses for another quiet period: the capacity is raised to that load
        for (long end = t + DecodePlanner.PROBE_QUIET_MS + 1000; t <= end; t += 1000) p.step(cells, t);
        assertEquals(2 * P1080_30, p.capacity());
        assertTrue(p.isLearnt());
    }

    @Test
    public void aLoadThatFailedIsNotProbedAgain() {
        DecodePlanner p = new DecodePlanner(P1080_30, false);
        List<DecodePlanner.Cell> cells = keyCells(2, P1080_30);
        long t = 0;
        apply(cells, p.step(cells, t));
        DecodePlanner.Decision d = DecodePlanner.Decision.NONE;
        for (t = 1000; d.action == DecodePlanner.Action.NONE && t < 40_000; t += 1000) d = p.step(cells, t);
        apply(cells, d);
        assertEquals(2, full(cells));
        // The probe makes the decoder lose frames: the second camera goes back to key frames
        cells.get(0).lossy = true;
        d = DecodePlanner.Decision.NONE;
        for (; d.action != DecodePlanner.Action.DEMOTE && t < 60_000; t += 1000) {
            d = p.step(cells, t);
            apply(cells, d);
        }
        assertEquals(1, d.index);
        assertEquals(1, full(cells));
        // Demoting it was enough: the losses stop
        cells.get(0).lossy = false;
        // A long quiet period later the same load is not tried again
        for (long end = t + 5 * DecodePlanner.PROBE_QUIET_MS; t < end; t += 1000) {
            assertEquals(DecodePlanner.Action.NONE, p.step(cells, t).action);
        }
    }

    @Test
    public void keyFrameCellsCountInTheLoad() {
        DecodePlanner p = new DecodePlanner(BUDGET, false);
        List<DecodePlanner.Cell> cells = keyCells(3, P1080_30);
        // Each key-frame cell costs a quarter of a 1080p30 stream: two full ones no longer fit
        for (DecodePlanner.Cell c : cells) c.keyLoad = P1080_30 / 4;
        long t = 0;
        for (int i = 0; i < 10; i++, t += 1000) apply(cells, p.step(cells, t));
        assertEquals(1, full(cells));
    }

    @Test
    public void aFailureNeverRaisesTheCapacity() {
        DecodePlanner p = new DecodePlanner(P1080_30, false);
        List<DecodePlanner.Cell> cells = keyCells(1, P1080_30);
        long t = 0;
        apply(cells, p.step(cells, t));
        // Losses at a load estimated higher than the capacity: capacity stays
        cells.get(0).fullLoad = 3 * P1080_30;
        cells.get(0).lossy = true;
        for (int i = 0; i < 6; i++, t += 1000) apply(cells, p.step(cells, t));
        assertTrue(p.capacity() <= P1080_30);
    }

    @Test
    public void aSlightlyLowerEstimateOfAFailedLoadIsNotProbed() {
        DecodePlanner p = new DecodePlanner(P1080_30, false);
        List<DecodePlanner.Cell> cells = keyCells(2, P1080_30);
        long t = 0;
        apply(cells, p.step(cells, t));
        DecodePlanner.Decision d = DecodePlanner.Decision.NONE;
        for (t = 1000; d.action == DecodePlanner.Action.NONE && t < 40_000; t += 1000) d = p.step(cells, t);
        apply(cells, d);
        cells.get(0).lossy = true;
        for (d = DecodePlanner.Decision.NONE; d.action != DecodePlanner.Action.DEMOTE; t += 1000) {
            d = p.step(cells, t);
            apply(cells, d);
        }
        cells.get(0).lossy = false;
        // The estimate of the same configuration comes out 5 % lower: still not tried
        cells.get(1).fullLoad = Math.round(P1080_30 * 0.95);
        for (long end = t + 5 * DecodePlanner.PROBE_QUIET_MS; t < end; t += 1000) {
            assertEquals(DecodePlanner.Action.NONE, p.step(cells, t).action);
        }
    }

    @Test
    public void waitsForTheLoadOfAHigherPriorityCell() {
        DecodePlanner p = new DecodePlanner(BUDGET, false);
        List<DecodePlanner.Cell> cells = keyCells(2, P1080_30);
        cells.get(0).known = false;
        assertEquals(DecodePlanner.Action.NONE, p.step(cells, 0).action);
    }

    @Test
    public void declaredBudgetIsIgnoredOnceLearnt() {
        DecodePlanner p = new DecodePlanner(1000, true);
        p.offerDeclared(5000);
        assertEquals(1000, p.capacity());
        DecodePlanner q = new DecodePlanner(1000, false);
        q.offerDeclared(5000);
        assertEquals(5000, q.capacity());
    }

    @Test
    public void anOfflineCameraDoesNotHoldTheLowerCellsBack() {
        DecodePlanner p = new DecodePlanner(BUDGET, false);
        List<DecodePlanner.Cell> cells = keyCells(3, P1080_30);
        cells.get(0).playing = false;
        cells.get(0).known = false;
        long t = 0;
        for (int i = 0; i < 10; i++, t += 1000) apply(cells, p.step(cells, t));
        assertTrue(cells.get(1).full && cells.get(2).full);
    }

    @Test
    public void aPlayingCellWithAnUnknownLoadIsWaitedForOnlyAWhile() {
        DecodePlanner p = new DecodePlanner(BUDGET, false);
        List<DecodePlanner.Cell> cells = keyCells(2, P1080_30);
        cells.get(0).known = false;
        long t = 0;
        for (; t < DecodePlanner.UNKNOWN_WAIT_MS; t += 1000) {
            assertEquals(DecodePlanner.Action.NONE, p.step(cells, t).action);
        }
        DecodePlanner.Decision d = p.step(cells, t);
        assertEquals(DecodePlanner.Action.PROMOTE, d.action);
        assertEquals(1, d.index);
    }

    @Test
    public void aPromotionWithinTheCapacityStaysBelowAFailedLoad() {
        // Learnt capacity of three cameras, but two already failed (stored with it)
        DecodePlanner p = new DecodePlanner(3 * P1080_30, true, 2 * P1080_30);
        List<DecodePlanner.Cell> cells = keyCells(3, P1080_30);
        long t = 0;
        for (int i = 0; i < 20; i++, t += 1000) apply(cells, p.step(cells, t));
        assertEquals(1, full(cells));
        assertEquals(2 * P1080_30, p.failedLoad());
    }

    @Test
    public void aDemotionStoresTheFailedLoad() {
        DecodePlanner p = new DecodePlanner(BUDGET, false);
        List<DecodePlanner.Cell> cells = keyCells(2, P1080_30);
        long t = 0;
        for (int i = 0; i < 10; i++, t += 1000) apply(cells, p.step(cells, t));
        assertEquals(0, p.failedLoad());
        cells.get(0).lossy = true;
        for (int i = 0; i < DecodePlanner.LOSS_SAMPLES; i++, t += 1000) apply(cells, p.step(cells, t));
        assertEquals(2 * P1080_30, p.failedLoad());
        assertEquals(1, full(cells));
    }

    @Test
    public void theLastPlayingCellIsNeverPaused() {
        DecodePlanner p = new DecodePlanner(1, false);
        List<DecodePlanner.Cell> cells = keyCells(2, P1080_30);
        cells.get(1).playing = false; // offline
        cells.get(0).lossy = true;
        long t = 0;
        for (int i = 0; i < 10; i++, t += 1000) {
            assertEquals(DecodePlanner.Action.NONE, p.step(cells, t).action);
        }
    }

    @Test
    public void sparseLossesOfKeyFrameCellsAreDetected() {
        DecodePlanner p = new DecodePlanner(1, false);
        List<DecodePlanner.Cell> cells = keyCells(2, P1080_30);
        DecodePlanner.Decision d = DecodePlanner.Decision.NONE;
        // One lost key frame every three seconds: never two lossy samples in a row
        for (long t = 0; t < DecodePlanner.LOSS_WINDOW_MS && d.action == DecodePlanner.Action.NONE;
                t += 1000) {
            cells.get(0).lossy = t % 3000 == 0;
            d = p.step(cells, t);
        }
        assertEquals(DecodePlanner.Action.PAUSE, d.action);
        assertEquals(1, d.index);
    }

    @Test
    public void aLoadJustReachedIsNotProvenAndTheCellIsNotPromotedAgain() {
        // Qodo on #86: a fitting promotion after a long quiet period, then losses at that load
        DecodePlanner p = new DecodePlanner(2 * P1080_30, false);
        List<DecodePlanner.Cell> cells = keyCells(2, P1080_30);
        long t = 0;
        apply(cells, p.step(cells, t));
        for (t = 1000; t <= DecodePlanner.PROBE_QUIET_MS + 1000; t += 1000) apply(cells, p.step(cells, t));
        assertEquals(2, full(cells));
        // The second camera makes the decoder lose frames
        cells.get(0).lossy = true;
        DecodePlanner.Decision d = DecodePlanner.Decision.NONE;
        for (; d.action != DecodePlanner.Action.DEMOTE; t += 1000) {
            d = p.step(cells, t);
            apply(cells, d);
        }
        cells.get(0).lossy = false;
        assertTrue("capacity below the failing load", p.capacity() < 2 * P1080_30);
        for (long end = t + 3 * DecodePlanner.PROBE_QUIET_MS; t < end; t += 1000) {
            assertEquals(DecodePlanner.Action.NONE, p.step(cells, t).action);
        }
    }

    @Test
    public void storedValuesBelowTheFloorAreNotTrusted() {
        // Learnt during a stalled media service (#126): below one 1080p30 stream
        DecodePlanner p = new DecodePlanner(P1080_30 / 2, true, P1080_30 / 2, P1080_30);
        assertEquals(P1080_30, p.capacity());
        assertEquals(0, p.failedLoad());
    }

    @Test
    public void lossesBelowTheFloorCountOnlyForThisSession() {
        DecodePlanner p = new DecodePlanner(BUDGET, false, 0, P1080_30);
        List<DecodePlanner.Cell> cells = keyCells(1, P1080_30 / 2);
        long t = 0;
        apply(cells, p.step(cells, t));
        assertEquals(1, full(cells));
        cells.get(0).lossy = true;
        DecodePlanner.Decision d = DecodePlanner.Decision.NONE;
        for (int i = 0; i < 6 && d.action != DecodePlanner.Action.DEMOTE; i++, t += 1000) {
            d = p.step(cells, t);
        }
        assertEquals(DecodePlanner.Action.DEMOTE, d.action);
        // Remembered for this session, so the cell is not promoted again at once
        assertEquals(P1080_30 / 2, p.failedLoad());
        assertTrue(p.capacity() >= P1080_30);
        // After a restart the stored value at or below the floor is not trusted
        assertEquals(0, new DecodePlanner(p.capacity(), true, p.failedLoad(), P1080_30).failedLoad());
    }

    @Test
    public void aDeclaredBudgetBelowTheFloorIsRaised() {
        DecodePlanner p = new DecodePlanner(BUDGET, false, 0, P1080_30);
        p.offerDeclared(P1080_30 / 3);
        assertEquals(P1080_30, p.capacity());
    }

    @Test
    public void aCellOnASoftwareDecoderIsNeverPromoted() {
        DecodePlanner p = new DecodePlanner(BUDGET, false);
        List<DecodePlanner.Cell> cells = keyCells(2, P1080_30);
        cells.get(0).pinned = true; // over the hardware limit (#88)
        long t = 0;
        for (int i = 0; i < 10; i++, t += 1000) apply(cells, p.step(cells, t));
        assertTrue(!cells.get(0).full);
        assertTrue("the next cell is not held back", cells.get(1).full);
    }

    @Test
    public void aSoftwareCellNeitherLoadsNorPausesTheHardware() {
        DecodePlanner p = new DecodePlanner(P1080_30, false, 0, P1080_30);
        List<DecodePlanner.Cell> cells = keyCells(2, P1080_30);
        DecodePlanner.Cell sw = cells.get(0);
        sw.pinned = true;
        sw.keyLoad = P1080_30 / 2;
        sw.lossy = true; // the software decoder drops frames: not a hardware overload
        long t = 0;
        for (int i = 0; i < 20; i++, t += 1000) apply(cells, p.step(cells, t));
        assertTrue("the hardware cell fits without the software load", cells.get(1).full);
        assertTrue(!cells.get(0).paused && !cells.get(1).paused);
        assertEquals(P1080_30, p.capacity());
    }
}
