/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * Decoder.java — main activity for H.264/H.265 hardware video decoding
 *
 */

package com.openipc.decoder;

import android.annotation.SuppressLint;
import android.app.Activity;

import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Decoder extends Activity
        implements MenuManager.Listener {

    private static final String TAG = "OpenIPCDecoder";

    // --- Manager instances ---
    AudioManager audioManager;
    MediaCodecManager videoDecoder;
    RtspClient rtspClient;
    MenuManager menuManager;

    // --- Shared state ---
    final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(32);
    final FramePool framePool = new FramePool(50);
    final NalAssembler nalAssembler = new NalAssembler(MediaCodecManager.MAX_NAL_SIZE, () -> {
        closeDecoder();
        nalQueue.clear();
    }, framePool);

    // --- UI components ---
    private TextureView mSurface;
    private Surface mVideoSurface;
    private TextView statusText;
    private float mDensity;
    private View mClearOverlay;

    // Quad mode
    private boolean quadEnabled;
    private QuadCell[] quadCells;
    private LinearLayout quadContainer;
    private final SurfaceView[] quadViews = new SurfaceView[CELL_COUNT];
    // Quad audio (#12): the cell whose sound plays, -1 for none
    private int quadAudio = -1;
    private final TextView[] quadAudioBadges = new TextView[CELL_COUNT];
    /** Small text in each cell's corner: stats while playing, otherwise the status (#80, #82). */
    private final TextView[] quadStats = new TextView[CELL_COUNT];
    /** Debug text in every cell (menu D), or in none (#92). */
    private boolean showStats;
    /** Slide mode (#108): multi-view, every cell on key frames only; saved as "slides". */
    private boolean slides;
    /** With the debug text on: where the debug page is (#96). */
    private TextView debugUrls;
    private volatile int quadAudioGen;
    private ExecutorService quadAudioExecutor;

    // Camera settings
    /** Camera slots (#13). Multi-view shows 2x2, or 3x3 once a slot above 4 is in use. */
    static final int CAM_COUNT = 8;
    /** Index of the U cell (pushed UDP RTP, #87): the ninth cell of the 3x3 grid. */
    static final int U_CELL = CAM_COUNT;
    /** Multi-view cells: the slots and the U cell. */
    static final int CELL_COUNT = CAM_COUNT + 1;
    private static final String DEFAULT_URL = "rtsp://root:12345@192.168.1.10:554/stream=0";
    private final String[] mHosts = new String[CAM_COUNT];
    /** RTP transport per slot (#67): TCP, UDP or Auto (UDP with fallback to TCP). */
    private final Transport.Mode[] mTransports = new Transport.Mode[CAM_COUNT];
    private int mActive;
    private volatile String mHost;
    private String mVersion;
    // Camera URLs (with passwords) are encrypted at rest (#16)
    private CredentialStore credentials;
    // Stored values that could not be decrypted: kept as they are until the slot is edited
    private final String[] mUnreadable = new String[CAM_COUNT];
    String mUserAgent = "User-Agent: OpenIPC-Decoder/1.0\r\n";

    // Thread management
    private volatile int listenerGen;
    private ExecutorService executor;

    // Pinch-to-zoom
    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mGestureDetector;
    private float mZoomScale = 1.0f;
    private float mPanX;
    private float mPanY;
    private static final float ZOOM_MIN = 1.0f;
    private static final float ZOOM_MAX = 5.0f;

    // Screenshot
    private boolean isTakingScreenshot;

    // Stream size, for fitting the video view into the screen
    private int mVideoWidth;
    private int mVideoHeight;

    // Codec/bitrate state for status overlay (pulled from RtspClient)
    private volatile boolean codecH265;
    private volatile String audioCodec = AudioConfig.NONE;

    // Bitrate tracking (polled from RtspClient on "connected")
    private long statusStreamBytes;
    private long statusBitrateNs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.decoder);

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
        credentials = CredentialStore.create();

        // --- Create managers ---
        videoDecoder = new MediaCodecManager(new MediaCodecManager.Listener() {
            @Override public void onResolutionChanged(int w, int h) { updateResolution(w, h); }
            @Override public void onDecoderStarted() { removeClearOverlay(); }
            @Override public void onDecoderFailed() {}
        });
        audioManager = new AudioManager();
        rtspClient = new RtspClient(framePool, nalAssembler, nalQueue);
        rtspClient.setListener(new RtspClient.Listener() {
            @Override public void onStatusChanged(String s) { updateStatus(s); }
            @Override
            public void onCodecChanged(boolean h265, AudioConfig audio) {
                codecH265 = h265;
                audioCodec = audio.codec;
                // RtspClient configures the AudioManager itself, on its own audio route
            }
            @Override public void onJitterSample(int avg) {
                menuManager.updateQuality(avg);
                menuManager.applyQualityToActive(quadEnabled, mActive);
            }
        });
        rtspClient.setAudioManager(audioManager);
        menuManager = new MenuManager(this, mDensity);
        menuManager.setListener(this);

        // --- UI setup ---
        mSurface = findViewById(R.id.video_surface);
        mSurface.setKeepScreenOn(true);
        statusText = findViewById(R.id.status_text);
        debugUrls = findViewById(R.id.debug_urls);

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
        final View menuAnchor = findViewById(R.id.decoder);
        // Rotation or a resized window: fit the video again (#31)
        menuAnchor.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) fitVideo();
        });
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
            @Override public boolean onDoubleTap(MotionEvent e) { resetZoom(); return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                menuManager.showMenu(menuAnchor, quadEnabled, mActive, mVersion,
                        BuildConfig.GIT_HASH, mHosts);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) { takeScreenshot(); }
        });
        mSurface.setOnTouchListener((v, event) -> {
            mScaleDetector.onTouchEvent(event);
            mGestureDetector.onTouchEvent(event);
            return true;
        });

        // Quad mode layout
        quadContainer = new LinearLayout(this);
        quadContainer.setOrientation(LinearLayout.VERTICAL);
        quadContainer.setVisibility(View.GONE);
        FrameLayout root = (FrameLayout) menuAnchor;
        root.addView(quadContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

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

        updateStatus("disconnected");
        DebugServer.get().setSource(debugSource);
    }

    // ====================== MenuManager.Listener ======================

    @Override
    public void onSwitchCamera(int slot) {
        if (leaveUdpView()) {
            // From the UDP view back to a slot: (re)start it even if it was the active one
            mActive = slot;
            saveSettings();
            return;
        }
        if (slot == mActive && !quadEnabled) return;
        if (quadEnabled) {
            // Exit quad mode and switch to selected camera
            stopQuad();
            mActive = slot;
            saveSettings();
            return;
        }
        mActive = slot;
        saveSettings();
    }

    @Override
    public void onToggleQuad() {
        if (leaveUdpView()) {
            setSlides(false);
            startQuad();
            return;
        }
        if (quadEnabled && slides) {
            // From slides back to the adaptive grid; Q again then leaves multi-view
            setSlides(false);
            return;
        }
        boolean newState = !quadEnabled;
        if (newState) {
            // Q always opens the adaptive grid; slides only come with S (Qodo on #124)
            if (slides) setSlides(false);
            startQuad();
        } else {
            stopQuad();
            boolean configured = mHost != null && !mHost.isEmpty() && !mHost.equals(DEFAULT_URL);
            if (configured) {
                rtspClient.configure(mHost, mUserAgent, mTransports[mActive]);
                startListener();
                rtspClient.start();
            }
        }
    }

    /**
     * S (#108): multi-view with every cell on key frames only, the lowest decoder load. From
     * the single or U view it starts multi-view in this mode; in multi-view it toggles.
     */
    @Override
    public void onToggleSlides() {
        if (leaveUdpView()) {
            setSlides(true);
            startQuad();
            return;
        }
        if (!quadEnabled) {
            setSlides(true);
            startQuad();
            return;
        }
        setSlides(!slides);
    }

    private void setSlides(boolean on) {
        boolean changed = slides != on;
        slides = on;
        menuManager.setSlidesShown(on);
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("slides", on).apply();
        if (!changed) return;
        QuadCell[] cells = quadCells;
        if (on && cells != null) {
            for (QuadCell c : cells) if (c != null) c.setKeyFramesOnly(true);
        }
        // Back to adaptive: plan from scratch, cells are promoted again where they fit
        if (!on && quadEnabled) decodePlanner = newPlanner();
        Toast.makeText(this, on ? "Slides: key frames only" : "Multi-view",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onToggleStats() {
        showStats = !showStats;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("show_stats", showStats).apply();
        menuManager.setStatsShown(showStats);
        updateDebugUrls();
        // Apply at once instead of at the next second
        QuadCell[] cells = quadCells;
        for (int i = 0; i < quadStats.length; i++) {
            QuadCell c = cells != null ? cells[i] : null;
            if (quadStats[i] == null || c == null) continue;
            quadStats[i].setVisibility(showStats ? View.VISIBLE : View.GONE);
        }
        Toast.makeText(this, showStats ? "Debug text on" : "Debug text off",
                Toast.LENGTH_SHORT).show();
    }

    /** One line http://<address>:8085/ per device address while the debug text is on. */
    private void updateDebugUrls() {
        if (debugUrls == null) return;
        if (!showStats) {
            debugUrls.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String ip : NetInfo.addresses()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("http://").append(ip).append(':').append(DebugServer.PORT).append('/');
        }
        if (sb.length() == 0) sb.append("Debug page: no network");
        if (!sb.toString().contentEquals(debugUrls.getText())) debugUrls.setText(sb);
        debugUrls.setVisibility(View.VISIBLE);
        // Above the grid and its overlays, which are added later
        ViewGroup parent = (ViewGroup) debugUrls.getParent();
        if (parent.getChildAt(parent.getChildCount() - 1) != debugUrls) debugUrls.bringToFront();
    }

    @Override
    public void onShowUdp() {
        if (udpViewShown) return;
        if (quadEnabled) {
            stopQuad();
        } else {
            if (rtspClient != null) rtspClient.stop();
            stopExecutor();
            audioManager.closeAudio();
            closeDecoder();
            nalQueue.clear();
        }
        mSurface.setVisibility(View.GONE);
        udpViewShown = true;
        menuManager.setUdpShown(true);
        startUdpGrid();
    }

    /** The U view (#87): a grid of one cell, the U cell, receiving on UDP_RTP_PORT. */
    private void startUdpGrid() {
        buildQuadGrid(1);
        quadContainer.setVisibility(View.VISIBLE);
        quadContainer.bringToFront();
        ensureQuadLaidOut();
        clearQuadViews();
        createQuadCells();
    }

    /** Stops the cells of the grid, its audio and its stats; the views stay. */
    private void stopQuadCells() {
        quadContainer.removeCallbacks(quadStatsTick);
        stopQuadAudio();
        if (quadCells != null) {
            for (QuadCell cell : quadCells) {
                if (cell != null) cell.stop();
            }
            quadCells = null;
        }
    }

    /** Hide the UDP view; true if it was shown. The caller starts what comes next. */
    private boolean leaveUdpView() {
        if (!udpViewShown) return false;
        udpViewShown = false;
        menuManager.setUdpShown(false);
        stopQuadCells();
        quadContainer.setVisibility(View.GONE);
        mSurface.setVisibility(View.VISIBLE);
        return true;
    }

    @Override
    public void onExit() { finishAndRemoveTask(); }

    @Override
    public void onShowSettings() { menuManager.showUrlEditor(mActive, mHosts, mTransports); }

    @Override
    public void onWebUI() { menuManager.startBrowser(mHost); }

    @Override
    public void onSaveSettings() { saveSettings(); }

    // ====================== Video lifecycle ======================

    private void createDecoder() {
        videoDecoder.setSurface(mVideoSurface);
        videoDecoder.createDecoder(codecH265);
    }

    private void closeDecoder() { videoDecoder.closeDecoder(); }

    // ====================== Audio lifecycle ======================

    private void closeAudio() { audioManager.closeAudio(); }

    // ====================== Settings ======================

    @SuppressLint("AuthLeak")
    private void loadSettings() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);

        if (pref.contains("host") && !pref.contains("host_0")) {
            SharedPreferences.Editor edit = pref.edit();
            edit.putString("host_0", credentials.seal(pref.getString("host", DEFAULT_URL)));
            edit.putBoolean("type_0", pref.getBoolean("type", false));
            edit.remove("host");
            edit.remove("type");
            edit.apply();
        }

        // Restore the last camera and mode (#22); both are saved but were never read back
        mActive = clampSlot(pref.getInt("active", 0));
        quadEnabled = pref.getBoolean("quad_enabled", false);
        showStats = pref.getBoolean("show_stats", false);
        slides = pref.getBoolean("slides", false);
        if (menuManager != null) menuManager.setSlidesShown(slides);
        if (menuManager != null) menuManager.setStatsShown(showStats);
        SharedPreferences.Editor migrate = null;
        for (int i = 0; i < CAM_COUNT; i++) {
            String stored = pref.getString("host_" + i, null);
            String url = credentials.open(stored);
            mHosts[i] = url != null ? url : DEFAULT_URL;
            mUnreadable[i] = url == null && stored != null ? stored : null;
            // Auto for a slot that has never stored one (#112)
            mTransports[i] = Transport.Mode.stored(pref.getString("transport_" + i, null));
            // Plain URLs from older versions are re-saved encrypted right away
            if (credentials.needsMigration(stored)) {
                if (migrate == null) migrate = pref.edit();
                migrate.putString("host_" + i, credentials.seal(stored));
            }
        }
        if (migrate != null) migrate.apply();

        Intent intent = getIntent();
        String link = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (link != null) {
            Log.d(TAG, "Link: " + Uri.parse(link).getHost());
            mHosts[mActive] = sanitizeUrl(link);
            // Optional, e.g. --es transport udp; a link without it keeps the slot's setting
            String transport = intent.getStringExtra("transport");
            if (transport != null) mTransports[mActive] = Transport.Mode.parse(transport);
            // A shared link is meant to be watched: single view, and saved so that it is
            // still there after the next pause/resume
            quadEnabled = false;
            pref.edit()
                    .putString("host_" + mActive, credentials.sealForSlot(
                            mHosts[mActive], mUnreadable[mActive], DEFAULT_URL))
                    .putBoolean("quad_enabled", false)
                    .putString("transport_" + mActive, mTransports[mActive].name())
                    .apply();
            intent.removeExtra(Intent.EXTRA_TEXT);
        }
        applyActiveCamera();
    }

    static int clampSlot(int slot) {
        return slot >= 0 && slot < CAM_COUNT ? slot : 0;
    }

    static String sanitizeUrl(String url) {
        return url.replaceAll("[\r\n]", "");
    }

    private void applyActiveCamera() {
        mHost = mHosts[mActive];
        resetZoom();
        if (mHost == null || mHost.isEmpty() || mHost.equals(DEFAULT_URL)) {
            updateStatus("unconfigured");
        } else {
            updateStatus("connecting");
        }
    }

    private void saveSettings() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        edit.putInt("active", mActive);
        for (int i = 0; i < CAM_COUNT; i++) {
            edit.putString("host_" + i,
                    credentials.sealForSlot(mHosts[i], mUnreadable[i], DEFAULT_URL));
            edit.putString("transport_" + i, mTransports[i].name());
        }
        edit.apply();

        applyActiveCamera();
        if (quadEnabled) return;

        listenerGen++;
        if (rtspClient != null) {
            rtspClient.stop();
        }
        audioManager.closeAudio();
        closeDecoder();
        nalQueue.clear();

        boolean configured = mHost != null && !mHost.isEmpty() && !mHost.equals(DEFAULT_URL);
        if (!configured) {
            clearVideo();
            return;
        }

        rtspClient.configure(mHost, mUserAgent, mTransports[mActive]);
        startListener();
        rtspClient.start();
    }

    // ====================== Status overlay ======================

    // Delayed overlay updates after "connected"; cancelled on every status change
    private final Runnable statusDetails = this::showConnectedDetails;
    private final Runnable statusHide = () -> {
        if (statusText != null) statusText.setVisibility(View.GONE);
    };

    private void showConnectedDetails() {
        if (statusText == null || statusText.getVisibility() != View.VISIBLE) return;
        String videoCodec = codecH265 ? "H.265" : "H.264";
        String aCodec;
        switch (audioCodec) {
            case AudioConfig.AAC:  aCodec = " • AAC"; break;
            case AudioConfig.PCMU:
            case AudioConfig.PCMA: aCodec = " • G.711"; break;
            case AudioConfig.PCM:  aCodec = " • PCM"; break;
            default:               aCodec = ""; break;
        }
        long elapsedNs = System.nanoTime() - statusBitrateNs;
        String bps = "";
        long bytes = rtspClient != null ? rtspClient.getStreamBytes() : 0;
        if (elapsedNs > 500_000_000 && bytes > 0) {
            double mbps = (bytes * 8.0 / 1_000_000)
                    / (elapsedNs / 1_000_000_000.0);
            bps = String.format(Locale.US, " • %.1f Mbps", mbps);
        }
        // The transport the stream really uses, not the slot's setting (#115)
        String inUse = rtspClient != null ? rtspClient.transportInUse() : null;
        String transport = inUse != null
                ? Transport.label(inUse, mTransports[mActive]) + " • " : "";
        statusText.setText("Connected • " + transport + videoCodec + aCodec + bps);
    }

    private void updateStatus(String status) {
        singleState = status;
        runOnUiThread(() -> {
            if (statusText == null) return;
            // A stale timer from an earlier "connected" must not overwrite or hide this status
            statusText.removeCallbacks(statusDetails);
            statusText.removeCallbacks(statusHide);

            switch (status) {
                case "connecting":
                    statusText.setText(getString(R.string.status_connecting));
                    statusText.setVisibility(View.VISIBLE);
                    break;
                case "connected":
                    statusText.setText(getString(R.string.status_connected));
                    statusText.setVisibility(View.VISIBLE);
                    statusStreamBytes = rtspClient != null ? rtspClient.getStreamBytes() : 0;
                    statusBitrateNs = rtspClient != null ? rtspClient.getBitrateMarkNs() : System.nanoTime();
                    // After 1 second, refresh with codec + bitrate info; hide after 5 seconds
                    statusText.postDelayed(statusDetails, 1000);
                    statusText.postDelayed(statusHide, 5000);
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

    // ====================== Quad mode ======================

    private void startQuad() {
        if (rtspClient != null) {
            rtspClient.stop();
        }
        closeDecoder();
        closeAudio();
        nalQueue.clear();
        stopExecutor();

        mSurface.setVisibility(View.GONE);
        buildQuadGrid(quadGridSize(mHosts, DEFAULT_URL));
        quadContainer.setVisibility(View.VISIBLE);
        ensureQuadLaidOut();
        removeClearOverlay();

        createQuadCells();

        quadEnabled = true;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("quad_enabled", true).apply();
    }

    private void stopQuad() {
        quadContainer.removeCallbacks(quadStatsTick);
        stopQuadAudio();
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
    }

    /**
     * Seen on an Android 10 TV box: after the window briefly lost visibility (a task brought
     * to the front by another start), the Quad container stayed at 0x0 with no layout pass
     * scheduled, so the cells never got a surface. Check once it should have been laid out
     * and force a pass if not.
     */
    private void ensureQuadLaidOut() {
        quadContainer.postDelayed(() -> {
            if (!(quadEnabled || udpViewShown) || quadContainer.getWidth() > 0) return;
            Log.w(TAG, "Quad layout did not happen, forcing a layout pass");
            View decor = getWindow().getDecorView();
            for (View v = quadContainer; v != null && v != decor;
                 v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                v.forceLayout();
            }
            decor.requestLayout();
            decor.invalidate();
        }, 500);
    }

    /** Grid side for multi-view: 2 while only slots 1-4 are used, else 3 (#13). */
    static int quadGridSize(String[] hosts, String defaultUrl) {
        for (int i = 4; i < hosts.length; i++) {
            String url = hosts[i];
            if (url != null && !url.isEmpty() && !url.equals(defaultUrl)) return 3;
        }
        return 2;
    }

    /**
     * (Re)build the multi-view grid; cells beyond it have no view. Side 3: the ninth cell is
     * the U cell; side 1: the U cell alone (the U view).
     */
    private void buildQuadGrid(int size) {
        quadContainer.removeAllViews();
        Arrays.fill(quadViews, null);
        Arrays.fill(quadAudioBadges, null);
        Arrays.fill(quadStats, null);
        for (int row = 0; row < size; row++) {
            LinearLayout lr = new LinearLayout(this);
            lr.setOrientation(LinearLayout.HORIZONTAL);
            quadContainer.addView(lr, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            for (int col = 0; col < size; col++) {
                int idx = size == 1 ? U_CELL : row * size + col;
                quadViews[idx] = new SurfaceView(this);
                quadViews[idx].setOnClickListener(cv ->
                        menuManager.showMenu(findViewById(R.id.decoder), quadEnabled, mActive, mVersion,
                                BuildConfig.GIT_HASH, mHosts));
                // Each cell is a FrameLayout so overlays can be stacked on the video
                FrameLayout cell = new FrameLayout(this);
                cell.addView(quadViews[idx], new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                // Long press selects the cell whose audio plays (#12); the U cell has video only
                if (idx != U_CELL) {
                    quadViews[idx].setOnLongClickListener(v -> {
                        toggleQuadAudio(idx);
                        return true;
                    });
                }
                TextView badge = new TextView(this);
                badge.setText(R.string.quad_audio_badge);
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(11);
                badge.setBackgroundColor(0x99000000);
                badge.setPadding(dp(6), dp(2), dp(6), dp(2));
                badge.setVisibility(View.GONE);
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
                bp.setMargins(dp(4), dp(4), dp(4), dp(4));
                cell.addView(badge, bp);
                quadAudioBadges[idx] = badge;
                TextView stats = new TextView(this);
                stats.setTextColor(Color.WHITE);
                stats.setTextSize(10);
                stats.setBackgroundColor(0x80000000);
                stats.setPadding(dp(4), dp(1), dp(4), dp(1));
                stats.setVisibility(View.GONE);
                FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
                sp.setMargins(dp(2), dp(2), dp(2), dp(2));
                cell.addView(stats, sp);
                quadStats[idx] = stats;
                lr.addView(cell, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            }
        }
    }

    /** Port of the U cell (#68, #87); 5600 is the usual default for pushed RTP. */
    static final int UDP_RTP_PORT = 5600;
    /** What the U cell listens to: any sender, unicast, on UDP_RTP_PORT (#87). */
    static final String UDP_CELL_URL = "udp://@:" + UDP_RTP_PORT;
    /** The U cell full-screen (menu button U). */
    private boolean udpViewShown;

    /** Shown over the U cell until its first frame: where to send the stream. */
    private TextView udpHintCell() {
        TextView hint = new TextView(this);
        hint.setText(udpHintText(NetInfo.addresses(), UDP_RTP_PORT));
        hint.setTextColor(Color.GRAY);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(12), dp(12), dp(12), dp(12));
        hint.setBackgroundColor(Color.BLACK);
        // Like the camera cells and the video view: a tap opens the menu
        hint.setOnClickListener(v -> menuManager.showMenu(findViewById(R.id.decoder),
                quadEnabled, mActive, mVersion, BuildConfig.GIT_HASH, mHosts));
        return hint;
    }

    /** Keeps the addresses in the U cell's hint current (DHCP, cable, Wi-Fi), once a second. */
    private void refreshUdpHint() {
        SurfaceView v = quadViews[U_CELL];
        if (v == null) return;
        View overlay = ((View) v.getParent()).findViewWithTag("quad_overlay_" + U_CELL);
        if (!(overlay instanceof TextView) || overlay.getVisibility() != View.VISIBLE) return;
        String text = udpHintText(NetInfo.addresses(), UDP_RTP_PORT);
        TextView hint = (TextView) overlay;
        if (!text.contentEquals(hint.getText())) hint.setText(text);
    }

    /** Hint of the U cell: every usable IPv4 address of the device, one per line (#87). */
    static String udpHintText(List<String> ips, int port) {
        StringBuilder target = new StringBuilder();
        for (String ip : ips) target.append('\n').append(ip).append(':').append(port);
        if (target.length() == 0) target.append("\nthis device:").append(port);
        return "UDP RTP \u00b7 port " + port + "\nWaiting for a stream\u2026\n\n"
                + "Send the camera's RTP/UDP output to" + target;
    }


    private void createQuadCells() {
        quadCells = new QuadCell[CELL_COUNT];
        for (int i = 0; i < CELL_COUNT; i++) {
            if (quadViews[i] == null) continue;
            String url = i == U_CELL ? UDP_CELL_URL : mHosts[i];
            if (url == null || url.isEmpty() || url.equals(DEFAULT_URL)) continue;
            Transport.Mode mode = i == U_CELL ? Transport.Mode.UDP : mTransports[i];
            quadCells[i] = new QuadCell(i, url, mode, quadViews[i], framePool, mUserAgent,
                    index -> runOnUiThread(() -> removeQuadOverlay(index)));
            quadCells[i].start();
        }
        quadAudio = getSharedPreferences("settings", MODE_PRIVATE).getInt("quad_audio", -1);
        startQuadAudio();
        startStatsTick();
    }

    // ====================== Quad audio (#12) ======================

    private void toggleQuadAudio(int index) {
        quadAudio = quadAudio == index ? -1 : index;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putInt("quad_audio", quadAudio).apply();
        applyQuadAudio();
        Toast.makeText(this, quadAudio < 0 ? "Audio off"
                : "Audio: camera " + (quadAudio + 1), Toast.LENGTH_SHORT).show();
    }

    /** Route audio of the selected cell to the AudioManager and update the badges. */
    private void applyQuadAudio() {
        // A new route: audio of a previously selected cell can no longer reach playback
        int route = audioManager.newRoute();
        QuadCell[] cells = quadCells;
        for (int i = 0; i < CELL_COUNT; i++) {
            QuadCell cell = cells != null ? cells[i] : null;
            boolean on = i == quadAudio && cell != null;
            if (cell != null) cell.setAudioSink(on ? audioManager : null, route);
            if (on) audioManager.configure(cell.getAudioConfig(), route);
            if (quadAudioBadges[i] != null) {
                quadAudioBadges[i].setVisibility(on ? View.VISIBLE : View.GONE);
            }
        }
    }

    /** The playback thread for Quad audio (single view has its own in startListener). */
    private void startQuadAudio() {
        stopQuadAudio();
        applyQuadAudio();
        final int gen = ++quadAudioGen;
        quadAudioExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "quad-audio"));
        quadAudioExecutor.execute(() -> {
            while (gen == quadAudioGen && !Thread.currentThread().isInterrupted()) {
                audioManager.playPcmFrame();
            }
        });
    }

    private void stopQuadAudio() {
        quadAudioGen++;
        if (quadAudioExecutor != null) {
            quadAudioExecutor.shutdownNow();
            quadAudioExecutor = null;
        }
        QuadCell[] cells = quadCells;
        if (cells != null) {
            for (QuadCell cell : cells) {
                if (cell != null) cell.setAudioSink(null, 0);
            }
        }
        // Invalidates in-flight Quad frames and track creation, clears the queues
        audioManager.newRoute();
        for (TextView badge : quadAudioBadges) {
            if (badge != null) badge.setVisibility(View.GONE);
        }
    }

    /** Clear the video view so no stale frame lingers. */
    private void clearVideo() {
        if (mSurface == null) return;
        if (mClearOverlay == null) {
            mClearOverlay = new View(this);
            mClearOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            mClearOverlay.setBackgroundColor(Color.BLACK);
            mClearOverlay.setTag("clear_overlay");
            ((FrameLayout) mSurface.getParent()).addView(mClearOverlay);
        }
        mClearOverlay.setVisibility(View.VISIBLE);
        mClearOverlay.bringToFront();
    }

    private void removeClearOverlay() {
        if (mClearOverlay != null) {
            mClearOverlay.setVisibility(View.GONE);
        }
    }

    private void clearQuadViews() {
        for (int i = 0; i < CELL_COUNT; i++) showQuadOverlay(i);
    }

    /** Black overlay over a cell; its corner text stays visible above it. */
    private void showQuadOverlay(int i) {
        SurfaceView t = quadViews[i];
        if (t == null) return;
        FrameLayout cell = (FrameLayout) t.getParent();
        View overlay = cell.findViewWithTag("quad_overlay_" + i);
        if (overlay == null) {
            // The U cell explains where to send the stream until the first frame (#87)
            overlay = i == U_CELL ? udpHintCell() : new View(this);
            if (i == U_CELL && udpViewShown) ((TextView) overlay).setTextSize(20);
            overlay.setTag("quad_overlay_" + i);
            overlay.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            overlay.setBackgroundColor(Color.BLACK);
            cell.addView(overlay);
        } else if (i == U_CELL) {
            ((TextView) overlay).setText(udpHintText(NetInfo.addresses(), UDP_RTP_PORT));
        }
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        if (quadStats[i] != null) quadStats[i].bringToFront();
    }

    // ====================== Cell stats and decoder load (#80, #82, #83) ======================

    private final Runnable quadStatsTick = new Runnable() {
        private int ticks;

        @Override
        public void run() {
            QuadCell[] cells = quadCells;
            if (!(quadEnabled || udpViewShown) || cells == null) return;
            long now = System.nanoTime() / 1_000_000;
            boolean log = ++ticks % 5 == 0;
            for (int i = 0; i < cells.length; i++) {
                QuadCell c = cells[i];
                TextView v = quadStats[i];
                if (c == null || v == null) continue;
                String line = c.sample(now);
                v.setText(line);
                // The corner text of every cell, or of none: menu D (#92)
                v.setVisibility(showStats ? View.VISIBLE : View.GONE);
                if (log) Log.i(TAG, "Quad-" + i + ": " + line);
            }
            planDecoding(cells, now);
            updateDebugUrls();
            refreshUdpHint();
            quadContainer.postDelayed(this, 1000);
        }
    };

    private void startStatsTick() {
        quadContainer.removeCallbacks(quadStatsTick);
        decodePlanner = newPlanner();
        quadContainer.postDelayed(quadStatsTick, 1000);
    }

    /**
     * Learnt decoder capacity of this device in macroblocks/s, or 0 (#85). "_v2": values
     * learnt before #89 counted NAL units dropped after a 5 ms wait as overload; not reused.
     */
    private static final String PREF_DECODER_CAPACITY = "decoder_capacity_v2";
    /** Smallest load that made the decoder lose frames, or 0: not retried after a restart. */
    private static final String PREF_DECODER_FAILED = "decoder_failed_load_v2";
    /** 1080p at 60 fps: the usual declared budget, until the decoder declares its own. */
    private static final long DEFAULT_CAPACITY = DecodePlanner.fullLoad(1920, 1080, 60);
    private DecodePlanner decodePlanner;

    private DecodePlanner newPlanner() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        if (pref.contains("decoder_capacity")) {
            pref.edit().remove("decoder_capacity").remove("decoder_failed_load").apply();
        }
        long learnt = pref.getLong(PREF_DECODER_CAPACITY, 0);
        long failed = pref.getLong(PREF_DECODER_FAILED, 0);
        if (learnt > 0) return new DecodePlanner(learnt, true, failed);
        long declared = MediaCodecManager.declaredBlocksPerSec;
        return new DecodePlanner(declared > 0 ? declared : DEFAULT_CAPACITY, false, failed);
    }

    /**
     * Every camera stays on screen: cells decode every frame while the decoder keeps up and
     * only key frames otherwise. Decoder losses (queue overflow, no free input buffer) are the
     * signal; frames lost on the network do not count. The capacity learnt this way is kept
     * for this device (#83, #85).
     */
    private void planDecoding(QuadCell[] cells, long now) {
        if (slides) {
            // Slides (#108): nothing is promoted; a cell that was at full rate goes back
            for (QuadCell c : cells) if (c != null) c.setKeyFramesOnly(true);
            return;
        }
        DecodePlanner planner = decodePlanner;
        if (planner == null) return;
        planner.offerDeclared(MediaCodecManager.declaredBlocksPerSec);
        long before = planner.capacity();
        long failedBefore = planner.failedLoad();
        boolean learntBefore = planner.isLearnt();

        java.util.List<DecodePlanner.Cell> order = new java.util.ArrayList<>();
        // Priority: the U cell once its stream plays (#94), the cell whose sound plays, then
        // slot order. While U receives nothing it stays last and takes no share
        int first = U_CELL < cells.length && cells[U_CELL] != null && cells[U_CELL].isPlaying()
                ? U_CELL : -1;
        if (first >= 0) order.add(plannerCell(first, cells[first], now));
        if (quadAudio >= 0 && quadAudio < cells.length && cells[quadAudio] != null
                && quadAudio != first) {
            order.add(plannerCell(quadAudio, cells[quadAudio], now));
        }
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != null && i != quadAudio && i != first) {
                order.add(plannerCell(i, cells[i], now));
            }
        }
        DecodePlanner.Decision d = planner.step(order, now);
        if (d.action != DecodePlanner.Action.NONE) {
            QuadCell c = cells[d.index];
            switch (d.action) {
                case PROMOTE:
                    c.setKeyFramesOnly(false);
                    Log.i(TAG, "Quad-" + d.index + ": full rate");
                    break;
                case DEMOTE:
                    c.setKeyFramesOnly(true);
                    Log.w(TAG, "Decoder losses: Quad-" + d.index + " to key frames, capacity "
                            + planner.capacity() + " blocks/s");
                    break;
                case PAUSE:
                    String reason = "Paused: decoder overloaded";
                    Log.w(TAG, "Decoder losses even on key frames: pausing Quad-" + d.index);
                    c.pause(reason);
                    showQuadOverlay(d.index);
                    if (quadStats[d.index] != null) quadStats[d.index].setText(reason);
                    break;
                default:
                    break;
            }
        }
        boolean capacityChanged = planner.isLearnt()
                && (!learntBefore || planner.capacity() != before);
        if (capacityChanged || planner.failedLoad() != failedBefore) {
            SharedPreferences.Editor e = getSharedPreferences("settings", MODE_PRIVATE).edit();
            if (planner.isLearnt()) e.putLong(PREF_DECODER_CAPACITY, planner.capacity());
            e.putLong(PREF_DECODER_FAILED, planner.failedLoad()).apply();
            Log.i(TAG, "Decoder capacity of this device: " + planner.capacity()
                    + " blocks/s, failed at " + planner.failedLoad());
        }
    }

    private static DecodePlanner.Cell plannerCell(int index, QuadCell c, long now) {
        // At full rate a few lost frames per second are noise; on key frames, a cell decodes
        // one frame per GOP, so every decoder loss counts, whatever the frame rate
        boolean lossy = c.isSettled(now) && (c.isKeyFramesOnly() ? c.lostFps() > 0
                : c.inFps() >= 5 && c.lostFps() >= Math.max(2f, 0.1f * c.inFps()));
        DecodePlanner.Cell pc = new DecodePlanner.Cell(index, !c.isKeyFramesOnly(),
                c.knowsLoad(), c.knowsLoad() ? c.fullLoad() : 0, lossy);
        pc.paused = c.isPaused();
        pc.playing = c.isPlaying();
        if (c.knowsLoad()) pc.keyLoad = c.keyLoad();
        return pc;
    }

    private void removeQuadOverlay(int index) {
        if (index < 0 || index >= quadViews.length || quadViews[index] == null) return;
        SurfaceView t = quadViews[index];
        View overlay = ((View) t.getParent()).findViewWithTag("quad_overlay_" + index);
        if (overlay != null) {
            overlay.setVisibility(View.GONE);
        }
    }

    // ====================== Debug HTTP server (#96) ======================

    /** How long a request waits for the UI thread before it reports it as not responding. */
    private static final long DEBUG_UI_TIMEOUT_MS = 1000;
    private final DebugServer.Source debugSource = new DebugServer.Source() {
        @Override public Map<String, Object> snapshot() { return debugSnapshot(); }
        @Override public byte[] snapshotJpeg(boolean labels) { return debugSnapshotJpeg(labels); }
    };
    /** A cell counts as playing in a snapshot when it showed a frame this recently (#98). */
    private static final long SNAPSHOT_LIVE_MS = 2000;
    /** Last status of the single view, from updateStatus(). */
    private volatile String singleState = "disconnected";

    /** Called on a server thread (#98): the cells are chosen on the UI thread, copied here. */
    private byte[] debugSnapshotJpeg(boolean labels) {
        AtomicReference<List<Snapshots.Cell>> ref = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        int[] side = new int[2];
        CountDownLatch done = new CountDownLatch(1);
        Handler main = new Handler(Looper.getMainLooper());
        Runnable pick = () -> {
            try {
                // The request may have given up already: then no capture at all (#103)
                if (abandoned.get()) return;
                ref.set(snapshotCells(side));
                if (abandoned.get()) recycleFrames(ref.getAndSet(null));
            } finally {
                done.countDown();
            }
        };
        main.post(pick);
        boolean inTime = false;
        try {
            inTime = done.await(DEBUG_UI_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!inTime) {
            abandoned.set(true);
            main.removeCallbacks(pick);
            recycleFrames(ref.getAndSet(null));
            return null;
        }
        List<Snapshots.Cell> cells = ref.get();
        return cells != null ? Snapshots.render(cells, side[0], side[1], labels) : null;
    }

    /** Frames taken for a snapshot that will not be drawn. */
    private static void recycleFrames(List<Snapshots.Cell> cells) {
        if (cells == null) return;
        for (Snapshots.Cell c : cells) {
            if (c.frame != null) c.frame.recycle();
        }
    }

    /**
     * What to draw, UI thread only; side[0] gets the grid side (1 for a single picture),
     * side[1] the width of the picture.
     */
    private List<Snapshots.Cell> snapshotCells(int[] side) {
        List<Snapshots.Cell> cells = new ArrayList<>();
        int width = Snapshots.width(getResources().getDisplayMetrics().widthPixels);
        side[1] = width;
        if (!mResumed) {
            Snapshots.Cell c = new Snapshots.Cell();
            c.label = "App in background";
            cells.add(c);
            side[0] = 1;
            return cells;
        }
        if (udpViewShown) {
            cells.add(snapshotCell(U_CELL));
            side[0] = 1;
            return cells;
        }
        if (!quadEnabled) {
            Snapshots.Cell c = new Snapshots.Cell();
            c.slot = mActive + 1;
            c.live = mResumed && "connected".equals(singleState)
                    && videoDecoder.renderedWithin(SNAPSHOT_LIVE_MS);
            c.label = statusText != null && statusText.getVisibility() == View.VISIBLE
                    ? statusText.getText().toString() : c.live ? "Playing" : "No new frames";
            if (c.live) {
                c.frame = mSurface.getBitmap(width, Snapshots.height(width));
            }
            cells.add(c);
            side[0] = 1;
            return cells;
        }
        int size = quadGridSize(mHosts, DEFAULT_URL);
        for (int i = 0; i < size * size; i++) cells.add(snapshotCell(i));
        side[0] = size;
        return cells;
    }

    /** One multi-view cell of a snapshot; slot 0 is the U cell. UI thread only. */
    private Snapshots.Cell snapshotCell(int i) {
        Snapshots.Cell c = new Snapshots.Cell();
        c.slot = i == U_CELL ? 0 : i + 1;
        QuadCell[] quad = quadCells;
        QuadCell cell = quad != null && i < quad.length ? quad[i] : null;
        TextView stats = quadStats[i];
        if (cell == null) {
            c.label = "not configured";
        } else {
            c.label = stats != null ? stats.getText().toString() : "";
            c.live = cell.showsVideo(SNAPSHOT_LIVE_MS);
            c.view = quadViews[i];
            if (i == U_CELL && !c.live) {
                c.label = "U \u00b7 waiting for RTP on port " + UDP_RTP_PORT;
            }
        }
        return c;
    }

    /** Called on a server thread: the state is read on the UI thread, which owns it. */
    private Map<String, Object> debugSnapshot() {
        AtomicReference<Map<String, Object>> ref = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ref.set(debugState());
            } finally {
                done.countDown();
            }
        });
        boolean answered;
        try {
            answered = done.await(DEBUG_UI_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            answered = false;
        }
        Map<String, Object> m = ref.get();
        if (!answered || m == null) {
            // A stuck UI thread is itself the news (ANR, #76): report what needs no UI state
            m = debugDevice();
            m.put("ui_thread", "not responding within " + DEBUG_UI_TIMEOUT_MS + " ms");
        }
        return m;
    }

    /** Device and app facts, safe to read from any thread; the page's header comes first. */
    private Map<String, Object> debugDevice() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "OpenIPC Decoder");
        m.put("version", mVersion);
        m.put("git", BuildConfig.GIT_HASH);
        m.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        m.put("uptime_s", SystemClock.elapsedRealtime() / 1000);
        String soc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.HARDWARE;
        m.put("device", Build.MANUFACTURER + " " + Build.MODEL + " \u00b7 " + soc
                + " \u00b7 board " + Build.BOARD);
        m.put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        m.put("screen", getResources().getDisplayMetrics().widthPixels + "x"
                + getResources().getDisplayMetrics().heightPixels);
        List<String> urls = new ArrayList<>();
        for (String ip : NetInfo.addresses()) urls.add("http://" + ip + ":" + DebugServer.PORT + "/");
        m.put("addresses", urls);
        m.put("server", DebugServer.get().state());
        return m;
    }

    /** The state behind the on-screen texts; UI thread only. */
    private Map<String, Object> debugState() {
        Map<String, Object> m = debugDevice();
        m.put("ui_thread", "ok");
        m.put("mode", !mResumed ? "paused" : udpViewShown ? "udp"
                : quadEnabled ? (slides ? "slides" : "multi-view") : "single");
        m.put("active_slot", mActive + 1);
        DecodePlanner planner = decodePlanner;
        if (quadEnabled && planner != null) {
            // Decoder capacity in macroblocks/s: declared, or learnt on this device (#85)
            Map<String, Object> dec = new LinkedHashMap<>();
            dec.put("capacity", planner.capacity());
            dec.put("learnt", planner.isLearnt());
            dec.put("failed_load", planner.failedLoad());
            m.put("decoder", dec);
        }
        if (udpViewShown) {
            Map<String, Object> udp = new LinkedHashMap<>();
            udp.put("port", UDP_RTP_PORT);
            udp.put("addresses", NetInfo.addresses());
            m.put("udp", udp);
        } else if (!quadEnabled) {
            Map<String, Object> single = new LinkedHashMap<>();
            single.put("url", DebugServer.redact(mHost));
            // In use (with the mode after a fallback) and the slot's setting (#112)
            single.put("transport", Transport.label(
                    rtspClient != null ? rtspClient.transportInUse() : null, mTransports[mActive]));
            single.put("transport_mode", mTransports[mActive] != null
                    ? mTransports[mActive].name() : null);
            single.put("video", mResumed && "connected".equals(singleState)
                    && videoDecoder.renderedWithin(SNAPSHOT_LIVE_MS));
            single.put("status", statusText != null && statusText.getVisibility() == View.VISIBLE
                    ? statusText.getText().toString() : singleState);
            if (mVideoWidth > 0) single.put("size", mVideoWidth + "x" + mVideoHeight);
            single.put("codec", codecH265 ? "H.265" : "H.264");
            single.put("audio", audioCodec);
            m.put("single", single);
        }
        QuadCell[] cells = quadCells;
        if ((quadEnabled || udpViewShown) && cells != null) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < CELL_COUNT; i++) {
                if (quadViews[i] == null) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                boolean u = i == U_CELL;
                c.put("slot", u ? "U" : i + 1);
                QuadCell cell = cells[i];
                if (cell == null) {
                    c.put("video", false);
                    c.put("text", "not configured");
                } else {
                    c.put("video", cell.showsVideo(SNAPSHOT_LIVE_MS));
                    TextView v = quadStats[i];
                    c.put("text", v != null ? v.getText().toString() : null);
                    c.put("url", u ? UDP_CELL_URL : DebugServer.redact(mHosts[i]));
                    // In use (with the mode after a fallback) and the slot's setting (#112)
                    c.put("transport", cell.transportLabel());
                    c.put("transport_mode", u ? "UDP" : mTransports[i] != null
                            ? mTransports[i].name() : null);
                    c.put("out_fps", cell.outFps());
                    c.put("in_fps", cell.inFps());
                    c.put("lost_fps", cell.lostFps());
                    // NAL units dropped because one of their packets was lost (#113)
                    c.put("nal_dropped", cell.nalDropped());
                    // Every frame, or key frames only while the decoder has no room (#85)
                    c.put("decode", cell.isKeyFramesOnly() ? "key frames" : "full");
                    if (cell.knowsLoad()) c.put("load", cell.isKeyFramesOnly()
                            ? cell.keyLoad() : cell.fullLoad());
                    c.put("paused", cell.isPaused());
                    c.put("audio", i == quadAudio);
                }
                list.add(c);
            }
            m.put("cells", list);
        }
        return m;
    }

    // ====================== Screen / zoom ======================

    private static final int REQUEST_STORAGE_FOR_SCREENSHOT = 1;

    private void takeScreenshot() {
        if (isTakingScreenshot) return;

        // Android 6-9 save through the shared Pictures folder: needs the runtime permission (#28)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_FOR_SCREENSHOT);
            return;
        }

        Bitmap bitmap = mSurface.getBitmap();
        if (bitmap == null) {
            Toast.makeText(this, "No video frame available", Toast.LENGTH_SHORT).show();
            return;
        }
        isTakingScreenshot = true;
        // Encoding a full-resolution PNG takes long: do it off the UI thread (#35)
        new Thread(() -> saveScreenshot(bitmap), "screenshot").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_STORAGE_FOR_SCREENSHOT) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            takeScreenshot();
        } else {
            Toast.makeText(this, "Storage permission is needed to save screenshots",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveScreenshot(Bitmap bitmap) {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "Screenshot_" + timeStamp + ".png";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        runOnUiThread(() -> isTakingScreenshot = false);
    }

    private void updateResolution(int width, int height) {
        if (width < 64 || height < 64) return;
        Log.d(TAG, "Resolution update: " + width + "x" + height);
        runOnUiThread(() -> {
            mVideoWidth = width;
            mVideoHeight = height;
            fitVideo();
        });
    }

    /**
     * Size the video view to fit inside the screen with the stream's aspect ratio
     * (letterbox or pillarbox). Re-run when the container size changes: rotation,
     * multi-window, and Android 16 ignoring the fixed orientation on large screens.
     */
    private void fitVideo() {
        if (mVideoWidth <= 0 || mVideoHeight <= 0) return;
        View box = (View) mSurface.getParent();
        int boxW = box.getWidth();
        int boxH = box.getHeight();
        if (boxW == 0 || boxH == 0) {
            boxW = getResources().getDisplayMetrics().widthPixels;
            boxH = getResources().getDisplayMetrics().heightPixels;
        }
        int[] size = fitInside(mVideoWidth, mVideoHeight, boxW, boxH);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size[0], size[1]);
        params.gravity = Gravity.CENTER;
        mSurface.setLayoutParams(params);
        resetZoom();
    }

    /** Largest size with the video's aspect ratio that fits inside the box (#17). */
    static int[] fitInside(int videoW, int videoH, int boxW, int boxH) {
        double scale = Math.min((double) boxW / videoW, (double) boxH / videoH);
        int w = (int) Math.round(videoW * scale);
        int h = (int) Math.round(videoH * scale);
        return new int[] {Math.min(w, boxW), Math.min(h, boxH)};
    }

    private void applyZoomTransform() {
        Matrix matrix = new Matrix();
        float cx = mSurface.getWidth() / 2f;
        float cy = mSurface.getHeight() / 2f;
        matrix.postScale(mZoomScale, mZoomScale, cx, cy);
        matrix.postTranslate(mPanX, mPanY);
        mSurface.setTransform(matrix);
    }

    private void clampPan() {
        float maxX = (mSurface.getWidth() * (mZoomScale - 1)) / 2f;
        float maxY = (mSurface.getHeight() * (mZoomScale - 1)) / 2f;
        mPanX = Math.max(-maxX, Math.min(maxX, mPanX));
        mPanY = Math.max(-maxY, Math.min(maxY, mPanY));
    }

    private void resetZoom() {
        mZoomScale = ZOOM_MIN;
        mPanX = 0f;
        mPanY = 0f;
        applyZoomTransform();
    }

    private void replaceSurface(Surface next) {
        Surface prev = mVideoSurface;
        mVideoSurface = next;
        videoDecoder.setSurface(next);
        if (prev != null) {
            try { prev.release(); } catch (Exception e) {
                Log.e(TAG, "Error releasing Surface", e);
            }
        }
    }

    private int dp(float dp) {
        return (int) (dp * mDensity + 0.5f);
    }

    // ====================== Video decode thread ======================

    private void startListener() {
        // Stop the previous pool first (#23): stopExecutor() bumps listenerGen itself, so the
        // generation for the new threads must be taken after it
        stopExecutor();
        final int gen = listenerGen;
        // The single-view stream gets its own audio route (nothing from Quad leaks in)
        rtspClient.setAudioRoute(audioManager.newRoute());
        executor = Executors.newFixedThreadPool(2);

        // Video decode thread
        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-video");
            while (gen == listenerGen) {
                try {
                    Frame buffer = nalQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (buffer != null) {
                        if (videoDecoder.decodeFrame(buffer, codecH265, 0)) {
                            createDecoder();
                            // The frame that triggered creation (often the SPS) goes in too
                            videoDecoder.decodeFrame(buffer, codecH265, 0);
                        }
                        framePool.recycle(buffer);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Video decode error: " + e.getMessage());
                }
            }
        });

        // Audio playback thread
        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-audio");
            while (gen == listenerGen && !Thread.currentThread().isInterrupted()) {
                audioManager.playPcmFrame();
            }
        });
    }

    // ====================== Lifecycle ======================

    @Override
    protected void onPause() {
        super.onPause();
        mResumed = false;
        menuManager.onPauseDismissBrowser();
        // The UDP view is not a saved mode: after a resume the slot or grid comes back
        leaveUdpView();
        quadContainer.removeCallbacks(quadStatsTick);
        stopStreams();
    }

    /**
     * Single view over UDP (#128, like the multi-view cells in #125): packets arrive but no
     * frame is decoded for UDP_STALL_SECONDS — a lossy UDP path keeps breaking the key
     * frames. With Auto the session then goes over to TCP. Checked once a second.
     */
    private static final int UDP_STALL_SECONDS = 8;
    private int singleStallSeconds;
    private int lastSingleFrames;
    private long lastSingleBytes;
    private final Runnable singleUdpCheck = new Runnable() {
        @Override
        public void run() {
            if (!mResumed) return;
            RtspClient client = rtspClient;
            int frames = videoDecoder.framesOut();
            long bytes = client != null ? client.getStreamBytes() : 0;
            boolean stalled = !quadEnabled && !udpViewShown && client != null
                    && client.playsOverUdp() && "connected".equals(singleState)
                    && bytes - lastSingleBytes > 10_000 && frames == lastSingleFrames;
            lastSingleFrames = frames;
            lastSingleBytes = bytes;
            singleStallSeconds = stalled ? singleStallSeconds + 1 : 0;
            if (singleStallSeconds >= UDP_STALL_SECONDS) {
                singleStallSeconds = 0;
                client.reportUdpBroken("packets but no decoded frame for " + UDP_STALL_SECONDS
                        + " s");
            }
            mSurface.postDelayed(this, 1000);
        }
    };

    private void stopStreams() {
        mSurface.removeCallbacks(singleUdpCheck);
        // The single view's stream ends here: it is no longer "connected" for the debug page
        singleState = "disconnected";
        if (quadCells != null) {
            for (QuadCell cell : quadCells) {
                if (cell != null) cell.stop();
            }
            quadCells = null;
        }

        stopQuadAudio();
        listenerGen++;
        if (rtspClient != null) {
            rtspClient.stop();
        }
        // Disabled first: the decode thread can no longer create a decoder after this
        videoDecoder.setEnabled(false);
        // Same for audio: a worker still running cannot start playback again. Nothing to wait
        // for on the UI thread (#32: this used to block it for seconds)
        audioManager.setEnabled(false);
        stopExecutor();
        nalQueue.clear();
    }

    private void stopExecutor() {
        // Invalidate the loops first: shutdownNow() alone left the audio loop spinning on
        // InterruptedException at 100% CPU (seen on the device after entering Quad)
        listenerGen++;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DebugServer.get().clearSource(debugSource);
        audioManager.shutdown();
    }

    /** A link shared while the app is open arrives here; onResume() then applies it. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Android 10+ may deliver it to the resumed activity without onPause()/onResume():
        // apply the shared link now
        if (mResumed) {
            leaveUdpView();
            stopStreams();
            startStreams();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        startStreams();
        updateDebugUrls();
    }

    private boolean mResumed;

    private void startStreams() {
        singleStallSeconds = 0;
        mSurface.removeCallbacks(singleUdpCheck);
        mSurface.postDelayed(singleUdpCheck, 1000);
        loadSettings();
        videoDecoder.setEnabled(true);
        audioManager.setEnabled(true);
        if (quadEnabled) {
            mSurface.setVisibility(View.GONE);
            if (quadCells != null) {
                for (QuadCell cell : quadCells) {
                    if (cell != null) cell.stop();
                }
                quadCells = null;
            }
            // The slots may have changed while paused, and with them the grid size (#13)
            buildQuadGrid(quadGridSize(mHosts, DEFAULT_URL));
            quadContainer.setVisibility(View.VISIBLE);
            ensureQuadLaidOut();
            clearQuadViews();
            createQuadCells();
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("quad_enabled", true).apply();
        } else {
            // The views may still be in Quad layout from before the pause
            quadContainer.setVisibility(View.GONE);
            mSurface.setVisibility(View.VISIBLE);
            boolean configured = mHost != null && !mHost.isEmpty() && !mHost.equals(DEFAULT_URL);
            if (configured) {
                rtspClient.configure(mHost, mUserAgent, mTransports[mActive]);
                startListener();
                rtspClient.start();
            }
        }
    }

    // ====================== Key events ======================

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "Key pressed: " + keyCode);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (menuManager.isPopupShowing()) {
                    menuManager.dismissPopup();
                } else {
                    menuManager.showMenu(findViewById(R.id.decoder), quadEnabled, mActive,
                            mVersion, BuildConfig.GIT_HASH, mHosts);
                }
                return true;
            // Back is not intercepted here (#30): the menu is a focusable PopupWindow and the
            // dialogs are Dialogs, which handle back themselves, also as predictive back
            // gestures (OnBackInvokedDispatcher, Android 13+). Without a popup, back keeps its
            // default action.
            case KeyEvent.KEYCODE_ESCAPE:
                if (menuManager.isPopupShowing()) {
                    menuManager.dismissPopup();
                    return true;
                }
                return super.onKeyDown(keyCode, event);
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // QuadCell class — extracted to QuadCell.java

}
