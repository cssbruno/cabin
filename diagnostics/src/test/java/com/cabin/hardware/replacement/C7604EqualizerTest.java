package com.cabin.hardware.replacement;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class C7604EqualizerTest {
    @Test public void quarterRateMatchesReferenceIncludingNearZeroNegativeQuantization() {
        C7604Equalizer.Plan regular=C7604Equalizer.plan(20,12000,10,10);
        assertEquals(0x84,regular.command); assertEquals(262,regular.register);
        assertArrayEquals(new int[] {1398101,-1,-1398101,0,4194304},regular.words());
        C7604Equalizer.Plan extended=C7604Equalizer.plan(0,12000,5,10);
        assertEquals(0x89,extended.command); assertEquals(62,extended.register);
        assertArrayEquals(new int[] {0,0,-256,0,0,0,0,0,4194304,0},extended.words());
    }
    @Test public void allBandsHaveNonOverlappingVerifiedRegisterSpans() {
        Set<Integer> registers=new HashSet<>();
        for(int band=0;band<36;band++) {
            C7604Equalizer.Plan plan=C7604Equalizer.plan(band,1000,10,16);
            assertEquals(band<20 ? 10 : 5,plan.words().length);
            for(int i=0;i<plan.words().length;i++) assertTrue(registers.add(plan.register+i));
            assertEquals(3+plan.words().length*3,plan.packet().length);
        }
        assertEquals(280,registers.size()); assertTrue(registers.contains(62)); assertTrue(registers.contains(341));
    }
    private double[] decoded(C7604Equalizer.Plan plan) {
        int[] words=plan.words(); double[] coefficients=new double[5];
        for(int i=0;i<5;i++) coefficients[i]=words.length==5 ? words[i]/4194304.0 : (words[2*i]/256.0+words[2*i+1]/8388608.0)/16384.0;
        return coefficients;
    }
    private double response(double[] c,double omega) {
        double nr=c[4]+c[1]*Math.cos(omega)+c[0]*Math.cos(2*omega), ni=-c[1]*Math.sin(omega)-c[0]*Math.sin(2*omega);
        double dr=1-c[3]*Math.cos(omega)-c[2]*Math.cos(2*omega), di=c[3]*Math.sin(omega)+c[2]*Math.sin(2*omega);
        return Math.sqrt((nr*nr+ni*ni)/(dr*dr+di*di));
    }
    @Test public void quantizedFiltersMeetCenterGainAndUnityAtDcAndNyquist() {
        for(int band:new int[] {0,20,35}) for(int gain:new int[] {0,4,10,16,20}) {
            double[] c=decoded(C7604Equalizer.plan(band,1000,10,gain));
            assertEquals(Math.pow(10,(gain-10)/20.0),response(c,2*Math.PI*1000/48000),0.0002);
            assertEquals(1,response(c,0),0.0002); assertEquals(1,response(c,Math.PI),0.0002);
            // Jury stability conditions for denominator 1-a1*z^-1-a2*z^-2.
            assertTrue(Math.abs(c[2])<1); assertTrue(1-c[3]-c[2]>0); assertTrue(1+c[3]-c[2]>0);
        }
    }
    @Test public void invalidBandNyquistQAndGainAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> C7604Equalizer.plan(36,1000,10,10));
        assertThrows(IllegalArgumentException.class,() -> C7604Equalizer.plan(0,24000,10,10));
        assertThrows(IllegalArgumentException.class,() -> C7604Equalizer.plan(0,1000,0,10));
        assertThrows(IllegalArgumentException.class,() -> C7604Equalizer.plan(0,1000,10,21));
    }
    @Test public void generatedPlanUsesExistingTransactionalWriter() throws Exception {
        List<byte[]> writes=new ArrayList<>();
        try(C7604Writer writer=new C7604Writer(new C7604Writer.Bus() { public void write(byte[] bytes) { writes.add(bytes); } public void close() { } })) {
            C7604Equalizer.Plan plan=C7604Equalizer.plan(35,17000,10,4); plan.apply(writer);
            assertArrayEquals(plan.packet(),writes.get(0)); assertArrayEquals(new byte[] {(byte)0xa4,0,0},writes.get(1));
        }
    }
}
