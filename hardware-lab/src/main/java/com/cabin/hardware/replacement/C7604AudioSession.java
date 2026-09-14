package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.*;

/** Owns DSP and amplifier sequencing. Caller establishes board ownership before constructing. */
public final class C7604AudioSession implements AutoCloseable {
    private final C7604Writer writer;
    private final AmplifierMute amplifier;
    private boolean ready, closed, endpointIssued, startupAttempted;
    private int initialBalance, initialFade;
    private Integer appliedGainWord;
    private String failure="";
    public C7604AudioSession(C7604Writer writer,AmplifierMute amplifier) {
        this.writer=Objects.requireNonNull(writer); this.amplifier=Objects.requireNonNull(amplifier);
    }
    public synchronized void start(C7604ProgramImage image,int attempts,C7604SourceRouting.Plan route,
            C7604SourceRouting.AndroidSwitch platform,double initialGainDb,int balance,int fade) throws IOException {
        start(image,attempts,route,platform,initialGainDb,balance,fade,NativeIo::setDspReset,Thread::sleep);
    }
    synchronized void start(C7604ProgramImage image,int attempts,C7604SourceRouting.Plan route,
            C7604SourceRouting.AndroidSwitch platform,double initialGainDb,int balance,int fade,
            C7604Startup.Reset reset,C7604Writer.Delay delay) throws IOException {
        if(closed || ready) throw new IllegalStateException("Audio session cannot start");
        Objects.requireNonNull(image); Objects.requireNonNull(route); Objects.requireNonNull(reset); Objects.requireNonNull(delay);
        if(route.androidIis!=null) Objects.requireNonNull(platform);
        if(attempts<1 || attempts>20) throw new IllegalArgumentException("Invalid readiness attempts");
        C7604Gain.word(initialGainDb); C7604Channels.field(balance,fade); writer.requireFreshForStartup();
        startupAttempted=true;
        try {
            amplifier.initialize();
            C7604Startup.initialize(writer,image,attempts,reset,delay);
            route.apply(writer,platform);
            writer.setMasterGainDb(initialGainDb);
            appliedGainWord=C7604Gain.word(initialGainDb);
            writer.setSpeakerField(balance,fade,false);
            amplifier.set(AmplifierMute.Reason.STARTUP,false);
            initialBalance=balance; initialFade=fade; ready=true;
        } catch(IOException | RuntimeException ex) { fail(ex); throw ex; }
    }
    public synchronized void route(C7604SourceRouting.Plan route,C7604SourceRouting.AndroidSwitch platform) throws IOException {
        requireReady(); Objects.requireNonNull(route);
        if(route.androidIis!=null) Objects.requireNonNull(platform);
        try {
            amplifier.set(AmplifierMute.Reason.SOURCE_CHANGE,true);
            route.apply(writer,platform);
            amplifier.set(AmplifierMute.Reason.SOURCE_CHANGE,false);
        } catch(IOException | RuntimeException ex) { fail(ex); throw ex; }
    }
    public synchronized void userMute(boolean muted) throws IOException {
        requireReady();
        try { amplifier.set(AmplifierMute.Reason.USER,muted); }
        catch(IOException | RuntimeException ex) { fail(ex); throw ex; }
    }
    /** Calibrated volume changes share routing, shutdown and DSP failure ownership. */
    public synchronized void volume(C7604VolumeCurve curve,int level,int tableOffset,
            int compensationSteps,int compensationLevel) throws IOException {
        requireReady();
        double decibels=Objects.requireNonNull(curve).decibels(level,tableOffset,compensationSteps,compensationLevel);
        int word=C7604Gain.word(decibels);
        try {
            writer.setMasterGainDb(decibels);
            appliedGainWord=word;
        } catch(IOException | RuntimeException ex) { fail(ex); throw ex; }
    }
    /** Applies a policy-resolved SOUND volume action, then its reference MCU notifications.
     * The transport must be the session's already-owned MCU connection.
     */
    public synchronized void applyVolumeCommand(SoundVolumeCommand command,C7604VolumeCurve curve,
            int tableOffset,int compensationSteps,int compensationLevel,RadioMcuTransport mcu) throws IOException {
        requireReady(); Objects.requireNonNull(command); Objects.requireNonNull(mcu);
        // Validate calibration before either device is touched, including compound volume/unmute actions.
        if(command.level!=null) C7604Gain.word(Objects.requireNonNull(curve).decibels(
            command.level,tableOffset,compensationSteps,compensationLevel));
        try {
            if(command.level!=null) {
                volume(curve,command.level,tableOffset,compensationSteps,compensationLevel);
                mcu.sendVolume(command.level);
            }
            if(command.muted!=null) {
                userMute(command.muted);
                mcu.sendUserMute(command.muted);
            }
        } catch(IOException | RuntimeException ex) {
            if(!closed) fail(ex);
            mcu.close();
            throw ex;
        }
    }
    public synchronized ModuleEndpoint soundModule(Map<Integer,C7604SoundModule.Band> bands) {
        requireReady(); if(endpointIssued) throw new IllegalStateException("Sound endpoint already issued");
        C7604SoundModule module=new C7604SoundModule(writer,bands,false);
        module.seedAppliedField(initialBalance,initialFade); endpointIssued=true;
        return new ModuleEndpoint() {
            public void command(int code,ModulePayload args) {
                synchronized(C7604AudioSession.this) {
                    requireReady();
                    try { module.command(code,args); }
                    catch(RuntimeException ex) { if(!writer.programLoaded()) fail(ex); throw ex; }
                }
            }
            public List<ModulePayload> cached(int field) {
                synchronized(C7604AudioSession.this) { return ready && !closed ? module.cached(field) : Collections.emptyList(); }
            }
            public void close() { try { C7604AudioSession.this.close(); } catch(IOException ex) { throw new IllegalStateException("Audio shutdown mute failed",ex); } }
        };
    }
    private void requireReady() { if(!ready || closed) throw new IllegalStateException("Audio session unavailable"); }
    private void fail(Exception cause) {
        ready=false; closed=true; appliedGainWord=null; failure=cause.toString();
        try { amplifier.set(AmplifierMute.Reason.FAULT,true); }
        catch(IOException | RuntimeException muteFailure) { cause.addSuppressed(muteFailure); }
        writer.close();
    }
    public synchronized Map<String,Object> diagnostics() {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("ready",ready && !closed);
        result.put("closed",closed); result.put("failure",failure); result.put("amplifierLastAppliedMute",amplifier.lastApplied());
        result.put("lastAppliedGainWord",appliedGainWord);
        result.put("muteReasons",amplifier.reasons().toString()); result.put("hardwareValidated",false); return result;
    }
    @Override public synchronized void close() throws IOException {
        if(closed) return; closed=true; ready=false; appliedGainWord=null;
        try { if(startupAttempted) amplifier.set(AmplifierMute.Reason.SLEEP,true); }
        catch(IOException | RuntimeException ex) { failure=ex.toString(); if(ex instanceof IOException) throw (IOException)ex; throw new IOException("Could not apply shutdown mute",ex); }
        finally { writer.close(); }
    }
}
