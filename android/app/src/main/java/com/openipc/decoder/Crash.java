/*
 *
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * Crash.java — crash report activity displaying error details
 *
 */

package com.openipc.decoder;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

public class Crash extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView errorView = new TextView(this);
        errorView.setTextSize(12);
        errorView.setTextColor(Color.RED);
        errorView.setPadding(24, 12, 24, 12);

        Intent intent = getIntent();
        String error = (intent != null) ? intent.getStringExtra("error") : null;
        errorView.setText(error != null ? error : "Unknown error");

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(errorView);
        setContentView(scrollView);
    }
}
