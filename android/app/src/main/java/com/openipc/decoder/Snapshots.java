/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * Snapshots.java — a JPEG of what the screen shows, for the debug page (#98)
 *
 */

package com.openipc.decoder;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Made only when the debug page asks for it. Cells are copied from their SurfaceViews at
 * the size they have in the picture with PixelCopy (the GPU scales them, no extra decoder,
 * nothing from the camera) and laid out like the screen. A cell that is not playing right now
 * is drawn black with its status, never with a stale last frame: the point is to see whether
 * video is there.
 */
final class Snapshots {
    private static final String TAG = "OpenIPCDecoder";
    /** The picture is as wide as the screen, but not wider than this; always 16:9. */
    static final int MAX_WIDTH = 1920;
    private static final int MIN_WIDTH = 640;
    private static final int JPEG_QUALITY = 85;
    /** For all cells together: what is not copied by then is shown as "no picture". */
    private static final long COPY_TIMEOUT_MS = 2000;

    /** One cell of the picture, filled on the UI thread. */
    static final class Cell {
        /** Slot number shown to the user (1-based), 0 for the U cell. */
        int slot;
        /** Where the frame comes from (a multi-view cell or the single view, #76), or null. */
        SurfaceView view;
        /** Playing right now; otherwise only the label is drawn. */
        boolean live;
        String label;
        /** Not on screen, but its last stream check found the stream (#100): label in green. */
        boolean ok;
        /** What the TV shows, in a grid drawn for the single view (#100): framed. */
        boolean onScreen;
        /**
         * Width / height of the picture as the screen shows it, fitted into the cell with
         * black around it (the single view, e.g. a turned slot, #248); 0 fills the cell.
         */
        float aspect;
    }

    /**
     * The largest rectangle of the given aspect ratio (width / height) centred in the cell,
     * as {left, top, right, bottom}; the whole cell for 0 or a ratio within 1 % of its own.
     */
    static int[] fit(int left, int top, int right, int bottom, float aspect) {
        int w = right - left, h = bottom - top;
        if (aspect <= 0 || w <= 0 || h <= 0 || Math.abs(aspect * h / w - 1f) < 0.01f) {
            return new int[] {left, top, right, bottom};
        }
        int fw = w, fh = h;
        if (aspect * h > w) fh = Math.max(1, Math.round(w / aspect));
        else fw = Math.max(1, Math.round(h * aspect));
        int l = left + (w - fw) / 2, t = top + (h - fh) / 2;
        return new int[] {l, t, l + fw, t + fh};
    }

    private static HandlerThread copyThread;

    private Snapshots() {}

    private static synchronized Handler copyHandler() {
        if (copyThread == null) {
            copyThread = new HandlerThread("debug-snapshot");
            copyThread.start();
        }
        return new Handler(copyThread.getLooper());
    }

    /** Width of the picture for a screen of the given width (#103). */
    static int width(int screenWidth) {
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, screenWidth));
    }

    /** Height of a picture of the given width: 16:9, like the wall on the debug page. */
    static int height(int width) {
        return width * 9 / 16;
    }

    /**
     * JPEG of the cells laid out in a grid of the given side (1 for the single view), the
     * whole picture {@code width} wide. Without labels the picture has only the frames: the
     * debug page draws the text itself.
     */
    static byte[] render(List<Cell> cells, int side, int width, boolean labels) {
        long t0 = SystemClock.elapsedRealtime();
        int height = height(width);
        int n = Math.min(cells.size(), side * side);
        // Cell edges at i * size / side: the cells differ by at most a pixel and fill the picture
        Rect[] rects = new Rect[n];
        for (int i = 0; i < n; i++) {
            int col = i % side, row = i / side;
            rects[i] = new Rect(col * width / side, row * height / side,
                    (col + 1) * width / side, (row + 1) * height / side);
        }
        // Where each frame goes: its own aspect ratio inside the cell (#248)
        Rect[] dst = new Rect[n];
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            Rect r = rects[i];
            int[] f = fit(r.left, r.top, r.right, r.bottom, c != null ? c.aspect : 0);
            dst[i] = new Rect(f[0], f[1], f[2], f[3]);
        }
        // All copies are requested at once and run side by side, then awaited together
        Copy[] copies = new Copy[n];
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            if (c != null && c.live && c.view != null) {
                copies[i] = Copy.start(c.view, dst[i].width(), dst[i].height());
            }
        }
        long deadline = SystemClock.elapsedRealtime() + COPY_TIMEOUT_MS;
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        // As large compared with the cell as the 14 px on the former 256×144 cells
        float textSize = Math.max(14, height / side / 10f);
        text.setTextSize(textSize);
        int bandH = Math.round(textSize + 6);
        Paint band = new Paint();
        band.setColor(0xA0000000);
        Paint frames = new Paint(Paint.FILTER_BITMAP_FLAG);
        Paint grid = new Paint();
        grid.setColor(Color.DKGRAY);
        // The camera on screen in a grid drawn for the single view (#100)
        Paint onScreen = new Paint();
        onScreen.setColor(0xFF3D8BFF);
        onScreen.setStyle(Paint.Style.STROKE);
        onScreen.setStrokeWidth(4);
        int copied = 0;
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            if (c == null) continue;
            Rect r = rects[i];
            int x = r.left, y = r.top, cw = r.width(), ch = r.height();
            Bitmap frame = copies[i] != null ? copies[i].await(deadline) : null;
            if (copies[i] != null && frame != null) copied++;
            boolean shown = c.live && frame != null;
            if (shown) {
                canvas.drawBitmap(frame, null, dst[i], frames);
            }
            if (frame != null) frame.recycle();
            if (labels) {
                drawLabel(canvas, c, shown, x, y, cw, ch, bandH, text, band);
                canvas.drawRect(x, y, x + cw, y + 1, grid);
                canvas.drawRect(x, y, x + 1, y + ch, grid);
                if (c.onScreen && side > 1) {
                    canvas.drawRect(x + 2, y + 2, x + cw - 2, y + ch - 2, onScreen);
                }
            }
        }
        long t1 = SystemClock.elapsedRealtime();
        if (labels) {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            text.setColor(Color.WHITE);
            float tw = text.measureText(time);
            canvas.drawRect(out.getWidth() - tw - 8, 0, out.getWidth(), bandH, band);
            canvas.drawText(time, out.getWidth() - tw - 4, bandH - 5, text);
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(256 * 1024);
        out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg);
        out.recycle();
        long t2 = SystemClock.elapsedRealtime();
        Log.d(TAG, "Snapshot: " + copied + " cells copied in " + (t1 - t0) + " ms, JPEG "
                + jpeg.size() / 1024 + " KiB in " + (t2 - t1) + " ms");
        return jpeg.toByteArray();
    }

    private static void drawLabel(Canvas canvas, Cell c, boolean shown, int x, int y, int cw,
                                  int ch, int bandH, TextPaint text, Paint band) {
        String full = (c.slot > 0 ? c.slot + " \u00b7 " : "") + (c.label != null ? c.label : "");
        if (c.live && !shown) full += " \u00b7 no picture";
        // Cut to the cell with an ellipsis rather than run into the next one
        String label = TextUtils.ellipsize(full, text, cw - 8, TextUtils.TruncateAt.END).toString();
        if (shown) {
            canvas.drawRect(x, y + ch - bandH, x + cw, y + ch, band);
            text.setColor(Color.WHITE);
            canvas.drawText(label, x + 4, y + ch - 6, text);
        } else {
            // No video: slot number and status in the middle, in red; a stream found by a
            // check of a slot not on screen in green, an empty slot grey (#100)
            text.setColor(c.ok ? 0xFF7FD07F
                    : StreamProbes.NOT_CONFIGURED.equals(c.label) ? 0xFF888888 : 0xFFFF6060);
            float w = text.measureText(label);
            canvas.drawText(label, x + Math.max(4, (cw - w) / 2),
                    y + (ch + text.getTextSize()) / 2f, text);
        }
    }

    /**
     * Copies what the SurfaceView shows into {@code into}, scaled to its size, on the snapshot
     * thread, which also gets {@code done} (the single view's screenshot, #76). False if it
     * cannot start: before Android 7 or without a surface.
     */
    static boolean copy(SurfaceView view, Bitmap into, PixelCopy.OnPixelCopyFinishedListener done) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        try {
            PixelCopy.request(view, into, done, copyHandler());
            return true;
        } catch (RuntimeException e) {
            // The surface went away meanwhile
            Log.d(TAG, "Snapshot copy failed: " + e.getMessage());
            return false;
        }
    }

    /** One PixelCopy in flight. */
    private static final class Copy {
        final Bitmap bitmap;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger result = new AtomicInteger(-1);

        private Copy(Bitmap bitmap) { this.bitmap = bitmap; }

        /** Starts copying what the SurfaceView shows, scaled to w×h; null if it cannot. */
        static Copy start(SurfaceView view, int w, int h) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
            Copy c = new Copy(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888));
            if (copy(view, c.bitmap, r -> {
                c.result.set(r);
                c.done.countDown();
            })) {
                return c;
            }
            // The surface went away (the grid was rebuilt)
            c.bitmap.recycle();
            return null;
        }

        /** The copied frame, or null if it failed or is not done by the deadline. */
        Bitmap await(long deadlineMs) {
            try {
                long wait = Math.max(0, deadlineMs - SystemClock.elapsedRealtime());
                if (!done.await(wait, TimeUnit.MILLISECONDS)) {
                    // Still copying into the bitmap: leave it to the GC, not recycle()
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (result.get() == PixelCopy.SUCCESS) return bitmap;
            Log.d(TAG, "Snapshot copy failed: " + result.get());
            bitmap.recycle();
            return null;
        }
    }
}
