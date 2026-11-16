/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * TvKeyboard.java — text fields that open the keyboard on OK, not on focus (#272)
 *
 */

package com.openipc.decoder;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.text.method.ArrowKeyMovementMethod;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/**
 * The keyboard of a TV box (SberBox) shows itself when the D-pad reaches a text field and then
 * takes every key of the remote, so a dialog cannot be left or walked through. A field made
 * here opens the keyboard on OK (or a tap); while not edited it reports no text to the system
 * (raw input type TYPE_NULL, no keyboard attaches) and the arrows move the focus, not the
 * cursor. A phone (touch mode) gets the keyboard at the tap that focuses the field, as before.
 */
final class TvKeyboard {
    private TvKeyboard() {
    }

    /** Makes {@code e} a field of this kind; {@code selectAll}: the text selected when editing starts. */
    static void field(EditText e, int inputType, boolean selectAll) {
        e.setInputType(inputType);
        e.setShowSoftInputOnFocus(false);
        idle(e);
        e.setOnClickListener(x -> edit(e, inputType, selectAll));
        e.setOnFocusChangeListener((x, has) -> {
            if (!has) idle(e);
            else if (x.isInTouchMode()) edit(e, inputType, selectAll);   // a phone: the focusing tap does not click
        });
    }

    private static void idle(EditText e) {
        e.setRawInputType(InputType.TYPE_NULL);
        e.setMovementMethod(null);
        e.setSelection(e.length());
    }

    /** Opens the keyboard on the field (OK, a tap, or a dialog that edits at once on a phone). */
    static void edit(EditText e, int inputType, boolean selectAll) {
        e.setRawInputType(inputType);
        e.setMovementMethod(ArrowKeyMovementMethod.getInstance());
        if (selectAll) e.selectAll();
        else e.setSelection(e.length());
        InputMethodManager m = (InputMethodManager) e.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) {
            m.restartInput(e);
            // after the restarted input connection, not before it
            e.post(() -> m.showSoftInput(e, 0));
        }
    }

    /**
     * For a dialog with such fields, once it shows: they report no text, so the dialog took
     * itself for one without text fields (FLAG_ALT_FOCUSABLE_IM) and no keyboard could open;
     * let it open, on OK only.
     */
    static void allowKeyboard(AlertDialog dialog) {
        Window w = dialog.getWindow();
        if (w != null) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    /**
     * For a dialog that edits a field at once on a phone, before it is shown: in touch mode the
     * field starts in editing and the window asks for the keyboard as it opens (a keyboard asked
     * for later, from onShow, comes before the window has the focus and is ignored); under a
     * remote the field waits for OK (and {@link #allowKeyboard} runs once the dialog shows).
     * Returns whether the field edits at once.
     */
    static boolean editAtOnce(AlertDialog dialog, EditText e, int inputType, boolean selectAll, boolean touch) {
        Window w = dialog.getWindow();
        if (w == null) return false;
        if (!touch) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            return false;
        }
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        e.setRawInputType(inputType);
        e.setMovementMethod(ArrowKeyMovementMethod.getInstance());
        if (selectAll) e.selectAll();
        return true;
    }
}
