package com.cabin.hondacustom;

import java.util.Locale;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class CatalogSafetyTest {
    @Test public void portugueseLanguageKeepsExactApiOrder()throws Exception{
        Locale old=Locale.getDefault();try{
            Locale.setDefault(new Locale("pt","BR"));Catalog c=new Catalog(RuntimeEnvironment.getApplication());
            Catalog.Entry e=c.byKey.get("3:14");
            assertEquals("Português",e.label(1));assertEquals("English",e.label(2));
            assertTrue(e.allows(new Setting(3,14,1,0,new int[]{1,2},null),2));
            for(Catalog.Entry row:c.entries){assertFalse(row.title.trim().isEmpty());for(String name:row.options.values())assertFalse(name.trim().isEmpty());}
        }finally{Locale.setDefault(old);}
    }
    @Test public void ambiguousAndUnmappedValuesCannotBeWritten()throws Exception{
        Catalog c=new Catalog(RuntimeEnvironment.getApplication());
        Catalog.Entry colors=c.byKey.get("3:42");Setting live=new Setting(3,42,2,0,new int[]{1,8},null);
        assertFalse(colors.allows(live,1));assertFalse(colors.allows(live,8));assertTrue(colors.allows(live,2));
        Catalog.Entry languages=c.byKey.get("3:95");
        assertFalse(languages.allows(new Setting(3,95,1,0,new int[]{1,20},null),20));
        Catalog.Entry eu=c.byKey.get("3:13");
        assertFalse(eu.allows(new Setting(3,13,1,0,new int[]{1,7},null),7));
    }
    @Test public void civicOfflineFilterExcludesUnrelatedEquipment()throws Exception{
        Catalog c=new Catalog(RuntimeEnvironment.getApplication());CivicScope scope=new CivicScope(RuntimeEnvironment.getApplication());
        assertTrue(scope.contains(c.byKey.get("3:14")));assertTrue(scope.contains(c.byKey.get("3:91")));
        assertTrue(scope.contains(c.byKey.get("2:68")));assertFalse(scope.contains(c.byKey.get("10:2")));
        assertFalse(scope.contains(c.byKey.get("3:45")));assertFalse(scope.contains(c.byKey.get("4:1")));
    }
    @Test public void editorNeverExpandsVehicleReportedRange()throws Exception{
        Catalog.Entry e=new Catalog(RuntimeEnvironment.getApplication()).byKey.get("3:14");
        Setting limited=new Setting(3,14,1,0,new int[]{1,1},null);
        assertTrue(e.allows(limited,1));assertFalse(e.allows(limited,2));
    }
    @Test public void separatePanelPresetHasNamedWritableValues()throws Exception{
        Catalog.Entry e=new Catalog(RuntimeEnvironment.getApplication()).byKey.get("3:91");
        Setting live=new Setting(3,91,1,0,new int[]{1,3},null);
        assertEquals("Panel configuration",e.title);assertEquals("Preset 3",e.label(3));
        assertTrue(e.allows(live,3));assertFalse(e.allows(live,4));
    }
}
