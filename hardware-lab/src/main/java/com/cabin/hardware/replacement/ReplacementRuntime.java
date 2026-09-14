package com.cabin.hardware.replacement;

import com.cabin.hardware.FytMcuCodec;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/** Single receive owner, bounded diagnostics, fresh per-session decoder. Never transmits. */
public final class ReplacementRuntime implements AutoCloseable {
    public enum Status { IDLE, RUNNING, COMPLETE, FAILED, CLOSED }
    public static final long FIELD_TTL_MS = 5000;
    private final LongSupplier clock;
    private final HondaCanDecoder can;
    private final RadioFeedbackDecoder radio;
    private final Map<Integer, Integer> radioFields = new TreeMap<>(), radioHistorical = new TreeMap<>();
    private final Map<Integer,Integer> presets = new TreeMap<>(), presetHistorical = new TreeMap<>();
    private final Map<Integer,Long> presetUpdated = new TreeMap<>();
    private final Map<Integer, Long> radioUpdated = new TreeMap<>();
    private final PowerSequence power = new PowerSequence();
    private final Map<Integer, Integer> fields = new TreeMap<>();
    private final Map<Integer, Integer> lastDecoded = new TreeMap<>();
    private final Map<Integer, Long> updated = new TreeMap<>();
    private final Map<Integer, Long> opcodes = new TreeMap<>();
    private McuTransport transport;
    private Thread reader;
    private Status status = Status.IDLE;
    private long generation, frames, checksumFailures, invalidLengths, bytes, pending, discarded;
    private String error = "", source = "none";
    private byte[] lastCan = new byte[0];
    private Runnable listener = () -> { };
    public ReplacementRuntime(HondaCanDecoder can, LongSupplier monotonicClock) { this(can, monotonicClock, false); }
    public ReplacementRuntime(HondaCanDecoder can, LongSupplier monotonicClock, boolean radioDriverOne) {
        this.can = can; this.clock = monotonicClock; radio = radioDriverOne ? new RadioFeedbackDecoder() : null;
    }
    private void clearRadio() { presets.clear(); presetUpdated.clear(); radioFields.clear(); radioUpdated.clear(); if (radio != null) radio.reset(); }
    public synchronized void onChange(Runnable listener) { this.listener = java.util.Objects.requireNonNull(listener); }
    public synchronized void start(McuTransport next, String source) {
        if (status == Status.CLOSED) throw new IllegalStateException("Runtime closed");
        if (reader != null && reader.isAlive()) throw new IllegalStateException("Receive session already active");
        transport = java.util.Objects.requireNonNull(next); this.source = java.util.Objects.requireNonNull(source);
        clearRadio(); presetHistorical.clear(); radioHistorical.clear(); fields.clear(); lastDecoded.clear(); updated.clear(); opcodes.clear(); power.reset(); lastCan = new byte[0]; error = "";
        frames = checksumFailures = invalidLengths = bytes = pending = discarded = 0;
        status = Status.RUNNING;
        long session = ++generation;
        reader = new Thread(() -> receive(next, session), "cabin-mcu-rx"); reader.setDaemon(true); reader.start();
    }
    private void receive(McuTransport input, long session) {
        FytMcuCodec codec = new FytMcuCodec(); long[] lastDiscarded = {0}; byte[] buffer = new byte[4096];
        try {
            while (true) {
                int count = input.read(buffer);
                synchronized (this) {
                    if (session != generation || status != Status.RUNNING) return;
                    if (count < 0) { status = Status.COMPLETE; break; }
                    if (count > buffer.length) throw new IOException("Transport exceeded read buffer");
                    codec.accept(buffer, 0, count, frame -> {
                        if (codec.discardedBytes() != lastDiscarded[0]) clearRadio();
                        lastDiscarded[0] = codec.discardedBytes();
                        dispatch(frame.payload());
                    });
                    if (codec.discardedBytes() != lastDiscarded[0]) clearRadio();
                    lastDiscarded[0] = codec.discardedBytes();
                    frames = codec.frameCount(); checksumFailures = codec.checksumFailures();
                    invalidLengths = codec.invalidLengths(); bytes = codec.receivedBytes();
                    pending = codec.pendingBytes(); discarded = codec.discardedBytes();
                }
                changed();
            }
        } catch (IOException | RuntimeException ex) {
            synchronized (this) {
                if (session == generation && status != Status.CLOSED) { status = Status.FAILED; error = ex.getClass().getSimpleName() + ": " + ex.getMessage(); }
            }
        } finally {
            input.close();
            synchronized (this) {
                if (session == generation) { clearRadio(); fields.clear(); updated.clear(); transport = null; }
            }
            changed();
        }
    }
    private void dispatch(byte[] payload) {
        int opcode = payload.length == 0 ? -1 : payload[0] & 255;
        opcodes.put(opcode, opcodes.getOrDefault(opcode, 0L) + 1);
        power.accept(payload);
        if (radio != null) {
            long now = clock.getAsLong();
            Long bandAt = radioUpdated.get(0);
            if (bandAt != null && (now < bandAt || now - bandAt >= FIELD_TTL_MS)) clearRadio();
            // A band message invalidates the old frequency even if its band value is unsupported.
            if (payload.length >= 3 && payload[0] == 1 && payload[1] == 3 && payload[2] == 6) {
                radioFields.clear(); radioUpdated.clear(); radioHistorical.clear();
            }
            Map<Integer, Integer> decoded = radio.accept(payload, now);
            radioFields.putAll(decoded); radioHistorical.putAll(decoded);
            decoded.keySet().forEach(key -> radioUpdated.put(key, now));
            Map<Integer,Integer> receivedPresets = radio.presetUpdates();
            presets.putAll(receivedPresets); presetHistorical.putAll(receivedPresets);
            receivedPresets.keySet().forEach(key -> presetUpdated.put(key,now));
        }
        if (opcode == 0xe3) {
            lastCan = Arrays.copyOfRange(payload, 1, payload.length);
            if (can != null) {
                Map<Integer, Integer> decoded = can.decode(lastCan);
                fields.putAll(decoded); if (!decoded.isEmpty()) { lastDecoded.clear(); lastDecoded.putAll(decoded); } long now = clock.getAsLong(); decoded.keySet().forEach(key -> updated.put(key, now));
            }
        }
    }
    private void changed() { Runnable target; synchronized (this) { target = listener; } try { target.run(); } catch (RuntimeException ignored) { } }
    public synchronized Map<Integer, Integer> snapshot() {
        long now = clock.getAsLong();
        Map<Integer, Integer> valid = new TreeMap<>();
        fields.forEach((key, value) -> { if (now - updated.get(key) < FIELD_TTL_MS && now >= updated.get(key)) valid.put(key, value); });
        return Collections.unmodifiableMap(valid);
    }
    public synchronized Map<Integer, Integer> radioSnapshot() {
        long now = clock.getAsLong(); Map<Integer, Integer> valid = new TreeMap<>();
        Long bandAt = radioUpdated.get(0);
        if (bandAt != null && now >= bandAt && now - bandAt < FIELD_TTL_MS)
            radioFields.forEach((key, value) -> { long at = radioUpdated.get(key); if (now >= at && now - at < FIELD_TTL_MS) valid.put(key, value); });
        return Collections.unmodifiableMap(valid);
    }
    public synchronized Map<Integer,Integer> radioPresetSnapshot() {
        long now=clock.getAsLong(); Map<Integer,Integer> valid=new TreeMap<>();
        presets.forEach((key,value)-> { long at=presetUpdated.get(key); if(now>=at && now-at<FIELD_TTL_MS) valid.put(key,value); });
        return Collections.unmodifiableMap(valid);
    }
    public synchronized java.util.List<ModulePayload> radioCached(int field) {
        if(field==4) {
            java.util.List<ModulePayload> values=new java.util.ArrayList<>();
            radioPresetSnapshot().forEach((channel,frequency)->values.add(ModulePayload.integers(channel,frequency)));
            return Collections.unmodifiableList(values);
        }
        Integer value=radioSnapshot().get(field);
        return value==null ? Collections.emptyList() : Collections.singletonList(ModulePayload.integers(value));
    }
    public synchronized Map<String, Object> diagnostics() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", status.name()); result.put("source", source); result.put("hardwareValidated", false);
        result.put("frames", frames); result.put("bytes", bytes); result.put("checksumFailures", checksumFailures);
        result.put("invalidLengths", invalidLengths); result.put("pendingBytes", pending); result.put("discardedBytes", discarded);
        result.put("opcodes", stringKeys(opcodes)); result.put("lastCanHex", hex(lastCan)); result.put("powerObservation", power.state().name());
        result.put("canProfile", can == null ? "unconfigured" : "0x10012a"); result.put("fields", stringKeys(snapshot())); result.put("error", error);
        result.put("lastDecodedCanFieldsHistorical", stringKeys(lastDecoded));
        result.put("radioDriver", radio == null ? "unconfigured" : "1 (explicit receive profile)");
        result.put("radioFields", stringKeys(radioSnapshot()));
        result.put("radioPresets",stringKeys(radioPresetSnapshot()));
        result.put("lastDecodedRadioPresetsHistorical",stringKeys(presetHistorical));
        result.put("lastDecodedRadioFieldsHistorical", stringKeys(radioHistorical));
        result.put("transmitEnabled", false); return result;
    }
    private static Map<String, Object> stringKeys(Map<Integer, ?> values) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(); values.forEach((key,value) -> result.put(Integer.toString(key), value)); return result;
    }
    private static String hex(byte[] bytes) { StringBuilder text = new StringBuilder(); for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02X", value & 255)); return text.toString(); }
    public synchronized Status status() { return status; }
    /** Wait is for tests/worker threads, never the main thread. */
    public boolean await(long timeoutMs) throws InterruptedException { Thread current; synchronized (this) { current = reader; } if (current == null) return true; current.join(timeoutMs); return !current.isAlive(); }
    @Override public void close() {
        McuTransport current;
        synchronized (this) { if (status == Status.CLOSED) return; status = Status.CLOSED; generation++; current = transport; transport = null; clearRadio(); fields.clear(); updated.clear(); listener = () -> { }; }
        if (current != null) current.close();
    }
}
