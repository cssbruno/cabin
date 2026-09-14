package com.cabin.hardware.replacement;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class C7604ChannelsTest {
    @Test public void centerAndCornerVectorsMatchFieldToRegisterMapping() {
        assertArrayEquals(new int[] {24,24,24,24},C7604Channels.field(8,8));
        assertArrayEquals(new int[] {24,255,255,255},C7604Channels.field(0,0));
        assertArrayEquals(new int[] {255,24,255,255},C7604Channels.field(16,0));
        assertArrayEquals(new int[] {255,255,24,255},C7604Channels.field(0,16));
        assertArrayEquals(new int[] {255,255,255,24},C7604Channels.field(16,16));
        assertArrayEquals(new int[] {30,24,30,24},C7604Channels.field(9,8));
        assertThrows(IllegalArgumentException.class,()->C7604Channels.field(-1,8));
        assertThrows(IllegalArgumentException.class,()->C7604Channels.field(8,17));
    }
    @Test public void mutedChannelsAndExplicitRestorationUseFourRegisterWrites() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus();
        try(C7604Writer writer=new C7604Writer(bus)) {
            assertThrows(IOException.class,()->writer.setSpeakerField(8,8,true));
            writer.loadProgram(new C7604ProgramImage(new int[] {2,0},new byte[] {1},new byte[] {2}),ms->{});
            bus.writes.clear(); writer.setSpeakerField(8,8,true);
            assertEquals(4,bus.writes.size());
            for(int i=0;i<4;i++) assertArrayEquals(new byte[] {(byte)0xc0,0,(byte)(0x83+i),(byte)0xff},bus.writes.get(i));
            bus.writes.clear(); writer.setSpeakerField(8,8,false);
            for(int i=0;i<4;i++) assertEquals(24,bus.writes.get(i)[3]&255);
            bus.writes.clear(); bus.failAt=2;
            assertThrows(IOException.class,()->writer.setSpeakerField(0,0,false));
            assertFalse(writer.programLoaded()); assertEquals(2,bus.writes.size());
            assertThrows(IOException.class,()->writer.setSpeakerField(8,8,false));
        }
    }
}
