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
import android.graphics.RectF;
import android.os.Build;
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

    /**
     * This stream's hardware decoder failed: the software path from then on. Per renderer, so
     * one cell at a decoder limit does not send the others to lockCanvas() (Qodo on #139).
     */
    private volatile boolean hwFailed;
    /** The last frame was decoded with BitmapFactory. */
    private volatile boolean software;

    private final JpegCodec codec = new JpegCodec();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private volatile boolean drawn;
    private Bitmap bitmap;

    /**
     * Shows one frame on {@code s}, turned {@code rotate} degrees counter-clockwise (0, 90,
     * 180 or 270, #200), then flipped left↔right on the screen when {@code mirror} (#226). Returns its {width, height} as shown (turned) when it is on screen,
     * null when not (yet). {@code current} is false once the stream or the surface has been
     * replaced.
     */
    int[] render(Frame frame, Surface s, int rotate, boolean mirror, JpegCodec.Current current) {
        if (s == null || !s.isValid() || !current.holds()) return null;
        // The hardware JPEG decoder first, where there is one (#132); it turns the frame itself
        // from Android 6 on (#201), before that a turned frame is drawn by the app (#200). It
        // cannot mirror: a mirrored frame is drawn by the app (#226)
        boolean hwTurns = !mirror
                && (rotate == 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
        if (hwTurns && !hwFailed && !drawn && JpegCodec.candidate() != null) {
            int[] size = JpegAssembler.size(frame.data(), frame.length());
            if (size != null) {
                try {
                    software = false;
                    return codec.decode(frame, s, size[0], size[1], rotate, current)
                            ? shownSize(size[0], size[1], rotate) : null;
                } catch (Exception e) {
                    codec.release();
                    // The surface was replaced meanwhile: not the decoder's fault, try again
                    if (!current.holds()) return null;
                    Log.w(TAG, "Hardware MJPEG decoder failed, decoding in software", e);
                    hwFailed = true;
                }
            }
        }
        // A decoder still connected to the surface would make lockCanvas() fail
        codec.release();
        software = true;
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
                // Flipped on the screen, after the turn (#226)
                if (mirror) c.scale(-1f, 1f, c.getWidth() / 2f, c.getHeight() / 2f);
                if (rotate == 0) {
                    c.drawBitmap(b, null, new Rect(0, 0, c.getWidth(), c.getHeight()), paint);
                } else {
                    float[] t = turn(rotate, c.getWidth(), c.getHeight());
                    c.save();
                    c.translate(t[0], t[1]);
                    c.rotate(t[2]);
                    c.drawBitmap(b, null, new RectF(t[3], t[4], t[5], t[6]), paint);
                    c.restore();
                }
            } finally {
                s.unlockCanvasAndPost(c);
            }
        } catch (RuntimeException e) {
            // The surface went away meanwhile (view or activity change)
            return null;
        }
        drawn = true;
        return shownSize(b.getWidth(), b.getHeight(), rotate);
    }

    /** {width, height} of a {@code w}×{@code h} frame turned {@code rotate} degrees (#200). */
    static int[] shownSize(int w, int h, int rotate) {
        return rotate == 90 || rotate == 270 ? new int[] {h, w} : new int[] {w, h};
    }

    /**
     * How a frame turned {@code rotate} degrees counter-clockwise fills a {@code cw}×{@code ch}
     * canvas (#200): translate by {t[0], t[1]} (the centre), rotate by t[2] degrees (Canvas
     * turns clockwise, so negative), then draw the frame into the rectangle {t[3], t[4], t[5],
     * t[6]} (left, top, right, bottom), which is the canvas with width and height swapped for
     * a quarter turn.
     */
    static float[] turn(int rotate, int cw, int ch) {
        boolean quarter = rotate == 90 || rotate == 270;
        float hw = (quarter ? ch : cw) / 2f;
        float hh = (quarter ? cw : ch) / 2f;
        return new float[] {cw / 2f, ch / 2f, -rotate, -hw, -hh, hw, hh};
    }

    /**
     * Where the point ({@code u}, {@code v}) of the frame, as fractions of its width and height
     * from the top left, lands on the canvas with {@link #turn}: the same transform as
     * Canvas.translate() and rotate(), for tests without a bitmap.
     */
    static float[] map(float[] t, float u, float v) {
        float x = t[3] + u * (t[5] - t[3]);
        float y = t[4] + v * (t[6] - t[4]);
        double a = Math.toRadians(t[2]);
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        return new float[] {(float) (t[0] + x * cos - y * sin), (float) (t[1] + x * sin + y * cos)};
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

    /** Frames are decoded with BitmapFactory: CPU load, not the hardware decoder's. */
    boolean isSoftware() { return software; }

    /** Frames the hardware decoder had no input buffer for. */
    int dropped() { return codec.inputDropped(); }

    /** Releases the hardware decoder (the stream stopped or is no MJPEG any more). */
    void release() {
        codec.release();
    }
}
