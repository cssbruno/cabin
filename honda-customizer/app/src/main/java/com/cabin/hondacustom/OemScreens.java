package com.cabin.hondacustom;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;

/** Public OEM entry points verified against the original APK manifests. */
public final class OemScreens {
    private OemScreens(){}
    public enum Screen {
        SYSTEM("com.mitsubishielectric.ada.app.dasettings","SystemSettingActivity"),
        LANGUAGE("com.mitsubishielectric.ada.app.dasettings","ChangeLanguageActivity"),
        DIAGNOSTICS("com.mitsubishielectric.ada.app.dealerdiag","DealerDiagActivity");
        final ComponentName component;
        Screen(String pkg,String name){component=new ComponentName(pkg,pkg+"."+name);}
    }
    public static String unavailable(Context context,Screen screen){
        try{
            ActivityInfo info=context.getPackageManager().getActivityInfo(screen.component,0);
            if(!info.enabled||!info.applicationInfo.enabled||!info.exported)return "This Honda screen is not available to other apps.";
            if(info.permission!=null&&context.checkCallingOrSelfPermission(info.permission)!=PackageManager.PERMISSION_GRANTED)return "Honda does not grant this app access to that screen.";
            return null;
        }catch(PackageManager.NameNotFoundException|SecurityException e){return "This Honda screen is not installed or accessible on this unit.";}
    }
    public static String open(Activity activity,Screen screen,boolean parked){
        if(!parked)return "Confirm that the car is parked before opening Honda settings.";
        String reason=unavailable(activity,screen);if(reason!=null)return reason;
        try{activity.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(screen.component));return null;}
        catch(ActivityNotFoundException|SecurityException e){return "Honda could not open this screen: "+e.getClass().getSimpleName();}
    }
}
