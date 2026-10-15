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
    /** The item a remote starts from: the active slot, Grid or UDP (#91, #177). */
    private TextView mFocusItem;
    private TextView mSettingsBtn;
    private TextView mWebUiBtn;
    TextView[] camButtons;
    private Dialog mBrowserDialog;
    /** The URL editor or the Reset confirmation while it is shown (#205). */
    private Dialog mDialog;
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
        /** A long press on Debug: stats and log saved as one file, then shared (#207). */
        void onSaveReport();
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

    /**
     * The menu, the open dialog and the WebUI closed before the activity finishes: a window
     * still showing then is logged as leaked (#205).
     */
    void dismissAll() {
        PopupWindow popup = mMenuPopup;
        mMenuPopup = null;
        Dialog dialog = mDialog;
        mDialog = null;
        try {
            if (popup != null && popup.isShowing()) popup.dismiss();
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing the menu", e);
        }
        onPauseDismissBrowser();
    }

    private void track(Dialog dialog) {
        mDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (mDialog == d) mDialog = null;
        });
    }

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

        // One grid (#177): the 8 slots on top, two rows of four words below, each word cell
        // exactly two slots wide. Every cell has the width of the widest word, so the columns
        // line up and nothing is clipped
        int cell = wordCellWidth();
        // Never wider than the screen allows (4 cells and the popup's margins): a large font
        // or a narrow screen shortens the labels with an ellipsis instead (Qodo on #178)
        int avail = activity.getResources().getDisplayMetrics().widthPixels - 2 * dp(12);
        if (avail > 0) cell = Math.min(cell, avail / 4);
        int half = cell / 2;
        cell = half * 2;

        LinearLayout camRow = gridRow(layout);
        camButtons = new TextView[CAM_COUNT];
        mCachedCamButtons = new TextView[CAM_COUNT];
        for (int i = 0; i < CAM_COUNT; i++) {
            final int slot = i;
            camButtons[i] = createItem(String.valueOf(i + 1));
            mCachedCamButtons[i] = camButtons[i];
            camButtons[i].setGravity(Gravity.CENTER);
            camButtons[i].setPadding(0, dp(8), 0, dp(8));
            camRow.addView(camButtons[i], new LinearLayout.LayoutParams(half,
                    LinearLayout.LayoutParams.MATCH_PARENT));
            if (i == mActive && single) highlightItem(camButtons[i]);
            applyQualityColor(camButtons[i], i, !single, mActive, mHosts);
            camButtons[i].setOnClickListener(v -> {
                if (listener != null) listener.onSwitchCamera(slot);
                PopupWindow p = mMenuPopup;
                if (p != null) p.dismiss();
            });
        }

        // Settings | WebUI | Grid | Slides
        LinearLayout modeRow = gridRow(layout);
        mSettingsBtn = wordItem(modeRow, "Settings", cell);
        mWebUiBtn = wordItem(modeRow, "WebUI", cell);
        // Grid is the view, Slides the playback mode: both may be on (#153)
        final TextView quadBtn = wordItem(modeRow, "Grid", cell);
        if (quadEnabled) highlightItem(quadBtn);
        mCachedQuadBtn = quadBtn;
        quadBtn.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            if (listener != null) listener.onToggleQuad();
        });
        // Slides: every cell on key frames only (#108)
        final TextView slidesBtn = wordItem(modeRow, "Slides", cell);
        if (slidesShown) highlightItem(slidesBtn);
        mCachedSlidesBtn = slidesBtn;
        slidesBtn.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            if (listener != null) listener.onToggleSlides();
        });

        // Exit | Reset | Debug | UDP
        LinearLayout actionRow = gridRow(layout);
        // The version under Exit, small (#176): beside it, it would widen every cell
        String ver = "v" + version + ", " + gitHash;
        SpannableString s = new SpannableString("Exit\n" + ver);
        s.setSpan(new RelativeSizeSpan(0.5f), 5, s.length(), 0);
        TextView exit = wordItem(actionRow, "Exit", cell);
        exit.setSingleLine(false);
        exit.setText(s);
        exit.setOnClickListener(v -> {
            if (listener != null) listener.onExit();
        });
        // Two deliberate presses: Reset, then Clear settings in the confirmation (#171)
        TextView reset = wordItem(actionRow, "Reset", cell);
        reset.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            confirmReset();
        });
        // Debug: the text in the multi-view cells, highlighted while on
        final TextView statsBtn = wordItem(actionRow, "Debug", cell);
        if (statsShown) highlightItem(statsBtn);
        mCachedStatsBtn = statsBtn;
        statsBtn.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            if (listener != null) listener.onToggleStats();
        });
        // Save report (#207), no cell of its own: the grid keeps its shape (#177). A long
        // touch, a long OK on a remote (View does it for DPAD_CENTER and ENTER), or Menu
        // while Debug has the focus, for remotes whose OK has no long press
        statsBtn.setOnLongClickListener(v -> {
            saveReport();
            return true;
        });
        statsBtn.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_MENU) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) saveReport();
            return true;
        });
        // UDP: the reserved UDP RTP cell full screen (#68, #87)
        final TextView udpBtn = wordItem(actionRow, "UDP", cell);
        if (udpShown) highlightItem(udpBtn);
        mCachedUdpBtn = udpBtn;
        udpBtn.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            if (listener != null) listener.onShowUdp();
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
        setSingleViewItems(single);
        linkRemote(camButtons,
                new TextView[]{mSettingsBtn, mWebUiBtn, quadBtn, slidesBtn},
                new TextView[]{exit, reset, statsBtn, udpBtn});

        mMenuLayout = layout;
        mMenuIsCached = true;
        mFocusItem = focusItem(quadEnabled, mActive);
        focusForKeys(layout, mFocusItem);
    }

    private void saveReport() {
        PopupWindow p = mMenuPopup;
        if (p != null) p.dismiss();
        if (listener != null) listener.onSaveReport();
    }

    private void updateCachedMenu(boolean quadEnabled, int mActive, String[] mHosts) {
        boolean single = !quadEnabled && !udpShown;
        setSingleViewItems(single);
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

    /** A row of the menu grid (#177), as wide as the menu. */
    private LinearLayout gridRow(LinearLayout menu) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        menu.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** A word cell of the menu grid: one width for all, the label centred (#176, #177). */
    private TextView wordItem(LinearLayout row, String title, int width) {
        TextView item = createItem(title);
        item.setGravity(Gravity.CENTER);
        item.setSingleLine(true);
        item.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.setPadding(dp(2), dp(8), dp(2), dp(8));
        row.addView(item, new LinearLayout.LayoutParams(width,
                LinearLayout.LayoutParams.MATCH_PARENT));
        return item;
    }

    /** Width of a word cell: the widest label of the lower rows, or two slots if wider. */
    private int wordCellWidth() {
        android.text.TextPaint paint = new android.text.TextPaint();
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18,
                activity.getResources().getDisplayMetrics()));
        float widest = 0;
        for (String w : new String[]{"Settings", "WebUI", "Grid", "Slides",
                "Exit", "Reset", "Debug", "UDP"}) {
            widest = Math.max(widest, paint.measureText(w));
        }
        int slot = (int) Math.ceil(paint.measureText("8")) + 2 * dp(12);
        return Math.max((int) Math.ceil(widest) + 2 * dp(10), 2 * slot);
    }

    /**
     * Settings and WebUI work in the single view only. Elsewhere they stay in place, grey and
     * skipped by the remote, so the grid does not change shape (#177).
     */
    private void setSingleViewItems(boolean single) {
        for (TextView item : new TextView[]{mSettingsBtn, mWebUiBtn}) {
            if (item == null) continue;
            item.setEnabled(single);
            item.setFocusable(single);
            item.setTextColor(single ? Color.WHITE : 0xFF555555);
        }
    }

    /**
     * Remote moves in the grid (#177): Down from a slot to the word cell under it, Up from a
     * word cell to the left one of its two slots; a grey cell passes the move on.
     */
    private static void linkRemote(TextView[] slots, TextView[] mid, TextView[] low) {
        for (TextView v : slots) v.setId(View.generateViewId());
        for (TextView v : mid) v.setId(View.generateViewId());
        for (TextView v : low) v.setId(View.generateViewId());
        for (int i = 0; i < slots.length; i++) {
            slots[i].setNextFocusDownId(mid[Math.min(i / 2, mid.length - 1)].getId());
        }
        for (int j = 0; j < mid.length; j++) {
            mid[j].setNextFocusUpId(slots[Math.min(2 * j, slots.length - 1)].getId());
            mid[j].setNextFocusDownId(low[j].getId());
            low[j].setNextFocusUpId(mid[j].getId());
        }
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
        track(dialog);
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
        track(dialog);
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
        // Android 15 draws apps edge to edge: the window starts under the status bar, which
        // covered Close (#169). Keep the content inside the system bars and the cutout; the
        // keyboard is still handled by adjustResize
        layout.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                l = bars.left;
                t = bars.top;
                r = bars.right;
                b = bars.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
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
