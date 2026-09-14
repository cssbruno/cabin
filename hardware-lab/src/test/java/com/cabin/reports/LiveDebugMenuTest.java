package com.cabin.reports;

import android.app.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public class LiveDebugMenuTest {
    private ActivityController<Activity> controller;
    private AlertDialog dialog;
    @Before public void setup() {
        DebugJournal.clear(); controller=Robolectric.buildActivity(Activity.class).setup();
        LiveDebugMenu.show(controller.get()); dialog=ShadowAlertDialog.getLatestAlertDialog();
        shadowOf(Looper.getMainLooper()).idle();
    }
    @After public void cleanup() { dialog.dismiss(); controller.pause().stop().destroy(); DebugJournal.clear(); }
    private void tick() { shadowOf(Looper.getMainLooper()).idleFor(600,TimeUnit.MILLISECONDS); }
    private TextView find(View root,String text) {
        if(root instanceof TextView && ((TextView)root).getText().toString().contains(text)) return (TextView)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            TextView found=find(((ViewGroup)root).getChildAt(i),text); if(found!=null)return found;
        }
        return null;
    }
    private TextView find(String text) { return find(dialog.getWindow().getDecorView(),text); }
    @Test public void updatesLiveButPauseFreezesViewUntilResume() {
        DebugJournal.record("CAN","callback","door opened"); tick(); assertNotNull(find("door opened"));
        find("Pause").performClick();
        DebugJournal.record("CarPlay","connected","video ready"); tick(); assertNull(find("video ready"));
        find("Resume").performClick(); assertNotNull(find("video ready"));
        assertFalse(controller.get().isFinishing());
    }
    @Test public void clearRemovesVisibleHistoryAndNewEventsStillArrive() {
        DebugJournal.record("CAN","error","old error"); tick();
        find("Clear").performClick(); assertEquals(0,DebugJournal.snapshot().length()); assertNull(find("old error"));
        DebugJournal.record("CAN","connected","new connection"); tick(); assertNotNull(find("new connection"));
    }
    @Test public void filterKeepsOnlySelectedSubsystem() {
        DebugJournal.record("CAN","sample","door"); DebugJournal.record("CarPlay","error","socket");
        assertEquals(1,LiveDebugMenu.filter(DebugJournal.snapshot(),"CAN").length());
        assertEquals(2,LiveDebugMenu.filter(DebugJournal.snapshot(),"All").length());
        assertEquals(0,LiveDebugMenu.filter(DebugJournal.snapshot(),"export").length());
    }
    @Test public void dismissedMenuDoesNotKeepRefreshing() {
        DebugJournal.record("CAN","sample","before dismiss"); tick(); TextView output=find("before dismiss");
        dialog.dismiss(); DebugJournal.record("CAN","sample","after dismiss"); tick();
        assertFalse(output.getText().toString().contains("after dismiss"));
    }
}
