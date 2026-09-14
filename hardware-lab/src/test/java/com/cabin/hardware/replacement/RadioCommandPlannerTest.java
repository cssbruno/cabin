package com.cabin.hardware.replacement;
import org.junit.Test;
import static org.junit.Assert.*;

public class RadioCommandPlannerTest {
    @Test public void referenceControlVectorsMatchServiceDispatch() {
        int[] codes={0,1,3,4,5,6,9,10}; int[] opcodes={0x0a,0x09,0x11,0x10,0x06,0x05,0x12,0x08};
        for(int i=0;i<codes.length;i++) assertArrayEquals(new byte[] {1,3,(byte)opcodes[i]},RadioCommandPlanner.payload(1,codes[i]));
        assertArrayEquals(new byte[] {(byte)0x88,0x55,0,3,1,3,0x11,0x10},RadioCommandPlanner.frame(1,3));
    }
    @Test public void amFmPresetBoundariesPreserveDistinctEncodings() {
        assertEquals(0xe5,RadioCommandPlanner.payload(1,7,0)[2]&255);
        assertEquals(0xf0,RadioCommandPlanner.payload(1,7,11)[2]&255);
        assertEquals(0x81,RadioCommandPlanner.payload(1,7,65536)[2]&255);
        assertEquals(0x92,RadioCommandPlanner.payload(1,7,65553)[2]&255);
        assertEquals(0x53,RadioCommandPlanner.payload(1,8,0)[2]&255);
        assertEquals(0x52,RadioCommandPlanner.payload(1,8,65553)[2]&255);
        assertThrows(IllegalArgumentException.class,() -> RadioCommandPlanner.payload(1,7,12));
        assertThrows(IllegalArgumentException.class,() -> RadioCommandPlanner.payload(1,8,65554));
    }
    @Test public void bandAndDirectFrequencyUseVerifiedWireFormats() {
        assertArrayEquals(new byte[] {1,3,0x18},RadioCommandPlanner.payload(1,11,-1));
        assertArrayEquals(new byte[] {1,3,0x1d},RadioCommandPlanner.payload(1,11,0));
        assertArrayEquals(new byte[] {1,3,0x1c},RadioCommandPlanner.payload(1,11,65538));
        assertArrayEquals(new byte[] {0x25,0x27,0x74},RadioCommandPlanner.payload(1,13,3,10100));
        assertThrows(UnsupportedOperationException.class,() -> RadioCommandPlanner.payload(1,13,1,10100));
        assertThrows(IllegalArgumentException.class,() -> RadioCommandPlanner.payload(1,13,3,10801));
    }
    @Test public void driverMismatchUnknownCodesAndWrongArityNeverRecordCommands() {
        assertThrows(UnsupportedOperationException.class,() -> RadioCommandPlanner.frame(4,3));
        assertThrows(UnsupportedOperationException.class,() -> RadioCommandPlanner.frame(1,999));
        assertThrows(IllegalArgumentException.class,() -> RadioCommandPlanner.frame(1,3,0));
        CommandJournal journal=new CommandJournal(); RadioPreviewModule module=new RadioPreviewModule(journal);
        assertThrows(IllegalArgumentException.class,() -> module.command(3,new ModulePayload(null,new float[] {1},null)));
        assertEquals(0,journal.total()); module.close(); assertThrows(IllegalStateException.class,() -> module.command(3,ModulePayload.EMPTY));
    }
    @Test public void previewJournalIsBoundedAndProvidesNoInventedRadioState() {
        CommandJournal journal=new CommandJournal(); RadioPreviewModule module=new RadioPreviewModule(journal);
        for(int i=0;i<100;i++) module.command(3,ModulePayload.EMPTY);
        assertEquals(100,journal.total()); assertEquals(64,journal.snapshot().size()); assertEquals(37L,journal.snapshot().get(0).get("sequence"));
        assertTrue(module.cached(1).isEmpty()); assertNull(module.get(1,ModulePayload.EMPTY));
    }
}
