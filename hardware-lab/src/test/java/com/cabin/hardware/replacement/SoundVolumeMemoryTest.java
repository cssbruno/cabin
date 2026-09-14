package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoundVolumeMemoryTest {
    @Test public void sourceBanksAreIndependentAndCallbacksKeepVendorIndices() throws Exception {
        List<int[]> writes=new ArrayList<>(); int[] restored={10,15,20};
        SoundVolumeMemory memory=new SoundVolumeMemory(30,restored,writes::add); restored[0]=0;
        memory.remember(2,16); memory.remember(15,21); memory.remember(1,11);
        assertEquals(11,memory.forSource(0)); assertEquals(11,memory.forSource(9));
        assertEquals(16,memory.forSource(2)); assertEquals(21,memory.forSource(15));
        assertArrayEquals(new int[] {11,16,21},writes.get(2));
        for(int bank=0;bank<3;bank++) assertArrayEquals(new int[] {bank,writes.get(2)[bank]},memory.cached().get(bank).integers());
        memory.remember(1,11); assertEquals(3,writes.size());
    }
    @Test public void failedPersistenceDoesNotPublishAnUnsavedValue() {
        SoundVolumeMemory memory=new SoundVolumeMemory(30,new int[] {10,15,20},levels->{levels[1]=0; throw new IOException("storage failed");});
        assertThrows(IOException.class,()->memory.remember(2,16));
        assertEquals(15,memory.forSource(2));
        assertThrows(IllegalArgumentException.class,()->memory.remember(-1,10));
        assertThrows(IllegalArgumentException.class,()->memory.remember(1,31));
    }
}
