package com.cabin.hondacustom;

import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Off-main OEM camera settings with live capability checks and service readback. */
public final class CameraClient {
    public enum Phase { DISCONNECTED, CONNECTING, READING, READY, WRITING }
    public Phase phase = Phase.DISCONNECTED;
    public String status = "Connect to read the cameras fitted to this Honda.";
    public boolean angleSensor;
    public final Map<Integer, Bundle> values = new LinkedHashMap<>();
    private final Context context;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Session session;
    private Runnable timeout;
    private boolean destroyed;
    private final class Session implements ServiceConnection {
        volatile boolean active = true;
        boolean bound;
        CameraProtocol protocol;
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!valid(this)) return;
            phase = Phase.READING; status = "Reading camera capabilities…"; changed.run();
            execute(this, () -> { protocol = new CameraProtocol(binder); return snapshot(this); }, "Camera settings loaded.");
        }
        @Override public void onServiceDisconnected(ComponentName name) { if (valid(this)) fail("Honda camera service disconnected."); }
    }
    private static final class Snapshot {
        final Map<Integer, Bundle> values = new LinkedHashMap<>();
        boolean angle;
    }
    private interface Operation { Snapshot run() throws Exception; }
    public CameraClient(Context context, Runnable changed) { this.context = context; this.changed = changed; }
    private boolean valid(Session s) { return s != null && s.active && session == s && !destroyed; }
    private void ensure(Session s) throws RemoteException { if (!valid(s)) throw new RemoteException("Camera session ended"); }
    public void connect() {
        if (destroyed || phase != Phase.DISCONNECTED) return;
        Session s = new Session(); session = s;
        phase = Phase.CONNECTING; status = "Connecting to Honda camera service…"; changed.run(); arm(s);
        try {
            s.bound = context.bindService(new Intent().setComponent(new ComponentName(CameraProtocol.PACKAGE, CameraProtocol.SERVICE)), s, Context.BIND_AUTO_CREATE);
            if (!s.bound) fail("Honda camera service is unavailable on this head unit.");
            else if (!valid(s)) unbind(s);
        } catch (RuntimeException e) { fail("Cannot connect to Honda camera service: " + e.getMessage()); }
    }
    private Snapshot snapshot(Session s) throws Exception {
        Snapshot result = new Snapshot();
        for (int camera : new int[]{CameraProtocol.REAR, CameraProtocol.LANEWATCH}) {
            ensure(s);
            if (s.protocol.available(camera)) {
                Bundle settings = s.protocol.read(camera);
                CameraProtocol.validate(camera, settings);
                result.values.put(camera, settings);
            }
        }
        ensure(s);
        result.angle = result.values.containsKey(CameraProtocol.REAR) && s.protocol.angleSensor();
        return result;
    }
    public void refresh() {
        Session s = session;
        if (!valid(s) || phase != Phase.READY) return;
        phase = Phase.READING; status = "Refreshing camera settings…"; changed.run();
        execute(s, () -> snapshot(s), "Camera settings refreshed.");
    }
    public void change(int camera, String key, int value, boolean parked) {
        if (!canWrite(camera, parked)) return;
        if (!Arrays.asList(CameraProtocol.keys(camera)).contains(key) || (value != 0 && value != 1)) {
            status = "Unsupported camera setting value."; changed.run(); return;
        }
        if (CameraProtocol.DYNAMIC.equals(key) && !angleSensor) {
            status = "Dynamic guidelines require the steering angle sensor."; changed.run(); return;
        }
        mutate(camera, key, value, false);
    }
    public void defaults(int camera, boolean parked) { if (canWrite(camera, parked)) mutate(camera, null, 0, true); }
    private boolean canWrite(int camera, boolean parked) {
        if (!parked) { status = "Confirm that the vehicle is parked before changing camera settings."; changed.run(); return false; }
        return valid(session) && phase == Phase.READY && values.containsKey(camera);
    }
    private void mutate(int camera, String key, int value, boolean defaults) {
        Session s = session;
        phase = Phase.WRITING; status = "Applying camera settings and checking service readback…"; changed.run();
        execute(s, () -> {
            ensure(s);
            if (!s.protocol.available(camera)) throw new RemoteException("Camera is no longer available");
            if (CameraProtocol.DYNAMIC.equals(key) && !s.protocol.angleSensor()) throw new RemoteException("Steering angle sensor is unavailable");
            Bundle expected = s.protocol.read(camera);
            CameraProtocol.validate(camera, expected);
            if (!defaults) expected.putInt(key, value);
            boolean laneSession = false;
            try {
                ensure(s);
                if (camera == CameraProtocol.LANEWATCH) {
                    // Mark first so an ambiguous session-entry failure still schedules cleanup.
                    laneSession = true;
                    s.protocol.laneWatchSession(true);
                }
                ensure(s);
                if (defaults) expected = s.protocol.defaults(camera);
                else s.protocol.write(camera, expected);
                CameraProtocol.validate(camera, expected);
                ensure(s);
                Bundle actual = s.protocol.read(camera);
                CameraProtocol.validate(camera, actual);
                for (String candidate : CameraProtocol.keys(camera)) {
                    if (expected.getInt(candidate) != actual.getInt(candidate))
                        throw new RemoteException("Camera service readback did not confirm the requested settings");
                }
                if (expected.containsKey("CAMERA_PARKING_SENSOR")
                        && (!actual.containsKey("CAMERA_PARKING_SENSOR")
                        || expected.getInt("CAMERA_PARKING_SENSOR") != actual.getInt("CAMERA_PARKING_SENSOR")))
                    throw new RemoteException("Parking-sensor view readback did not confirm the requested settings");
                return snapshot(s);
            } finally { if (laneSession) s.protocol.laneWatchSession(false); }
        }, defaults ? "Camera defaults confirmed by service readback." : "Camera setting confirmed by service readback.");
    }
    private void execute(Session s, Operation operation, String success) {
        arm(s);
        worker.execute(() -> {
            try {
                ensure(s); Snapshot result = operation.run();
                main.post(() -> {
                    if (!valid(s)) return;
                    cancelTimeout(); values.clear(); values.putAll(result.values); angleSensor = result.angle;
                    phase = Phase.READY; status = result.values.isEmpty() ? "This Honda reports no supported rear or LaneWatch camera." : success;
                    changed.run();
                });
            } catch (Exception e) { main.post(() -> { if (valid(s)) fail("Camera request failed: " + e.getMessage() + ". Reconnect to check current values."); }); }
        });
    }
    private void arm(Session s) {
        cancelTimeout(); timeout = () -> { if (valid(s)) fail("Camera request timed out. Any in-flight change has an unknown outcome; reconnect to read current values."); };
        main.postDelayed(timeout, 20000);
    }
    private void cancelTimeout() { if (timeout != null) main.removeCallbacks(timeout); timeout = null; }
    private void unbind(Session s) { if (s.bound) { s.bound = false; try { context.unbindService(s); } catch (IllegalArgumentException ignored) {} } }
    private void fail(String message) { disconnect(); status = message; changed.run(); }
    public void disconnect() {
        Session s = session; session = null;
        if (s != null) { s.active = false; unbind(s); }
        cancelTimeout(); values.clear(); angleSensor = false; phase = Phase.DISCONNECTED;
        status = "Camera service disconnected."; changed.run();
    }
    public void destroy() { disconnect(); destroyed = true; worker.shutdown(); }
}
