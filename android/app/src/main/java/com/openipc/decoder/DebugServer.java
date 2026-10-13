/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * DebugServer.java — read-only HTTP server with the debug information, LAN clients only (#96)
 *
 */

package com.openipc.decoder;

import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Serves what the decoder knows about itself on port 8085, for a browser or curl:
 * {@code /} a page refreshing itself, {@code /stats.json} the same as JSON, {@code /log} the
 * app's recent log. Always on; a client outside the local network gets 403. GET only, one
 * request per connection, short timeouts and a few workers: it must never get in the way of
 * decoding. Logins and passwords never leave the device (redacted like the log, #55).
 */
final class DebugServer {
    private static final String TAG = "OpenIPCDecoder";
    static final int PORT = 8085;
    /** Lines of the app's log served by /log. */
    static final int LOG_LINES = 500;
    private static final int SOCKET_TIMEOUT_MS = 3000;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final long REBIND_DELAY_MS = 10_000;

    /** What the server reports; called on a server thread. */
    interface Source {
        /** State as nested Map / List / String / Number / Boolean, see {@link #toJson}. */
        Map<String, Object> snapshot();
    }

    private static DebugServer instance;

    private volatile Source source;
    /** "listening" or why the port could not be opened, for the debug text. */
    private volatile String state = "starting";
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), r -> {
                Thread t = new Thread(r, "debug-http");
                t.setDaemon(true);
                return t;
            });

    private DebugServer() {}

    /** The one server of the process, started on first use and kept for its lifetime. */
    static synchronized DebugServer get() {
        if (instance == null) {
            instance = new DebugServer();
            Thread t = new Thread(instance::acceptLoop, "debug-http-accept");
            t.setDaemon(true);
            t.start();
        }
        return instance;
    }

    void setSource(Source s) { source = s; }

    /** Clears the source only if it is still the given one (an old activity going away). */
    void clearSource(Source s) {
        if (source == s) source = null;
    }

    String state() { return state; }

    private void acceptLoop() {
        while (true) {
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                state = "listening";
                Log.i(TAG, "Debug HTTP server on port " + PORT);
                while (true) {
                    Socket s = server.accept();
                    try {
                        workers.execute(() -> handle(s));
                    } catch (RejectedExecutionException busy) {
                        closeQuietly(s);
                    }
                }
            } catch (IOException e) {
                state = "port " + PORT + " unavailable: " + e.getMessage();
                Log.w(TAG, "Debug HTTP server: " + state + ", retrying");
            }
            try {
                Thread.sleep(REBIND_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            if (!NetInfo.isLocalClient(socket.getInetAddress())) {
                reply(out, 403, "text/plain", "Forbidden: local network only\n");
                return;
            }
            String requestLine = readRequestHead(socket.getInputStream());
            if (requestLine == null) return;
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
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            switch (path) {
                case "/":
                case "/index.html":
                    reply(out, 200, "text/html; charset=utf-8", PAGE);
                    break;
                case "/stats.json":
                    reply(out, 200, "application/json; charset=utf-8", toJson(snapshot()));
                    break;
                case "/log":
                    reply(out, 200, "text/plain; charset=utf-8", readLog(LOG_LINES));
                    break;
                default:
                    reply(out, 404, "text/plain", "Not found\n");
            }
        } catch (IOException e) {
            // The client went away or was too slow: nothing to report
        } catch (RuntimeException e) {
            Log.w(TAG, "Debug HTTP request failed", e);
        }
    }

    private Map<String, Object> snapshot() {
        Source s = source;
        Map<String, Object> m;
        if (s != null) {
            m = s.snapshot();
        } else {
            m = new java.util.LinkedHashMap<>();
            m.put("mode", "no activity");
        }
        m.put("server", state);
        return m;
    }

    /** The request line; the headers are read and dropped. Null on a closed connection. */
    static String readRequestHead(InputStream in) throws IOException {
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
        return line.isEmpty() ? null : line;
    }

    private static void reply(OutputStream out, int code, String type, String body)
            throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + (code == 405 ? "Allow: GET\r\n" : "")
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            default:  return "Error";
        }
    }

    /**
     * The last lines of the app's log. Without READ_LOGS logcat shows only this app's own
     * lines; each one is redacted, as the RTSP layer may have logged a URL or a header.
     */
    static String readLog(int lines) {
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", String.valueOf(lines))
                    .redirectErrorStream(true).start();
            ArrayDeque<String> tail = new ArrayDeque<>(lines);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (tail.size() == lines) tail.removeFirst();
                    tail.addLast(line);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String line : tail) sb.append(redact(line)).append('\n');
            return sb.toString();
        } catch (IOException e) {
            return "Cannot read the log: " + e.getMessage() + "\n";
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static final java.util.regex.Pattern AUTH_HEADER =
            java.util.regex.Pattern.compile("(?i)(authorization:\\s*\\w+)\\s+\\S.*");

    /** Like the log (#55), plus Authorization headers: never a login or password. */
    static String redact(String s) {
        if (s == null) return null;
        s = RtspSession.redact(s);
        return AUTH_HEADER.matcher(s).replaceAll("$1 ***");
    }

    // ====================== JSON ======================

    /** JSON of nested Map / List / array / String / Number / Boolean / null. */
    static String toJson(Object v) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, v);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(String.format(Locale.US, "%.2f", d));
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeJson(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Object[]) {
            writeJson(sb, java.util.Arrays.asList((Object[]) v));
        } else {
            writeString(sb, String.valueOf(v));
        }
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

    /** Static page: it fetches /stats.json every 1.5 s and renders it, no server templating. */
    private static final String PAGE = "<!doctype html>\n"
            + "<html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>OpenIPC Decoder</title><style>"
            + "body{font:14px/1.4 system-ui,sans-serif;margin:16px;background:#111;color:#ddd}"
            + "h1{font-size:18px;margin:0 0 8px}h2{font-size:15px;margin:16px 0 6px}"
            + "table{border-collapse:collapse;width:100%}"
            + "td,th{border-bottom:1px solid #333;padding:4px 8px;text-align:left;"
            + "vertical-align:top;word-break:break-all}"
            + "th{color:#999;font-weight:normal;white-space:nowrap;width:1%}"
            + "a{color:#8cf}#err{color:#f88}"
            + "</style></head><body>"
            + "<h1>OpenIPC Decoder</h1>"
            + "<div><a href=\"/stats.json\">stats.json</a> · <a href=\"/log\">log</a>"
            + " <span id=\"err\"></span></div>"
            + "<div id=\"out\"></div>"
            + "<script>\n"
            + "function esc(s){return String(s).replace(/[&<>\"]/g,function(c){"
            + "return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]})}\n"
            + "function val(v){if(v===null||v===undefined)return '';"
            + "if(Array.isArray(v))return v.map(val).join('<br>');"
            + "if(typeof v==='object')return table(v);return esc(v)}\n"
            + "function table(o){var h='<table>';for(var k in o){"
            + "if(k==='cells')continue;h+='<tr><th>'+esc(k)+'</th><td>'+val(o[k])+'</td></tr>'}"
            + "return h+'</table>'}\n"
            + "function cells(c){if(!c||!c.length)return '';var keys=[];"
            + "c.forEach(function(r){for(var k in r)if(keys.indexOf(k)<0)keys.push(k)});"
            + "var h='<h2>Cells</h2><table><tr>'+keys.map(function(k){return '<th>'+esc(k)+'</th>'})"
            + ".join('')+'</tr>';c.forEach(function(r){h+='<tr>'+keys.map(function(k){"
            + "return '<td>'+val(r[k])+'</td>'}).join('')+'</tr>'});return h+'</table>'}\n"
            + "function tick(){fetch('/stats.json',{cache:'no-store'}).then(function(r){return r.json()})"
            + ".then(function(s){document.getElementById('out').innerHTML=table(s)+cells(s.cells);"
            + "document.getElementById('err').textContent=''})"
            + ".catch(function(e){document.getElementById('err').textContent='offline: '+e})"
            + ".finally(function(){setTimeout(tick,1500)})}\n"
            + "tick();\n"
            + "</script></body></html>\n";
}
