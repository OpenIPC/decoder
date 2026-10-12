/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * DecoderRetry.java — back-off for re-creating a failed MediaCodec decoder
 *
 */

package com.openipc.decoder;

/**
 * After a decoder failure the next attempt waits 0.5 s, then 1, 2, 4, 8 s, at most 10 s.
 * A decoded frame resets the back-off. Thread-safe.
 */
class DecoderRetry {
    static final long MIN_DELAY_MS = 500;
    static final long MAX_DELAY_MS = 10_000;

    private int failures;
    private long notBeforeMs;

    synchronized void onFailure(long nowMs) {
        failures++;
        notBeforeMs = nowMs + delayMs(failures);
    }

    synchronized void onSuccess() {
        failures = 0;
        notBeforeMs = 0;
    }

    synchronized boolean mayCreate(long nowMs) {
        return nowMs >= notBeforeMs;
    }

    synchronized int failures() {
        return failures;
    }

    static long delayMs(int failures) {
        if (failures <= 0) return 0;
        int shift = Math.min(failures - 1, 5);
        return Math.min(MAX_DELAY_MS, MIN_DELAY_MS << shift);
    }
}
