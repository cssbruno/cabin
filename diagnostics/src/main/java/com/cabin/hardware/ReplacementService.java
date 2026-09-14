package com.cabin.hardware;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import com.cabin.hardware.replacement.*;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bound replacement bench. Replay is explicitly selected; startup opens no hardware. */
public final class ReplacementService extends Service {
    public static final String DESCRIPTOR = "com.cabin.hardware.IReplacementDiagnostics";
    private ReplacementRuntime runtime;
    private volatile LiveReplacementSession live;
    private ModuleToolkitBridge toolkit;
    private final CommandJournal journal = new CommandJournal();
    private boolean hondaConfigured, radioPreview, radioReceive;
    private final ScheduledExecutorService notifications = Executors.newSingleThreadScheduledExecutor();
    private volatile ModuleToolkitBridge publishedToolkit;
    public final class LocalBinder extends Binder {
        ReplacementService service() { return ReplacementService.this; }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(DESCRIPTOR); return true; }
            if ((code != 1 && code != 2) || flags != 0 || reply == null) return false;
            if (data.dataSize() > 1024) throw new IllegalArgumentException("Oversized diagnostics request");
            data.enforceInterface(DESCRIPTOR);
            if (data.dataAvail() != 0) throw new IllegalArgumentException("Trailing diagnostics data");
            synchronized (ReplacementService.this) {
                reply.writeNoException();
                if (code == 1) reply.writeString(report()); else reply.writeStrongBinder(toolkit);
            }
            return true;
        }
    }
    private final LocalBinder binder = new LocalBinder();
    @Override public void onCreate() {
        super.onCreate(); runtime = new ReplacementRuntime(null, SystemClock::elapsedRealtime);
        rebuildToolkit();
        notifications.scheduleWithFixedDelay(() -> { LiveReplacementSession session=live; if(session!=null) session.poll(); else { ModuleToolkitBridge current=publishedToolkit; if(current!=null) current.poll(); } }, 0, 250, TimeUnit.MILLISECONDS);
    }
    synchronized void replay(InputStream capture, boolean hondaReference) { replay(capture, hondaReference, false); }
    synchronized void replay(InputStream capture, boolean hondaReference, boolean radioDriverOne) {
        replay(capture, hondaReference, radioDriverOne, "user-selected-file");
    }
    synchronized void replayExample(InputStream capture) {
        replay(capture, true, true, "synthetic-example");
    }
    private synchronized void replay(InputStream capture, boolean hondaReference, boolean radioDriverOne, String source) {
        if (live != null) { live.close(); live = null; }
        if (toolkit != null) { toolkit.close(); toolkit = null; }
        publishedToolkit = null; runtime.close();
        runtime = new ReplacementRuntime(hondaReference ? new HondaCanDecoder(HondaCanDecoder.PROFILE, false, false) : null, SystemClock::elapsedRealtime, radioDriverOne);
        radioReceive=radioDriverOne; hondaConfigured=hondaReference; rebuildToolkit();
        runtime.start(new ReplayTransport(capture), source);
    }
    /** Internal deployment entry point: caller must transfer an already-owned transport.
     * No exported Binder request or replay UI can open devices or select this mode.
     */
    synchronized void startOwnedConnection(McuTransport ownedTransport, boolean hondaReference) {
        startOwnedConnection(ownedTransport,hondaReference,null);
    }
    synchronized void startOwnedConnection(McuTransport ownedTransport, boolean hondaReference, ModuleEndpoint ownedSound) {
        java.util.Objects.requireNonNull(ownedTransport);
        publishedToolkit = null;
        if (live != null) { live.close(); live = null; }
        if (toolkit != null) { toolkit.close(); toolkit = null; }
        runtime.close();
        hondaConfigured = hondaReference; radioReceive = true; radioPreview = false;
        try {
            live = new LiveReplacementSession(ownedTransport,
                hondaReference ? new HondaCanDecoder(HondaCanDecoder.PROFILE,false,false) : null,
                SystemClock::elapsedRealtime,ownedSound);
            toolkit = live.toolkit(); publishedToolkit = toolkit;
        } catch (RuntimeException ex) {
            ownedTransport.close(); if(ownedSound!=null) ownedSound.close();
            hondaConfigured = false; radioReceive = false;
            runtime = new ReplacementRuntime(null,SystemClock::elapsedRealtime);
            rebuildToolkit(); throw ex;
        }
    }
    private void rebuildToolkit() {
        if(toolkit!=null) toolkit.close();
        Map<Integer,ModuleEndpoint> modules=new java.util.TreeMap<>();
        ReplacementRuntime session=runtime;
        if(hondaConfigured) modules.put(7,new ModuleEndpoint() {
            public java.util.List<ModulePayload> cached(int field) {
                Integer value=session.snapshot().get(field);
                return value==null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(ModulePayload.integers(value));
            }
        });
        if(radioPreview || radioReceive) {
            final boolean previewEnabled = radioPreview;
            modules.put(1,new ModuleEndpoint() {
                private boolean closed;
                public synchronized void close() { closed = true; }
                public synchronized java.util.List<ModulePayload> cached(int field) {
                    if (closed) return java.util.Collections.emptyList();
                    return session.radioCached(field);
                }
                public synchronized void command(int code, ModulePayload payload) {
                    if (closed) throw new IllegalStateException("Radio module closed");
                    if (!previewEnabled) throw new UnsupportedOperationException("Radio command preview is disabled");
                    new RadioPreviewModule(journal).command(code,payload);
                }
            });
        }
        toolkit=new ModuleToolkitBridge(modules); publishedToolkit=toolkit;
    }
    synchronized void previewRadio(int command,int... args) {
        if (live != null) throw new IllegalStateException("End the live session before starting previews");
        if(!radioPreview) { radioPreview=true; rebuildToolkit(); }
        new RadioPreviewModule(journal).command(command,ModulePayload.integers(args));
    }
    synchronized void previewEqualizer(int band,int frequency,int qTenths,int gainStep) {
        if (live != null) throw new IllegalStateException("End the live session before starting previews");
        C7604Equalizer.Plan plan=C7604Equalizer.plan(band,frequency,qTenths,gainStep);
        journal.record(4,1,"I2C 0x1c",plan.packet());
        journal.record(4,1,"I2C 0x1c",new byte[] {(byte)0xa4,0,0});
    }
    synchronized String report() {
        try {
            JSONObject result = new JSONObject(live == null ? runtime.diagnostics() : live.diagnostics());
            result.put("liveConnectionMode", live != null);
            result.put("scope", "Development bench; not a deployable SYU replacement");
            result.put("capabilities", "CAN 7: reference receive subset; RADIO 1: driver-1 band/frequency receive subset; typed commands on an owned live connection or offline previews; SOUND 4: C7604 EQ calculation and packet library; MAIN 0: power observation only");
            result.put("activeToolkitModules", new org.json.JSONArray(toolkit.moduleIds()));
            result.put("rejectedRequests", toolkit.rejectedRequests());
            result.put("radioPreviewEnabled", radioPreview); result.put("radioReceiveEnabled", radioReceive);
            result.put("commandJournal", new org.json.JSONArray(journal.snapshot()));
            result.put("totalPreviewCommands", journal.total());
            result.put("unsupportedModules", "0 MAIN control, 1 RADIO, 2 BT, 3 DVD, 4 SOUND service, 5 IPOD, 6 TV, 8 TPMS, 9 DVR, 10 STEER, 11 CUSTOMER, 12 OBD, 13 TEST, 14 CANUPDATE, 15 AMP, 16 EMITTER, 17 GSENSOR, 18 GESTURE, 19 sensors");
            return result.toString(2);
        } catch (org.json.JSONException ex) { throw new IllegalStateException(ex); }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public synchronized void onDestroy() {
        publishedToolkit = null; notifications.shutdownNow();
        if (live != null) { live.close(); live = null; }
        if (toolkit != null) toolkit.close(); runtime.close(); super.onDestroy();
    }
}
