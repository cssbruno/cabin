package com.cabin.hardware.replacement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipFile;

/** Immutable, caller-supplied DSP image. No firmware program is bundled with the app. */
public final class C7604ProgramImage {
    private final int[] registers;
    private final byte[] parameters, cram;
    public C7604ProgramImage(int[] registerPairs, byte[] parameters, byte[] cram) {
        if (registerPairs == null || registerPairs.length == 0 || registerPairs.length > 512 || registerPairs.length % 2 != 0
                || parameters == null || parameters.length == 0 || parameters.length > 8189
                || cram == null || cram.length == 0 || cram.length > 8189)
            throw new IllegalArgumentException("Invalid DSP image sizes");
        for(int i=0;i<registerPairs.length;i+=2)
            if(registerPairs[i]<0 || registerPairs[i]>65535 || registerPairs[i+1]<0 || registerPairs[i+1]>255)
                throw new IllegalArgumentException("Invalid DSP register pair");
        this.registers=registerPairs.clone(); this.parameters=parameters.clone(); this.cram=cram.clone();
    }
    public int[] registers() { return registers.clone(); }
    public byte[] parameters() { return parameters.clone(); }
    public byte[] cram() { return cram.clone(); }
    /** Exact resource mappings/hashes from the pinned Joying service. Rejects other images. */
    public static C7604ProgramImage fromReferenceApk(Path apk) throws IOException {
        try(ZipFile zip=new ZipFile(apk.toFile())) {
            int[] registers=table(zip,"res/n0.txt","a5daf727b84353d14570126f16e7766e6b07bfe6193995aaf02fb69617ca69e7",328);
            int[] parameters=table(zip,"res/3z.txt","db80af258dc6f0ec03a292e3b0f0e8089792e877f5b036c4eac769955ec96505",4600);
            int[] cram=table(zip,"res/HH.txt","2c5bffab3af00de9a5376bd1f980699d8974b27d2701e767f8cfccda78ae2946",1533);
            return new C7604ProgramImage(registers,bytes(parameters),bytes(cram));
        }
    }
    private static int[] table(ZipFile zip,String name,String expected,int count) throws IOException {
        java.util.zip.ZipEntry entry=zip.getEntry(name);
        if(entry==null) throw new IOException("Missing DSP table: "+name);
        byte[] raw;
        try(java.io.InputStream input=zip.getInputStream(entry); java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int n;
            while((n=input.read(buffer))!=-1) { if(output.size()+n>65536) throw new IOException("DSP table too large"); output.write(buffer,0,n); }
            raw=output.toByteArray();
        }
        try {
            StringBuilder hash=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(raw)) hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if(!expected.equals(hash.toString())) throw new IOException("DSP table hash mismatch: "+name);
        } catch(NoSuchAlgorithmException ex) { throw new IOException(ex); }
        String[] fields=new String(raw,StandardCharsets.US_ASCII).replaceAll("\\s", "").split(",");
        if(fields.length!=count) throw new IOException("DSP table length mismatch");
        int[] values=new int[count];
        try { for(int i=0;i<count;i++) values[i]=Integer.parseInt(fields[i],16); }
        catch(NumberFormatException ex) { throw new IOException("Invalid DSP table",ex); }
        return values;
    }
    private static byte[] bytes(int[] values) throws IOException {
        byte[] result=new byte[values.length];
        for(int i=0;i<values.length;i++) { if(values[i]<0 || values[i]>255) throw new IOException("Invalid DSP program byte"); result[i]=(byte)values[i]; }
        return result;
    }
}
