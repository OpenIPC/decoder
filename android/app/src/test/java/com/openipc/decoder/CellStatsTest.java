/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * CellStatsTest.java — multi-view cell status text and decoder instance limits (#80, #82, #83)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class CellStatsTest {
    @After
    public void resetBudget() {
        MediaCodecManager.InstanceBudget.reset();
    }

    private static QuadCell cell() {
        return new QuadCell(0, "rtsp://cam/s", Transport.Mode.TCP, null, new FramePool(4), "",
                index -> {});
    }

    @Test
    public void connectionErrorsAreShortened() {
        assertEquals("Port in use", QuadCell.shortError("bind failed: EADDRINUSE (Address already in use)"));
        assertEquals("Connection refused", QuadCell.shortError(
                "failed to connect to /10.0.0.2 (port 554) after 5000ms: isConnected failed: ECONNREFUSED"));
        assertEquals("No answer from camera", QuadCell.shortError(
                "failed to connect to /10.0.0.2 (port 554) after 5000ms"));
        assertEquals("Wrong login or password", QuadCell.shortError("RTSP/1.0 401 Unauthorized"));
        assertEquals("Connection failed", QuadCell.shortError(null));
        assertTrue(QuadCell.shortError("x".repeat(80)).length() <= 41);
    }

    @Test
    public void statusBeforePlaying() {
        QuadCell c = cell();
        assertEquals("Connecting…", c.sample(1000));
        c.onStatus("buffering");
        assertEquals("Buffering…", c.sample(2000));
        c.onStatus("disconnected");
        assertEquals("Disconnected", c.sample(3000));
        c.onFailure("bind failed: EADDRINUSE (Address already in use)");
        assertEquals("Port in use", c.sample(4000));
    }

    @Test
    public void playingShowsCodecAndRates() {
        QuadCell c = cell();
        c.onFailure("ECONNREFUSED");
        c.onStatus("connected");
        // A working connection clears the connection error; a new cell starts on key frames (#85)
        assertEquals("H.264 key frames · 0.0 fps", c.sample(1000));
        assertTrue(c.needsAttention());
        c.setKeyFramesOnly(false);
        assertEquals("H.264 0/0 fps", c.sample(2000));
        assertFalse("playing at full rate: no text without D", c.needsAttention());
        assertFalse(c.isPaused());
    }

    @Test
    public void keyNalUnits() {
        // H.264: IDR, SPS, PPS pass; non-IDR slice and SEI do not
        assertTrue(QuadCell.isKeyNal(5, false));
        assertTrue(QuadCell.isKeyNal(7, false));
        assertTrue(QuadCell.isKeyNal(8, false));
        assertFalse(QuadCell.isKeyNal(1, false));
        assertFalse(QuadCell.isKeyNal(6, false));
        // H.265: IRAP (16-21), VPS/SPS/PPS pass; TRAIL_R and SEI do not
        assertTrue(QuadCell.isKeyNal(19, true));
        assertTrue(QuadCell.isKeyNal(32, true));
        assertTrue(QuadCell.isKeyNal(34, true));
        assertFalse(QuadCell.isKeyNal(1, true));
        assertFalse(QuadCell.isKeyNal(39, true));
        assertTrue(QuadCell.isKeyFrameNal(5, false));
        assertFalse(QuadCell.isKeyFrameNal(7, false));
    }

    @Test
    public void pausedReasonWins() {
        QuadCell c = cell();
        c.onStatus("connected");
        c.pause("Paused: decoder overloaded");
        assertTrue(c.isPaused());
        assertEquals("Paused: decoder overloaded", c.sample(1000));
    }

    @Test
    public void instanceBudgetHoldsTheLearntLimit() {
        String hevc = "video/hevc";
        // Unknown limit: no restriction yet
        assertTrue(MediaCodecManager.InstanceBudget.tryAcquire(hevc));
        MediaCodecManager.InstanceBudget.learn(hevc, 2);
        assertEquals(2, MediaCodecManager.InstanceBudget.limit(hevc));
        assertTrue(MediaCodecManager.InstanceBudget.tryAcquire(hevc));
        assertFalse("third H.265 decoder over a limit of 2",
                MediaCodecManager.InstanceBudget.tryAcquire(hevc));
        MediaCodecManager.InstanceBudget.release(hevc);
        assertTrue(MediaCodecManager.InstanceBudget.tryAcquire(hevc));
        // Other codecs are counted separately
        assertTrue(MediaCodecManager.InstanceBudget.tryAcquire("video/avc"));
        assertEquals(-1, MediaCodecManager.InstanceBudget.limit("video/avc"));
    }
}
