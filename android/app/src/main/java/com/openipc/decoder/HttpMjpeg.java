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
            int slash = rest.indexOf('/');
            String authority = slash >= 0 ? rest.substring(0, slash) : rest;
            Url u = new Url();
            if (slash >= 0) u.path = rest.substring(slash);
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                u.userInfo = RtspSession.Url.percentDecode(authority.substring(0, at));
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
            int semi = b.indexOf(';');
            if (semi >= 0) b = b.substring(0, semi).trim();
            if (b.length() >= 2 && b.startsWith("\"") && b.endsWith("\"")) {
                b = b.substring(1, b.length() - 1);
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
                // No length: the data runs up to CRLF + delimiter
                ByteArrayOutputStream part = new ByteArrayOutputStream();
                byte[] end = ("\r\n" + delimiter).getBytes(StandardCharsets.US_ASCII);
                if (!readUntil(body, end, part)) return;
                n = part.size();
                if (n > MAX_PART) throw new IOException("MJPEG part of " + n + " bytes");
                if (buf.length < n) buf = new byte[n];
                System.arraycopy(part.toByteArray(), 0, buf, 0, n);
                // What follows the delimiter on its line: "--" closes the stream
                String tail = readLine(body);
                line = tail == null ? null : delimiter + tail;
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
