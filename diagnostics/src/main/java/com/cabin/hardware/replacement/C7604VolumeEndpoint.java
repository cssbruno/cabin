package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** SOUND composition owning volume commands, persistence and its existing EQ/field endpoint.
 * The source/call coordinator must activate after routing and invalidate before changing policy.
 */
public final class C7604VolumeEndpoint implements ModuleEndpoint {
    private final C7604AudioSession audio;
    private final RadioMcuTransport mcu;
    private final ModuleEndpoint sound;
    private final SoundVolumeMemory memory;
    private final C7604VolumeCurve curve;
    private final int step;
    private int appId,offset,compensationSteps,compensationLevel,level;
    private boolean muted,muteAllowed,active,closed;

    public C7604VolumeEndpoint(C7604AudioSession audio,RadioMcuTransport mcu,ModuleEndpoint sound,
            SoundVolumeMemory memory,C7604VolumeCurve curve,int step) {
        this.audio=Objects.requireNonNull(audio); this.mcu=Objects.requireNonNull(mcu);
        this.sound=Objects.requireNonNull(sound); this.memory=Objects.requireNonNull(memory);
        this.curve=Objects.requireNonNull(curve);
        if(memory.maximum()>=curve.levels() || step<1 || step>memory.maximum())
            throw new IllegalArgumentException("Volume profile does not cover configured range/step");
        this.step=step;
    }
    /** No command is accepted until a routed source and explicit policy have been applied. */
    public synchronized void activate(int appId,int offset,int compensationSteps,int compensationLevel,
            boolean userMuted,boolean muteAllowed) throws IOException {
        if(closed) throw new IllegalStateException("Volume endpoint closed");
        int restored=memory.forSource(appId);
        curve.decibels(restored,offset,compensationSteps,compensationLevel);
        if(userMuted && !muteAllowed) throw new IllegalArgumentException("Requested mute conflicts with call policy");
        active=false;
        try {
            audio.applyVolumeCommand(SoundVolumeCommand.resolve(restored,restored,memory.maximum(),step,false,muteAllowed),
                curve,offset,compensationSteps,compensationLevel,mcu);
            // Explicitly establish user mute, even when no previous endpoint state is known.
            audio.userMute(userMuted); mcu.sendUserMute(userMuted);
            this.appId=appId; this.offset=offset; this.compensationSteps=compensationSteps;
            this.compensationLevel=compensationLevel; this.muteAllowed=muteAllowed;
            level=restored; muted=userMuted; active=true;
        } catch(IOException | RuntimeException ex) { retire(ex); throw ex; }
    }
    /** The coordinator calls this before source, call or sleep state becomes uncertain. */
    public synchronized void invalidatePolicy() { active=false; }
    private boolean available() {
        return active && !closed && mcu.isOpen() && Boolean.TRUE.equals(audio.diagnostics().get("ready"));
    }
    @Override public synchronized void command(int code,ModulePayload args) {
        if(!available()) throw new IllegalStateException("Current audio policy unavailable");
        if(code!=0) {
            try { sound.command(code,args); }
            catch(RuntimeException ex) { if(!available()) retire(ex); throw ex; }
            return;
        }
        Objects.requireNonNull(args);
        int[] values=args.integers();
        if(!args.integerOnly() || values==null || values.length!=1)
            throw new IllegalArgumentException("Volume command requires one integer");
        SoundVolumeCommand action=SoundVolumeCommand.resolve(values[0],level,memory.maximum(),step,muted,muteAllowed);
        try {
            audio.applyVolumeCommand(action,curve,offset,compensationSteps,compensationLevel,mcu);
            if(action.level!=null) memory.remember(appId,action.level);
            if(action.level!=null) level=action.level;
            if(action.muted!=null) muted=action.muted;
        } catch(IOException | RuntimeException ex) {
            retire(ex); throw new IllegalStateException("Volume operation failed",ex);
        }
    }
    @Override public synchronized List<ModulePayload> cached(int field) {
        if(!available()) return Collections.emptyList();
        if(field==2) return Collections.singletonList(ModulePayload.integers(level));
        if(field==3) return Collections.singletonList(ModulePayload.integers(muted ? 1 : 0));
        if(field==0x43) return memory.cached();
        return sound.cached(field);
    }
    private void retire(Exception cause) {
        try { close(); } catch(RuntimeException ex) { cause.addSuppressed(ex); }
    }
    @Override public synchronized void close() {
        if(closed) return;
        closed=true; active=false;
        try { sound.close(); }
        finally {
            try { audio.close(); } catch(IOException ex) { throw new IllegalStateException("Audio mute failed",ex); }
            finally { mcu.close(); }
        }
    }
}
