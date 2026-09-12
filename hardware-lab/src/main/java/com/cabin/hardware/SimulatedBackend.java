package com.cabin.hardware;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit test fixture, not automatic detection of a real car. No device files or CAN writes. */
final class SimulatedBackend implements VehicleBackend {
    private final Map<Integer, Integer> fields = new LinkedHashMap<>();
    private Runnable listener = () -> {};
    private boolean closed;

    SimulatedBackend() {
        fields.put(1000, 1048874); // Demo profile using Cabin's legacy field layout.
        fields.put(24, 1); // A/C
        fields.put(29, 3); // Fan
        for (int code = 36; code <= 41; code++) fields.put(code, 0);
    }
    public synchronized Map<Integer, Integer> snapshot() { return new LinkedHashMap<>(fields); }
    public synchronized void onChange(Runnable next) { listener = next; }
    void toggleDoor() {
        Runnable notify;
        synchronized (this) {
            if (closed) return;
            fields.put(37, 1 - fields.get(37));
            notify = listener;
        }
        notify.run();
    }
    public boolean command(int command, int[] values) { return false; }
    public synchronized void close() { closed = true; fields.clear(); listener = () -> {}; }
}
