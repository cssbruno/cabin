package com.cabin.hardware.replacement;

import org.junit.Test;
import static org.junit.Assert.*;

public class SoundVolumeCommandTest {
    @Test public void absoluteAndRelativeVolumeClampAndReleaseUserMute() {
        SoundVolumeCommand absolute=SoundVolumeCommand.resolve(Integer.MAX_VALUE,10,30,2,true,true);
        assertEquals(Integer.valueOf(30),absolute.level); assertEquals(Boolean.FALSE,absolute.muted);
        assertEquals(Integer.valueOf(30),SoundVolumeCommand.resolve(-1,29,30,2,false,true).level);
        assertEquals(Integer.valueOf(0),SoundVolumeCommand.resolve(-2,1,30,2,false,true).level);
    }
    @Test public void boundaryStepsDoNotUnmuteAndMuteActionsRespectCallPolicy() {
        SoundVolumeCommand limit=SoundVolumeCommand.resolve(-1,30,30,2,true,true);
        assertNull(limit.level); assertNull(limit.muted);
        assertNull(SoundVolumeCommand.resolve(-2,0,30,2,true,true).muted);
        assertEquals(Boolean.TRUE,SoundVolumeCommand.resolve(-3,10,30,2,false,true).muted);
        assertNull(SoundVolumeCommand.resolve(-3,10,30,2,true,true).muted);
        assertEquals(Boolean.FALSE,SoundVolumeCommand.resolve(-5,10,30,2,false,false).muted);
        assertEquals(Boolean.FALSE,SoundVolumeCommand.resolve(-4,10,30,2,true,false).muted);
    }
    @Test public void missingStateAndUnimplementedLifecycleActionsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->SoundVolumeCommand.resolve(5,-1,30,2,false,true));
        assertThrows(IllegalArgumentException.class,()->SoundVolumeCommand.resolve(5,0,30,0,false,true));
        assertThrows(UnsupportedOperationException.class,()->SoundVolumeCommand.resolve(-6,0,30,2,false,true));
        assertThrows(UnsupportedOperationException.class,()->SoundVolumeCommand.resolve(-7,0,30,2,false,true));
    }
}
