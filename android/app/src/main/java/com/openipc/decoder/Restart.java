/*
 *
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * Restart.java — starts the app again in a new process after Reset (#171)
 *
 */

package com.openipc.decoder;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/**
 * Runs in a process of its own (":restart"), like {@link Crash}: it ends the main process,
 * then opens the app, which therefore starts in a fresh process with nothing kept in memory.
 * Ending the main process from inside it right after startActivity() could take the new
 * activity down with it (Qodo on #172, seen on Android 15).
 */
public class Restart extends Activity {
    static final String EXTRA_PID = "pid";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pid = getIntent() != null ? getIntent().getIntExtra(EXTRA_PID, -1) : -1;
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid);
        Intent open = new Intent(this, Decoder.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(open);
        finish();
        // This helper process is not needed any more
        Runtime.getRuntime().exit(0);
    }
}
