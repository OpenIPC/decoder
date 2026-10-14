/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * MenuManager.java — popup menu, URL editor, WebUI browser
 *
 */

package com.openipc.decoder;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.HttpAuthHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import java.util.HashSet;
import java.util.Set;

class MenuManager {
    private static final String TAG = "OpenIPCDecoder";
    private static final int CAM_COUNT = Decoder.CAM_COUNT;
    private static final String DEFAULT_URL = "rtsp://root:12345@192.168.1.10:554/stream=0";

    private final Decoder activity;
    private final float mDensity;

    // Menu state
    private PopupWindow mMenuPopup;
    private LinearLayout mMenuLayout;
    private TextView[] mCachedCamButtons;
    private TextView mCachedQuadBtn;
    private TextView mCachedSlidesBtn;
    /** Slide mode (#108): multi-view with every cell on key frames only. */
    private boolean slidesShown;
    private TextView mCachedUdpBtn;
    private TextView mCachedStatsBtn;
    private boolean statsShown;
    /** The UDP RTP view is shown: neither a slot nor multi-view is active. */
    private boolean udpShown;
    private boolean mMenuIsCached;
    /** The item a remote starts from: the active slot, Q or U (#91). */
    private TextView mFocusItem;
    private TextView mSettingsBtn;
    /** Settings | WebUI, shown in the single view only (#173). */
    private LinearLayout mSettingsRow;
    private TextView mWebUiBtn;
    TextView[] camButtons;
    private Dialog mBrowserDialog;
    private int mActiveQualityColor = Color.WHITE;

    interface Listener {
        void onSwitchCamera(int slot);
        void onToggleQuad();
        /** S: slide mode on or off — multi-view on key frames only (#108). */
        void onToggleSlides();
        /** U: the reserved UDP RTP cell full-screen (#68). */
        void onShowUdp();
        /** D: debug text (resolution, codec, fps) in every multi-view cell on or off. */
        void onToggleStats();
        void onExit();
        /** Reset: every stored setting cleared, the app restarted as on first start (#171). */
        void onReset();
        void onShowSettings();
        void onSaveSettings();
        void onWebUI();
    }

    private Listener listener;

    MenuManager(Decoder activity, float density) {
        this.activity = activity;
        this.mDensity = density;
    }

    void setListener(Listener cb) { this.listener = cb; }

    /** Update quality color (called from RtspClient callback). */
    void updateQuality(int latency) {
        if (latency < 0) {
            mActiveQualityColor = Color.WHITE;
        } else if (latency < 100) {
            mActiveQualityColor = 0xFF00FF00;
        } else if (latency < 300) {
            mActiveQualityColor = 0xFFFFFF00;
        } else {
            mActiveQualityColor = 0xFFFF0000;
        }
    }

    void setUdpShown(boolean shown) { udpShown = shown; }

    void setStatsShown(boolean shown) { statsShown = shown; }

    void setSlidesShown(boolean shown) { slidesShown = shown; }

    void applyQualityToActive(boolean quadEnabled, int mActive) {
        if (camButtons != null && !quadEnabled && !udpShown) {
            camButtons[mActive].setTextColor(mActiveQualityColor);
        }
    }

    void dismissPopup() {
        if (mMenuPopup != null) {
            mMenuPopup.dismiss();
            mMenuPopup = null;
        }
    }

    boolean isPopupShowing() { return mMenuPopup != null; }

    void showMenu(View anchor, boolean quadEnabled, int mActive, String version,
                  String gitHash, String[] mHosts) {
        boolean single = !quadEnabled && !udpShown;
        if (mMenuIsCached) {
            updateCachedMenu(quadEnabled, mActive, mHosts);
            if (mMenuPopup != null) return;
            mMenuPopup = new PopupWindow(mMenuLayout,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, true);
            int margin = dp(12);
            mMenuPopup.showAtLocation(anchor, Gravity.TOP | Gravity.START, margin, margin);
            mMenuPopup.setOnDismissListener(() -> mMenuPopup = null);
            mFocusItem = focusItem(quadEnabled, mActive);
            focusForKeys(mMenuLayout, mFocusItem);
            return;
        }

        // The popup is focusable and takes every key; on its own it only closes on Back,
        // so Escape (keyboards) is handled here as well
        LinearLayout layout = new LinearLayout(activity) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.getAction() == KeyEvent.ACTION_UP) dismissPopup();
                    return true;
                }
                // While the layout itself has the focus (opened by touch, #65), a D-pad key
                // finds no item: they all lie inside it, not beside it. The first key moves
                // the focus to the active item instead (#91)
                if (findFocus() == this && isNavigationKey(event.getKeyCode())
                        && event.getAction() == KeyEvent.ACTION_DOWN) {
                    TextView item = mFocusItem;
                    // Consumed only if the focus really moved; otherwise the key goes on
                    if (item != null && item.requestFocus()) return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setFocusableInTouchMode(true);

        PopupWindow popup = new PopupWindow(layout, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        int margin = dp(12);
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, margin, margin);
        mMenuPopup = popup;
        popup.setOnDismissListener(() -> mMenuPopup = null);

        LinearLayout camRow = new LinearLayout(activity);
        camRow.setOrientation(LinearLayout.HORIZONTAL);
        // 8 slots + U D Q (#13, #93) may not fit a narrow screen or a large font: the row scrolls,
        // and follows the focus when moving with a remote
        HorizontalScrollView camScroll = new HorizontalScrollView(activity);
        camScroll.setHorizontalScrollBarEnabled(false);
        camScroll.addView(camRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(camScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mSettingsBtn = createItem("Settings");

        final TextView quadBtn = createItem("Q");
        quadBtn.setGravity(Gravity.CENTER);
        quadBtn.setPadding(dp(12), dp(8), dp(12), dp(8));

        mWebUiBtn = createItem("WebUI");
        // Two items per row keep the menu short (#173); the row is for the single view only
        mSettingsRow = pairRow(mSettingsBtn, mWebUiBtn);
        layout.addView(mSettingsRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        mSettingsRow.setVisibility(single ? View.VISIBLE : View.GONE);

        camButtons = new TextView[CAM_COUNT];
        mCachedCamButtons = new TextView[CAM_COUNT];
        for (int i = 0; i < CAM_COUNT; i++) {
            final int slot = i;
            camButtons[i] = createItem(String.valueOf(i + 1));
            mCachedCamButtons[i] = camButtons[i];
            camButtons[i].setGravity(Gravity.CENTER);
            camButtons[i].setPadding(dp(12), dp(8), dp(12), dp(8));
            camRow.addView(camButtons[i], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            if (i == mActive && single) {
                highlightItem(camButtons[i]);
                applyQualityColor(camButtons[i], i, !single, mActive, mHosts);
            } else {
                applyQualityColor(camButtons[i], i, !single, mActive, mHosts);
            }

            camButtons[i].setOnClickListener(v -> {
                if (listener != null) listener.onSwitchCamera(slot);
                PopupWindow p = mMenuPopup;
                if (p != null) p.dismiss();
            });
        }

        // U after the slots: the reserved UDP RTP cell of the 3x3 grid, full-screen (#68)
        final TextView udpBtn = createItem("U");
        udpBtn.setGravity(Gravity.CENTER);
        udpBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        camRow.addView(udpBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (udpShown) highlightItem(udpBtn);
        mCachedUdpBtn = udpBtn;
        udpBtn.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            if (listener != null) listener.onShowUdp();
        });

        // D after U: the debug text in the multi-view cells, highlighted while on
        final TextView statsBtn = createItem("D");
        statsBtn.setGravity(Gravity.CENTER);
        statsBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        camRow.addView(statsBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (statsShown) highlightItem(statsBtn);
        mCachedStatsBtn = statsBtn;
        statsBtn.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            if (listener != null) listener.onToggleStats();
        });

        // Q, then S last: 1 2 3 4 5 6 7 8 U D Q S (#93, #108)
        camRow.addView(quadBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // Q is the view, S the playback mode: both may be on (#153)
        if (quadEnabled) highlightItem(quadBtn);
        mCachedQuadBtn = quadBtn;
        quadBtn.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            if (listener != null) listener.onToggleQuad();
        });

        final TextView slidesBtn = createItem("S");
        slidesBtn.setGravity(Gravity.CENTER);
        slidesBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        camRow.addView(slidesBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (slidesShown) highlightItem(slidesBtn);
        mCachedSlidesBtn = slidesBtn;
        slidesBtn.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            if (listener != null) listener.onToggleSlides();
        });

        String code = "Exit [v" + version + ", " + gitHash + "]";
        SpannableString s = new SpannableString(code);
        s.setSpan(new SuperscriptSpan(), 5, s.length(), 0);
        s.setSpan(new RelativeSizeSpan(0.5f), 5, s.length(), 0);

        View divider = new View(activity);
        divider.setBackgroundColor(Color.DKGRAY);
        layout.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // Two deliberate presses: Reset, then Clear settings in the confirmation (#171)
        TextView reset = createItem("Reset");
        reset.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            confirmReset();
        });

        TextView exit = createItem("Exit");
        exit.setText(s);
        layout.addView(pairRow(exit, reset), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        exit.setOnClickListener(v -> {
            if (listener != null) listener.onExit();
        });

        mSettingsBtn.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            if (listener != null) listener.onShowSettings();
        });

        mWebUiBtn.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            if (listener != null) listener.onWebUI();
        });

        mMenuLayout = layout;
        mMenuIsCached = true;
        mFocusItem = focusItem(quadEnabled, mActive);
        focusForKeys(layout, mFocusItem);
    }

    private void updateCachedMenu(boolean quadEnabled, int mActive, String[] mHosts) {
        boolean single = !quadEnabled && !udpShown;
        if (mSettingsRow != null) {
            mSettingsRow.setVisibility(single ? View.VISIBLE : View.GONE);
        }
        if (mCachedQuadBtn != null) {
            if (quadEnabled) highlightItem(mCachedQuadBtn);
            else resetItem(mCachedQuadBtn);
        }
        if (mCachedSlidesBtn != null) {
            if (slidesShown) highlightItem(mCachedSlidesBtn);
            else resetItem(mCachedSlidesBtn);
        }
        if (mCachedUdpBtn != null) {
            if (udpShown) highlightItem(mCachedUdpBtn);
            else resetItem(mCachedUdpBtn);
        }
        if (mCachedStatsBtn != null) {
            if (statsShown) highlightItem(mCachedStatsBtn);
            else resetItem(mCachedStatsBtn);
        }
        if (mCachedCamButtons != null && camButtons != null) {
            for (int i = 0; i < CAM_COUNT; i++) {
                if (mCachedCamButtons[i] == null || camButtons[i] == null) continue;
                if (i == mActive && single) {
                    highlightItem(camButtons[i]);
                    applyQualityColor(camButtons[i], i, !single, mActive, mHosts);
                } else {
                    resetItem(camButtons[i]);
                    applyQualityColor(camButtons[i], i, !single, mActive, mHosts);
                }
            }
        }
    }

    /**
     * A menu opened by touch has no focused view, so keys never reach the layout and Escape
     * did not close it. In touch mode the layout itself takes focus (#65); opened with a
     * remote, the active item does (#91).
     */
    private static void focusForKeys(View layout, View item) {
        if (layout.isInTouchMode() || item == null) layout.requestFocus();
        else item.requestFocus();
    }

    /** The highlighted item of the slot row: the active slot, Q in multi-view, U. */
    private TextView focusItem(boolean quadEnabled, int mActive) {
        if (udpShown && mCachedUdpBtn != null) return mCachedUdpBtn;
        if (quadEnabled && mCachedQuadBtn != null) return mCachedQuadBtn;
        if (camButtons != null && mActive >= 0 && mActive < camButtons.length) {
            return camButtons[mActive];
        }
        return null;
    }

    static boolean isNavigationKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_TAB:
                return true;
            default:
                return false;
        }
    }

    /**
     * Two menu items side by side, split by a thin line (#173): the left one takes the free
     * width, the right one its own. Left / Right move between them with a remote.
     */
    private LinearLayout pairRow(TextView left, TextView right) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(left, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        View line = new View(activity);
        line.setBackgroundColor(Color.DKGRAY);
        row.addView(line, new LinearLayout.LayoutParams(dp(1),
                LinearLayout.LayoutParams.MATCH_PARENT));
        row.addView(right, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView createItem(String title) {
        TextView text = new TextView(activity);
        text.setText(title);
        text.setPadding(dp(8), dp(6), dp(8), dp(6));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        // Reachable with a remote (#91); not only by the clickable default of newer Androids
        text.setFocusable(true);
        focusChange(text);
        return text;
    }

    /**
     * One cue per state (#143): on / active (D on, Q, S, U, the active slot) is the fill, an
     * orange button like a pressed key; the focus is the frame only, thick and light blue. A
     * coloured border for both read as the same selector on a phone (#114).
     */
    private static final int ACTIVE_FILL = 0xFFB85C00;
    private static final int FOCUS_BORDER = 0xFF40C4FF;
    private final Set<View> activeItems = new HashSet<>();

    private void highlightItem(TextView item) {
        activeItems.add(item);
        paintItem(item, item.hasFocus());
    }

    private void resetItem(TextView item) {
        item.setTextColor(Color.WHITE);
        activeItems.remove(item);
        paintItem(item, item.hasFocus());
    }

    /**
     * Active items are filled orange, the focused item has a thick light blue frame; an item
     * that is both keeps the orange fill inside the blue frame, so neither state hides.
     */
    private void paintItem(View item, boolean focused) {
        boolean active = activeItems.contains(item);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? ACTIVE_FILL : Color.BLACK);
        if (focused) bg.setStroke(dp(3), FOCUS_BORDER);
        else bg.setStroke(1, Color.GRAY);
        item.setBackground(bg);
    }

    private void applyQualityColor(TextView btn, int slot, boolean quadEnabled,
                                    int mActive, String[] mHosts) {
        if (mHosts[slot] == null || mHosts[slot].isEmpty() || mHosts[slot].equals(DEFAULT_URL)) {
            btn.setTextColor(0xFF666666);
        } else if (slot == mActive && !quadEnabled) {
            btn.setTextColor(mActiveQualityColor);
        } else {
            btn.setTextColor(Color.WHITE);
        }
    }

    private EditText createEdit(String title) {
        EditText text = new EditText(activity);
        text.setText(title);
        text.setPadding(dp(8), dp(8), dp(8), dp(8));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        text.setMaxLines(1);
        text.setImeOptions(EditorInfo.IME_ACTION_DONE);
        text.setSelection(0);
        focusChange(text);
        return text;
    }

    void showUrlEditor(int mActive, String[] mHosts, Transport.Mode[] mTransports) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Camera " + (mActive + 1) + " URL");

        final EditText input = new EditText(activity);
        input.setText(mHosts[mActive]);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSelectAllOnFocus(true);
        input.setSelection(input.getText().length());

        // RTP transport of the slot (#67, #69): Auto and Multicast fall back to TCP
        RadioGroup transport = new RadioGroup(activity);
        transport.setOrientation(RadioGroup.HORIZONTAL);
        Transport.Mode[] modes = Transport.Mode.values();
        String[] labels = {"TCP", "UDP", "Auto", "Multicast"};
        for (int i = 0; i < modes.length; i++) {
            RadioButton b = new RadioButton(activity);
            b.setId(View.generateViewId());
            b.setText(labels[i]);
            b.setTag(modes[i]);
            transport.addView(b);
            if (modes[i] == mTransports[mActive]) b.setChecked(true);
        }

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), 0);
        content.addView(input);
        content.addView(transport);
        builder.setView(content);

        final int slot = mActive;
        builder.setPositiveButton("Save", (dialog, which) -> {
            String url = input.getText().toString().trim();
            mHosts[slot] = Decoder.sanitizeUrl(url);
            View checked = transport.findViewById(transport.getCheckedRadioButtonId());
            if (checked != null) mTransports[slot] = (Transport.Mode) checked.getTag();
            if (listener != null) listener.onSaveSettings(); // triggers save + reconnect
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        input.requestFocus();
    }

    /**
     * Asks before clearing the settings (#171). Cancel has the focus, so a stray OK on a remote
     * closes the dialog without deleting anything.
     */
    private void confirmReset() {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Start with a clean slate?")
                .setMessage("This clears all camera slots, their transports and the other "
                        + "settings, so you can set the app up again from scratch. The app "
                        + "restarts and opens as right after installing. Your cameras are not "
                        + "changed.")
                .setNegativeButton("Cancel", (d, which) -> d.cancel())
                .setPositiveButton("Clear settings", (d, which) -> {
                    if (listener != null) listener.onReset();
                })
                .create();
        dialog.setOnShowListener(d -> {
            View cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (cancel != null) cancel.requestFocus();
        });
        dialog.show();
    }

    private void focusChange(View item) {
        paintItem(item, false);
        item.setOnFocusChangeListener((v, hasFocus) -> paintItem(v, hasFocus));
    }

    @SuppressLint("SetJavaScriptEnabled")
    void startBrowser(String mHost) {
        Uri uri = Uri.parse(mHost);
        String link = uri.getHost();
        if (link == null) {
            Log.w(TAG, "Cannot open WebUI: invalid host in URL");
            return;
        }

        WebView view = new WebView(activity);
        view.getSettings().setJavaScriptEnabled(true);

        final Uri finalUri = uri;
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedHttpAuthRequest(
                    WebView v, HttpAuthHandler handler, String h, String realm) {
                String userInfo = finalUri.getUserInfo();
                // Only the camera itself gets its credentials, never a host reached by a redirect or link
                if (userInfo != null && link.equalsIgnoreCase(h)) {
                    String[] parts = userInfo.split(":", 2);
                    if (parts.length == 2) {
                        handler.proceed(parts[0], parts[1]);
                        return;
                    }
                }
                handler.cancel();
            }
        });

        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equals(scheme) && !"https".equals(scheme))) {
            scheme = "http";
        }

        // Full screen, not an AlertDialog around the page (#160): a small dialog barely moved
        // for the on-screen keyboard, which covered the page's fields, and it relaid out as
        // the keyboard came and went. The theme has no windowFullscreen, so the window resizes
        // above the keyboard and the page scrolls its focused field into view
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar);
        // Back goes back in the page first: a key on older Androids, the back callback
        // (also the predictive gesture) on Android 13+
        Runnable back = () -> {
            if (view.canGoBack()) view.goBack();
            else dialog.cancel();
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, back::run);
        } else {
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) back.run();
                return true;
            });
        }
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        // Close for a remote or a touchscreen
        TextView closeBtn = createItem("Close");
        closeBtn.setOnClickListener(v -> dialog.cancel());
        layout.addView(closeBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        dialog.setContentView(layout);
        dialog.setOnDismissListener(d -> {
            mBrowserDialog = null;
            view.destroy();
        });
        mBrowserDialog = dialog;
        dialog.show();
        // The page's text fields need the keyboard (#120): the window must take input focus
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.requestFocus(View.FOCUS_DOWN);
        view.loadUrl(scheme + "://" + link);
    }

    private int dp(float dp) {
        return (int) (dp * mDensity + 0.5f);
    }

    void onPauseDismissBrowser() {
        Dialog browser = mBrowserDialog;
        mBrowserDialog = null;
        if (browser != null && browser.isShowing()) {
            try { browser.dismiss(); } catch (Exception e) {
                Log.e(TAG, "Error dismissing browser dialog", e);
            }
        }
    }

}
