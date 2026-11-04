/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RemoteKeysTest.java — zoom, pan and the selected cell on a D-pad remote (#263)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class RemoteKeysTest {
    private static final int UP = KeyEvent.KEYCODE_DPAD_UP;
    private static final int DOWN = KeyEvent.KEYCODE_DPAD_DOWN;
    private static final int LEFT = KeyEvent.KEYCODE_DPAD_LEFT;
    private static final int RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT;

    @Test
    public void zoomKeys() {
        assertEquals(1, RemoteKeys.zoomDirection(KeyEvent.KEYCODE_ZOOM_IN));
        assertEquals(1, RemoteKeys.zoomDirection(KeyEvent.KEYCODE_CHANNEL_UP));
        assertEquals(1, RemoteKeys.zoomDirection(KeyEvent.KEYCODE_PLUS));
        assertEquals(-1, RemoteKeys.zoomDirection(KeyEvent.KEYCODE_ZOOM_OUT));
        assertEquals(-1, RemoteKeys.zoomDirection(KeyEvent.KEYCODE_PAGE_DOWN));
        assertEquals(-1, RemoteKeys.zoomDirection(KeyEvent.KEYCODE_MINUS));
        assertEquals(0, RemoteKeys.zoomDirection(UP));
        assertEquals(0, RemoteKeys.zoomDirection(KeyEvent.KEYCODE_DPAD_CENTER));
    }

    @Test
    public void arrows() {
        assertTrue(RemoteKeys.isArrow(UP));
        assertTrue(RemoteKeys.isArrow(RIGHT));
        assertFalse(RemoteKeys.isArrow(KeyEvent.KEYCODE_ENTER));
    }

    @Test
    public void zoomStaysWithinLimits() {
        assertEquals(1.25f, RemoteKeys.zoom(1f, 1.25f, 1f, 5f), 1e-6);
        assertEquals(5f, RemoteKeys.zoom(4.5f, 1.25f, 1f, 5f), 1e-6);
        assertEquals(1f, RemoteKeys.zoom(1.1f, 0.8f, 1f, 5f), 1e-6);
        // Rounding of repeated steps lands on 1× exactly, which resets the pan
        assertEquals(1f, RemoteKeys.zoom(1.0005f, 1f, 1f, 5f), 0f);
    }

    @Test
    public void heldUpReachesMaxInAboutTwoSeconds() {
        float z = 1f;
        int repeats = 0;
        while (z < 5f && repeats < 100) {
            z = RemoteKeys.zoom(z, RemoteKeys.ZOOM_HOLD_STEP, 1f, 5f);
            repeats++;
        }
        assertEquals(5f, z, 1e-6);
        // At 20 repeats a second
        assertTrue(repeats >= 30 && repeats <= 50);
    }

    @Test
    public void zoomKeepsTheCentre() {
        assertEquals(200f, RemoteKeys.panAfterZoom(100f, 2f, 4f), 1e-6);
        assertEquals(-50f, RemoteKeys.panAfterZoom(-100f, 4f, 2f), 1e-6);
        assertEquals(0f, RemoteKeys.panAfterZoom(100f, 0f, 2f), 0f);
    }

    @Test
    public void arrowsShowThatSideOfThePicture() {
        assertArrayEquals(new float[] {0f, 10f}, RemoteKeys.panForArrow(UP, 10f), 0f);
        assertArrayEquals(new float[] {0f, -10f}, RemoteKeys.panForArrow(DOWN, 10f), 0f);
        assertArrayEquals(new float[] {10f, 0f}, RemoteKeys.panForArrow(LEFT, 10f), 0f);
        assertArrayEquals(new float[] {-10f, 0f}, RemoteKeys.panForArrow(RIGHT, 10f), 0f);
        assertArrayEquals(new float[] {0f, 0f},
                RemoteKeys.panForArrow(KeyEvent.KEYCODE_ENTER, 10f), 0f);
    }

    @Test
    public void selectionMovesInTheGrid() {
        // 2×2: 0 1 / 2 3
        assertEquals(1, RemoteKeys.moveCell(0, 2, RIGHT));
        assertEquals(2, RemoteKeys.moveCell(0, 2, DOWN));
        assertEquals(3, RemoteKeys.moveCell(1, 2, DOWN));
        assertEquals(2, RemoteKeys.moveCell(3, 2, LEFT));
        // 3×3: the ninth cell (8, the U cell) is reachable
        assertEquals(8, RemoteKeys.moveCell(5, 3, DOWN));
        assertEquals(8, RemoteKeys.moveCell(7, 3, RIGHT));
        assertEquals(4, RemoteKeys.moveCell(1, 3, DOWN));
    }

    @Test
    public void selectionStopsAtTheEdges() {
        assertEquals(0, RemoteKeys.moveCell(0, 2, UP));
        assertEquals(0, RemoteKeys.moveCell(0, 2, LEFT));
        assertEquals(3, RemoteKeys.moveCell(3, 2, RIGHT));
        assertEquals(8, RemoteKeys.moveCell(8, 3, DOWN));
        assertEquals(2, RemoteKeys.moveCell(2, 3, RIGHT));
    }

    @Test
    public void aCellOutOfTheGridStartsAtTheFirst() {
        assertEquals(0, RemoteKeys.moveCell(-1, 2, RIGHT));
        // 3×3 grid shrunk to 2×2: cell 6 is gone
        assertEquals(0, RemoteKeys.moveCell(6, 2, UP));
    }
}
