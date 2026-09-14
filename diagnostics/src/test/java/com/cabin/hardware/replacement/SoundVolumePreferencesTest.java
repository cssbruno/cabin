package com.cabin.hardware.replacement;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class SoundVolumePreferencesTest {
    @Test public void remembersAcrossRecreationAndRejectsOtherProfilesAndMissingData() throws Exception {
        SharedPreferences preferences=RuntimeEnvironment.getApplication().getSharedPreferences("volume-test",Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        SoundVolumePreferences store=new SoundVolumePreferences(preferences,"verified-profile",30);
        assertThrows(IOException.class,store::restore);
        store.save(new int[] {10,15,20}); store.restore().remember(2,16);
        SoundVolumeMemory restored=new SoundVolumePreferences(preferences,"verified-profile",30).restore();
        assertEquals(10,restored.forSource(1)); assertEquals(16,restored.forSource(2)); assertEquals(20,restored.forSource(15));
        assertThrows(IOException.class,()->new SoundVolumePreferences(preferences,"another-profile",30).restore());
        assertThrows(IOException.class,()->new SoundVolumePreferences(preferences,"verified-profile",20).restore());
        preferences.edit().remove("media").commit(); assertThrows(IOException.class,store::restore);
        preferences.edit().putString("media","invalid").commit(); assertThrows(IOException.class,store::restore);
    }
}
