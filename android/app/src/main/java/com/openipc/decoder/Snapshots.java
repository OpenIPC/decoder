/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * Snapshots.java — a small JPEG of what the screen shows, for the debug page (#98)
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
 * thumbnail size with PixelCopy (the GPU scales them, no extra decoder, nothing from the
 * camera) and laid out like the screen. A cell that is not playing right now is drawn black
 * with its status, never with a stale last frame: the point is to see whether video is there.
 */
final class Snapshots {
    private static final String TAG = "OpenIPCDecoder";
    static final int CELL_W = 320;
    static final int CELL_H = 180;
    private static final int JPEG_QUALITY = 70;
    private static final long COPY_TIMEOUT_MS = 500;

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

    /** JPEG of the cells laid out in a grid of the given side (1 for the single view). */
    static byte[] render(List<Cell> cells, int side) {
        int cw = side == 1 ? CELL_W * 2 : CELL_W;
        int ch = side == 1 ? CELL_H * 2 : CELL_H;
        Bitmap out = Bitmap.createBitmap(cw * side, ch * side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(14);
        Paint band = new Paint();
        band.setColor(0xA0000000);
        Paint grid = new Paint();
        grid.setColor(Color.DKGRAY);
        for (int i = 0; i < cells.size() && i < side * side; i++) {
            Cell c = cells.get(i);
            if (c == null) continue;
            int x = (i % side) * cw;
            int y = (i / side) * ch;
            Bitmap frame = c.frame;
            if (frame == null && c.live && c.view != null) frame = copy(c.view, cw, ch);
            boolean shown = c.live && frame != null;
            if (shown) {
                canvas.drawBitmap(frame, null, new Rect(x, y, x + cw, y + ch), null);
            }
            if (frame != null) frame.recycle();
            String label = (c.slot > 0 ? c.slot + " · " : "") + (c.label != null ? c.label : "");
            if (c.live && !shown) label += " · no picture";
            if (shown) {
                canvas.drawRect(x, y + ch - 20, x + cw, y + ch, band);
                text.setColor(Color.WHITE);
                canvas.drawText(label, x + 4, y + ch - 6, text);
            } else {
                // No video: slot number and status in the middle, in red
                text.setColor(0xFFFF6060);
                float w = text.measureText(label);
                canvas.drawText(label, x + Math.max(4, (cw - w) / 2), y + ch / 2f + 5, text);
            }
            canvas.drawRect(x, y, x + cw, y + 1, grid);
            canvas.drawRect(x, y, x + 1, y + ch, grid);
        }
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        text.setColor(Color.WHITE);
        float tw = text.measureText(time);
        canvas.drawRect(out.getWidth() - tw - 8, 0, out.getWidth(), 20, band);
        canvas.drawText(time, out.getWidth() - tw - 4, 15, text);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(64 * 1024);
        out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg);
        out.recycle();
        return jpeg.toByteArray();
    }

    /** The frame the SurfaceView shows, scaled to w×h; null if it cannot be copied. */
    private static Bitmap copy(SurfaceView view, int w, int h) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        AtomicInteger result = new AtomicInteger(-1);
        CountDownLatch done = new CountDownLatch(1);
        try {
            PixelCopy.request(view, bm, r -> {
                result.set(r);
                done.countDown();
            }, copyHandler());
            if (!done.await(COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // Still copying into bm: leave it to the garbage collector, not recycle()
                return null;
            }
            if (result.get() == PixelCopy.SUCCESS) return bm;
            Log.d(TAG, "Snapshot copy failed: " + result.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            // The surface went away (the grid was rebuilt)
            Log.d(TAG, "Snapshot copy failed: " + e.getMessage());
        }
        bm.recycle();
        return null;
    }
}
