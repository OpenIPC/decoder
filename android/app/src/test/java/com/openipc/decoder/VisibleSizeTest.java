/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * VisibleSizeTest.java — picture size from the decoder's crop rectangle, not the padded buffer (#166)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class VisibleSizeTest {
    @Test
    public void cropGivesThePictureSize() {
        // H.264 1080p: 16-line macroblocks pad the buffer to 1088
        assertArrayEquals(new int[]{1920, 1080},
                MediaCodecManager.visibleSize(1920, 1088, 0, 0, 1919, 1079));
        // H.265 1080p on a decoder that pads the width to 2304 (#166)
        assertArrayEquals(new int[]{1920, 1080},
                MediaCodecManager.visibleSize(2304, 1088, 0, 0, 1919, 1079));
        // An offset crop
        assertArrayEquals(new int[]{1280, 720},
                MediaCodecManager.visibleSize(1280, 736, 0, 8, 1279, 727));
    }

    @Test
    public void withoutCropTheBufferSizeStays() {
        assertArrayEquals(new int[]{1920, 1088},
                MediaCodecManager.visibleSize(1920, 1088, -1, -1, -1, -1));
        assertArrayEquals(new int[]{640, 480},
                MediaCodecManager.visibleSize(640, 480, 0, 0, 0, 0));
    }

    @Test
    public void cropLargerThanTheBufferIsIgnored() {
        assertArrayEquals(new int[]{1920, 1088},
                MediaCodecManager.visibleSize(1920, 1088, 0, 0, 3839, 2159));
    }
}
