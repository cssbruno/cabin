package com.cabin.hardware.replacement;

import java.io.IOException;

/** Exactly one owner. Zero means idle; negative means end of stream. Close cancels reads. */
public interface McuTransport extends AutoCloseable {
    int read(byte[] buffer) throws IOException;
    void write(byte[] frame) throws IOException;
    @Override void close();
}
