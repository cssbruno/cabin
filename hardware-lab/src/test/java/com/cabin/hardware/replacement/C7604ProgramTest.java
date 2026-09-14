package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class C7604ProgramTest {
    static class Bus implements C7604Writer.Bus {
        final Map<Integer,Integer> registers=new HashMap<>(); final List<byte[]> writes=new ArrayList<>(); int failAt;
        Bus() { registers.put(0xc0,4); registers.put(2,255); registers.put(0xa3,255); }
        public byte[] read(byte[] prefix,int count) {
            assertEquals(0x40,prefix[0]&255); assertEquals(1,count);
            return new byte[] {(byte)(int)registers.get(((prefix[1]&255)<<8)|(prefix[2]&255))};
        }
        public void write(byte[] packet) throws IOException {
            writes.add(packet.clone()); if(writes.size()==failAt) throw new IOException("partial write");
            if((packet[0]&255)==0xc0) registers.put(((packet[1]&255)<<8)|(packet[2]&255),packet[3]&255);
        }
        public void close() { }
    }
    private C7604ProgramImage fixture() { return new C7604ProgramImage(new int[] {2,0xaa,0xa3,0x55},new byte[4600],new byte[1533]); }
    @Test public void loadingPreservesUnrelatedBitsAndOrdersTablesAndDelays() throws Exception {
        Bus bus=new Bus(); List<Long> delays=new ArrayList<>();
        try(C7604Writer writer=new C7604Writer(bus)) {
            writer.loadProgram(fixture(),delays::add); assertTrue(writer.programLoaded());
            assertEquals(Arrays.asList(10L,10L,10L,10L,100L),delays);
            assertEquals(10,bus.writes.size());
            assertArrayEquals(new byte[] {(byte)0xc0,0,2,0x7f},bus.writes.get(0));
            assertArrayEquals(new byte[] {(byte)0xc0,0,(byte)0xa3,(byte)0xf8},bus.writes.get(1));
            assertEquals(0x2a,bus.writes.get(2)[3]&255); assertEquals(0x50,bus.writes.get(3)[3]&255);
            assertEquals(4603,bus.writes.get(6).length); assertEquals(0xb8,bus.writes.get(6)[0]&255);
            assertEquals(1536,bus.writes.get(7).length); assertEquals(0xb4,bus.writes.get(7)[0]&255);
            assertEquals(Integer.valueOf(0xaa),bus.registers.get(2)); assertEquals(Integer.valueOf(0x57),bus.registers.get(0xa3));
            assertThrows(IOException.class,()->writer.loadProgram(fixture(),delays::add));
        }
    }
    @Test public void tableFailureNeverEnablesOutputsOrAllowsLaterWrites() {
        Bus bus=new Bus(); bus.failAt=7;
        try(C7604Writer writer=new C7604Writer(bus)) {
            assertThrows(IOException.class,()->writer.loadProgram(fixture(),ms->{}));
            assertFalse(writer.programLoaded()); assertEquals(7,bus.writes.size());
            assertEquals(Integer.valueOf(0x50),bus.registers.get(0xa3));
            assertThrows(IOException.class,()->writer.writeCoefficients(0x84,262,new int[] {0}));
        }
    }
    @Test public void notReadyAndInterruptedLoadingFailWithoutClaimingSuccess() {
        Bus bus=new Bus(); bus.registers.put(0xc0,0);
        try(C7604Writer writer=new C7604Writer(bus)) {
            assertThrows(IOException.class,()->writer.loadProgram(fixture(),ms->{})); assertTrue(bus.writes.isEmpty());
        }
        bus=new Bus();
        try(C7604Writer writer=new C7604Writer(bus)) {
            assertThrows(IOException.class,()->writer.loadProgram(fixture(),ms->{throw new InterruptedException();}));
            assertFalse(writer.programLoaded()); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test public void imageIsImmutableAndRejectsMalformedDimensions() {
        int[] pairs={2,255}; byte[] bytes={1}; C7604ProgramImage image=new C7604ProgramImage(pairs,bytes,bytes);
        pairs[0]=7; bytes[0]=4; image.parameters()[0]=6;
        assertEquals(2,image.registers()[0]); assertEquals(1,image.parameters()[0]);
        assertThrows(IllegalArgumentException.class,()->new C7604ProgramImage(new int[] {1},bytes,bytes));
        assertThrows(IllegalArgumentException.class,()->new C7604ProgramImage(new int[] {1,256},bytes,bytes));
        assertThrows(IllegalArgumentException.class,()->new C7604ProgramImage(new int[] {1,1},new byte[8190],bytes));
    }
    @Test public void unrecognizedApkCannotProvideAProgramImage() throws Exception {
        java.nio.file.Path apk=java.nio.file.Files.createTempFile("invalid-dsp", ".apk");
        try {
            try(java.util.zip.ZipOutputStream out=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(apk))) {
                out.putNextEntry(new java.util.zip.ZipEntry("res/n0.txt")); out.write("02,80".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); out.closeEntry();
            }
            assertThrows(IOException.class,()->C7604ProgramImage.fromReferenceApk(apk));
        } finally { java.nio.file.Files.delete(apk); }
    }
}
