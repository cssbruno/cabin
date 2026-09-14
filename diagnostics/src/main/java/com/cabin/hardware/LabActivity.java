package com.cabin.hardware;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Cabin diagnostics using the installed FYT service; no synthetic fallback. */
public final class LabActivity extends Activity {
    private TextView status;
    private TextView inventoryStatus;
    private Button inspect, export;
    private String report;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final int EXPORT_REPORT = 41;
    private static final int EXPORT_FIRMWARE = 42;
    private LiveCanReceiver receiver;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (receiver != null) status.setText(receiver.report());
            ui.postDelayed(this, 500);
        }
    };
    private void connect() {
        if (receiver != null) receiver.close();
        receiver = new LiveCanReceiver(this);
        receiver.start();
        status.setText(receiver.report());
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);
        TextView heading = new TextView(this);
        heading.setText("Cabin diagnostics"); heading.setTextSize(28);
        column.addView(heading);
        TextView note = new TextView(this);
        note.setText("Live FYT CAN data from the installed vehicle service. Initial values may be cached. If the service is unavailable, no vehicle values are substituted.");
        column.addView(note);
        Button reconnect = new Button(this); reconnect.setText("Reconnect to FYT");
        reconnect.setOnClickListener(v -> connect()); column.addView(reconnect);
        Button saveLive = new Button(this); saveLive.setText("Save live CAN report");
        saveLive.setOnClickListener(v -> com.cabin.reports.ReportExport.show(this, "live-can-report.json",
            receiver != null ? receiver.report() : status.getText().toString())); column.addView(saveLive);
        Button debug = new Button(this); debug.setText("Live debug");
        debug.setOnClickListener(v -> com.cabin.reports.LiveDebugMenu.show(this)); column.addView(debug);
        status = new TextView(this); status.setTextSize(16); status.setTextIsSelectable(true);
        status.setText("Connecting to FYT…"); column.addView(status);
        Button replacement = new Button(this); replacement.setText("Offline test bench (simulated data)");
        replacement.setOnClickListener(v -> startActivity(new Intent(this, ReplacementActivity.class))); column.addView(replacement);
        Button capture = new Button(this); capture.setText("Analyze saved MCU capture");
        capture.setOnClickListener(v -> startActivity(new Intent(this, McuCaptureActivity.class)));
        column.addView(capture);
        inventoryStatus = new TextView(this);
        inventoryStatus.setText("Inspect this unit to identify its firmware and visible hardware interfaces.");
        inventoryStatus.setTextSize(18); inventoryStatus.setPadding(0, pad, 0, pad);
        column.addView(inventoryStatus);
        inspect = new Button(this); inspect.setText("Inspect this unit");
        inspect.setOnClickListener(v -> inspectUnit()); column.addView(inspect);
        export = new Button(this); export.setText("Export hardware report"); export.setEnabled(false);
        export.setOnClickListener(v -> com.cabin.reports.ReportExport.show(this,"cabin-hardware-report.json",report));
        column.addView(export);
        Button firmware = new Button(this);
        firmware.setText("Export SYU firmware bundle");
        firmware.setOnClickListener(v -> {
            Intent saveFirmware = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "cabin-syu-firmware.zip");
            try { startActivityForResult(saveFirmware, EXPORT_FIRMWARE); }
            catch (android.content.ActivityNotFoundException ex) { inventoryStatus.setText("No file picker available on this unit."); }
        });
        column.addView(firmware);
        if (saved != null) { report = saved.getString("hardwareReport"); export.setEnabled(report != null); }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true); scroll.addView(column);
        setContentView(scroll);
    }
    private void inspectUnit() {
        inspect.setEnabled(false); export.setEnabled(false); report = null;
        inventoryStatus.setText("Reading hardware and firmware details…");
        worker.execute(() -> {
            try {
                String next = HardwareInventory.collect(getApplicationContext()).toString(2);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    report = next; inspect.setEnabled(true); export.setEnabled(true);
                    inventoryStatus.setText("Inspection ready. Export the report for hardware-backend development. Compatibility is still unverified.");
                });
            } catch (Exception ex) {
                runOnUiThread(() -> { if (!isDestroyed()) {
                    inspect.setEnabled(true); inventoryStatus.setText("Inspection failed. Try again.");
                }});
            }
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_FIRMWARE && result == RESULT_OK && data != null && data.getData() != null) {
            final android.net.Uri destination = data.getData();
            inventoryStatus.setText("Exporting readable SYU firmware files…");
            worker.execute(() -> {
                String message;
                try (java.io.OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
                    if (stream == null) throw new java.io.IOException("No output stream");
                    FirmwareBundle.export(getApplicationContext(), stream);
                    message = "Bundle saved. Send the ZIP for hardware implementation. Missing files are listed in its report.";
                } catch (Exception ex) { message = "Export failed. Discard the incomplete ZIP and try again."; }
                final String text = message;
                runOnUiThread(() -> { if (!isDestroyed()) inventoryStatus.setText(text); });
            });
            return;
        }
        if (request != EXPORT_REPORT || result != RESULT_OK || data == null || data.getData() == null || report == null) return;
        final String content = report;
        final android.net.Uri uri = data.getData();
        worker.execute(() -> {
            String message;
            try (java.io.OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) throw new java.io.IOException("No output stream");
                stream.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                message = "Hardware report saved. Send this JSON file for the next backend step.";
            } catch (Exception ex) { message = "Could not save the report. Try another location."; }
            final String statusMessage = message;
            runOnUiThread(() -> { if (!isDestroyed()) inventoryStatus.setText(statusMessage); });
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (report != null) out.putString("hardwareReport", report);
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    @Override protected void onStart() {
        super.onStart();
        connect();
        ui.post(refresh);
    }
    @Override protected void onStop() {
        ui.removeCallbacks(refresh);
        if (receiver != null) receiver.close();
        receiver = null;
        super.onStop();
    }
}
