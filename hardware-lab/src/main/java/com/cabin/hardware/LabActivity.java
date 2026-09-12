package com.cabin.hardware;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small test client exercising the Binder interface, with clearly simulated data. */
public final class LabActivity extends Activity {
    private TextView status;
    private TextView inventoryStatus;
    private Button inspect, export;
    private String report;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final int EXPORT_REPORT = 41;
    private static final int EXPORT_FIRMWARE = 42;
    private Button toggle;
    private boolean bound;
    private IBinder module;
    private ToolkitBridge localBridge;
    private final Binder callback = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(ToolkitBridge.CALLBACK); return true; }
            if (code != 1) return false;
            data.enforceInterface(ToolkitBridge.CALLBACK);
            if (data.dataSize() > 4096 || data.dataAvail() < 8) return false;
            int field = data.readInt();
            int count = data.readInt();
            if (field == 37 && count == 1 && data.dataAvail() >= 4) {
                int value = data.readInt();
                runOnUiThread(() -> status.setText("Simulated front-left door: " + (value == 1 ? "Open" : "Closed")));
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            localBridge = binder instanceof ToolkitBridge ? (ToolkitBridge) binder : null;
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(ToolkitBridge.TOOLKIT);
                data.writeInt(ToolkitBridge.CAN_MODULE);
                if (!binder.transact(1, data, reply, 0)) throw new IllegalStateException("Toolkit unavailable");
                reply.readException();
                module = reply.readStrongBinder();
                if (module == null) throw new IllegalStateException("CAN module unavailable");
                subscribe(true);
                toggle.setEnabled(localBridge != null);
            } catch (RemoteException | RuntimeException ex) {
                status.setText("Simulator connection failed");
                toggle.setEnabled(false);
            } finally { data.recycle(); reply.recycle(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            module = null; localBridge = null;
            toggle.setEnabled(false);
            status.setText("Simulator disconnected");
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);
        TextView heading = new TextView(this);
        heading.setText("Cabin Hardware Lab"); heading.setTextSize(28);
        column.addView(heading);
        TextView note = new TextView(this);
        note.setText("SIMULATION ONLY\nPrototype service interface. No real CAN, MCU or DSP connection.");
        note.setTextSize(18); note.setPadding(0, pad, 0, pad);
        column.addView(note);
        status = new TextView(this); status.setTextSize(22); status.setText("Connecting to simulator…");
        column.addView(status);
        toggle = new Button(this); toggle.setText("Toggle simulated door"); toggle.setMinHeight((int) (56 * getResources().getDisplayMetrics().density));
        toggle.setEnabled(false);
        toggle.setOnClickListener(v -> { if (localBridge != null) localBridge.toggleSimulatedDoor(); });
        column.addView(toggle);
        inventoryStatus = new TextView(this);
        inventoryStatus.setText("Inspect this unit to identify its firmware and visible hardware interfaces.");
        inventoryStatus.setTextSize(18); inventoryStatus.setPadding(0, pad, 0, pad);
        column.addView(inventoryStatus);
        inspect = new Button(this); inspect.setText("Inspect this unit");
        inspect.setOnClickListener(v -> inspectUnit()); column.addView(inspect);
        export = new Button(this); export.setText("Export hardware report"); export.setEnabled(false);
        export.setOnClickListener(v -> {
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "cabin-hardware-report.json");
            try { startActivityForResult(save, EXPORT_REPORT); }
            catch (android.content.ActivityNotFoundException ex) { inventoryStatus.setText("No file picker available on this unit."); }
        });
        column.addView(export);
        Button firmware = new Button(this);
        firmware.setText("Export SYU firmware bundle");
        firmware.setOnClickListener(v -> {
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "cabin-syu-firmware.zip");
            try { startActivityForResult(save, EXPORT_FIRMWARE); }
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
        bound = bindService(new Intent(this, ToolkitService.class), connection, BIND_AUTO_CREATE);
        if (!bound) status.setText("Simulator unavailable");
    }
    private void subscribe(boolean register) throws RemoteException {
        if (module == null) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ToolkitBridge.MODULE);
            data.writeStrongBinder(callback);
            data.writeInt(37);
            if (register) data.writeInt(1);
            if (!module.transact(register ? 3 : 4, data, reply, 0)) throw new RemoteException("Subscription unavailable");
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }
    @Override protected void onStop() {
        try { subscribe(false); } catch (RemoteException | RuntimeException ignored) { }
        if (bound) unbindService(connection);
        bound = false; module = null; localBridge = null; toggle.setEnabled(false);
        super.onStop();
    }
}
