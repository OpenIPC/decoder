/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * GlMirrorTest.java — where the mirrored and turned picture lands (#226)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class GlMirrorTest {
    private static final float E = 1e-6f;

    /** The picture's right edge centre (1, 0) and top edge centre (0, 1), as shown. */
    private static void lands(int turn, boolean mirror, float[] right, float[] top) {
        float[] m = GlMirror.transform(turn, mirror);
        assertArrayEquals(right, GlMirror.map(m, 1f, 0f), E);
        assertArrayEquals(top, GlMirror.map(m, 0f, 1f), E);
    }

    @Test
    public void mirrorSwapsLeftAndRight() {
        lands(0, false, new float[] {1f, 0f}, new float[] {0f, 1f});
        lands(0, true, new float[] {-1f, 0f}, new float[] {0f, 1f});
    }

    @Test
    public void turnIsCounterClockwise() {
        // 90° ↺: the right edge goes up, the top goes left
        lands(90, false, new float[] {0f, 1f}, new float[] {-1f, 0f});
        lands(180, false, new float[] {-1f, 0f}, new float[] {0f, -1f});
        lands(270, false, new float[] {0f, -1f}, new float[] {1f, 0f});
        lands(-90, false, new float[] {0f, -1f}, new float[] {1f, 0f});
    }

    @Test
    public void mirrorComesAfterTheTurn() {
        // Turned first, then flipped on the screen: the top stays on the screen's top half
        // for 0° and 180°, and goes right instead of left for 90°
        lands(90, true, new float[] {0f, 1f}, new float[] {1f, 0f});
        lands(180, true, new float[] {1f, 0f}, new float[] {0f, -1f});
        lands(270, true, new float[] {0f, -1f}, new float[] {-1f, 0f});
    }
}
