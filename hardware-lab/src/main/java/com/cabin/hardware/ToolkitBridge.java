package com.cabin.hardware;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Independently implemented subset used by Cabin: toolkit lookup and CAN subscriptions. */
final class ToolkitBridge extends Binder implements AutoCloseable {
    static final String TOOLKIT = "com.syu.ipc.IRemoteToolkit";
    static final String MODULE = "com.syu.ipc.IRemoteModule";
    static final String CALLBACK = "com.syu.ipc.IModuleCallback";
    static final int CAN_MODULE = 7;
    private final VehicleBackend backend;
    private final Map<IBinder, Subscription> clients = new HashMap<>();
    private Map<Integer, Integer> last;
    private boolean closed;
    private int rejectedCommands;
    private final Binder module = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(MODULE); return true; }
            if (code != 1 && code != 3 && code != 4) return false;
            bounded(data);
            data.enforceInterface(MODULE);
            synchronized (ToolkitBridge.this) { if (closed) return false; }
            if (code == 1) {
                if (data.dataAvail() < 16) throw new IllegalArgumentException("Truncated command");
                int command = data.readInt();
                int count = data.readInt();
                if (count < -1 || count > 64 || count > (data.dataAvail() - 8) / 4) throw new IllegalArgumentException("Invalid command array");
                int[] values = new int[Math.max(0, count)];
                for (int i = 0; i < values.length; i++) values[i] = data.readInt();
                // Only integer command payloads are supported by this prototype.
                int floats = data.readInt(), strings = data.readInt();
                if (floats < -1 || floats > 0 || strings < -1 || strings > 0) throw new IllegalArgumentException("Unsupported command payload");
                exhausted(data);
                if (!backend.command(command, values)) synchronized (ToolkitBridge.this) { rejectedCommands++; }
            } else {
                IBinder callback = data.readStrongBinder();
                if (data.dataAvail() < (code == 3 ? 8 : 4)) throw new IllegalArgumentException("Truncated subscription");
                int field = data.readInt();
                int cached = code == 3 ? data.readInt() : 0;
                exhausted(data);
                if (callback == null || field < 0 || field > 4095 || cached < 0 || cached > 1) throw new IllegalArgumentException("Invalid subscription");
                if (code == 3) {
                    register(callback, field);
                    Integer value = backend.snapshot().get(field);
                    if (cached == 1 && value != null) send(callback, field, value);
                } else unregister(callback, field);
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    ToolkitBridge(VehicleBackend backend) {
        this.backend = backend;
        last = backend.snapshot();
        backend.onChange(this::publish);
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(TOOLKIT); return true; }
        if (code != 1 || reply == null) return false;
        bounded(data);
        data.enforceInterface(TOOLKIT);
        if (data.dataAvail() != 4) throw new IllegalArgumentException("Invalid module lookup");
        int id = data.readInt();
        exhausted(data);
        reply.writeNoException();
        synchronized (this) { reply.writeStrongBinder(!closed && id == CAN_MODULE ? module : null); }
        return true;
    }
    private static void bounded(Parcel data) {
        if (data.dataSize() > 4096) throw new IllegalArgumentException("Oversized request");
    }
    private static void exhausted(Parcel data) {
        if (data.dataAvail() != 0) throw new IllegalArgumentException("Trailing request data");
    }
    private final class Subscription implements IBinder.DeathRecipient {
        final IBinder binder;
        final Set<Integer> fields = new HashSet<>();
        Subscription(IBinder binder) { this.binder = binder; }
        @Override public void binderDied() { remove(binder); }
    }
    private synchronized void register(IBinder binder, int field) throws RemoteException {
        if (closed) return;
        Subscription sub = clients.get(binder);
        if (sub == null) {
            if (clients.size() >= 32) throw new IllegalStateException("Too many clients");
            sub = new Subscription(binder);
            binder.linkToDeath(sub, 0);
            clients.put(binder, sub);
        }
        if (sub.fields.size() >= 128 && !sub.fields.contains(field)) throw new IllegalStateException("Too many fields");
        sub.fields.add(field);
    }
    private synchronized void unregister(IBinder binder, int field) {
        Subscription sub = clients.get(binder);
        if (sub != null && sub.fields.remove(field) && sub.fields.isEmpty()) remove(binder);
    }
    private synchronized void remove(IBinder binder) {
        Subscription sub = clients.remove(binder);
        if (sub != null) binder.unlinkToDeath(sub, 0);
    }
    private void send(IBinder binder, int field, int value) {
        synchronized (this) {
            Subscription sub = clients.get(binder);
            if (closed || sub == null || !sub.fields.contains(field)) return;
        }
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInterfaceToken(CALLBACK);
            parcel.writeInt(field);
            parcel.writeIntArray(new int[] {value});
            parcel.writeFloatArray(null);
            parcel.writeStringArray(null);
            if (!binder.transact(1, parcel, null, IBinder.FLAG_ONEWAY)) remove(binder);
        } catch (RemoteException | RuntimeException ex) { remove(binder); }
        finally { parcel.recycle(); }
    }
    private void publish() {
        Map<Integer, Integer> current = backend.snapshot();
        Map<Integer, Integer> changes = new HashMap<>();
        ArrayList<IBinder> targets;
        synchronized (this) {
            if (closed) return;
            current.forEach((field, value) -> { if (!value.equals(last.get(field))) changes.put(field, value); });
            last = current;
            targets = new ArrayList<>(clients.keySet());
        }
        changes.forEach((field, value) -> targets.forEach(target -> send(target, field, value)));
    }
    void toggleSimulatedDoor() {
        if (backend instanceof SimulatedBackend) ((SimulatedBackend) backend).toggleDoor();
    }
    synchronized int rejectedCommands() { return rejectedCommands; }
    synchronized int subscriberCount() { return clients.size(); }
    @Override public synchronized void close() {
        closed = true;
        for (IBinder binder : new ArrayList<>(clients.keySet())) remove(binder);
        backend.close();
        last.clear();
    }
}
