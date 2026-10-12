/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * FramePool.java — simple object pool for Frame objects to reduce GC pressure
 *
 */

package com.openipc.decoder;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Two size classes: small frames (RTP packets, audio, most NAL units) and large frames
 * (key frames). Keeping them apart stops small requests from taking the large buffers and
 * key frames from always allocating; the large class is bounded to cap retained memory.
 */
class FramePool {
    /** Frames up to this capacity are "small": one interleaved RTP packet fits. */
    static final int SMALL_MAX = 65536 + 4;
    private static final int SMALL_MIN = 2048;
    private static final int LARGE_STEP = 256 * 1024;

    private final BlockingQueue<Frame> small;
    private final BlockingQueue<Frame> large;

    FramePool(int maxSize) {
        this.small = new ArrayBlockingQueue<>(maxSize);
        this.large = new ArrayBlockingQueue<>(Math.max(1, maxSize / 8));
    }

    Frame obtain(int size) {
        boolean isSmall = size <= SMALL_MAX;
        Frame frame = (isSmall ? small : large).poll();
        if (frame != null && frame.data().length >= size) {
            frame.setLength(0);
            return frame;
        }
        // Round the capacity up so that the frame can be reused for similar sizes
        return new Frame(new byte[capacityFor(size)], 0);
    }

    void recycle(Frame frame) {
        if (frame == null) return;
        // offer() drops the frame when its class is full
        (frame.data().length <= SMALL_MAX ? small : large).offer(frame);
    }

    void clear() {
        small.clear();
        large.clear();
    }

    static int capacityFor(int size) {
        if (size <= SMALL_MAX) {
            int c = SMALL_MIN;
            while (c < size) c <<= 1;
            return Math.min(c, SMALL_MAX);
        }
        return ((size + LARGE_STEP - 1) / LARGE_STEP) * LARGE_STEP;
    }
}
