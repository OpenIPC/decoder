/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * TapGuard.java — tells a real tap on the picture from a swipe at the screen edge
 *
 */

package com.openipc.decoder;

/**
 * Follows one touch sequence on the screen (#213) and says whether it was a tap that may
 * open the menu. Not a tap: a sequence that starts in the edge zone, where the system takes
 * swipes to show its bars or go back; one whose finger moved more than the touch slop, also
 * when the system kept the moves and only the up arrived (GestureDetector takes a down and a
 * far-away up with no move between as a tap); one with a second finger (pinch); one that was
 * cancelled. The answer is the one of the last finished sequence (up or cancel), so a tap
 * confirmed after its up (GestureDetector waits for a double tap) or a click posted after it
 * gets its own sequence's answer, not one of a new touch that has just begun. Coordinates are
 * in pixels of the screen; no Android classes, for the unit tests.
 */
class TapGuard {
    /** Edge zone where the system has no gesture insets (before Android 10, or 0 reported). */
    static final int EDGE_TOP_DP = 24;
    static final int EDGE_BOTTOM_DP = 24;
    static final int EDGE_SIDE_DP = 16;

    private final float slop;
    private boolean tap;
    private boolean lastTap;
    private float downX, downY;

    TapGuard(float touchSlopPx) {
        slop = touchSlopPx;
    }

    /**
     * Whether (x, y) is in the edge zone of a w x h screen: within the given inset of
     * each side (left, top, right, bottom).
     */
    static boolean startsInEdgeZone(float x, float y, int w, int h, int[] insets) {
        return x < insets[0] || y < insets[1] || x >= w - insets[2] || y >= h - insets[3];
    }

    /** Per side the larger of the system's gesture inset and the fixed edge band. */
    static int[] edgeZone(int[] gestureInsets, float density) {
        int[] fixed = {
                Math.round(EDGE_SIDE_DP * density), Math.round(EDGE_TOP_DP * density),
                Math.round(EDGE_SIDE_DP * density), Math.round(EDGE_BOTTOM_DP * density)};
        int[] zone = new int[4];
        for (int i = 0; i < 4; i++) {
            zone[i] = Math.max(fixed[i], gestureInsets == null ? 0 : gestureInsets[i]);
        }
        return zone;
    }

    /** First finger down: a new sequence. */
    void down(float x, float y, int w, int h, int[] zone) {
        downX = x;
        downY = y;
        tap = !startsInEdgeZone(x, y, w, h, zone);
    }

    /** A move of the first finger. */
    void moveTo(float x, float y) {
        float dx = x - downX, dy = y - downY;
        if (dx * dx + dy * dy > slop * slop) tap = false;
    }

    /** The up of the last finger: the sequence is finished. */
    void up(float x, float y) {
        moveTo(x, y);
        lastTap = tap;
    }

    /** A second finger: a pinch, not a tap. */
    void secondFinger() {
        tap = false;
    }

    void cancel() {
        tap = false;
        lastTap = false;
    }

    /** Whether the last finished sequence was a tap that may open the menu. */
    boolean wasTap() {
        return lastTap;
    }
}
