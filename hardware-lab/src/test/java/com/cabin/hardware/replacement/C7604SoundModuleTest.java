package com.cabin.hardware.replacement;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class C7604SoundModuleTest {
    private C7604SoundModule module(C7604ProgramTest.Bus bus,boolean muted) throws Exception {
        C7604Writer writer=new C7604Writer(bus);
        writer.loadProgram(new C7604ProgramImage(new int[] {2,0},new byte[] {1},new byte[] {2}),ms->{}); bus.writes.clear();
        return new C7604SoundModule(writer,Map.of(20,new C7604SoundModule.Band(1000,10),21,new C7604SoundModule.Band(2000,10)),muted);
    }
    @Test public void publishesOnlyAppliedValuesAndKeepsIndexedBands() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus();
        try(C7604SoundModule module=module(bus,false)) {
            assertArrayEquals(new int[] {11},module.cached(1).get(0).integers()); assertTrue(module.cached(9).isEmpty());
            module.command(1,ModulePayload.integers(20,16)); module.command(1,ModulePayload.integers(21,8));
            assertEquals(4,bus.writes.size()); assertArrayEquals(C7604Equalizer.plan(20,1000,10,16).packet(),bus.writes.get(0));
            assertEquals(2,module.cached(9).size()); assertArrayEquals(new int[] {21,8},module.cached(9).get(1).integers());
            module.command(3,ModulePayload.integers(8,9)); assertArrayEquals(new int[] {8,9},module.cached(8).get(0).integers());
        }
    }
    @Test public void balanceDoesNotUnmuteConfiguredChannelsAndInvalidRequestsDoNotWrite() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus();
        try(C7604SoundModule module=module(bus,true)) {
            assertThrows(IllegalArgumentException.class,()->module.command(1,ModulePayload.integers(0,10)));
            assertThrows(UnsupportedOperationException.class,()->module.command(7,ModulePayload.integers(0)));
            assertTrue(bus.writes.isEmpty()); module.command(3,ModulePayload.integers(0,0));
            for(byte[] packet:bus.writes) assertEquals(255,packet[3]&255);
        }
    }
    @Test public void failedCommitClearsCallbacksAndRetiresModule() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); C7604SoundModule module=module(bus,false);
        bus.failAt=2;
        assertThrows(IllegalStateException.class,()->module.command(1,ModulePayload.integers(20,10)));
        assertTrue(module.cached(1).isEmpty()); assertTrue(module.cached(9).isEmpty());
        assertThrows(IllegalStateException.class,()->module.command(3,ModulePayload.integers(8,8))); module.close();
    }
}
