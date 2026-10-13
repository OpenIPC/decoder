package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HttpMjpegTest {
    private static final byte[] JPEG1 = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
    private static final byte[] JPEG2 = {(byte) 0xFF, (byte) 0xD8, 9, '\r', '\n', '-', (byte) 0xFF,
            (byte) 0xD9};

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] part(String boundary, byte[] jpeg, boolean withLength) {
        String head = "--" + boundary + "\r\nContent-Type: image/jpeg\r\n"
                + (withLength ? "Content-Length: " + jpeg.length + "\r\n" : "") + "\r\n";
        return concat(ascii(head), jpeg, ascii("\r\n"));
    }

    /** Each piece as one HTTP chunk, as Majestic sends it. */
    private static byte[] chunked(byte[]... pieces) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : pieces) {
            byte[] size = ascii(Integer.toHexString(p.length) + "\r\n");
            out.write(size, 0, size.length);
            out.write(p, 0, p.length);
            out.write('\r');
            out.write('\n');
        }
        byte[] end = ascii("0\r\n\r\n");
        out.write(end, 0, end.length);
        return out.toByteArray();
    }

    private static List<byte[]> read(InputStream body, String boundary) throws IOException {
        List<byte[]> frames = new ArrayList<>();
        HttpMjpeg.readParts(body, boundary, (buf, off, len) -> {
            frames.add(Arrays.copyOfRange(buf, off, off + len));
            return true;
        });
        return frames;
    }

    @Test
    public void urlWithCredentialsPortAndPath() throws IOException {
        HttpMjpeg.Url u = HttpMjpeg.Url.parse("http://root:12%3A34@172.17.32.133:8080/mjpeg?x=1");
        assertEquals("172.17.32.133", u.host);
        assertEquals(8080, u.port);
        assertEquals("/mjpeg?x=1", u.path);
        assertEquals("root:12:34", u.userInfo);
        HttpMjpeg.Url plain = HttpMjpeg.Url.parse("http://cam");
        assertEquals(80, plain.port);
        assertEquals("/", plain.path);
        assertNull(plain.userInfo);
        assertTrue(HttpMjpeg.matches(" HTTP://cam/mjpeg"));
        assertFalse(HttpMjpeg.matches("rtsp://cam/stream=0"));
    }

    @Test
    public void responseHeadOfMajestic() throws IOException {
        InputStream in = new ByteArrayInputStream(ascii("HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=boundarydonotcross\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n"));
        HttpMjpeg.Response r = HttpMjpeg.readResponse(in);
        assertEquals(200, r.status);
        assertTrue(r.chunked);
        assertEquals("boundarydonotcross", r.boundary());
    }

    @Test
    public void unauthorizedKeepsTheChallenge() throws IOException {
        InputStream in = new ByteArrayInputStream(ascii("HTTP/1.1 401 Unauthorized\r\n"
                + "WWW-Authenticate: Basic realm=\"Authentication\"\r\n\r\n"));
        HttpMjpeg.Response r = HttpMjpeg.readResponse(in);
        assertEquals(401, r.status);
        assertEquals(1, r.challenges.size());
        assertNull(r.boundary());
    }

    @Test
    public void quotedBoundaryWithDashes() {
        HttpMjpeg.Response r = new HttpMjpeg.Response();
        r.contentType = "multipart/x-mixed-replace;boundary=\"--myboundary\"";
        assertEquals("myboundary", r.boundary());
        r.contentType = "image/jpeg";
        assertNull(r.boundary());
    }

    @Test
    public void chunkedPartsWithContentLength() throws IOException {
        String b = "boundarydonotcross";
        byte[] body = chunked(part(b, JPEG1, true), part(b, JPEG2, true));
        List<byte[]> frames = read(new HttpMjpeg.Chunked(new ByteArrayInputStream(body)), b);
        assertEquals(2, frames.size());
        assertArrayEquals(JPEG1, frames.get(0));
        assertArrayEquals(JPEG2, frames.get(1));
    }

    @Test
    public void partsWithoutLengthRunToTheNextBoundary() throws IOException {
        String b = "frame";
        byte[] body = concat(part(b, JPEG1, false), part(b, JPEG2, false), ascii("--frame--\r\n"));
        List<byte[]> frames = read(new ByteArrayInputStream(body), b);
        assertEquals(2, frames.size());
        assertArrayEquals(JPEG1, frames.get(0));
        // CR LF '-' inside the data is not taken for a delimiter
        assertArrayEquals(JPEG2, frames.get(1));
    }

    @Test
    public void aChunkSplitInsideAPartStillGivesWholeFrames() throws IOException {
        String b = "b";
        byte[] all = concat(part(b, JPEG1, true), part(b, JPEG2, true));
        byte[] body = chunked(Arrays.copyOfRange(all, 0, 20), Arrays.copyOfRange(all, 20, 37),
                Arrays.copyOfRange(all, 37, all.length));
        List<byte[]> frames = read(new HttpMjpeg.Chunked(new ByteArrayInputStream(body)), b);
        assertEquals(2, frames.size());
        assertArrayEquals(JPEG1, frames.get(0));
        assertArrayEquals(JPEG2, frames.get(1));
    }
}
