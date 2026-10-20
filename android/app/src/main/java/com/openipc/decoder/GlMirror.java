/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * GlMirror.java — draws a decoder's output mirrored (and turned) onto a surface with OpenGL ES
 *
 */

package com.openipc.decoder;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A slot with Mirror on (#226): the decoder renders into a SurfaceTexture, and every frame is
 * drawn onto the view's surface flipped left↔right and turned like the slot's Turn. A
 * SurfaceView cannot be mirrored with setScaleX(-1) (the system composes it), and
 * MediaFormat.KEY_ROTATION has no mirror, so this path is used only for mirrored slots; the
 * others keep the decoder drawing straight onto the SurfaceView (#76).
 *
 * Everything GL runs on one thread of its own. Release the decoder before this, so its output
 * surface is gone when the EGL surface leaves the view's surface.
 */
final class GlMirror implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "OpenIPCDecoder";
    private static final long SYNC_TIMEOUT_MS = 2_000;

    private static final String VERTEX =
            "uniform mat4 uMvp;\n"
            + "uniform mat4 uSt;\n"
            + "attribute vec4 aPos;\n"
            + "attribute vec4 aTex;\n"
            + "varying vec2 vTex;\n"
            + "void main() {\n"
            + "  gl_Position = uMvp * aPos;\n"
            + "  vTex = (uSt * aTex).xy;\n"
            + "}\n";
    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vTex;\n"
            + "uniform samplerExternalOES sTex;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(sTex, vTex);\n"
            + "}\n";
    /** Triangle strip over the whole surface: x, y, then u, v. */
    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
    };

    private final HandlerThread thread;
    private final Handler handler;
    private final float[] mvp;
    private final float[] st = new float[16];
    private final FloatBuffer quad;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int program;
    private int texture;
    private int uMvp, uSt, aPos, aTex;
    private SurfaceTexture surfaceTexture;
    private Surface input;
    private volatile boolean released;
    /** eglInitialize() succeeded: tearDown() balances it with eglTerminate(). */
    private boolean initialized;

    private GlMirror(int turnCcw, boolean mirror) {
        mvp = transform(turnCcw, mirror);
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quad.put(QUAD).position(0);
        thread = new HandlerThread("gl-mirror");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /**
     * The position transform: turned {@code turnCcw} degrees counter-clockwise, then flipped
     * left↔right on the screen when {@code mirror}, so Mirror always swaps the sides of the
     * picture as it is shown, whatever the turn.
     */
    static float[] transform(int turnCcw, boolean mirror) {
        // Quarter turns are exact; column-major, as glUniformMatrix4fv takes it
        int t = ((turnCcw % 360) + 360) % 360;
        float c = t == 0 ? 1f : t == 180 ? -1f : 0f;
        float s = t == 90 ? 1f : t == 270 ? -1f : 0f;
        float f = mirror ? -1f : 1f;
        return new float[] {
                f * c, s, 0f, 0f,
                -f * s, c, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
        };
    }

    /** Where the point (x, y) of the full-surface quad lands with {@code m} (for tests). */
    static float[] map(float[] m, float x, float y) {
        return new float[] {m[0] * x + m[4] * y + m[12], m[1] * x + m[5] * y + m[13]};
    }

    /** Set up on {@code target}; throws when EGL or GL cannot. Decode into {@link #input()}. */
    static GlMirror create(Surface target, int turnCcw, boolean mirror) throws Exception {
        GlMirror g = new GlMirror(turnCcw, mirror);
        try {
            g.runSync(() -> g.setUp(target));
        } catch (Exception e) {
            g.release();
            throw e;
        }
        return g;
    }

    /** The surface the decoder renders into. */
    Surface input() { return input; }

    /** Release GL and the thread; waits until the view's surface is free again. */
    void release() {
        // A frame already queued on the GL thread is no longer drawn (Qodo on #246)
        released = true;
        try {
            runSync(this::tearDown);
        } catch (Exception e) {
            Log.w(TAG, "Mirror release: " + e.getMessage());
        }
        thread.quitSafely();
    }

    private interface Task {
        void run() throws Exception;
    }

    private void runSync(Task task) throws Exception {
        final Exception[] failure = new Exception[1];
        CountDownLatch done = new CountDownLatch(1);
        if (!handler.post(() -> {
            try {
                task.run();
            } catch (Exception e) {
                failure[0] = e;
            } finally {
                done.countDown();
            }
        })) {
            throw new IllegalStateException("mirror thread stopped");
        }
        if (!done.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("mirror thread does not answer");
        }
        if (failure[0] != null) throw failure[0];
    }

    private void setUp(Surface target) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new IllegalStateException("EGL display: " + eglError());
        }
        initialized = true;
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] < 1) {
            throw new IllegalStateException("EGL config: " + eglError());
        }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("EGL context: " + eglError());
        window = EGL14.eglCreateWindowSurface(display, configs[0], target,
                new int[] {EGL14.EGL_NONE}, 0);
        if (window == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("EGL surface: " + eglError());
        if (!EGL14.eglMakeCurrent(display, window, window, context)) {
            throw new IllegalStateException("EGL current: " + eglError());
        }

        program = link(compile(GLES20.GL_VERTEX_SHADER, VERTEX),
                compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uSt = GLES20.glGetUniformLocation(program, "uSt");
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texture = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(texture);
        surfaceTexture.setOnFrameAvailableListener(this, handler);
        input = new Surface(surfaceTexture);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture t) {
        // On the GL thread (the listener's handler)
        if (released || t != surfaceTexture) return;
        try {
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(st);
            int[] size = new int[2];
            EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0);
            EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 1);
            GLES20.glViewport(0, 0, size[0], size[1]);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(uSt, 1, false, st, 0);
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGL14.eglSwapBuffers(display, window);
        } catch (RuntimeException e) {
            // The surface went away meanwhile (view or activity change): the owner releases us
            Log.w(TAG, "Mirror frame: " + e.getMessage());
        }
    }

    private void tearDown() {
        released = true;
        if (surfaceTexture != null) {
            surfaceTexture.setOnFrameAvailableListener(null);
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (input != null) {
            input.release();
            input = null;
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program);
            if (texture != 0) GLES20.glDeleteTextures(1, new int[] {texture}, 0);
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            // Destroying the EGL surface frees the view's surface for a plain decoder again
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            // Android counts eglInitialize() per display: this only drops our reference, the
            // other mirrored cells and the system renderer keep theirs (Qodo on #246)
            if (initialized) EGL14.eglTerminate(display);
            EGL14.eglReleaseThread();
        }
        initialized = false;
        window = EGL14.EGL_NO_SURFACE;
        context = EGL14.EGL_NO_CONTEXT;
        display = EGL14.EGL_NO_DISPLAY;
        program = 0;
        texture = 0;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("shader: " + log);
        }
        return shader;
    }

    private static int link(int vertex, int fragment) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vertex);
        GLES20.glAttachShader(p, fragment);
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new IllegalStateException("program: " + log);
        }
        return p;
    }

    private static String eglError() {
        return "0x" + Integer.toHexString(EGL14.eglGetError());
    }
}
