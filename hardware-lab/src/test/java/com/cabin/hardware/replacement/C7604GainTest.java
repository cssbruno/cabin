package com.cabin.hardware.replacement;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class C7604GainTest {
    @Test public void independentQ21VectorsAndSilenceMatchReference() {
        assertEquals(0,C7604Gain.word(-145)); assertEquals(0,C7604Gain.word(-200));
        assertEquals(0x200000,C7604Gain.word(0));
        assertEquals(0x100000,C7604Gain.word(20*Math.log10(0.5)));
        assertEquals(0x400000,C7604Gain.word(20*Math.log10(2)));
        assertEquals(C7604Gain.word(12),C7604Gain.word(100));
        assertArrayEquals(new byte[] {(byte)0x80,0,7,0x20,0,0},C7604Gain.packet(0));
        assertArrayEquals(new byte[] {(byte)0x80,0,7,0,0,0},C7604Gain.packet(-145));
        assertThrows(IllegalArgumentException.class,()->C7604Gain.word(Double.NaN));
        assertThrows(IllegalArgumentException.class,()->C7604Gain.word(Double.POSITIVE_INFINITY));
    }
    @Test public void gainCurveIsMonotonicAndStaysInsidePositive24Bits() {
        int previous=0;
        for(int tenth=-1450;tenth<=120;tenth++) {
            int word=C7604Gain.word(tenth/10.0); assertTrue(word>=previous); assertTrue(word<=8388607); previous=word;
        }
    }
    @Test public void gainRequiresProgramThenWritesCoefficientAndCommit() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus();
        try(C7604Writer writer=new C7604Writer(bus)) {
            assertThrows(IOException.class,()->writer.setMasterGainDb(0)); assertTrue(bus.writes.isEmpty());
            writer.loadProgram(new C7604ProgramImage(new int[] {2,0},new byte[] {1},new byte[] {2}),ms->{});
            bus.writes.clear(); writer.setMasterGainDb(0);
            assertEquals(2,bus.writes.size()); assertArrayEquals(C7604Gain.packet(0),bus.writes.get(0));
            assertArrayEquals(new byte[] {(byte)0xa4,0,0},bus.writes.get(1));
            bus.writes.clear(); bus.failAt=2;
            assertThrows(IOException.class,()->writer.setMasterGainDb(-12)); assertFalse(writer.programLoaded());
            assertThrows(IOException.class,()->writer.setMasterGainDb(0)); assertEquals(2,bus.writes.size());
        }
    }
}
