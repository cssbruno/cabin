package com.cabin.hardware.replacement;
import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class AmplifierMuteTest {
    @Test public void independentReasonsCannotReleaseEachOther() throws Exception {
        List<Boolean> writes=new ArrayList<>(); AmplifierMute mute=new AmplifierMute(writes::add);
        assertNull(mute.lastApplied()); mute.initialize();
        mute.set(AmplifierMute.Reason.USER,true); mute.set(AmplifierMute.Reason.STARTUP,false);
        assertEquals(List.of(true),writes);
        mute.set(AmplifierMute.Reason.SOURCE_CHANGE,true); mute.set(AmplifierMute.Reason.USER,false);
        assertEquals(List.of(true),writes); mute.set(AmplifierMute.Reason.SOURCE_CHANGE,false);
        assertEquals(List.of(true,false),writes); assertTrue(mute.reasons().isEmpty());
    }
    @Test public void failureDropsAppliedStateAndLatchesFaultWithoutRetry() throws Exception {
        List<Boolean> writes=new ArrayList<>(); AmplifierMute mute=new AmplifierMute(value->{writes.add(value); if(!value) throw new IOException("uncertain write");});
        mute.initialize(); assertThrows(IOException.class,()->mute.set(AmplifierMute.Reason.STARTUP,false));
        assertNull(mute.lastApplied()); assertTrue(mute.reasons().contains(AmplifierMute.Reason.FAULT));
        assertThrows(IOException.class,mute::initialize); assertThrows(IOException.class,()->mute.set(AmplifierMute.Reason.FAULT,false));
        assertEquals(2,writes.size());
    }
    @Test public void uninitializedControlCannotUnmute() {
        AmplifierMute mute=new AmplifierMute(value->fail("unexpected write"));
        assertThrows(IllegalStateException.class,()->mute.set(AmplifierMute.Reason.STARTUP,false));
    }
}
