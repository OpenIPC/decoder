/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * Decoder.java — main activity for H.264/H.265 hardware video decoding
 *
 */

package com.openipc.decoder;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

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
    // A SurfaceView, like the grid cells (#61), not a TextureView (#76): the decoder renders
    // to a layer of its own, not through the app's window buffer, whose queueBuffer stalled
    // for seconds and longer with every grid/single switch on Mali TV boxes
    private SurfaceView mSurface;
    /** The SurfaceView is hidden for a moment to get a new surface after MJPEG (#119). */
    private boolean recreatingSurface;
    /** Size of the video view without zoom (fitVideo()), 0 before the first frame. */
    private int mFitWidth;
    private int mFitHeight;
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
    /** S (#108, #153): key frames only, in every view; saved as "slides". */
    /** Read by the single view's decode thread too (Qodo on #155). */
    private volatile boolean slides;
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
    /** Per slot (#201): degrees counter-clockwise, -1 for Auto (from the URL). */
    private final int[] mRotates = new int[CAM_COUNT];
    private int mActive;
    private volatile String mHost;
    private String mVersion;
    // Camera URLs (with passwords) are encrypted at rest (#16)
    private CredentialStore credentials;
    // Stored values that could not be decrypted: kept as they are until the slot is edited
    private final String[] mUnreadable = new String[CAM_COUNT];
    String mUserAgent = "User-Agent: OpenIPC-Decoder/1.0\r\n";
    /** Short checks of the slots not on screen while the debug page is in use (#100). */
    private StreamProbes streamProbes;

    // Thread management
    private volatile int listenerGen;
    private ExecutorService executor;

    // Pinch-to-zoom
    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mGestureDetector;
    // A swipe from the screen edge or a drag is no tap that opens the menu (#213)
    private TapGuard tapGuard;
    /** The system's gesture insets (left, top, right, bottom), Android 10+. */
    private final int[] gestureInsets = new int[4];
    /** Set from a touch's up until the click it posts has run (#213). */
    private boolean clickFromTouch;
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
        streamProbes = new StreamProbes(mUserAgent);
        mDensity = getResources().getDisplayMetrics().density;
        credentials = CredentialStore.create();
        // The decoder list for /stats.json, built off the UI thread (#88)
        CodecInfo.warmUp();

        // --- Create managers ---
        videoDecoder = new MediaCodecManager(new MediaCodecManager.Listener() {
            @Override public void onResolutionChanged(int w, int h) {
                // The decoder turns the picture (#201, Android 6+ as in MediaCodecManager): a
                // quarter turn swaps its sides
                RtspClient c = rtspClient;
                int r = c != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? c.rotate() : 0;
                if (r == 90 || r == 270) updateResolution(h, w);
                else updateResolution(w, h);
            }
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
                boolean jpeg = rtspClient.isJpeg();
                if (jpeg) {
                    // MJPEG has its own decoder or is drawn by the app (#119, #132): the
                    // video decoder must not hold the surface
                    closeDecoder();
                } else {
                    jpegRenderer.release();
                }
                if (!jpeg && jpegRenderer.takeCanvasDrawn()) {
                    // A surface drawn with lockCanvas() cannot take a decoder any more: no
                    // decoder until the UI thread has put a new one in place (Qodo on #131)
                    surfacePending = true;
                    // Cleared once the new surface is there (surfaceCreated)
                    runOnUiThread(Decoder.this::recreateVideoSurface);
                }
                // RtspClient configures the AudioManager itself, on its own audio route
            }
            @Override public void onJitterSample(int avg) {
                menuManager.updateQuality(avg);
                menuManager.applyQualityToActive(quadEnabled, mActive);
            }
            @Override public void onDetail(String text) { showStatusDetail(text); }
        });
        rtspClient.setAudioManager(audioManager);
        menuManager = new MenuManager(this, mDensity);
        menuManager.setListener(this);

        // --- UI setup ---
        mSurface = findViewById(R.id.video_surface);
        mSurface.setKeepScreenOn(true);
        statusText = findViewById(R.id.status_text);
        debugUrls = findViewById(R.id.debug_urls);

        mSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                // The surface belongs to the SurfaceView: used, never released, here
                replaceSurface(holder.getSurface());
                // A new surface: nothing drawn into it with lockCanvas() yet (#132)
                jpegRenderer.takeCanvasDrawn();
                surfacePending = false;
                recreatingSurface = false;
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                // Nothing may render into the surface once this returns: close the decoder,
                // the decode thread makes a new one when the next surface is there
                replaceSurface(null);
                closeDecoder();
                if (recreatingSurface) {
                    // GONE has reached the surface: show the view again for a new one, unless
                    // the grid or the U view has taken the screen meanwhile (as in QuadCell)
                    recreatingSurface = false;
                    mSurface.post(() -> {
                        if (!quadEnabled && !udpViewShown) mSurface.setVisibility(View.VISIBLE);
                    });
                }
            }
        });

        tapGuard = new TapGuard(ViewConfiguration.get(this).getScaledTouchSlop());
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
                if (!tapGuard.wasTap()) return true;
                menuManager.showMenu(menuAnchor, quadEnabled, mActive, mVersion,
                        BuildConfig.GIT_HASH, mHosts);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) { takeScreenshot(); }
        });
        mSurface.setOnTouchListener((v, event) -> {
            // Pan moves the view itself (#76): the detectors get the touch in the parent's
            // coordinates, which do not move with it, or each step would eat the next one
            // (Qodo on #185)
            MotionEvent inParent = MotionEvent.obtain(event);
            inParent.offsetLocation(v.getLeft(), v.getTop());
            mScaleDetector.onTouchEvent(inParent);
            mGestureDetector.onTouchEvent(inParent);
            inParent.recycle();
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            View decor = getWindow().getDecorView();
            decor.setOnApplyWindowInsetsListener((v, insets) -> {
                saveGestureInsets(insets);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Bars that stay shown while the video has the focus (not the transient
                    // ones after a swipe, which the app does not see) go away again after a
                    // moment (#202)
                    v.removeCallbacks(hideBarsRunnable);
                    if (hasWindowFocus() && insets.isVisible(WindowInsets.Type.systemBars())) {
                        v.postDelayed(hideBarsRunnable, BARS_REHIDE_MS);
                    }
                }
                return v.onApplyWindowInsets(insets);
            });
        }
        hideSystemBars();

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
        // Q switches the view only; the playback mode is S's and stays as it is (#153)
        if (leaveUdpView()) {
            startQuad();
            return;
        }
        boolean newState = !quadEnabled;
        if (newState) {
            startQuad();
        } else {
            stopQuad();
            boolean configured = mHost != null && !mHost.isEmpty() && !mHost.equals(DEFAULT_URL);
            if (configured) {
                // A probe of this camera ends first: some take one client only (#100)
                streamProbes.willShow(mHost);
                configureClient();
                startListener();
                rtspClient.start();
            }
        }
    }

    /**
     * S (#108, #153): the playback mode only — every frame, or key frames only (the lowest
     * decoder load) — in whatever view is shown: multi-view, U or a single camera. It never
     * switches the view; Q does that.
     */
    @Override
    public void onToggleSlides() {
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
        // The decoders keep running: no warm-up (Qodo on #149)
        if (!on && quadEnabled) decodePlanner = newPlanner(false);
        // The single view: back to every frame from the next key frame on (#153)
        if (!on) singleWaitKey = true;
        Toast.makeText(this, on ? "Key frames only" : "Full stream", Toast.LENGTH_SHORT).show();
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
        // The report is on the same item (#207): said where it is seen
        Toast.makeText(this, showStats ? "Debug text on \u00b7 hold Debug to save a report"
                : "Debug text off", Toast.LENGTH_SHORT).show();
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
        resetDecoderStuck();
        mSurface.setVisibility(View.VISIBLE);
        return true;
    }

    @Override
    public void onExit() {
        // Not left showing over a finishing activity (#205)
        menuManager.dismissAll();
        finishAndRemoveTask();
    }

    /**
     * Reset (#171): stops every stream, deletes the stored settings, the key of the encrypted
     * camera URLs and what WebUI kept (cookies, HTTP logins), then starts the app in a new
     * process, so nothing kept in memory survives either.
     */
    @Override
    public void onReset() {
        Log.i(TAG, "Reset: clearing all settings");
        // commit(), not apply(): the process ends soon after. If the write fails, nothing
        // else is touched and the app stays as it was, to try again (Qodo on #172)
        if (!getSharedPreferences("settings", MODE_PRIVATE).edit().clear().commit()) {
            Log.w(TAG, "Reset: the settings could not be cleared");
            Toast.makeText(this, "Could not clear the settings, please try again",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (quadEnabled) stopQuad();
        if (rtspClient != null) rtspClient.stop();
        closeDecoder();
        closeAudio();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                KeystoreCipher.deleteKey();
            } catch (Exception e) {
                // The URLs it encrypted are gone already; the next save reuses the key
                Log.w(TAG, "Reset: cannot delete the URL key: " + e.getMessage());
            }
        }
        try {
            android.webkit.WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
            android.webkit.CookieManager cookies = android.webkit.CookieManager.getInstance();
            // Restarted only once the cookies are really gone (Qodo on #172)
            cookies.removeAllCookies(removed -> {
                cookies.flush();
                restartClean();
            });
        } catch (Exception e) {
            // No WebView on this device (#15): nothing stored there either
            restartClean();
        }
    }

    /** Starts the app again in a new process after a reset (#171), through {@link Restart}. */
    private void restartClean() {
        Intent restart = new Intent(this, Restart.class);
        restart.putExtra(Restart.EXTRA_PID, android.os.Process.myPid());
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(restart);
    }

    @Override
    public void onShowSettings() {
        menuManager.showUrlEditor(mActive, mHosts, mTransports, mRotates);
    }

    @Override
    public void onWebUI() { menuManager.startBrowser(mHost); }

    @Override
    public void onSaveSettings() { saveSettings(); }

    // ====================== Video lifecycle ======================

    /** The single view's client for the active slot, with the slot's turn (#201). */
    private void configureClient() {
        rtspClient.configure(mHost, mUserAgent, mTransports[mActive]);
        rtspClient.setSlotTurn(mRotates[mActive]);
    }

    private void createDecoder() {
        videoDecoder.setSurface(mVideoSurface);
        RtspClient c = rtspClient;
        videoDecoder.setRotation(c != null ? c.rotate() : 0);
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
            mRotates[i] = pref.getInt("rotate_" + i, -1);
            // Plain URLs from older versions are re-saved encrypted right away
            if (credentials.needsMigration(stored)) {
                if (migrate == null) migrate = pref.edit();
                migrate.putString("host_" + i, credentials.seal(stored));
            }
        }
        if (migrate != null) migrate.apply();

        Intent intent = getIntent();
        String link = intent.getStringExtra(Intent.EXTRA_TEXT);
        // A report shared back to the app (#207) is no camera link (Qodo on #208)
        if (link != null && link.startsWith(DebugServer.REPORT_TITLE)) {
            Log.i(TAG, "Shared text is a report, not a link: ignored");
            intent.removeExtra(Intent.EXTRA_TEXT);
            link = null;
        }
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
            edit.putInt("rotate_" + i, mRotates[i]);
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

        // A probe of this camera ends first: some take one client only (#100)
        streamProbes.willShow(mHost);
        configureClient();
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
        String videoCodec = rtspClient != null && rtspClient.isJpeg() ? "MJPEG"
                : codecH265 ? "H.265" : "H.264";
        String aCodec;
        switch (audioCodec) {
            case AudioConfig.AAC:  aCodec = " • AAC"; break;
            case AudioConfig.OPUS: aCodec = " • Opus"; break;
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

    /**
     * What a flowufo:// slot waits for (#187), in place of the plain status; posted after the
     * status it belongs to, so it wins. Not shown once the stream plays.
     */
    private void showStatusDetail(String text) {
        runOnUiThread(() -> {
            if (statusText == null || "connected".equals(singleState)) return;
            statusText.setText(text);
            statusText.setVisibility(View.VISIBLE);
        });
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
        // Every cell gets its own session: a probe would be a second client of its camera
        streamProbes.stopAll();
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
        resetDecoderStuck();
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
                quadViews[idx].setOnClickListener(cv -> {
                    if (!touchTapOrKey()) return;
                    menuManager.showMenu(findViewById(R.id.decoder), quadEnabled, mActive, mVersion,
                            BuildConfig.GIT_HASH, mHosts);
                });
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
        hint.setText(udpHintText(NetInfo.udpAddresses(), UDP_RTP_PORT));
        hint.setTextColor(Color.GRAY);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(12), dp(12), dp(12), dp(12));
        hint.setBackgroundColor(Color.BLACK);
        // Like the camera cells and the video view: a tap opens the menu
        hint.setOnClickListener(v -> {
            if (!touchTapOrKey()) return;
            menuManager.showMenu(findViewById(R.id.decoder),
                    quadEnabled, mActive, mVersion, BuildConfig.GIT_HASH, mHosts);
        });
        return hint;
    }

    /** Keeps the addresses in the U cell's hint current (DHCP, cable, Wi-Fi), once a second. */
    private void refreshUdpHint() {
        SurfaceView v = quadViews[U_CELL];
        if (v == null) return;
        View overlay = ((View) v.getParent()).findViewWithTag("quad_overlay_" + U_CELL);
        if (!(overlay instanceof TextView) || overlay.getVisibility() != View.VISIBLE) return;
        String text = udpHintText(NetInfo.udpAddresses(), UDP_RTP_PORT);
        TextView hint = (TextView) overlay;
        if (!text.contentEquals(hint.getText())) hint.setText(text);
    }

    /**
     * Hint of the U cell: every usable IPv4 address of the device, one per line (#87). The
     * addresses carry the port, so no separate port line.
     */
    static String udpHintText(List<String> ips, int port) {
        StringBuilder target = new StringBuilder();
        for (String ip : ips) target.append('\n').append(ip).append(':').append(port);
        if (target.length() == 0) target.append("\nthis device:").append(port);
        return "Waiting for a stream\u2026\n\nSend the camera's RTP/UDP output to\n" + target;
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
            quadCells[i].setSlotTurn(i < CAM_COUNT ? mRotates[i] : -1);
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
            ((TextView) overlay).setText(udpHintText(NetInfo.udpAddresses(), UDP_RTP_PORT));
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
            checkDecoderStuck(cells);
            planDecoding(cells, now);
            updateDebugUrls();
            refreshUdpHint();
            quadContainer.postDelayed(this, 1000);
        }
    };

    // ====================== The device's decoder stands still (#145) ======================

    /** Time with frames arriving in several cells and none decoded: the device is stuck. */
    static final long STUCK_MS = 20_000;
    /** Since when the condition holds, or -1 (monotonic ms; Qodo on #147: time, not ticks). */
    private long stuckSinceMs = -1;
    private TextView stuckBanner;

    /**
     * One observation of the detector: since when at least two cells have received whole
     * frames and no cell at all decoded one (#145), or -1 when that does not hold now.
     */
    static long stuckSince(long sinceMs, long nowMs, int receiving, boolean anyOutput) {
        if (receiving < 2 || anyOutput) return -1;
        return sinceMs >= 0 ? sinceMs : nowMs;
    }

    static boolean isStuck(long sinceMs, long nowMs) {
        return sinceMs >= 0 && nowMs - sinceMs >= STUCK_MS;
    }

    private boolean decoderStuck() {
        return isStuck(stuckSinceMs, System.nanoTime() / 1_000_000);
    }

    /**
     * Seen on the AG-216: every cell receives but no decoder gives a frame, and only a reboot
     * of the device helps (#145). Said on screen instead of black cells and "0.0 fps".
     */
    private void checkDecoderStuck(QuadCell[] cells) {
        int receiving = 0;
        boolean anyOutput = false;
        for (QuadCell c : cells) {
            if (c == null) continue;
            // Output of any cell, a lossy one too, shows the decoder works (Qodo on #147)
            // Only H.264/H.265 cells: MJPEG may be drawn without the video decoder at all
            if (!c.usesVideoDecoder()) continue;
            if (c.outFps() > 0) anyOutput = true;
            if (c.isReceiving()) receiving++;
        }
        long now = System.nanoTime() / 1_000_000;
        stuckSinceMs = stuckSince(stuckSinceMs, now, receiving, anyOutput);
        showDecoderStuck(isStuck(stuckSinceMs, now));
    }

    /** Leaving or rebuilding the grid: a fresh count, no banner (Qodo on #147). */
    private void resetDecoderStuck() {
        stuckSinceMs = -1;
        showDecoderStuck(false);
    }

    private void showDecoderStuck(boolean on) {
        if (!on) {
            if (stuckBanner != null) stuckBanner.setVisibility(View.GONE);
            return;
        }
        if (stuckBanner == null) {
            stuckBanner = new TextView(this);
            stuckBanner.setText("The video decoder of this device does not respond.\n"
                    + "Frames arrive, none is decoded. Restart the device.");
            stuckBanner.setTextColor(Color.WHITE);
            stuckBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            stuckBanner.setGravity(Gravity.CENTER);
            stuckBanner.setBackgroundColor(0xCC000000);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            stuckBanner.setPadding(pad, pad, pad, pad);
            ((FrameLayout) findViewById(R.id.decoder)).addView(stuckBanner, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
        }
        if (stuckBanner.getVisibility() != View.VISIBLE) {
            Log.w(TAG, "No cell decodes although frames arrive: the device's decoder is stuck");
        }
        stuckBanner.setVisibility(View.VISIBLE);
        stuckBanner.bringToFront();
    }

    private void startStatsTick() {
        quadContainer.removeCallbacks(quadStatsTick);
        decodePlanner = newPlanner(true);
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
    /** One 1080p30 stream: never learnt below that (#126). */
    static final long MIN_CAPACITY = DecodePlanner.fullLoad(1920, 1080, 30);
    private DecodePlanner decodePlanner;

    /** {@code warmup}: the grid's decoders start now, together (#148). */
    private DecodePlanner newPlanner(boolean warmup) {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        if (pref.contains("decoder_capacity")) {
            pref.edit().remove("decoder_capacity").remove("decoder_failed_load").apply();
        }
        long learnt = pref.getLong(PREF_DECODER_CAPACITY, 0);
        long failed = pref.getLong(PREF_DECODER_FAILED, 0);
        long declared = MediaCodecManager.declaredBlocksPerSec;
        DecodePlanner p = learnt > 0 ? new DecodePlanner(learnt, true, failed, MIN_CAPACITY)
                : new DecodePlanner(declared > 0 ? declared : DEFAULT_CAPACITY, false, failed,
                        MIN_CAPACITY);
        // The decoders start together and lose frames for a moment (#148)
        if (warmup) p.setWarmup(DecodePlanner.WARMUP_MS);
        return p;
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
        // No cell decoded anything this second although some play: the decoder (or the media
        // service) stands still. Nothing to learn from that (#126)
        // Decoder losses (queue overflow, no input buffer) mean overload, not a stall: those
        // seconds are planned as usual (Qodo on #129)
        boolean anyPlaying = false;
        boolean anyOutput = false;
        boolean anyLosses = false;
        for (QuadCell c : cells) {
            if (c == null || !c.isPlaying()) continue;
            anyPlaying = true;
            if (c.outFps() > 0) anyOutput = true;
            if (c.lostFps() > 0) anyLosses = true;
        }
        if (anyPlaying && !anyOutput && !anyLosses && countFullRate(cells) > 0) {
            planner.skipped(now);
            return;
        }
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
                case RESUME:
                    Log.i(TAG, "Quiet for a while: Quad-" + d.index + " plays again");
                    c.resume();
                    // stop() took its audio route; the selected cell gets it back (Qodo on #149)
                    applyQuadAudio();
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

    private static int countFullRate(QuadCell[] cells) {
        int n = 0;
        for (QuadCell c : cells) if (c != null && c.isPlaying() && !c.isKeyFramesOnly()) n++;
        return n;
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
        pc.pinned = c.isSoftwareDecoder();
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
        @Override public byte[] jieliRaw(String which) {
            RtspClient c = rtspClient;
            return c != null ? c.jieliRaw(which) : Jieli.Client.lastRaw(which);
        }
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
            return null;
        }
        List<Snapshots.Cell> cells = ref.get();
        return cells != null ? Snapshots.render(cells, side[0], side[1], labels) : null;
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
        if (udpViewShown || !quadEnabled) {
            // The grid as in multi-view (#100): the camera on screen, the others' checks
            updateProbes();
            int size = probeGridSize();
            long now = SystemClock.elapsedRealtime();
            for (int i = 0; i < size * size; i++) {
                Snapshots.Cell c;
                if (!onScreen(i)) {
                    c = new Snapshots.Cell();
                    c.slot = i == U_CELL ? 0 : i + 1;
                    StreamProbe.Result r = probeResult(i);
                    c.label = probeText(i, now);
                    c.ok = r != null && r.ok;
                } else if (udpViewShown) {
                    c = snapshotCell(U_CELL);
                } else {
                    c = new Snapshots.Cell();
                    c.slot = i + 1;
                    c.live = "connected".equals(singleState)
                            && singleRenderedWithin(SNAPSHOT_LIVE_MS);
                    c.label = statusText != null && statusText.getVisibility() == View.VISIBLE
                            ? statusText.getText().toString() : c.live ? "Playing" : "No new frames";
                    // Copied with the grid cells, off the UI thread (#76)
                    if (c.live) c.view = mSurface;
                }
                c.onScreen = onScreen(i);
                cells.add(c);
            }
            side[0] = size;
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
            c.label = StreamProbes.NOT_CONFIGURED;
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
        // The video decoders and what they can do: instances, adaptive playback (#88)
        List<Map<String, Object>> decoders = CodecInfo.videoDecodersIfReady();
        m.put("decoders", decoders != null ? decoders : "loading");
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
            // Frames arrive in several cells, none decodes: restart the device (#145)
            m.put("decoder_stuck", decoderStuck());
        }
        if (udpViewShown) {
            Map<String, Object> udp = new LinkedHashMap<>();
            udp.put("port", UDP_RTP_PORT);
            udp.put("addresses", NetInfo.udpAddresses());
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
                    && singleRenderedWithin(SNAPSHOT_LIVE_MS));
            single.put("status", statusText != null && statusText.getVisibility() == View.VISIBLE
                    ? statusText.getText().toString() : singleState);
            if (mVideoWidth > 0) single.put("size", mVideoWidth + "x" + mVideoHeight);
            single.put("codec", rtspClient != null && rtspClient.isJpeg() ? "MJPEG"
                    : codecH265 ? "H.265" : "H.264");
            single.put("audio", audioCodec);
            // Frames shown in the last second and the playback mode of S (#153)
            single.put("out_fps", singleOutFps);
            single.put("decode", slides ? "key frames" : "full");
            // The toy drone's protocol counters (#187)
            Map<String, Object> ufo = rtspClient != null ? rtspClient.flowUfoStats() : null;
            if (ufo != null) single.put("flowufo", ufo);
            Map<String, Object> jl = rtspClient != null ? rtspClient.jieliStats() : null;
            if (jl != null) single.put("jieli", jl);
            Map<String, Object> hb = rtspClient != null ? rtspClient.heartbeatStats() : null;
            if (hb != null) single.put("heartbeat", hb);
            // Turned counter-clockwise, "size" is the turned one (#200, #201)
            int rotate = rtspClient != null ? rtspClient.rotate() : 0;
            if (rotate != 0) single.put("rotate", rotate);
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
                    c.put("text", StreamProbes.NOT_CONFIGURED);
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
                    // Slices passed on cut short at a lost piece (some may still be dropped by
                    // a full queue); absent while that is off (#133)
                    int salvaged = cell.nalSalvaged();
                    if (salvaged >= 0) c.put("nal_damaged", salvaged);
                    // Every frame, or key frames only while the decoder has no room (#85)
                    c.put("decode", cell.isKeyFramesOnly() ? "key frames" : "full");
                    if (cell.knowsLoad()) c.put("load", cell.isKeyFramesOnly()
                            ? cell.keyLoad() : cell.fullLoad());
                    c.put("paused", cell.isPaused());
                    c.put("audio", i == quadAudio);
                    Map<String, Object> ufo = cell.flowUfoStats();
                    if (ufo != null) c.put("flowufo", ufo);
                    Map<String, Object> jl = cell.jieliStats();
                    if (jl != null) c.put("jieli", jl);
                    Map<String, Object> hb = cell.heartbeatStats();
                    if (hb != null) c.put("heartbeat", hb);
                    if (cell.rotate() != 0) c.put("rotate", cell.rotate());
                }
                list.add(c);
            }
            m.put("cells", list);
        }
        if (udpViewShown || !quadEnabled) {
            updateProbes();
            m.put("slots", probeSlots());
        }
        return m;
    }

    // ====================== Stream checks in single view (#100) ======================

    /** Single and U view: the debug page is in use, the other slots get short checks. */
    private void updateProbes() {
        String[] urls = new String[CAM_COUNT];
        for (int i = 0; i < CAM_COUNT; i++) {
            urls[i] = mHosts[i] != null && !mHosts[i].isEmpty() && !mHosts[i].equals(DEFAULT_URL)
                    ? mHosts[i] : null;
        }
        streamProbes.update(urls, mTransports, udpViewShown ? null : mHost,
                mResumed && (udpViewShown || !quadEnabled));
    }

    /**
     * The grid of the debug page in single and U view: 3x3 in the U view (it has the U cell),
     * and when the slot on screen is beyond the 2x2 grid, even an empty one (Qodo on #163).
     */
    private int probeGridSize() {
        if (udpViewShown || mActive >= 4) return 3;
        return quadGridSize(mHosts, DEFAULT_URL);
    }

    /** Cell i shows what the TV shows: the active slot, or the U cell in the U view. */
    private boolean onScreen(int i) {
        return udpViewShown ? i == U_CELL : i == mActive;
    }

    private StreamProbe.Result probeResult(int i) {
        return i < CAM_COUNT && mHosts[i] != null
                ? streamProbes.result(i, mHosts[i], mTransports[i]) : null;
    }

    /** What a cell not on screen says: its last check, or why it has none. */
    private String probeText(int i, long nowMs) {
        if (i == U_CELL) return "U \u00b7 not checked in single view";
        String skip = StreamProbes.skipReason(mHosts[i], DEFAULT_URL,
                udpViewShown ? null : mHost);
        if (skip != null) return skip;
        StreamProbe.Result r = probeResult(i);
        if (r != null) return r.text(nowMs) + (streamProbes.isRunning(i) ? " \u00b7 checking" : "");
        return streamProbes.isRunning(i) ? "Checking stream\u2026" : "Waiting for a stream check";
    }

    /**
     * Transport of a grid cell in single and U view (Qodo on #163): in use for the camera on
     * screen, otherwise what a check of it goes over.
     */
    private String slotTransport(int i, String url) {
        if (i == U_CELL) return "UDP push";
        if (onScreen(i)) {
            return Transport.label(rtspClient != null ? rtspClient.transportInUse() : null,
                    mTransports[i]);
        }
        if (RtpListener.Url.matches(url)) return "UDP listen";
        if (HttpMjpeg.matches(url)) return "HTTP";
        if (FlowUfo.Url.matches(url)) return "FLOW-UFO UDP";
        if (Jieli.Url.matches(url)) return "Jieli RTP";
        Transport.Mode m = StreamProbe.probeMode(mTransports[i]);
        return "check over " + (m == Transport.Mode.MULTICAST ? "multicast" : m.name());
    }

    /**
     * Every cell of the grid for /stats.json in single and U view (#100): the camera on screen
     * as it plays, the others with the result of their last check. UI thread only.
     */
    private List<Object> probeSlots() {
        int size = probeGridSize();
        long now = SystemClock.elapsedRealtime();
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < size * size; i++) {
            boolean u = i == U_CELL;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("slot", u ? "U" : i + 1);
            c.put("on_screen", onScreen(i));
            String url = u ? UDP_CELL_URL : mHosts[i];
            if (u || (url != null && !url.isEmpty() && !url.equals(DEFAULT_URL))) {
                c.put("url", u ? UDP_CELL_URL : DebugServer.redact(url));
                c.put("transport", slotTransport(i, url));
                c.put("transport_mode", u ? "UDP" : mTransports[i] != null
                        ? mTransports[i].name() : null);
            }
            if (onScreen(i)) {
                if (udpViewShown) {
                    QuadCell[] cells = quadCells;
                    QuadCell cell = cells != null ? cells[U_CELL] : null;
                    TextView v = quadStats[U_CELL];
                    c.put("video", cell != null && cell.showsVideo(SNAPSHOT_LIVE_MS));
                    c.put("text", v != null ? v.getText().toString() : null);
                } else {
                    boolean video = mResumed && "connected".equals(singleState)
                            && singleRenderedWithin(SNAPSHOT_LIVE_MS);
                    c.put("video", video);
                    c.put("text", video ? (mVideoWidth > 0 ? mVideoWidth + "\u00d7" + mVideoHeight + " " : "")
                            + (rtspClient != null && rtspClient.isJpeg() ? "MJPEG"
                            : codecH265 ? "H.265" : "H.264") + " \u00b7 " + singleOutFps + " fps"
                            : statusText != null && statusText.getVisibility() == View.VISIBLE
                            ? statusText.getText().toString() : singleState);
                }
            } else {
                c.put("video", false);
                c.put("text", probeText(i, now));
                StreamProbe.Result r = probeResult(i);
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("state", r != null ? (r.ok ? "ok" : "error")
                        : streamProbes.isRunning(i) ? "checking" : u || StreamProbes.skipReason(
                        mHosts[i], DEFAULT_URL, udpViewShown ? null : mHost) != null
                        ? "not checked" : "waiting");
                if (r != null) {
                    p.put("checked_s_ago", (now - r.atMs) / 1000);
                    p.put("summary", r.summary);
                    if (r.codec != null) p.put("codec", r.codec);
                    if (r.width > 0) p.put("size", r.width + "x" + r.height);
                    p.put("fps", r.fps);
                    p.put("kbps", r.kbps);
                    p.put("key_frames", r.keyFrames);
                }
                c.put("probe", p);
            }
            list.add(c);
        }
        return list;
    }

    // ====================== Screen / zoom ======================

    private static final int REQUEST_STORAGE_FOR_SCREENSHOT = 1;
    /** Largest screenshot: 4K, 33 MB as ARGB; a larger stream is scaled down to it. */
    private static final int SCREENSHOT_MAX_PIXELS = 3840 * 2160;

    private void takeScreenshot() {
        if (isTakingScreenshot) return;

        // A SurfaceView has no getBitmap() (#76): PixelCopy, Android 7+. Checked before the
        // permission, which would be asked for nothing here (Qodo on #185)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Toast.makeText(this, "Screenshots need Android 7 or newer", Toast.LENGTH_SHORT).show();
            return;
        }

        // Android 7-9 save through the shared Pictures folder: needs the runtime permission (#28)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_FOR_SCREENSHOT);
            return;
        }

        if (mVideoWidth <= 0 || mVideoHeight <= 0
                || !mSurface.getHolder().getSurface().isValid()) {
            Toast.makeText(this, "No video frame available", Toast.LENGTH_SHORT).show();
            return;
        }
        // At the stream's own size, not the view's, but at most SCREENSHOT_MAX_PIXELS: the
        // resolution comes from the camera (Qodo on #185)
        double fit = Math.min(1, Math.sqrt((double) SCREENSHOT_MAX_PIXELS
                / ((double) mVideoWidth * mVideoHeight)));
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(Math.max(1, (int) (mVideoWidth * fit)),
                    Math.max(1, (int) (mVideoHeight * fit)), Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "No memory for a screenshot of " + mVideoWidth + "x" + mVideoHeight);
            Toast.makeText(this, "Failed to save screenshot", Toast.LENGTH_SHORT).show();
            return;
        }
        isTakingScreenshot = true;
        // Copied on the snapshot thread, the UI thread does not wait for it
        boolean started = Snapshots.copy(mSurface, bitmap, result -> {
            if (result == PixelCopy.SUCCESS) {
                // Encoding a full-resolution PNG takes long: on a thread of its own (#35)
                new Thread(() -> saveScreenshot(bitmap), "screenshot").start();
            } else {
                Log.d(TAG, "Screenshot copy failed: " + result);
                bitmap.recycle();
                runOnUiThread(() -> {
                    isTakingScreenshot = false;
                    Toast.makeText(this, "No video frame available", Toast.LENGTH_SHORT).show();
                });
            }
        });
        if (!started) {
            bitmap.recycle();
            isTakingScreenshot = false;
            Toast.makeText(this, "No video frame available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_STORAGE_FOR_REPORT) {
            // Refused: saved all the same, in the app's own folder (#207)
            startSavingReport();
            return;
        }
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

    // ====================== Save report (#207) ======================

    private static final int REQUEST_STORAGE_FOR_REPORT = 2;
    /** Shared as text when there is no file to share (Android 9 and older): Binder's limit. */
    private static final int REPORT_SHARE_TEXT_MAX = 50_000;
    private boolean isSavingReport;

    /** Long press on Debug: the debug page's /report.txt as a file, then the Share sheet. */
    @Override
    public void onSaveReport() {
        if (isSavingReport) return;
        // Android 6-9 write to the shared Download folder: the runtime permission, like
        // screenshots (#28); Android 10+ go through MediaStore with none
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_FOR_REPORT);
            return;
        }
        startSavingReport();
    }

    private void startSavingReport() {
        if (isSavingReport) return;
        isSavingReport = true;
        // The state is read on the UI thread, up to a timeout: the report is made beside it
        new Thread(this::saveReport, "report").start();
    }

    /** On the report thread. */
    private void saveReport() {
        Date now = new Date();
        String name = DebugServer.reportName(now);
        String text;
        Uri uri = null;
        String where;
        try {
            text = DebugServer.get().reportText(now);
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                uri = writeReportToDownloads(name, data);
                where = Environment.DIRECTORY_DOWNLOADS + "/OpenIPC/" + name;
            } else {
                where = writeReportFile(name, data).getAbsolutePath();
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Could not save the report", e);
            runOnUiThread(() -> {
                isSavingReport = false;
                Toast.makeText(this, "Failed to save the report", Toast.LENGTH_LONG).show();
            });
            return;
        }
        Log.i(TAG, "Report saved: " + where);
        final Uri shared = uri;
        final String report = text;
        final String at = where;
        runOnUiThread(() -> {
            isSavingReport = false;
            Toast.makeText(this, "Report saved: " + at, Toast.LENGTH_LONG).show();
            if (!isFinishing()) shareReport(name, shared, report, at);
        });
    }

    /** Android 10+: Download/OpenIPC through MediaStore, no permission. */
    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private Uri writeReportToDownloads(String name, byte[] data) throws IOException {
        android.content.ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/OpenIPC");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore did not create " + name);
        try {
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) throw new IOException("MediaStore gave no stream for " + name);
                os.write(data);
            }
            // Published only when the pending flag is really cleared (Qodo on #208)
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) {
                throw new IOException("MediaStore did not publish " + name);
            }
        } catch (IOException | RuntimeException e) {
            try {
                resolver.delete(uri, null, null);
            } catch (RuntimeException ignored) {
                // Nothing more to clean up
            }
            throw e;
        }
        return uri;
    }

    /**
     * Android 9 and older: Download/OpenIPC with the storage permission; without it (refused,
     * no shared storage) the app's own folder, which needs none.
     */
    @SuppressWarnings("deprecation")
    private File writeReportFile(String name, byte[] data) throws IOException {
        File shared = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "OpenIPC");
        try {
            return writeFile(shared, name, data);
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Report: " + shared + " not writable (" + e.getMessage()
                    + "), saving in the app's folder");
        }
        File own = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (own == null) own = new File(getFilesDir(), "reports");
        return writeFile(own, name, data);
    }

    /** Never over an earlier file: report-…-2.txt and so on if the name is taken (Qodo on #208). */
    private static File writeFile(File dir, String name, byte[] data) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        int dot = name.lastIndexOf('.');
        File f = new File(dir, name);
        for (int n = 2; !f.createNewFile(); n++) {
            if (n > 99) throw new IOException("no free name for " + name + " in " + dir);
            f = new File(dir, name.substring(0, dot) + "-" + n + name.substring(dot));
        }
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(data);
        }
        return f;
    }

    /**
     * The system Share sheet, to send the report later (Telegram, mail). The file itself on
     * Android 10+; older versions have no FileProvider here, so the text, cut to a size an
     * intent can carry, with where the whole file is. Skipped where nothing can take it, as
     * on most TV boxes.
     */
    private void shareReport(String name, Uri uri, String text, String where) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "OpenIPC Decoder " + name);
        if (uri != null) {
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newRawUri(name, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            String body = text.length() <= REPORT_SHARE_TEXT_MAX ? text
                    : text.substring(0, REPORT_SHARE_TEXT_MAX)
                            + "\n... cut here; the whole report is in " + where + "\n";
            send.putExtra(Intent.EXTRA_TEXT, body);
        }
        try {
            // Not to this app itself: it takes shared text as a camera link (Qodo on #208)
            boolean target = false;
            for (android.content.pm.ResolveInfo ri
                    : getPackageManager().queryIntentActivities(send, 0)) {
                if (ri.activityInfo != null
                        && !getPackageName().equals(ri.activityInfo.packageName)) {
                    target = true;
                    break;
                }
            }
            if (!target) {
                Log.i(TAG, "Report: no app to share it with");
                return;
            }
            Intent chooser = Intent.createChooser(send, "Share report");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                        new android.content.ComponentName[] {
                                new android.content.ComponentName(this, Decoder.class)});
            }
            startActivity(chooser);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Report: cannot share it: " + e.getMessage());
        }
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
        mFitWidth = size[0];
        mFitHeight = size[1];
        // The surface keeps this size when zoom makes the view larger: SurfaceFlinger scales
        // it, and a frame drawn with lockCanvas() (MJPEG) is not up to 5x the screen (#76)
        mSurface.getHolder().setFixedSize(size[0], size[1]);
        // Lays the view out at this size
        resetZoom();
    }

    /** Largest size with the video's aspect ratio that fits inside the box (#17). */
    static int[] fitInside(int videoW, int videoH, int boxW, int boxH) {
        double scale = Math.min((double) boxW / videoW, (double) boxH / videoH);
        int w = (int) Math.round(videoW * scale);
        int h = (int) Math.round(videoH * scale);
        return new int[] {Math.min(w, boxW), Math.min(h, boxH)};
    }

    /**
     * Zoom and pan of the single view. A SurfaceView has no setTransform() (#76), and its
     * surface follows setScaleX() / setTranslationX() only from Android 7 on: the view itself
     * is laid out mZoomScale times the fitted size, centred, and moved by the pan, which places
     * the surface the same way on every Android version.
     */
    private void applyZoomTransform() {
        if (mFitWidth <= 0 || mFitHeight <= 0) return;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.round(mFitWidth * mZoomScale), Math.round(mFitHeight * mZoomScale));
        params.gravity = Gravity.CENTER;
        // With CENTER the left and top margins move the view off the centre
        params.leftMargin = Math.round(mPanX);
        params.topMargin = Math.round(mPanY);
        mSurface.setLayoutParams(params);
    }

    private void clampPan() {
        // Measured on the fitted size: the view's own size grows with the zoom now (#76)
        float maxX = (mFitWidth * (mZoomScale - 1)) / 2f;
        float maxY = (mFitHeight * (mZoomScale - 1)) / 2f;
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
        // The MJPEG decoder renders into the old surface: let go of it first (#132)
        jpegRenderer.release();
        mVideoSurface = next;
        videoDecoder.setSurface(next);
    }

    /** The system's gesture insets, where a swipe is the system's and no tap (#213). */
    @TargetApi(Build.VERSION_CODES.Q)
    @SuppressWarnings("deprecation")
    private void saveGestureInsets(WindowInsets insets) {
        Insets g = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? insets.getInsets(WindowInsets.Type.systemGestures())
                : insets.getSystemGestureInsets();
        gestureInsets[0] = g.left;
        gestureInsets[1] = g.top;
        gestureInsets[2] = g.right;
        gestureInsets[3] = g.bottom;
    }

    /**
     * Every touch of the window passes here before the views get it: TapGuard follows the
     * sequence, so the menu opens on a real tap only, not on a swipe from the edge that shows
     * the bars (#213) or a drag. Keys (remote, D-pad) do not come here.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (tapGuard == null) return super.dispatchTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                View decor = getWindow().getDecorView();
                tapGuard.down(ev.getX(), ev.getY(), decor.getWidth(), decor.getHeight(),
                        TapGuard.edgeZone(gestureInsets, mDensity));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                tapGuard.moveTo(ev.getX(), ev.getY());
                break;
            case MotionEvent.ACTION_UP:
                tapGuard.up(ev.getX(), ev.getY());
                clickFromTouch = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                tapGuard.secondFinger();
                break;
            case MotionEvent.ACTION_CANCEL:
                tapGuard.cancel();
                break;
            default:
                break;
        }
        boolean handled = super.dispatchTouchEvent(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
            // A view clicks after the up, posted: this runs after that click
            getWindow().getDecorView().post(() -> clickFromTouch = false);
        }
        return handled;
    }

    /**
     * A click on a cell or the U hint opens the menu: from a key (remote, D-pad) or
     * accessibility always, from a touch only when it was a real tap (#213).
     */
    private boolean touchTapOrKey() {
        return !clickFromTouch || tapGuard.wasTap();
    }

    private int dp(float dp) {
        return (int) (dp * mDensity + 0.5f);
    }

    // ====================== MJPEG (#119) ======================

    /** MJPEG frames onto the video surface: hardware decoder or BitmapFactory (#119, #132). */
    private final JpegRenderer jpegRenderer = new JpegRenderer();
    /** A new video surface is being made after MJPEG: no decoder until it is there. */
    private volatile boolean surfacePending;
    /** JPEG frames drawn, the MJPEG counterpart of the decoder's output count. */
    private volatile int jpegFramesOut;
    private volatile long lastJpegFrameMs;

    /**
     * One JPEG frame of an MJPEG stream into the single view, on the decode thread, drawn over
     * the whole surface; the view's fit transform keeps the aspect ratio, as for video.
     */
    private boolean drawJpeg(Frame frame, int gen) {
        Surface s = mVideoSurface;
        RtspClient client = rtspClient;
        int rotate = client != null ? client.rotate() : 0;
        int[] size = jpegRenderer.render(frame, s, rotate,
                () -> gen == listenerGen && s == mVideoSurface);
        if (size != null) jpegShown(size[0], size[1]);
        return size != null;
    }

    /** A JPEG frame is on screen (hardware or software path). */
    private void jpegShown(int w, int h) {
        jpegFramesOut++;
        boolean first = lastJpegFrameMs == 0;
        lastJpegFrameMs = SystemClock.elapsedRealtime();
        if (first || w != mVideoWidth || h != mVideoHeight) {
            runOnUiThread(() -> {
                updateResolution(w, h);
                removeClearOverlay();
            });
        }
    }

    /**
     * A new surface, for a decoder after MJPEG was drawn with lockCanvas() (#119), UI thread:
     * the SurfaceView is hidden and shown again, as a grid cell does (QuadCell, #139). GONE has
     * to reach the surface first, so VISIBLE follows in surfaceDestroyed(), and surfacePending
     * ends in surfaceCreated(). Hidden already, or no surface now: the next one is new anyway.
     */
    private void recreateVideoSurface() {
        // Hidden for a new surface already: surfaceCreated() ends the wait
        if (recreatingSurface && mSurface != null && mSurface.getVisibility() != View.VISIBLE) {
            return;
        }
        if (mSurface == null || mSurface.getVisibility() != View.VISIBLE
                || !mSurface.getHolder().getSurface().isValid()) {
            surfacePending = false;
            return;
        }
        recreatingSurface = true;
        mSurface.setVisibility(View.GONE);
    }

    /** The single view showed a frame within the last {@code ms} (video or MJPEG). */
    private boolean singleRenderedWithin(long ms) {
        return videoDecoder.renderedWithin(ms)
                || (rtspClient != null && rtspClient.isJpeg() && lastJpegFrameMs > 0
                && SystemClock.elapsedRealtime() - lastJpegFrameMs < ms);
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
                    if (buffer != null && rtspClient.isJpeg()) {
                        // Every JPEG frame is whole: when drawing falls behind (a turned frame
                        // is drawn in software, #187), show the newest and drop the older ones
                        // instead of letting the delay grow
                        Frame newer;
                        while ((newer = nalQueue.poll()) != null) {
                            framePool.recycle(buffer);
                            buffer = newer;
                        }
                        // S: MJPEG has no key frames, one frame per second instead (#153); the
                        // second counts from a frame really shown (Qodo on #155)
                        long now = System.nanoTime() / 1_000_000;
                        if ((!slides || now - lastSlideJpegMs >= QuadCell.JPEG_REDUCED_MS)
                                && drawJpeg(buffer, gen)) {
                            lastSlideJpegMs = now;
                        }
                        framePool.recycle(buffer);
                    } else if (buffer != null && surfacePending) {
                        // Waiting for a fresh surface after MJPEG; the next key frame follows
                        framePool.recycle(buffer);
                    } else if (buffer != null && skipForKeyFrames(buffer)) {
                        // After surfacePending: a key frame dropped there must not end the wait
                        // for one (Qodo on #155)
                        framePool.recycle(buffer);
                    } else if (buffer != null) {
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

    /** S in the single view: the last MJPEG frame drawn (#153). */
    private long lastSlideJpegMs;
    /** Back from key frames only: P frames wait for the next key frame (#153). */
    private volatile boolean singleWaitKey;

    /**
     * Key frames only in the single view (S, #153): parameter sets and key frames go to the
     * decoder, the rest is dropped; after S is turned off, until the next key frame.
     */
    private boolean skipForKeyFrames(Frame buffer) {
        if (!slides && !singleWaitKey) return false;
        if (buffer.length() < 5) return true;
        int type = NalAssembler.fragment(buffer.data()[4], codecH265);
        if (!QuadCell.isKeyNal(type, codecH265)) return true;
        if (!slides && QuadCell.isKeyFrameNal(type, codecH265)) singleWaitKey = false;
        return false;
    }

    // ====================== Lifecycle ======================

    @Override
    protected void onPause() {
        super.onPause();
        mResumed = false;
        streamProbes.stopAll();
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
    private volatile int singleOutFps;
    private long lastSingleBytes;
    private final Runnable singleUdpCheck = new Runnable() {
        @Override
        public void run() {
            if (!mResumed) return;
            RtspClient client = rtspClient;
            // Drawn MJPEG frames count as output too (Qodo on #131)
            int frames = videoDecoder.framesOut() + jpegFramesOut;
            long bytes = client != null ? client.getStreamBytes() : 0;
            boolean stalled = !quadEnabled && !udpViewShown && client != null
                    && (client.playsOverUdp() || client.listens())
                    && "connected".equals(singleState)
                    && bytes - lastSingleBytes > 10_000 && frames == lastSingleFrames;
            // Frames shown in the last second, for /stats.json (#153)
            singleOutFps = Math.max(0, frames - lastSingleFrames);
            lastSingleFrames = frames;
            lastSingleBytes = bytes;
            singleStallSeconds = stalled ? singleStallSeconds + 1 : 0;
            if (singleStallSeconds >= UDP_STALL_SECONDS) {
                singleStallSeconds = 0;
                // Every key frame loses a piece: decode them damaged rather than not at all;
                // a pushed stream has no TCP to go to (#133)
                if (!nalAssembler.salvaging()) {
                    nalAssembler.setSalvage(true);
                    Log.w(TAG, "No complete key frame over UDP, decoding damaged ones");
                }
                client.reportUdpBroken("packets but no decoded frame for " + UDP_STALL_SECONDS
                        + " s");
            }
            mSurface.postDelayed(this, 1000);
        }
    };

    private void stopStreams() {
        lastJpegFrameMs = 0;
        // The next stream's first MJPEG frame shows at once with S on (Qodo on #155)
        lastSlideJpegMs = 0;
        resetDecoderStuck();
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
        // After the worker's generation is gone: a frame still in flight sees that and can no
        // longer create the JPEG decoder again (Qodo on #135)
        jpegRenderer.release();
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
        // The menu and the dialogs go with the activity, or Android logs them as leaked (#205)
        if (menuManager != null) menuManager.dismissAll();
        getWindow().getDecorView().removeCallbacks(hideBarsRunnable);
        super.onDestroy();
        DebugServer.get().clearSource(debugSource);
        streamProbes.shutdown();
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

    /** How long bars that stay shown over the video are left before they are hidden (#202). */
    private static final long BARS_REHIDE_MS = 3000;
    private final Runnable hideBarsRunnable = this::hideSystemBars;

    /**
     * Full screen over the whole display in every viewing mode (single, grid, U), with no
     * status or navigation bar (#202). A swipe from an edge shows them for a moment only.
     * Android 15 draws apps edge to edge, so bars left shown cover the picture; some systems
     * (an OPPO, #187) bring them back after another window had the focus, so this runs again
     * whenever the activity resumes or gets the focus back: after the menu, a dialog or the
     * WebUI, which keeps the bars and its insets padding (#169, #180).
     */
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                ctrl.hide(WindowInsets.Type.systemBars());
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        hideSystemBars();
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
            resetDecoderStuck();
            mSurface.setVisibility(View.VISIBLE);
            boolean configured = mHost != null && !mHost.isEmpty() && !mHost.equals(DEFAULT_URL);
            if (configured) {
                // A probe of this camera ends first: some take one client only (#100)
                streamProbes.willShow(mHost);
                configureClient();
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
                // A held OK toggles once: a long OK on Debug closes the menu while the key is
                // still down, its repeats must not open it again (#207, Qodo on #208)
                if (event.getRepeatCount() > 0) return true;
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
