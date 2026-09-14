package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

public final class NativeSerialTransport implements McuTransport {
    private final AtomicLong handle;
    /** Caller must establish ownership before opening; existing serial readers cannot be detected here. */
    public NativeSerialTransport(String path, int baud) throws IOException {
        if (!path.matches("/dev/tty(S[0-9]+|Mbx[0-9]+)")) throw new IOException("Unsupported MCU device path");
        handle = new AtomicLong(NativeIo.openSerial(path, baud));
    }
    public int read(byte[] buffer) throws IOException { return NativeIo.readSerial(handle.get(), buffer, 200); }
    public void write(byte[] frame) throws IOException { NativeIo.writeSerial(handle.get(), frame, 1000); }
    public void close() { long old = handle.getAndSet(0); if (old != 0) NativeIo.closeSerial(old); }
}
