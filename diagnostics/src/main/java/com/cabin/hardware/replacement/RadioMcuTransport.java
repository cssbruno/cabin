package com.cabin.hardware.replacement;

import com.cabin.hardware.FytMcuCodec;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** One owned duplex connection. No retries: a failed write may already have sent bytes. */
public final class RadioMcuTransport implements McuTransport {
    private final McuTransport delegate;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock writer = new ReentrantLock();
    private final AtomicLong written = new AtomicLong();
    private volatile String failure = "";

    public RadioMcuTransport(McuTransport ownedTransport) { delegate = Objects.requireNonNull(ownedTransport); }
    public void sendRadio(int command, int... args) throws IOException {
        sendFrame(RadioCommandPlanner.frame(1, command, args));
    }
    /** t0/g.w0: notify the MCU after applying the resolved current volume. */
    public void sendVolume(int level) throws IOException {
        if(level<0 || level>255) throw new IllegalArgumentException("Resolved volume must fit one byte");
        sendFrame(FytMcuCodec.encode(new byte[] {(byte)0xe7,(byte)level,0}));
    }
    /** t0/g.w0: notify the MCU of the applied user mute state. */
    public void sendUserMute(boolean muted) throws IOException {
        sendFrame(FytMcuCodec.encode(new byte[] {1,0,(byte)(muted ? 0xa1 : 0xa0)}));
    }
    private void sendFrame(byte[] frame) throws IOException {
        if (!writer.tryLock()) throw new IOException("MCU writer busy; command was not queued");
        try {
            if (closed.get()) throw new IOException("MCU connection closed");
            try {
                delegate.write(frame);
                if (closed.get()) throw new IOException("Connection closed during write; delivery unknown");
                written.incrementAndGet();
            } catch (IOException | RuntimeException ex) {
                failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                close();
                throw ex;
            }
        } finally { writer.unlock(); }
    }
    @Override public int read(byte[] buffer) throws IOException {
        if (closed.get()) throw new IOException("MCU connection closed");
        try {
            int count = delegate.read(buffer);
            if (count < 0) close();
            if (count > buffer.length) throw new IOException("Transport exceeded read buffer");
            return count;
        } catch (IOException | RuntimeException ex) {
            if (!closed.get()) failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            close(); throw ex;
        }
    }
    /** Raw writes cannot bypass the typed radio/audio command validation. */
    @Override public void write(byte[] frame) { throw new UnsupportedOperationException("Use typed MCU commands"); }
    public boolean isOpen() { return !closed.get(); }
    public long completedWrites() { return written.get(); }
    public String failure() { return failure; }
    @Override public void close() { if (closed.compareAndSet(false, true)) delegate.close(); }
}
