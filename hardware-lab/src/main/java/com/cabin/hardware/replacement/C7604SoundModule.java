package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.*;

/** Sound module 4 for an explicitly configured, programmed C7604 device.
 * Callbacks are last-applied service state, not physical hardware readback.
 */
public final class C7604SoundModule implements ModuleEndpoint {
    public static final class Band {
        public final int frequencyHz, qTenths;
        public Band(int frequencyHz,int qTenths) { this.frequencyHz=frequencyHz; this.qTenths=qTenths; }
    }
    private final C7604Writer writer;
    private final Map<Integer,Band> bands;
    private final Map<Integer,Integer> appliedGains=new TreeMap<>();
    private final boolean muted;
    private int[] appliedField;
    private boolean closed;
    public C7604SoundModule(C7604Writer ownedWriter,Map<Integer,Band> configuredBands,boolean muted) {
        writer=Objects.requireNonNull(ownedWriter); Objects.requireNonNull(configuredBands);
        if(!writer.programLoaded()) throw new IllegalArgumentException("Programmed C7604 session required");
        Map<Integer,Band> copy=new TreeMap<>();
        configuredBands.forEach((index,band)-> {
            Objects.requireNonNull(index); Objects.requireNonNull(band);
            C7604Equalizer.plan(index,band.frequencyHz,band.qTenths,10);
            copy.put(index,band);
        });
        bands=Collections.unmodifiableMap(copy); this.muted=muted;
    }
    /** Only the owning startup coordinator supplies values it has already written successfully. */
    synchronized void seedAppliedField(int balance,int fade) {
        if(closed || !writer.programLoaded() || appliedField!=null) throw new IllegalStateException("Cannot initialize field state");
        C7604Channels.field(balance,fade); appliedField=new int[] {balance,fade};
    }
    @Override public synchronized void command(int code,ModulePayload payload) {
        if(closed || !writer.programLoaded()) throw new IllegalStateException("Sound session unavailable");
        if(!payload.integerOnly()) throw new IllegalArgumentException("Integer sound arguments required");
        int[] args=payload.integers();
        if(code!=1 && code!=3) throw new UnsupportedOperationException("Sound command not implemented: "+code);
        if(args==null || args.length!=2) throw new IllegalArgumentException("Sound command requires two integers");
        try {
            if(code==1) {
                Band band=bands.get(args[0]);
                if(band==null) throw new IllegalArgumentException("EQ frequency/Q not configured for band");
                C7604Equalizer.plan(args[0],band.frequencyHz,band.qTenths,args[1]).apply(writer);
                appliedGains.put(args[0],args[1]);
            } else {
                writer.setSpeakerField(args[0],args[1],muted);
                appliedField=args.clone();
            }
        } catch(IOException ex) { close(); throw new IllegalStateException("Sound write failed",ex); }
    }
    @Override public synchronized List<ModulePayload> cached(int field) {
        if(closed || !writer.programLoaded()) return Collections.emptyList();
        if(field==1) return Collections.singletonList(ModulePayload.integers(11));
        if(field==8 && appliedField!=null) return Collections.singletonList(ModulePayload.integers(appliedField));
        if(field==9) {
            List<ModulePayload> values=new ArrayList<>();
            appliedGains.forEach((band,gain)->values.add(ModulePayload.integers(band,gain)));
            return Collections.unmodifiableList(values);
        }
        return Collections.emptyList();
    }
    @Override public synchronized void close() {
        if(!closed) { closed=true; appliedField=null; appliedGains.clear(); writer.close(); }
    }
}
