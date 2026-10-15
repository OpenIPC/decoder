/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * LogBuffer.java — the app's recent log lines in memory, redacted, for /log (#96)
 *
 */

package com.openipc.decoder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * A bounded ring of log lines. Lines are redacted when they come in, so no login or password
 * is kept. Fed by a logcat reader started with the server: without READ_LOGS logcat shows only
 * this app's own lines, so every Log call in the code lands here without changing it.
 */
final class LogBuffer {
    private static final long RESTART_DELAY_MS = 10_000;

    private final int capacity;
    private final ArrayDeque<String> lines;

    LogBuffer(int capacity) {
        this.capacity = capacity;
        this.lines = new ArrayDeque<>(capacity);
    }

    void add(String line) {
        String safe = DebugServer.redact(line);
        synchronized (lines) {
            if (lines.size() == capacity) lines.removeFirst();
            lines.addLast(safe);
        }
    }

    String text() {
        StringBuilder sb = new StringBuilder();
        synchronized (lines) {
            for (String line : lines) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Follow logcat on a daemon thread for the life of the process; restarted if it ends. */
    void startReader() {
        Thread t = new Thread(() -> {
            while (true) {
                Process p = null;
                try {
                    // No -d: dump what logcat still has, then keep following. Every line of the
                    // app's own tag, but only Info and up from the rest of the process: graphics
                    // libraries on some phones (mali_gralloc) log dozens of Debug lines a second
                    // and would push the app's lines out of the ring within seconds (#187)
                    p = new ProcessBuilder("logcat", "-v", "threadtime",
                            "OpenIPCDecoder:V", "*:I")
                            .redirectErrorStream(true).start();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) add(line);
                    }
                } catch (IOException e) {
                    add("Cannot read the log: " + e.getMessage());
                } finally {
                    if (p != null) p.destroy();
                }
                try {
                    Thread.sleep(RESTART_DELAY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "debug-log");
        t.setDaemon(true);
        t.start();
    }
}
