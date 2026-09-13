package com.cabin.hondacustom;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.view.*;
import android.widget.*;
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
    private static <T extends View> T find(View view,Class<T> type,String text){
        if(type.isInstance(view)&&(text==null||(view instanceof TextView&&text.equals(((TextView)view).getText().toString()))))return type.cast(view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            T match=find(((ViewGroup)view).getChildAt(i),type,text);if(match!=null)return match;
        }
        return null;
    }
    @Test public void headUnitPauseRequiresFreshParkedAcknowledgementBeforeNativeLaunch(){
        OemScreens.Screen screen=OemScreens.Screen.LANGUAGE;
        PackageInfo pkg=new PackageInfo();pkg.packageName=screen.component.getPackageName();
        ApplicationInfo app=new ApplicationInfo();app.packageName=pkg.packageName;app.enabled=true;pkg.applicationInfo=app;
        ActivityInfo info=new ActivityInfo();info.applicationInfo=app;info.packageName=pkg.packageName;info.name=screen.component.getClassName();info.enabled=true;info.exported=true;
        pkg.activities=new ActivityInfo[]{info};Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(pkg);
        try(org.robolectric.android.controller.ActivityController<HeadUnitActivity> controller=Robolectric.buildActivity(HeadUnitActivity.class).setup()){
            HeadUnitActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            CheckBox parked=find(root,CheckBox.class,null);Button language=find(root,Button.class,"Head-unit language");
            assertNotNull(parked);assertNotNull(language);assertTrue(language.isEnabled());
            parked.setChecked(true);controller.pause().resume();assertFalse(parked.isChecked());
            language.performClick();assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            parked.setChecked(true);language.performClick();
            assertEquals(screen.component,Shadows.shadowOf(activity).getNextStartedActivity().getComponent());assertFalse(parked.isChecked());
        }
    }

}
