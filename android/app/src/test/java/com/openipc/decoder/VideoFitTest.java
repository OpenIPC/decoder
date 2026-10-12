/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * VideoFitTest.java — fitting the video view inside the screen
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class VideoFitTest {
    @Test
    public void sameAspectFillsTheScreen() {
        assertArrayEquals(new int[] {1920, 1080}, Decoder.fitInside(1280, 720, 1920, 1080));
    }

    @Test
    public void narrowerScreenGetsBarsTopAndBottom() {
        // #17: 16:9 video on a 16:10 tablet is no longer cropped left and right
        assertArrayEquals(new int[] {1920, 1080}, Decoder.fitInside(1920, 1080, 1920, 1200));
        // 4:3 tablet
        assertArrayEquals(new int[] {1024, 576}, Decoder.fitInside(1920, 1080, 1024, 768));
    }

    @Test
    public void widerScreenGetsBarsLeftAndRight() {
        // 4:3 camera on a 16:9 TV
        assertArrayEquals(new int[] {1440, 1080}, Decoder.fitInside(640, 480, 1920, 1080));
        // 21:9 phone
        assertArrayEquals(new int[] {1920, 1080}, Decoder.fitInside(1920, 1080, 2520, 1080));
    }

    @Test
    public void portraitScreen() {
        // #31: Android 16 may show the app in portrait on large screens
        assertArrayEquals(new int[] {1200, 675}, Decoder.fitInside(1920, 1080, 1200, 1920));
    }

    @Test
    public void neverLargerThanTheBox() {
        int[] s = Decoder.fitInside(1921, 1081, 1000, 563);
        assertArrayEquals(new int[] {1000, 563}, s);
    }
}
