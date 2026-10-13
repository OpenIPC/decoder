/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * HttpMjpeg.java — MJPEG over HTTP: multipart/x-mixed-replace, one JPEG per part (#121)
 *
 */

package com.openipc.decoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The HTTP side of an http:// slot such as Majestic's {@code http://<camera>/mjpeg}: the URL,
 * the response head and the body, a {@code multipart/x-mixed-replace} stream whose parts are
 * whole JPEG frames, chunked or not. The connection itself is RtspSession's, which also
 * handles authentication (Basic, Digest) and reconnects. Pure Java, no Android types.
 */
final class HttpMjpeg {
    /** Larger parts are not JPEG frames of a camera: the stream is given up. */
    static final int MAX_PART = 8 * 1024 * 1024;
    private static final int MAX_LINE = 8192;

    private HttpMjpeg() {}

    static boolean matches(String url) {
        return url != null && url.trim().toLowerCase(Locale.ROOT).startsWith("http://");
    }

    static final class Url {
        String host;
        int port = 80;
        /** Path and query, "/" at least. */
        String path = "/";
        /** Decoded "user:password", or null. */
        String userInfo;

        static Url parse(String url) throws IOException {
            if (!matches(url)) throw new IOException("Not an http:// URL");
            String rest = url.trim().substring("http://".length());
            // The fragment is not sent (Qodo on #150)
            int hash = rest.indexOf('#');
            if (hash >= 0) rest = rest.substring(0, hash);
            // The authority ends at '/' or '?' (http://camera?stream=1)
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char ch = rest.charAt(i);
                if (ch == '/' || ch == '?') {
                    end = i;
                    break;
                }
            }
            String authority = rest.substring(0, end);
            Url u = new Url();
            if (end < rest.length()) {
                u.path = rest.charAt(end) == '?' ? "/" + rest.substring(end) : rest.substring(end);
            }
            // The path goes into the request line: no spaces or control characters (Qodo on #150)
            for (int i = 0; i < u.path.length(); i++) {
                char ch = u.path.charAt(i);
                if (ch <= 0x20 || ch == 0x7F) throw new IOException("Bad character in URL path");
            }
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                u.userInfo = RtspSession.Url.percentDecode(authority.substring(0, at));
                for (int i = 0; i < u.userInfo.length(); i++) {
                    char ch = u.userInfo.charAt(i);
                    if (ch < 0x20 || ch == 0x7F) {
                        throw new IOException("Bad character in URL credentials");
                    }
                }
                authority = authority.substring(at + 1);
            }
            String host = authority;
            int colon = authority.lastIndexOf(':');
            if (authority.startsWith("[")) {
                int close = authority.indexOf(']');
                if (close < 0) throw new IOException("Bad host in URL");
                host = authority.substring(1, close);
                colon = authority.indexOf(':', close);
            } else if (colon >= 0) {
                host = authority.substring(0, colon);
            }
            if (colon >= 0) {
                try {
                    u.port = Integer.parseInt(authority.substring(colon + 1));
                } catch (NumberFormatException e) {
                    throw new IOException("Bad port in URL");
                }
                if (u.port <= 0 || u.port > 65535) throw new IOException("Bad port in URL");
            }
            if (host.isEmpty()) throw new IOException("No host in URL");
            u.host = host;
            return u;
        }
    }

    /** Status and the headers that matter here. */
    static final class Response {
        int status;
        String contentType;
        boolean chunked;
        final List<String> challenges = new ArrayList<>();

        /** The multipart boundary, without the leading "--", or null when not multipart. */
        String boundary() {
            if (contentType == null) return null;
            String lower = contentType.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("multipart/")) return null;
            int i = lower.indexOf("boundary=");
            if (i < 0) return null;
            String b = contentType.substring(i + "boundary=".length()).trim();
            if (b.startsWith("\"")) {
                // Quoted: up to the closing quote, a ';' inside belongs to it (Qodo on #150)
                int close = b.indexOf('"', 1);
                if (close < 0) return null;
                b = b.substring(1, close);
            } else {
                int semi = b.indexOf(';');
                if (semi >= 0) b = b.substring(0, semi).trim();
            }
            // Some servers put the dashes into the parameter too
            if (b.startsWith("--")) b = b.substring(2);
            return b.isEmpty() ? null : b;
        }
    }

    /** The status line and headers, up to the empty line. */
    static Response readResponse(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) throw new IOException("No HTTP response");
        String[] parts = line.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("Not an HTTP response");
        }
        Response r = new Response();
        try {
            r.status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Bad HTTP status");
        }
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("content-type")) r.contentType = value;
            else if (name.equals("transfer-encoding")) {
                r.chunked = value.toLowerCase(Locale.ROOT).contains("chunked");
            } else if (name.equals("www-authenticate")) r.challenges.add(value);
        }
        return r;
    }

    interface FrameSink {
        /** One JPEG frame; false to stop reading. */
        boolean onFrame(byte[] buf, int offset, int length);
    }

    /**
     * Reads parts until the stream ends, a part is malformed or the sink says stop. Each part
     * goes to the sink whole: by its Content-Length, or else up to the next boundary.
     */
    static void readParts(InputStream body, String boundary, FrameSink sink) throws IOException {
        String delimiter = "--" + boundary;
        byte[] buf = new byte[256 * 1024];
        String line;
        // Up to the first delimiter (a preamble is allowed)
        while ((line = readLine(body)) != null && !line.startsWith(delimiter)) {
            // skip
        }
        while (line != null) {
            if (line.startsWith(delimiter + "--")) return; // the closing delimiter
            int length = -1;
            while ((line = readLine(body)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim()
                        .equalsIgnoreCase("content-length")) {
                    try {
                        length = Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException e) {
                        length = -1;
                    }
                }
            }
            if (line == null) return;
            if (length > MAX_PART) throw new IOException("MJPEG part of " + length + " bytes");
            int n;
            if (length >= 0) {
                if (buf.length < length) buf = new byte[length];
                readFully(body, buf, length);
                n = length;
                // The rest of the line after the data, then the next delimiter
                while ((line = readLine(body)) != null && !line.startsWith(delimiter)) {
                    // skip
                }
            } else {
                // No length: the data runs up to a line that is the delimiter, after LF or CR LF
                // (Qodo on #150); "\n--boundary" inside the data with more on its line is data
                ByteArrayOutputStream part = new ByteArrayOutputStream();
                byte[] end = ("\n" + delimiter).getBytes(StandardCharsets.US_ASCII);
                String tail;
                while (true) {
                    if (!readUntil(body, end, part)) return;
                    // The rest of the line as it is, terminator included
                    tail = readRawLine(body);
                    if (tail == null) return;
                    String t = tail.trim();
                    if (t.isEmpty() || t.equals("--")) break;
                    // Not a delimiter line: put back exactly what was taken for one
                    byte[] back = ("\n" + delimiter + tail).getBytes(StandardCharsets.ISO_8859_1);
                    part.write(back, 0, back.length);
                    if (part.size() > MAX_PART) throw new IOException("MJPEG part too large");
                }
                byte[] data = part.toByteArray();
                n = data.length;
                if (n > 0 && data[n - 1] == '\r') n--;
                if (buf.length < n) buf = new byte[n];
                System.arraycopy(data, 0, buf, 0, n);
                // "--" after the delimiter closes the stream
                line = delimiter + tail.trim();
            }
            if (n > 0 && !sink.onFrame(buf, 0, n)) return;
        }
    }

    /** Copies bytes into {@code out} until {@code end} has been read (not copied). */
    private static boolean readUntil(InputStream in, byte[] end, ByteArrayOutputStream out)
            throws IOException {
        int matched = 0;
        int b;
        while ((b = in.read()) >= 0) {
            if (b == (end[matched] & 0xFF)) {
                matched++;
                if (matched == end.length) return true;
                continue;
            }
            if (matched > 0) {
                // Not the delimiter after all: what matched so far is data
                out.write(end, 0, matched);
                matched = b == (end[0] & 0xFF) ? 1 : 0;
                if (matched == 0) out.write(b);
            } else {
                out.write(b);
            }
            if (out.size() > MAX_PART) throw new IOException("MJPEG part too large");
        }
        return false;
    }

    private static void readFully(InputStream in, byte[] buf, int length) throws IOException {
        int off = 0;
        while (off < length) {
            int r = in.read(buf, off, length - off);
            if (r < 0) throw new IOException("Stream ended inside a part");
            off += r;
        }
    }

    /** Bytes up to and including LF, as ISO-8859-1; null at the end of the stream. */
    private static String readRawLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            sb.append((char) b);
            if (b == '\n') return sb.toString();
            if (sb.length() >= MAX_LINE) return sb.toString();
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** One CRLF (or LF) terminated ASCII line without the terminator; null at the end. */
    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            if (sb.length() >= MAX_LINE) throw new IOException("HTTP line too long");
            sb.append((char) b);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** The body of a chunked response as a plain stream (RFC 9112 7.1). */
    static final class Chunked extends InputStream {
        private final InputStream in;
        private int left;
        private boolean done;

        Chunked(InputStream in) {
            this.in = in;
        }

        private boolean next() throws IOException {
            if (done) return false;
            if (left > 0) return true;
            String line = readLine(in);
            // The CRLF that ends the previous chunk's data
            if (line != null && line.isEmpty()) line = readLine(in);
            if (line == null) {
                done = true;
                return false;
            }
            int semi = line.indexOf(';');
            String hex = (semi >= 0 ? line.substring(0, semi) : line).trim();
            try {
                left = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Bad chunk size");
            }
            if (left < 0) throw new IOException("Bad chunk size");
            if (left == 0) {
                done = true;
                return false;
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!next()) return -1;
            int b = in.read();
            if (b < 0) {
                done = true;
                return -1;
            }
            left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!next()) return -1;
            int r = in.read(b, off, Math.min(len, left));
            if (r < 0) {
                done = true;
                return -1;
            }
            left -= r;
            return r;
        }
    }
}
