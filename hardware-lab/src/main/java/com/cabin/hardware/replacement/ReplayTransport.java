package com.cabin.hardware.replacement;

import java.io.InputStream;
import java.io.IOException;
import java.util.Objects;

/** A capture is receive-only. Closing owns and closes the supplied stream. */
public final class ReplayTransport implements McuTransport {
    private final InputStream input;
    private volatile boolean closed;
    private long bytes;
    public ReplayTransport(InputStream input) { this.input = Objects.requireNonNull(input); }
    @Override public int read(byte[] buffer) throws IOException {
        if (closed) throw new IOException("Replay closed");
        if (buffer.length == 0) throw new IllegalArgumentException("Empty buffer");
        int count = input.read(buffer, 0, Math.min(buffer.length, 4096));
        if (count > 0 && (bytes += count) > 8L * 1024 * 1024) throw new IOException("Capture exceeds 8 MiB");
        return count;
    }
    @Override public void write(byte[] frame) throws IOException { throw new IOException("Replay is receive-only"); }
    @Override public void close() { closed = true; try { input.close(); } catch (IOException ignored) { } }
}
