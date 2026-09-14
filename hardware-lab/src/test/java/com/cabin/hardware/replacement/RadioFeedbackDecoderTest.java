package com.cabin.hardware.replacement;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class RadioFeedbackDecoderTest {
    private final RadioFeedbackDecoder decoder = new RadioFeedbackDecoder();
    private Map<Integer,Integer> feed(int code, int value, long time) {
        return decoder.accept(new byte[] {1,3,(byte)code,(byte)value},time);
    }
    private Map<Integer,Integer> tune(int high,int middle,int low) {
        feed(1,high,0); feed(2,middle,1); return feed(3,low,2);
    }
    @Test public void decodesFmAndAmWithExplicitBand() {
        assertEquals(Integer.valueOf(65536),feed(6,0,0).get(0));
        assertEquals(Integer.valueOf(10100),tune(1,1,0).get(1));
        assertEquals(Integer.valueOf(1),feed(6,11,0).get(0));
        assertEquals(Integer.valueOf(810),tune(0,8,10).get(1));
    }
    @Test public void rejectsUnknownBandsAndFrequencyRanges() {
        assertTrue(tune(1,1,0).isEmpty());
        feed(6,0,0); assertTrue(tune(1,8,1).isEmpty());
        assertTrue(tune(0,64,99).isEmpty());
        assertTrue(feed(6,3,0).isEmpty()); assertTrue(tune(1,1,0).isEmpty());
    }
    @Test public void requiresOrderedCompleteSequence() {
        feed(6,0,0); feed(1,1,0); assertTrue(feed(3,0,1).isEmpty());
        feed(1,1,0); feed(2,1,1); feed(2,1,2); assertTrue(feed(3,0,3).isEmpty());
        feed(1,1,0); feed(2,100,1); assertTrue(feed(3,0,2).isEmpty());
        assertEquals(Integer.valueOf(10100),tune(1,1,0).get(1));
    }
    @Test public void timeoutAndClockRollbackDiscardPartialFrequency() {
        feed(6,0,0); feed(1,1,0); feed(2,1,1); assertTrue(feed(3,0,1000).isEmpty());
        feed(1,1,100); feed(2,1,99); assertTrue(feed(3,0,101).isEmpty());
    }
    @Test public void sessionLossBandChangeAndMalformedMessagesDiscardAssembly() {
        feed(6,0,0); feed(1,1,0); feed(2,1,1); decoder.reset(); assertTrue(feed(3,0,2).isEmpty());
        assertTrue(tune(1,1,0).isEmpty());
        feed(6,0,0); feed(1,1,0); feed(6,1,1); feed(2,1,2); assertTrue(feed(3,0,3).isEmpty());
        feed(1,1,0); decoder.accept(new byte[] {1,3,2},1); assertTrue(feed(3,0,2).isEmpty());
    }
    @Test public void presetFrequencyAndUnknownControlMessagesNeverBecomeCurrentFrequency() {
        feed(6,0,0); assertTrue(tune(11,1,0).isEmpty());
        feed(1,1,0); feed(0x10,1,1); feed(2,1,2); assertTrue(feed(3,0,3).isEmpty());
        assertTrue(feed(0x80,1,0).isEmpty());
    }
    @Test public void decodesIndexedPresetBoundariesWithoutCurrentBand() {
        int[][] vectors={{1,65536,11,1,0,10100},{18,65553,10,87,50,8750},{0x65,0,10,8,10,810},{0x70,11,10,16,20,1620}};
        for(int[] v:vectors) {
            feed(0x10,v[0],0); assertTrue(tune(v[2],v[3],v[4]).isEmpty());
            assertEquals(Integer.valueOf(v[5]),decoder.presetUpdates().get(v[1]));
            assertEquals(1,decoder.presetUpdates().size());
        }
    }
    @Test public void presetSelectorIsBoundedExpiresAndCannotBeReused() {
        for(int selector:new int[] {0,19,0x64,0x71,255}) {
            feed(0x10,selector,0); tune(11,1,0); assertTrue(decoder.presetUpdates().isEmpty());
        }
        feed(0x10,1,0); feed(1,11,999); feed(2,1,1000); feed(3,0,1001); assertTrue(decoder.presetUpdates().isEmpty());
        feed(0x10,1,0); tune(11,1,0); assertEquals(1,decoder.presetUpdates().size());
        tune(11,1,0); assertTrue(decoder.presetUpdates().isEmpty());
        feed(0x10,1,0); decoder.reset(); tune(11,1,0); assertTrue(decoder.presetUpdates().isEmpty());
    }
}
