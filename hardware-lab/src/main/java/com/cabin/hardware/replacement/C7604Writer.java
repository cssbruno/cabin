package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.Objects;

/** C7604 coefficient writes and status readback; full DSP initialization remains separate. */
public final class C7604Writer implements AutoCloseable {
    public interface Bus extends AutoCloseable {
        void write(byte[] bytes) throws IOException;
        default byte[] read(byte[] prefix, int count) throws IOException { throw new IOException("I2C read unavailable"); }
        @Override void close();
    }
    private final Bus bus;
    private boolean closed, failed, programLoaded;
    synchronized void requireFreshForStartup() throws IOException {
        if(closed || failed || programLoaded) throw new IOException("DSP startup requires a fresh writer");
    }
    public interface Delay { void sleep(long milliseconds) throws InterruptedException; }
    public C7604Writer(Bus bus) { this.bus = Objects.requireNonNull(bus); }
    public static C7604Writer open(String path) throws IOException {
        if (!path.matches("/dev/i2c-[0-9]+")) throw new IOException("Invalid I2C path");
        long handle = NativeIo.openI2c(path, 0x1c);
        return new C7604Writer(new Bus() {
            public void write(byte[] bytes) throws IOException { NativeIo.writeI2c(handle, bytes); }
            public byte[] read(byte[] prefix, int count) throws IOException { return NativeIo.readI2c(handle,prefix,count); }
            public void close() { NativeIo.closeI2c(handle); }
        });
    }
    public static byte[] coefficients(int command, int register, int[] words) {
        if ((command != 0x84 && command != 0x89) || register < 0 || register > 65535 || words == null || words.length < 1 || words.length > 128)
            throw new IllegalArgumentException("Invalid C7604 coefficient request");
        byte[] packet = new byte[3 + words.length * 3];
        packet[0] = (byte) command; packet[1] = (byte) (register >>> 8); packet[2] = (byte) register;
        for (int i = 0; i < words.length; i++) {
            int word = words[i];
            if (word < -8388608 || word > 8388607) throw new IllegalArgumentException("Coefficient exceeds signed 24 bits");
            packet[3+i*3] = (byte) (word >>> 16); packet[4+i*3] = (byte) (word >>> 8); packet[5+i*3] = (byte) word;
        }
        return packet;
    }
    /** Caller supplies a register/coefficients validated for its fully initialized sound ID 11. */
    public synchronized void writeCoefficients(int command, int register, int[] words) throws IOException {
        byte[] packet = coefficients(command, register, words);
        if (closed || failed) throw new IOException("DSP session closed or requires reinitialization");
        try { bus.write(packet); bus.write(new byte[] {(byte) 0xa4, 0, 0}); }
        catch (IOException | RuntimeException ex) { failed = true; throw ex; }
    }
    /** Loads the verified f0 sequence after reset/ownership have been established externally.
     * This initializes the DSP program only, not source routing, EQ state or amplifier volume.
     */
    public void loadProgram(C7604ProgramImage image) throws IOException { loadProgram(image,Thread::sleep); }
    synchronized void loadProgram(C7604ProgramImage image, Delay delay) throws IOException {
        Objects.requireNonNull(image); Objects.requireNonNull(delay);
        if (closed || failed || programLoaded) throw new IOException("DSP requires a fresh session/reset");
        try {
            if (readStatus() != 4) throw new IOException("C7604 is not ready for program loading");
            gates(false,false,false,false); delay.sleep(10);
            int[] pairs=image.registers();
            for(int i=0;i<pairs.length;i+=2) {
                int value=pairs[i+1];
                if(pairs[i]==2) value &= 0x7f;
                if(pairs[i]==0xa3) value &= 0xf8;
                registerWrite(pairs[i],value);
            }
            delay.sleep(10); gates(true,false,false,false); delay.sleep(10);
            programWrite(0xb8,image.parameters()); programWrite(0xb4,image.cram());
            delay.sleep(10); gates(true,true,true,true); delay.sleep(100);
            programLoaded=true;
        } catch(InterruptedException ex) {
            failed=true; Thread.currentThread().interrupt(); throw new IOException("DSP loading interrupted; reset required",ex);
        } catch(IOException | RuntimeException ex) { failed=true; throw ex; }
    }
    public synchronized boolean programLoaded() { return programLoaded && !closed && !failed; }
    private int registerRead(int register) throws IOException {
        byte[] result=bus.read(new byte[] {0x40,(byte)(register>>>8),(byte)register},1);
        if(result==null || result.length!=1) throw new IOException("Incomplete C7604 register read");
        return result[0]&255;
    }
    private void registerWrite(int register,int value) throws IOException {
        bus.write(new byte[] {(byte)0xc0,(byte)(register>>>8),(byte)register,(byte)value});
    }
    private void programWrite(int command,byte[] image) throws IOException {
        byte[] packet=new byte[image.length+3]; packet[0]=(byte)command;
        System.arraycopy(image,0,packet,3,image.length); bus.write(packet);
    }
    private void gates(boolean run,boolean second,boolean third,boolean fourth) throws IOException {
        int first=registerRead(2), other=registerRead(0xa3);
        registerWrite(2,(first&0x7f)|(run?0x80:0));
        registerWrite(0xa3,(other&0xf8)|(second?2:0)|(third?4:0)|(fourth?1:0));
    }
    /** Direct DSP master attenuation/gain, not Android volume or amplifier mute.
     * UI volume-step calibration and source offsets must be supplied by the sound service.
     */
    public synchronized void setMasterGainDb(double decibels) throws IOException {
        byte[] packet=C7604Gain.packet(decibels);
        if (!programLoaded()) throw new IOException("DSP program is not loaded or session failed");
        try { bus.write(packet); bus.write(new byte[] {(byte)0xa4,0,0}); }
        catch(IOException | RuntimeException ex) { failed=true; throw ex; }
    }
    public synchronized void setVolumeLevel(C7604VolumeCurve curve, int level, int tableOffset,
            int compensationSteps, int compensationLevel) throws IOException {
        setMasterGainDb(Objects.requireNonNull(curve).decibels(level,tableOffset,compensationSteps,compensationLevel));
    }
    /** DSP channel attenuation only; amplifier GPIO/MCU mute arbitration is separate. */
    public synchronized void setSpeakerField(int balance, int fade, boolean muted) throws IOException {
        int[] field=C7604Channels.field(balance,fade);
        if(muted) field=C7604Channels.muted();
        if(!programLoaded()) throw new IOException("DSP program is not loaded or session failed");
        try { for(int channel=0;channel<4;channel++) registerWrite(0x83+channel,field[channel]); }
        catch(IOException | RuntimeException ex) { failed=true; throw ex; }
    }
    /** Hardware input index from the verified board profile; not a SYU application ID.
     * Android HAL switching must be completed by the source-routing coordinator separately.
     */
    public synchronized void selectInput(int input, boolean alternatePath) throws IOException {
        if(input<0 || input>3) throw new IllegalArgumentException("C7604 input must be 0..3");
        if(!programLoaded()) throw new IOException("DSP program is not loaded or session failed");
        int selector=input==1 ? 0x0c : input==2 ? 2 : 8;
        try {
            // e0 -> B0(1,0x80,6): boolean converter returns 0x10 or 0x20.
            bus.write(new byte[] {(byte)0x80,0,6,0,0,(byte)(alternatePath?0x20:0x10)});
            bus.write(new byte[] {(byte)0xa4,0,0});
            // X -> C0(0x8d, selector); IN4 follows the reference default selector 8.
            registerWrite(0x8d,selector);
        } catch(IOException | RuntimeException ex) { failed=true; throw ex; }
    }
    /** C7604.setup -> s0(0xc0) -> q0(0x40,0xc0,1), status 4 means chip ready.
     * Readiness alone does not prove DSP program/configuration has been initialized.
     */
    public synchronized int readStatus() throws IOException {
        if (closed || failed) throw new IOException("DSP session closed or requires reinitialization");
        try {
            byte[] result = bus.read(new byte[] {0x40,0,(byte)0xc0},1);
            if (result == null || result.length != 1) throw new IOException("Incomplete C7604 status read");
            return result[0] & 255;
        } catch (IOException | RuntimeException ex) { failed = true; throw ex; }
    }
    @Override public synchronized void close() { if (!closed) { closed = true; bus.close(); } }
}
