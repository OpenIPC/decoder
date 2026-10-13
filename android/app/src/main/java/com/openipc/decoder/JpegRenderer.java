/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * JpegRenderer.java — one MJPEG stream onto a surface: hardware decoder first, else BitmapFactory
 *
 */

package com.openipc.decoder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import android.view.Surface;

/**
 * Shows the JPEG frames of one MJPEG stream on a surface, for the single view (#119) and for a
 * multi-view cell (#132): through the hardware JPEG decoder where the device has one, otherwise
 * decoded with BitmapFactory (into the previous bitmap when the size is the same) and drawn over
 * the whole surface with lockCanvas(). A surface drawn that way cannot take a decoder any more:
 * the owner makes a new one when the stream changes to H.264/H.265 ({@link #drewWithCanvas}).
 * Used by one decode thread; release() may come from others.
 */
final class JpegRenderer {
    private static final String TAG = "OpenIPCDecoder";

    /** The hardware decoder failed once in this run: the software path from then on. */
    private static volatile boolean hwFailed;

    private final JpegCodec codec = new JpegCodec();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private volatile boolean drawn;
    private Bitmap bitmap;

    /**
     * Shows one frame on {@code s}. Returns its {width, height} when it is on screen, null when
     * not (yet). {@code current} is false once the stream or the surface has been replaced.
     */
    int[] render(Frame frame, Surface s, JpegCodec.Current current) {
        if (s == null || !s.isValid() || !current.holds()) return null;
        // The hardware JPEG decoder first, where there is one (#132)
        if (!hwFailed && !drawn && JpegCodec.candidate() != null) {
            int[] size = JpegAssembler.size(frame.data(), frame.length());
            if (size != null) {
                try {
                    return codec.decode(frame, s, size[0], size[1], current) ? size : null;
                } catch (Exception e) {
                    codec.release();
                    // The surface was replaced meanwhile: not the decoder's fault, try again
                    if (!current.holds()) return null;
                    Log.w(TAG, "Hardware MJPEG decoder failed, decoding in software", e);
                    hwFailed = true;
                }
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        Bitmap reuse = bitmap;
        if (reuse != null) o.inBitmap = reuse;
        Bitmap b;
        try {
            b = BitmapFactory.decodeByteArray(frame.data(), 0, frame.length(), o);
        } catch (IllegalArgumentException e) {
            // Another size than the reusable bitmap
            o.inBitmap = null;
            b = BitmapFactory.decodeByteArray(frame.data(), 0, frame.length(), o);
        }
        if (b == null) return null;
        bitmap = b;
        try {
            Canvas c = s.lockCanvas(null);
            try {
                c.drawColor(Color.BLACK);
                c.drawBitmap(b, null, new Rect(0, 0, c.getWidth(), c.getHeight()), paint);
            } finally {
                s.unlockCanvasAndPost(c);
            }
        } catch (RuntimeException e) {
            // The surface went away meanwhile (view or activity change)
            return null;
        }
        drawn = true;
        return new int[] {b.getWidth(), b.getHeight()};
    }

    /**
     * Something was drawn with lockCanvas() since the last call: the surface cannot take a
     * decoder any more. Clears the flag, for the owner who replaces the surface.
     */
    boolean takeCanvasDrawn() {
        boolean d = drawn;
        drawn = false;
        return d;
    }

    /** The surface holds frames drawn with lockCanvas(). */
    boolean drewWithCanvas() { return drawn; }

    /** Releases the hardware decoder (the stream stopped or is no MJPEG any more). */
    void release() {
        codec.release();
    }
}
