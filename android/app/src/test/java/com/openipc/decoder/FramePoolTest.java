/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * FramePoolTest.java — size classes and reuse in FramePool
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FramePoolTest {
    @Test
    public void capacityIsRoundedUpForReuse() {
        assertEquals(2048, FramePool.capacityFor(100));
        assertEquals(2048, FramePool.capacityFor(1400));
        assertEquals(4096, FramePool.capacityFor(2049));
        assertEquals(FramePool.SMALL_MAX, FramePool.capacityFor(65536 + 4));
        assertEquals(256 * 1024, FramePool.capacityFor(70_000));
        assertEquals(512 * 1024, FramePool.capacityFor(300_000));
    }

    @Test
    public void smallFramesAreReused() {
        FramePool pool = new FramePool(4);
        Frame a = pool.obtain(1400);
        pool.recycle(a);
        Frame b = pool.obtain(1200);
        assertSame("same capacity class is reused", a, b);
        assertEquals(0, b.length());
    }

    @Test
    public void smallRequestsDoNotTakeLargeBuffers() {
        FramePool pool = new FramePool(8);
        Frame big = pool.obtain(500_000);
        pool.recycle(big);
        Frame small = pool.obtain(1400);
        assertNotSame(big, small);
        assertTrue(small.data().length <= FramePool.SMALL_MAX);
        // ...and the large buffer is still there for the next key frame
        assertSame(big, pool.obtain(400_000));
    }

    @Test
    public void largeClassIsBounded() {
        FramePool pool = new FramePool(16); // large class holds 2
        Frame[] f = {pool.obtain(300_000), pool.obtain(300_000), pool.obtain(300_000)};
        for (Frame x : f) pool.recycle(x);
        assertSame(f[0], pool.obtain(300_000));
        assertSame(f[1], pool.obtain(300_000));
        assertNotSame(f[2], pool.obtain(300_000)); // the third was dropped, not retained
    }
}
