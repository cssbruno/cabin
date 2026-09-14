package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Reference appId policy with explicit board/app mapping and current override state. */
public final class C7604SourceRouting {
    public interface AndroidSwitch { void setIis(boolean enabled) throws IOException; }
    public static final class Plan {
        public final int appId, input;
        public final boolean dspAlternate;
        public final Boolean androidIis;
        private Plan(int appId,int input,boolean alternate,Boolean androidIis) {
            this.appId=appId; this.input=input; this.dspAlternate=alternate; this.androidIis=androidIis;
        }
        public void apply(C7604Writer writer,AndroidSwitch platform) throws IOException {
            Objects.requireNonNull(writer);
            if(!writer.programLoaded()) throw new IOException("DSP program is not loaded");
            if(androidIis!=null) Objects.requireNonNull(platform,"Android audio switch required");
            try {
                if(androidIis!=null) platform.setIis(androidIis);
                writer.selectInput(input,dspAlternate);
            } catch(IOException | RuntimeException ex) {
                // Android switching may have partly succeeded; never continue on an uncertain route.
                writer.close(); throw ex;
            }
        }
    }
    private final Map<Integer,Integer> inputs;
    public C7604SourceRouting(Map<Integer,Integer> inputs) {
        if(inputs==null || inputs.isEmpty() || inputs.size()>256) throw new IllegalArgumentException("Explicit app/input map required");
        java.util.TreeMap<Integer,Integer> copy=new java.util.TreeMap<>();
        inputs.forEach((app,input)-> {
            if(app==null || app<0 || app>255 || input==null || input<0 || input>3) throw new IllegalArgumentException("Invalid app/input mapping");
            copy.put(app,input);
        });
        this.inputs=java.util.Collections.unmodifiableMap(copy);
    }
    public Plan resolve(int requestedApp,boolean callOverride,int forcedApp,boolean forceArmAnalog,
            boolean radioRequiresAnalog,boolean platformSwitching) {
        if(requestedApp<0 || requestedApp>255 || forcedApp < -1 || forcedApp>255) throw new IllegalArgumentException("Invalid source ID");
        int app=forcedApp>=0 ? forcedApp : callOverride ? 2 : requestedApp;
        Integer input=inputs.get(app);
        if(input==null) throw new IllegalArgumentException("No verified input mapping for source "+app);
        boolean alternate=input==0;
        boolean analog=forceArmAnalog || app==1 && radioRequiresAnalog;
        if(analog) alternate=false;
        Boolean android=null;
        if(platformSwitching) {
            if(app==2) { android=false; alternate=false; }
            else if(alternate) android=true;
            else { android=!analog; alternate=false; }
        }
        return new Plan(app,input,alternate,android);
    }
}
