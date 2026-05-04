/*
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * FramePool.java — simple object pool for Frame objects to reduce GC pressure
 *
 */

package com.openipc.decoder;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

class FramePool {
    private final BlockingQueue<Frame> pool;
    private final int maxSize;

    FramePool(int maxSize) {
        this.maxSize = maxSize;
        this.pool = new ArrayBlockingQueue<>(maxSize);
    }

    Frame obtain(int size) {
        Frame frame = pool.poll();
        if (frame != null && frame.data().length >= size) {
            frame.setLength(0);
            return frame;
        }
        return new Frame(new byte[size], 0);
    }

    void recycle(Frame frame) {
        if (frame != null && pool.size() < maxSize) {
            pool.offer(frame);
        }
    }

    void clear() {
        pool.clear();
    }
}
