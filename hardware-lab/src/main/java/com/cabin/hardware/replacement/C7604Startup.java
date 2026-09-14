package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.Objects;

/** Reset/readiness/program-loading stage for caller-owned target hardware. Does not acquire ownership. */
public final class C7604Startup {
    public interface Reset { void setHigh(boolean high) throws IOException; }
    private C7604Startup() { }
    public static void initialize(C7604Writer writer,C7604ProgramImage image,int readinessAttempts) throws IOException {
        initialize(writer,image,readinessAttempts,NativeIo::setDspReset,Thread::sleep);
    }
    static void initialize(C7604Writer writer,C7604ProgramImage image,int readinessAttempts,
            Reset reset,C7604Writer.Delay delay) throws IOException {
        Objects.requireNonNull(writer); Objects.requireNonNull(image); Objects.requireNonNull(reset); Objects.requireNonNull(delay);
        if(readinessAttempts<1 || readinessAttempts>20) throw new IllegalArgumentException("Readiness attempts must be 1..20");
        synchronized(writer) {
            writer.requireFreshForStartup();
            try {
                reset.setHigh(false); delay.sleep(20); reset.setHigh(true); delay.sleep(20);
                boolean ready=false;
                for(int attempt=0;attempt<readinessAttempts;attempt++) {
                    if(writer.readStatus()==4) { ready=true; break; }
                    if(attempt+1<readinessAttempts) delay.sleep(520);
                }
                if(!ready) throw new IOException("DSP readiness polling exhausted");
                writer.loadProgram(image,delay);
            } catch(InterruptedException ex) {
                writer.close(); Thread.currentThread().interrupt(); throw new IOException("DSP startup interrupted",ex);
            } catch(IOException | RuntimeException ex) { writer.close(); throw ex; }
        }
    }
}
