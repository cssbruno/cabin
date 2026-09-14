package com.cabin.hardware;

import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only vendor CAN monitor. Coordinator never performs vendor Binder calls. */
final class LiveCanReceiver implements AutoCloseable {
    static final long FRESH_MS = 30_000, DISCOVERY_MS = 10_000;
    private static final int[] FIELDS = {1000,0,1,2,3,4,5,10,11,12,13,14,16,18,19,
        20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,
        51,52,53,54,55,56,57,65,73,77,89,90,91,92,93,94,95,96,97,137,179,180,181};
    private final Context context;
    private final HandlerThread thread = new HandlerThread("LiveCAN");
    private final Handler worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService[] routes = {routeExecutor(), routeExecutor()};
    private final AtomicBoolean[] busy = {new AtomicBoolean(), new AtomicBoolean()};
    private final Map<Integer, int[]> values = new TreeMap<>();
    private final Map<Integer, Long> received = new HashMap<>();
    private volatile Session session;
    private int nextRoute;
    private long updates, lastUpdate;
    private Integer profile;
    private String status = "CONNECTING", error = "", component = "";
    private volatile String report = "Connecting to live CAN…";
    private final Runnable retry = this::bind;
    private final Runnable freshness = new Runnable() {
        public void run() {
            if (closed.get()) return;
            if (session != null && session.ready) publish();
            worker.postDelayed(this, 1000);
        }
    };
    private static ExecutorService routeExecutor() {
        return Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "LiveCAN-Binder"); t.setDaemon(true); return t; });
    }
    private final class Session implements ServiceConnection {
        final int route;
        final Set<Integer> registered = new HashSet<>(); // Owned by this route's executor.
        final Runnable timeout = () -> fail(this, new IllegalStateException("FYT discovery timed out"));
        IBinder module, callback;
        IBinder.DeathRecipient death;
        boolean bound, ready;
        Session(int route) { this.route = route; }
        boolean active() { return !closed.get() && session == this; }
        public void onServiceConnected(ComponentName name, IBinder binder) {
            worker.post(() -> {
                if (!active()) return;
                routes[route].execute(() -> {
                    try {
                        if (!active()) return;
                        module = route == 1 ? binder : getModule(binder);
                        if (!active()) return;
                        if (module == null || !ToolkitBridge.MODULE.equals(module.getInterfaceDescriptor()))
                            throw new RemoteException("Unexpected CAN Binder interface");
                        callback = createCallback(this);
                        death = () -> lost();
                        module.linkToDeath(death, 0);
                        for (int field : FIELDS) {
                            if (!active()) return;
                            registered.add(field);
                            subscription(module, callback, field, true);
                        }
                        worker.post(() -> {
                            if (!active()) return;
                            worker.removeCallbacks(timeout); ready = true; error = "";
                            status = updates == 0 ? "WAITING_FOR_CALLBACKS" : "RECEIVING_VENDOR_DATA";
                            publish();
                        });
                    } catch (Exception ex) { worker.post(() -> fail(this, ex)); }
                });
            });
        }
        private void lost() { worker.post(() -> fail(this, new RemoteException("Vehicle service disconnected"))); }
        public void onServiceDisconnected(ComponentName name) { lost(); }
        public void onBindingDied(ComponentName name) { lost(); }
        public void onNullBinding(ComponentName name) { lost(); }
    }
    LiveCanReceiver(Context context) {
        this.context = context.getApplicationContext();
        thread.start(); worker = new Handler(thread.getLooper());
    }
    void start() { worker.post(() -> {
        if (closed.get() || session != null) return;
        bind(); worker.removeCallbacks(freshness); worker.postDelayed(freshness, 1000);
    }); }
    String report() { return report; }
    private void bind() {
        if (closed.get() || session != null) return;
        int route = nextRoute;
        if (!busy[route].compareAndSet(false, true)) {
            route = 1 - route;
            if (!busy[route].compareAndSet(false, true)) {
                status = "DISCONNECTED"; error = "FYT calls are still blocked; no readings available"; publish();
                worker.postDelayed(retry, 2000); return;
            }
        }
        Session owner = new Session(route); session = owner;
        status = "CONNECTING"; updates = 0; lastUpdate = 0; profile = null; values.clear(); received.clear();
        Intent intent = new Intent(route == 1 ? "com.syu.ms.canbus" : "com.syu.ms.toolkit").setPackage("com.syu.ms");
        ComponentName target = new ComponentName("com.syu.ms", route == 1 ? "app.ModuleService" : "app.ToolkitService");
        try {
            android.content.pm.ResolveInfo info = context.getPackageManager().resolveService(intent, 0);
            ServiceInfo service = info == null ? null : info.serviceInfo;
            if (service != null && service.exported && service.enabled && "com.syu.ms".equals(service.packageName))
                target = new ComponentName(service.packageName, service.name);
        } catch (RuntimeException ignored) { }
        intent.setComponent(target); component = target.flattenToShortString(); publish();
        com.cabin.reports.DebugJournal.record("CAN", "bind", component);
        worker.postDelayed(owner.timeout, DISCOVERY_MS);
        try {
            owner.bound = context.bindService(intent, owner, Context.BIND_AUTO_CREATE);
            if (!owner.bound) throw new IllegalStateException("Vehicle service not found or unavailable: " + component);
        } catch (Exception ex) { fail(owner, ex); }
    }
    private Binder createCallback(Session owner) {
        return new Binder() {
            { attachInterface(null, ToolkitBridge.CALLBACK); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(ToolkitBridge.CALLBACK); return true; }
                if (code != 1) return false;
                data.enforceInterface(ToolkitBridge.CALLBACK);
                if (data.dataAvail() < 8 || data.dataAvail() > 4096) return false;
                int field = data.readInt(), count = data.readInt();
                if (count < -1 || count > 64 || count > data.dataAvail() / 4) return false;
                int[] sample = new int[Math.max(0, count)];
                for (int i = 0; i < sample.length; i++) sample[i] = data.readInt();
                long arrived = SystemClock.elapsedRealtime();
                worker.post(() -> {
                    if (!owner.active() || Arrays.stream(FIELDS).noneMatch(f -> f == field) || sample.length == 0) return;
                    if (field == 1000) {
                        if (profile != null && profile != sample[0]) { values.clear(); received.clear(); }
                        profile = sample[0];
                    }
                    values.put(field, sample); received.put(field, arrived); updates++; lastUpdate = arrived;
                    com.cabin.reports.DebugJournal.record("CAN", "callback", "field=" + field + "; values=" + Arrays.toString(sample));
                    if (owner.ready) status = "RECEIVING_VENDOR_DATA";
                    publish();
                });
                if (reply != null) reply.writeNoException();
                return true;
            }
        };
    }
    static IBinder getModule(IBinder toolkit) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ToolkitBridge.TOOLKIT); data.writeInt(7);
            if (!toolkit.transact(1, data, reply, 0) || reply.dataAvail() < 4) throw new RemoteException("CAN toolkit lookup failed");
            reply.readException(); return reply.readStrongBinder();
        } finally { data.recycle(); reply.recycle(); }
    }
    static void subscription(IBinder module, IBinder callback, int field, boolean add) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ToolkitBridge.MODULE); data.writeStrongBinder(callback); data.writeInt(field);
            if (add) data.writeInt(1); // Include the vendor's cached value on registration.
            if (!module.transact(add ? 3 : 4, data, reply, 0) || reply.dataAvail() < 4) throw new RemoteException("CAN subscription failed for field " + field);
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }
    private void publish() {
        try {
            long now = SystemClock.elapsedRealtime();
            JSONObject fields = new JSONObject(), historical = new JSONObject(), ages = new JSONObject();
            for (Map.Entry<Integer,int[]> entry : values.entrySet()) {
                long age = now - received.get(entry.getKey());
                String key = String.valueOf(entry.getKey());
                historical.put(key, new org.json.JSONArray(entry.getValue())); ages.put(key, age);
                if (age >= 0 && age < FRESH_MS) fields.put(key, new org.json.JSONArray(entry.getValue()));
            }
            String displayStatus = status;
            if (session != null && session.ready && updates > 0 && (now < lastUpdate || now - lastUpdate >= FRESH_MS))
                displayStatus = "STALE_VENDOR_DATA";
            report = new JSONObject().put("source", "live vendor CAN service (includes cached callbacks)")
                .put("debugEvents", com.cabin.reports.DebugJournal.snapshot())
                .put("androidApi", Build.VERSION.SDK_INT).put("firmwareBuild", Build.DISPLAY)
                .put("registeredFieldCount", session != null && session.ready ? FIELDS.length : 0)
                .put("status", displayStatus).put("service", component).put("error", error)
                .put("callbackCount", updates).put("lastCallbackElapsedMs", lastUpdate)
                .put("lastCallbackAgeMs", updates == 0 ? JSONObject.NULL : now - lastUpdate)
                .put("vehicleProfile", fields.has("1000") ? profile : JSONObject.NULL)
                .put("lastReceivedFields", fields).put("historicalFields", historical).put("fieldAgeMs", ages)
                .put("freshnessWindowMs", FRESH_MS).put("transmitEnabled", false)
                .put("note", "Values are vendor reports, not raw CAN frames. Cached callbacks do not prove current vehicle traffic. No callback for 30 seconds marks readings stale; unchanged fields may not be rebroadcast.").toString(2);
        } catch (org.json.JSONException ex) { report = ex.toString(); }
    }
    private void fail(Session owner, Exception ex) {
        if (!owner.active()) return;
        com.cabin.reports.DebugJournal.record("CAN", "connection_failed", component + ": " + ex);
        cleanup(); values.clear(); received.clear(); profile = null; lastUpdate = 0; updates = 0; status = "DISCONNECTED";
        error = ex.getClass().getSimpleName() + ": " + ex.getMessage(); publish();
        nextRoute = 1 - owner.route;
        if (!closed.get()) { worker.removeCallbacks(retry); worker.postDelayed(retry, 2000); }
    }
    private void cleanup() {
        Session old = session; session = null;
        if (old == null) return;
        worker.removeCallbacks(old.timeout);
        if (old.bound) try { context.unbindService(old); } catch (RuntimeException ignored) { }
        old.bound = false;
        // Serialize unregister after any in-flight register. A stuck route cannot block the other route or coordinator.
        routes[old.route].execute(() -> {
            try {
                if (old.module != null && old.callback != null)
                    for (int field : old.registered) try { subscription(old.module, old.callback, field, false); } catch (Exception ignored) { break; }
                if (old.module != null && old.death != null) try { old.module.unlinkToDeath(old.death, 0); } catch (RuntimeException ignored) { }
            } finally { busy[old.route].set(false); }
        });
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            cleanup(); values.clear(); received.clear(); profile = null; updates = 0; status = "CLOSED"; publish();
            for (ExecutorService route : routes) route.shutdown();
            thread.quitSafely();
        });
    }
}
