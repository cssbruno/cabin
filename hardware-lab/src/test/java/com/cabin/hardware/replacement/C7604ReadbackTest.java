package com.cabin.hardware.replacement;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class C7604ReadbackTest {
    private static class Bus implements C7604Writer.Bus {
        byte[] response={4}; int reads,writes; boolean failure;
        public void write(byte[] data) { writes++; }
        public byte[] read(byte[] prefix,int count) throws IOException {
            reads++; assertArrayEquals(new byte[] {0x40,0,(byte)0xc0},prefix); assertEquals(1,count);
            if(failure) throw new IOException("bus failed"); return response;
        }
        public void close() { }
    }
    @Test public void readinessReadUsesReferencePrefixAndUnsignedByteWithoutWrites() throws Exception {
        Bus bus=new Bus(); try(C7604Writer device=new C7604Writer(bus)) {
            assertEquals(4,device.readStatus()); bus.response=new byte[] {(byte)0xff}; assertEquals(255,device.readStatus());
            assertEquals(0,bus.writes);
        }
    }
    @Test public void failedOrShortReadInvalidatesSessionAndBlocksFurtherWrites() {
        for(int mode=0;mode<3;mode++) {
            Bus bus=new Bus(); bus.failure=mode==0; bus.response=mode==1 ? null : new byte[0];
            try(C7604Writer device=new C7604Writer(bus)) {
                assertThrows(IOException.class,device::readStatus);
                assertThrows(IOException.class,device::readStatus);
                assertThrows(IOException.class,()->device.writeCoefficients(0x84,262,new int[] {0}));
                assertEquals(1,bus.reads); assertEquals(0,bus.writes);
            }
        }
    }
    @Test public void closedSessionCannotRead() {
        Bus bus=new Bus(); C7604Writer device=new C7604Writer(bus); device.close();
        assertThrows(IOException.class,device::readStatus); assertEquals(0,bus.reads);
    }
}
