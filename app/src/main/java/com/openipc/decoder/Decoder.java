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
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
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
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class Decoder extends Activity {
    private final String TAG = "OpenIPCDecoder";
    private final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(32);
    private final BlockingQueue<Frame> pcmQueue = new ArrayBlockingQueue<>(32);
    private final byte[] nalBuffer = new byte[1024 * 1024];

    private int nalSize;
    private MediaCodec mDecoder;
    private SurfaceView mSurface;
    private AudioTrack audioTrack;
    private TextView mConnect;

    private volatile boolean codecH265;
    private boolean lastCodec;
    private volatile boolean activeStream;
    // Incremented on every start/stop; worker threads exit when it no longer matches
    private volatile int listenerGen;
    private volatile Socket mSocket;
    private int lastWidth;
    private int lastHeight;
    private volatile long lastFrame;

    private volatile String mHost;

    // Stream parameters taken from the SDP of the current session
    private volatile int videoPt = -1;
    private volatile int audioPt = -1;
    private volatile int audioRate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.decoder);

        mSurface = findViewById(R.id.video_surface);
        mSurface.setKeepScreenOn(true);
        mConnect = findViewById(R.id.text_connect);
        mConnect.setTextColor(Color.LTGRAY);

        codecH265 = true;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        View menu = findViewById(R.id.decoder);
        menu.setOnClickListener(item -> createMenu(menu));

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception", throwable);

            Intent intent = new Intent(getApplicationContext(), Crash.class);
            intent.putExtra("error", Log.getStackTraceString(throwable));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            System.exit(1);
        });
    }

    @SuppressLint("AuthLeak")
    private void loadSettings() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        mHost = pref.getString("host", "rtsp://root:12345@192.168.1.10:554/stream=0");

        String link = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (link != null) {
            Log.d(TAG, "Link: " + link);
            mHost = link;
        }
    }

    private void saveSettings() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        edit.putString("host", mHost);
        edit.apply();

        mConnect.setText(mHost);
        activeStream = false;
        closeSocket();
    }

    private void createMenu(View menu) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        PopupWindow popup = new PopupWindow(layout, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.showAtLocation(menu, Gravity.TOP | Gravity.START, 0, 80);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setVisibility(View.GONE);
        layout.addView(header);

        TextView settings = createItem("Settings");
        layout.addView(settings);

        EditText host = createEdit(mHost);
        header.addView(host);
        host.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                mHost = host.getText().toString().trim();
                saveSettings();
                popup.dismiss();
                return true;
            }
            return false;
        });

        TextView webui = createItem("WebUI");
        layout.addView(webui);
        webui.setOnClickListener(v -> {
            startBrowser();
            popup.dismiss();
        });

        String code = "Exit [V1.0]";
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            code = "Exit [V" + name + "]";
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Cannot extract version code");
        }

        SpannableString s = new SpannableString(code);
        s.setSpan(new SuperscriptSpan(), 4, s.length(), 0);
        s.setSpan(new RelativeSizeSpan(0.5f), 5, s.length(), 0);

        TextView exit = createItem("Exit");
        layout.addView(exit);
        exit.setText(s);
        exit.setOnClickListener(v -> finishAndRemoveTask());

        settings.setOnClickListener(v -> {
            boolean show = header.getVisibility() == View.VISIBLE;
            header.setVisibility(show ? View.GONE : View.VISIBLE);
            settings.setVisibility(show ? View.VISIBLE : View.GONE);
            webui.setVisibility(show ? View.VISIBLE : View.GONE);
            exit.setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    private TextView createItem(String title) {
        TextView text = new TextView(this);
        text.setText(title);
        text.setPadding(40, 40, 40, 40);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        focusChange(text);

        return text;
    }

    private EditText createEdit(String title) {
        EditText text = new EditText(this);
        text.setText(title);
        text.setPadding(40, 40, 40, 40);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        text.setImeOptions(EditorInfo.IME_ACTION_DONE);
        text.setSelection(text.getText().length());
        focusChange(text);

        return text;
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

    @SuppressLint("SetJavaScriptEnabled")
    private void startBrowser() {
        WebView view = new WebView(this);
        view.setWebViewClient(new WebViewClient());
        view.getSettings().setJavaScriptEnabled(true);
        String link = Uri.parse(mHost).getHost();
        if (link != null) {
            view.loadUrl("http://" + link);
        }

        Log.d(TAG, "WebView: " + link);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(true);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenWidth = (int) (displayMetrics.widthPixels * 0.75);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(screenWidth, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.show();

        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedHttpAuthRequest(
                    WebView view, HttpAuthHandler handler, String host, String realm) {
                String mUser = Uri.parse(mHost).getUserInfo();
                if (mUser != null) {
                    String[] part = mUser.split(":", 2);
                    if (part.length > 1) {
                        handler.proceed(part[0], part[1]);
                    }
                }
            }
        });
    }

    private void updateResolution(int width, int height) {
        if (lastWidth < 64 || lastHeight < 64) {
            return;
        }

        int lastH = mSurface.getHeight();
        int lastW = (int) ((float) lastH / height * width);
        Log.d(TAG, "Set resolution: " + width + "x" + height + " -> " + lastW + "x" + lastH);

        runOnUiThread(() -> {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(lastW, lastH);
            params.width = lastW;
            params.height = lastH;
            params.gravity = Gravity.CENTER;
            findViewById(R.id.video_surface).setLayoutParams(params);
            mConnect.setVisibility(View.GONE);
        });
    }

    private int getFragment(byte data) {
        return codecH265 ? (data >> 1) & 0x3F : data & 0x1F;
    }

    private Frame buildFrame(Frame frame) {
        byte[] rxBuffer = frame.data();
        int rxSize = frame.length();
        int cpSize = 12;
        rxSize -= cpSize;

        int staBit;
        int endBit;
        int nalBit = 4;

        if (lastCodec != codecH265) {
            lastCodec = codecH265;
            closeDecoder();
            Log.d(TAG, "Set codec to " + (codecH265 ? "H265" : "H264"));
        }

        int fragment = getFragment(rxBuffer[cpSize]);
        if (fragment == 28 || fragment == 49) {
            if (codecH265) {
                staBit = rxBuffer[cpSize + 2] & 0x80;
                endBit = rxBuffer[cpSize + 2] & 0x40;
                int type = (rxBuffer[cpSize] & 0x81) | (rxBuffer[cpSize + 2] & 0x3F) << 1;
                nalBuffer[4] = (byte) type;
                nalBuffer[5] = 1;

                nalBit++;
                cpSize++;
                rxSize--;
            } else {
                staBit = rxBuffer[cpSize + 1] & 0x80;
                endBit = rxBuffer[cpSize + 1] & 0x40;
                int type = (rxBuffer[cpSize] & 0xE0) | (rxBuffer[cpSize + 1] & 0x1F);
                nalBuffer[4] = (byte) type;
            }

            cpSize++;
            rxSize--;

            if (staBit > 0) {
                nalBuffer[0] = 0;
                nalBuffer[1] = 0;
                nalBuffer[2] = 0;
                nalBuffer[3] = 1;

                System.arraycopy(rxBuffer, cpSize, nalBuffer, nalBit, rxSize);
                nalSize = rxSize + nalBit;
            } else {
                cpSize++;
                rxSize--;

                System.arraycopy(rxBuffer, cpSize, nalBuffer, nalSize, rxSize);
                nalSize += rxSize;

                if (endBit > 0) {
                    byte[] output = new byte[nalSize];
                    System.arraycopy(nalBuffer, 0, output, 0, nalSize);
                    return new Frame(output, nalSize);
                }
            }
        } else {
            nalBuffer[0] = 0;
            nalBuffer[1] = 0;
            nalBuffer[2] = 0;
            nalBuffer[3] = 1;

            System.arraycopy(rxBuffer, cpSize, nalBuffer, nalBit, rxSize);
            nalSize = rxSize + nalBit;

            byte[] output = new byte[nalSize];
            System.arraycopy(nalBuffer, 0, output, 0, nalSize);
            return new Frame(output, nalSize);
        }

        return null;
    }

    private void playAudio(Frame data) {
        if (audioTrack == null) {
            createAudio(data.length);
        } else {
            audioTrack.write(data.data, 0, data.length);
        }
    }

    private void processAudio(Frame frame) {
        int header = 12;
        int length = frame.length() - header;

        byte[] data = new byte[length];
        System.arraycopy(frame.data(), header, data, 0, length);
        for (int i = 0; i + 1 < length; i += 2) {
            byte tmp = data[i];
            data[i] = data[i + 1];
            data[i + 1] = tmp;
        }

        Frame buffer = new Frame(data, length);
        try {
            pcmQueue.put(buffer);
        } catch (InterruptedException e) {
            Log.w(TAG, "Audio queue is full");
        }
    }

    private void createAudio(int length) {
        // Rate from the SDP; legacy guess for servers that do not announce it
        int sample = audioRate > 0 ? audioRate : length * 25;
        int format = AudioFormat.CHANNEL_OUT_MONO;

        Log.d(TAG, "Create audio decoder (" + sample + "hz)");
        int size = AudioTrack.getMinBufferSize(sample, format, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sample, format,
                AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
        audioTrack.play();
    }

    private void closeAudio() {
        if (audioTrack != null) {
            Log.i(TAG, "Close audio decoder");
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
    }

    private void decodeFrame(Frame buffer) {
        if (mDecoder == null) {
            createDecoder();
            return;
        }

        lastFrame = SystemClock.elapsedRealtime();

        int flag = 0;
        int fragment = getFragment(buffer.data[4]);
        if (fragment == 32 || fragment == 33 || fragment == 34) {
            flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
        }

        try {
            int inputBufferId = mDecoder.dequeueInputBuffer(0);
            if (inputBufferId >= 0) {
                ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputBufferId);
                if (inputBuffer != null) {
                    inputBuffer.put(buffer.data, 0, buffer.length);
                    mDecoder.queueInputBuffer(inputBufferId, 0,
                            buffer.length, System.nanoTime() / 1000, flag);
                }
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outputBufferId = mDecoder.dequeueOutputBuffer(info, 0);

            if (outputBufferId >= 0) {
                MediaFormat format = mDecoder.getOutputFormat(outputBufferId);
                int mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                int mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);

                if (lastWidth != mWidth || lastHeight != mHeight) {
                    lastWidth = mWidth;
                    lastHeight = mHeight;
                    updateResolution(lastWidth, lastHeight);
                }

                mDecoder.releaseOutputBuffer(outputBufferId, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Codec exception: " + e.getMessage());
            mDecoder = null;
        }
    }

    private void createDecoder() {
        Surface mVideo = mSurface.getHolder().getSurface();
        if (!mVideo.isValid()) {
            return;
        }

        MediaCodec local;
        String type = codecH265 ? "video/hevc" : "video/avc";

        MediaFormat format;
        format = MediaFormat.createVideoFormat(type, 1280, 720);

        try {
            Log.i(TAG, "Start video decoder");
            local = MediaCodec.createDecoderByType(type);
            local.configure(format, mVideo, null, 0);
            local.start();
        } catch (Exception e) {
            Log.e(TAG, "Cannot setup decoder: " + e.getMessage());
            return;
        }

        mDecoder = local;
        updateResolution(lastWidth, lastHeight);
    }

    private void closeDecoder() {
        if (mDecoder != null) {
            Log.i(TAG, "Close video decoder");
            try {
                mDecoder.stop();
                mDecoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Decoder exception");
            }
            mDecoder = null;
        }
    }

    private void closeSocket() {
        Socket s = mSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class Response {
        int code;
        final Map<String, String> headers = new HashMap<>();
        String body = "";
    }

    /** Read one CRLF-terminated line byte by byte, so no stream data is buffered away. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != '\n') {
            if (c < 0) throw new IOException("Connection closed");
            if (c != '\r') {
                if (sb.length() > 8192) throw new IOException("RTSP line too long");
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    private static void readFully(InputStream in, byte[] data, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(data, read, len - read);
            if (n < 0) throw new IOException("Connection closed");
            read += n;
        }
    }

    private Response readResponse(InputStream in) throws IOException {
        Response r = new Response();
        String status = readLine(in);
        Log.i(TAG, status);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("RTSP/")) {
            throw new IOException("Bad RTSP response: " + status);
        }
        try {
            r.code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Bad RTSP status: " + status);
        }

        String line;
        while (!(line = readLine(in)).isEmpty()) {
            Log.i(TAG, line);
            int colon = line.indexOf(':');
            if (colon > 0) {
                r.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        String length = r.headers.get("content-length");
        if (length != null) {
            int len = Integer.parseInt(length);
            if (len < 0 || len > 65536) throw new IOException("Bad Content-Length: " + len);
            byte[] body = new byte[len];
            readFully(in, body, len);
            r.body = new String(body, StandardCharsets.UTF_8);
        }
        return r;
    }

    private Response request(InputStream in, OutputStream w, String method, String url,
                             int seq, String headers) throws IOException {
        String req = method + " " + url + " RTSP/1.0\r\n" +
                "CSeq: " + seq + "\r\n" + headers + "\r\n";
        w.write(req.getBytes(StandardCharsets.UTF_8));
        w.flush();
        return readResponse(in);
    }

    private static String resolveControl(String base, String control) {
        if (control == null || control.equals("*")) return base;
        if (control.startsWith("rtsp://") || control.startsWith("rtsps://")) return control;
        return base.endsWith("/") ? base + control : base + "/" + control;
    }

    /** Parse the SDP: returns { videoUrl, audioUrl or null } and sets the stream parameters. */
    private String[] parseSdp(String sdp, String base) {
        String[] urls = { null, null };
        String[] controls = { null, null };
        String session = null;
        int media = -1; // -1 session level, 0 video, 1 audio, 2 other
        int vPt = -1;
        int aPt = -1;
        String vCodec = null;
        String aCodec = null;
        int aRate = 0;
        boolean hasAudio = false;

        for (String line : sdp.split("\r?\n")) {
            if (line.startsWith("m=")) {
                String[] p = line.split("\\s+");
                int pt = -1;
                try {
                    if (p.length >= 4) pt = Integer.parseInt(p[3]);
                } catch (NumberFormatException ignored) {
                }
                if (line.startsWith("m=video")) {
                    media = 0;
                    vPt = pt;
                } else if (line.startsWith("m=audio")) {
                    media = 1;
                    aPt = pt;
                    hasAudio = true;
                } else {
                    media = 2;
                }
            } else if (line.startsWith("a=control:")) {
                String ctrl = line.substring(10).trim();
                if (media == -1) session = ctrl;
                else if (media < 2) controls[media] = ctrl;
            } else if (line.startsWith("a=rtpmap:") && (media == 0 || media == 1)) {
                // a=rtpmap:<pt> <name>/<rate>[/<channels>]
                String[] p = line.substring(9).trim().split("[\\s/]+");
                if (p.length < 2) continue;
                if (media == 0 && p[0].equals(String.valueOf(vPt))) {
                    vCodec = p[1].toUpperCase(Locale.ROOT);
                } else if (media == 1 && p[0].equals(String.valueOf(aPt))) {
                    aCodec = p[1].toUpperCase(Locale.ROOT);
                    try {
                        if (p.length > 2) aRate = Integer.parseInt(p[2]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        String sessionBase = resolveControl(base, session);
        urls[0] = resolveControl(sessionBase, controls[0] != null ? controls[0] : "trackID=0");
        if (hasAudio) {
            urls[1] = resolveControl(sessionBase, controls[1] != null ? controls[1] : "trackID=1");
        }

        // Video codec from rtpmap; servers without rtpmap keep the legacy 96/97 convention
        videoPt = vPt;
        if (vCodec != null) {
            codecH265 = vCodec.equals("H265") || vCodec.equals("HEVC");
        } else {
            codecH265 = vPt == 97;
        }

        // Only 16-bit PCM (L16) is played; without rtpmap keep the legacy PT 100
        boolean pcm = aCodec != null ? aCodec.equals("L16") : aPt == 100;
        audioPt = pcm ? aPt : -1;
        audioRate = pcm && aRate > 0 && aRate <= 192000 ? aRate : 0;

        Log.i(TAG, "SDP: video pt " + vPt + " " + (codecH265 ? "H265" : "H264")
                + ", audio pt " + aPt + " " + (aCodec != null ? aCodec : "?")
                + (pcm ? "" : " (not played)"));
        return urls;
    }

    private void rtsp_connect(int gen) throws Exception {
        Uri uri = Uri.parse(mHost);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IOException("No host in URL");
        int port = uri.getPort();

        // Request URL without user:password, which goes into the Authorization header
        String path = uri.getEncodedPath();
        String query = uri.getEncodedQuery();
        String url = "rtsp://" + host + (port >= 0 ? ":" + port : "")
                + (path != null ? path : "") + (query != null ? "?" + query : "");

        try (Socket s = new Socket()) {
            mSocket = s;
            if (gen != listenerGen) return;
            s.connect(new InetSocketAddress(host, port < 0 ? 554 : port), 3000);
            s.setSoTimeout(3000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream w = s.getOutputStream();

            Log.d(TAG, "Start rtsp connection");

            String user = uri.getUserInfo();
            String auth = "";
            if (user != null) {
                auth = "Authorization: Basic " + Base64.encodeToString(
                        user.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\r\n";
            }

            int seq = 1;
            Response r = request(in, w, "DESCRIBE", url, seq++,
                    auth + "Accept: application/sdp\r\n");
            if (r.code / 100 != 2) throw new IOException("DESCRIBE failed: " + r.code);
            String base = r.headers.get("content-base");
            String[] tracks = parseSdp(r.body, base != null ? base : url);

            r = request(in, w, "SETUP", tracks[0], seq++,
                    auth + "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
            if (r.code / 100 != 2) throw new IOException("SETUP video failed: " + r.code);
            String session = r.headers.get("session");
            if (session == null) throw new IOException("No Session header");
            session = session.split(";")[0].trim();

            if (tracks[1] != null) {
                r = request(in, w, "SETUP", tracks[1], seq++, auth +
                        "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n" +
                        "Session: " + session + "\r\n");
                if (r.code / 100 != 2) {
                    Log.w(TAG, "SETUP audio failed: " + r.code + ", continuing without audio");
                    audioPt = -1;
                }
            }

            r = request(in, w, "PLAY", url, seq, auth + "Session: " + session + "\r\n");
            if (r.code / 100 != 2) throw new IOException("PLAY failed: " + r.code);

            lastFrame = SystemClock.elapsedRealtime();
            activeStream = true;
            tcp_stream(in, gen);
        } finally {
            // A thread from an older generation must not clobber the state of a newer one
            if (gen == listenerGen) {
                mSocket = null;
                activeStream = false;
            }
        }
    }

    private void tcp_stream(InputStream input, int gen) throws IOException {
        while (activeStream && gen == listenerGen) {
            int b = input.read();
            if (b < 0) throw new IOException("Connection closed");
            if (b != 0x24) {
                continue;
            }

            int channel = input.read();
            int hi = input.read();
            int lo = input.read();
            if ((channel | hi | lo) < 0) throw new IOException("Connection closed");
            int len = (hi << 8) | lo;
            byte[] data = new byte[len];
            readFully(input, data, len);

            if ((channel == 0 || channel == 2) && len > 13) {
                processPacket(new Frame(data, len));
            }
        }
    }

    private int lastUnknownPayload = -1;

    private void processPacket(Frame frame) {
        byte[] data = frame.data();
        int payload = (data[1] & 0x7F);
        if (payload == videoPt) {
            lastFrame = SystemClock.elapsedRealtime();
            Frame output = buildFrame(frame);
            if (output != null && !nalQueue.offer(output)) {
                Log.w(TAG, "Frame queue is full");
            }
            return;
        } else if (payload == audioPt) {
            processAudio(frame);
            return;
        }

        if (payload != lastUnknownPayload) {
            lastUnknownPayload = payload;
            Log.w(TAG, "Ignored rtp type: " + payload);
        }
    }

    private void startListener() {
        Log.i(TAG, "Start network listener");
        final int gen = ++listenerGen;

        new Thread(() -> {
            while (gen == listenerGen) {
                try {
                    if (!activeStream) {
                        runOnUiThread(() -> {
                            mSurface.setVisibility(View.GONE);
                            mSurface.setVisibility(View.VISIBLE);
                            mConnect.setVisibility(View.VISIBLE);
                        });
                        rtsp_connect(gen);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Cannot connect rtsp: " + e.getMessage());
                    SystemClock.sleep(1000);
                }
            }
        }, "rtsp-network").start();

        new Thread(() -> {
            while (gen == listenerGen) {
                if (activeStream && SystemClock.elapsedRealtime() - lastFrame > 3000) {
                    Log.w(TAG, "Stream is inactive");
                    activeStream = false;
                    closeSocket();
                }
                SystemClock.sleep(1000);
            }
        }, "rtsp-watchdog").start();

        new Thread(() -> {
            while (gen == listenerGen) {
                try {
                    Frame buffer = nalQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (buffer != null && gen == listenerGen) {
                        decodeFrame(buffer);
                    }
                } catch (InterruptedException e) {
                    Log.w(TAG, "Cannot decode video: " + e.getMessage());
                }
            }
        }, "rtsp-video").start();

        new Thread(() -> {
            while (gen == listenerGen) {
                try {
                    Frame buffer = pcmQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (buffer != null && gen == listenerGen) {
                        playAudio(buffer);
                    }
                } catch (InterruptedException e) {
                    Log.w(TAG, "Cannot decode audio: " + e.getMessage());
                }
            }
        }, "rtsp-audio").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Old threads see the new generation and exit; closing the socket unblocks the reader
        listenerGen++;
        activeStream = false;
        closeSocket();
        closeDecoder();
        closeAudio();
        nalQueue.clear();
        pcmQueue.clear();
    }

    @Override
    protected void onResume() {
        super.onResume();

        loadSettings();
        startListener();

        mConnect.setText(mHost);
        mConnect.setVisibility(View.VISIBLE);
    }

    private record Frame(byte[] data, int length) {
    }
}
