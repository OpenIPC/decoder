/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * SnapshotsTest.java — where a frame goes in its snapshot cell (#248)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class SnapshotsTest {
    @Test
    public void fitKeepsTheCellForNoOrTheSameRatio() {
        assertArrayEquals(new int[] {0, 0, 640, 360}, Snapshots.fit(0, 0, 640, 360, 0f));
        assertArrayEquals(new int[] {0, 0, 640, 360}, Snapshots.fit(0, 0, 640, 360, 16f / 9));
        // 1280×720 into a cell of 213×120: within 1 %, no one-pixel bars
        assertArrayEquals(new int[] {213, 120, 426, 240}, Snapshots.fit(213, 120, 426, 240, 16f / 9));
    }

    @Test
    public void fitCentresATurnedPictureWithBarsAtTheSides() {
        // 1920×2560 (2560×1920 turned 90°) in a 16:9 cell: as tall as the cell, centred
        assertArrayEquals(new int[] {185, 0, 455, 360}, Snapshots.fit(0, 0, 640, 360, 1920f / 2560));
        // The same in the bottom right cell of a 3×3 grid of 1280×720
        assertArrayEquals(new int[] {976, 480, 1156, 720},
                Snapshots.fit(853, 480, 1280, 720, 1920f / 2560));
    }

    @Test
    public void fitPutsBarsAboveAndBelowAWiderPicture() {
        // 32:9 in a 16:9 cell
        assertArrayEquals(new int[] {0, 90, 640, 270}, Snapshots.fit(0, 0, 640, 360, 32f / 9));
    }
}
