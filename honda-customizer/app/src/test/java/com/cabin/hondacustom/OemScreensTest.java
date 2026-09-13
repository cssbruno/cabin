package com.cabin.hondacustom;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class OemScreensTest {
    @Test public void missingHeadUnitScreenIsExplained(){
        assertNotNull(OemScreens.unavailable(RuntimeEnvironment.getApplication(),OemScreens.Screen.LANGUAGE));
    }
    @Test public void nonExportedScreenCannotBeOpened(){
        OemScreens.Screen screen=OemScreens.Screen.LANGUAGE;
        PackageInfo pkg=new PackageInfo();pkg.packageName=screen.component.getPackageName();
        ApplicationInfo app=new ApplicationInfo();app.packageName=pkg.packageName;app.enabled=true;pkg.applicationInfo=app;
        ActivityInfo info=new ActivityInfo();info.applicationInfo=app;info.packageName=pkg.packageName;info.name=screen.component.getClassName();info.enabled=true;info.exported=false;
        pkg.activities=new ActivityInfo[]{info};Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(pkg);
        assertNotNull(OemScreens.unavailable(RuntimeEnvironment.getApplication(),screen));
        info.exported=true;Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(pkg);
        assertNull(OemScreens.unavailable(RuntimeEnvironment.getApplication(),screen));
    }
    @Test public void parkedAcknowledgementRequired(){
        try(org.robolectric.android.controller.ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            assertTrue(OemScreens.open(activity.get(),OemScreens.Screen.SYSTEM,false).contains("parked"));
            assertNull(Shadows.shadowOf(activity.get()).getNextStartedActivity());
        }
    }
    @Test public void headUnitScreenDoesNotLaunchVendorAppOnOpen(){
        try(org.robolectric.android.controller.ActivityController<HeadUnitActivity> activity=Robolectric.buildActivity(HeadUnitActivity.class).setup()){
            assertNull(Shadows.shadowOf(activity.get()).getNextStartedActivity());
        }
    }
    @Test public void panelEditorLaunchesWithoutWritingOrOpeningVendorApps(){
        try(org.robolectric.android.controller.ActivityController<MeterActivity> activity=Robolectric.buildActivity(MeterActivity.class).setup()){
            assertNull(Shadows.shadowOf(activity.get()).getNextStartedActivity());
        }
    }
}
