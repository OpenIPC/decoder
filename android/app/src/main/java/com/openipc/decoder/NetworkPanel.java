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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
 * The library's web port is not asked: it is the port the debug page really listens on
 * ({@link DebugServer#port()}: the one of {@link WebPort} or, when that is taken, the next free
 * one), and the tunnel restarts when that changes (#284). The same dialog holds the page's port
 * and password ({@link WebPassword}).
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
        return load(c, null);
    }

    /**
     * As {@link #load(Context)}; a secret whose sealed value cannot be opened (the Keystore key
     * is gone or not available now) reads as its initial value (empty), and its stored value goes
     * to {@code unreadable}, so that a Save which leaves the field as it is keeps it.
     */
    private static Map<String, String> load(Context c, Map<String, String> unreadable) {
        Map<String, String> v = new HashMap<>();
        CredentialStore store = null;
        for (Field f : fields(c)) {
            if (fixed(f)) continue;
            String stored = prefs(c).getString(PREFIX + f.key, null);
            String s = stored;
            if (s != null && f.kind == Field.Kind.SECRET && !s.isEmpty()) {
                if (store == null) store = CredentialStore.create();
                s = store.open(s);
                if (s == null) {
                    Log.w(TAG, "Network: the stored " + f.key + " cannot be read, enter it again");
                    if (unreadable != null) unreadable.put(f.key, stored);
                }
            }
            // an empty client name (saved by an older version) takes the default one too
            if (s == null || (s.isEmpty() && CamexTunnel.NAME.equals(f.key))) s = f.initial;
            v.put(f.key, s);
        }
        // nginx on the server reaches the box's web page there (camex 3.19.0, needs that server);
        // camex 4.1.2 lets in from the tunnel only this port, held by this app (camex#187)
        int web = DebugServer.get().port();
        v.put(CamexTunnel.WEB_PORT, web > 0 ? String.valueOf(web) : "");   // off: none (#286)
        return v;
    }

    /**
     * The library's fields (camex 4.2.0, #286): their order, labels, hints and the default client
     * name, decoder-<the last 4 of ANDROID_ID>.
     */
    private static java.util.List<Field> fields(Context c) {
        return CamexTunnel.fields(c, "decoder");
    }

    /** A field of the library that Decoder sets itself, not shown: the web port. */
    private static boolean fixed(Field f) {
        return CamexTunnel.WEB_PORT.equals(f.key);
    }

    private static void save(Context c, Map<String, String> v, Map<String, String> unreadable,
                             boolean on) {
        SharedPreferences.Editor e = prefs(c).edit();
        CredentialStore store = CredentialStore.create();
        for (Field f : fields(c)) {
            if (fixed(f)) continue;
            String s = v.get(f.key);
            if (f.kind == Field.Kind.SECRET) {
                // an unreadable secret left as the form showed it (empty) is kept as it was
                String old = unreadable.get(f.key);
                if (s == null || s.isEmpty()) s = old != null ? old : "";
                else s = store.seal(s);
            }
            e.putString(PREFIX + f.key, s == null ? "" : s);
        }
        e.putBoolean(KEY_ON, on).apply();
    }

    // The open Network dialog, dismissed with its activity (Decoder.onDestroy)
    private static java.lang.ref.WeakReference<AlertDialog> openDialog;

    /** Closes the Network dialog, if it is open (the activity goes away). */
    static void dismiss() {
        AlertDialog d = openDialog != null ? openDialog.get() : null;
        openDialog = null;
        if (d != null && d.isShowing()) {
            try {
                d.dismiss();
            } catch (RuntimeException e) {
                // the window is already gone with its activity
            }
        }
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

    /** The web port the running tunnel was given; 0 before the first start. */
    private static volatile int announcedPort;

    /** A start that waits for the debug page's port (see {@link #start}); main thread only. */
    private static Runnable pendingStart;
    /** The activity's refresh of the page's address on screen; main thread only (Qodo on #285). */
    static Runnable onWebPort;

    /**
     * The debug page tried its port: it got this one (at the start, or another one after a
     * change in Network or when the set one is taken), or none was free and this is the one
     * asked for. A start that waited for it goes on now; a running tunnel given another port
     * restarts with this one, so the server's nginx and camex's gate point at the page (#284).
     * Any thread.
     */
    static void webPortSettled(int port) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            Runnable r = pendingStart;
            pendingStart = null;
            // not if Network was saved off meanwhile (Qodo on #285)
            if (r != null && App.context != null && isOn(App.context)) r.run();
            Runnable shown = onWebPort;
            if (shown != null) shown.run();
            WebService.refresh();   // after an update: its notification tells the real port
        });
        Context c = App.context;
        if (c == null || port == announcedPort || !CamexTunnel.running() || !isOn(c)) return;
        main.post(() -> {
            if (port == announcedPort || !CamexTunnel.running()) return;
            Map<String, String> v = load(c);
            if (CamexTunnel.check(v) != null) return;
            Log.i(TAG, "Network: the web page is on port " + port + " now, the tunnel restarts");
            announcedPort = port;
            CamexTunnel.start(c.getApplicationContext(), v);
        });
    }

    private static void start(Activity a, boolean askNotifications) {
        // the tunnel tells the server the page's port: right after the app's start the page may
        // not have it yet (8085 taken: 8086); wait for it rather than restart the tunnel then
        if (!DebugServer.get().settled()) {
            pendingStart = () -> start(a, askNotifications);
            return;
        }
        pendingStart = null;
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
        String web = v.get(CamexTunnel.WEB_PORT);
        announcedPort = web == null || web.isEmpty() ? 0 : Integer.parseInt(web);
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
        Map<String, String> unreadable = new HashMap<>();
        Map<String, String> values = load(a, unreadable);
        TextView state = new TextView(a);
        // the tunnel's state under Join, amber monospace as a display line (#286)
        state.setTextColor(Brass.color(a, R.color.amber));
        state.setTypeface(android.graphics.Typeface.MONOSPACE);
        state.setTag(R.id.brass_keep, true);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), 0);
        // one SETTINGS dialog, as in our other apps (#286): DECODER, then CAMEX
        content.addView(Brass.band(a, "Decoder", false));
        CheckBox remote = check(a, "Allow remote access", WebPort.allowed(a));
        content.addView(remote);
        // the web page: its port (8085 unless set; the next free one when taken) and password,
        // from the local network and the tunnel alike
        int webPort = DebugServer.get().port();
        content.addView(label(a, "Port" + (webPort != 0 && webPort != WebPort.get(a)
                ? " (taken: now " + webPort + ")" : "")));
        EditText port = field(a, WebPort.text(a), InputType.TYPE_CLASS_NUMBER);
        port.setHint("empty: " + WebPort.DEFAULT + "; taken: the next free");
        content.addView(port);
        String webPassword = WebPassword.read(a);
        final String keepWebPassword = webPassword == null ? WebPassword.stored(a) : null;
        content.addView(label(a, webPassword != null
                ? "Web page password"
                : "Web page password: the stored one cannot be read, the page is locked;"
                        + " enter it again or a new one"));
        EditText web = field(a, webPassword != null ? webPassword : "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        web.setHint("empty: the page is open");
        content.addView(web);
        // only then: a way back to an open page (an empty field keeps the unreadable one)
        CheckBox removeWeb = keepWebPassword != null
                ? check(a, "Remove the stored password: the page is open", false) : null;
        if (removeWeb != null) content.addView(removeWeb);

        content.addView(Brass.band(a, "Camex", true));
        CheckBox on = check(a, "Join a camex server", isOn(a));
        content.addView(on);
        content.addView(state);
        Map<String, View> views = new HashMap<>();
        for (Field f : fields(a)) {
            if (fixed(f)) continue;
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
            if (view instanceof EditText && f.hint != null && !f.hint.isEmpty()) {
                ((EditText) view).setHint(f.hint);
            }
            // a key is hidden, with "Show the key" under it (the dots, not the input type: the
            // field's type is TvKeyboard's)
            if (f.kind == Field.Kind.SECRET && view instanceof EditText) {
                EditText key = (EditText) view;
                CheckBox show = check(a, Field.SHOW_SECRET, false);
                show.setOnCheckedChangeListener((b, shown) -> {
                    int at = key.getSelectionEnd();
                    key.setTransformationMethod(shown ? null
                            : android.text.method.PasswordTransformationMethod.getInstance());
                    if (at >= 0) key.setSelection(Math.min(at, key.length()));
                });
                content.addView(show);
            }
        }

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
        AlertDialog dialog = Brass.dialog(a)
                .setTitle("Settings")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(d -> {
            TvKeyboard.allowKeyboard(dialog);
            CamexTunnel.addListener(listener);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
                Map<String, String> n = new HashMap<>();
                for (Field f : fields(a)) {
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
                String badPort = WebPort.check(port.getText().toString());
                if (badPort != null) {
                    port.setError(badPort);
                    port.requestFocus();
                    return;
                }
                String[] problem = on.isChecked() ? CamexTunnel.check(n) : null;
                if (problem != null) {
                    View view = views.get(problem[0]);
                    if (view instanceof EditText) ((EditText) view).setError(problem[1]);
                    if (view != null) view.requestFocus();
                    return;
                }
                save(a, n, unreadable, on.isChecked());
                // a new port, or the page turned on or off: it moves now (and a running tunnel is
                // told it below)
                boolean moved = WebPort.setAllowed(a, remote.isChecked());
                if (WebPort.save(a, port.getText().toString())) moved = true;
                if (moved) DebugServer.get().rebind();
                WebPassword.save(a, web.getText().toString(),
                        removeWeb != null && removeWeb.isChecked() ? null : keepWebPassword);
                // on: (re)connect with the saved values (a running tunnel restarts); off: stop
                if (on.isChecked()) connect(a);
                else {
                    pendingStart = null;
                    CamexTunnel.stop();
                }
                dialog.dismiss();
            });
        });
        dialog.setOnDismissListener(d -> {
            CamexTunnel.removeListener(listener);
            if (openDialog != null && openDialog.get() == dialog) openDialog = null;
        });
        dismiss();
        openDialog = new java.lang.ref.WeakReference<>(dialog);
        Brass.frame(dialog);
        dialog.show();
        Brass.style(dialog);
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
