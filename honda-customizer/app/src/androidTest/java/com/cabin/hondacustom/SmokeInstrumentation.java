package com.cabin.hondacustom;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Framework-only installation/navigation checks against the real installed release APK.
 * Runs exclusively on a disposable emulator with no OEM vehicle services.
 */
public final class SmokeInstrumentation extends Instrumentation {
    private Activity activity;
    private final List<String> passed=new ArrayList<>();
    private interface Check {void run()throws Exception;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            if(!Build.FINGERPRINT.contains("generic")&&!Build.MODEL.contains("sdk"))throw new IllegalStateException("Smoke checks require an emulator, not vehicle hardware");
            check("main_installs_and_launches",()->{open(MainActivity.class);require(find("Civic catalog")!=null,"Civic filter missing");});
            check("missing_vehicle_service_fails_closed",()->{click("Connect");awaitText("Honda service not found");require(find("Connect")!=null,"Connect did not recover");});
            check("offline_civic_catalog_and_search",()->{
                click("Civic catalog");require(find("Panel configuration")!=null,"Preset setting missing");
                runOnMainSync(()->{EditText edit=(EditText)findType(activity.getWindow().getDecorView(),EditText.class);edit.setText("language");});waitForIdleSync();
                require(find("Language Selection")!=null,"Language search returned no Civic setting");
            });
            check("panel_navigation_missing_service",()->{click("Panel");waitForIdleSync();awaitActivity(MeterActivity.class);click("Connect");awaitText("Honda meter service unavailable");});
            check("camera_navigation_missing_service",()->{open(MainActivity.class);click("Cameras");waitForIdleSync();awaitActivity(CameraActivity.class);click("Connect to Honda");awaitText("Honda camera service is unavailable");});
            check("head_unit_missing_editors_disabled",()->{open(MainActivity.class);click("Head unit");waitForIdleSync();awaitActivity(HeadUnitActivity.class);require(!find("Head-unit language").isEnabled(),"Missing OEM editor enabled");});
            check("service_actions_require_live_support",()->{
                open(MainActivity.class);click("Service");waitForIdleSync();
                requireDisabledDialogControl("Calibrate tire-pressure system");
                requireDisabledDialogControl("Restore vehicle customization defaults");
            });
            result.putInt("passed",passed.size());result.putInt("failures",0);
            result.putString("checks",passed.toString());result.putString("stream","\nPASS "+passed.size()+" emulator smoke checks\n"+passed+"\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable e){
            result.putInt("passed",passed.size());result.putInt("failures",1);result.putString("stream","\nFAIL "+e+"\nPassed: "+passed+"\n");
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    private void check(String name,Check check)throws Exception{android.util.Log.i("HondaSmoke","START "+name);check.run();passed.add(name);android.util.Log.i("HondaSmoke","PASS "+name);}
    private void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
    private void open(Class<? extends Activity> type){
        if(activity!=null)runOnMainSync(()->activity.finish());waitForIdleSync();
        activity=startActivitySync(new Intent(getTargetContext(),type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
    }
    private void awaitActivity(Class<? extends Activity> type){
        // Lifecycle callbacks below track the actual activity opened by the app's button.
        awaitText(type==MeterActivity.class?"Instrument-panel contents":type==CameraActivity.class?"Honda camera settings":"Head-unit settings");
        require(type.isInstance(activity),"Wrong destination activity");
    }
    @Override public void callActivityOnResume(Activity next){super.callActivityOnResume(next);activity=next;}
    private View find(String part){final View[] out=new View[1];runOnMainSync(()->out[0]=findText(activity.getWindow().getDecorView(),part));return out[0];}
    private View findText(View view,String part){
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(part))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),part);if(found!=null)return found;}
        return null;
    }
    private View findType(View view,Class<?> type){
        if(type.isInstance(view))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findType(((ViewGroup)view).getChildAt(i),type);if(found!=null)return found;}
        return null;
    }
    private View findControl(View view,String label){
        if(view instanceof TextView&&view.isClickable()&&((TextView)view).getText().toString().equals(label))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findControl(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private void requireDisabledDialogControl(String label){
        android.app.UiAutomation automation=getUiAutomation();
        android.view.accessibility.AccessibilityNodeInfo root=null;
        long deadline=SystemClock.uptimeMillis()+5000;
        while(SystemClock.uptimeMillis()<deadline){root=automation.getRootInActiveWindow();if(root!=null&&!root.findAccessibilityNodeInfosByText(label).isEmpty())break;SystemClock.sleep(50);}
        require(root!=null,"Dialog accessibility tree unavailable");
        List<android.view.accessibility.AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(label);
        require(!nodes.isEmpty(),"Missing dialog control: "+label);
        for(android.view.accessibility.AccessibilityNodeInfo node:nodes)require(!node.isEnabled(),"Offline action enabled: "+label);
    }
    private void click(String label){final View[] match=new View[1];runOnMainSync(()->match[0]=findControl(activity.getWindow().getDecorView(),label));View target=match[0];require(target!=null,"Missing control: "+label);runOnMainSync(()->target.performClick());waitForIdleSync();}
    private void awaitText(String text){long end=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<end){waitForIdleSync();if(find(text)!=null)return;SystemClock.sleep(25);}throw new AssertionError("Missing text: "+text);}
}
