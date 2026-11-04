/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * RemoteKeys.java — what the remote's keys do while the menu is closed (#263)
 *
 */

package com.openipc.decoder;

import android.view.KeyEvent;

/**
 * The touch-only actions on a D-pad remote (#263): zoom and pan of the single view, the cell
 * a long OK acts on in multi-view. Pure functions, so they are tested without a device.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** One press of a zoom key (ZOOM, CH, PAGE, + and -). */
    static final float ZOOM_STEP = 1.25f;
    /** Each key repeat while Up or Down is held: about 5× in two seconds at 20 repeats/s. */
    static final float ZOOM_HOLD_STEP = 1.04f;
    /** One press of an arrow moves the zoomed picture by this share of the fitted view. */
    static final float PAN_STEP = 0.1f;
    /** The selection frame in multi-view goes away after this long without a key. */
    static final long SELECTION_SHOWN_MS = 4000;

    /** +1 for a key that zooms in, -1 for one that zooms out, 0 for any other. */
    static int zoomDirection(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ZOOM_IN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_NUMPAD_ADD:
            case KeyEvent.KEYCODE_EQUALS:
                return 1;
            case KeyEvent.KEYCODE_ZOOM_OUT:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                return -1;
            default:
                return 0;
        }
    }

    static boolean isArrow(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /** The new zoom, kept within [min, max]; at or below min + a little it is min exactly. */
    static float zoom(float scale, float factor, float min, float max) {
        float next = Math.max(min, Math.min(max, scale * factor));
        return next < min * 1.001f ? min : next;
    }

    /**
     * The pan after a zoom from {@code oldScale} to {@code newScale}: the point of the picture at
     * the centre of the screen stays there, as a remote has no point to zoom into.
     */
    static float panAfterZoom(float pan, float oldScale, float newScale) {
        return oldScale > 0 ? pan * newScale / oldScale : 0f;
    }

    /**
     * Pan in x and y for an arrow, {@code step} pixels: Up shows more of the top of the picture,
     * so the picture moves down; Left likewise moves it right.
     */
    static float[] panForArrow(int keyCode, float step) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return new float[] {0f, step};
            case KeyEvent.KEYCODE_DPAD_DOWN: return new float[] {0f, -step};
            case KeyEvent.KEYCODE_DPAD_LEFT: return new float[] {step, 0f};
            case KeyEvent.KEYCODE_DPAD_RIGHT: return new float[] {-step, 0f};
            default: return new float[] {0f, 0f};
        }
    }

    /**
     * The cell an arrow moves the selection to in a {@code size}×{@code size} grid, cells
     * numbered by rows; at an edge it stays. A cell out of the grid starts at the first.
     */
    static int moveCell(int cell, int size, int keyCode) {
        if (size < 1) return cell;
        if (cell < 0 || cell >= size * size) return 0;
        int row = cell / size;
        int col = cell % size;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: row = Math.max(0, row - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: row = Math.min(size - 1, row + 1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: col = Math.max(0, col - 1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: col = Math.min(size - 1, col + 1); break;
            default: break;
        }
        return row * size + col;
    }
}
