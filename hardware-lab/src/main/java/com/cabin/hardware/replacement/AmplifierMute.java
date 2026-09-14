package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.*;

/** Replacement lifecycle reasons, not vendor bitmask IDs. State is OS-write completion, not readback. */
public final class AmplifierMute {
    public enum Reason { STARTUP, SOURCE_CHANGE, USER, SLEEP, FAULT }
    public interface Output { void setMuted(boolean muted) throws IOException; }
    private final Output output;
    private final EnumSet<Reason> reasons=EnumSet.of(Reason.STARTUP);
    private Boolean applied;
    private boolean failed;
    public AmplifierMute(Output output) { this.output=Objects.requireNonNull(output); }
    public static AmplifierMute nativeControl() { return new AmplifierMute(NativeIo::setAmplifierMute); }
    /** Must be called before any reset/route operation; startup remains held until explicitly released. */
    public synchronized void initialize() throws IOException { write(); }
    public synchronized void set(Reason reason,boolean active) throws IOException {
        Objects.requireNonNull(reason);
        if(failed) throw new IOException("Amplifier mute state uncertain; new hardware session required");
        if(applied==null) throw new IllegalStateException("Initialize amplifier control first");
        if(active) reasons.add(reason); else reasons.remove(reason);
        write();
    }
    private void write() throws IOException {
        if(failed) throw new IOException("Amplifier mute state uncertain");
        boolean muted=!reasons.isEmpty();
        if(applied!=null && applied==muted) return;
        try { output.setMuted(muted); applied=muted; }
        catch(IOException | RuntimeException ex) { failed=true; applied=null; reasons.add(Reason.FAULT); throw ex; }
    }
    public synchronized Boolean lastApplied() { return applied; }
    public synchronized Set<Reason> reasons() { return Collections.unmodifiableSet(EnumSet.copyOf(reasons)); }
}
