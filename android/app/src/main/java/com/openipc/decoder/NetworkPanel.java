/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * NetworkPanel.java — the Network item of the menu: the camex tunnel (#272)
 *
 */

package com.openipc.decoder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.openipc.camex.tunnel.CamexTunnel;
import org.openipc.camex.tunnel.Field;

import java.util.HashMap;
import java.util.Map;

/**
 * The camex tunnel (the library of bundles/camex): the Network screen is built from its fields,
 * the values are kept in Decoder's settings as {@code tunnel_camex_<key>} (the psk sealed with
 * {@link CredentialStore}), and {@code tunnel_on} connects now and at every start of the app.
 * Android's VPN consent: a person confirms the system dialog once; a box without a person gets
 * it while it is prepared: adb shell appops set com.openipc.decoder ACTIVATE_VPN allow.
 */
final class NetworkPanel {
    private static final String TAG = "OpenIPCDecoder";
    static final int REQ_VPN = 0x4e57;
    private static final int REQ_NOTIFY = REQ_VPN + 1;
    private static final String PREFS = "settings";
    static final String KEY_ON = "tunnel_on";
    private static final String PREFIX = "tunnel_camex_";

    private NetworkPanel() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The saved values, the initial ones where nothing is saved; the psk opened. */
    static Map<String, String> load(Context c) {
        Map<String, String> v = new HashMap<>();
        CredentialStore store = null;
        for (Field f : CamexTunnel.fields()) {
            String s = prefs(c).getString(PREFIX + f.key, null);
            if (s != null && f.kind == Field.Kind.SECRET && !s.isEmpty()) {
                if (store == null) store = CredentialStore.create();
                s = store.open(s);
            }
            v.put(f.key, s != null ? s : f.initial);
        }
        return v;
    }

    private static void save(Context c, Map<String, String> v, boolean on) {
        SharedPreferences.Editor e = prefs(c).edit();
        CredentialStore store = CredentialStore.create();
        for (Field f : CamexTunnel.fields()) {
            String s = v.get(f.key);
            if (s != null && f.kind == Field.Kind.SECRET && !s.isEmpty()) s = store.seal(s);
            e.putString(PREFIX + f.key, s == null ? "" : s);
        }
        e.putBoolean(KEY_ON, on).apply();
    }

    static boolean isOn(Context c) {
        return prefs(c).getBoolean(KEY_ON, false);
    }

    /**
     * At the start of the app: connects when Network is on. Without the consent the system
     * dialog shows here too: Android 5.0 has no appops for it, so a box is set up by confirming
     * this dialog once.
     */
    static void autoStart(Activity a) {
        if (isOn(a) && !CamexTunnel.running()) connect(a, false);
    }

    /** Save with Network on: asks for the VPN consent once (the system dialog), then starts. */
    static void connect(Activity a) {
        connect(a, true);
    }

    // askNotifications: a person turned Network on (Save), so the notification permission of
    // Android 13+ is asked; not at every start of the app (a box may have no one to ask)
    private static boolean askAfterConsent;

    private static void connect(Activity a, boolean askNotifications) {
        Intent consent = null;
        try {
            consent = CamexTunnel.consentIntent(a);
        } catch (RuntimeException e) {
            Log.e(TAG, "VPN consent", e);
        }
        if (consent != null) {
            try {
                askAfterConsent = askNotifications;
                a.startActivityForResult(consent, REQ_VPN);
                return;
            } catch (RuntimeException e) {
                // no dialog on this device: start() reports NEEDS_CONSENT
                Log.e(TAG, "no VPN consent dialog on this device", e);
            }
        }
        start(a, askNotifications);
    }

    /** {@link Activity#onActivityResult} of {@link #REQ_VPN}. */
    static void onVpnResult(Activity a, int resultCode) {
        if (resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "Network is on, but Android's VPN consent was not given");
        }
        // without the consent start() ends in NEEDS_CONSENT, which the Network screen shows
        start(a, askAfterConsent);
    }

    private static void start(Activity a, boolean askNotifications) {
        Map<String, String> v = load(a);
        if (CamexTunnel.check(v) != null) {
            Log.w(TAG, "Network is on, but its settings are not complete");
            return;
        }
        // Android 13+: the tunnel's notification needs the runtime permission
        if (askNotifications && Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
        }
        CamexTunnel.setNotificationLook("Decoder", R.drawable.ic_launcher_foreground);
        CamexTunnel.start(a.getApplicationContext(), v);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static TextView label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(0xFFAAAAAA);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, dp(c, 10), 0, 0);
        return t;
    }

    private static CheckBox check(Context c, String text, boolean on) {
        CheckBox b = new CheckBox(c);
        b.setText(text);
        b.setChecked(on);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return b;
    }

    /** A text field that opens the keyboard on OK only under a remote ({@link TvKeyboard}). */
    private static EditText field(Context c, String value, int inputType) {
        EditText e = new EditText(c);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setSingleLine(true);
        TvKeyboard.field(e, inputType, false);
        return e;
    }

    /** The Network dialog: the fields of the tunnel, the On switch, the state. */
    @android.annotation.SuppressLint("SetTextI18n")   // the app's UI is English only, as the menu
    static void show(Activity a) {
        Map<String, String> values = load(a);
        TextView state = new TextView(a);
        state.setTextColor(Color.WHITE);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), 0);
        content.addView(state);
        Map<String, View> views = new HashMap<>();
        for (Field f : CamexTunnel.fields()) {
            String v = values.get(f.key);
            View view;
            switch (f.kind) {
                case FLAG:
                    view = check(a, f.label, Boolean.parseBoolean(v));
                    break;
                case CHOICE:
                    content.addView(label(a, f.label));
                    RadioGroup g = new RadioGroup(a);
                    g.setOrientation(RadioGroup.HORIZONTAL);
                    for (String choice : f.choices) {
                        RadioButton b = new RadioButton(a);
                        b.setId(View.generateViewId());
                        b.setText(choice);
                        b.setTag(choice);
                        g.addView(b);
                        if (choice.equals(v)) b.setChecked(true);
                    }
                    view = g;
                    break;
                case SECRET:
                    content.addView(label(a, f.label));
                    view = field(a, v, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    break;
                case HOST_PORT:
                    content.addView(label(a, f.label));
                    view = field(a, v, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                    break;
                default:
                    content.addView(label(a, f.label));
                    view = field(a, v, InputType.TYPE_CLASS_TEXT);
                    break;
            }
            content.addView(view);
            views.put(f.key, view);
        }
        CheckBox on = check(a, "On: connect now and at every start", isOn(a));
        content.addView(on);

        // at most about half the screen: a taller view pushes the dialog's buttons off a TV
        final int maxHeight = a.getResources().getDisplayMetrics().heightPixels * 55 / 100;
        ScrollView scroll = new ScrollView(a) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(content);

        CamexTunnel.Listener listener = (s, address, error) -> state.setText(stateText(s, address, error));
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.network_title))
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(d -> {
            TvKeyboard.allowKeyboard(dialog);
            CamexTunnel.addListener(listener);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
                Map<String, String> n = new HashMap<>();
                for (Field f : CamexTunnel.fields()) {
                    View view = views.get(f.key);
                    if (view instanceof CheckBox) {
                        n.put(f.key, String.valueOf(((CheckBox) view).isChecked()));
                    } else if (view instanceof RadioGroup) {
                        RadioGroup g = (RadioGroup) view;
                        View b = g.findViewById(g.getCheckedRadioButtonId());
                        n.put(f.key, b != null ? (String) b.getTag() : f.initial);
                    } else if (view instanceof EditText) {
                        n.put(f.key, ((EditText) view).getText().toString());
                    }
                }
                String[] problem = on.isChecked() ? CamexTunnel.check(n) : null;
                if (problem != null) {
                    View view = views.get(problem[0]);
                    if (view instanceof EditText) ((EditText) view).setError(problem[1]);
                    if (view != null) view.requestFocus();
                    return;
                }
                save(a, n, on.isChecked());
                // on: (re)connect with the saved values (a running tunnel restarts); off: stop
                if (on.isChecked()) connect(a);
                else CamexTunnel.stop();
                dialog.dismiss();
            });
        });
        dialog.setOnDismissListener(d -> CamexTunnel.removeListener(listener));
        dialog.show();
    }

    /** The state line of the Network screen. */
    static String stateText(CamexTunnel.State s, String address, String error) {
        boolean hasAddress = address != null && !address.isEmpty();
        boolean hasError = error != null && !error.isEmpty();
        switch (s) {
            case CONNECTING:
                return "Connecting" + (hasError ? " · " + error : "");
            case CONNECTED:
                return "Connected" + (hasAddress ? " · " + address : "");
            case NEEDS_CONSENT:
                return "VPN consent needed: Save asks again";
            case FAILED:
                return "Failed" + (hasError ? " · " + error : "");
            default:
                return "Stopped";
        }
    }
}
