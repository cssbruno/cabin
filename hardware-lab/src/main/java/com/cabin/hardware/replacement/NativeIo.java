package com.cabin.hardware.replacement;

import java.io.IOException;

/** Own JNI library. Loading it neither opens hardware nor loads SYU binaries. */
final class NativeIo {
    static { System.loadLibrary("cabin_io"); }
    static native long openSerial(String path, int baud) throws IOException;
    static native int readSerial(long handle, byte[] buffer, int timeoutMs) throws IOException;
    static native void writeSerial(long handle, byte[] data, int timeoutMs) throws IOException;
    static native void closeSerial(long handle);
    static native long openI2c(String path, int address) throws IOException;
    static native void writeI2c(long handle, byte[] data) throws IOException;
    static native byte[] readI2c(long handle, byte[] prefix, int count) throws IOException;
    static native void closeI2c(long handle);
    static native void setDspReset(boolean high) throws IOException;
    static native void setAmplifierMute(boolean muted) throws IOException;
    private NativeIo() { }
}
