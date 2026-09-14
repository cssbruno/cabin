package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Live module composition for an already-owned, configured MCU transport.
 * Does not stop vendor processes, request root, or infer ownership from a serial open.
 * NativeSerialTransport supplies bounded reads/writes and cancellation on close.
 */
public final class LiveReplacementSession implements AutoCloseable {
    private final RadioMcuTransport transport;
    private final ReplacementRuntime runtime;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ModuleToolkitBridge toolkit;

    public LiveReplacementSession(McuTransport ownedTransport, HondaCanDecoder can, LongSupplier clock) {
        this(ownedTransport,can,clock,null);
    }
    public LiveReplacementSession(McuTransport ownedTransport, HondaCanDecoder can, LongSupplier clock, ModuleEndpoint ownedSound) {
        this(new RadioMcuTransport(ownedTransport),can,clock,shared->ownedSound);
    }
    public interface SoundFactory { ModuleEndpoint create(RadioMcuTransport sharedMcu) throws IOException; }
    /** Factory receives the one writer used by radio and the receive runtime. */
    public static LiveReplacementSession withSoundFactory(McuTransport ownedTransport,HondaCanDecoder can,
            LongSupplier clock,SoundFactory factory) {
        java.util.Objects.requireNonNull(factory); java.util.Objects.requireNonNull(clock);
        return new LiveReplacementSession(new RadioMcuTransport(ownedTransport),can,clock,factory);
    }
    private LiveReplacementSession(RadioMcuTransport sharedTransport,HondaCanDecoder can,LongSupplier clock,SoundFactory factory) {
        transport = sharedTransport;
        runtime = new ReplacementRuntime(can, java.util.Objects.requireNonNull(clock), true);
        final ModuleEndpoint ownedSound;
        try { ownedSound=factory.create(transport); }
        catch(IOException | RuntimeException ex) { transport.close(); throw new IllegalStateException("Sound setup failed",ex); }
        Map<Integer,ModuleEndpoint> modules = new TreeMap<>();
        modules.put(1,new ModuleEndpoint() {
            public List<ModulePayload> cached(int field) {
                return closed.get() ? Collections.emptyList() : runtime.radioCached(field);
            }
            public void command(int command, ModulePayload args) {
                if (closed.get() || runtime.status() != ReplacementRuntime.Status.RUNNING)
                    throw new IllegalStateException("Live session is not running");
                if (!args.integerOnly()) throw new IllegalArgumentException("Radio command requires integers");
                try { transport.sendRadio(command,args.integers()); }
                catch (IOException ex) { throw new IllegalStateException("MCU write failed: " + ex.getMessage(),ex); }
            }
        });
        if (can != null) modules.put(7,new ModuleEndpoint() {
            public List<ModulePayload> cached(int field) {
                return scalar(closed.get() ? null : runtime.snapshot().get(field));
            }
        });
        if(ownedSound!=null) modules.put(4,new ModuleEndpoint() {
            public void command(int code,ModulePayload args) {
                if(closed.get() || runtime.status()!=ReplacementRuntime.Status.RUNNING) throw new IllegalStateException("Live session unavailable");
                ownedSound.command(code,args);
            }
            public List<ModulePayload> cached(int field) {
                return closed.get() || runtime.status()!=ReplacementRuntime.Status.RUNNING ? Collections.emptyList() : ownedSound.cached(field);
            }
            public void close() { ownedSound.close(); }
        });
        toolkit = new ModuleToolkitBridge(modules);
        try { runtime.start(transport,"owned MCU connection (radio driver 1)"); }
        catch (RuntimeException ex) { close(); throw ex; }
    }
    private static List<ModulePayload> scalar(Integer value) {
        return value == null ? Collections.emptyList() : Collections.singletonList(ModulePayload.integers(value));
    }
    public ModuleToolkitBridge toolkit() { return toolkit; }
    /** Poll from a service worker; never execute client callbacks on the MCU receive thread. */
    public void poll() {
        if(closed.get()) return;
        if(runtime.status()!=ReplacementRuntime.Status.RUNNING) { close(); return; }
        toolkit.poll();
    }
    public Map<String,Object> diagnostics() {
        Map<String,Object> result = runtime.diagnostics();
        result.put("transmitEnabled", !closed.get() && transport.isOpen() && runtime.status() == ReplacementRuntime.Status.RUNNING);
        result.put("completedSerialWrites", transport.completedWrites());
        result.put("serialWriteFailure", transport.failure());
        result.put("writeAcknowledgement", "OS write completion only; no device acknowledgement inferred");
        return result;
    }
    @Override public void close() {
        if (closed.compareAndSet(false,true)) {
            // Cancel I/O before waiting for toolkit callbacks or retiring endpoint objects.
            runtime.close(); transport.close(); toolkit.close();
        }
    }
}
