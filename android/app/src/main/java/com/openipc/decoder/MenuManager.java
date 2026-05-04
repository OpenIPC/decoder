/*
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
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
import android.text.InputType;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.HttpAuthHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

class MenuManager {
    private static final String TAG = "OpenIPCDecoder";
    private static final int CAM_COUNT = 4;
    private static final String DEFAULT_URL = "rtsp://root:12345@192.168.1.10:554/stream=0";

    private final Decoder activity;
    private final float mDensity;

    // Menu state
    private PopupWindow mMenuPopup;
    private LinearLayout mMenuLayout;
    private TextView[] mCachedCamButtons;
    private TextView mCachedQuadBtn;
    private boolean mMenuIsCached;
    private TextView mSettingsBtn;
    private TextView mWebUiBtn;
    TextView[] camButtons;
    private Dialog mBrowserDialog;
    private int mActiveQualityColor = Color.WHITE;

    interface Listener {
        void onSwitchCamera(int slot);
        void onToggleQuad();
        void onExit();
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

    void applyQualityToActive(boolean quadEnabled, int mActive) {
        if (camButtons != null && !quadEnabled) {
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
        if (mMenuIsCached) {
            updateCachedMenu(quadEnabled, mActive, mHosts);
            if (mMenuPopup != null) return;
            mMenuPopup = new PopupWindow(mMenuLayout,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, true);
            int margin = dp(12);
            mMenuPopup.showAtLocation(anchor, Gravity.TOP | Gravity.START, margin, margin);
            mMenuPopup.setOnDismissListener(() -> mMenuPopup = null);
            return;
        }

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);

        PopupWindow popup = new PopupWindow(layout, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        int margin = dp(12);
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, margin, margin);
        mMenuPopup = popup;
        popup.setOnDismissListener(() -> mMenuPopup = null);

        LinearLayout camRow = new LinearLayout(activity);
        camRow.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(camRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mSettingsBtn = createItem("Settings");
        layout.addView(mSettingsBtn);
        mSettingsBtn.setVisibility(quadEnabled ? View.GONE : View.VISIBLE);

        final TextView quadBtn = createItem("K");
        quadBtn.setGravity(Gravity.CENTER);
        quadBtn.setPadding(dp(12), dp(8), dp(12), dp(8));

        mWebUiBtn = createItem("WebUI");
        layout.addView(mWebUiBtn);
        mWebUiBtn.setVisibility(quadEnabled ? View.GONE : View.VISIBLE);

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

            if (i == mActive && !quadEnabled) {
                highlightItem(camButtons[i]);
                applyQualityColor(camButtons[i], i, quadEnabled, mActive, mHosts);
            } else {
                applyQualityColor(camButtons[i], i, quadEnabled, mActive, mHosts);
            }

            camButtons[i].setOnClickListener(v -> {
                if (listener != null) listener.onSwitchCamera(slot);
                PopupWindow p = mMenuPopup;
                if (p != null) p.dismiss();
            });
        }

        camRow.addView(quadBtn, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (quadEnabled) highlightItem(quadBtn);
        mCachedQuadBtn = quadBtn;
        quadBtn.setOnClickListener(v -> {
            PopupWindow p = mMenuPopup;
            if (p != null) p.dismiss();
            if (listener != null) listener.onToggleQuad();
        });

        String code = "Exit [v" + version + ", " + gitHash + "]";
        SpannableString s = new SpannableString(code);
        s.setSpan(new SuperscriptSpan(), 5, s.length(), 0);
        s.setSpan(new RelativeSizeSpan(0.5f), 5, s.length(), 0);

        View divider = new View(activity);
        divider.setBackgroundColor(Color.DKGRAY);
        layout.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        TextView exit = createItem("Exit");
        layout.addView(exit);
        exit.setText(s);
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
    }

    private void updateCachedMenu(boolean quadEnabled, int mActive, String[] mHosts) {
        if (mSettingsBtn != null) {
            mSettingsBtn.setVisibility(quadEnabled ? View.GONE : View.VISIBLE);
        }
        if (mWebUiBtn != null) {
            mWebUiBtn.setVisibility(quadEnabled ? View.GONE : View.VISIBLE);
        }
        if (mCachedQuadBtn != null) {
            if (quadEnabled) highlightItem(mCachedQuadBtn);
            else resetItem(mCachedQuadBtn);
        }
        if (mCachedCamButtons != null && camButtons != null) {
            for (int i = 0; i < CAM_COUNT; i++) {
                if (mCachedCamButtons[i] == null || camButtons[i] == null) continue;
                if (i == mActive && !quadEnabled) {
                    highlightItem(camButtons[i]);
                    applyQualityColor(camButtons[i], i, quadEnabled, mActive, mHosts);
                } else {
                    resetItem(camButtons[i]);
                    applyQualityColor(camButtons[i], i, quadEnabled, mActive, mHosts);
                }
            }
        }
    }

    private TextView createItem(String title) {
        TextView text = new TextView(activity);
        text.setText(title);
        text.setPadding(dp(8), dp(6), dp(8), dp(6));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        focusChange(text);
        return text;
    }

    private void highlightItem(TextView item) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setStroke(2, Color.BLUE);
        item.setBackground(bg);
    }

    private void resetItem(TextView item) {
        item.setTextColor(Color.WHITE);
        focusChange(item);
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

    void showUrlEditor(int mActive, String[] mHosts) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Camera " + (mActive + 1) + " URL");

        final EditText input = new EditText(activity);
        input.setText(mHosts[mActive]);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSelectAllOnFocus(true);
        input.setSelection(input.getText().length());
        builder.setView(input);

        final int slot = mActive;
        builder.setPositiveButton("Save", (dialog, which) -> {
            String url = input.getText().toString().trim();
            mHosts[slot] = Decoder.sanitizeUrl(url);
            if (listener != null) listener.onSaveSettings(); // triggers save + reconnect
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        input.requestFocus();
    }

    private void focusChange(View item) {
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.BLACK);
        border.setStroke(1, Color.GRAY);
        item.setBackground(border);
        item.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) border.setStroke(1, Color.BLUE);
            else border.setStroke(1, Color.GRAY);
            v.setBackground(border);
        });
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
                if (userInfo != null) {
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

        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setView(view);
        b.setNegativeButton("Close", (dialog, which) -> dialog.cancel());
        Dialog dialog = b.create();
        dialog.setOnDismissListener(d -> {
            mBrowserDialog = null;
            view.destroy();
        });
        mBrowserDialog = dialog;
        dialog.show();
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
