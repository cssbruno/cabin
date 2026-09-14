package com.cabin.hardware.replacement;
import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class C7604SourceRoutingTest {
    private final C7604SourceRouting routing=new C7604SourceRouting(Map.of(1,0,2,1,3,2));
    @Test public void overridesAndPlatformTruthTableMatchReference() {
        assertEquals(2,routing.resolve(1,true,-1,false,false,false).appId);
        assertEquals(3,routing.resolve(1,true,3,false,false,false).appId);
        var digital=routing.resolve(1,false,-1,false,false,true); assertEquals(Boolean.TRUE,digital.androidIis); assertTrue(digital.dspAlternate);
        var analog=routing.resolve(1,false,-1,false,true,true); assertEquals(Boolean.FALSE,analog.androidIis); assertFalse(analog.dspAlternate);
        var other=routing.resolve(3,false,-1,false,false,true); assertEquals(Boolean.TRUE,other.androidIis); assertFalse(other.dspAlternate);
        var call=routing.resolve(1,true,-1,false,false,true); assertEquals(Boolean.FALSE,call.androidIis); assertFalse(call.dspAlternate);
        assertNull(routing.resolve(1,false,-1,false,false,false).androidIis);
        assertThrows(IllegalArgumentException.class,()->routing.resolve(4,false,-1,false,false,false));
    }
    @Test public void platformFailureStopsDspRouteAndInvalidatesSession() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus();
        try(C7604Writer writer=new C7604Writer(bus)) {
            writer.loadProgram(new C7604ProgramImage(new int[] {2,0},new byte[] {1},new byte[] {2}),ms->{}); bus.writes.clear();
            assertThrows(IOException.class,()->routing.resolve(1,false,-1,false,false,true).apply(writer,enabled->{throw new IOException("HAL failure");}));
            assertTrue(bus.writes.isEmpty()); assertFalse(writer.programLoaded());
        }
    }
    public static class Vendor {
        final List<Boolean> calls=new ArrayList<>();
        public void setVoiceSwitch2iis(boolean value) { calls.add(value); }
        public void setAudioSwitch2iis(boolean value) { calls.add(value); }
    }
    public static class Legacy {
        int state=-1;
        public void setWiredDeviceConnectionState(int device,int state,String address,String name) { assertEquals(8,device); this.state=state; }
    }
    @Test public void platformAdapterUsesBothVendorMethodsOrObservedFallback() throws Exception {
        Vendor vendor=new Vendor(); new AndroidAudioSwitch(vendor).setIis(true); assertEquals(List.of(true,true),vendor.calls);
        Legacy legacy=new Legacy(); AndroidAudioSwitch adapter=new AndroidAudioSwitch(legacy); adapter.setIis(true); assertEquals(0,legacy.state); adapter.setIis(false); assertEquals(1,legacy.state);
        assertThrows(IOException.class,()->new AndroidAudioSwitch(new Object()));
    }
    @Test public void successfulRouteSwitchesPlatformBeforeDsp() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus();
        try(C7604Writer writer=new C7604Writer(bus)) {
            writer.loadProgram(new C7604ProgramImage(new int[] {2,0},new byte[] {1},new byte[] {2}),ms->{}); bus.writes.clear();
            final boolean[] called={false};
            routing.resolve(1,false,-1,false,false,true).apply(writer,enabled->{assertTrue(enabled); assertTrue(bus.writes.isEmpty()); called[0]=true;});
            assertTrue(called[0]); assertEquals(3,bus.writes.size());
            assertArrayEquals(new byte[] {(byte)0xc0,0,(byte)0x8d,8},bus.writes.get(2));
        }
    }
}
