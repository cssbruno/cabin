package com.cabin.hardware.replacement;
import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class C7604StartupTest {
    private C7604ProgramImage image() { return new C7604ProgramImage(new int[] {2,0},new byte[] {1},new byte[] {2}); }
    @Test public void resetsWaitsThenLoadsProgram() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); List<String> events=new ArrayList<>();
        try(C7604Writer writer=new C7604Writer(bus)) {
            C7604Startup.initialize(writer,image(),2,high->events.add("reset:"+high),ms->events.add("wait:"+ms));
            assertEquals(List.of("reset:false","wait:20","reset:true","wait:20"),events.subList(0,4));
            assertTrue(writer.programLoaded());
        }
    }
    @Test public void finiteReadinessFailureClosesWithoutProgramWrites() {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); bus.registers.put(0xc0,0); List<Long> waits=new ArrayList<>();
        C7604Writer writer=new C7604Writer(bus);
        assertThrows(IOException.class,()->C7604Startup.initialize(writer,image(),3,high->{},waits::add));
        assertEquals(List.of(20L,20L,520L,520L),waits); assertTrue(bus.writes.isEmpty());
        assertThrows(IOException.class,writer::readStatus);
    }
    @Test public void resetFailureAndInterruptionDoNotContinue() {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); C7604Writer writer=new C7604Writer(bus);
        assertThrows(IOException.class,()->C7604Startup.initialize(writer,image(),1,high->{throw new IOException("denied");},ms->fail()));
        assertThrows(IOException.class,writer::readStatus); assertTrue(bus.writes.isEmpty());
        C7604Writer second=new C7604Writer(bus);
        try {
            assertThrows(IOException.class,()->C7604Startup.initialize(second,image(),1,high->{},ms->{throw new InterruptedException();}));
            assertTrue(Thread.currentThread().isInterrupted()); assertThrows(IOException.class,second::readStatus);
        } finally { Thread.interrupted(); }
    }
    @Test public void invalidOrClosedStartupNeverTouchesReset() throws Exception {
        C7604Writer writer=new C7604Writer(new C7604ProgramTest.Bus());
        assertThrows(IllegalArgumentException.class,()->C7604Startup.initialize(writer,image(),0,high->fail("reset called"),ms->fail("delay called")));
        assertEquals(4,writer.readStatus()); writer.close();
        assertThrows(IOException.class,()->C7604Startup.initialize(writer,image(),1,high->fail("reset called"),ms->fail("delay called")));
    }
}
