package com.cabin.hardware;

import java.util.Map;

/** Hardware adapters must report their own verified fields; absent data is not zero. */
interface VehicleBackend extends AutoCloseable {
    Map<Integer, Integer> snapshot();
    void onChange(Runnable listener);
    boolean command(int command, int[] values);
    void close();
}
