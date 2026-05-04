/*
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * Frame.java — lightweight byte-array wrapper for decoded/raw media data
 *
 */

package com.openipc.decoder;

class Frame {
    private final byte[] data;
    private int length;

    Frame(byte[] data, int length) {
        this.data = data;
        this.length = length;
    }

    byte[] data() { return data; }
    int length() { return length; }
    void setLength(int length) { this.length = length; }
}
