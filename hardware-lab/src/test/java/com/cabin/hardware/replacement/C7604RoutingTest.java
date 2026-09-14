package com.cabin.hardware.replacement;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class C7604RoutingTest {
    private C7604Writer ready(C7604ProgramTest.Bus bus) throws Exception {
        C7604Writer writer=new C7604Writer(bus);
        writer.loadProgram(new C7604ProgramImage(new int[] {2,0},new byte[] {1},new byte[] {2}),ms->{});
        bus.writes.clear(); return writer;
    }
    @Test public void allReferenceInputsAndBothPathsUseOrderedPackets() throws Exception {
        int[] selectors={8,12,2,8};
        for(int input=0;input<4;input++) for(boolean alternate:new boolean[] {false,true}) {
            C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus();
            try(C7604Writer writer=ready(bus)) {
                writer.selectInput(input,alternate); assertEquals(3,bus.writes.size());
                assertArrayEquals(new byte[] {(byte)0x80,0,6,0,0,(byte)(alternate?0x20:0x10)},bus.writes.get(0));
                assertArrayEquals(new byte[] {(byte)0xa4,0,0},bus.writes.get(1));
                assertArrayEquals(new byte[] {(byte)0xc0,0,(byte)0x8d,(byte)selectors[input]},bus.writes.get(2));
            }
        }
    }
    @Test public void partialRouteFailurePoisonsSessionWithoutRetryingOrSelectingInput() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus();
        try(C7604Writer writer=ready(bus)) {
            bus.failAt=2; assertThrows(IOException.class,()->writer.selectInput(1,true));
            assertEquals(2,bus.writes.size()); assertFalse(writer.programLoaded());
            assertThrows(IOException.class,()->writer.selectInput(0,false)); assertEquals(2,bus.writes.size());
        }
    }
    @Test public void rejectsUnconfiguredAndOutOfRangeRequestsBeforeWrites() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus();
        try(C7604Writer writer=new C7604Writer(bus)) {
            assertThrows(IOException.class,()->writer.selectInput(0,false));
            assertThrows(IllegalArgumentException.class,()->writer.selectInput(4,false));
            assertThrows(IllegalArgumentException.class,()->writer.selectInput(-1,false));
            assertTrue(bus.writes.isEmpty());
        }
    }
}
