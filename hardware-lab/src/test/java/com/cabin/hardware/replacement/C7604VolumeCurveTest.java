package com.cabin.hardware.replacement;
import org.junit.Test;
import static org.junit.Assert.*;

public class C7604VolumeCurveTest {
    @Test public void zeroBypassesOffsetsAndNormalLevelsUseDriverBaseline() {
        C7604VolumeCurve curve=new C7604VolumeCurve(new int[] {-1450,-600,-500,-400});
        assertEquals(-145,curve.decibels(0,50,2,120),0);
        assertEquals(-70,curve.decibels(1,0,0,0),0);
        assertEquals(-65,curve.decibels(1,50,0,0),0);
        assertEquals(-50,curve.decibels(3,0,0,0),0);
    }
    @Test public void compensationUsesThresholdsFractionAndUpperIndexBound() {
        C7604VolumeCurve curve=new C7604VolumeCurve(new int[] {-1450,-700,-600,-500});
        assertEquals(-70,curve.decibels(1,0,2,60),0);
        assertEquals(-60,curve.decibels(1,0,2,90),0);
        assertEquals(-50,curve.decibels(1,0,2,120),0);
        assertEquals(-50,curve.decibels(2,0,2,255),0);
        assertEquals(-50,curve.decibels(3,0,2,255),0);
    }
    @Test public void descendingTablesPreserveSignAndInputsAreDefensive() {
        int[] source={-1450,-500,-600}; C7604VolumeCurve curve=new C7604VolumeCurve(source); source[1]=0;
        assertEquals(-30,curve.decibels(1,0,0,0),0);
        assertThrows(IllegalArgumentException.class,()->curve.decibels(3,0,0,0));
        assertThrows(IllegalArgumentException.class,()->curve.decibels(1,0,-1,0));
        assertThrows(IllegalArgumentException.class,()->new C7604VolumeCurve(new int[] {1,2}));
    }
    @Test public void mappedVolumeReachesTheProgrammedWriter() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus();
        try(C7604Writer writer=new C7604Writer(bus)) {
            writer.loadProgram(new C7604ProgramImage(new int[] {2,0},new byte[] {1},new byte[] {2}),ms->{});
            bus.writes.clear(); writer.setVolumeLevel(new C7604VolumeCurve(new int[] {-1450,-700,-600}),1,0,0,0);
            assertArrayEquals(C7604Gain.packet(-70),bus.writes.get(0)); assertEquals(2,bus.writes.size());
        }
    }
}
