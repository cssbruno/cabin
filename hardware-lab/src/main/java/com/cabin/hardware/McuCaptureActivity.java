package com.cabin.hardware;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Analyze explicitly selected capture files, independently of the simulated Binder service. */
public final class McuCaptureActivity extends Activity {
    private static final int OPEN = 51, SAVE = 52;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Button open, demo, save;
    private TextView status;
    private String report;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("MCU capture analysis"); title.setTextSize(26); column.addView(title);
        TextView note = new TextView(this);
        note.setText("Open a saved raw binary capture (up to 8 MiB). This checks messages against the Joying reference format. It does not connect to the car.\n");
        note.setTextSize(18); column.addView(note);
        demo = new Button(this); demo.setText("Analyze example data"); column.addView(demo);
        demo.setOnClickListener(v -> analyze(null));
        open = new Button(this); open.setText("Open capture file"); column.addView(open);
        open.setOnClickListener(v -> launch(new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream"), OPEN));
        save = new Button(this); save.setText("Save analysis report"); save.setEnabled(false); column.addView(save);
        save.setOnClickListener(v -> com.cabin.reports.ReportExport.show(this,"cabin-mcu-analysis.json",report));
        status = new TextView(this); status.setTextSize(17); column.addView(status);
        if (state != null) {
            report = state.getString("analysis");
            status.setText(report == null ? "Choose a capture or analyze the example data."
                : state.getString("status", ""));
            save.setEnabled(report != null);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(column); setContentView(scroll);
    }

    private void launch(Intent intent, int request) {
        try { startActivityForResult(intent, request); }
        catch (android.content.ActivityNotFoundException ex) { status.setText("No file picker available."); }
    }

    private void analyze(android.net.Uri uri) {
        report = null; busy(true); status.setText("Analyzing…");
        worker.execute(() -> {
            String result = null;
            String message;
            try (InputStream stream = uri == null ? getAssets().open("mcu-example.bin")
                    : getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new java.io.IOException("No input stream");
                JSONObject json = McuCaptureAnalyzer.analyze(stream);
                json.put("inputKind", uri == null ? "synthetic-example" : "user-selected-file");
                result = json.toString(2);
                message = (uri == null ? "EXAMPLE DATA\n" : "CAPTURE FILE\n")
                    + json.getLong("validFrames") + " valid frames\n"
                    + json.getLong("checksumFailures") + " checksum failures\n"
                    + json.getLong("invalidLengths") + " invalid lengths\n"
                    + json.getLong("incompleteBytes") + " incomplete bytes\n"
                    + json.getLong("discardedBytes") + " discarded bytes\n\n"
                    + "Framing only. Vehicle values and hardware compatibility are unverified.";
            } catch (Exception ex) { message = "Could not analyze this file. Use a readable raw binary capture no larger than 8 MiB."; }
            final String output = result, text = message;
            runOnUiThread(() -> { if (!isDestroyed()) { report = output; busy(false); status.setText(text); } });
        });
    }

    private void busy(boolean busy) {
        open.setEnabled(!busy); demo.setEnabled(!busy); save.setEnabled(!busy && report != null);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        if (request == OPEN) { analyze(uri); return; }
        if (request != SAVE || report == null) return;
        String output = report;
        busy(true);
        worker.execute(() -> {
            String message;
            try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) throw new java.io.IOException("No output stream");
                stream.write(output.getBytes(StandardCharsets.UTF_8));
                message = "Analysis report saved.";
            } catch (Exception ex) { message = "Save failed. Discard any incomplete report and try again."; }
            final String text = message;
            runOnUiThread(() -> { if (!isDestroyed()) { busy(false); status.setText(text); } });
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("analysis", report); state.putString("status", status.getText().toString());
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
