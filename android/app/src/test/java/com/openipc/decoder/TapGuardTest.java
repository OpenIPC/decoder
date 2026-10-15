/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * TapGuardTest.java — a swipe from the screen edge or a drag is no tap (#213)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TapGuardTest {
    private static final int W = 1600, H = 720;
    private static final int[] ZONE = {28, 47, 28, 47};

    @Test
    public void edgeZoneOnEachSide() {
        assertTrue(TapGuard.startsInEdgeZone(800, 0, W, H, ZONE));
        assertTrue(TapGuard.startsInEdgeZone(800, 46, W, H, ZONE));
        assertFalse(TapGuard.startsInEdgeZone(800, 47, W, H, ZONE));
        assertTrue(TapGuard.startsInEdgeZone(800, 719, W, H, ZONE));
        assertTrue(TapGuard.startsInEdgeZone(800, H - 47, W, H, ZONE));
        assertFalse(TapGuard.startsInEdgeZone(800, H - 48, W, H, ZONE));
        assertTrue(TapGuard.startsInEdgeZone(0, 360, W, H, ZONE));
        assertTrue(TapGuard.startsInEdgeZone(1599, 360, W, H, ZONE));
        assertFalse(TapGuard.startsInEdgeZone(800, 360, W, H, ZONE));
    }

    @Test
    public void zoneIsTheLargerOfGestureInsetsAndFixedBand() {
        // 280 dpi: density 1.75; 16 dp = 28 px, 24 dp = 42 px
        assertArrayEquals(new int[] {28, 42, 28, 42}, TapGuard.edgeZone(null, 1.75f));
        assertArrayEquals(new int[] {28, 42, 28, 42}, TapGuard.edgeZone(new int[4], 1.75f));
        // Gesture navigation: wide back zones left and right, a taller one at the bottom
        assertArrayEquals(new int[] {53, 42, 53, 84},
                TapGuard.edgeZone(new int[] {53, 0, 53, 84}, 1.75f));
    }

    @Test
    public void tapInTheMiddleOpensTheMenu() {
        TapGuard g = new TapGuard(14);
        g.down(800, 400, W, H, ZONE);
        g.moveTo(805, 403);
        g.moveTo(805, 403);
        assertTrue(g.wasTap());
    }

    @Test
    public void swipeFromTheTopEdgeIsNoTap() {
        TapGuard g = new TapGuard(14);
        g.down(800, 0, W, H, ZONE);
        g.moveTo(800, 2);
        assertFalse(g.wasTap());
    }

    @Test
    public void upFarAwayWithoutMovesIsNoTap() {
        // GestureDetector takes this as a tap: the system kept the moves of its swipe
        TapGuard g = new TapGuard(14);
        g.down(800, 100, W, H, ZONE);
        g.moveTo(800, 400);
        assertFalse(g.wasTap());
    }

    @Test
    public void dragBackWithinSlopIsStillNoTap() {
        TapGuard g = new TapGuard(14);
        g.down(800, 400, W, H, ZONE);
        g.moveTo(900, 400);
        g.moveTo(800, 400);
        assertFalse(g.wasTap());
    }

    @Test
    public void pinchAndCancelAreNoTap() {
        TapGuard g = new TapGuard(14);
        g.down(800, 400, W, H, ZONE);
        g.secondFinger();
        assertFalse(g.wasTap());
        g.down(800, 400, W, H, ZONE);
        g.cancel();
        assertFalse(g.wasTap());
    }

    @Test
    public void newSequenceStartsAfresh() {
        TapGuard g = new TapGuard(14);
        g.down(800, 0, W, H, ZONE);
        assertFalse(g.wasTap());
        g.down(800, 400, W, H, ZONE);
        assertTrue(g.wasTap());
    }

    @Test
    public void nothingTouchedIsNoTap() {
        assertFalse(new TapGuard(14).wasTap());
    }
}
