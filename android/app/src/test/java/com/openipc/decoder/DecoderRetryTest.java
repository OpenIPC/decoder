/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * DecoderRetryTest.java — decoder re-creation back-off and enable/disable gate
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DecoderRetryTest {
    @Test
    public void delaysGrowAndAreCapped() {
        assertEquals(0, DecoderRetry.delayMs(0));
        assertEquals(500, DecoderRetry.delayMs(1));
        assertEquals(1000, DecoderRetry.delayMs(2));
        assertEquals(2000, DecoderRetry.delayMs(3));
        assertEquals(8000, DecoderRetry.delayMs(5));
        assertEquals(10_000, DecoderRetry.delayMs(6));
        assertEquals(10_000, DecoderRetry.delayMs(100));
    }

    @Test
    public void failureBlocksUntilTheDelayPasses() {
        // #21: a failure is not permanent
        DecoderRetry r = new DecoderRetry();
        assertTrue(r.mayCreate(1000));
        r.onFailure(1000);
        assertFalse(r.mayCreate(1499));
        assertTrue(r.mayCreate(1500));
        r.onFailure(1500);
        assertFalse(r.mayCreate(2499));
        assertTrue(r.mayCreate(2500));
        assertEquals(2, r.failures());
    }

    @Test
    public void successResetsTheBackOff() {
        DecoderRetry r = new DecoderRetry();
        r.onFailure(0);
        r.onFailure(0);
        r.onSuccess();
        assertEquals(0, r.failures());
        assertTrue(r.mayCreate(0));
    }

    private static Frame sps() {
        byte[] d = {0, 0, 0, 1, 0x67, 0x42};
        return new Frame(d, d.length);
    }

    @Test
    public void disabledManagerNeverAsksForADecoder() {
        // #32: after a pause no decoder may be created by a late decode thread
        MediaCodecManager m = new MediaCodecManager(null);
        assertTrue("no decoder yet: create one", m.decodeFrame(sps(), false, 0));
        m.setEnabled(false);
        assertFalse(m.decodeFrame(sps(), false, 0));
        m.createDecoder(false); // must be a no-op while disabled
        assertFalse(m.decodeFrame(sps(), false, 0));
        m.setEnabled(true);
        assertTrue(m.decodeFrame(sps(), false, 0));
    }

    @Test
    public void savedSlotIsClamped() {
        // #22: a stored slot outside 0..CAM_COUNT-1 falls back to the first camera (#13: 8 slots)
        assertEquals(2, Decoder.clampSlot(2));
        assertEquals(7, Decoder.clampSlot(7));
        assertEquals(0, Decoder.clampSlot(-1));
        assertEquals(0, Decoder.clampSlot(Decoder.CAM_COUNT));
    }
}
