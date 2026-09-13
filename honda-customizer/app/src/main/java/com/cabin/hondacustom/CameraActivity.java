package com.cabin.hondacustom;

import android.app.*;
import android.content.ActivityNotFoundException;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.*;
import java.util.*;

/** Live settings for the rear and LaneWatch cameras reported by Honda's service. */
public final class CameraActivity extends Activity {
    private CameraClient client;
    private TextView status;
    private LinearLayout controls;
    private Button connect, refresh, disconnect;
    private CheckBox parked;
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String content, int size) {
        TextView view = new TextView(this); view.setText(content); view.setTextSize(size);
        view.setTextColor(Color.rgb(22, 36, 54)); view.setPadding(0, dp(6), 0, dp(10)); return view;
    }
    private Button button(LinearLayout body, String label, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setMinHeight(dp(54));
        view.setOnClickListener(v -> action.run()); body.addView(view); return view;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        client = new CameraClient(this, this::render);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(16), dp(20), dp(16)); body.setBackgroundColor(Color.rgb(243, 246, 249));
        TextView title = text("Honda camera settings", 26); title.setTypeface(null, Typeface.BOLD); body.addView(title);
        body.addView(text("Read and change the cameras fitted to this Honda. Values are checked with the original camera service.", 17));
        status = text("", 16); body.addView(status);
        connect = button(body, "Connect to Honda", () -> client.connect());
        refresh = button(body, "Refresh current values", () -> client.refresh());
        disconnect = button(body, "Disconnect", () -> client.disconnect());
        parked = new CheckBox(this); parked.setText("The vehicle is parked"); body.addView(parked);
        parked.setOnCheckedChangeListener((b, checked) -> render());
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); body.addView(controls);
        button(body, "Open original Honda camera screen", () -> {
            if (!parked.isChecked()) { status.setText("Confirm that the vehicle is parked first."); return; }
            client.disconnect();
            try { startActivity(CameraProtocol.settingsIntent()); }
            catch (ActivityNotFoundException | SecurityException e) { status.setText("The original Honda camera screen is unavailable on this head unit."); }
        });
        button(body, "Back to Honda Customizer", this::finish);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll); render();
    }
    private void render() {
        if (controls == null) return;
        status.setText(client.status);
        boolean ready = client.phase == CameraClient.Phase.READY;
        connect.setEnabled(client.phase == CameraClient.Phase.DISCONNECTED);
        refresh.setEnabled(ready); disconnect.setEnabled(client.phase != CameraClient.Phase.DISCONNECTED);
        controls.removeAllViews();
        for (int camera : new int[]{CameraProtocol.REAR, CameraProtocol.LANEWATCH}) {
            Bundle values = client.values.get(camera);
            if (values == null) continue;
            controls.addView(text(camera == CameraProtocol.REAR ? "Rear camera" : "LaneWatch", 21));
            for (String key : CameraProtocol.keys(camera)) {
                boolean supported = !CameraProtocol.DYNAMIC.equals(key) || client.angleSensor;
                int value = values.getInt(key);
                Button edit = button(controls, label(key) + ": " + (supported ? valueLabel(key, value) : "Steering angle sensor unavailable"), () -> choose(camera, key, value));
                edit.setEnabled(ready && parked.isChecked() && supported);
            }
            Button defaults = button(controls, "Restore " + (camera == CameraProtocol.REAR ? "rear camera" : "LaneWatch") + " defaults", () -> {
                String detail = camera == CameraProtocol.REAR
                        ? "Restore rear camera guidelines and the parking-sensor view mode to Honda defaults?"
                        : "Restore LaneWatch turn-signal activation, display time and reference lines to Honda defaults?";
                show(new AlertDialog.Builder(this).setTitle("Restore camera defaults?").setMessage(detail)
                        .setNegativeButton("Cancel", null).setPositiveButton("Restore", (d, w) -> client.defaults(camera, parked.isChecked())).create());
            });
            defaults.setEnabled(ready && parked.isChecked());
        }
    }
    static String label(String key) {
        if (CameraProtocol.STATIC.equals(key)) return "Fixed guidelines";
        if (CameraProtocol.DYNAMIC.equals(key)) return "Dynamic guidelines";
        if (CameraProtocol.TURN.equals(key)) return "Show with turn signal";
        if (CameraProtocol.DURATION.equals(key)) return "Display time after turn signal off";
        return "Reference lines";
    }
    static String valueLabel(String key, int value) {
        // Camera.apk STR_MM_04_06_03_RES_11/12 and LaneWatchSettingActivity$4 map 0/1.
        return CameraProtocol.DURATION.equals(key) ? (value == 1 ? "2 seconds" : "0 seconds") : (value == 1 ? "On" : "Off");
    }
    private void choose(int camera, String key, int value) {
        String[] labels = new String[]{valueLabel(key, 0), valueLabel(key, 1)};
        show(new AlertDialog.Builder(this).setTitle(label(key)).setSingleChoiceItems(labels, value, (dialog, selected) -> {
            dialog.dismiss();
            if (selected == value) return;
            show(new AlertDialog.Builder(this).setTitle("Apply camera setting?")
                    .setMessage(label(key) + ": " + labels[selected])
                    .setNegativeButton("Cancel", null).setPositiveButton("Apply", (d, w) -> client.change(camera, key, selected, parked.isChecked())).create());
        }).setNegativeButton("Cancel", null).create());
    }
    private void show(AlertDialog dialog) { dialogs.add(dialog); dialog.setOnDismissListener(d -> dialogs.remove(dialog)); dialog.show(); }
    @Override protected void onPause() {
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        parked.setChecked(false); client.disconnect(); super.onPause();
    }
    @Override protected void onDestroy() { client.destroy(); super.onDestroy(); }
}
