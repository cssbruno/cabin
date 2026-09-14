package com.cabin.hardware;

import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class ReplacementServiceTest {
    @Test public void serviceStartsIdleAndReplayReportContainsHistoricalCanWithoutLiveState() throws Exception {
        ServiceController<ReplacementService> controller = Robolectric.buildService(ReplacementService.class).create();
        ReplacementService service = controller.get();
        try {
            JSONObject initial = new JSONObject(service.report()); assertEquals("IDLE",initial.getString("status")); assertFalse(initial.getBoolean("transmitEnabled"));
            service.replay(new ByteArrayInputStream(FytMcuCodec.encode(new byte[] {(byte)0xe3,0x24,1,0x40})),true);
            long deadline = System.nanoTime() + 2_000_000_000L;
            JSONObject report;
            do { report = new JSONObject(service.report()); if (report.getString("status").equals("COMPLETE")) break; Thread.sleep(5); } while (System.nanoTime() < deadline);
            assertEquals("user-selected-file", report.getString("source"));
            assertEquals("COMPLETE",report.getString("status")); assertEquals(1,report.getJSONObject("lastDecodedCanFieldsHistorical").getInt("37"));
            assertEquals(0,report.getJSONObject("fields").length());
            IBinder binder = service.onBind(new Intent()); Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try { data.writeInterfaceToken(ReplacementService.DESCRIPTOR); assertTrue(binder.transact(1,data,reply,0)); reply.readException(); assertFalse(new JSONObject(reply.readString()).getBoolean("hardwareValidated")); }
            finally { data.recycle(); reply.recycle(); }
        } finally { controller.destroy(); }
    }
    @Test public void builtInExampleReportIsExplicitlySynthetic() throws Exception {
        ServiceController<ReplacementService> controller = Robolectric.buildService(ReplacementService.class).create();
        try {
            ReplacementService service = controller.get();
            service.replayExample(new ByteArrayInputStream(new byte[0]));
            assertEquals("synthetic-example", new JSONObject(service.report()).getString("source"));
            service.replay(new ByteArrayInputStream(new byte[0]), false);
            assertEquals("user-selected-file", new JSONObject(service.report()).getString("source"));
        } finally { controller.destroy(); }
    }
    @Test public void commandPreviewsExposeJournalWithoutClaimingHardwareState() throws Exception {
        ServiceController<ReplacementService> controller=Robolectric.buildService(ReplacementService.class).create();
        try {
            ReplacementService service=controller.get(); service.previewRadio(3); service.previewEqualizer(20,1000,10,16);
            JSONObject report=new JSONObject(service.report());
            assertEquals(3,report.getJSONArray("commandJournal").length());
            assertEquals("MCU",report.getJSONArray("commandJournal").getJSONObject(0).getString("transport"));
            assertEquals("I2C 0x1c",report.getJSONArray("commandJournal").getJSONObject(1).getString("transport"));
            assertEquals("A40000",report.getJSONArray("commandJournal").getJSONObject(2).getString("frameHex"));
            assertFalse(report.getBoolean("transmitEnabled")); assertEquals(0,report.getJSONObject("fields").length());
            assertEquals(1,report.getJSONArray("activeToolkitModules").getInt(0));
        } finally { controller.destroy(); }
    }
    @Test public void strictToolkitRejectsCommandsAndPublishesNullWhenDataExpires() throws Exception {
        final Map<Integer,Integer> values = new HashMap<>(); values.put(37,1); Runnable[] listener = {null};
        ToolkitBridge bridge = new ToolkitBridge(new VehicleBackend() {
            public Map<Integer,Integer> snapshot() { return new HashMap<>(values); }
            public void onChange(Runnable callback) { listener[0] = callback; }
            public boolean command(int command,int[] args) { return false; }
            public void close() { }
        },true);
        IBinder module; Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try { data.writeInterfaceToken(ToolkitBridge.TOOLKIT); data.writeInt(7); bridge.transact(1,data,reply,0); reply.readException(); module = reply.readStrongBinder(); }
        finally { data.recycle(); reply.recycle(); }
        final int[][] received = {new int[] {999}};
        IBinder callback = new android.os.Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply,int flags) {
                data.enforceInterface(ToolkitBridge.CALLBACK); assertEquals(37,data.readInt()); received[0] = data.createIntArray(); data.createFloatArray(); data.createStringArray(); reply.writeNoException(); return true;
            }
        };
        data = Parcel.obtain(); reply = Parcel.obtain();
        try { data.writeInterfaceToken(ToolkitBridge.MODULE); data.writeStrongBinder(callback); data.writeInt(37); data.writeInt(1); module.transact(3,data,reply,0); reply.readException(); }
        finally { data.recycle(); reply.recycle(); }
        assertArrayEquals(new int[] {1},received[0]); values.clear(); listener[0].run(); assertNull(received[0]);
        data = Parcel.obtain(); reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ToolkitBridge.MODULE); data.writeInt(1); data.writeIntArray(new int[] {1}); data.writeFloatArray(null); data.writeStringArray(null);
            // Local Binder may propagate directly; a remote Binder marshals the exception into reply.
            try { module.transact(1,data,reply,0); reply.readException(); fail("Unsupported command acknowledged"); }
            catch (UnsupportedOperationException expected) { }
        } finally { data.recycle(); reply.recycle(); bridge.close(); }
    }
    @Test public void radioReceiveProfileIsExplicitAndReportsDecodedHistoryWithoutCommands() throws Exception {
        ServiceController<ReplacementService> controller=Robolectric.buildService(ReplacementService.class).create();
        try {
            ReplacementService service=controller.get();
            assertFalse(new JSONObject(service.report()).getBoolean("radioReceiveEnabled"));
            java.io.ByteArrayOutputStream capture=new java.io.ByteArrayOutputStream();
            for(int[] pair:new int[][] {{6,0},{1,1},{2,1},{3,0}})
                capture.write(FytMcuCodec.encode(new byte[] {1,3,(byte)pair[0],(byte)pair[1]}));
            service.replay(new ByteArrayInputStream(capture.toByteArray()),false,true);
            JSONObject report; long deadline=System.nanoTime()+2_000_000_000L;
            do { report=new JSONObject(service.report()); if(report.getString("status").equals("COMPLETE")) break; Thread.sleep(5); } while(System.nanoTime()<deadline);
            assertEquals("COMPLETE",report.getString("status"));
            assertTrue(report.getBoolean("radioReceiveEnabled")); assertFalse(report.getBoolean("radioPreviewEnabled"));
            assertEquals(10100,report.getJSONObject("lastDecodedRadioFieldsHistorical").getInt("1"));
            assertEquals(0,report.getJSONObject("radioFields").length());
            assertEquals(1,report.getJSONArray("activeToolkitModules").getInt(0));
            assertEquals(0,report.getJSONArray("commandJournal").length());
        } finally { controller.destroy(); }
    }
    @Test public void ownedLiveConnectionIsClosedWhenSwitchingBackToReplay() throws Exception {
        ServiceController<ReplacementService> controller=Robolectric.buildService(ReplacementService.class).create();
        java.util.concurrent.CountDownLatch stopped=new java.util.concurrent.CountDownLatch(1);
        final boolean[] transportClosed={false};
        com.cabin.hardware.replacement.McuTransport device=new com.cabin.hardware.replacement.McuTransport() {
            public int read(byte[] buffer) throws java.io.IOException {
                try { stopped.await(); return -1; }
                catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new java.io.IOException(ex); }
            }
            public void write(byte[] bytes) { fail("No commands should be sent on startup"); }
            public void close() { transportClosed[0]=true; stopped.countDown(); }
        };
        try {
            ReplacementService service=controller.get(); service.startOwnedConnection(device,true);
            JSONObject live=new JSONObject(service.report()); assertTrue(live.getBoolean("liveConnectionMode"));
            assertTrue(live.getBoolean("transmitEnabled")); assertEquals(2,live.getJSONArray("activeToolkitModules").length());
            assertThrows(IllegalStateException.class,()->service.previewRadio(3));
            assertThrows(IllegalStateException.class,()->service.previewEqualizer(20,1000,10,16));
            service.replay(new ByteArrayInputStream(new byte[0]),false);
            assertTrue(transportClosed[0]);
            JSONObject replay=new JSONObject(service.report()); assertFalse(replay.getBoolean("liveConnectionMode"));
            assertFalse(replay.getBoolean("transmitEnabled"));
        } finally { controller.destroy(); stopped.countDown(); }
    }
}
