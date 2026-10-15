/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RotateTest.java — MJPEG frames turned for the sideways KY UFO camera (#200)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

public class RotateTest {
    private static final float EPS = 0.01f;

    @Test
    public void ninetyForTheCooingdvWebcamPathOnly() throws IOException {
        assertEquals(90, RtspSession.Url.parse("rtsp://192.168.1.1:7070/webcam").rotate);
        assertEquals(90, RtspSession.Url.parse("rtsp://10.0.0.9:7070/WEBCAM/").rotate);
        assertEquals(0, RtspSession.Url.parse("rtsp://192.168.1.1:554/webcam").rotate);
        assertEquals(0, RtspSession.Url.parse("rtsp://192.168.1.1:7070/live").rotate);
        assertEquals(0, RtspSession.Url.parse("rtsp://192.168.1.10:554/stream=0").rotate);
    }

    @Test
    public void optionOverridesAndIsNotSentToTheCamera() throws IOException {
        RtspSession.Url u = RtspSession.Url.parse("rtsp://192.168.1.1:7070/webcam?rotate=0");
        assertEquals(0, u.rotate);
        assertEquals(7099, u.heartbeatPort);
        assertEquals("rtsp://192.168.1.1:7070/webcam", u.requestUrl);
        u = RtspSession.Url.parse("rtsp://192.168.1.1:7070/webcam?Rotate=270&heartbeat=off");
        assertEquals(270, u.rotate);
        assertEquals(0, u.heartbeatPort);
        assertEquals("rtsp://192.168.1.1:7070/webcam", u.requestUrl);
        // Any camera, other parameters kept in their order
        u = RtspSession.Url.parse("rtsp://u:p@cam/x?a=1&rotate=180&b=2");
        assertEquals(180, u.rotate);
        assertEquals("rtsp://cam/x?a=1&b=2", u.requestUrl);
        u = RtspSession.Url.parse("rtsp://cam/x?rotate=90");
        assertEquals(90, u.rotate);
        assertEquals("rtsp://cam/x", u.requestUrl);
        for (String bad : new String[] {"rtsp://cam/x?rotate=45", "rtsp://cam/x?rotate=",
                "rtsp://cam/x?rotate", "rtsp://cam/x?rotate=-90", "rtsp://cam/x?rotate=360",
                "rtsp://cam/x?rotate=left"}) {
            try {
                RtspSession.Url.parse(bad);
                fail(bad);
            } catch (IOException expected) {
                // ok
            }
        }
    }

    @Test
    public void quarterTurnSwapsTheShownSize() {
        assertArrayEquals(new int[] {640, 480}, JpegRenderer.shownSize(480, 640, 90));
        assertArrayEquals(new int[] {640, 480}, JpegRenderer.shownSize(480, 640, 270));
        assertArrayEquals(new int[] {480, 640}, JpegRenderer.shownSize(480, 640, 180));
        assertArrayEquals(new int[] {480, 640}, JpegRenderer.shownSize(480, 640, 0));
    }

    @Test
    public void turnedPortraitSourceFillsALandscapeScreen() {
        // The K200's 480x640 on a 1600x720 phone: 960x720, not a 540x720 strip
        int[] shown = JpegRenderer.shownSize(480, 640, 90);
        assertArrayEquals(new int[] {960, 720}, Decoder.fitInside(shown[0], shown[1], 1600, 720));
        assertArrayEquals(new int[] {540, 720}, Decoder.fitInside(480, 640, 1600, 720));
    }

    @Test
    public void ninetyCounterClockwiseMovesTheCorners() {
        // A 960x720 surface; the frame's top left goes to the bottom left, its top right to
        // the top left: the top edge becomes the left one
        float[] t = JpegRenderer.turn(90, 960, 720);
        assertPoint(0, 720, JpegRenderer.map(t, 0, 0));
        assertPoint(0, 0, JpegRenderer.map(t, 1, 0));
        assertPoint(960, 720, JpegRenderer.map(t, 0, 1));
        assertPoint(960, 0, JpegRenderer.map(t, 1, 1));
        assertPoint(480, 360, JpegRenderer.map(t, 0.5f, 0.5f));
    }

    @Test
    public void otherTurnsFillTheSurfaceToo() {
        float[] t = JpegRenderer.turn(270, 960, 720);
        assertPoint(960, 0, JpegRenderer.map(t, 0, 0));
        assertPoint(0, 720, JpegRenderer.map(t, 1, 1));
        t = JpegRenderer.turn(180, 960, 720);
        assertPoint(960, 720, JpegRenderer.map(t, 0, 0));
        assertPoint(0, 0, JpegRenderer.map(t, 1, 1));
        t = JpegRenderer.turn(0, 960, 720);
        assertPoint(0, 0, JpegRenderer.map(t, 0, 0));
        assertPoint(960, 720, JpegRenderer.map(t, 1, 1));
    }

    private static void assertPoint(float x, float y, float[] p) {
        assertEquals(x, p[0], EPS);
        assertEquals(y, p[1], EPS);
    }
}
