/*
 *
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * Decoder.java — main activity for H.264/H.265 hardware video decoding
 *
 */

package com.openipc.decoder;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
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
import android.widget.Toast;
import android.graphics.Bitmap;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Decoder extends Activity {
    private static final String TAG = "OpenIPCDecoder";
    private final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(32);
    private final BlockingQueue<Frame> pcmQueue = new ArrayBlockingQueue<>(32);
    private final BlockingQueue<byte[]> aacQueue = new ArrayBlockingQueue<>(32);
    // Object pools for memory optimization
    private final FramePool framePool = new FramePool(50);
    private final NalAssembler nalAssembler = new NalAssembler(1024 * 1024, () -> {
        closeDecoder();
        nalQueue.clear();
    });

    private volatile MediaCodec mDecoder;
    // Surface captured on the UI thread via TextureView.SurfaceTextureListener; volatile so the
    // network thread can safely read it in createDecoder() without holding any UI lock.
    // Always release the previous Surface before replacing — Surface wraps a native handle.
    private volatile Surface mVideoSurface;
    // guards all MediaCodec lifecycle operations (create / use / release) so that
    // closeDecoder() called from the network thread never races with decodeFrame()
    // called from the video thread
    private final Object decoderLock = new Object();
    private TextureView mSurface;

    // cached display density — avoids repeated getDisplayMetrics() calls during menu build
    private float mDensity;

    // Pinch-to-zoom and pan state
    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mGestureDetector;
    private float mZoomScale = 1.0f;
    private float mPanX = 0f;
    private float mPanY = 0f;
    private static final float ZOOM_MIN = 1.0f;
    private static final float ZOOM_MAX = 5.0f;
    private volatile AudioTrack audioTrack;

    private volatile boolean codecH265;
    private boolean listener;       // only accessed on the UI thread — no volatile needed
    private volatile boolean activeStream;
    private volatile int lastWidth;
    private volatile int lastHeight;
    private volatile long lastFrame;

    // incremented in onPause so that orphaned threads from the previous
    // lifecycle can detect they are stale and exit their loops
    private volatile int listenerGen;

    // set on audio init failure; cleared on session close to allow next retry
    private volatile boolean audioFailed;

    // set on codec init failure; prevents per-frame retry when codec is unsupported
    private volatile boolean decoderFailed;

    // held so onPause() can close them to unblock blocking read()/receive() on the network thread
    private volatile Socket mTcpSocket;

    // pre-allocated to avoid per-frame GC pressure in the video decode loop
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    // read from the network thread, written from the UI thread
    private static final int CAM_COUNT = 4;
    private static final String DEFAULT_URL = "rtsp://root:12345@192.168.1.10:554/stream=0";
    private final String[] mHosts = new String[CAM_COUNT];
    private int mActive; // only accessed on the UI thread — no volatile needed
    private volatile String mHost;
    private String mVersion = "1.23";
    private String mUserAgent = "User-Agent: OpenIPC-Decoder/1.0\r\n";

    // tracks last warned unknown RTP payload type to suppress log spam on the network thread
    private int lastUnknownPayload = -1; // only accessed on the network thread — no volatile needed

    // RTP fragmentation unit NAL types
    private static final int RTP_FU_H264  = 28;  // H.264 FU-A
    private static final int RTP_FU_H265  = 49;  // H.265 FU

    // RTP dynamic payload types as negotiated in the OpenIPC camera SDP
    private static final int RTP_PT_H265  = 97;  // H.265/HEVC video
    private static final int RTP_PT_H264  = 96;  // H.264/AVC video
    private static final int RTP_PT_PCMU_DEFAULT = 100; // fallback audio PT

    // Parameter-set NAL types used to set BUFFER_FLAG_CODEC_CONFIG on the decoder input
    private static final int H265_NAL_VPS = 32;  // Video Parameter Set
    private static final int H265_NAL_SPS = 33;  // Sequence Parameter Set
    private static final int H265_NAL_PPS = 34;  // Picture Parameter Set
    private static final int H264_NAL_SPS = 7;
    private static final int H264_NAL_PPS = 8;

    // inactivity threshold: reconnect if no RTP frame arrives within this period
    private static final long WATCHDOG_MS  = 3000;

    // audio clock rate parsed from SDP rtpmap; falls back to 8000 Hz if SDP is absent
    private volatile int audioSampleRate = 8000;

    // audio payload type parsed from SDP m=audio; defaults to the OpenIPC convention
    private volatile int audioPt = RTP_PT_PCMU_DEFAULT;

    // L16 encoding is big-endian per RFC 3551; set false for native-endian encodings
    private volatile boolean audioBigEndian = true;

    // Audio codec type
    private volatile String audioCodec = "PCM"; // "PCM", "AAC", "G711"

    // AAC decoder
    private volatile MediaCodec aacDecoder;
    private final Object aacDecoderLock = new Object();

    // reference kept so we can dismiss the dialog (and destroy the WebView) on rotation
    private Dialog mBrowserDialog; // only accessed on the UI thread — no volatile needed

    // reference kept so onKeyDown can dismiss the menu popup on Back key press
    private PopupWindow mMenuPopup; // only accessed on the UI thread — no volatile needed

    private ExecutorService executor; // only accessed on the UI thread — no volatile needed

    // quad mode: 4 simultaneous video streams in a 2x2 grid — all UI thread only
    private boolean quadEnabled;
    private QuadCell[] quadCells;
    private TextView mSettingsBtn;
    private TextView mWebUiBtn;
    private LinearLayout quadContainer;
    private final TextureView[] quadViews = new TextureView[4];

    // UI status indicators
    private TextView statusText;
    // Camera number buttons in popup menu (needed by updateQuality)
    private TextView[] camButtons;
    // Current quality color for active camera (green/yellow/red, or WHITE if unknown)
    private int mActiveQualityColor = Color.WHITE;

    // Screenshot state
    private boolean isTakingScreenshot = false;

    // Quality update tracking
    private long lastQualityUpdateTime = 0;
    private long lastRtpTimestamp = -1;
    private long lastRtpArrivalNs = -1;
    private long jitterAccumulator = 0;
    private int jitterSampleCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.decoder);
        
        // Keep screen on while app is running
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mVersion = getPackageManager()
                        .getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0))
                        .versionName;
            } else {
                //noinspection deprecation
                mVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {}
        mUserAgent = "User-Agent: OpenIPC-Decoder/" + mVersion + "\r\n";
        mDensity = getResources().getDisplayMetrics().density;

        mSurface = findViewById(R.id.video_surface);
        mSurface.setKeepScreenOn(true);

        // Initialize status indicator
        statusText = findViewById(R.id.status_text);
        // capture the rendering Surface on the UI thread via TextureView listener
        mSurface.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                replaceSurface(new Surface(st));
            }
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
                replaceSurface(new Surface(st));
            }
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                replaceSurface(null);
                return true;
            }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
        });

        // Pinch-to-zoom
        mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                mZoomScale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, mZoomScale * d.getScaleFactor()));
                clampPan();
                applyZoomTransform();
                return true;
            }
        });
        // Pan (scroll), double-tap to reset zoom, single tap opens menu, long-press screenshot
        final View menuAnchor = findViewById(R.id.decoder);
        mGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (mZoomScale > ZOOM_MIN) {
                    mPanX -= dx;
                    mPanY -= dy;
                    clampPan();
                    applyZoomTransform();
                    return true;
                }
                return false;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                resetZoom();
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                createMenu(menuAnchor);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                takeScreenshot();
            }
        });
        mSurface.setOnTouchListener((v, event) -> {
            mScaleDetector.onTouchEvent(event);
            mGestureDetector.onTouchEvent(event);
            return true;
        });

        // quad mode: 2x2 grid of TextureViews, initially hidden
        quadContainer = new LinearLayout(this);
        quadContainer.setOrientation(LinearLayout.VERTICAL);
        quadContainer.setVisibility(View.GONE);
        for (int row = 0; row < 2; row++) {
            LinearLayout lr = new LinearLayout(this);
            lr.setOrientation(LinearLayout.HORIZONTAL);
            quadContainer.addView(lr, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            for (int col = 0; col < 2; col++) {
                int idx = row * 2 + col;
                quadViews[idx] = new TextureView(this);
                quadViews[idx].setOnClickListener(cv -> createMenu(menuAnchor));
                lr.addView(quadViews[idx], new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            }
        }
        FrameLayout root = (FrameLayout) menuAnchor;
        root.addView(quadContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        // codec type will be set by SDP parser; no default needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.navigationBars());
                ctrl.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }

        // Update status initially
        updateStatus("disconnected");
        
        // Menu is opened via single-tap in the gesture detector above
        // Global uncaught exception handler is installed in App.onCreate()
    }

    @SuppressLint("AuthLeak")
    private void loadSettings() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);

        // migrate legacy single-camera settings to slot 0
        if (pref.contains("host") && !pref.contains("host_0")) {
            SharedPreferences.Editor edit = pref.edit();
            edit.putString("host_0", pref.getString("host", DEFAULT_URL));
            edit.putBoolean("type_0", pref.getBoolean("type", false));
            edit.remove("host");
            edit.remove("type");
            edit.apply();
        }

        // Always start on camera 1 regardless of saved state
        mActive = 0;
        quadEnabled = false;
        for (int i = 0; i < CAM_COUNT; i++) {
            mHosts[i] = pref.getString("host_" + i, DEFAULT_URL);
        }
        applyActiveCamera();

        Intent intent = getIntent();
        String link = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (link != null) {
            Log.d(TAG, "Link: " + Uri.parse(link).getHost());
            mHosts[mActive] = sanitizeUrl(link);
            mHost = mHosts[mActive];
            intent.removeExtra(Intent.EXTRA_TEXT);
        }
    }

    /** Strip CR/LF to prevent CRLF injection into RTSP header lines. */
    private static String sanitizeUrl(String url) {
        return url.replaceAll("[\r\n]", "");
    }

    /** Copy the active slot values into the volatile fields read by the network thread. */
    private void applyActiveCamera() {
        mHost = mHosts[mActive];
        resetZoom();
        // detect unconfigured slot (default URL)
        if (mHost == null || mHost.isEmpty() || mHost.equals(DEFAULT_URL)) {
            updateStatus("unconfigured");
        } else {
            updateStatus("connecting");
        }
    }

    /** Update connection status indicator. */
    private void updateStatus(String status) {
        runOnUiThread(() -> {
            if (statusText == null) return;

            switch (status) {
                case "connecting":
                    statusText.setText(getString(R.string.status_connecting));
                    statusText.setVisibility(View.VISIBLE);
                    break;
                case "connected":
                    statusText.setText(getString(R.string.status_connected));
                    // Hide after 2 seconds
                    statusText.postDelayed(() -> {
                        if (statusText != null) {
                            statusText.setVisibility(View.GONE);
                        }
                    }, 2000);
                    break;
                case "disconnected":
                    statusText.setText(getString(R.string.status_disconnected));
                    statusText.setVisibility(View.VISIBLE);
                    break;
                case "buffering":
                    statusText.setText(getString(R.string.status_buffering));
                    statusText.setVisibility(View.VISIBLE);
                    break;
                case "unconfigured":
                    statusText.setText(getString(R.string.status_unconfigured));
                    statusText.setVisibility(View.VISIBLE);
                    break;
            }
        });
    }
    
    /** Update quality indicator — colors the active camera button text. */
    private void updateQuality(int latency) {
        runOnUiThread(() -> {
            if (latency < 0) {
                mActiveQualityColor = Color.WHITE;
            } else if (latency < 100) {
                mActiveQualityColor = 0xFF00FF00; // Green
            } else if (latency < 300) {
                mActiveQualityColor = 0xFFFFFF00; // Yellow
            } else {
                mActiveQualityColor = 0xFFFF0000; // Red
            }
            // Apply to the active camera button if the menu is open
            if (camButtons != null && !quadEnabled) {
                camButtons[mActive].setTextColor(mActiveQualityColor);
            }
        });
    }
    
    /** Take screenshot of current video frame */
    private void takeScreenshot() {
        if (isTakingScreenshot) return;

        isTakingScreenshot = true;

        // Get bitmap from TextureView
        Bitmap bitmap = mSurface.getBitmap();
        if (bitmap == null) {
            Toast.makeText(this, "No video frame available", Toast.LENGTH_SHORT).show();
            isTakingScreenshot = false;
            return;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "Screenshot_" + timeStamp + ".png";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+ — use MediaStore via ContentResolver
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OpenIPC");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try {
                    OutputStream fos = getContentResolver().openOutputStream(uri);
                    if (fos != null) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        fos.close();
                    }
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    runOnUiThread(() ->
                        Toast.makeText(this, "Screenshot saved: " + fileName, Toast.LENGTH_LONG).show()
                    );
                } catch (IOException e) {
                    Log.e(TAG, "Error saving screenshot via MediaStore", e);
                    getContentResolver().delete(uri, null, null);
                    runOnUiThread(() ->
                        Toast.makeText(this, "Failed to save screenshot", Toast.LENGTH_SHORT).show()
                    );
                }
            }
        } else {
            // API < 29 — use MediaStore insertImage (via ContentResolver, no runtime permission needed)
            try {
                String saved = MediaStore.Images.Media.insertImage(
                    getContentResolver(), bitmap, fileName, "OpenIPC Decoder screenshot");
                if (saved != null) {
                    runOnUiThread(() ->
                        Toast.makeText(this, "Screenshot saved: " + fileName, Toast.LENGTH_LONG).show()
                    );
                } else {
                    throw new IOException("MediaStore insertImage returned null");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error saving screenshot", e);
                runOnUiThread(() ->
                    Toast.makeText(this, "Failed to save screenshot", Toast.LENGTH_SHORT).show()
                );
            }
        }
        bitmap.recycle();
        isTakingScreenshot = false;
    }

    /** Enter quad mode: stop single stream, show 2x2 grid, start 4 independent cells. */
    private void startQuad() {

        // stop single-stream playback
        if (listener) {
            listenerGen++;
            listener = false;
            activeStream = false;
            closeSockets();
            if (executor != null) { executor.shutdownNow(); executor = null; }
            closeDecoder();
            closeAudio();
            nalQueue.clear();
            pcmQueue.clear();
        }

        mSurface.setVisibility(View.GONE);
        quadContainer.setVisibility(View.VISIBLE);
        removeClearOverlay();

        quadCells = new QuadCell[4];
        for (int i = 0; i < 4; i++) {
            String url = mHosts[i];
            if (url == null || url.isEmpty() || url.equals(DEFAULT_URL)) continue;
            quadCells[i] = new QuadCell(i, url, quadViews[i]);
            quadCells[i].start();
        }

        quadEnabled = true;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("quad_enabled", true).apply();
    }

    /** Exit quad mode: stop all cells, show single stream. */
    private void stopQuad() {
        quadEnabled = false;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("quad_enabled", false).apply();

        if (quadCells != null) {
            for (QuadCell cell : quadCells) {
                if (cell != null) cell.stop();
            }
            quadCells = null;
        }

        quadContainer.setVisibility(View.GONE);
        mSurface.setVisibility(View.VISIBLE);

        // restart single-stream playback
        boolean configured = mHost != null && !mHost.isEmpty() && !mHost.equals(DEFAULT_URL);
        if (!listener && configured) {
            listener = true;
            startListener();
        }
    }

    private void saveSettings() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        edit.putInt("active", mActive);
        for (int i = 0; i < CAM_COUNT; i++) {
            edit.putString("host_" + i, mHosts[i]);
        }
        edit.apply();

        applyActiveCamera();
        if (quadEnabled) return;

        // 1. Invalidate old threads — they check gen == listenerGen at loop top
        listenerGen++;
        listener = false;
        activeStream = false;

        // 2. Unblock I/O (read()/receive()) so network threads exit promptly
        closeSockets();

        // 3. Interrupt executor threads FIRST. The video decode thread may be
        //    blocked inside synchronized(decoderLock) at dequeueInputBuffer(5000).
        //    shutdownNow() sends interrupt() which lets it exit the lock quickly.
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        // 4. NOW close decoder/audio — decoderLock will be quickly available
        //    because the interrupted video thread has exited its synchronized block.
        closeDecoder();
        closeAudio();
        nalQueue.clear();
        pcmQueue.clear();

        // 5. Detect unconfigured slot — don't start listener
        boolean configured = mHost != null && !mHost.isEmpty() && !mHost.equals(DEFAULT_URL);
        if (!configured) {
            clearVideo();
            return;
        }

        listener = true;
        startListener();
    }

    /** Close all active network sockets to unblock I/O threads. */
    private void closeSockets() {
        Socket tcp = mTcpSocket;
        if (tcp != null) {
            try {
                tcp.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing TCP socket", e);
            }
        }
    }

    private void createMenu(View menu) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        // compact width for the main menu; expanded to full screen in Settings mode
        PopupWindow popup = new PopupWindow(layout, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        // add margin so the menu doesn't touch the screen edge — issue #4
        int margin = dp(12);
        popup.showAtLocation(menu, Gravity.TOP | Gravity.START, margin, margin);
        mMenuPopup = popup;
        popup.setOnDismissListener(() -> mMenuPopup = null);

        LinearLayout camRow = new LinearLayout(this);
        camRow.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(camRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mSettingsBtn = createItem("Settings");
        layout.addView(mSettingsBtn);
        mSettingsBtn.setVisibility(quadEnabled ? View.GONE : View.VISIBLE);

        // Quad toggle button "K" — declared first so camera-buttons handler can reference it
        final TextView quadBtn = createItem("K");
        quadBtn.setGravity(Gravity.CENTER);
        quadBtn.setPadding(dp(12), dp(8), dp(12), dp(8));

        mWebUiBtn = createItem("WebUI");
        layout.addView(mWebUiBtn);
        mWebUiBtn.setVisibility(quadEnabled ? View.GONE : View.VISIBLE);
        mWebUiBtn.setOnClickListener(v -> {
            startBrowser();
            popup.dismiss();
        });

        camButtons = new TextView[CAM_COUNT];
        for (int i = 0; i < CAM_COUNT; i++) {
            final int slot = i;
            camButtons[i] = createItem(String.valueOf(i + 1));
            camButtons[i].setGravity(Gravity.CENTER);
            camButtons[i].setPadding(dp(12), dp(8), dp(12), dp(8));
            camRow.addView(camButtons[i], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            if (i == mActive && !quadEnabled) {
                highlightItem(camButtons[i]);
                applyQualityColor(camButtons[i], i);
            } else {
                applyQualityColor(camButtons[i], i);
            }

            camButtons[i].setOnClickListener(v -> {
                if (slot == mActive && !quadEnabled) return;
                if (quadEnabled) {
                    // exit quad mode, switch to selected camera
                    popup.dismiss();
                    stopQuad();
                    quadEnabled = false;
                    mSettingsBtn.setVisibility(View.VISIBLE);
                    mWebUiBtn.setVisibility(View.VISIBLE);
                    resetItem(quadBtn);
                }
                mActive = slot;
                for (int j = 0; j < CAM_COUNT; j++) {
                    if (j == mActive) {
                        highlightItem(camButtons[j]);
                        applyQualityColor(camButtons[j], j);
                    } else {
                        resetItem(camButtons[j]);
                        applyQualityColor(camButtons[j], j);
                    }
                }
                saveSettings();
            });
        }

        // Add K to the start of the camera row (already declared above; add now)
        camRow.addView(quadBtn, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (quadEnabled) highlightItem(quadBtn);
        quadBtn.setOnClickListener(v -> {
            popup.dismiss();
            boolean newState = !quadEnabled;
            if (newState) {
                highlightItem(quadBtn);
                // de-highlight all cameras when quad is activated
                for (int j = 0; j < CAM_COUNT; j++) {
                    resetItem(camButtons[j]);
                    applyQualityColor(camButtons[j], j);
                }
            } else {
                resetItem(quadBtn);
                // re-highlight the active camera when leaving quad
                highlightItem(camButtons[mActive]);
                applyQualityColor(camButtons[mActive], mActive);
            }
            mSettingsBtn.setVisibility(newState ? View.GONE : View.VISIBLE);
            mWebUiBtn.setVisibility(newState ? View.GONE : View.VISIBLE);
            if (newState) startQuad(); else stopQuad();
        });

        String code = "Exit [v" + mVersion + ", " + BuildConfig.GIT_HASH + "]";

        SpannableString s = new SpannableString(code);
        s.setSpan(new SuperscriptSpan(),    5, s.length(), 0);
        s.setSpan(new RelativeSizeSpan(0.5f), 5, s.length(), 0);

        View divider = new View(this);
        divider.setBackgroundColor(Color.DKGRAY);
        layout.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        TextView exit = createItem("Exit");
        layout.addView(exit);
        exit.setText(s);
        exit.setOnClickListener(v -> finishAndRemoveTask());

        mSettingsBtn.setOnClickListener(v -> {
            popup.dismiss();
            showUrlEditor();
        });
    }

    private TextView createItem(String title) {
        TextView text = new TextView(this);
        text.setText(title);
        text.setPadding(dp(8), dp(6), dp(8), dp(6));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        focusChange(text);

        return text;
    }

    /** Apply blue highlight to the active camera button. */
    private void highlightItem(TextView item) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setStroke(2, Color.BLUE);
        item.setBackground(bg);
    }

    /** Reset camera button to default style. */
    private void resetItem(TextView item) {
        item.setTextColor(Color.WHITE);
        focusChange(item);
    }

    /** Set text color of camera button based on configuration and quality. */
    private void applyQualityColor(TextView btn, int slot) {
        if (mHosts[slot] == null || mHosts[slot].isEmpty() || mHosts[slot].equals(DEFAULT_URL)) {
            btn.setTextColor(0xFF666666); // Gray = unconfigured
        } else if (slot == mActive && !quadEnabled) {
            btn.setTextColor(mActiveQualityColor); // Active cam's quality color
        } else {
            btn.setTextColor(Color.WHITE); // Default white
        }
    }

    private EditText createEdit(String title) {
        EditText text = new EditText(this);
        text.setText(title);
        text.setPadding(dp(8), dp(8), dp(8), dp(8));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        // Show only the beginning of the URL (first ~20 chars), single line
        text.setMaxLines(1);
        text.setImeOptions(EditorInfo.IME_ACTION_DONE);
        text.setSelection(0);
        focusChange(text);

        return text;
    }

    /** Show a full-screen dialog to edit the camera URL for the active slot. */
    private void showUrlEditor() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Camera " + (mActive + 1) + " URL");

        final EditText input = new EditText(this);
        input.setText(mHosts[mActive]);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSelectAllOnFocus(true);
        input.setSelection(input.getText().length());
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String url = input.getText().toString().trim();
            mHosts[mActive] = sanitizeUrl(url);
            saveSettings();
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
            if (hasFocus) {
                border.setStroke(1, Color.BLUE);
            } else {
                border.setStroke(1, Color.GRAY);
            }
            v.setBackground(border);
        });
    }

    /** Clear the video view so no stale frame lingers. Adds a black overlay
     *  View instead of touching the TextureView (which would break the hardware
     *  rendering pipeline). The overlay is removed when updateQuality() signals
     *  an active stream. */
    private void clearVideo() {
        if (mSurface == null) return;
        // Remove existing overlay if any
        removeClearOverlay();
        final View overlay = new View(this);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setTag("clear_overlay");
        ((FrameLayout) mSurface.getParent()).addView(overlay);
    }

    private void removeClearOverlay() {
        if (mSurface == null) return;
        FrameLayout parent = (FrameLayout) mSurface.getParent();
        View overlay = parent.findViewWithTag("clear_overlay");
        if (overlay != null) {
            parent.removeView(overlay);
        }
    }

    /** Clear all quad views via black overlays so stale frames are erased before restart.
     *  Uses View overlay instead of lockCanvas() which breaks the MediaCodec rendering pipeline. */
    private void clearQuadViews() {
        for (int i = 0; i < 4; i++) {
            TextureView t = quadViews[i];
            if (t == null) continue;
            // Find or create a black overlay child
            View overlay = t.findViewWithTag("quad_overlay_" + i);
            if (overlay == null) {
                overlay = new View(this);
                overlay.setTag("quad_overlay_" + i);
                overlay.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                overlay.setBackgroundColor(Color.BLACK);
                ((FrameLayout) t.getParent()).addView(overlay);
            }
            overlay.setVisibility(View.VISIBLE);
            overlay.bringToFront();
        }
    }

    /** Remove quad overlays (called from QuadCell when a frame is decoded). */
    private void removeQuadOverlay(int index) {
        if (index < 0 || index >= quadViews.length || quadViews[index] == null) return;
        TextureView t = quadViews[index];
        View overlay = t.findViewWithTag("quad_overlay_" + index);
        if (overlay != null) {
            overlay.setVisibility(View.GONE);
        }
    }

    private int dp(float dp) {
        return (int) (dp * mDensity + 0.5f);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startBrowser() {
        Uri uri = Uri.parse(mHost);
        String link = uri.getHost();
        if (link == null) {
            Log.w(TAG, "Cannot open WebUI: invalid host in URL");
            return;
        }
        // WebUI runs on HTTP port 80, not the RTSP port (554).
        // If the URL has a non-default RTSP port, the WebUI is still on :80.
        // Don't append the RTSP port to the WebUI URL.

        WebView view = new WebView(this);
        view.getSettings().setJavaScriptEnabled(true);

        // Set the auth-capable client BEFORE loadUrl so credentials are
        // available on the very first HTTP 401 challenge.
        final Uri finalUri = uri;
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedHttpAuthRequest(
                    WebView view, HttpAuthHandler handler, String host, String realm) {
                String userInfo = finalUri.getUserInfo();
                if (userInfo != null) {
                    String[] part = userInfo.split(":", 2);
                    if (part.length == 2) {
                        handler.proceed(part[0], part[1]);
                        return;
                    }
                }
                handler.cancel();
            }
        });

        Log.d(TAG, "WebView: " + link);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(true);

        int screenWidth;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            screenWidth = (int) (getWindowManager().getCurrentWindowMetrics()
                    .getBounds().width() * 0.75);
        } else {
            //noinspection deprecation
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(dm);
            screenWidth = (int) (dm.widthPixels * 0.75);
        }
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(screenWidth, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        // release WebView native resources when the dialog is dismissed
        dialog.setOnDismissListener(d -> view.destroy());
        mBrowserDialog = dialog;

        // Use the scheme from the camera URL (http or https), default to http
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equals(scheme) && !"https".equals(scheme))) {
            scheme = "http";
        }
        view.loadUrl(scheme + "://" + link);
    }

    private void updateResolution(int width, int height) {
        if (width < 64 || height < 64) {  // validate incoming params to avoid division by zero
            return;
        }

        Log.d(TAG, "Resolution update: " + width + "x" + height);
        runOnUiThread(() -> {
            // getHeight() must be called on the UI thread — View dimensions are written
            // by the layout system on the UI thread; reading them elsewhere is a data race
            int surfaceH = mSurface.getHeight();
            if (surfaceH == 0) surfaceH = getResources().getDisplayMetrics().heightPixels;
            int surfaceW = (int) ((float) surfaceH / height * width);
            Log.d(TAG, "Set surface: " + surfaceW + "x" + surfaceH);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(surfaceW, surfaceH);
            params.gravity = Gravity.CENTER;
            mSurface.setLayoutParams(params);
        });
    }

    /** Apply current zoom scale and pan offset via TextureView matrix transform */
    private void applyZoomTransform() {
        Matrix matrix = new Matrix();
        float cx = mSurface.getWidth() / 2f;
        float cy = mSurface.getHeight() / 2f;
        matrix.postScale(mZoomScale, mZoomScale, cx, cy);
        matrix.postTranslate(mPanX, mPanY);
        mSurface.setTransform(matrix);
    }

    /** Clamp pan offset so the image edges don't go past the viewport center */
    private void clampPan() {
        float maxX = (mSurface.getWidth() * (mZoomScale - 1)) / 2f;
        float maxY = (mSurface.getHeight() * (mZoomScale - 1)) / 2f;
        mPanX = Math.max(-maxX, Math.min(maxX, mPanX));
        mPanY = Math.max(-maxY, Math.min(maxY, mPanY));
    }

    /** Reset zoom to default 1:1 view */
    private void resetZoom() {
        mZoomScale = ZOOM_MIN;
        mPanX = 0f;
        mPanY = 0f;
        applyZoomTransform();
    }

    /** Release the previous Surface (if any) and store the new one. */
    private void replaceSurface(Surface next) {
        Surface prev = mVideoSurface;
        mVideoSurface = next;
        if (prev != null) {
            try {
                prev.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing Surface", e);
            }
        }
    }

    private Frame buildFrame(Frame frame) {
        return nalAssembler.assemble(frame, codecH265);
    }

    private void playAudio(Frame data) {
        // snapshot to a local: onPause() can null audioTrack from the UI thread at any moment
        AudioTrack track = audioTrack;
        if (track == null) {
            if (!audioFailed) {  // skip retry if init already failed for this session
                createAudio();
            }
        } else {
            // write() may return a positive number less than the requested length;
            // loop until all bytes are consumed or a fatal error is reported.
            // Re-snapshot audioTrack on each iteration: closeAudio() can null it at any moment.
            byte[] buf = data.data();
            int offset = 0;
            int remaining = data.length();
            while (remaining > 0) {
                AudioTrack t = audioTrack; // re-check: UI thread may have called closeAudio()
                if (t == null) break;
                int written = t.write(buf, offset, remaining);
                if (written < 0) {
                    Log.e(TAG, "AudioTrack.write() error: " + written);
                    break;
                }
                offset    += written;
                remaining -= written;
            }
        }
    }

    private void processAudio(Frame frame) {
        int header = 12;
        int length = frame.length() - header;
        if (length <= 0) {  // ignore malformed packets shorter than the RTP header
            return;
        }

        byte[] audioData = new byte[length];
        System.arraycopy(frame.data(), header, audioData, 0, length);

        if ("AAC".equals(audioCodec)) {
            // For AAC, we need to handle ADTS headers if present
            processAacFrame(audioData);
        } else if ("G711".equals(audioCodec)) {
            // G.711 decoding would go here
            // For now, skip G.711
            Log.d(TAG, "G.711 audio not yet implemented");
        } else {
            // PCM processing
            if (audioBigEndian) {
                for (int i = 0; i + 1 < length; i += 2) {
                    byte tmp = audioData[i];
                    audioData[i] = audioData[i + 1];
                    audioData[i + 1] = tmp;
                }
            }

            if (!pcmQueue.offer(new Frame(audioData, length))) {
                Log.w(TAG, "Audio queue full, frame dropped");
            }
        }
    }

    private void processAacFrame(byte[] aacData) {
        if (!aacQueue.offer(aacData)) {
            Log.w(TAG, "AAC audio queue full, frame dropped");
        }
    }

    private void createAudio() {
        if ("AAC".equals(audioCodec)) {
            createAacDecoder();
        } else {
            createPcmAudio();
        }
    }

    private void createPcmAudio() {
        int sample = audioSampleRate;
        int format = AudioFormat.CHANNEL_OUT_MONO;

        Log.d(TAG, "Create PCM audio (" + sample + "hz)");
        int size = AudioTrack.getMinBufferSize(sample, format, AudioFormat.ENCODING_PCM_16BIT);
        if (size <= 0) {
            Log.e(TAG, "Invalid PCM audio parameters: sample=" + sample + " bufSize=" + size);
            audioFailed = true;
            return;
        }

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sample)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize, releasing");
            track.release();
            audioFailed = true;
            return;
        }

        audioTrack = track;
        try {
            track.play();
        } catch (Exception e) {
            audioTrack = null;
            track.release();
            Log.e(TAG, "AudioTrack.play() failed", e);
            audioFailed = true;
        }
    }

    private void createAacDecoder() {
        Log.d(TAG, "Creating AAC decoder");
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                audioSampleRate, 1); // mono
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_IS_ADTS, 1);

        MediaCodec codec;
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            try {
                codec.configure(fmt, null, null, 0);
                codec.start();
            } catch (Exception e) {
                codec.release();
                throw e;
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot create AAC decoder, falling back to PCM", e);
            createPcmAudio();
            return;
        }

        synchronized (aacDecoderLock) {
            MediaCodec old = aacDecoder;
            if (old != null) {
                try { old.stop(); } catch (Exception ignored) {}
                old.release();
            }
            aacDecoder = codec;
        }

        // Start AAC decode loop on the executor
        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-aac-decode");
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (listener && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] aacData = aacQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (aacData == null) continue;

                    // Check if the codec was released by closeAudio() while we were waiting
                    synchronized (aacDecoderLock) {
                        if (aacDecoder != codec) break;
                    }

                    // Check if the frame already has ADTS header (starts with 0xFFF)
                    boolean hasAdts = (aacData.length >= 2
                            && (aacData[0] & 0xFF) == 0xFF
                            && (aacData[1] & 0xF0) == 0xF0);

                    int inputId = codec.dequeueInputBuffer(10_000);
                    if (inputId < 0) continue;

                    ByteBuffer inBuf = codec.getInputBuffer(inputId);
                    if (inBuf == null) continue;
                    inBuf.clear();
                    if (hasAdts) {
                        // Strip ADTS header (7 bytes) before feeding to MediaCodec
                        // MediaCodec raw AAC input expects ADTS-free data when KEY_IS_ADTS=0,
                        // or full ADTS frames when KEY_IS_ADTS=1
                        inBuf.put(aacData, 0, aacData.length);
                    } else {
                        // Raw AAC without ADTS — prepend one for decoders that need it
                        // (depends on device; KEY_IS_ADTS=1 handles this)
                        inBuf.put(aacData);
                    }
                    codec.queueInputBuffer(inputId, 0, aacData.length,
                            System.nanoTime() / 1000, 0);

                    // Drain output
                    int outId;
                    while ((outId = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                        if (info.size > 0) {
                            ByteBuffer outBuf = codec.getOutputBuffer(outId);
                            if (outBuf != null) {
                                byte[] pcm = new byte[info.size];
                                outBuf.position(info.offset);
                                outBuf.get(pcm, 0, info.size);
                                if (!pcmQueue.offer(new Frame(pcm, info.size))) {
                                    Log.w(TAG, "AAC decoded PCM queue full, frame dropped");
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outId, false);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "AAC decode error", e);
                    // Close and release the codec to avoid leaking resources
                    synchronized (aacDecoderLock) {
                        if (aacDecoder == codec) {
                            aacDecoder = null;
                        }
                    }
                    try { codec.stop(); } catch (Exception ignored) {}
                    try { codec.release(); } catch (Exception ignored) {}
                    break;
                }
            }
        });
    }

    private void closeAudio() {
        AudioTrack track = audioTrack;
        if (track != null) {
            // null the field BEFORE stop/release: any concurrent playAudio() snapshot
            // will then see null and skip, rather than writing to a released AudioTrack
            audioTrack = null;
            Log.i(TAG, "Close audio decoder");
            try {
                track.stop();
                track.release();
            } catch (Exception e) {
                Log.e(TAG, "Audio close exception", e);
            }
        }
        synchronized (aacDecoderLock) {
            MediaCodec aac = aacDecoder;
            if (aac != null) {
                aacDecoder = null;
                try { aac.stop(); } catch (Exception ignored) {}
                try { aac.release(); } catch (Exception ignored) {}
            }
        }
        aacQueue.clear();
        audioFailed = false; // allow re-init on the next session
    }

    private void decodeFrame(Frame buffer) {
        if (buffer.length() < 5) { // need at least 4-byte start code + 1 NAL type byte
            Log.w(TAG, "NAL frame too short: " + buffer.length());
            return;
        }

        lastFrame = SystemClock.elapsedRealtime();

        int flag = 0;
        int fragment = NalAssembler.fragment(buffer.data()[4], codecH265);
        // mark parameter-set NALs so the decoder can configure itself before the first frame
        boolean isConfigNal = codecH265
                ? (fragment == H265_NAL_VPS || fragment == H265_NAL_SPS || fragment == H265_NAL_PPS)
                : (fragment == H264_NAL_SPS || fragment == H264_NAL_PPS);
        if (isConfigNal) {
            flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
        }

        // Hold decoderLock for the entire codec operation: closeDecoder() (called from the
        // network thread on codec-switch) must not call stop()/release() while we are still
        // feeding buffers or dequeuing output — MediaCodec is not thread-safe for that.
        boolean needCreate = false;
        synchronized (decoderLock) {
            MediaCodec codec = mDecoder;
            if (codec == null) {
                needCreate = !decoderFailed; // createDecoder() acquires decoderLock itself
            } else {
                try {
                    // 5 ms timeout: gives the codec a chance to free a slot under load
                    // instead of immediately returning -1 and silently dropping the frame
                    int inputBufferId = codec.dequeueInputBuffer(5_000);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                        if (inputBuffer != null) {
                            inputBuffer.clear(); // reset position/limit — dequeue does not guarantee this
                            inputBuffer.put(buffer.data(), 0, buffer.length());
                            codec.queueInputBuffer(inputBufferId, 0,
                                    buffer.length(), System.nanoTime() / 1000, flag);
                        }
                    }

                    // drain all available output buffers — the codec may have
                    // multiple frames ready after a single input submission
                    MediaCodec.BufferInfo info = mBufferInfo;
                    int outputBufferId;
                    while ((outputBufferId = codec.dequeueOutputBuffer(info, 0)) >= 0
                            || outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat format = codec.getOutputFormat();
                            int mWidth  = format.getInteger(MediaFormat.KEY_WIDTH);
                            int mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                            if (lastWidth != mWidth || lastHeight != mHeight) {
                                lastWidth  = mWidth;
                                lastHeight = mHeight;
                                updateResolution(lastWidth, lastHeight);
                            }
                        } else {
                            codec.releaseOutputBuffer(outputBufferId, true);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Codec exception: " + e.getMessage());
                    // mark failed so the video thread won't retry until a new stream arrives
                    mDecoder = null;
                    decoderFailed = true;
                    try { codec.stop();    } catch (Exception ignored) {}
                    try { codec.release(); } catch (Exception ignored) {}
                }
            }
        }
        // createDecoder() acquires decoderLock internally — must be called outside our block
        if (needCreate) createDecoder();
    }

    private void createDecoder() {
        // fast pre-check before allocating anything
        synchronized (decoderLock) {
            if (mDecoder != null) return; // already running — avoid double-init and native leak
        }

        // use the Surface captured on the UI thread — getSurface() is not thread-safe to call
        // from the network thread directly (the TextureView listener populates mVideoSurface)
        Surface mVideo = mVideoSurface;
        if (mVideo == null || !mVideo.isValid()) {
            return;
        }

        String type = codecH265 ? "video/hevc" : "video/avc";

        MediaFormat format = MediaFormat.createVideoFormat(type, 1280, 720);
        // pre-declare the max input size to match our NAL buffer; prevents
        // BufferOverflowException when a large intra-frame arrives
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);

        MediaCodec local;
        try {
            Log.i(TAG, "Start video decoder");
            local = MediaCodec.createDecoderByType(type);
            // configure() and start() are split from createDecoderByType() so that
            // if either throws, we can release() the already-created codec object —
            // otherwise the native handle leaks until the next GC cycle
            try {
                local.configure(format, mVideo, null, 0);
                local.start();
                runOnUiThread(this::removeClearOverlay);
            } catch (Exception e) {
                local.release(); // prevent native MediaCodec handle leak
                throw e;
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot setup decoder: " + e.getMessage());
            decoderFailed = true; // codec likely unsupported; stop retrying until next session
            return;
        }

        synchronized (decoderLock) {
            if (mDecoder != null) {
                // another thread created the decoder while we were outside the lock; discard ours
                local.release();
                return;
            }
            mDecoder = local;
        }
        // reset the watchdog baseline: decodeFrame() only updates lastFrame after the codec
        // is ready, so the first call (codec==null path) returns early without touching it.
        // Without this, a keyframe interval > 3s would trigger a spurious stream disconnect.
        lastFrame = SystemClock.elapsedRealtime();
        updateResolution(lastWidth, lastHeight);
    }

    private void closeDecoder() {
        synchronized (decoderLock) {
            MediaCodec codec = mDecoder;
            if (codec == null) { decoderFailed = false; return; }
            mDecoder = null;
            Log.i(TAG, "Close video decoder");
            // release inside the lock: prevents createDecoder() from allocating a second
            // codec instance while the old one is still held by the hardware pipeline
            try {
                codec.stop();
                codec.release();
            } catch (Exception e) {
                Log.e(TAG, "Decoder close exception", e);
            }
            decoderFailed = false;
        }
    }

    /**
     * Reads one CRLF-terminated line from a raw InputStream without any internal buffering,
     * so the stream position is left exactly after the '\n' — no bytes are consumed in advance.
     * A BufferedReader/BufferedInputStream must NOT be used here: after PLAY the camera
     * immediately starts sending RTP data, and any pre-read bytes would be silently lost.
     */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128); // pre-sized for a typical header line
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') {
                if (sb.length() >= 8192) throw new IOException("RTSP header line too long");
                sb.append((char) c);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Reads a complete RTSP response (status line + headers until blank line).
     * Throws IOException if the status code is not 2xx.
     * Returns the trimmed value of {@code targetHeader}, or null if the header is absent.
     */
    private static String readRtspResponse(InputStream in, String targetHeader) throws IOException {
        String status = readLine(in);
        if (status == null) throw new IOException("Server closed connection during handshake");
        Log.i(TAG, status);

        // validate that the response is a 2xx success
        String[] parts = status.split(" ", 3);
        if (parts.length < 2) throw new IOException("Malformed RTSP response: " + status);
        try {
            int code = Integer.parseInt(parts[1]);
            if (code < 200 || code >= 300) throw new IOException("RTSP error: " + status.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Malformed RTSP status code: " + status);
        }

        // read remaining headers until the blank separator line
        String found = null;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            Log.i(TAG, line);
            // RFC 2326: header names are case-insensitive
            if (targetHeader != null && line.toLowerCase(Locale.ROOT)
                    .startsWith(targetHeader.toLowerCase(Locale.ROOT))) {
                // extract value, stripping optional parameters (e.g. "Session: abc;timeout=60")
                found = line.substring(targetHeader.length()).split(";")[0].trim();
            }
        }
        return found;
    }

    /**
     * Parses the full SDP body in a single pass (RFC 4566), extracting:
     * <ul>
     *   <li>audio clock rate → stored in {@link #audioSampleRate}</li>
     *   <li>video codec (H.264 vs H.265) → stored in {@link #codecH265}</li>
     *   <li>per-track Control URLs (RFC 2326 §C.1.1) → returned as [video, audio]</li>
     * </ul>
     * Absolute {@code a=control:} values are used as-is; relative values are resolved
     * against {@code baseUrl}. Falls back to "{@code baseUrl}/trackID=N" if absent.
     */
    private String[] parseSdp(String sdp, String baseUrl) {
        String base = baseUrl; // may be overridden by session-level a=control:
        String[] controls = { null, null };
        int track = -1; // -1 = session section, 0 = video, 1 = audio

        for (String line : sdp.split("[\r\n]+")) {
            if (line.startsWith("m=video")) {
                track = 0;
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    try {
                        int pt = Integer.parseInt(parts[3]);
                        codecH265 = (pt == RTP_PT_H265);
                        Log.d(TAG, "SDP video codec: " + (codecH265 ? "H.265" : "H.264")
                                + " (PT=" + pt + ")");
                    } catch (NumberFormatException ignored) {}
                }
            } else if (line.startsWith("m=audio")) {
                track = 1;
                // parse audio payload type from "m=audio <port> RTP/AVP <pt>"
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    try {
                        audioPt = Integer.parseInt(parts[3]);
                        Log.d(TAG, "SDP audio PT: " + audioPt);
                    } catch (NumberFormatException ignored) {}
                }
            } else if (line.startsWith("a=control:")) {
                String ctrl = line.substring("a=control:".length()).trim();
                if (track == -1) {
                    // session-level control: override base URL (RFC 2326 §C.1.1)
                    base = ctrl.startsWith("rtsp://") ? ctrl : baseUrl + "/" + ctrl;
                } else {
                    controls[track] = ctrl.startsWith("rtsp://") ? ctrl : base + "/" + ctrl;
                }
            } else if (audioPt >= 0 && line.startsWith("a=rtpmap:" + audioPt + " ")) {
                String encoding = line.substring(("a=rtpmap:" + audioPt + " ").length());
                String codecName = encoding.split("/")[0].toUpperCase(Locale.ROOT);

                if (codecName.contains("MP4A") || codecName.contains("AAC")) {
                    audioCodec = "AAC";
                    audioBigEndian = false;
                    Log.d(TAG, "Audio codec: AAC detected");
                } else if (codecName.contains("PCMA") || codecName.contains("PCMU")) {
                    audioCodec = "G711";
                    audioBigEndian = false;
                    Log.d(TAG, "Audio codec: G.711 detected");
                } else if (codecName.startsWith("L16")) {
                    audioCodec = "PCM";
                    audioBigEndian = true;
                } else {
                    audioCodec = "PCM";
                    audioBigEndian = false;
                }

                int slash = encoding.indexOf('/');
                if (slash >= 0) {
                    int end = encoding.indexOf('/', slash + 1);
                    String rateStr = (end >= 0
                            ? encoding.substring(slash + 1, end)
                            : encoding.substring(slash + 1)).trim();
                    try {
                        int rate = Integer.parseInt(rateStr);
                        if (rate > 0 && rate <= 192000) {
                            audioSampleRate = rate;
                            Log.d(TAG, "Audio: " + codecName
                                    + " " + rate + " Hz, BE=" + audioBigEndian);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        // apply defaults for tracks without explicit control
        if (controls[0] == null) controls[0] = base + "/trackID=0";
        if (controls[1] == null) controls[1] = base + "/trackID=1";
        return controls;
    }

    private void rtspConnect() throws Exception {
        nalAssembler.reset(); // discard any partial NAL fragment from the previous session
        lastUnknownPayload = -1; // reset so warnings appear for each new session
        String currentHost = mHost;
        if (currentHost == null || currentHost.isEmpty()) {
            throw new IOException("Camera slot not configured");
        }
        Uri uri = Uri.parse(currentHost);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("Invalid RTSP URL: host is missing or empty");
        }
        Socket s = null;
        try {
            s = new Socket();
            int port = uri.getPort();
            s.connect(new InetSocketAddress(host, port < 0 ? 554 : port), 1000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(1000);
            // use the raw InputStream — a BufferedReader would pre-read RTP stream bytes
            // into its internal buffer, making them unavailable to tcpStream()
            InputStream input = s.getInputStream();
            OutputStream w = s.getOutputStream();

            Log.d(TAG, "Start rtsp connection");

            String user = uri.getUserInfo();
            String auth = "";
            if (user != null) {
                auth = "Authorization: Basic " +
                        Base64.encodeToString(user.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\r\n";
            }
            // Strip userinfo from the request-line URL: credentials belong only in the
            // Authorization header — including them in the URL leaks them to server access logs.
            String path = uri.getEncodedPath();
            String query = uri.getEncodedQuery();
            // include query string (?param=value) — some cameras embed channel or stream IDs there
            String rtspUrl = uri.getScheme() + "://" + host
                    + (port >= 0 ? ":" + port : "")
                    + (path  != null ? path         : "")
                    + (query != null ? "?" + query  : "");

            int seq = 1;
            String desc = "DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + mUserAgent + "Accept: application/sdp\r\n\r\n";
            w.write(desc.getBytes(StandardCharsets.UTF_8));
            w.flush();

            // read DESCRIBE response; capture Content-Length to skip the SDP body
            String contentLenStr = readRtspResponse(input, "Content-Length:");
            int sdpBodyLen = 0;
            if (contentLenStr != null) {
                try { sdpBodyLen = Integer.parseInt(contentLenStr); }
                catch (NumberFormatException ignored) {}
            }
            // read SDP body; parse audio clock rate, then discard the rest
            StringBuilder sdp = new StringBuilder();
            byte[] skipBuf = new byte[512];
            while (sdpBodyLen > 0) {
                int n = input.read(skipBuf, 0, Math.min(sdpBodyLen, skipBuf.length));
                if (n <= 0) break; // -1 = EOF; 0 = legal but unusual, avoids infinite loop
                if (sdp.length() < 4096) // cap to avoid OOM on pathological responses
                    sdp.append(new String(skipBuf, 0, n, StandardCharsets.UTF_8));
                sdpBodyLen -= n;
            }
            // single-pass SDP parse: audio rate, video codec, and per-track Control URLs
            String[] trackUrls = parseSdp(sdp.toString(), rtspUrl);

            seq++;
            String video = "SETUP " + trackUrls[0] + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + mUserAgent +
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n";
            w.write(video.getBytes(StandardCharsets.UTF_8));
            w.flush();

            // read SETUP response; Session header is required to continue
            String session = readRtspResponse(input, "Session:");
            if (session == null) {
                throw new IOException("RTSP server did not return a Session header");
            }
            // strip CR/LF to prevent the session token from injecting extra RTSP headers
            session = session.replaceAll("[\r\n]", "");

            seq++;
            String audio = "SETUP " + trackUrls[1] + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + mUserAgent +
                    "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n" +
                    "Session: " + session + "\r\n\r\n";
            w.write(audio.getBytes(StandardCharsets.UTF_8));
            w.flush();

            readRtspResponse(input, null);

            seq++;
            String play = "PLAY " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + mUserAgent + "Session: " + session + "\r\n\r\n";
            w.write(play.getBytes(StandardCharsets.UTF_8));
            w.flush();

            readRtspResponse(input, null);
            updateStatus("buffering");

            // disable read timeout before streaming: keyframe intervals often exceed 1 second
            s.setSoTimeout(0);
            // reset watchdog baseline so the 3-second timeout counts from now,
            // not from the epoch (lastFrame == 0 would trigger the watchdog immediately)
            lastFrame = SystemClock.elapsedRealtime();
            activeStream = true;
            // Update status
            updateStatus("connected");
            // pre-warm the decoder now, while first packets are still in transit;
            // without this, createDecoder() runs on the first decoded frame (~200–500 ms later)
            createDecoder();
            mTcpSocket = s;
            try {
                tcpStream(input);
            } finally {
                mTcpSocket = null;
            }
            try {
                String teardown = "TEARDOWN " + rtspUrl + " RTSP/1.0\r\n" +
                        "CSeq: " + (seq + 1) + "\r\n" + auth + mUserAgent +
                        "Session: " + session + "\r\n\r\n";
                w.write(teardown.getBytes(StandardCharsets.UTF_8));
                w.flush();
                Log.d(TAG, "RTSP TEARDOWN sent");
            } catch (Exception e) {
                Log.e(TAG, "Error sending TEARDOWN", e);
            }
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception e) {
                    Log.e(TAG, "Error closing TCP socket", e);
                }
            }
        }
    }

    private void tcpStream(InputStream rawInput) throws IOException {
        // wrap in BufferedInputStream to batch OS-level reads
        BufferedInputStream input = new BufferedInputStream(rawInput, 65536);
        byte[] pktBuf = new byte[65535];
        while (activeStream) {
            int b = input.read();
            if (b == -1) {
                activeStream = false; // server closed connection cleanly — signal reconnect
                break;
            }
            if (b != 0x24) continue; // not an RTSP interleaved marker — skip

            int channel = input.read();
            int hi = input.read();
            int lo = input.read();
            if (channel == -1 || hi == -1 || lo == -1) {
                activeStream = false; // unexpected EOF inside interleaved header
                break;
            }
            int len = (hi << 8) | lo;
            // A zero-length or oversized packet is malformed; skip it to avoid
            // an empty read loop or an out-of-bounds access into pktBuf.
            if (len <= 0 || len > pktBuf.length) {
                Log.w(TAG, "Invalid RTSP interleaved packet length: " + len);
                continue;
            }

            int read = 0;
            while (read < len) {
                int n = input.read(pktBuf, read, len - read);
                if (n == -1) throw new IOException("stream truncated mid-packet");
                read += n;
            }

            if (channel == 0 || channel == 2) {
                Frame frame = obtainFrame(len);
                System.arraycopy(pktBuf, 0, frame.data(), 0, len);
                frame.setLength(len);
                processPacket(frame);
            }
        }
    }


    private void processPacket(Frame frame) {
        try {
            if (frame.length() < 12) {
                Log.w(TAG, "RTP packet too short: " + frame.length());
                return;
            }
            byte[] data = frame.data();

            int cc = data[0] & 0x0F;
            boolean hasExt = (data[0] & 0x10) != 0;
            if (cc != 0 || hasExt) {
                Log.w(TAG, "Unsupported RTP header: CC=" + cc + " X=" + (hasExt ? 1 : 0) + ", dropping");
                return;
            }

            int payload = (data[1] & 0x7F);
            if (payload == audioPt) {
                processAudio(frame);
                return;
            } else if (payload == RTP_PT_H265 || payload == RTP_PT_H264) {
                // Calculate jitter from RTP timestamp (bytes 4-7, big-endian, 90 kHz clock)
                long now = SystemClock.elapsedRealtime();
                if (now - lastQualityUpdateTime > 1000) {
                    lastQualityUpdateTime = now;
                    int avgJitter = (jitterSampleCount > 0)
                            ? (int)(jitterAccumulator / jitterSampleCount)
                            : -1;
                    updateQuality(avgJitter);
                    jitterAccumulator = 0;
                    jitterSampleCount = 0;
                }
                if (lastRtpTimestamp >= 0 && lastRtpArrivalNs >= 0) {
                    int rtpTs = ((data[4] & 0xFF) << 24)
                              | ((data[5] & 0xFF) << 16)
                              | ((data[6] & 0xFF) << 8)
                              | (data[7] & 0xFF);
                    int rtpDeltaMs = (int)((rtpTs - lastRtpTimestamp) / 90L);
                    long arrivalDeltaMs = (System.nanoTime() - lastRtpArrivalNs) / 1000000;
                    long jitter = Math.abs(arrivalDeltaMs - rtpDeltaMs);
                    if (jitter < 5000) {
                        jitterAccumulator += jitter;
                        jitterSampleCount++;
                    }
                }
                lastRtpTimestamp = ((data[4] & 0xFF) << 24)
                                 | ((data[5] & 0xFF) << 16)
                                 | ((data[6] & 0xFF) << 8)
                                 | (data[7] & 0xFF);
                lastRtpArrivalNs = System.nanoTime();

                codecH265 = payload == RTP_PT_H265;
                Frame output = buildFrame(frame);
                if (output != null) {
                    if (!nalQueue.offer(output)) {
                        Log.w(TAG, "Video queue full, frame dropped");
                        recycleFrame(output);
                    }
                }
                return;
            }

            if (payload != lastUnknownPayload) {
                lastUnknownPayload = payload;
                Log.w(TAG, "Unknown rtp type: " + payload);
            }
        } finally {
            // Recycle the frame after processing
            recycleFrame(frame);
        }
    }

    private void startListener() {
        Log.i(TAG, "Start network listener");

        // Each thread captures its generation; exits as soon as onPause
        // increments listenerGen, preventing duplicate threads on resume.
        final int gen = listenerGen;

        executor = Executors.newFixedThreadPool(5); // network, watchdog, video decode, audio play, AAC decode

        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-network");
            int retryDelay = 1000; // start at 1 s
            int consecutiveFailures = 0;
            final int MAX_RETRY_DELAY = 30000; // 30 seconds max
            final int MAX_CONSECUTIVE_FAILURES = 10;

            while (gen == listenerGen) {
                try {
                    if (!activeStream) {
                        rtspConnect();
                        // Successful connection
                        retryDelay = 1000;
                        consecutiveFailures = 0;
                        updateStatus("connected");

                        // Wait a bit before checking connection status again
                        // This prevents immediate reconnection if stream drops quickly
                        SystemClock.sleep(2000);
                    } else {
                        // Stream is active, just sleep and monitor
                        SystemClock.sleep(1000);
                    }
                } catch (Exception e) {
                    consecutiveFailures++;
                    activeStream = false;
                    updateStatus("disconnected");

                    // Log with more context
                    if (consecutiveFailures <= 3 || (consecutiveFailures % 5 == 0)) {
                        Log.w(TAG, "RTSP connection failed (" + consecutiveFailures + "): " + e.getMessage());
                    }

                    // Exponential backoff with jitter
                    int jitter = (int)(Math.random() * 500); // 0-500ms jitter
                    int delay = retryDelay + jitter;

                    // Cap delay based on consecutive failures
                    if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                        delay = MAX_RETRY_DELAY;
                        Log.w(TAG, "Many consecutive failures, using max delay: " + delay + "ms");
                    }

                    SystemClock.sleep(delay);

                    // Increase retry delay with ceiling
                    retryDelay = Math.min(retryDelay * 2, MAX_RETRY_DELAY);

                    // Reset if we've been disconnected for a long time
                    if (consecutiveFailures > 20) {
                        Log.i(TAG, "Resetting connection state after many failures");
                        closeSockets();
                        closeDecoder();
                        closeAudio();
                        nalQueue.clear();
                        pcmQueue.clear();
                        consecutiveFailures = 15; // Don't reset to 0, keep some history
                    }
                }
            }
        });

        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-watchdog");
            while (gen == listenerGen) {
                // only check when streaming and at least one frame has been received;
                // lastFrame == 0 means a new connection is still negotiating RTSP handshake
                if (activeStream && lastFrame > 0
                        && SystemClock.elapsedRealtime() - lastFrame > WATCHDOG_MS) {
                    Log.w(TAG, "Stream is inactive");
                    activeStream = false;
                    // close the TCP socket so input.read() unblocks immediately
                    Socket tcp = mTcpSocket;
                    if (tcp != null) try { tcp.close(); } catch (Exception ignored) {}
                }
                SystemClock.sleep(1000);
            }
        });

        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-video");
            while (gen == listenerGen) {
                try {
                    Frame buffer = nalQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (buffer != null) {
                        decodeFrame(buffer);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // restore interrupt status and stop
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Video decode error: " + e.getMessage());
                }
            }
        });

        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-audio");
            while (gen == listenerGen) {
                try {
                    Frame buffer = pcmQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (buffer != null) {
                        playAudio(buffer);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // restore interrupt status and stop
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Audio playback error: " + e.getMessage());
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // dismiss any open browser dialog; its OnDismissListener will call view.destroy()
        // preventing a WebView native-resource leak when the Activity is rotated
        Dialog browser = mBrowserDialog;
        mBrowserDialog = null;
        if (browser != null && browser.isShowing()) {
            try {
                browser.dismiss();
            } catch (Exception e) {
                Log.e(TAG, "Error dismissing browser dialog", e);
            }
        }
        // stop quad cells if active
        if (quadCells != null) {
            for (QuadCell cell : quadCells) {
                if (cell != null) cell.stop();
            }
            quadCells = null;
        }
        if (listener) {
            listenerGen++;  // invalidate all threads from the current generation
            listener = false;
            activeStream = false;
            closeSockets();
            if (executor != null) {
                executor.shutdownNow();
                try {
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                executor = null;
            }
            closeDecoder();
            closeAudio();
            nalQueue.clear();
            pcmQueue.clear();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        loadSettings();
        if (quadEnabled) {
            // re-enter quad mode after pause/resume
            mSurface.setVisibility(View.GONE);
            quadContainer.setVisibility(View.VISIBLE);
            // Ensure any existing quad cells are stopped before creating new ones
            if (quadCells != null) {
                for (QuadCell cell : quadCells) {
                    if (cell != null) cell.stop();
                }
                quadCells = null;
            }
            // clear stale quad frames
            clearQuadViews();
            quadCells = new QuadCell[4];
            for (int i = 0; i < 4; i++) {
                String url = mHosts[i];
                if (url == null || url.isEmpty() || url.equals(DEFAULT_URL)) continue;
                quadCells[i] = new QuadCell(i, url, quadViews[i]);
                quadCells[i].start();
            }
        } else {
            mSurface.setVisibility(View.VISIBLE);
            quadContainer.setVisibility(View.GONE);
            // Stop any remaining quad cells when switching back to single view
            if (quadCells != null) {
                for (QuadCell cell : quadCells) {
                    if (cell != null) cell.stop();
                }
                quadCells = null;
            }
            // clear stale frame before reconnect
            // Don't start listener for unconfigured slots
            boolean configured = mHost != null && !mHost.isEmpty() && !mHost.equals(DEFAULT_URL);
            if (!listener && configured) {
                listener = true;
                startListener();
            }
        }
    }

    @Override
    protected void onDestroy() {
        // Guard: if the Activity is killed directly (e.g. finishAndRemoveTask)
        // without onPause, perform cleanup here.
        if (listener) {
            listenerGen++;
            listener = false;
            activeStream = false;
            closeSockets();
            if (executor != null) {
                executor.shutdownNow();
                try {
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                executor = null;
            }
            closeDecoder();
            closeAudio();
            nalQueue.clear();
            pcmQueue.clear();
        }
        if (quadCells != null) {
            for (QuadCell cell : quadCells) {
                if (cell != null) cell.stop();
            }
            quadCells = null;
        }
        PopupWindow popup = mMenuPopup;
        if (popup != null) {
            popup.dismiss();
            mMenuPopup = null;
        }
        Dialog browser = mBrowserDialog;
        if (browser != null) {
            browser.dismiss();
            mBrowserDialog = null;
        }
        super.onDestroy();
    }

    /**
     * Self-contained RTSP video-only player for one quadrant cell.
     * Uses TCP exclusively — UDP fixed ports (5000/5002) cannot be shared
     * across multiple simultaneous streams. Audio is intentionally skipped
     * to reduce resource usage.
     */
    private class QuadCell {
        final int index;
        final String host;
        final TextureView view;
        final String tag;

        private volatile boolean running;
        private volatile boolean activeStream;
        private volatile long lastFrame;
        private volatile boolean codecH265;
        private volatile boolean started;
        private volatile boolean threadsStarted;

        private volatile Surface surface;
        private volatile MediaCodec decoder;
        private volatile boolean decoderFailed;
        private final Object decoderLock = new Object();

        private final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(30);
        private final NalAssembler nalAssembler = new NalAssembler(512 * 1024, () -> {
            nalQueue.clear();
            synchronized (decoderLock) {
                MediaCodec c = decoder;
                if (c != null) {
                    decoder = null;
                    try { c.stop(); } catch (Exception ignored) {}
                    try { c.release(); } catch (Exception ignored) {}
                    decoderFailed = false;
                }
            }
        });
        private int lastUnknownPayload = -1;

        private volatile Socket tcpSocket;
        private ExecutorService executor;
        private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        QuadCell(int index, String host, TextureView view) {
            this.index = index;
            this.host = host;
            this.view = view;
            this.tag = "Quad-" + index;
        }

        void start() {
            if (started) return;
            started = true;
            running = true;
            view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                    if (threadsStarted) return;
                    Surface old = surface;
                    surface = new Surface(st);
                    if (old != null) {
                        try {
                            old.release();
                        } catch (Exception e) {
                            Log.e(TAG, tag + " Error releasing old surface", e);
                        }
                    }
                    startThreads();
                }

                @Override
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
                    Surface old = surface;
                    surface = new Surface(st);
                    if (old != null) {
                        synchronized (decoderLock) {
                        }
                        try {
                            old.release();
                        } catch (Exception e) {
                            Log.e(TAG, tag + " Error releasing old surface on size change", e);
                        }
                    }
                }

                @Override
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                    Surface s = surface;
                    surface = null;
                    if (s != null) {
                        try {
                            s.release();
                        } catch (Exception e) {
                            Log.e(TAG, tag + " Error releasing surface on destroy", e);
                        }
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
            });
            if (view.isAvailable()) {
                if (surface == null) {
                    surface = new Surface(view.getSurfaceTexture());
                }
                if (!threadsStarted) startThreads();
            }
        }

        private void startThreads() {
            if (threadsStarted) return;
            threadsStarted = true;
            if (executor != null) {
                // Ensure previous executor is shut down
                executor.shutdownNow();
                try {
                    if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                executor = null;
            }
            executor = Executors.newFixedThreadPool(3);

            // network thread with exponential backoff
            executor.execute(() -> {
                Thread.currentThread().setName(tag + "-net");
                int retryDelay = 1000;
                int consecutiveFailures = 0;
                final int MAX_RETRY_DELAY = 30000;
                final int MAX_CONSECUTIVE_FAILURES = 10;
                while (running) {
                    try {
                        connect();
                        retryDelay = 1000;
                        consecutiveFailures = 0;
                        SystemClock.sleep(1000);
                    } catch (Exception e) {
                        activeStream = false;
                        Log.w(TAG, tag + ": " + e.getMessage());
                        consecutiveFailures++;
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            Log.e(TAG, tag + ": too many consecutive failures, giving up");
                            running = false;
                            break;
                        }
                        SystemClock.sleep(retryDelay);
                        retryDelay = Math.min(retryDelay * 2, MAX_RETRY_DELAY);
                    }
                }
            });

            // watchdog
            executor.execute(() -> {
                Thread.currentThread().setName(tag + "-wd");
                while (running) {
                    if (activeStream && lastFrame > 0
                            && SystemClock.elapsedRealtime() - lastFrame > WATCHDOG_MS) {
                        activeStream = false;
                        Socket tcp = tcpSocket;
                        if (tcp != null) {
                            try {
                                tcp.close();
                            } catch (Exception e) {
                                Log.e(TAG, tag + " Error closing socket in watchdog", e);
                            }
                        }
                    }
                    SystemClock.sleep(1000);
                }
            });

            // video decoder
            executor.execute(() -> {
                Thread.currentThread().setName(tag + "-dec");
                while (running) {
                    try {
                        Frame f = nalQueue.poll(5, TimeUnit.MILLISECONDS);
                        if (f != null) decode(f);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, tag + " decode: " + e.getMessage());
                    }
                }
            });
        }

        void stop() {
            started = false;
            threadsStarted = false;
            running = false;
            activeStream = false;
            nalAssembler.reset();
            Socket tcp = tcpSocket;
            if (tcp != null) {
                try {
                    tcp.close();
                } catch (Exception e) {
                    Log.e(TAG, tag + " Error closing TCP socket", e);
                }
            }
            if (executor != null) {
                executor.shutdownNow();
                try {
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                executor = null;
            }
            synchronized (decoderLock) {
                MediaCodec c = decoder;
                if (c != null) {
                    decoder = null;
                    try { c.stop(); } catch (Exception e) {
                        Log.e(TAG, tag + " Error stopping decoder", e);
                    }
                    try { c.release(); } catch (Exception e) {
                        Log.e(TAG, tag + " Error releasing decoder", e);
                    }
                }
                decoderFailed = false;
            }
            nalQueue.clear();
            Surface s = surface;
            if (s != null) {
                surface = null;
                // don't release s here — it wraps the TextureView's internal
                // SurfaceTexture; releasing it would invalidate the texture.
                // GC will clean up when the QuadCell is discarded.
            }
        }

        private void connect() throws Exception {
            nalAssembler.reset();
            lastUnknownPayload = -1;
            if (host == null || host.isEmpty()) {
                SystemClock.sleep(5000);
                return;
            }

            Uri uri = Uri.parse(host);
            String h = uri.getHost();
            if (h == null || h.isEmpty()) throw new IOException("Invalid host");

            Socket s = null;
            try {
                s = new Socket();
                int port = uri.getPort();
                s.connect(new InetSocketAddress(h, port < 0 ? 554 : port), 5000);
                s.setTcpNoDelay(true);
                s.setSoTimeout(5000);

                InputStream input = s.getInputStream();
                OutputStream w = s.getOutputStream();

                String user = uri.getUserInfo();
                String auth = "";
                if (user != null) {
                    auth = "Authorization: Basic " +
                            Base64.encodeToString(user.getBytes(StandardCharsets.UTF_8),
                                    Base64.NO_WRAP) + "\r\n";
                }

                String path = uri.getEncodedPath();
                String query = uri.getEncodedQuery();
                String rtspUrl = uri.getScheme() + "://" + h
                        + (port >= 0 ? ":" + port : "")
                        + (path != null ? path : "")
                        + (query != null ? "?" + query : "");

                int seq = 1;
                // DESCRIBE
                w.write(("DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent +
                        "Accept: application/sdp\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                w.flush();

                String contentLen = readRtspResponse(input, "Content-Length:");
                int sdpLen = 0;
                if (contentLen != null) {
                    try { sdpLen = Integer.parseInt(contentLen); }
                    catch (NumberFormatException ignored) {}
                }
                StringBuilder sdpBuf = new StringBuilder();
                byte[] buf = new byte[512];
                while (sdpLen > 0) {
                    int n = input.read(buf, 0, Math.min(sdpLen, buf.length));
                    if (n <= 0) break;
                    if (sdpBuf.length() < 4096)
                        sdpBuf.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    sdpLen -= n;
                }

                // parse SDP for video track only (no audio in quad mode)
                String videoControl = null;
                String baseControl = rtspUrl;
                int section = -1;
                for (String line : sdpBuf.toString().split("[\r\n]+")) {
                    if (line.startsWith("m=video")) {
                        section = 0;
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 4) {
                            try { codecH265 = Integer.parseInt(parts[3]) == RTP_PT_H265; }
                            catch (NumberFormatException ignored) {}
                        }
                    } else if (line.startsWith("m=")) {
                        section = 1;
                    } else if (line.startsWith("a=control:")) {
                        String ctrl = line.substring("a=control:".length()).trim();
                        if (section == -1) {
                            baseControl = ctrl.startsWith("rtsp://")
                                    ? ctrl : rtspUrl + "/" + ctrl;
                        } else if (section == 0) {
                            videoControl = ctrl.startsWith("rtsp://")
                                    ? ctrl : baseControl + "/" + ctrl;
                        }
                    }
                }
                if (videoControl == null) videoControl = baseControl + "/trackID=0";

                // SETUP video — TCP only (UDP ports can't be shared across quad cells)
                seq++;
                w.write(("SETUP " + videoControl + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent +
                        "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                w.flush();

                String session = readRtspResponse(input, "Session:");
                if (session == null) throw new IOException("No Session");
                session = session.replaceAll("[\r\n]", "");

                // PLAY
                seq++;
                w.write(("PLAY " + rtspUrl + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent +
                        "Session: " + session + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                w.flush();

                readRtspResponse(input, null);

                s.setSoTimeout(0);
                lastFrame = SystemClock.elapsedRealtime();
                activeStream = true;
                initDecoder();

                tcpSocket = s;
                try {
                    readTcp(input);
                } finally {
                    tcpSocket = null;
                    try {
                        w.write(("TEARDOWN " + rtspUrl + " RTSP/1.0\r\n" +
                                "CSeq: " + (seq + 1) + "\r\n" + auth + mUserAgent +
                                "Session: " + session + "\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                        w.flush();
                    } catch (Exception e) {
                        Log.e(TAG, tag + " Error sending TEARDOWN", e);
                    }
                }
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception e) {
                        Log.e(TAG, tag + " Error closing socket in connect()", e);
                    }
                }
            }
        }

        private void readTcp(InputStream rawInput) throws IOException {
            BufferedInputStream input = new BufferedInputStream(rawInput, 65536);
            byte[] pktBuf = new byte[65535];
            while (activeStream && running) {
                int b = input.read();
                if (b == -1) { activeStream = false; break; }
                if (b != 0x24) continue;

                int channel = input.read();
                int hi = input.read();
                int lo = input.read();
                if (channel == -1 || hi == -1 || lo == -1) { activeStream = false; break; }

                int len = (hi << 8) | lo;
                if (len <= 0 || len > pktBuf.length) continue;

                int read = 0;
                while (read < len) {
                    int n = input.read(pktBuf, read, len - read);
                    if (n == -1) throw new IOException("Truncated");
                    read += n;
                }

                // channel 0 = video RTP; skip audio (channel 2)
                if (channel == 0) {
                    Frame f = obtainFrame(len);
                    System.arraycopy(pktBuf, 0, f.data(), 0, len);
                    f.setLength(len);
                    handlePacket(f);
                }
            }
        }

        private void handlePacket(Frame frame) {
            if (frame.length() < 12) { recycleFrame(frame); return; }
            byte[] data = frame.data();
            if ((data[0] & 0x0F) != 0 || (data[0] & 0x10) != 0) { recycleFrame(frame); return; }

            int pt = data[1] & 0x7F;
            if (pt == RTP_PT_H265 || pt == RTP_PT_H264) {
                codecH265 = (pt == RTP_PT_H265);
                Frame output = nalAssembler.assemble(frame, codecH265);
                recycleFrame(frame);  // assemble() copies what it needs
                if (output != null && !nalQueue.offer(output)) {
                    Log.w(TAG, tag + " queue full, dropping frame");
                }
            } else if (pt != lastUnknownPayload) {
                lastUnknownPayload = pt;
                Log.w(TAG, tag + " unknown PT: " + pt);
                recycleFrame(frame);
            } else {
                recycleFrame(frame);
            }
        }

        private void decode(Frame buffer) {
            if (buffer.length() < 5) return;
            lastFrame = SystemClock.elapsedRealtime();

            int flag = 0;
            int frag = NalAssembler.fragment(buffer.data()[4], codecH265);
            boolean config = codecH265
                    ? (frag == H265_NAL_VPS || frag == H265_NAL_SPS || frag == H265_NAL_PPS)
                    : (frag == H264_NAL_SPS || frag == H264_NAL_PPS);
            if (config) flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;

            boolean needCreate = false;
            synchronized (decoderLock) {
                MediaCodec c = decoder;
                if (c == null) {
                    needCreate = !decoderFailed;
                } else {
                    try {
                        int id = c.dequeueInputBuffer(5_000);
                        if (id >= 0) {
                            ByteBuffer in = c.getInputBuffer(id);
                            if (in != null) {
                                in.clear();
                                in.put(buffer.data(), 0, buffer.length());
                                c.queueInputBuffer(id, 0, buffer.length(),
                                        System.nanoTime() / 1000, flag);
                            }
                        }
                        int oid;
                        while ((oid = c.dequeueOutputBuffer(bufferInfo, 0)) >= 0
                                || oid == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (oid >= 0) c.releaseOutputBuffer(oid, true);
                        }
                    } catch (Exception e) {
                        decoder = null;
                        decoderFailed = true;
                        try { c.stop(); } catch (Exception ignored) {}
                        try { c.release(); } catch (Exception ignored) {}
                    }
                }
            }
            if (needCreate) initDecoder();
        }

        private void initDecoder() {
            synchronized (decoderLock) {
                if (decoder != null) return;
            }
            Surface s = surface;
            if (s == null || !s.isValid()) return;

            String type = codecH265 ? "video/hevc" : "video/avc";
            MediaFormat fmt = MediaFormat.createVideoFormat(type, 1280, 720);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024);

            MediaCodec local;
            try {
                local = MediaCodec.createDecoderByType(type);
                try {
                    local.configure(fmt, s, null, 0);
                    local.start();
                } catch (Exception e) {
                    local.release();
                    throw e;
                }
            } catch (Exception e) {
                Log.e(TAG, tag + " decoder init: " + e.getMessage());
                decoderFailed = true;
                return;
            }

            synchronized (decoderLock) {
                if (decoder != null) { local.release(); return; }
                decoder = local;
            }
            lastFrame = SystemClock.elapsedRealtime();
            // Remove the black overlay now that the decoder pipeline is active
            final int idx = index;
            runOnUiThread(() -> removeQuadOverlay(idx));
        }
    }

    /**
     * Reusable NAL unit reassembler from RTP fragmentation units.
     * Replaces the duplicated buildFrame()/assembleNal() methods with a single
     * implementation shared by the main decoder and QuadCell streams.
     */
    private static class NalAssembler {
        private final byte[] nalBuffer;
        private int nalSize;
        private boolean lastCodec; // tracks codec switches to flush stale state
        private final Runnable onCodecSwitch;

        NalAssembler(int bufferSize, Runnable onCodecSwitch) {
            this.nalBuffer = new byte[bufferSize];
            this.onCodecSwitch = onCodecSwitch;
        }

        void reset() { nalSize = 0; }

        /** Reassemble one RTP packet into (possibly null) NAL frame. */
        Frame assemble(Frame frame, boolean codecH265) {
            byte[] rx = frame.data();
            int rxSize = frame.length();
            int cp = 12;
            rxSize -= cp;
            if (rxSize <= 0) return null;

            int nalBit = 4;
            if (lastCodec != codecH265) {
                lastCodec = codecH265;
                nalSize = 0;
                onCodecSwitch.run();
            }

            int frag = fragment(rx[cp], codecH265);
            if (frag == RTP_FU_H264 || frag == RTP_FU_H265) {
                int minPayload = codecH265 ? 3 : 2;
                if (rxSize < minPayload) return null;

                int staBit, endBit;
                if (codecH265) {
                    staBit = rx[cp + 2] & 0x80;
                    endBit = rx[cp + 2] & 0x40;
                    nalBuffer[4] = (byte) ((rx[cp] & 0x81) | (rx[cp + 2] & 0x3F) << 1);
                    nalBuffer[5] = 1;
                    nalBit++;
                    cp++; rxSize--;
                } else {
                    staBit = rx[cp + 1] & 0x80;
                    endBit = rx[cp + 1] & 0x40;
                    nalBuffer[4] = (byte) ((rx[cp] & 0xE0) | (rx[cp + 1] & 0x1F));
                }
                cp++; rxSize--;

                if (staBit > 0) {
                    nalBuffer[0] = 0; nalBuffer[1] = 0; nalBuffer[2] = 0; nalBuffer[3] = 1;
                    nalBit++;
                    cp++; rxSize--;
                    if (nalBit + rxSize > nalBuffer.length) { nalSize = 0; return null; }
                    System.arraycopy(rx, cp, nalBuffer, nalBit, rxSize);
                    nalSize = rxSize + nalBit;
                    if (endBit > 0) {
                        byte[] out = new byte[nalSize];
                        System.arraycopy(nalBuffer, 0, out, 0, nalSize);
                        return new Frame(out, nalSize);
                    }
                } else {
                    cp++; rxSize--;
                    if (nalSize + rxSize > nalBuffer.length) { nalSize = 0; return null; }
                    System.arraycopy(rx, cp, nalBuffer, nalSize, rxSize);
                    nalSize += rxSize;
                    if (endBit > 0) {
                        byte[] out = new byte[nalSize];
                        System.arraycopy(nalBuffer, 0, out, 0, nalSize);
                        return new Frame(out, nalSize);
                    }
                }
            } else {
                nalBuffer[0] = 0; nalBuffer[1] = 0; nalBuffer[2] = 0; nalBuffer[3] = 1;
                if (nalBit + rxSize > nalBuffer.length) return null;
                System.arraycopy(rx, cp, nalBuffer, nalBit, rxSize);
                nalSize = rxSize + nalBit;
                byte[] out = new byte[nalSize];
                System.arraycopy(nalBuffer, 0, out, 0, nalSize);
                return new Frame(out, nalSize);
            }
            return null; // middle fragment — still accumulating
        }

        /** Extract NAL unit type from a byte at the FU indicator / NAL header position. */
        static int fragment(byte data, boolean codecH265) {
            return codecH265 ? (data >> 1) & 0x3F : data & 0x1F;
        }
    }

    private static class Frame {
        private final byte[] data;
        private int length;

        Frame(byte[] data, int length) {
            this.data = data;
            this.length = length;
        }

        byte[] data() { return data; }
        int length() { return length; }
        void setLength(int length) { this.length = length; }
    }

    /**
     * Simple object pool for Frame objects to reduce GC pressure
     */
    private static class FramePool {
        private final BlockingQueue<Frame> pool;
        private final int maxSize;

        FramePool(int maxSize) {
            this.maxSize = maxSize;
            this.pool = new ArrayBlockingQueue<>(maxSize);
        }

        Frame obtain(int size) {
            Frame frame = pool.poll();
            if (frame != null && frame.data().length >= size) {
                frame.setLength(0);  // clear stale length from prior use
                return frame;
            }
            return new Frame(new byte[size], 0);
        }

        void recycle(Frame frame) {
            if (frame != null && pool.size() < maxSize) {
                pool.offer(frame);
            }
        }

        void clear() {
            pool.clear();
        }
    }

    // Helper method to obtain frame from pool
    private Frame obtainFrame(int size) {
        return framePool.obtain(size);
    }

    // Helper method to recycle frame
    private void recycleFrame(Frame frame) {
        framePool.recycle(frame);
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "Key pressed: " + keyCode);

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (mMenuPopup == null) {
                    createMenu(findViewById(R.id.decoder));
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                PopupWindow popup = mMenuPopup;
                if (popup != null) {
                    popup.dismiss();
                    return true;
                }
                // fall through to default if no popup is open
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
