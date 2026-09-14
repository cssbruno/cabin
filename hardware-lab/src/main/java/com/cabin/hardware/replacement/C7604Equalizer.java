package com.cabin.hardware.replacement;

import java.io.IOException;

/** Independent peaking-EQ calculation and register mapping from C7604.D0/b0/e/j.
 * Caller must provide the actual frequency, Q and gain; no initialized state is inferred.
 */
public final class C7604Equalizer {
    public static final class Plan {
        public final int command, register;
        private final int[] words;
        private Plan(int command,int register,int[] words) { this.command=command; this.register=register; this.words=words; }
        public int[] words() { return words.clone(); }
        public byte[] packet() { return C7604Writer.coefficients(command,register,words); }
        public void apply(C7604Writer writer) throws IOException { writer.writeCoefficients(command,register,words); }
    }
    private C7604Equalizer() { }
    public static Plan plan(int band,int frequencyHz,int qTenths,int gainStep) {
        if(band<0 || band>=36 || frequencyHz<1 || frequencyHz>=24000 || qTenths<1 || qTenths>1000 || gainStep<0 || gainStep>20)
            throw new IllegalArgumentException("Invalid C7604 EQ parameters");
        // The firmware uses a float division before widening Q to double.
        double q=(double)(qTenths/10.0f), angle=2*Math.PI*frequencyHz/48000.0;
        double alpha=Math.sin(angle)/(2*q), cosine=Math.cos(angle), amplitude=Math.pow(10,(gainStep-10)/40.0);
        double denominator=1+alpha/amplitude;
        double[] values={(1-alpha*amplitude)/denominator, -2*cosine/denominator,
            -(1-alpha/amplitude)/denominator, 2*cosine/denominator, (1+alpha*amplitude)/denominator};
        boolean extended=band<20;
        int[] words=new int[extended ? 10 : 5];
        for(int i=0;i<values.length;i++) {
            if(extended) { words[i*2]=upper(values[i]); words[i*2+1]=lower(values[i]); }
            else words[i]=single(values[i]);
        }
        return new Plan(extended ? 0x89 : 0x84,extended ? 62+10*band : 262+5*(band-20),words);
    }
    private static double clamp(double value,double low,double high) { return Math.max(low,Math.min(high,value)); }
    private static int signed24(int bits) { return (bits<<8)>>8; }
    static int single(double value) {
        double scaled=clamp(value*4194304.0,-8388608,8388607);
        if(scaled<0) scaled+=16777216;
        return signed24((int)clamp(scaled+0.5,0,16777215));
    }
    private static double extended(double value) {
        double scaled=clamp(value*16384,-32768,32767.99998474);
        return scaled<0 ? scaled+65536 : scaled;
    }
    static int upper(double value) { return signed24(((int)clamp(extended(value),0,65535))*256); }
    static int lower(double value) { double scaled=extended(value); return (int)clamp((scaled-(int)scaled)*8388608+0.5,0,8388607); }
}
