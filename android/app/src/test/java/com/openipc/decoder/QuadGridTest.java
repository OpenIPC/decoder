/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * QuadGridTest.java — multi-view grid size for 8 camera slots (#13)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void udpCellHintNamesPortAndAddress() {
        String t = Decoder.udpHintText(java.util.Arrays.asList("192.168.1.20"), 5600);
        assertTrue(t, t.startsWith("Waiting for a stream"));
        assertFalse(t, t.contains("UDP RTP"));
        assertTrue(t.contains("192.168.1.20:5600"));
        // Without a known address the hint still tells where to send the stream
        assertTrue(Decoder.udpHintText(java.util.Collections.emptyList(), 5600)
                .contains("this device:5600"));
    }

    @Test
    public void theUCellIsTheNinthCell() {
        assertEquals(Decoder.CAM_COUNT, Decoder.U_CELL);
        assertEquals(9, Decoder.CELL_COUNT);
        assertEquals("udp://@:5600", Decoder.UDP_CELL_URL);
    }

    @Test
    public void udpHintListsEveryAddress() {
        String none = Decoder.udpHintText(java.util.Collections.emptyList(), 5600);
        assertTrue(none, none.endsWith("\nthis device:5600"));
        String one = Decoder.udpHintText(java.util.Arrays.asList("172.17.32.144"), 5600);
        assertTrue(one, one.endsWith("output to\n\n172.17.32.144:5600"));
        String two = Decoder.udpHintText(
                java.util.Arrays.asList("172.17.32.144", "192.168.1.20"), 5600);
        assertTrue(two, two.endsWith("output to\n\n172.17.32.144:5600\n192.168.1.20:5600"));
    }

    @Test
    public void decoderStuckIsReportedAfterTwentyQuietSeconds() {
        int s = 0;
        for (int i = 1; i < Decoder.STUCK_SECONDS; i++) {
            s = Decoder.stuckStep(s, 9, false);
            assertEquals(i, s);
        }
        assertTrue(s < Decoder.STUCK_SECONDS);
        s = Decoder.stuckStep(s, 9, false);
        assertTrue(s >= Decoder.STUCK_SECONDS);
        // Any output, from any cell, starts the count again (Qodo on #147)
        assertEquals(0, Decoder.stuckStep(s, 9, true));
        // One camera alone may just wait for a key frame
        assertEquals(0, Decoder.stuckStep(5, 1, false));
        assertEquals(0, Decoder.stuckStep(5, 0, false));
    }
}
