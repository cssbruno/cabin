package com.cabin.hardware;

import android.app.Activity;
import android.os.*;
import android.widget.*;

/** Read-only receive screen, kept separate from synthetic replay and command previews. */
public final class LiveCanActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LiveCanReceiver receiver;
    private TextView output;
    private final Runnable refresh = new Runnable() {
        public void run() { if (receiver != null) output.setText(receiver.report()); ui.postDelayed(this, 500); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density); column.setPadding(pad,pad,pad,pad);
        TextView title = new TextView(this); title.setText("Live CAN receive"); title.setTextSize(24); column.addView(title);
        Button debug = new Button(this); debug.setText("Live debug"); column.addView(debug);
        debug.setOnClickListener(v -> com.cabin.reports.LiveDebugMenu.show(this));
        TextView note = new TextView(this); note.setText("Attempts to receive CAN data through the installed FYT service. Check the status below for connection and freshness. Initial values may come from the service’s cache."); column.addView(note);
        Button retry = new Button(this); retry.setText("Reconnect"); column.addView(retry);
        retry.setOnClickListener(v -> connect());
        Button save = new Button(this); save.setText("Save live CAN report"); column.addView(save);
        save.setOnClickListener(v -> com.cabin.reports.ReportExport.show(this,"live-can-report.json",
            receiver != null ? receiver.report() : output.getText().toString()));
        output = new TextView(this); output.setTypeface(android.graphics.Typeface.MONOSPACE); output.setTextIsSelectable(true);
        column.addView(output); ScrollView scroll = new ScrollView(this); scroll.addView(column); setContentView(scroll);
    }
    private void connect() {
        if (receiver != null) receiver.close();
        receiver = new LiveCanReceiver(this); receiver.start();
    }
    @Override protected void onStart() { super.onStart(); connect(); ui.post(refresh); }
    @Override protected void onStop() {
        ui.removeCallbacks(refresh); if (receiver != null) receiver.close(); receiver = null; super.onStop();
    }
}
