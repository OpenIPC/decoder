/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * QuadGridTest.java — multi-view grid size for 8 camera slots (#13)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class QuadGridTest {
    private static final String DEF = "rtsp://default";

    private static String[] hosts(String... set) {
        String[] h = new String[Decoder.CAM_COUNT];
        java.util.Arrays.fill(h, DEF);
        for (int i = 0; i < set.length; i++) if (set[i] != null) h[i] = set[i];
        return h;
    }

    @Test
    public void eightSlots() {
        assertEquals(8, Decoder.CAM_COUNT);
    }

    @Test
    public void twoByTwoWhileOnlyTheFirstFourAreUsed() {
        assertEquals(2, Decoder.quadGridSize(hosts(), DEF));
        assertEquals(2, Decoder.quadGridSize(hosts("rtsp://a", "rtsp://b", "rtsp://c", "rtsp://d"), DEF));
    }

    @Test
    public void threeByThreeOnceAnUpperSlotIsUsed() {
        assertEquals(3, Decoder.quadGridSize(hosts(null, null, null, null, "rtsp://e"), DEF));
        assertEquals(3, Decoder.quadGridSize(hosts(null, null, null, null, null, null, null, "rtsp://h"), DEF));
    }

    @Test
    public void emptyOrMissingUpperSlotsDoNotCount() {
        String[] h = hosts();
        h[5] = "";
        h[6] = null;
        assertEquals(2, Decoder.quadGridSize(h, DEF));
    }
}
