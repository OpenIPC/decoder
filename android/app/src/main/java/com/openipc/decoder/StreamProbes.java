/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * StreamProbes.java — short stream checks of the slots not on screen, for the debug page (#100)
 *
 */

package com.openipc.decoder;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * In single view only one camera has a session, so the debug page would know nothing about
 * the others. While the page is in use (it asks for the state every 1.5 s, which calls
 * {@link #update}), each other configured slot gets a {@link StreamProbe} every PERIOD_MS,
 * one or two at a time. A slot of the camera on screen is never probed: some cameras take
 * one RTSP client only (XM). Nothing runs once the page has not asked for IDLE_MS, or in
 * multi-view, where every cell has its own session.
 */
final class StreamProbes {
    private static final String TAG = "OpenIPCDecoder";
    /** A slot is checked again this long after its last check began. */
    static final long PERIOD_MS = 45_000;
    /** No request from the debug page for this long: no new probes. */
    static final long IDLE_MS = 60_000;
    /** Probes at the same time: a few sockets, never more load than that. */
    static final int MAX_RUNNING = 2;
    private static final long TICK_MS = 2000;

    /** Why a slot gets no probe, or null if it gets one. */
    static String skipReason(String url, String defaultUrl, String screenUrl) {
        if (url == null || url.trim().isEmpty() || url.equals(defaultUrl)) return "not configured";
        // A listening slot would take the port; the camera pushes to it, nothing to ask
        if (RtpListener.Url.matches(url)) return "UDP listen slot: not checked in single view";
        String e = endpoint(url);
        if (e == null) return "invalid URL";
        if (e.equals(endpoint(screenUrl))) return "same camera as on screen";
        return null;
    }

    /** "host:port" of an RTSP or HTTP URL, lower case, or null. */
    static String endpoint(String url) {
        if (url == null) return null;
        try {
            if (HttpMjpeg.matches(url)) {
                HttpMjpeg.Url u = HttpMjpeg.Url.parse(url);
                return u.host.toLowerCase(Locale.ROOT) + ":" + u.port;
            }
            if (RtpListener.Url.matches(url)) return null;
            RtspSession.Url u = RtspSession.Url.parse(url);
            return u.host == null || u.host.isEmpty() ? null
                    : u.host.toLowerCase(Locale.ROOT) + ":" + u.port;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The slot to probe next, or -1: one that may be probed, is not running, has no running
     * probe to the same camera, and whose last start is at least PERIOD_MS ago; the one
     * waiting longest first (never checked before all others), the lower slot on a tie.
     */
    static int next(String[] urls, String[] skip, long[] lastStartMs, boolean[] running,
                    long nowMs) {
        Set<String> busy = new HashSet<>();
        for (int i = 0; i < urls.length; i++) {
            if (running[i]) busy.add(endpoint(urls[i]));
        }
        int best = -1;
        for (int i = 0; i < urls.length; i++) {
            if (skip[i] != null || running[i] || busy.contains(endpoint(urls[i]))) continue;
            if (lastStartMs[i] > 0 && nowMs - lastStartMs[i] < PERIOD_MS) continue;
            if (best < 0 || lastStartMs[i] < lastStartMs[best]) best = i;
        }
        return best;
    }

    private final String userAgent;
    private final Object lock = new Object();
    // Guarded by lock: the slots as of the last update
    private String[] urls = new String[0];
    private Transport.Mode[] modes = new Transport.Mode[0];
    /** {@link StreamProbe#key} per slot: URL and probe transport. */
    private String[] keys = new String[0];
    private String screenUrl;
    private boolean wanted;
    private long lastUpdateMs;
    private long[] lastStartMs = new long[0];
    private final boolean[] running = new boolean[Decoder.CAM_COUNT];
    private final Probe[] probes = new Probe[Decoder.CAM_COUNT];
    private ScheduledFuture<?> ticker;
    /** After shutdown(): nothing is scheduled any more. */
    private boolean shut;

    private final ConcurrentHashMap<Integer, StreamProbe.Result> results = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "probe-sched");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workers = new ThreadPoolExecutor(MAX_RUNNING, MAX_RUNNING,
            30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                // Background priority: the stream on screen comes first
                Thread t = new Thread(() -> {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "probe");
                t.setDaemon(true);
                return t;
            });

    /** A running probe; cancelled when its camera goes on screen or probing stops. */
    private static final class Probe implements StreamProbe.Cancel {
        final String url;
        /** {@link StreamProbe#key} of the slot's setting when the probe began. */
        final String key;
        private volatile boolean cancelled;
        // Guarded by this
        private RtspSession session;

        Probe(String url, String key) {
            this.url = url;
            this.key = key;
        }

        @Override public boolean cancelled() { return cancelled; }

        @Override
        public synchronized boolean start(RtspSession s) {
            if (cancelled) return false;
            session = s;
            s.start();
            return true;
        }

        /**
         * Ends the probe now: its socket is closed before this returns, so the camera has no
         * second client once the TV connects (one-client cameras, Qodo on #163).
         */
        synchronized void cancel() {
            cancelled = true;
            if (session != null) session.stop();
        }
    }

    StreamProbes(String userAgent) {
        this.userAgent = userAgent;
        ((ThreadPoolExecutor) workers).allowCoreThreadTimeOut(true);
        // No thread kept while the page is not in use
        ((ScheduledThreadPoolExecutor) scheduler).setKeepAliveTime(30, TimeUnit.SECONDS);
        ((ScheduledThreadPoolExecutor) scheduler).allowCoreThreadTimeOut(true);
    }

    /** The activity goes away: probes end, the threads go (Qodo on #163). */
    void shutdown() {
        synchronized (lock) {
            shut = true;
        }
        stopAll();
        scheduler.shutdownNow();
        workers.shutdown();
    }

    /**
     * The slots now (UI thread, from the debug requests): {@code wanted} in single view and the
     * U view, false otherwise; {@code screenUrl} the camera on screen, or null.
     */
    void update(String[] slotUrls, Transport.Mode[] slotModes, String screen, boolean want) {
        synchronized (lock) {
            if (shut) return;
            urls = slotUrls.clone();
            modes = java.util.Arrays.copyOf(slotModes, urls.length);
            String[] newKeys = new String[urls.length];
            for (int i = 0; i < urls.length; i++) newKeys[i] = StreamProbe.key(urls[i], modes[i]);
            if (lastStartMs.length != urls.length) lastStartMs = new long[urls.length];
            for (int i = 0; i < urls.length; i++) {
                // A changed URL or transport: the last check says nothing about it, check soon
                if (i < keys.length && !java.util.Objects.equals(keys[i], newKeys[i])) {
                    results.remove(i);
                    lastStartMs[i] = 0;
                }
            }
            keys = newKeys;
            screenUrl = screen;
            wanted = want;
            lastUpdateMs = SystemClock.elapsedRealtime();
            cancelStaleLocked();
            if (want && ticker == null) {
                ticker = scheduler.scheduleWithFixedDelay(this::tick, 0, TICK_MS,
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    /** The TV is about to connect to this camera: a probe of it ends now. */
    void willShow(String url) {
        synchronized (lock) {
            screenUrl = url;
            cancelStaleLocked();
        }
    }

    /** Multi-view or the app going away: running probes end now, no new ones. */
    void stopAll() {
        synchronized (lock) {
            wanted = false;
            cancelStaleLocked();
        }
    }

    /** The result for a slot, if it was found for the slot's current URL and transport. */
    StreamProbe.Result result(int slot, String url, Transport.Mode mode) {
        StreamProbe.Result r = results.get(slot);
        return r != null && r.key.equals(StreamProbe.key(url, mode)) ? r : null;
    }

    /** The slot is being checked right now. */
    boolean isRunning(int slot) {
        synchronized (lock) {
            return slot < running.length && running[slot];
        }
    }

    /** Ends probes that must not go on: probing stopped, or their camera is on screen now. */
    private void cancelStaleLocked() {
        String screen = StreamProbes.endpoint(screenUrl);
        for (int i = 0; i < probes.length; i++) {
            Probe p = probes[i];
            if (p == null || p.cancelled()) continue;
            String e = endpoint(p.url);
            boolean slotChanged = i >= keys.length || !p.key.equals(keys[i]);
            if (!wanted || slotChanged || (e != null && e.equals(screen))) p.cancel();
        }
    }

    private void tick() {
        synchronized (lock) {
            long now = SystemClock.elapsedRealtime();
            if (!wanted || now - lastUpdateMs > IDLE_MS) {
                // The page went away or the grid is back: stop asking cameras
                wanted = false;
                cancelStaleLocked();
                if (ticker != null) ticker.cancel(false);
                ticker = null;
                return;
            }
            int count = 0;
            for (boolean r : running) if (r) count++;
            if (count >= MAX_RUNNING) return;
            String[] skip = new String[urls.length];
            for (int i = 0; i < urls.length; i++) skip[i] = skipReason(urls[i], null, screenUrl);
            boolean[] run = java.util.Arrays.copyOf(running, urls.length);
            // One new probe per tick: the checks are spread out, never started all at once
            int slot = next(urls, skip, lastStartMs, run, now);
            if (slot < 0) return;
            lastStartMs[slot] = now;
            running[slot] = true;
            Probe p = new Probe(urls[slot], keys[slot]);
            probes[slot] = p;
            Transport.Mode mode = slot < modes.length ? modes[slot] : null;
            try {
                workers.execute(() -> runProbe(slot, p, mode));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Shut down meanwhile
                probes[slot] = null;
                running[slot] = false;
            }
        }
    }

    private void runProbe(int slot, Probe p, Transport.Mode mode) {
        StreamProbe.Result r = null;
        try {
            r = StreamProbe.run("Probe-" + (slot + 1), p.url, mode, userAgent, p);
        } catch (RuntimeException e) {
            Log.w(TAG, "Probe-" + (slot + 1) + " failed", e);
        } finally {
            synchronized (lock) {
                if (probes[slot] == p) {
                    probes[slot] = null;
                    running[slot] = false;
                }
            }
        }
        if (r != null) {
            results.put(slot, r);
            Log.i(TAG, "Probe-" + (slot + 1) + ": " + (r.ok ? "stream OK, " : "") + r.summary);
        }
    }
}
