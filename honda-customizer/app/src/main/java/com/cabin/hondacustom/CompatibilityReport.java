package com.cabin.hondacustom;

import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.os.Process;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Package preflight and explicitly attributed observations; never proves vehicle behavior. */
public final class CompatibilityReport {
    public enum Result { NOT_TESTED, USER_REPORTED_PASS, USER_REPORTED_FAIL, USER_REPORTED_NOT_APPLICABLE }
    public enum Check {
        LIVE_SETTINGS("Live settings", "On the parked car, connect and refresh. Compare current values with the original Honda screens."),
        CLUSTER("Cluster units / language", "Record the original value. Change one supported choice, inspect the cluster, then restore the original value."),
        METER("Panel contents", "Record the original panel layout. Change one supported item, inspect the instrument panel, then restore the layout."),
        CAMERA("Camera guidelines", "While parked, change one supported guideline option. Inspect the corresponding camera image, then restore the original option."),
        HEAD_UNIT("Head-unit preferences", "Record a supported preference. Change it, inspect the original screen or behavior, then restore it."),
        PERSISTENCE("Persistence", "After an intentional supported change, restart the head unit and compare the saved value and visible behavior. Restore the original value afterwards.");
        public final String title, instructions;
        Check(String title,String instructions){this.title=title;this.instructions=instructions;}
    }
    public static final class Observation {
        public final Result result; public final String notes, recordedAt;
        Observation(Result result,String notes){this.result=result;this.notes=notes;this.recordedAt=result==Result.NOT_TESTED?null:now();}
    }
    public static final class ServiceCheck {
        public final String label, packageName, className;
        public String versionName, problem, permission;
        public long versionCode;
        public boolean packageVisible, componentVisible, enabled, exported, permissionGranted, sameUid, canBind;
        public Integer permissionProtectionLevel;
        ServiceCheck(String label,String pkg,String cls){this.label=label;packageName=pkg;className=cls;}
        public ComponentName component(){return new ComponentName(packageName,className);}
        JSONObject json()throws JSONException{
            JSONObject value=new JSONObject();value.put("label",label);value.put("package",packageName);value.put("component",className);
            value.put("package_visible",packageVisible);value.put("component_visible",componentVisible);
            value.put("version_name",nullable(versionName));value.put("version_code",packageVisible?versionCode:JSONObject.NULL);
            value.put("effective_enabled",enabled);value.put("exported",exported);value.put("same_uid",sameUid);
            value.put("required_permission",nullable(permission));value.put("permission_granted",permissionGranted);
            value.put("permission_protection_level",nullable(permissionProtectionLevel));value.put("preflight_allows_binding",canBind);
            value.put("actual_service_connection",className.equals(UnitInfoProtocol.SERVICE)?"See identity_read":"NOT_TESTED");
            value.put("detail",nullable(problem));return value;
        }
    }
    public final String generatedAt=now();
    public final List<ServiceCheck> services=new ArrayList<>();
    public final Map<Check,Observation> observations=new LinkedHashMap<>();
    public String appVersion="Unknown", identityStatus="NOT_TESTED", model, modelError, destinationError;
    public Integer destinationCode;
    public CompatibilityReport(){for(Check check:Check.values())observations.put(check,new Observation(Result.NOT_TESTED,""));}
    static String now(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
    private static Object nullable(Object value){return value==null?JSONObject.NULL:value;}
    private static boolean effectiveEnabled(int setting,boolean manifest){
        if(setting==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)return manifest;
        return setting==PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }
    public static ServiceCheck inspect(Context context,String label,String pkg,String cls){
        ServiceCheck result=new ServiceCheck(label,pkg,cls);PackageManager pm=context.getPackageManager();
        try{
            PackageInfo installed=pm.getPackageInfo(pkg,PackageManager.GET_DISABLED_COMPONENTS);
            result.packageVisible=true;result.versionName=installed.versionName;
            result.versionCode=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;
            ServiceInfo service=pm.getServiceInfo(result.component(),PackageManager.GET_DISABLED_COMPONENTS);
            result.componentVisible=true;result.exported=service.exported;
            ApplicationInfo app=service.applicationInfo;
            result.enabled=app!=null&&effectiveEnabled(pm.getApplicationEnabledSetting(pkg),app.enabled)
                    &&effectiveEnabled(pm.getComponentEnabledSetting(result.component()),service.enabled);
            result.sameUid=app!=null&&app.uid==Process.myUid();result.permission=service.permission;
            result.permissionGranted=service.permission==null||service.permission.isEmpty()
                    ||context.checkPermission(service.permission,Process.myPid(),Process.myUid())==PackageManager.PERMISSION_GRANTED;
            if(service.permission!=null&&!service.permission.isEmpty()){
                try{result.permissionProtectionLevel=pm.getPermissionInfo(service.permission,0).protectionLevel;}
                catch(PackageManager.NameNotFoundException ignored){}
            }
            result.canBind=result.enabled&&(result.exported||result.sameUid)&&result.permissionGranted;
            result.problem=!result.enabled?"Package or service is disabled":!result.exported&&!result.sameUid?"Service is not exported to this app":
                    !result.permissionGranted?"Required permission is not granted":"Package preflight allows binding; runtime policy may still reject it";
        }catch(PackageManager.NameNotFoundException e){result.problem=result.packageVisible?"Service component is missing or not visible":"Package is not installed or not visible";}
        catch(SecurityException|IllegalArgumentException e){result.canBind=false;result.problem="Package inspection blocked: "+e.getClass().getSimpleName();}
        return result;
    }
    public static CompatibilityReport collect(Context context){
        CompatibilityReport report=new CompatibilityReport();
        try{PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),0);report.appVersion=p.versionName+" ("+p.versionCode+")";}
        catch(PackageManager.NameNotFoundException ignored){}
        report.services.add(inspect(context,"Vehicle customization",HondaProtocol.PACKAGE,HondaProtocol.SERVICE));
        report.services.add(inspect(context,"Head-unit identity",UnitInfoProtocol.PACKAGE,UnitInfoProtocol.SERVICE));
        report.services.add(inspect(context,"Camera settings",CameraProtocol.PACKAGE,CameraProtocol.SERVICE));
        report.services.add(inspect(context,"Instrument-panel contents",MeterProtocol.PACKAGE,MeterProtocol.SERVICE));
        report.services.add(inspect(context,"Display adjustment",DisplayProtocol.PACKAGE,DisplayProtocol.SERVICE));
        report.services.add(inspect(context,"Diagnostics",DiagnosticProtocol.PACKAGE,DiagnosticProtocol.SERVICE));
        return report;
    }
    public ServiceCheck identityService(){for(ServiceCheck service:services)if(service.className.equals(UnitInfoProtocol.SERVICE))return service;return null;}
    public void record(Check check,Result result,String notes){
        if(check==null||result==null)throw new IllegalArgumentException("Choose a hardware check and result");
        String text=notes==null?"":notes.trim();
        if(result!=Result.NOT_TESTED&&text.isEmpty())throw new IllegalArgumentException("Add what you observed or why the check does not apply");
        observations.put(check,new Observation(result,text));
    }
    public String toJson(){
        try{
            JSONObject out=new JSONObject();out.put("schema_version",1);out.put("generated_at_utc",generatedAt);out.put("app_version",appVersion);
            JSONObject android=new JSONObject();android.put("api",Build.VERSION.SDK_INT);android.put("release",Build.VERSION.RELEASE);
            android.put("manufacturer",Build.MANUFACTURER);android.put("model",Build.MODEL);android.put("product",Build.PRODUCT);
            android.put("build_id",Build.ID);android.put("fingerprint",Build.FINGERPRINT);out.put("android",android);
            JSONArray packages=new JSONArray();for(ServiceCheck service:services)packages.put(service.json());out.put("oem_services",packages);
            JSONObject identity=new JSONObject();identity.put("status",identityStatus);identity.put("raw_model_identifier",nullable(model));
            identity.put("raw_destination_code",nullable(destinationCode));identity.put("model_error",nullable(modelError));identity.put("destination_error",nullable(destinationError));
            identity.put("source","Original UnitInfo getUnitInformation: model type 7; destination type 4");
            identity.put("interpretation","Firmware identifiers do not independently establish the installed vehicle model, year, trim or market");out.put("identity_read",identity);
            out.put("automated_vehicle_writes",0);out.put("independently_verified_hardware",false);
            out.put("hardware_validation","NOT_INDEPENDENTLY_VERIFIED; observations below are entered by the user");
            JSONArray checks=new JSONArray();for(Check check:Check.values()){
                Observation o=observations.get(check);JSONObject row=new JSONObject();row.put("check",check.name());row.put("title",check.title);
                row.put("instructions",check.instructions);row.put("result",o.result.name());row.put("notes",o.notes);row.put("recorded_at_utc",nullable(o.recordedAt));
                row.put("source",o.result==Result.NOT_TESTED?"Not tested":"User-reported; not automatically verified");checks.put(row);
            }out.put("hardware_observations",checks);return out.toString(2);
        }catch(JSONException e){throw new IllegalStateException("Could not format compatibility report",e);}
    }
}
