/*
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
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
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
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
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    final NalAssembler nalAssembler = new NalAssembler(1024 * 1024, () -> {
        closeDecoder();
        nalQueue.clear();
    });

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
    private final TextureView[] quadViews = new TextureView[4];

    // Camera settings
    private static final int CAM_COUNT = 4;
    private static final String DEFAULT_URL = "rtsp://root:12345@192.168.1.10:554/stream=0";
    private final String[] mHosts = new String[CAM_COUNT];
    private int mActive;
    private volatile String mHost;
    private String mVersion;
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

    // Codec/bitrate state for status overlay (pulled from RtspClient)
    private volatile boolean codecH265;
    private volatile String audioCodec = "PCM";

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
            public void onCodecChanged(boolean h265, String aCodec, int sr, boolean be, int pt) {
                codecH265 = h265;
                audioCodec = aCodec;
                audioManager.configure(sr, pt, be, aCodec);
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
        for (int row = 0; row < 2; row++) {
            LinearLayout lr = new LinearLayout(this);
            lr.setOrientation(LinearLayout.HORIZONTAL);
            quadContainer.addView(lr, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            for (int col = 0; col < 2; col++) {
                int idx = row * 2 + col;
                quadViews[idx] = new TextureView(this);
                quadViews[idx].setOnClickListener(cv ->
                        menuManager.showMenu(menuAnchor, quadEnabled, mActive, mVersion,
                                BuildConfig.GIT_HASH, mHosts));
                lr.addView(quadViews[idx], new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            }
        }
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
    }

    // ====================== MenuManager.Listener ======================

    @Override
    public void onSwitchCamera(int slot) {
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
        boolean newState = !quadEnabled;
        if (newState) startQuad(); else {
            stopQuad();
            boolean configured = mHost != null && !mHost.isEmpty() && !mHost.equals(DEFAULT_URL);
            if (configured) {
                rtspClient.configure(mHost, mUserAgent);
                startListener();
                rtspClient.start();
            }
        }
    }

    @Override
    public void onExit() { finishAndRemoveTask(); }

    @Override
    public void onShowSettings() { menuManager.showUrlEditor(mActive, mHosts); }

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
            edit.putString("host_0", pref.getString("host", DEFAULT_URL));
            edit.putBoolean("type_0", pref.getBoolean("type", false));
            edit.remove("host");
            edit.remove("type");
            edit.apply();
        }

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
            edit.putString("host_" + i, mHosts[i]);
        }
        edit.apply();

        applyActiveCamera();
        if (quadEnabled) return;

        listenerGen++;
        if (rtspClient != null) {
            rtspClient.invalidateGen();
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

        rtspClient.configure(mHost, mUserAgent);
        startListener();
        rtspClient.start();
    }

    // ====================== Status overlay ======================

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
                    statusText.setVisibility(View.VISIBLE);
                    statusStreamBytes = rtspClient != null ? rtspClient.getStreamBytes() : 0;
                    statusBitrateNs = rtspClient != null ? rtspClient.getBitrateMarkNs() : System.nanoTime();
                    // After 1 second, refresh with codec + bitrate info
                    statusText.postDelayed(() -> {
                        if (statusText == null
                                || statusText.getVisibility() != View.VISIBLE) return;
                        String videoCodec = codecH265 ? "H.265" : "H.264";
                        String aCodec = "AAC".equals(audioCodec) ? "AAC"
                                : "G711".equals(audioCodec) ? "G.711" : "PCM";
                        long elapsedNs = System.nanoTime() - statusBitrateNs;
                        String bps = "";
                        long bytes = rtspClient != null ? rtspClient.getStreamBytes() : 0;
                        if (elapsedNs > 500_000_000 && bytes > 0) {
                            double mbps = (bytes * 8.0 / 1_000_000)
                                    / (elapsedNs / 1_000_000_000.0);
                            bps = String.format(Locale.US, " • %.1f Mbps", mbps);
                        }
                        statusText.setText("Connected • " + videoCodec + " • " + aCodec + bps);
                    }, 1000);
                    statusText.postDelayed(() -> {
                        if (statusText != null) {
                            statusText.setVisibility(View.GONE);
                        }
                    }, 5000);
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
            rtspClient.invalidateGen();
            rtspClient.stop();
        }
        closeDecoder();
        closeAudio();
        nalQueue.clear();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        mSurface.setVisibility(View.GONE);
        quadContainer.setVisibility(View.VISIBLE);
        removeClearOverlay();

        quadCells = new QuadCell[4];
        for (int i = 0; i < 4; i++) {
            String url = mHosts[i];
            if (url == null || url.isEmpty() || url.equals(DEFAULT_URL)) continue;
            quadCells[i] = new QuadCell(i, url, quadViews[i], framePool, mUserAgent, index -> runOnUiThread(() -> removeQuadOverlay(index)));
            quadCells[i].start();
        }

        quadEnabled = true;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("quad_enabled", true).apply();
    }

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
        for (int i = 0; i < 4; i++) {
            TextureView t = quadViews[i];
            if (t == null) continue;
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

    private void removeQuadOverlay(int index) {
        if (index < 0 || index >= quadViews.length || quadViews[index] == null) return;
        TextureView t = quadViews[index];
        View overlay = t.findViewWithTag("quad_overlay_" + index);
        if (overlay != null) {
            overlay.setVisibility(View.GONE);
        }
    }

    // ====================== Screen / zoom ======================

    private void takeScreenshot() {
        if (isTakingScreenshot) return;
        isTakingScreenshot = true;

        Bitmap bitmap = mSurface.getBitmap();
        if (bitmap == null) {
            Toast.makeText(this, "No video frame available", Toast.LENGTH_SHORT).show();
            isTakingScreenshot = false;
            return;
        }

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
        isTakingScreenshot = false;
    }

    private void updateResolution(int width, int height) {
        if (width < 64 || height < 64) return;
        Log.d(TAG, "Resolution update: " + width + "x" + height);
        runOnUiThread(() -> {
            int surfaceH = mSurface.getHeight();
            if (surfaceH == 0) surfaceH = getResources().getDisplayMetrics().heightPixels;
            int surfaceW = (int) ((float) surfaceH / height * width);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(surfaceW, surfaceH);
            params.gravity = Gravity.CENTER;
            mSurface.setLayoutParams(params);
        });
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
        listenerGen++;
        final int gen = listenerGen;

        executor = Executors.newFixedThreadPool(2);

        // Video decode thread
        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-video");
            while (gen == listenerGen) {
                try {
                    Frame buffer = nalQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (buffer != null) {
                        boolean needCreate = videoDecoder.decodeFrame(buffer, codecH265, 0);
                        framePool.recycle(buffer);
                        if (needCreate) createDecoder();
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
            while (gen == listenerGen) {
                audioManager.playPcmFrame();
            }
        });
    }

    // ====================== Lifecycle ======================

    @Override
    protected void onPause() {
        super.onPause();
        menuManager.onPauseDismissBrowser();

        if (quadCells != null) {
            for (QuadCell cell : quadCells) {
                if (cell != null) cell.stop();
            }
            quadCells = null;
        }

        listenerGen++;
        if (rtspClient != null) {
            rtspClient.invalidateGen();
            rtspClient.stop();
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
        closeDecoder();
        closeAudio();
        nalQueue.clear();
    }

    @Override
    protected void onResume() {
        super.onResume();

        loadSettings();
        if (quadEnabled) {
            mSurface.setVisibility(View.GONE);
            quadContainer.setVisibility(View.VISIBLE);
            if (quadCells != null) {
                for (QuadCell cell : quadCells) {
                    if (cell != null) cell.stop();
                }
                quadCells = null;
            }
            clearQuadViews();
            quadCells = new QuadCell[4];
            for (int i = 0; i < 4; i++) {
                String url = mHosts[i];
                if (url == null || url.isEmpty() || url.equals(DEFAULT_URL)) continue;
                quadCells[i] = new QuadCell(i, url, quadViews[i], framePool, mUserAgent, index -> runOnUiThread(() -> removeQuadOverlay(index)));
                quadCells[i].start();
            }
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("quad_enabled", true).apply();
        } else {
            boolean configured = mHost != null && !mHost.isEmpty() && !mHost.equals(DEFAULT_URL);
            if (configured) {
                rtspClient.configure(mHost, mUserAgent);
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
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (menuManager.isPopupShowing()) {
                    menuManager.dismissPopup();
                    return true;
                }
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // QuadCell class — extracted to QuadCell.java

}
