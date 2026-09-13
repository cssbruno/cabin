package com.cabin.hondacustom;

import android.content.ComponentName;
import android.content.Intent;
import android.os.*;

/** CameraAPServiceLib.odex wire contract; CameraId and CameraState each parcel one int. */
public final class CameraProtocol {
    public static final String PACKAGE = "com.mitsubishielectric.ada.appservice.camera";
    public static final String SERVICE = PACKAGE + ".CameraAPService";
    public static final String DESCRIPTOR = PACKAGE + ".ICameraAPService";
    public static final int REAR = 0, LANEWATCH = 4;
    public static final String PARKING_SENSOR = "CAMERA_PARKING_SENSOR";
    public static final String STATIC = "REAR_WIDE_CAMERA_STATIC", DYNAMIC = "REAR_WIDE_CAMERA_DYNAMIC";
    public static final String TURN = "LANEWATCH_TURN_SW", DURATION = "LANEWATCH_DISPLAY_TIME", GUIDE = "LANEWATCH_GUIDE_LINE";
    private final IBinder remote;
    public CameraProtocol(IBinder remote) throws RemoteException {
        if (!DESCRIPTOR.equals(remote.getInterfaceDescriptor())) throw new RemoteException("Unexpected camera service interface");
        this.remote = remote;
    }
    public static Intent settingsIntent() {
        String pkg = "com.mitsubishielectric.ada.app.camera";
        return new Intent().setComponent(new ComponentName(pkg, pkg + ".activity.CameraSettingActivity"));
    }
    public static String[] keys(int camera) {
        if (camera == REAR) return new String[]{STATIC, DYNAMIC};
        if (camera == LANEWATCH) return new String[]{TURN, DURATION, GUIDE};
        throw new IllegalArgumentException("Unsupported camera");
    }
    public static void validate(int camera, Bundle settings) {
        if (settings == null) throw new IllegalArgumentException("Missing camera settings");
        for (String key : keys(camera)) {
            Object value = settings.get(key);
            if (!(value instanceof Integer) || ((Integer)value != 0 && (Integer)value != 1))
                throw new IllegalArgumentException("Missing or unsupported camera value: " + key);
        }
        // OEM onClickPKSButton offers only 0/1; RearWideCameraManager defaults to 1.
        // getInt() alone would silently coerce a malformed optional value to zero.
        if (settings.containsKey(PARKING_SENSOR)) {
            Object value = settings.get(PARKING_SENSOR);
            if (!(value instanceof Integer) || ((Integer)value != 0 && (Integer)value != 1))
                throw new IllegalArgumentException("Unsupported parking-sensor view mode");
        }
    }
    private interface Writer { void write(Parcel data); }
    private interface Reader<T> { T read(Parcel reply) throws RemoteException; }
    private <T> T call(int code, Writer writer, Reader<T> reader) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (writer != null) writer.write(data);
            if (!remote.transact(code, data, reply, 0)) throw new RemoteException("Unsupported camera transaction " + code);
            reply.readException();
            return reader.read(reply);
        } finally { data.recycle(); reply.recycle(); }
    }
    private static void id(Parcel p, int camera) { keys(camera); p.writeInt(1); p.writeInt(camera); }
    private static void check(Parcel p) throws RemoteException {
        int result = p.readInt();
        if (result != 0) throw new RemoteException("Honda camera service rejected request (" + result + ")");
    }
    private static Bundle outBundle(Parcel p) throws RemoteException {
        check(p);
        if (p.readInt() != 1) throw new RemoteException("Camera service returned no settings");
        Bundle result = new Bundle(); result.readFromParcel(p); return result;
    }
    public boolean available(int camera) throws RemoteException {
        return call(0x0d, p -> id(p, camera), p -> {
            check(p);
            if (p.readInt() != 1) throw new RemoteException("Missing camera state");
            int state = p.readInt();
            if (state != 0 && state != 1) throw new RemoteException("Unsupported camera state " + state);
            return state == 0;
        });
    }
    public Bundle read(int camera) throws RemoteException { return call(0x22, p -> id(p, camera), CameraProtocol::outBundle); }
    public void write(int camera, Bundle values) throws RemoteException {
        validate(camera, values);
        call(0x23, p -> { id(p, camera); p.writeInt(1); values.writeToParcel(p, 0); }, p -> { check(p); return null; });
    }
    public Bundle defaults(int camera) throws RemoteException { return call(0x24, p -> id(p, camera), CameraProtocol::outBundle); }
    public boolean angleSensor() throws RemoteException {
        return call(0x5e, null, p -> {
            int value = p.readInt();
            if (value != 0 && value != 1) throw new RemoteException("Invalid angle sensor state");
            return value == 1;
        });
    }
    public void laneWatchSession(boolean active) throws RemoteException {
        call(0x85, p -> p.writeInt(active ? 1 : 0), p -> null);
    }
}
