/*
 *
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * App.java — Application subclass for global uncaught exception handling
 *
 */

package com.openipc.decoder;

import android.app.Application;
import android.content.Intent;
import android.os.Process;
import android.util.Log;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e("OpenIPCDecoder", "Uncaught exception", throwable);

            try {
                Intent intent = new Intent(getApplicationContext(), Crash.class);
                intent.putExtra("error", Log.getStackTraceString(throwable));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            } finally {
                // The app state is undefined after an uncaught exception (and the main looper
                // may be dead), so end this process; Crash runs in its own ":crash" process.
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }
}
