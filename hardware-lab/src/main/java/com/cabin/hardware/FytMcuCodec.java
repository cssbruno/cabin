package com.cabin.hardware;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/** Independent Joying-reference MCU framing. No device access or command semantics.
 * Instances are thread-confined; create a new decoder for each capture/connection.
 * See documents/research/JOYING-MCU-FRAMING.md for the pinned wire evidence.
 */
public final class FytMcuCodec {
    public static final int MAX_PAYLOAD = 512;
    private final byte[] pending = new byte[MAX_PAYLOAD + 5];
    private int size;
    private long received, discarded, checksumFailures, invalidLengths, frames;

    public static final class Frame {
        public final long offset;
        private final byte[] payload;
        private Frame(long offset, byte[] payload) { this.offset = offset; this.payload = payload; }
        public byte[] payload() { return payload.clone(); }
        public int payloadLength() { return payload.length; }
        /** First payload byte only; its meaning depends on firmware and direction. */
        public int opcode() { return payload.length == 0 ? -1 : payload[0] & 255; }
    }

    /** Encodes bytes for offline fixtures. Does not send them to any transport. */
    public static byte[] encode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("Payload exceeds reference limit");
        byte[] wire = new byte[payload.length + 5];
        wire[0] = (byte) 0x88;
        wire[1] = 0x55;
        wire[2] = (byte) (payload.length >>> 8);
        wire[3] = (byte) payload.length;
        System.arraycopy(payload, 0, wire, 4, payload.length);
        byte checksum = 0;
        for (int i = 2; i < wire.length - 1; i++) checksum ^= wire[i];
        wire[wire.length - 1] = checksum;
        return wire;
    }

    /** Accepts arbitrarily sized chunks. The consumer must not re-enter this decoder. */
    public void accept(byte[] bytes, int offset, int length, Consumer<Frame> consumer) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(consumer, "consumer");
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        for (int i = offset; i < offset + length; i++) {
            pending[size++] = bytes[i];
            received++;
            drain(consumer);
        }
    }

    private void drain(Consumer<Frame> consumer) {
        while (size > 0) {
            if ((pending[0] & 255) != 0x88) { discard(); continue; }
            if (size < 2) return;
            if (pending[1] != 0x55) { discard(); continue; }
            if (size < 4) return;
            int length = ((pending[2] & 255) << 8) | (pending[3] & 255);
            if (length > MAX_PAYLOAD) { invalidLengths++; discard(); continue; }
            int total = length + 5;
            if (size < total) return;
            byte checksum = 0;
            for (int i = 2; i < total - 1; i++) checksum ^= pending[i];
            if (checksum != pending[total - 1]) { checksumFailures++; discard(); continue; }
            Frame frame = new Frame(received - size, Arrays.copyOfRange(pending, 4, total - 1));
            remove(total);
            frames++;
            consumer.accept(frame);
        }
    }

    private void discard() { discarded++; remove(1); }
    private void remove(int count) {
        size -= count;
        System.arraycopy(pending, count, pending, 0, size);
    }
    public long receivedBytes() { return received; }
    public long discardedBytes() { return discarded; }
    public long checksumFailures() { return checksumFailures; }
    public long invalidLengths() { return invalidLengths; }
    public long frameCount() { return frames; }
    /** At EOF these are incomplete bytes, never published as a frame. */
    public int pendingBytes() { return size; }
}
