package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class RadioMcuTransportTest {
    @Test public void volumeAndMuteUseVerifiedFramesAndRejectUnresolvedValues() throws Exception {
        Device device=new Device(); RadioMcuTransport transport=new RadioMcuTransport(device);
        transport.sendVolume(30);
        assertArrayEquals(new byte[] {(byte)0x88,0x55,0,3,(byte)0xe7,30,0,(byte)0xfa},device.sent);
        transport.sendUserMute(true);
        assertArrayEquals(new byte[] {(byte)0x88,0x55,0,3,1,0,(byte)0xa1,(byte)0xa3},device.sent);
        transport.sendUserMute(false);
        assertArrayEquals(new byte[] {(byte)0x88,0x55,0,3,1,0,(byte)0xa0,(byte)0xa2},device.sent);
        assertThrows(IllegalArgumentException.class,()->transport.sendVolume(-1));
        assertThrows(IllegalArgumentException.class,()->transport.sendVolume(256));
        assertEquals(3,device.writes); assertEquals(3,transport.completedWrites()); transport.close();
    }
    @Test public void audioWriteFailureAlsoRetiresRadioWithoutRetry() {
        Device device=new Device(); device.fail=true; RadioMcuTransport transport=new RadioMcuTransport(device);
        assertThrows(IOException.class,()->transport.sendVolume(12));
        assertThrows(IOException.class,()->transport.sendRadio(3));
        assertThrows(IOException.class,()->transport.sendUserMute(true));
        assertEquals(1,device.writes); assertEquals(1,device.closes);
    }
    static class Device implements McuTransport {
        byte[] sent; int writes, closes; boolean fail;
        public int read(byte[] bytes) { return -1; }
        public void write(byte[] frame) throws IOException { writes++; sent=frame.clone(); if(fail) throw new IOException("partial write"); }
        public void close() { closes++; }
    }
    @Test public void sendsExactFrameAndRejectsUnsupportedBeforeIo() throws Exception {
        Device device=new Device(); RadioMcuTransport transport=new RadioMcuTransport(device);
        transport.sendRadio(3);
        assertArrayEquals(new byte[] {(byte)0x88,0x55,0,3,1,3,0x11,0x10},device.sent);
        assertEquals(1,transport.completedWrites());
        assertThrows(UnsupportedOperationException.class,()->transport.sendRadio(999));
        assertThrows(IllegalArgumentException.class,()->transport.sendRadio(7,12));
        assertThrows(UnsupportedOperationException.class,()->transport.write(new byte[] {1}));
        assertEquals(1,device.writes); transport.close(); transport.close(); assertEquals(1,device.closes);
    }
    @Test public void failedWriteClosesConnectionAndIsNeverRetried() {
        Device device=new Device(); device.fail=true; RadioMcuTransport transport=new RadioMcuTransport(device);
        assertThrows(IOException.class,()->transport.sendRadio(3));
        assertThrows(IOException.class,()->transport.sendRadio(3));
        assertEquals(1,device.writes); assertEquals(1,device.closes); assertEquals(0,transport.completedWrites());
        assertFalse(transport.isOpen()); assertTrue(transport.failure().contains("partial write"));
    }
    @Test public void eofDisablesCommands() throws Exception {
        Device device=new Device(); RadioMcuTransport transport=new RadioMcuTransport(device);
        assertEquals(-1,transport.read(new byte[10]));
        assertThrows(IOException.class,()->transport.sendRadio(3)); assertEquals(0,device.writes);
    }
    @Test public void concurrentCommandIsRejectedAndCloseCancelsInflightWrite() throws Exception {
        CountDownLatch writing=new CountDownLatch(1), release=new CountDownLatch(1);
        Device device=new Device() {
            @Override public void write(byte[] frame) throws IOException {
                writes++; writing.countDown();
                try { if(!release.await(2,TimeUnit.SECONDS)) throw new IOException("test timed out"); }
                catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
            }
            @Override public void close() { closes++; release.countDown(); }
        };
        RadioMcuTransport transport=new RadioMcuTransport(device); AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread first=new Thread(()->{try { transport.sendRadio(3); } catch(Throwable ex) { failure.set(ex); }});
        first.start();
        try {
            assertTrue(writing.await(2,TimeUnit.SECONDS));
            IOException busy=assertThrows(IOException.class,()->transport.sendRadio(4)); assertTrue(busy.getMessage().contains("busy"));
            assertTrue(assertThrows(IOException.class,()->transport.sendVolume(10)).getMessage().contains("busy"));
            assertTrue(assertThrows(IOException.class,()->transport.sendUserMute(true)).getMessage().contains("busy"));
            transport.close(); first.join(2000); assertFalse(first.isAlive()); assertTrue(failure.get() instanceof IOException);
            assertEquals(1,device.writes); assertEquals(0,transport.completedWrites()); assertEquals(1,device.closes);
        } finally { release.countDown(); first.join(2000); transport.close(); }
    }
}
