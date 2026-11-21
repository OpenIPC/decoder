/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * DebugServer.java — read-only HTTP server with the debug information, LAN clients only (#96)
 *
 */

package com.openipc.decoder;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Serves what the decoder knows about itself on the port of {@link WebPort} (8085 unless Network
 * sets another; the next free one when it is taken, #284), for a browser or curl:
 * {@code /} a page refreshing itself, {@code /stats.json} the same as JSON, {@code /log} the
 * app's recent log, {@code /report.txt} both in one file to download (#207). On unless Settings → Allow remote access is off (#286); a client outside the local network gets 403. With a password set in
 * Network, every request needs it (HTTP Basic, any user name), from the local network and the
 * tunnel alike; without one the pages are open, as before. GET only, one
 * request per connection, short timeouts and a few workers: it must never get in the way of
 * decoding. Logins and passwords never leave the device (redacted like the log, #55).
 */
final class DebugServer {
    private static final String TAG = "OpenIPCDecoder";
    /** Lines of the app's log served by /log. */
    static final int LOG_LINES = 500;
    private static final int SOCKET_TIMEOUT_MS = 3000;
    /**
     * Whole request, writing included: a client that stops reading cannot hold a worker.
     * Long enough for a snapshot on a box busy decoding: at background priority it took up
     * to ~10 s right after start with 6 streams on the AG-216 (#98).
     */
    private static final long REQUEST_DEADLINE_MS = 20_000;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final long REBIND_DELAY_MS = 10_000;
    /** A snapshot this young is served again: several viewers cost one capture (#98). */
    private static final long SNAPSHOT_REUSE_MS = 1000;
    /** The answer to a wrong password waits this long: about two guesses a second at most. */
    private static final long WRONG_PASSWORD_DELAY_MS = 1000;

    /** What the server reports; called on a server thread. */
    interface Source {
        /** State as nested Map / List / String / Number / Boolean, see {@link #toJson}. */
        Map<String, Object> snapshot();

        /**
         * JPEG of what the screen shows (#98), or null when there is nothing to show. Without
         * labels: only the frames, for the page that draws the text over them.
         */
        byte[] snapshotJpeg(boolean labels);

        /** Raw bytes of the single view's jieli:// slot (#187), or null. */
        default byte[] jieliRaw(String which) { return null; }
    }

    private static DebugServer instance;

    private volatile Source source;
    /** "listening" or why the port could not be opened, for the debug text. */
    private volatile String state = "starting";
    /** The port the server listens on, 0 while it has none. */
    private volatile int port;
    private static final String OFF = "off (Allow remote access)";
    /** True once the first port was tried: the tunnel waits for it, so it starts with that port. */
    private volatile boolean settled;
    /** The open socket, closed by {@link #rebind()} to take the port set in Network now. */
    private volatile ServerSocket server;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), r -> {
                // Background priority: on a box busy decoding, a request waits, not the video
                Thread t = new Thread(() -> {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "debug-http");
                t.setDaemon(true);
                return t;
            });
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "debug-http-deadline");
        t.setDaemon(true);
        return t;
    });
    private final LogBuffer log = new LogBuffer(LOG_LINES);
    private final Object snapshotLock = new Object();
    /** Index 0: frames only (the page), 1: with labels; reused separately. */
    private final byte[][] lastSnapshot = new byte[2][];
    private final long[] lastSnapshotMs = new long[2];
    private final int[] lastSnapshotGen = new int[2];
    /** Bumped when the source changes: a picture of an earlier source is never served. */
    private volatile int sourceGen;

    /** Package-private for the tests: no threads, nothing bound. */
    DebugServer() {}

    /** The one server of the process, started on first use and kept for its lifetime. */
    static synchronized DebugServer get() {
        if (instance == null) {
            instance = new DebugServer();
            instance.log.startReader();
            Thread t = new Thread(instance::acceptLoop, "debug-http-accept");
            t.setDaemon(true);
            t.start();
        }
        return instance;
    }

    // No lock here: these run on the UI thread, which a snapshot in progress may be waiting for

    void setSource(Source s) {
        source = s;
        sourceGen++;
    }

    /** Clears the source only if it is still the given one (an old activity going away). */
    void clearSource(Source s) {
        if (source != s) return;
        source = null;
        sourceGen++;
    }

    String state() { return state; }

    /** The port the page is on: the one listened on, else the one asked for; 0 when off. */
    int port() {
        if (!WebPort.allowed(App.context)) return 0;
        int p = port;
        return p != 0 ? p : WebPort.get(App.context);
    }

    /** Woken by {@link #rebind()}: a change in Settings is taken at once, not after a delay. */
    private final Object wake = new Object();
    private boolean woken;

    private boolean sleep(long ms) {
        synchronized (wake) {
            if (!woken) {
                try {
                    wake.wait(ms);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            woken = false;
        }
        return true;
    }

    /** Whether the server has tried its port since the start (it listens, or none was free). */
    boolean settled() {
        return settled;
    }

    /** Bumped by {@link #rebind()}: a socket opened for an older setting is closed again. */
    private volatile int generation;

    /**
     * Takes the port now set in Network (or Allow remote access): the open socket is closed and
     * the next one opened; a wait between tries ends at once. Until the server has tried the new
     * port it is not {@link #settled()}, so a tunnel start waits for the real port (Qodo on #285).
     */
    void rebind() {
        settled = false;
        port = 0;   // until the new one is open, port() is the one now set
        synchronized (wake) {
            generation++;
            woken = true;
            wake.notifyAll();
        }
        ServerSocket s = server;
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // closed already
        }
    }

    /**
     * The set port or, when it is taken, the next free one of {@link WebPort#TRIES}; null when
     * none is free. The reason the set port is taken goes to {@code why[0]}.
     */
    private static ServerSocket open(int wanted, String[] why) {
        for (int p = wanted; p < wanted + WebPort.TRIES && p <= WebPort.MAX; p++) {
            ServerSocket s = null;
            try {
                s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                return s;
            } catch (IOException e) {
                if (p == wanted) why[0] = e.getMessage();
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // not open
                    }
                }
            }
        }
        return null;
    }

    private void acceptLoop() {
        while (true) {
            final int gen = generation;
            if (!WebPort.allowed(App.context)) {
                // Settings → Allow remote access off: no port at all, until it is on again; each
                // wake (a Save) settles again, so a tunnel start waiting for it goes on (Qodo on
                // #287)
                port = 0;
                if (!OFF.equals(state)) Log.i(TAG, "Debug HTTP server: off, remote access is not allowed");
                state = OFF;
                settled = true;
                NetworkPanel.webPortSettled(0);
                if (!sleep(REBIND_DELAY_MS)) return;
                continue;
            }
            int wanted = WebPort.get(App.context);
            String[] why = new String[1];
            ServerSocket opened = open(wanted, why);
            if (opened == null) {
                port = 0;
                int last = Math.min(wanted + WebPort.TRIES - 1, WebPort.MAX);
                state = "ports " + wanted + " to " + last + " unavailable: " + why[0];
                Log.w(TAG, "Debug HTTP server: " + state + ", retrying");
                settled = true;
                NetworkPanel.webPortSettled(wanted);
            } else {
                try (ServerSocket s = opened) {
                    server = s;
                    // a Save while the socket was opening: its close went nowhere, take it now
                    if (generation != gen) {
                        server = null;
                        continue;
                    }
                    port = s.getLocalPort();
                    state = "listening";
                    if (port == wanted) {
                        Log.i(TAG, "Debug HTTP server on port " + port);
                    } else {
                        Log.w(TAG, "Debug HTTP server on port " + port + ": port " + wanted
                                + " is taken (" + why[0] + ")");
                    }
                    settled = true;
                    NetworkPanel.webPortSettled(port);
                    while (true) {
                        Socket c = s.accept();
                        try {
                            workers.execute(() -> handle(c));
                        } catch (RejectedExecutionException busy) {
                            closeQuietly(c);
                        }
                    }
                } catch (IOException e) {
                    server = null;
                    // closed by rebind(): the port set in Network now, or off, at once
                    if (generation != gen) continue;
                    port = 0;
                    state = "port closed: " + e.getMessage();
                    Log.w(TAG, "Debug HTTP server: " + state + ", retrying");
                }
            }
            if (!sleep(REBIND_DELAY_MS)) return;
        }
    }

    private void handle(Socket s) {
        // Socket writes have no timeout of their own: closing the socket ends a blocked write
        ScheduledFuture<?> deadline = deadlines.schedule(() -> closeQuietly(s),
                REQUEST_DEADLINE_MS, TimeUnit.MILLISECONDS);
        try (Socket socket = s) {
            // accepted just before Allow remote access went off: not served (Qodo on #287)
            if (!WebPort.allowed(App.context)) return;
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            if (!NetInfo.isLocalClient(socket.getInetAddress())) {
                reply(out, 403, "text/plain", "Forbidden: local network only\n");
                return;
            }
            StringBuilder headers = new StringBuilder();
            String requestLine = readRequestHead(socket.getInputStream(), headers);
            if (requestLine == null) return;
            if (!authorized(headers.toString(), WebPassword.get())) {
                // a wrong password (not the browser's first try without one) costs a second:
                // guessing goes slowly
                if (hasAuthorization(headers.toString())) {
                    try {
                        Thread.sleep(WRONG_PASSWORD_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                reply(out, 401, "text/plain", "Password needed\n".getBytes(StandardCharsets.UTF_8),
                        "WWW-Authenticate: Basic realm=\"OpenIPC Decoder\", charset=\"UTF-8\"\r\n");
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                reply(out, 400, "text/plain", "Bad request\n");
                return;
            }
            if (!parts[0].equals("GET")) {
                reply(out, 405, "text/plain", "Only GET\n");
                return;
            }
            String path = parts[1];
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
            serve(out, path, query);
        } catch (IOException e) {
            // The client went away or was too slow: nothing to report
        } catch (RuntimeException e) {
            Log.w(TAG, "Debug HTTP request failed", e);
        } finally {
            deadline.cancel(false);
        }
    }

    /** Answers one GET of a LAN client (checked by the caller). */
    void serve(OutputStream out, String path, String query) throws IOException {
        switch (path) {
            case "/":
            case "/index.html":
                reply(out, 200, "text/html; charset=utf-8", PAGE);
                break;
            case "/stats.json":
                reply(out, 200, "application/json; charset=utf-8", toJson(snapshot(), true));
                break;
            case "/snapshot.jpg":
                byte[] jpeg = snapshotJpeg(!("&" + query + "&").contains("&labels=0&"));
                if (jpeg != null) reply(out, 200, "image/jpeg", jpeg);
                else reply(out, 503, "text/plain", "No picture\n");
                break;
            case "/log":
                reply(out, 200, "text/plain; charset=utf-8", log.text());
                break;
            case "/jieli-frame.jpg":
            case "/jieli-dropped.bin":
                // The toy's frames as they came (#187), to see what the picture is made from
                Source src = source;
                String which = path.startsWith("/jieli-frame") ? "frame" : "dropped";
                byte[] raw = src != null ? src.jieliRaw(which) : Jieli.Client.lastRaw(which);
                if (raw != null) {
                    reply(out, 200, path.endsWith(".jpg") ? "image/jpeg"
                            : "application/octet-stream", raw);
                } else {
                    reply(out, 404, "text/plain", "Nothing yet (a jieli:// slot in the single"
                            + " view)\n");
                }
                break;
            case "/report.txt":
                // Downloaded by the browser, not shown (#207)
                Date now = new Date();
                reply(out, 200, "text/plain; charset=utf-8",
                        reportText(now).getBytes(StandardCharsets.UTF_8),
                        "Content-Disposition: attachment; filename=\"" + reportName(now)
                                + "\"\r\n");
                break;
            default:
                reply(out, 404, "text/plain", "Not found\n");
        }
    }

    // ====================== Report (#207) ======================

    /**
     * The report as the app saves it and /report.txt serves it: the same text both ways. Slow
     * (the state is read on the UI thread, up to a timeout): not on the UI thread.
     */
    String reportText(Date now) {
        return reportText(snapshot(), log.text(), now,
                android.os.SystemClock.elapsedRealtime() / 1000);
    }

    /** The report's first line. */
    static final String REPORT_TITLE = "OpenIPC Decoder report";

    /** report-YYYYMMDD-HHMMSS.txt, local time. */
    static String reportName(Date now) {
        return "report-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now) + ".txt";
    }

    /**
     * A header (app, version, git hash, device, Android, time, uptime), then the state as
     * /stats.json has it, then the log ring. Both are redacted already; the whole text is
     * redacted once more, so nothing that slipped in on the way leaves the device.
     */
    static String reportText(Map<String, Object> stats, String log, Date now, long uptimeS) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append(REPORT_TITLE).append('\n');
        sb.append("App: OpenIPC Decoder ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append("), git ")
                .append(BuildConfig.GIT_HASH).append('\n');
        sb.append("Device: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append('\n');
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE).append(" (SDK ")
                .append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("Time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                .format(now)).append('\n');
        sb.append("Uptime: ").append(uptime(uptimeS)).append('\n');
        sb.append("\n== stats.json ==\n");
        sb.append(toJson(stats, true));
        sb.append("\n== log ==\n");
        sb.append(log != null ? log : "");
        return redact(sb.toString());
    }

    /** 1d 2h 3m 4s; the leading zero units left out. */
    static String uptime(long s) {
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (d > 0 || h > 0) sb.append(h).append("h ");
        if (d > 0 || h > 0 || m > 0) sb.append(m).append("m ");
        return sb.append(s % 60).append('s').toString();
    }

    private Map<String, Object> snapshot() {
        Source s = source;
        Map<String, Object> m;
        if (s != null) {
            m = s.snapshot();
        } else {
            m = new java.util.LinkedHashMap<>();
            m.put("app", "OpenIPC Decoder");
            m.put("server", state);
            m.put("mode", "no activity");
            if (App.context != null) m.put("start_on_boot", BootReceiver.state(App.context));
        }
        return m;
    }

    /** Captured only when asked for, one capture at a time, reused for a second. */
    private byte[] snapshotJpeg(boolean labels) {
        int v = labels ? 1 : 0;
        synchronized (snapshotLock) {
            long now = android.os.SystemClock.elapsedRealtime();
            int gen = sourceGen;
            if (lastSnapshot[v] != null && lastSnapshotGen[v] == gen
                    && now - lastSnapshotMs[v] < SNAPSHOT_REUSE_MS) {
                return lastSnapshot[v];
            }
            Source s = source;
            byte[] jpeg = s != null ? s.snapshotJpeg(labels) : null;
            // The activity was replaced or went away meanwhile: that picture is out of date
            if (gen != sourceGen) jpeg = null;
            lastSnapshot[v] = jpeg;
            lastSnapshotGen[v] = gen;
            lastSnapshotMs[v] = android.os.SystemClock.elapsedRealtime();
            return jpeg;
        }
    }

    /** The request line; the headers are read and dropped. Null on a closed connection. */
    static String readRequestHead(InputStream in) throws IOException {
        return readRequestHead(in, null);
    }

    /** The request line; the header lines after it go to {@code headers}, when given. */
    static String readRequestHead(InputStream in, StringBuilder headers) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (head.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) break;
            head.write(b);
            // End of the head: \r\n\r\n (or a bare \n\n)
            if (b == '\n') {
                if (++matched == 2) break;
            } else if (b != '\r') {
                matched = 0;
            }
        }
        String text = head.toString("ISO-8859-1");
        int eol = text.indexOf('\n');
        String line = (eol >= 0 ? text.substring(0, eol) : text).trim();
        if (headers != null && eol >= 0) headers.append(text.substring(eol + 1));
        return line.isEmpty() ? null : line;
    }

    /**
     * Whether a request with these header lines may be served: always without a password
     * (empty), never when the stored one cannot be read (null: locked until it is entered again
     * in Network), otherwise when its Basic credentials carry the password, whatever the user
     * name (the browser asks for both).
     */
    static boolean authorized(String headers, String password) {
        if (password == null) return false;
        if (password.isEmpty()) return true;
        for (String line : headers.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0 || !line.substring(0, colon).trim().equalsIgnoreCase("Authorization")) {
                continue;
            }
            String value = line.substring(colon + 1).trim();
            int space = value.indexOf(' ');
            if (space < 0 || !value.substring(0, space).equalsIgnoreCase("Basic")) return false;
            byte[] pair = base64(value.substring(space + 1).trim());
            if (pair == null) return false;
            String userPass = new String(pair, StandardCharsets.UTF_8);
            int sep = userPass.indexOf(':');
            if (sep < 0) return false;
            // the same time for a wrong password of any length
            return java.security.MessageDigest.isEqual(
                    sha256(userPass.substring(sep + 1)), sha256(password));
        }
        return false;
    }

    /** Whether the request carries an Authorization header. */
    static boolean hasAuthorization(String headers) {
        for (String line : headers.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Authorization")) {
                return true;
            }
        }
        return false;
    }

    private static byte[] sha256(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Standard base64 with or without padding, null when it is not base64 (java.util.Base64
     * needs API 26, android.util.Base64 is a stub in the unit tests).
     */
    static byte[] base64(String s) {
        final String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '=') end--;
        if (s.length() - end > 2) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bits = 0, n = 0;
        for (int i = 0; i < end; i++) {
            int d = abc.indexOf(s.charAt(i));
            if (d < 0) return null;
            bits = (bits << 6) | d;
            n += 6;
            if (n >= 8) {
                n -= 8;
                out.write((bits >> n) & 0xFF);
            }
        }
        if (n >= 6) return null;   // a lone last character carries no byte
        return out.toByteArray();
    }

    private static void reply(OutputStream out, int code, String type, String body)
            throws IOException {
        reply(out, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(OutputStream out, int code, String type, byte[] data)
            throws IOException {
        reply(out, code, type, data, "");
    }

    /** {@code extra}: more header lines, each ending with \r\n. */
    private static void reply(OutputStream out, int code, String type, byte[] data, String extra)
            throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + (code == 405 ? "Allow: GET\r\n" : "")
                + extra
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 503: return "Service Unavailable";
            default:  return "Error";
        }
    }

    private static final java.util.regex.Pattern AUTH_HEADER =
            java.util.regex.Pattern.compile("(?i)(authorization:\\s*\\w+)\\s+\\S.*");
    /** Logins in the URL path or query, e.g. user=admin_password=... (XM cameras). */
    private static final java.util.regex.Pattern USER_PARAM =
            java.util.regex.Pattern.compile("(?i)\\b(user(?:name)?|login|usr)=[^_&;/?\\s]*");

    /** Like the log (#55), plus logins and Authorization headers: never a login or password. */
    static String redact(String s) {
        if (s == null) return null;
        s = RtspSession.redact(s);
        s = USER_PARAM.matcher(s).replaceAll("$1=***");
        return AUTH_HEADER.matcher(s).replaceAll("$1 ***");
    }

    // ====================== JSON ======================

    /** JSON of nested Map / List / array / String / Number / Boolean / null. */
    static String toJson(Object v) {
        return toJson(v, false);
    }

    /** As {@link #toJson(Object)}; pretty: one value per line, indented by two spaces. */
    static String toJson(Object v, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, v, pretty ? 0 : -1);
        if (pretty) sb.append('\n');
        return sb.toString();
    }

    /** {@code depth} < 0: compact. */
    private static void writeJson(StringBuilder sb, Object v, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(String.format(Locale.US, "%.2f", d));
        } else if (v instanceof Map || v instanceof List || v instanceof Object[]) {
            boolean map = v instanceof Map;
            java.util.Collection<?> items = map ? ((Map<?, ?>) v).entrySet()
                    : v instanceof List ? (List<?>) v : java.util.Arrays.asList((Object[]) v);
            sb.append(map ? '{' : '[');
            boolean first = true;
            for (Object o : items) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, depth + 1, depth);
                if (map) {
                    Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                    writeString(sb, String.valueOf(e.getKey()));
                    sb.append(depth < 0 ? ":" : ": ");
                    o = e.getValue();
                }
                writeJson(sb, o, depth < 0 ? -1 : depth + 1);
            }
            if (!first) newline(sb, depth, depth);
            sb.append(map ? '}' : ']');
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (depth < 0) return;
        sb.append('\n');
        for (int i = 0; i < indent; i++) sb.append("  ");
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    // ====================== Page ======================

    /**
     * Static page: a one-line header (state, version, device time, links, snapshot interval)
     * and the snapshot filling the rest with the text of each cell drawn over it; on a narrow
     * screen the snapshot takes the full width and the cells are listed below it (#103). It
     * fetches /stats.json every 1.5 s and the snapshot (without labels) every 3, 5 or 10 s,
     * both only while the tab is visible (#98). The Info switch hides the text over the cells
     * and the list, leaving the header and the snapshot (#141). In single view the grid stays,
     * with the camera on screen framed and the stream checks of the other slots (#100).
     * Served as one line: no newlines and no optional spaces, every JS statement ends with ';'
     * or '}', so the lines can be joined (#181).
     */
    private static final String PAGE = "<!doctype html>"
            + "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\""
            + " content=\"width=device-width, initial-scale=1\"><title>Decoder</title>"
            + "<link id=\"fav\" rel=\"icon\" href=\"data:,\">"
            + "<style>"
            + "html,body{height:100%;margin:0}"
            + "body{display:flex;flex-direction:column;background:#111;color:#ddd;font:14px/1.35"
            + " system-ui,sans-serif}"
            + "header{display:flex;flex-wrap:wrap;align-items:center;gap:6px 18px;padding:8px"
            + " 12px;background:#181818;border-bottom:1px solid #262626}"
            + ".brand{display:flex;align-items:center;gap:8px;white-space:nowrap}"
            + ".brand b{font-size:17px;font-weight:600;color:#fff}"
            + ".dot{width:9px;height:9px;border-radius:50%;background:#555;flex:none}"
            + ".dot.ok{background:#3c3}.dot.warn{background:#fb4}.dot.bad{background:#f55}"
            + ".meta{display:flex;flex-wrap:wrap;gap:4px 14px;color:#999;font-size:13px;"
            + "font-variant-numeric:tabular-nums}"
            + ".meta span{white-space:nowrap}#err{color:#ff5a5a}"
            // The controls wrap as groups on a narrow screen, none goes off it (Qodo on #142)
            + "nav{display:flex;flex-wrap:wrap;align-items:center;gap:6px 10px;margin-left:auto;"
            + "font-size:13px;color:#999}nav>*{white-space:nowrap}"
            + ".btn,.seg button{font:inherit;color:#cde;background:#222;border:1px solid #363636;"
            + "border-radius:6px;padding:3px 10px;text-decoration:none;cursor:pointer}"
            + ".btn:hover,.seg button:hover{background:#2c2c2c}"
            + ".seg{display:inline-flex;margin-left:4px}.seg button{border-radius:0;"
            + "margin-left:-1px}"
            + ".seg button:first-child{border-radius:6px 0 0 6px}.seg"
            + " button:last-child{border-radius:0 6px 6px 0}"
            + ".seg button.on{background:#2f4a66;border-color:#3d6488;color:#fff}"
            + "main{flex:1;min-height:0;display:flex;flex-direction:column;align-items:center;"
            + "padding:8px;gap:8px;overflow:auto}"
            + "#wall{position:relative;background:#000;flex:none}"
            + "#snap,#cells{position:absolute;top:0;left:0;width:100%;height:100%}"
            + "#cells{display:grid}"
            + ".cell{position:relative;border:1px solid #333;overflow:hidden;container-type:size}"
            + ".top,.bot{position:absolute;left:0;right:0;padding:.3em .6em;font-size:clamp(9px,"
            + "3.4cqw,13px);color:#fff;text-shadow:0 0 3px #000;white-space:nowrap;"
            + "overflow:hidden;text-overflow:ellipsis}"
            + ".top{top:0;background:linear-gradient(rgba(0,0,0,.65),transparent)}"
            + ".bot{bottom:0;background:linear-gradient(transparent,rgba(0,0,0,.75))}"
            + ".slot{font-weight:700;margin-right:6px}.dim{color:#bbb}"
            + ".bad{color:#ff5a5a;font-weight:700}.warn{color:#ffb84d}"
            + ".off{position:absolute;inset:0;display:flex;flex-direction:column;"
            + "align-items:center;justify-content:center;background:#000;color:#ff5a5a;"
            + "text-align:center;padding:8px;font-size:clamp(9px,4cqw,14px)}"
            + ".off b{font-size:clamp(16px,14cqh,30px);color:#666}"
            // Single view (#100): the camera on screen framed, a stream found by a check green
            + ".cell.on{border:2px solid #3d8bff}.off.ok,.ok{color:#7fd07f}.off.none{color:#888}"
            + "#list{display:none;width:100%;border-collapse:collapse;font-size:13px}"
            + "#list td{padding:5px 6px;border-top:1px solid #262626;vertical-align:top}"
            + "#list td:first-child{font-weight:700;width:1.5em;color:#fff}"
            + "#list .h{color:#bbb}"
            + "body.small #list{display:table}"
            + "body.small .cell .top .dim,body.small .cell .bot,body.small .off span{display:none}"
            // Info off (#141): the snapshot's cells alone, no text over them, no list below
            + "body.clean .cell>*,body.clean #list{display:none!important}"
            + "body.clean .cell{border-color:transparent}"
            // brass 1.3.0 of the brand project (#286): the shared look of our apps' pages
            + ":root{--panel:#2c2119;--panel2:#3d2d22;--brass:#c9a24d;--brass2:#8c6a2a;--amber:#ffb347;"
            + "--ink:#e8d9b8;--dim:#b09873;--red:#c8452c;--green:#5ad38f;--foot:#a08a69}*{box-sizing:bo"
            + "rder-box}body.brass{display:block;height:auto;min-height:100%;background:radial-gradient"
            + "(ellipse at 50% 0,#3b2c22 0,#1a1512 55%,#0f0b09 100%);color:var(--ink);font-family:Georg"
            + "ia,'Times New Roman',serif;padding:28px 12px;text-align:center}.brass h1{font-size:clamp"
            + "(22px,4.5vw,40px);letter-spacing:.2em;text-transform:uppercase;margin:0 0 6px;color:var("
            + "--brass);text-shadow:0 1px 0 #f3d78a,0 -1px 0 #3d2a0c,0 0 22px rgba(201,162,77,.35);font"
            + "-weight:400}.ttl{position:relative;display:inline-block}.ttl .ver{position:absolute;left"
            + ":100%;bottom:.24em;margin-left:.6em;font:12px/1 'Courier New',monospace;letter-spacing:."
            + "12em;color:var(--dim);text-shadow:none;white-space:nowrap}.brass .sub{font-family:'Couri"
            + "er New',monospace;font-size:12px;color:var(--dim);letter-spacing:.3em;text-transform:upp"
            + "ercase;margin-bottom:24px}.brass .panel{position:relative;width:min(1200px,100%);margin:"
            + "0 auto;text-align:left;background:linear-gradient(180deg,var(--panel2),var(--panel));bor"
            + "der:3px solid var(--brass2);border-radius:16px;box-shadow:inset 0 0 0 2px #1a1210,inset "
            + "0 0 50px rgba(0,0,0,.6),0 24px 48px rgba(0,0,0,.65);padding:30px 24px 24px}.rivet{positi"
            + "on:absolute;width:14px;height:14px;border-radius:50%;background:radial-gradient(circle a"
            + "t 35% 35%,#f1d38b,#8c6a2a 60%,#3a2a10)}.rivet.tl{top:8px;left:8px}.rivet.tr{top:8px;righ"
            + "t:8px}.rivet.bl{bottom:8px;left:8px}.rivet.br{bottom:8px;right:8px}.plate{font-family:'C"
            + "ourier New',monospace;font-size:12px;letter-spacing:.2em;text-transform:uppercase;color:"
            + "#2a1c10;background:linear-gradient(180deg,#e2c377,#a88237);border:1px solid #5b4416;bord"
            + "er-radius:4px;padding:4px 10px;display:inline-block}.brass .top{position:static;display:"
            + "flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between;background"
            + ":none;padding:0;font-size:inherit;text-shadow:none;color:inherit}.brass nav{display:flex"
            + ";flex-wrap:wrap;align-items:center;gap:8px;margin:0;font-family:'Courier New',monospace;"
            + "font-size:12px;color:var(--dim);letter-spacing:.12em;text-transform:uppercase}.bb,.brass"
            + " .seg button{font:12px Georgia,serif;letter-spacing:.15em;text-transform:uppercase;color"
            + ":#2a1c10;background:linear-gradient(180deg,#e9cd84,#b08b3c 55%,#7c5c1f);border:0;border-"
            + "radius:8px;padding:6px 12px;box-shadow:0 3px 0 #4a3510;text-decoration:none;cursor:point"
            + "er}.brass .seg{display:inline-flex;gap:4px;margin:0}.brass .seg button{border-radius:8px"
            + ";margin:0}.brass .seg button.on{background:linear-gradient(180deg,#f6dc95,#c99d45 55%,#8"
            + "a6621);box-shadow:0 1px 0 #4a3510,inset 0 0 10px rgba(255,180,60,.55);color:#2a1c10}.lab"
            + "{margin-left:6px}.board{margin-top:18px;padding:10px 16px;border:2px solid #574123;borde"
            + "r-radius:8px;background:#120d0a;box-shadow:inset 0 0 20px rgba(0,0,0,.8);font-family:'Co"
            + "urier New',monospace;font-size:14px}.board .r{display:grid;grid-template-columns:22px 12"
            + "0px minmax(0,1fr);align-items:center;padding:5px 0;gap:0 6px}.board .bulb{width:14px;hei"
            + "ght:14px;border-radius:50%;border:1px solid #6e5320;background:#2a2118}.board .bulb.ok{b"
            + "ackground:radial-gradient(circle at 40% 35%,#d8ffe8,var(--green) 45%,#155b34);box-shadow"
            + ":0 0 10px var(--green)}.board .bulb.warn{background:radial-gradient(circle at 40% 35%,#f"
            + "ff3c4,var(--amber) 45%,#a24d0a);box-shadow:0 0 10px var(--amber)}.board .bulb.bad{backgr"
            + "ound:radial-gradient(circle at 40% 35%,#ffd0c4,var(--red) 45%,#5a1a0e)}.board .bulb.none"
            + "{visibility:hidden}.board .k{color:var(--dim);letter-spacing:.15em;text-transform:upperc"
            + "ase;font-size:12px}.board .v{color:var(--ink);letter-spacing:.06em;text-transform:upperc"
            + "ase}.brass main{display:flex;flex-direction:column;align-items:center;padding:0;margin-t"
            + "op:18px;overflow:visible}.brass #wall{border:2px solid #574123;border-radius:8px;overflo"
            + "w:hidden;box-shadow:inset 0 0 20px rgba(0,0,0,.8)}.brass .cell{border-color:#574123}.bra"
            + "ss .cell.on{border:2px solid var(--amber)}.brass .top.ct,.brass .cell .top{position:abso"
            + "lute;display:block;font-family:'Courier New',monospace}.brass .cell .slot{color:var(--am"
            + "ber)}.brass .ok{color:var(--green)}.brass .bad{color:var(--red)}.brass .warn{color:var(-"
            + "-amber)}.brass .off b{color:#6e5a3c}.brass #list td{border-top-color:#574123;color:var(-"
            + "-dim);font-family:'Courier New',monospace}.foot{margin-top:18px;font-family:'Courier New"
            + "',monospace;font-size:11px;color:var(--foot);letter-spacing:.2em;text-transform:uppercas"
            + "e;text-align:center}@media (max-width:620px){.ttl .ver{bottom:.1em}.sub .br{display:bloc"
            + "k}.board{padding:8px 12px;font-size:12px}.board .r{grid-template-columns:20px 74px minma"
            + "x(0,1fr)}.board .k{font-size:11px;letter-spacing:.1em}.brass .panel{padding:26px 14px 18"
            + "px}}"
            + ".board .bulb.dim{background:#2a2118}.brass nav .g{display:inline-flex;align-items:center;gap:6px}"
            + "</style></head><body class=\"brass\">"
            + "<h1 class=\"ttl\">Decoder<span class=\"ver\" id=\"ver2\"></span></h1>"
            + "<div class=\"sub\">Elegant <span class=\"br\">video player</span></div>"
            + "<div class=\"panel\"><i class=\"rivet tl\"></i><i class=\"rivet tr\"></i>"
            + "<i class=\"rivet bl\"></i><i class=\"rivet br\"></i>"
            + "<div class=\"top\"><span class=\"plate\">Video wall</span>"
            + "<nav><a class=\"bb\" href=\"/stats.json\">JSON</a><a class=\"bb\" href=\"/log\">"
            + "Log</a><a class=\"bb\" href=\"/report.txt\" download"
            + " title=\"Header, stats.json and the log in one file\">Report</a>"
            // a label wraps with its switch, never alone at the end of a line
            + "<span class=\"g\"><span class=\"lab\">Info</span><span class=\"seg\" id=\"info\">"
            + "<button value=\"1\">On</button><button value=\"0\">Off</button></span></span>"
            + "<span class=\"g\"><span class=\"lab\">Snapshot</span><span class=\"seg\" id=\"every\">"
            + "<button value=\"3\">3 s</button><button value=\"5\">5 s</button>"
            + "<button value=\"10\">10 s</button></span></span></nav></div>"
            // the board (#286): a row with a lamp for the app, the cameras, the screen, camex
            + "<div class=\"board\" id=\"board\"></div>"
            + "<div hidden><span id=\"dot\"></span><span id=\"ver\"></span><span id=\"clock\">"
            + "</span><span id=\"up\"></span><span id=\"err\"></span></div>"
            + "<main id=\"main\"><div id=\"wall\"><img id=\"snap\" alt=\"\"><div id=\"cells\">"
            + "</div></div><table id=\"list\"></table></main></div>"
            + "<div class=\"foot\">Decoder \u00b7 OpenIPC \u00b7 Prosperity Public License 3.0.0</div>"
            + "<script>"
            + "function $(id){return document.getElementById(id)}"
            + "function esc(s){return String(s==null?'':s).replace(/[&<>\"]/g,function(c){return"
            + "{'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]})}"
            // The wall keeps 16:9 (the snapshot's shape) and fills the space left; on a narrow
            // screen it takes the full width and the cells are listed below it
            // the wall fits the window's height below the board too (Qodo on #287)
            + "function fit(){var m=$('main'),w=m.clientWidth,wall=$('wall'),"
            + "h=Math.max(180,innerHeight-(wall.getBoundingClientRect().top+scrollY)-70),small=w<720;"
            + "document.body.classList.toggle('small',small);"
            + "if(!small&&w*9/16>h)w=h*16/9;$('wall').style.width=Math.floor(w)+'px';"
            + "$('wall').style.height=Math.floor(w*9/16)+'px'}"
            + "window.onresize=fit;fit();"
            + "function later(f,ms){setTimeout(function(){if(document.hidden)document.addEventLis"
            + "tener('visibilitychange',function v(){if(!document.hidden){document.removeEventLis"
            + "tener('visibilitychange',v);f()}});else f()},ms)}"
            + "function up(s){var d=Math.floor(s/86400),h=Math.floor(s%86400/3600),"
            + "m=Math.floor(s%3600/60);return(d?d+'d ':'')+(d||h?h+'h ':'')+m+'m'}"
            + "function host(u){var m=/\\/\\/(?:[^@\\/]*@)?(\\[[^\\]]*\\]|[^\\/:?]*)/.exec(u||'');"
            + "return m?m[1]:''}"
            + "function num(v){return Math.round(v*10)/10}"
            + "function where(c){return esc(host(c.url))+(c.transport?' \u00b7 '+"
            + "esc(c.transport):'')+(c.audio===true?' \u00b7 \u266a':'')}"
            + "function ok(c){return c.probe&&c.probe.state==='ok'}"
            + "function info(c){if(!c.video)return'<span class=\"'+(ok(c)?'ok':'bad')+'\">'+"
            + "esc(c.text)+'</span>';"
            + "var t=esc(c.text),r=/(\\d+)\\/(\\d+) fps/.exec(c.text||'');"
            + "if(r&&+r[1]<0.9*r[2])t=t.replace(r[0],'<span class=\"warn\">'+r[0]+'</span>');"
            + "if(c.lost_fps>0)t+=' \u00b7 <span class=\"bad\">lost '+num(c.lost_fps)"
            + "+'/s</span>';"
            + "return t}"
            // One cell: slot, camera and transport on top; size, fps and losses below
            + "function cell(c){var top='<span class=\"slot\">'+esc(c.slot)+'</span><span"
            + " class=\"dim\">'+where(c)+(c.on_screen?' \u00b7 on screen':'')+'</span>',"
            + "cls=c.on_screen?'cell on':'cell';"
            + "if(!c.video)return'<div class=\"'+cls+'\"><div class=\"off'+(ok(c)?' ok':'')"
            + "+(c.text==='" + StreamProbes.NOT_CONFIGURED + "'?' none':'')+'\">"
            + "<b>'+esc(c.slot)+'</b><span>'+esc(c.text)+'</span></div><div class=\"top\">'+top+"
            + "'</div></div>';"
            + "return'<div class=\"'+cls+'\"><div class=\"top\">'+top+'</div><div"
            + " class=\"bot\">'+info(c)+'</div></div>'}"
            + "function row(c){return'<tr><td>'+esc(c.slot)+'</td><td><span class=\"h\">"
            + "'+where(c)+'</span><br>'+info(c)+'</td></tr>'}"
            // the board and the tab (#286): the worst lamp is the tab's title and icon
            + "function lampColor(c){return{ok:'#5ad38f',warn:'#ffb347',bad:'#c8452c'}[c]||'#b09873'}"
            + "function brow(c,k,v){return'<div class=\"r\"><span class=\"bulb '+(c||'none')+'\"></span><"
            + "span class=\"k\">'+k+'</span><span class=\"v\">'+esc(v)+'</span></div>'}"
            + "function board(s){var rows=[],all='ok';function worst(c){if(c==='bad')all='bad';else if(c="
            + "=='warn'&&all!=='bad')all='warn'}"
            + "if(!s){rows.push(brow('bad','Decoder','no answer'));all='bad'}else{"
            + "var stuck=s.decoder_stuck===true,ui=s.ui_thread&&s.ui_thread!=='ok',none=!s.ui_thread,dc=s"
            + "tuck?'bad':ui||none?'warn':'ok';worst(dc);"
            + "rows.push(brow(dc,'Decoder',stuck?'video decoder not responding \u2014 restart the device'"
            + ":ui?'UI '+s.ui_thread:none?(s.mode||'no data'):'running \u00b7 up '+up(s.uptime_s)));"
            + "var cs=s.slots||s.cells||(s.single?[s.single]:[]),conf=0,live=0,bad=0;cs.forEach(function(c){if("
            + "!c||c.slot===''||c.slot==null||c.text==='" + StreamProbes.NOT_CONFIGURED + "')return;if(!c"
            // no answer: a failed check, or a cell of the grid without video; a camera not
            // checked now (UDP push, checks on hold) is told apart (Qodo on #287)
            + ".url&&!c.video)return;conf++;if(c.video||ok(c))live++;else if(!c.probe||c.probe.state==="
            + "'error')bad++});var unk=conf-live-bad;"
            + "var cc=!conf?'dim':bad?'warn':'ok';worst(cc);rows.push(brow(cc,'Cameras',!conf?'none"
            + " configured':live+' of '+conf+' live'+(bad?' \u00b7 '+bad+' no answer':'')+(unk?' \u00b7 '"
            + "+unk+' not checked':'')))"
            + ";"
            + "var n=(s.cells||[]).length,side=n>4?3:2,m=s.mode;rows.push(brow('','Screen',m==='paused'?'"
            + "app in background':m==='udp'?'U view':m==='single'?'camera '+s.active_slot+' on screen':m="
            + "=='slides'?'slides \u00b7 grid '+side+'\u00d7'+side:m==='multi-view'?'grid '+side+'\u00d7'"
            + "+side:(m||'')));"
            + "var x=s.camex,xs=x&&x.state;if(!x||!x.on)rows.push(brow('dim','Camex','off'));else{var xc="
            + "xs==='connected'?'ok':xs==='failed'?'bad':'warn';worst(xc);"
            + "rows.push(brow(xc,'Camex',xs==='connected'?'connected'+(x.address?' \u00b7 '+x.address:'')"
            + ":xs==='failed'?'failed'+(x.error?' \u00b7 '+x.error:''):xs==='needs_consent'?'VPN consent "
            + "needed':xs==='waiting'?'waiting for the VPN \u00b7 another app holds it'"
            + ":xs==='connecting'?'connecting'+(x.error?' \u00b7 '+x.error:''):(xs||'stopped')))}}"
            + "$('board').innerHTML=rows.join('');$('ver2').textContent=s&&s.version?s.version:'';"
            + "document.title={ok:'Running',warn:'Attention',bad:'Error'}[all]+' \u00b7 Decoder';"
            + "$('fav').href='data:image/svg+xml,'+encodeURIComponent('<svg xmlns=\"http://www.w3.org/200"
            + "0/svg\" viewBox=\"0 0 16 16\"><circle cx=\"8\" cy=\"8\" r=\"6.5\" stroke=\"#6e5320\" fill="
            + "\"'+lampColor(all)+'\"/></svg>')}"
            + "function render(s){board(s);"
            + "$('ver').textContent='v'+(s.version||'?')+' \u00b7 '+(s.git||'');"
            + "$('clock').textContent=s.time||'';$('up').textContent=s.uptime_s!=null?'up '+"
            + "up(s.uptime_s):'';"
            // Orange: the UI thread is stuck, or there is no activity behind the server at all
            + "var ui=s.ui_thread&&s.ui_thread!=='ok',none=!s.ui_thread;"
            // Red: frames arrive, the device's decoder gives none; only a restart helps (#145)
            + "var stuck=s.decoder_stuck===true;"
            + "$('err').textContent=stuck?'video decoder not responding \u2014 restart the device'"
            + ":ui?'UI '+s.ui_thread:none?(s.mode||'no data'):'';"
            + "$('dot').className='dot '+(stuck?'bad':ui||none?'warn':'ok');"
            + "var list,side=1;"
            + "if(s.mode==='paused'||!(s.cells||s.single||s.slots))"
            + "list=[{text:s.mode==='paused'?'App in background':(s.mode||'')}];"
            // Single and U view (#100): the grid with the camera on screen and the checks of
            // the others
            + "else if(s.slots){list=s.slots.slice();side=list.length>4?3:2}"
            // The U view is the U cell alone; the 3x3 grid has it as its ninth cell (#87)
            + "else if(s.cells){side=s.mode==='udp'?1:s.cells.length>4?3:2;list=s.cells.slice();"
            + "while(list.length<side*side)list.push({slot:'',text:''})}"
            + "else if(s.single){var c=s.single;c.slot=s.active_slot;"
            + "c.text=c.video?(c.size||'')+' '+c.codec:c.status;list=[c]}"
            + "$('cells').style.gridTemplateColumns='repeat('+side+',1fr)';"
            + "$('cells').style.gridTemplateRows='repeat('+side+',1fr)';"
            + "$('cells').innerHTML=list.map(cell).join('');$('list').innerHTML=list.map(row).joi"
            + "n('')}"
            + "function tick(){fetch('/stats.json',{cache:'no-store'}).then(function(r){return"
            + " r.json()}).then(function(s){render(s)})"
            + ".catch(function(e){$('err').textContent='offline';$('dot').className='dot bad';board(null)}"
            + ").finally(function(){later(tick,1500)})}"
            + "var every=5,seg=$('every');try{every=+localStorage.getItem('every')||5}catch(x){}"
            + "function mark(){[].forEach.call(seg.children,function(b){b.className=+b.value===ev"
            + "ery?'on':''})}"
            + "seg.onclick=function(e){if(!e.target.value)return;every=+e.target.value;mark();"
            + "try{localStorage.setItem('every',every)}catch(x){}};mark();"
            + "var img=$('snap');img.onload=img.onerror=function(){later(snap,every*1000)};"
            + "function snap(){img.src='/snapshot.jpg?labels=0&t='+Date.now()}"
            // Info on or off (#141), remembered like the interval; Off until chosen: the page
            // opens on the clean picture, every 5 s
            + "var shown=0,iseg=$('info');try{shown=localStorage.getItem('info')==='1'?1:0}catch(x){}"
            + "function imark(){[].forEach.call(iseg.children,function(b){b.className=+b.value===sh"
            + "own?'on':''});document.body.classList.toggle('clean',!shown)}"
            + "iseg.onclick=function(e){if(!e.target.value)return;shown=+e.target.value;imark();"
            + "try{localStorage.setItem('info',shown)}catch(x){}};imark();"
            + "later(tick,0);later(snap,0);"
            + "</script></body></html>";
}
