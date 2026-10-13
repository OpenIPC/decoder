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
 * the size they have in the picture with PixelCopy (the GPU scales them, no extra decoder, nothing from the
 * camera) and laid out like the screen. A cell that is not playing right now is drawn black
 * with its status, never with a stale last frame: the point is to see whether video is there.
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
        /** Where the frame comes from in multi-view, or null. */
        SurfaceView view;
        /** Frame already taken on the UI thread (single view, TextureView), or null. */
        Bitmap frame;
        /** Playing right now; otherwise only the label is drawn. */
        boolean live;
        String label;
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
        int cw = width / side;
        int ch = height(width) / side;
        int n = Math.min(cells.size(), side * side);
        // All copies are requested at once and run side by side, then awaited together
        Copy[] copies = new Copy[n];
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            if (c != null && c.frame == null && c.live && c.view != null) {
                copies[i] = Copy.start(c.view, cw, ch);
            }
        }
        long deadline = SystemClock.elapsedRealtime() + COPY_TIMEOUT_MS;
        Bitmap out = Bitmap.createBitmap(cw * side, ch * side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        // As large compared with the cell as the 14 px on the former 256×144 cells
        float textSize = Math.max(14, ch / 10f);
        text.setTextSize(textSize);
        int bandH = Math.round(textSize + 6);
        Paint band = new Paint();
        band.setColor(0xA0000000);
        Paint frames = new Paint(Paint.FILTER_BITMAP_FLAG);
        Paint grid = new Paint();
        grid.setColor(Color.DKGRAY);
        int copied = 0;
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            if (c == null) continue;
            int x = (i % side) * cw;
            int y = (i / side) * ch;
            Bitmap frame = copies[i] != null ? copies[i].await(deadline) : c.frame;
            if (copies[i] != null && frame != null) copied++;
            boolean shown = c.live && frame != null;
            if (shown) {
                canvas.drawBitmap(frame, null, new Rect(x, y, x + cw, y + ch), frames);
            }
            if (frame != null) frame.recycle();
            if (labels) {
                drawLabel(canvas, c, shown, x, y, cw, ch, bandH, text, band);
                canvas.drawRect(x, y, x + cw, y + 1, grid);
                canvas.drawRect(x, y, x + 1, y + ch, grid);
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
                                  int ch, int bandH, Paint text, Paint band) {
        String label = (c.slot > 0 ? c.slot + " \u00b7 " : "") + (c.label != null ? c.label : "");
        if (c.live && !shown) label += " \u00b7 no picture";
        if (shown) {
            canvas.drawRect(x, y + ch - bandH, x + cw, y + ch, band);
            text.setColor(Color.WHITE);
            canvas.drawText(label, x + 4, y + ch - 6, text);
        } else {
            // No video: slot number and status in the middle, in red
            text.setColor(0xFFFF6060);
            float w = text.measureText(label);
            canvas.drawText(label, x + Math.max(4, (cw - w) / 2), y + (ch + text.getTextSize()) / 2f, text);
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
            try {
                PixelCopy.request(view, c.bitmap, r -> {
                    c.result.set(r);
                    c.done.countDown();
                }, copyHandler());
                return c;
            } catch (RuntimeException e) {
                // The surface went away (the grid was rebuilt)
                Log.d(TAG, "Snapshot copy failed: " + e.getMessage());
                c.bitmap.recycle();
                return null;
            }
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
