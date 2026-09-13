package com.cabin.hondacustom;

import android.content.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class CameraIntegrationTest {
    private CameraClient client;
    private FakeCamera fake;
    static class FakeCamera extends Binder {
        final Map<Integer, Bundle> values = new HashMap<>();
        volatile int writes, defaults, enters, exits, reads, unbinds;
        volatile boolean rear = true, lane = true, angle = true, mismatch, reject;
        CountDownLatch entered, release;
        FakeCamera() {
            attachInterface(null, "com.mitsubishielectric.ada.appservice.camera.ICameraAPService");
            Bundle r = new Bundle(); r.putInt("REAR_WIDE_CAMERA_STATIC", 0); r.putInt("REAR_WIDE_CAMERA_DYNAMIC", 1); r.putInt("CAMERA_PARKING_SENSOR", 2); values.put(0, r);
            Bundle l = new Bundle(); l.putInt("LANEWATCH_TURN_SW", 1); l.putInt("LANEWATCH_DISPLAY_TIME", 0); l.putInt("LANEWATCH_GUIDE_LINE", 1); values.put(4, l);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            data.enforceInterface("com.mitsubishielectric.ada.appservice.camera.ICameraAPService");
            assertEquals(0, flags);
            if (code == 0x5e) { assertEquals(0, data.dataAvail()); reply.writeNoException(); reply.writeInt(angle ? 1 : 0); return true; }
            if (code == 0x85) {
                if (data.readInt() == 1) enters++; else exits++;
                assertEquals(0, data.dataAvail()); reply.writeNoException(); return true;
            }
            assertEquals(1, data.readInt());
            int camera = data.readInt(); assertTrue(camera == 0 || camera == 4);
            if (code == 0x23) {
                assertEquals(1, data.readInt()); Bundle received = new Bundle(); received.readFromParcel(data);
                assertEquals(0, data.dataAvail()); writes++;
                if (entered != null) { entered.countDown(); try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                if (!mismatch && !reject) values.put(camera, received);
                reply.writeNoException(); reply.writeInt(reject ? -2 : 0); return true;
            }
            assertEquals(0, data.dataAvail()); // Both getters and defaults have out-only arguments.
            reply.writeNoException(); reply.writeInt(0);
            if (code == 0x0d) { reply.writeInt(1); reply.writeInt((camera == 0 ? rear : lane) ? 0 : 1); return true; }
            if (code == 0x22 || code == 0x24) {
                if (code == 0x24) {
                    defaults++;
                    Bundle value = values.get(camera);
                    for (String key : value.keySet()) value.putInt(key, key.equals("LANEWATCH_DISPLAY_TIME") ? 0 : 1);
                } else reads++;
                reply.writeInt(1); values.get(camera).writeToParcel(reply, 0); return true;
            }
            return false;
        }
    }
    @Before public void setup() {
        fake = new FakeCamera();
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public boolean bindService(Intent intent, ServiceConnection c, int flags) {
                assertEquals("com.mitsubishielectric.ada.appservice.camera.CameraAPService", intent.getComponent().getClassName());
                c.onServiceConnected(intent.getComponent(), fake); return true;
            }
            @Override public void unbindService(ServiceConnection c) { fake.unbinds++; }
        };
        client = new CameraClient(context, () -> {});
    }
    @After public void close() { if (fake.release != null) fake.release.countDown(); client.destroy(); }
    private void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(5); }
        ShadowLooper.idleMainLooper(); assertTrue(client.phase + ": " + client.status, condition.getAsBoolean());
    }
    private void ready() throws Exception { client.connect(); await(() -> client.phase == CameraClient.Phase.READY); }
    @Test public void readsOnlyAvailableCamerasWithoutEnteringSession() throws Exception {
        fake.lane = false; ready(); assertEquals(1, client.values.size()); assertEquals(1, fake.reads);
        assertEquals(0, fake.writes); assertEquals(0, fake.enters); assertTrue(client.angleSensor);
    }
    @Test public void durationLabelsAreActualOemValues() {
        assertEquals("0 seconds", CameraActivity.valueLabel(CameraProtocol.DURATION, 0));
        assertEquals("2 seconds", CameraActivity.valueLabel(CameraProtocol.DURATION, 1));
    }
    @Test public void rearWritePreservesFreshSiblingAndParkingView() throws Exception {
        ready(); fake.values.get(0).putInt("REAR_WIDE_CAMERA_DYNAMIC", 0);
        client.change(0, CameraProtocol.STATIC, 1, true); await(() -> fake.writes == 1 && client.phase == CameraClient.Phase.READY);
        assertEquals(0, fake.values.get(0).getInt("REAR_WIDE_CAMERA_DYNAMIC"));
        assertEquals(2, fake.values.get(0).getInt("CAMERA_PARKING_SENSOR"));
        assertEquals(1, client.values.get(0).getInt(CameraProtocol.STATIC)); assertEquals(0, fake.enters);
        assertTrue(client.status.contains("service readback"));
    }
    @Test public void laneWriteClosesSessionAfterVerifiedReadback() throws Exception {
        ready(); client.change(4, CameraProtocol.DURATION, 1, true);
        await(() -> fake.writes == 1 && client.phase == CameraClient.Phase.READY);
        assertEquals(1, fake.enters); assertEquals(1, fake.exits);
        assertEquals(1, client.values.get(4).getInt(CameraProtocol.DURATION));
    }
    @Test public void parkedRangeAndAngleChecksPreventWrites() throws Exception {
        fake.angle = false; ready();
        client.change(0, CameraProtocol.STATIC, 1, false);
        client.change(0, CameraProtocol.STATIC, 3, true);
        client.change(0, CameraProtocol.DYNAMIC, 0, true);
        client.change(4, CameraProtocol.STATIC, 1, true);
        assertEquals(0, fake.writes); assertEquals(0, fake.enters);
    }
    @Test public void changingCapabilityIsCheckedAgainBeforeWrite() throws Exception {
        ready(); fake.angle = false; client.change(0, CameraProtocol.DYNAMIC, 0, true);
        await(() -> client.phase == CameraClient.Phase.DISCONNECTED); assertEquals(0, fake.writes);
    }
    @Test public void mismatchClearsValuesAndClosesLaneSession() throws Exception {
        ready(); fake.mismatch = true; client.change(4, CameraProtocol.DURATION, 1, true);
        await(() -> client.phase == CameraClient.Phase.DISCONNECTED);
        assertTrue(client.status.contains("did not confirm")); assertTrue(client.values.isEmpty()); assertEquals(1, fake.exits);
    }
    @Test public void rejectionClosesLaneSession() throws Exception {
        ready(); fake.reject = true; client.change(4, CameraProtocol.DURATION, 1, true);
        await(() -> client.phase == CameraClient.Phase.DISCONNECTED);
        assertTrue(client.status.contains("rejected")); assertEquals(1, fake.exits);
    }
    @Test public void defaultsUseOutOnlyBundleAndReadback() throws Exception {
        ready(); client.defaults(0, false); assertEquals(0, fake.defaults);
        client.defaults(0, true); await(() -> fake.defaults == 1 && client.phase == CameraClient.Phase.READY);
        assertEquals(1, client.values.get(0).getInt("CAMERA_PARKING_SENSOR"));
        assertTrue(client.status.contains("defaults confirmed"));
    }
    @Test public void timeoutIgnoresLateResultAndCleansSession() throws Exception {
        ready(); fake.entered = new CountDownLatch(1); fake.release = new CountDownLatch(1);
        client.change(4, CameraProtocol.DURATION, 1, true);
        assertTrue(fake.entered.await(3, TimeUnit.SECONDS));
        ShadowLooper.idleMainLooper(21, TimeUnit.SECONDS);
        assertEquals(CameraClient.Phase.DISCONNECTED, client.phase); assertTrue(client.status.contains("unknown outcome"));
        fake.release.countDown(); await(() -> fake.exits == 1);
        assertEquals(CameraClient.Phase.DISCONNECTED, client.phase); assertTrue(client.values.isEmpty()); assertEquals(1, fake.unbinds);
    }
    @Test public void incompleteBundleFailsClosedBeforeAnyWrite() throws Exception {
        fake.values.get(4).remove("LANEWATCH_GUIDE_LINE"); client.connect();
        await(() -> client.phase == CameraClient.Phase.DISCONNECTED); assertEquals(0, fake.writes); assertTrue(client.values.isEmpty());
    }
    @Test public void wrongServiceDescriptorCannotBeUsed() throws Exception {
        Binder bad = new Binder(); bad.attachInterface(null, "other.service");
        try { new CameraProtocol(bad); fail("Expected interface rejection"); } catch (RemoteException expected) { }
    }
    @Test public void cameraScreenLaunchesWithoutHondaService() {
        try (org.robolectric.android.controller.ActivityController<CameraActivity> activity = Robolectric.buildActivity(CameraActivity.class).setup()) { assertNotNull(activity.get()); }
    }
}
