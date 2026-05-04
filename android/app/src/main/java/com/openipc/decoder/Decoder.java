/*
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * Decoder.java — main activity for H.264/H.265 hardware video decoding
 *
 */

package com.openipc.decoder;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
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

import java.nio.ByteBuffer;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
        if (newState) startQuad(); else stopQuad();
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
            quadCells[i] = new QuadCell(i, url, quadViews[i], framePool);
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

        boolean configured = mHost != null && !mHost.isEmpty() && !mHost.equals(DEFAULT_URL);
        if (configured) {
            rtspClient.configure(mHost, mUserAgent);
            startListener();
            rtspClient.start();
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
                quadCells[i] = new QuadCell(i, url, quadViews[i], framePool);
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

    // ====================== QuadCell inner class ======================

    private class QuadCell {
        final int index;
        final String host;
        final TextureView view;
        final String tag;
        final FramePool cellFramePool;

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

        QuadCell(int index, String host, TextureView view, FramePool pool) {
            this.index = index;
            this.host = host;
            this.view = view;
            this.tag = "Quad-" + index;
            this.cellFramePool = pool;
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
                        try { old.release(); } catch (Exception e) {
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
                        try { old.release(); } catch (Exception e) {
                            Log.e(TAG, tag + " Error releasing surface on size change", e);
                        }
                    }
                }
                @Override
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                    Surface s = surface;
                    surface = null;
                    if (s != null) {
                        try { s.release(); } catch (Exception e) {
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

            executor.execute(() -> {
                Thread.currentThread().setName(tag + "-wd");
                final long WATCHDOG_MS = 8000;
                while (running) {
                    if (activeStream && lastFrame > 0
                            && SystemClock.elapsedRealtime() - lastFrame > WATCHDOG_MS) {
                        activeStream = false;
                        Socket tcp = tcpSocket;
                        if (tcp != null) {
                            try { tcp.close(); } catch (Exception e) {
                                Log.e(TAG, tag + " Error closing socket in watchdog", e);
                            }
                        }
                    }
                    SystemClock.sleep(1000);
                }
            });

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
                try { tcp.close(); } catch (Exception e) {
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
            surface = null;
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
                            Base64.encodeToString(user.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\r\n";
                }
                String path = uri.getEncodedPath();
                String query = uri.getEncodedQuery();
                String rtspUrl = uri.getScheme() + "://" + h
                        + (port >= 0 ? ":" + port : "")
                        + (path  != null ? path         : "")
                        + (query != null ? "?" + query  : "");

                // DESCRIBE
                int seq = 1;
                w.write(("DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent +
                        "Accept: application/sdp\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                w.flush();

                String contentLenStr = RtspClient.readRtspResponse(input, "Content-Length:");
                int sdpLen = 0;
                if (contentLenStr != null) {
                    try { sdpLen = Integer.parseInt(contentLenStr); } catch (NumberFormatException ignored) {}
                }
                StringBuilder sdpBuf = new StringBuilder();
                byte[] skipBuf = new byte[512];
                while (sdpLen > 0) {
                    int n = input.read(skipBuf, 0, Math.min(sdpLen, skipBuf.length));
                    if (n <= 0) break;
                    if (sdpBuf.length() < 4096)
                        sdpBuf.append(new String(skipBuf, 0, n, StandardCharsets.UTF_8));
                    sdpLen -= n;
                }

                // Parse SDP for video track only
                String videoControl = null;
                String baseControl = rtspUrl;
                int section = -1;
                for (String line : sdpBuf.toString().split("[\r\n]+")) {
                    if (line.startsWith("m=video")) {
                        section = 0;
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 4) {
                            try { codecH265 = Integer.parseInt(parts[3]) == 97; }
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

                // SETUP
                seq++;
                w.write(("SETUP " + videoControl + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent +
                        "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                w.flush();

                String session = RtspClient.readRtspResponse(input, "Session:");
                if (session == null) throw new IOException("No Session");
                session = session.replaceAll("[\r\n]", "");

                // PLAY
                seq++;
                w.write(("PLAY " + rtspUrl + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent +
                        "Session: " + session + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                w.flush();

                RtspClient.readRtspResponse(input, null);

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
                    try { s.close(); } catch (Exception e) {
                        Log.e(TAG, tag + " Error closing socket in connect()", e);
                    }
                }
            }
        }

        private void readTcp(InputStream rawInput) throws IOException {
            BufferedInputStream input = new BufferedInputStream(rawInput, 65536);
            byte[] pktBuf = new byte[65535];
            while (activeStream && running) {
                int total = RtspClient.readInterleavedPacket(input, pktBuf);
                if (total < 0) { activeStream = false; break; }

                int channel = pktBuf[1];
                int len = total - 4;
                if (channel == 0) {
                    Frame f = cellFramePool.obtain(len);
                    System.arraycopy(pktBuf, 4, f.data(), 0, len);
                    f.setLength(len);
                    handlePacket(f);
                }
            }
        }

        private void handlePacket(Frame frame) {
            if (frame.length() < 12) { cellFramePool.recycle(frame); return; }
            byte[] data = frame.data();
            if ((data[0] & 0x0F) != 0 || (data[0] & 0x10) != 0) { cellFramePool.recycle(frame); return; }

            int pt = data[1] & 0x7F;
            if (pt == 97 || pt == 96) {
                lastFrame = SystemClock.elapsedRealtime();
                codecH265 = (pt == 97);
                Frame output = nalAssembler.assemble(frame, codecH265);
                cellFramePool.recycle(frame);
                if (output != null && !nalQueue.offer(output)) {
                    Log.w(TAG, tag + " queue full, dropping frame");
                    cellFramePool.recycle(output);
                }
            } else if (pt != lastUnknownPayload) {
                lastUnknownPayload = pt;
                Log.w(TAG, tag + " unknown PT: " + pt);
                cellFramePool.recycle(frame);
            } else {
                cellFramePool.recycle(frame);
            }
        }

        private void decode(Frame buffer) {
            if (buffer.length() < 5) return;
            lastFrame = SystemClock.elapsedRealtime();

            int flag = 0;
            int frag = NalAssembler.fragment(buffer.data()[4], codecH265);
            boolean config = codecH265
                    ? (frag == 32 || frag == 33 || frag == 34)
                    : (frag == 7 || frag == 8);
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
            final int idx = index;
            runOnUiThread(() -> removeQuadOverlay(idx));
        }
    }

}
