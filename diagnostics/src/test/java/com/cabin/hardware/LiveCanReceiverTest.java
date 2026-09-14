package com.cabin.hardware;

import android.content.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class LiveCanReceiverTest {
    private LiveCanReceiver receiver;
    private HandlerThread thread;
    private FakeContext context;
    @Before public void setup() throws Exception {
        context = new FakeContext(RuntimeEnvironment.getApplication());
        receiver = new LiveCanReceiver(context);
        java.lang.reflect.Field field = LiveCanReceiver.class.getDeclaredField("thread"); field.setAccessible(true);
        thread = (HandlerThread) field.get(receiver);
    }
    private void drain() { Looper looper = thread.getLooper(); if (looper != null) shadowOf(looper).idle(); }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) { drain(); Thread.sleep(5); }
        drain(); assertTrue(receiver.report(), condition.getAsBoolean());
    }
    private void receiving() throws Exception { await(() -> receiver.report().contains("RECEIVING_VENDOR_DATA")); }
    @After public void close() throws Exception {
        context.release.countDown(); context.module.release.countDown(); receiver.close(); thread.join(1000); assertFalse(thread.isAlive());
    }
    @Test public void receivesCachedThenChangedFieldsAndUnsubscribes() throws Exception {
        receiver.start(); receiving();
        assertTrue(receiver.report(), receiver.report().contains("RECEIVING_VENDOR_DATA"));
        assertTrue(receiver.report(), receiver.report().contains("1048874"));
        context.module.emit(1, 1); drain();
        org.json.JSONObject report = new org.json.JSONObject(receiver.report());
        assertEquals(1, report.getJSONObject("lastReceivedFields").getJSONArray("1").getInt(0));
        assertFalse(report.getBoolean("transmitEnabled"));
        assertEquals(0, context.module.commands);
        receiver.close(); thread.join(1000);
        await(() -> context.module.subscriptions.isEmpty());
        assertEquals(1, context.unbinds);
    }
    @Test public void fallsBackToDirectServiceWhenToolkitUnavailable() throws Exception {
        context.rejectToolkit = true;
        receiver.start(); drain();
        shadowOf(thread.getLooper()).idleFor(3, java.util.concurrent.TimeUnit.SECONDS); drain();
        receiving(); assertTrue(context.actions.contains("com.syu.ms.canbus"));
        assertTrue(receiver.report(), receiver.report().contains("RECEIVING_VENDOR_DATA"));
        assertEquals(0, context.module.commands);
    }
    @Test public void disconnectClearsReadingsAndRejectsOldCallback() throws Exception {
        receiver.start(); receiving();
        IBinder stale = context.module.callback;
        context.connection.onServiceDisconnected(new ComponentName("com.syu.ms", "app.ToolkitService")); drain();
        Module.send(stale,1,1); drain();
        org.json.JSONObject report = new org.json.JSONObject(receiver.report());
        assertEquals("DISCONNECTED", report.getString("status"));
        assertEquals(0, report.getJSONObject("lastReceivedFields").length());
    }
    @Test public void stalledToolkitFallsBackAndLateResultCannotReplaceDirectSession() throws Exception {
        context.blockToolkit = true;
        receiver.start(); drain(); drain();
        assertTrue(context.entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        shadowOf(thread.getLooper()).idleFor(13, java.util.concurrent.TimeUnit.SECONDS);
        receiving();
        assertTrue(receiver.report().contains("app.ModuleService"));
        context.release.countDown(); Thread.sleep(30); drain();
        assertTrue(receiver.report().contains("app.ModuleService"));
        assertEquals(0, context.module.commands);
    }
    @Test public void closeReturnsWhileVendorCallIsBlocked() throws Exception {
        context.blockToolkit = true;
        receiver.start(); drain(); drain();
        assertTrue(context.entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        receiver.close(); thread.join(1000);
        assertFalse(thread.isAlive()); assertEquals(1, context.unbinds);
        assertTrue(receiver.report().contains("CLOSED"));
    }
    @Test public void oldFieldsExpireEvenWhenAnotherFieldKeepsUpdating() throws Exception {
        receiver.start(); receiving();
        shadowOf(thread.getLooper()).idleFor(31, java.util.concurrent.TimeUnit.SECONDS); drain();
        org.json.JSONObject stale = new org.json.JSONObject(receiver.report());
        assertEquals("STALE_VENDOR_DATA", stale.getString("status"));
        assertEquals(0, stale.getJSONObject("lastReceivedFields").length());
        assertTrue(stale.getJSONObject("historicalFields").has("1"));
        context.module.emit(1, 1); drain();
        org.json.JSONObject fresh = new org.json.JSONObject(receiver.report());
        assertEquals("RECEIVING_VENDOR_DATA", fresh.getString("status"));
        assertTrue(fresh.getJSONObject("lastReceivedFields").has("1"));
        assertFalse(fresh.getJSONObject("lastReceivedFields").has("1000"));
        assertTrue(fresh.isNull("vehicleProfile"));
    }
    @Test public void stalledSubscriptionStillTimesOutAndUsesDirectRoute() throws Exception {
        context.module.blockRegistration = true; context.separateDirect = true;
        receiver.start(); drain(); drain();
        assertTrue(context.module.entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        shadowOf(thread.getLooper()).idleFor(13, java.util.concurrent.TimeUnit.SECONDS);
        receiving(); assertTrue(receiver.report().contains("app.ModuleService"));
        context.module.release.countDown();
        await(() -> context.module.subscriptions.isEmpty());
        assertTrue(context.directModule.subscriptions.contains(1));
    }
    @Test public void stalledUnregisterDoesNotBlockDisconnectOrFallback() throws Exception {
        receiver.start(); receiving();
        context.module.blockUnregister = true; context.separateDirect = true;
        context.connection.onServiceDisconnected(new ComponentName("com.syu.ms", "app.ToolkitService")); drain();
        assertTrue(context.module.entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(receiver.report().contains("DISCONNECTED"));
        shadowOf(thread.getLooper()).idleFor(3, java.util.concurrent.TimeUnit.SECONDS);
        receiving(); assertTrue(receiver.report().contains("app.ModuleService"));
        assertEquals(1, context.unbinds);
    }
    private static class FakeContext extends ContextWrapper {
        final Module module = new Module(), directModule = new Module();
        boolean separateDirect;
        boolean rejectToolkit, blockToolkit;
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        int unbinds;
        ServiceConnection connection;
        final List<String> actions = new ArrayList<>();
        final Binder toolkit = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                data.enforceInterface(ToolkitBridge.TOOLKIT); assertEquals(1,code); assertEquals(7,data.readInt());
                if (blockToolkit) {
                    entered.countDown();
                    try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
                reply.writeNoException(); reply.writeStrongBinder(module); return true;
            }
        };
        FakeContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            actions.add(intent.getAction()); this.connection = connection;
            if (rejectToolkit && "com.syu.ms.toolkit".equals(intent.getAction())) return false;
            connection.onServiceConnected(intent.getComponent(), "com.syu.ms.canbus".equals(intent.getAction()) ? (separateDirect ? directModule : module) : toolkit); return true;
        }
        @Override public void unbindService(ServiceConnection connection) { unbinds++; }
    }
    private static class Module extends Binder {
        final Set<Integer> subscriptions = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile IBinder callback;
        int commands;
        boolean blockRegistration, blockUnregister;
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Module() { attachInterface(null,ToolkitBridge.MODULE); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            data.enforceInterface(ToolkitBridge.MODULE);
            if (code == 1) { commands++; fail("Receive monitor must never send a CAN command"); }
            if ((blockRegistration && code == 3) || (blockUnregister && code == 4)) {
                entered.countDown();
                try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            callback = data.readStrongBinder(); int field = data.readInt();
            if (code == 3) {
                assertEquals(1,data.readInt()); subscriptions.add(field);
                if (field == 1000) emit(field,1048874);
                if (field == 1) emit(field,0);
            } else { assertEquals(4,code); subscriptions.remove(field); }
            reply.writeNoException(); return true;
        }
        void emit(int field, int value) throws RemoteException { send(callback,field,value); }
        static void send(IBinder callback, int field, int value) throws RemoteException {
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(ToolkitBridge.CALLBACK); data.writeInt(field); data.writeIntArray(new int[]{value});
                data.writeFloatArray(null); data.writeStringArray(null);
                assertTrue(callback.transact(1,data,null,IBinder.FLAG_ONEWAY));
            } finally { data.recycle(); }
        }
    }
}
