package com.cabin.hondacustom;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Installed-APK checks on disposable emulators only. */
public final class SmokeInstrumentation extends Instrumentation {
    private Activity activity;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();List<String> passed=new ArrayList<>();try{
        if(!Build.FINGERPRINT.contains("generic")&&!Build.MODEL.contains("sdk"))throw new IllegalStateException("Emulator required");
        activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        require(find("Honda Customizer · FYT")!=null,"FYT title missing");passed.add("fyt_main_installs_and_launches");
        View connect=find("Connect to FYT");require(connect instanceof Button,"FYT connect missing");runOnMainSync(connect::performClick);waitForIdleSync();
        long end=SystemClock.uptimeMillis()+10000;while(find("FYT service unavailable")==null&&SystemClock.uptimeMillis()<end){SystemClock.sleep(50);waitForIdleSync();}
        require(find("FYT service unavailable")!=null,"Missing FYT service not reported");passed.add("missing_fyt_service_fails_closed");
        require(find("Honda diagnostics")==null&&find("Connect to Honda")==null,"OEM controls present");
        android.content.pm.PackageInfo info=getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),android.content.pm.PackageManager.GET_ACTIVITIES|android.content.pm.PackageManager.GET_PERMISSIONS);
        require(info.activities.length==1,"OEM activities packaged");
        if(info.requestedPermissions!=null)for(String permission:info.requestedPermissions)require(!permission.contains("mitsubishielectric"),"OEM permission packaged");
        passed.add("oem_services_and_screens_absent");
        result.putInt("passed",passed.size());result.putInt("failures",0);result.putString("checks",new org.json.JSONArray(passed).toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putInt("passed",passed.size());result.putInt("failures",1);result.putString("stream",e.toString());finish(Activity.RESULT_CANCELED,result);}}
    private void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
    private View find(String text){final View[] result=new View[1];runOnMainSync(()->result[0]=find(activity.getWindow().getDecorView(),text));return result[0];}
    private View find(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View match=find(((ViewGroup)view).getChildAt(i),text);if(match!=null)return match;}return null;}
}
