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

    /** What the server reports; called on a server thread. */
    interface Source {
        /** State as nested Map / List / String / Number / Boolean, see {@link #toJson}. */
        Map<String, Object> snapshot();

        /**
         * JPEG of what the screen shows (#98), or null when there is nothing to show. Without
         * labels: only the frames, for the page that draws the text over them.
         */
        byte[] snapshotJpeg(boolean labels);
    }

    private static DebugServer instance;

    private volatile Source source;
    /** "listening" or why the port could not be opened, for the debug text. */
    private volatile String state = "starting";
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

    private DebugServer() {}

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
        // Socket writes have no timeout of their own: closing the socket ends a blocked write
        ScheduledFuture<?> deadline = deadlines.schedule(() -> closeQuietly(s),
                REQUEST_DEADLINE_MS, TimeUnit.MILLISECONDS);
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
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
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
                default:
                    reply(out, 404, "text/plain", "Not found\n");
            }
        } catch (IOException e) {
            // The client went away or was too slow: nothing to report
        } catch (RuntimeException e) {
            Log.w(TAG, "Debug HTTP request failed", e);
        } finally {
            deadline.cancel(false);
        }
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
        reply(out, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(OutputStream out, int code, String type, byte[] data)
            throws IOException {
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
     * both only while the tab is visible (#98).
     */
    private static final String PAGE = "<!doctype html>\n"
            + "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\""
            + " content=\"width=device-width, initial-scale=1\"><title>OpenIPC Decoder</title>"
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
            + "nav{display:flex;align-items:center;gap:6px;margin-left:auto;white-space:nowrap;"
            + "font-size:13px;color:#999}"
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
            + "#list{display:none;width:100%;border-collapse:collapse;font-size:13px}"
            + "#list td{padding:5px 6px;border-top:1px solid #262626;vertical-align:top}"
            + "#list td:first-child{font-weight:700;width:1.5em;color:#fff}"
            + "#list .h{color:#bbb}"
            + "body.small #list{display:table}"
            + "body.small .cell .top .dim,body.small .cell .bot,body.small .off span{display:none}"
            + "</style></head><body>"
            + "<header>"
            + "<div class=\"brand\"><span id=\"dot\" class=\"dot\"></span><b>OpenIPC Decoder</b>"
            + "</div>"
            + "<div class=\"meta\"><span id=\"ver\"></span><span id=\"clock\"></span><span"
            + " id=\"up\"></span><span id=\"err\"></span></div>"
            + "<nav><a class=\"btn\" href=\"/stats.json\">JSON</a><a class=\"btn\" href=\"/log\">"
            + "Log</a>"
            + "<span>Snapshot<span class=\"seg\" id=\"every\"><button value=\"3\">3 s</button>"
            + "<button value=\"5\">5 s</button><button value=\"10\">10 s</button></span></span>"
            + "</nav>"
            + "</header>"
            + "<main id=\"main\"><div id=\"wall\"><img id=\"snap\" alt=\"\"><div id=\"cells\">"
            + "</div></div><table id=\"list\"></table></main>"
            + "<script>\n"
            + "function $(id){return document.getElementById(id)}\n"
            + "function esc(s){return String(s==null?'':s).replace(/[&<>\"]/g,function(c){return"
            + " {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]})}\n"
            // The wall keeps 16:9 (the snapshot's shape) and fills the space left; on a narrow
            // screen it takes the full width and the cells are listed below it
            + "function fit(){var m=$('main'),w=m.clientWidth-16,h=m.clientHeight-16,small=w<720;\n"
            + "document.body.classList.toggle('small',small);\n"
            + "if(!small&&w*9/16>h)w=h*16/9;$('wall').style.width=Math.floor(w)+'px';"
            + "$('wall').style.height=Math.floor(w*9/16)+'px'}\n"
            + "window.onresize=fit;fit();\n"
            + "function later(f,ms){setTimeout(function(){if(document.hidden)document.addEventLis"
            + "tener('visibilitychange',function v(){if(!document.hidden){document.removeEventLis"
            + "tener('visibilitychange',v);f()}});else f()},ms)}\n"
            + "function up(s){var d=Math.floor(s/86400),h=Math.floor(s%86400/3600),"
            + "m=Math.floor(s%3600/60);return (d?d+'d ':'')+(d||h?h+'h ':'')+m+'m'}\n"
            + "function host(u){var m=/\\/\\/(?:[^@\\/]*@)?(\\[[^\\]]*\\]|[^\\/:?]*)/.exec(u||'');"
            + "return m?m[1]:''}\n"
            + "function num(v){return Math.round(v*10)/10}\n"
            + "function where(c){return esc(host(c.url))+(c.transport?' \u00b7 '+"
            + "esc(c.transport):'')+(c.audio===true?' \u00b7 \u266a':'')}\n"
            + "function info(c){if(!c.video)return '<span class=\"bad\">'+esc(c.text)+'</span>';\n"
            + "var t=esc(c.text),r=/(\\d+)\\/(\\d+) fps/.exec(c.text||'');\n"
            + "if(r&&+r[1]<0.9*r[2])t=t.replace(r[0],'<span class=\"warn\">'+r[0]+'</span>');\n"
            + "if(c.lost_fps>0)t+=' \u00b7 <span class=\"bad\">lost '+num(c.lost_fps)"
            + "+'/s</span>';\n"
            + "if(c.behind_s>0)t+=' \u00b7 <span class=\"bad\">behind '+c.behind_s"
            + "+' s</span>';return t}\n"
            // One cell: slot, camera and transport on top; size, fps and losses below
            + "function cell(c){var top='<span class=\"slot\">'+esc(c.slot)+'</span><span"
            + " class=\"dim\">'+where(c)+'</span>';\n"
            + "if(!c.video)return '<div class=\"cell\"><div class=\"off\"><b>'+esc(c.slot)+'</b>"
            + "<span>'+esc(c.text)+'</span></div><div class=\"top\">'+top+'</div></div>';\n"
            + "return '<div class=\"cell\"><div class=\"top\">'+top+'</div><div class=\"bot\">"
            + "'+info(c)+'</div></div>'}\n"
            + "function row(c){return '<tr><td>'+esc(c.slot)+'</td><td><span class=\"h\">"
            + "'+where(c)+'</span><br>'+info(c)+'</td></tr>'}\n"
            + "function render(s){"
            + "$('ver').textContent='v'+(s.version||'?')+' \u00b7 '+(s.git||'');\n"
            + "$('clock').textContent=s.time||'';$('up').textContent=s.uptime_s!=null?'up '+"
            + "up(s.uptime_s):'';\n"
            // Orange: the UI thread is stuck, or there is no activity behind the server at all
            + "var ui=s.ui_thread&&s.ui_thread!=='ok',none=!s.ui_thread;\n"
            + "$('err').textContent=ui?'UI '+s.ui_thread:none?(s.mode||'no data'):'';\n"
            + "$('dot').className='dot '+(ui||none?'warn':'ok');\n"
            + "var list,side=1;\n"
            + "if(s.mode==='paused'||s.mode==='udp'||!(s.cells||s.single))list=[s.mode==='udp'?{s"
            + "lot:'U',text:'UDP RTP port '+(s.udp?s.udp.port:'')}:{text:s.mode==='paused'?'App"
            + " in background':(s.mode||'')}];\n"
            + "else if(s.cells){side=s.cells.length>4?3:2;list=s.cells.slice();"
            + "while(list.length<side*side)list.push({slot:'U',text:'no receiver yet'})}\n"
            + "else if(s.single){var c=s.single;c.slot=s.active_slot;"
            + "c.text=c.video?(c.size||'')+' '+c.codec:c.status;list=[c]}\n"
            + "$('cells').style.gridTemplateColumns='repeat('+side+',1fr)';"
            + "$('cells').style.gridTemplateRows='repeat('+side+',1fr)';\n"
            + "$('cells').innerHTML=list.map(cell).join('');$('list').innerHTML=list.map(row).joi"
            + "n('')}\n"
            + "function tick(){fetch('/stats.json',{cache:'no-store'}).then(function(r){return"
            + " r.json()}).then(function(s){render(s)})\n"
            + ".catch(function(e){$('err').textContent='offline';$('dot').className='dot bad'}"
            + ").finally(function(){later(tick,1500)})}\n"
            + "var every=5,seg=$('every');try{every=+localStorage.getItem('every')||5}catch(x){}\n"
            + "function mark(){[].forEach.call(seg.children,function(b){b.className=+b.value===ev"
            + "ery?'on':''})}\n"
            + "seg.onclick=function(e){if(!e.target.value)return;every=+e.target.value;mark();"
            + "try{localStorage.setItem('every',every)}catch(x){}};mark();\n"
            + "var img=$('snap');img.onload=img.onerror=function(){later(snap,every*1000)};\n"
            + "function snap(){img.src='/snapshot.jpg?labels=0&t='+Date.now()}\n"
            + "later(tick,0);later(snap,0);\n"
            + "</script></body></html>\n";
}
