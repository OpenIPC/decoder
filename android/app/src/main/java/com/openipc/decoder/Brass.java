/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * Brass.java — the "brass" look of the menu and the dialogs (#286, brand brass 1.3.0)
 *
 */

package com.openipc.decoder;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The palette is res/values/colors.xml (the brand's master copy); the shapes follow brass.css:
 * a panel of {@code panel2} → {@code panel} with a {@code brass2} frame, brass keys (an engaged
 * one "pressed" with an amber glow, the remote's focus an amber frame), dark displays for text
 * fields, section bands of a brass plate. The video window keeps its own black theme: only the
 * menu over it, the status line and the dialogs are brass.
 */
final class Brass {
    /** Dark ink on brass (keys, plates): {@code ink_dark}. */
    static final int INK_DARK = 0xFF2A1C10;
    /** A key that leads nowhere (an unconfigured camera, a word that does not apply now). */
    static final int INK_OFF = 0xFF6E5A3C;
    /** The latency of the active camera on a brass key: dark tones of green, amber and red. */
    static final int INK_OK = 0xFF1E6B3A;
    static final int INK_WARN = 0xFF8A4B00;
    static final int INK_BAD = 0xFF9A2A14;
    /** The display behind text fields and the status line, as {@code .state} of brass.css. */
    static final int DISPLAY = 0xFF120D0A;
    private static final int DISPLAY_EDGE = 0xFF574123;
    private static final int KEY_EDGE = 0xFF4A3510;
    private static final int PLATE_EDGE = 0xFF5B4416;

    private Brass() {
    }

    /** A colour of colors.xml (Context.getColor is API 23; the app starts at 21). */
    @SuppressWarnings("deprecation")
    static int color(Context c, int id) {
        return c.getResources().getColor(id);
    }

    private static float dp(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    /** The panel: {@code panel2} → {@code panel}, a 3dp {@code brass2} frame, radius 16dp. */
    static GradientDrawable panel(Context c) {
        float d = dp(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{color(c, R.color.panel2), color(c, R.color.panel)});
        g.setCornerRadius(16 * d);
        g.setStroke(Math.round(3 * d), color(c, R.color.brass2));
        return g;
    }

    /** A brass key: plain or engaged ("pressed", an amber glow), with the amber focus frame. */
    static GradientDrawable key(Context c, boolean engaged, boolean focused) {
        float d = dp(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, engaged
                ? new int[]{0xFFF6DC95, 0xFFC99D45, 0xFF8A6621}
                : new int[]{0xFFE9CD84, 0xFFB08B3C, 0xFF7C5C1F});
        g.setCornerRadius(10 * d);
        if (focused) g.setStroke(Math.round(3 * d), color(c, R.color.amber));
        else if (engaged) g.setStroke(Math.round(2 * d), 0xFFFFC266);   // the glow's rim
        else g.setStroke(Math.max(1, Math.round(d)), KEY_EDGE);
        return g;
    }

    /** A dialog button: a brass key that shows the remote's focus by itself. */
    private static Drawable keyStates(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, key(c, false, true));
        s.addState(new int[]{android.R.attr.state_pressed}, key(c, true, false));
        s.addState(new int[]{}, key(c, false, false));
        return s;
    }

    /** The dark display of a text field: the focused one with an amber frame. */
    private static Drawable displayStates(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, display(c, color(c, R.color.amber)));
        s.addState(new int[]{}, display(c, DISPLAY_EDGE));
        return s;
    }

    private static GradientDrawable display(Context c, int edge) {
        float d = dp(c);
        GradientDrawable g = new GradientDrawable();
        g.setColor(DISPLAY);
        g.setCornerRadius(6 * d);
        g.setStroke(Math.round(2 * d), edge);
        return g;
    }

    /** The plate of a section band: a brass gradient, dark serif capitals. */
    static TextView band(Context c, String text, boolean gapAbove) {
        float d = dp(c);
        TextView t = new TextView(c);
        t.setText(text);
        t.setAllCaps(true);
        t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        t.setLetterSpacing(0.25f);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(INK_DARK);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFE2C377, 0xFFA88237});
        g.setCornerRadius(4 * d);
        g.setStroke(Math.max(1, Math.round(d)), PLATE_EDGE);
        t.setBackground(g);
        t.setGravity(Gravity.CENTER);
        int p = Math.round(8 * d);
        t.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Math.round((gapAbove ? 22 : 6) * d), 0, Math.round(8 * d));
        t.setLayoutParams(lp);
        t.setTag(BAND);
        return t;
    }

    private static final String BAND = "brass-band";

    /** A builder whose dialog has the brass theme (amber accents, the focus ring). */
    static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, R.style.BrassDialog);
    }

    /**
     * The brass panel of a dialog, before {@code show()}: a window changed once it shows loses
     * the keyboard it opened with (a phone editing the camera's URL at once).
     */
    static void frame(AlertDialog dialog) {
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawable(panel(dialog.getContext()));
    }

    /**
     * The brass look of a dialog that is showing (its panel set by {@link #frame} before): the
     * serif title in brass, brass keys for its buttons, dark displays for its fields. Call right
     * after {@code show()}; it changes the views only, not the window.
     */
    static void style(AlertDialog dialog) {
        Window w = dialog.getWindow();
        if (w == null) return;
        Context c = dialog.getContext();
        styleTree(c, w.getDecorView());
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL}) {
            Button b = dialog.getButton(which);
            if (b != null) key(c, b);
        }
    }

    /** A dialog button as a brass key: serif capitals in dark ink, a little apart. */
    private static void key(Context c, Button b) {
        b.setBackground(keyStates(c));
        b.setTextColor(INK_DARK);
        b.setTypeface(Typeface.SERIF);
        b.setLetterSpacing(0.15f);
        b.setAllCaps(true);
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            int m = Math.round(4 * dp(c));
            ((ViewGroup.MarginLayoutParams) lp).setMargins(m, m, m, m);
            b.setLayoutParams(lp);
        }
    }

    private static void styleTree(Context c, View v) {
        if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(color(c, R.color.ink));
            e.setHintTextColor(0x99B09873);
            e.setTypeface(Typeface.MONOSPACE);
            int l = e.getPaddingLeft(), t = e.getPaddingTop();
            e.setBackground(displayStates(c));
            int p = Math.round(10 * dp(c));
            e.setPadding(Math.max(l, p), Math.max(t, p), p, p);
        } else if (v instanceof CompoundButton) {
            CompoundButton b = (CompoundButton) v;
            b.setTextColor(color(c, R.color.ink));
            b.setButtonTintList(ColorStateList.valueOf(color(c, R.color.amber)));
            // the remote's focus (focus_ring.xml): views made with the activity's context miss
            // the dialog theme's checkboxStyle
            b.setBackgroundResource(R.drawable.focus_ring);
        } else if (v instanceof Button) {
            // the dialog's own buttons: style(AlertDialog)
        } else if (v instanceof TextView && BAND.equals(v.getTag())) {
            // a section band keeps its plate
        } else if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (t.getId() == android.R.id.title || isAlertTitle(t)) {
                t.setTextColor(color(c, R.color.brass));
                t.setTypeface(Typeface.SERIF);
                t.setAllCaps(true);
                t.setLetterSpacing(0.2f);
            } else if (t.getId() == android.R.id.message) {
                t.setTextColor(color(c, R.color.ink));
            } else if (!Boolean.TRUE.equals(t.getTag(R.id.brass_keep))) {
                t.setTextColor(color(c, R.color.dim));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) styleTree(c, g.getChildAt(i));
        }
    }

    private static boolean isAlertTitle(View v) {
        if (v.getId() == View.NO_ID) return false;
        try {
            return "alertTitle".equals(v.getResources().getResourceEntryName(v.getId()));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
