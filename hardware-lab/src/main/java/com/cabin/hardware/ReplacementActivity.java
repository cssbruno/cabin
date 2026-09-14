package com.cabin.hardware;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

/** Replay UI owns no live device permissions or vendor service lifecycle. */
public final class ReplacementActivity extends Activity {
    private static final int OPEN = 71, SAVE = 72;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private ReplacementService service;
    private TextView output;
    private CheckBox honda, radioReceive;
    private Button example, open, save;
    private final java.util.List<Button> radioButtons = new java.util.ArrayList<>();
    private boolean bound;
    private final Runnable refresh = new Runnable() {
        public void run() { if (service != null) output.setText(service.report()); handler.postDelayed(this, 500); }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ReplacementService.LocalBinder) binder).service();
            example.setEnabled(true); open.setEnabled(true); save.setEnabled(true); for(Button button:radioButtons) button.setEnabled(true);
        }
        public void onServiceDisconnected(ComponentName name) { service = null; example.setEnabled(false); open.setEnabled(false); save.setEnabled(false); for(Button button:radioButtons) button.setEnabled(false); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density); column.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setText("Independent replacement bench"); title.setTextSize(24); column.addView(title);
        TextView note = new TextView(this); note.setText("Replay only. No hardware commands. Imported captures need a known direction and matching CAN profile."); column.addView(note);
        Button live = new Button(this); live.setText("Receive live CAN from vehicle"); column.addView(live);
        live.setOnClickListener(v -> startActivity(new Intent(this, LiveCanActivity.class)));
        honda = new CheckBox(this); honda.setText("Decode as reference Honda 0x10012a (normal door/temp order)"); column.addView(honda);
        radioReceive = new CheckBox(this); radioReceive.setText("Decode radio feedback as reference MCU driver 1"); column.addView(radioReceive);
        example = button(column, "Run built-in example"); example.setOnClickListener(v -> {
            try { service.replayExample(getAssets().open("replacement-example.bin")); }
            catch (IOException | RuntimeException ex) { error(ex); }
        });
        open = button(column, "Open MCU receive capture"); open.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE), OPEN));
        save = button(column, "Export debug report"); save.setOnClickListener(v -> com.cabin.reports.ReportExport.show(this,"replacement-report.json",service.report()));
        TextView radioNote = new TextView(this); radioNote.setText("Radio driver 1 command preview — bytes are recorded only. This does not tune the car radio."); column.addView(radioNote);
        LinearLayout radioRow = new LinearLayout(this); column.addView(radioRow);
        String[] labels = {"Step −", "Step +", "Seek −", "Seek +"}; int[] codes={4,3,6,5};
        for(int i=0;i<labels.length;i++) {
            Button button=new Button(this); button.setText(labels[i]); button.setEnabled(false); radioButtons.add(button); radioRow.addView(button,new LinearLayout.LayoutParams(0,-2,1));
            final int command=codes[i]; button.setOnClickListener(v -> { if(service!=null) { try { service.previewRadio(command); } catch(RuntimeException ex) { error(ex); } } });
        }
        Button eqPreview=new Button(this); eqPreview.setText("Preview EQ example: band 20 / 1000 Hz / Q 1 / +6"); eqPreview.setEnabled(false); radioButtons.add(eqPreview); column.addView(eqPreview);
        eqPreview.setOnClickListener(v -> { if(service!=null) { try { service.previewEqualizer(20,1000,10,16); } catch(RuntimeException ex) { error(ex); } } });
        output = new TextView(this); output.setTextIsSelectable(true); output.setTypeface(android.graphics.Typeface.MONOSPACE);
        column.addView(output); ScrollView scroll = new ScrollView(this); scroll.addView(column); setContentView(scroll);
        bound = bindService(new Intent(this, ReplacementService.class), connection, BIND_AUTO_CREATE);
        handler.post(refresh);
    }
    private Button button(LinearLayout column, String text) { Button button = new Button(this); button.setText(text); button.setEnabled(false); column.addView(button); return button; }
    private void error(Exception ex) { Toast.makeText(this, ex.getClass().getSimpleName() + ": " + ex.getMessage(), Toast.LENGTH_LONG).show(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || service == null) return;
        android.net.Uri uri = data.getData(); boolean decode = honda.isChecked(), decodeRadio = radioReceive.isChecked(); String report = service.report();
        files.execute(() -> {
            try {
                if (request == OPEN) {
                    // Materialize at most 8 MiB off the UI thread, avoiding an unbounded provider read in the MCU loop.
                    byte[] capture;
                    try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        if (input == null) throw new IOException("Cannot open capture");
                        byte[] chunk = new byte[4096]; int count;
                        while ((count = input.read(chunk)) != -1) { if (bytes.size() + count > 8 * 1024 * 1024) throw new IOException("Capture exceeds 8 MiB"); bytes.write(chunk, 0, count); }
                        capture = bytes.toByteArray();
                    }
                    runOnUiThread(() -> { if (service != null && !isDestroyed()) { try { service.replay(new ByteArrayInputStream(capture), decode, decodeRadio); } catch (RuntimeException ex) { error(ex); } } });
                } else if (request == SAVE) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) { if (out == null) throw new IOException("Cannot save report"); out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                }
            } catch (Exception ex) { runOnUiThread(() -> { if (!isDestroyed()) error(ex); }); }
        });
    }
    @Override protected void onDestroy() { handler.removeCallbacks(refresh); files.shutdownNow(); if (bound) unbindService(connection); service = null; super.onDestroy(); }
}
