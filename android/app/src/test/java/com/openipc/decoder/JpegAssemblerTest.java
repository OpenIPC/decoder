/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * JpegAssemblerTest.java — RTP/JPEG (RFC 2435) to JPEG frames (#119)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JpegAssemblerTest {
    /**
     * Fixtures: a 64x32 gradient (R = 4x, G = 8y, B = 128) encoded as baseline JPEG 4:2:2 and
     * cut into RTP/JPEG packets of 64 scan bytes — once with Q = 255 and the quantization
     * tables in the stream, once with Q = 50 (tables from Q). Format: 16-bit length + packet.
     */
    private static byte[] resource(String name) throws Exception {
        try (InputStream in = JpegAssemblerTest.class.getResourceAsStream("/rtp/" + name)) {
            assertNotNull(name, in);
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) all.write(b, 0, n);
            return all.toByteArray();
        }
    }

    private static List<byte[]> packets(String name) throws Exception {
        List<byte[]> out = new ArrayList<>();
        {
            byte[] d = resource(name);
            for (int i = 0; i < d.length; ) {
                int len = ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
                byte[] p = new byte[len];
                System.arraycopy(d, i + 2, p, 0, len);
                out.add(p);
                i += 2 + len;
            }
        }
        return out;
    }

    private static List<byte[]> assemble(List<byte[]> packets) {
        JpegAssembler a = new JpegAssembler(new FramePool(4));
        List<byte[]> frames = new ArrayList<>();
        for (byte[] p : packets) {
            assertTrue(a.assemble(p, 0, p.length, f -> {
                byte[] copy = new byte[f.length()];
                System.arraycopy(f.data(), 0, copy, 0, f.length());
                frames.add(copy);
            }));
        }
        return frames;
    }

    /**
     * The expected .jpg files were made by a reference implementation of the same header
     * rebuilding (RFC 2435 Appendix B) and decoded back to the gradient with PIL; the
     * assembler must produce them byte for byte (ImageIO is not available in unit tests).
     */
    private static void assertJpeg(byte[] jpeg, String expected) throws Exception {
        assertEquals(0xFF, jpeg[0] & 0xFF);
        assertEquals(0xD8, jpeg[1] & 0xFF);
        assertEquals(0xFF, jpeg[jpeg.length - 2] & 0xFF);
        assertEquals(0xD9, jpeg[jpeg.length - 1] & 0xFF);
        // SOF0: 8 bits, height 32, width 64, 3 components, Y sampled 2x1 (type 0)
        int sof = indexOf(jpeg, (byte) 0xFF, (byte) 0xC0);
        assertTrue(sof > 0);
        assertEquals(32, ((jpeg[sof + 5] & 0xFF) << 8) | (jpeg[sof + 6] & 0xFF));
        assertEquals(64, ((jpeg[sof + 7] & 0xFF) << 8) | (jpeg[sof + 8] & 0xFF));
        assertEquals(0x21, jpeg[sof + 11] & 0xFF);
        assertTrue(Arrays.equals(resource(expected), jpeg));
    }

    private static int indexOf(byte[] d, byte a, byte b) {
        for (int i = 0; i + 1 < d.length; i++) if (d[i] == a && d[i + 1] == b) return i;
        return -1;
    }

    @Test
    public void tablesInTheStream() throws Exception {
        List<byte[]> frames = assemble(packets("jpeg_q255_64x32.rtp"));
        assertEquals(1, frames.size());
        assertJpeg(frames.get(0), "jpeg_q255_64x32.jpg");
    }

    @Test
    public void tablesFromQ() throws Exception {
        List<byte[]> frames = assemble(packets("jpeg_q50_64x32.rtp"));
        assertEquals(1, frames.size());
        assertJpeg(frames.get(0), "jpeg_q50_64x32.jpg");
    }

    @Test
    public void aMissingFragmentDropsTheFrame() throws Exception {
        List<byte[]> p = packets("jpeg_q255_64x32.rtp");
        p.remove(2);
        assertEquals(0, assemble(p).size());
    }

    @Test
    public void joiningMidFrameWaitsForTheNextOne() throws Exception {
        List<byte[]> p = packets("jpeg_q255_64x32.rtp");
        List<byte[]> twice = new ArrayList<>(p.subList(1, p.size()));
        twice.addAll(p);
        assertEquals(1, assemble(twice).size());
    }

    @Test
    public void q50TablesMatchTheStandardOnes() {
        byte[] t = JpegAssembler.tables(50);
        assertEquals(16, t[0]);
        assertEquals(17, t[64]);
        assertEquals(99, t[127]);
    }
}
