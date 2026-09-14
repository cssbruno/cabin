package com.cabin.hondacustom;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class ValidationTest {
    private ValidationClient client;
    private Identity fake;
    private Context context;
    private boolean denyPermission,delayBinding,synchronousDisconnect;
    private volatile int binds,unbinds;
    private ServiceConnection pending;
    static class Identity extends Binder {
        final List<Integer> readTypes=Collections.synchronizedList(new ArrayList<>());
        boolean wrongType,reject;
        Object model="CIVIC-FIRMWARE-ID";
        CountDownLatch descriptorEntered,descriptorRelease;
        Identity(){attachInterface(null,UnitInfoProtocol.DESCRIPTOR);}
        @Override public String getInterfaceDescriptor(){
            if(descriptorEntered!=null){descriptorEntered.countDown();try{descriptorRelease.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            return UnitInfoProtocol.DESCRIPTOR;
        }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            assertEquals("Compatibility checks must only use getUnitInformation",0x21,code);assertEquals(0,flags);
            data.enforceInterface("com.mitsubishielectric.ada.appservice.unitinfomanager.IUnitInformationManagerApService");
            assertEquals(1,data.readInt());int type=data.readInt();assertTrue(type==4||type==7);
            assertNull(data.readValue(null));assertNull(data.readArrayList(null));assertNull(data.createIntArray());assertEquals(0,data.dataAvail());
            readTypes.add(type);reply.writeNoException();reply.writeInt(reject?-3:0);reply.writeInt(1);reply.writeInt(wrongType?99:type);
            reply.writeValue(type==7?model:6);reply.writeList(null);reply.writeIntArray(null);return true;
        }
    }
    @Before public void setup(){
        fake=new Identity();
        context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public int checkPermission(String name,int pid,int uid){return denyPermission?PackageManager.PERMISSION_DENIED:PackageManager.PERMISSION_GRANTED;}
            @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){
                assertEquals(UnitInfoProtocol.SERVICE,intent.getComponent().getClassName());binds++;pending=connection;
                if(synchronousDisconnect)connection.onServiceDisconnected(intent.getComponent());
                else if(!delayBinding)connection.onServiceConnected(intent.getComponent(),fake);
                return true;
            }
            @Override public void unbindService(ServiceConnection connection){unbinds++;}
        };
        client=new ValidationClient(context,()->{});
    }
    @After public void close(){if(fake.descriptorRelease!=null)fake.descriptorRelease.countDown();client.destroy();}
    private ServiceInfo install(String pkg,String cls,boolean exported,String permission){
        PackageInfo info=new PackageInfo();info.packageName=pkg;info.versionName="1.F197.60";info.versionCode=60;
        ApplicationInfo app=new ApplicationInfo();app.packageName=pkg;app.enabled=true;app.uid=android.os.Process.myUid()+42;info.applicationInfo=app;
        ServiceInfo service=new ServiceInfo();service.name=cls;service.packageName=pkg;service.applicationInfo=app;service.enabled=true;service.exported=exported;service.permission=permission;info.services=new ServiceInfo[]{service};
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);return service;
    }
    private void installIdentity(boolean exported,String permission){install(UnitInfoProtocol.PACKAGE,UnitInfoProtocol.SERVICE,exported,permission);}
    private void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<end){ShadowLooper.idleMainLooper();Thread.sleep(5);}
        ShadowLooper.idleMainLooper();assertTrue(client.phase+": "+client.status,condition.getAsBoolean());
    }
    @Test public void missingPackagesProduceReportWithoutBinding()throws Exception{
        client.run();await(()->client.phase==ValidationClient.Phase.COMPLETE);
        assertEquals(6,client.report.services.size());assertEquals(0,binds);assertTrue(fake.readTypes.isEmpty());
        assertEquals("PREFLIGHT_BLOCKED",client.report.identityStatus);
        JSONObject json=new JSONObject(client.report.toJson());assertFalse(json.getBoolean("independently_verified_hardware"));assertEquals(0,json.getInt("automated_vehicle_writes"));
        for(int i=0;i<json.getJSONArray("hardware_observations").length();i++)assertEquals("NOT_TESTED",json.getJSONArray("hardware_observations").getJSONObject(i).getString("result"));
    }
    @Test public void nonExportedIdentityCannotBind()throws Exception{
        installIdentity(false,null);client.run();await(()->client.phase==ValidationClient.Phase.COMPLETE);
        assertFalse(client.report.identityService().canBind);assertEquals(0,binds);
    }
    @Test public void permissionDeniedIdentityCannotBind()throws Exception{
        installIdentity(true,"honda.permission.IDENTITY");denyPermission=true;client.run();await(()->client.phase==ValidationClient.Phase.COMPLETE);
        assertFalse(client.report.identityService().permissionGranted);assertEquals(0,binds);
    }
    @Test public void effectiveDisabledServiceCannotBind()throws Exception{
        installIdentity(true,null);context.getPackageManager().setComponentEnabledSetting(new ComponentName(UnitInfoProtocol.PACKAGE,UnitInfoProtocol.SERVICE),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,0);
        client.run();await(()->client.phase==ValidationClient.Phase.COMPLETE);assertFalse(client.report.identityService().enabled);assertEquals(0,binds);
    }
    @Test public void liveReportOnlyReadsExactModelAndDestinationWire()throws Exception{
        installIdentity(true,null);install(HondaProtocol.PACKAGE,HondaProtocol.SERVICE,true,null);
        install(CameraProtocol.PACKAGE,CameraProtocol.SERVICE,true,null);install(MeterProtocol.PACKAGE,MeterProtocol.SERVICE,true,null);
        client.run();await(()->client.phase==ValidationClient.Phase.COMPLETE);
        assertEquals(Arrays.asList(7,4),fake.readTypes);assertEquals(1,binds);assertEquals(1,unbinds);
        assertEquals("READ_SUCCEEDED",client.report.identityStatus);assertEquals("CIVIC-FIRMWARE-ID",client.report.model);assertEquals(Integer.valueOf(6),client.report.destinationCode);
        assertEquals("1.F197.60",client.report.identityService().versionName);assertEquals(60,client.report.identityService().versionCode);
        JSONObject json=new JSONObject(client.report.toJson());assertEquals(28,json.getJSONObject("android").getInt("api"));assertFalse(json.getBoolean("independently_verified_hardware"));
    }
    @Test public void badModelDoesNotInventIdentityOrDiscardReadableDestination()throws Exception{
        installIdentity(true,null);fake.model=Boolean.TRUE;client.run();await(()->client.phase==ValidationClient.Phase.COMPLETE);
        assertEquals("PARTIAL_READ",client.report.identityStatus);assertNull(client.report.model);assertNotNull(client.report.modelError);assertEquals(Integer.valueOf(6),client.report.destinationCode);
    }
    @Test public void malformedIdentityReplyIsRejected()throws Exception{
        fake.wrongType=true;
        try{new UnitInfoProtocol(fake).getDestinationCode();fail("Wrong SettingData type must fail");}catch(RemoteException expected){assertTrue(expected.getMessage().contains("Invalid Honda identity"));}
    }
    @Test public void rejectedIdentityReadIsNotSuccess()throws Exception{
        installIdentity(true,null);fake.reject=true;client.run();await(()->client.phase==ValidationClient.Phase.COMPLETE);
        assertEquals("READ_FAILED",client.report.identityStatus);assertNull(client.report.model);assertNull(client.report.destinationCode);assertTrue(client.report.modelError.contains("rejected"));
    }
    @Test public void timeoutReleasesBindingAndIgnoresLateService()throws Exception{
        installIdentity(true,null);delayBinding=true;client.run();await(()->binds==1);
        ShadowLooper.idleMainLooper(21,TimeUnit.SECONDS);assertEquals("TIMED_OUT",client.report.identityStatus);assertEquals(1,unbinds);
        pending.onServiceConnected(new ComponentName(UnitInfoProtocol.PACKAGE,UnitInfoProtocol.SERVICE),fake);ShadowLooper.idleMainLooper();assertTrue(fake.readTypes.isEmpty());
    }
    @Test public void synchronousDisconnectReleasesReturnedBindingOnce()throws Exception{
        installIdentity(true,null);synchronousDisconnect=true;client.run();await(()->client.phase==ValidationClient.Phase.COMPLETE);
        assertEquals("SERVICE_DISCONNECTED",client.report.identityStatus);assertEquals(1,unbinds);client.cancel();assertEquals(1,unbinds);
    }
    @Test public void cancellationDuringDescriptorLookupStopsIdentityReads()throws Exception{
        installIdentity(true,null);fake.descriptorEntered=new CountDownLatch(1);fake.descriptorRelease=new CountDownLatch(1);
        client.run();await(()->binds==1);assertTrue(fake.descriptorEntered.await(3,TimeUnit.SECONDS));client.cancel();fake.descriptorRelease.countDown();
        // A new queued scan finishing proves the cancelled worker has returned.
        delayBinding=true;client.run();await(()->binds==2);assertTrue(fake.readTypes.isEmpty());client.cancel();
    }
    @Test public void manualResultsRequireNotesAndRemainAttributedToUser()throws Exception{
        CompatibilityReport report=new CompatibilityReport();
        try{report.record(CompatibilityReport.Check.CAMERA,CompatibilityReport.Result.USER_REPORTED_PASS,"");fail();}catch(IllegalArgumentException expected){}
        report.record(CompatibilityReport.Check.CAMERA,CompatibilityReport.Result.USER_REPORTED_PASS,"Guidelines changed visibly; restored the original option.");
        JSONObject json=new JSONObject(report.toJson());assertFalse(json.getBoolean("independently_verified_hardware"));
        JSONObject camera=json.getJSONArray("hardware_observations").getJSONObject(3);assertEquals("USER_REPORTED_PASS",camera.getString("result"));assertTrue(camera.getString("source").contains("not automatically verified"));
        report.record(CompatibilityReport.Check.CAMERA,CompatibilityReport.Result.NOT_TESTED,"");assertEquals(CompatibilityReport.Result.NOT_TESTED,report.observations.get(CompatibilityReport.Check.CAMERA).result);
    }
    @Test public void validationScreenDoesNotAutomaticallyLaunchOrBind(){
        try(org.robolectric.android.controller.ActivityController<ValidationActivity> activity=Robolectric.buildActivity(ValidationActivity.class).setup()){
            assertNull(Shadows.shadowOf(activity.get()).getNextStartedActivity());assertEquals(0,binds);
        }
    }
    @Test public void observerCancellationCannotContinueIntoBinding()throws Exception{
        installIdentity(true,null);client.destroy();
        client=new ValidationClient(context,()->{if(client.report!=null&&"READING".equals(client.report.identityStatus))client.cancel();});
        client.run();await(()->client.phase==ValidationClient.Phase.COMPLETE);assertEquals(0,binds);assertTrue(fake.readTypes.isEmpty());
    }
    @Test public void observerDestroyBeforeScanDoesNotQueueRejectedWork(){
        client.destroy();client=new ValidationClient(context,()->{if(client.phase==ValidationClient.Phase.RUNNING)client.destroy();});
        client.run();assertEquals(0,binds);assertEquals(ValidationClient.Phase.COMPLETE,client.phase);
    }
    @Test public void avAndSignatureProtectedDiagnosticsAreInspectedWithoutBindingThem()throws Exception{
        installIdentity(true,null);install(DisplayProtocol.PACKAGE,DisplayProtocol.SERVICE,true,null);
        install(DiagnosticProtocol.PACKAGE,DiagnosticProtocol.SERVICE,true,DiagnosticProtocol.PERMISSION);
        PackageInfo diag=context.getPackageManager().getPackageInfo(DiagnosticProtocol.PACKAGE,PackageManager.GET_SERVICES);
        PermissionInfo permission=new PermissionInfo();permission.name=DiagnosticProtocol.PERMISSION;permission.packageName=DiagnosticProtocol.PACKAGE;permission.protectionLevel=PermissionInfo.PROTECTION_SIGNATURE;
        diag.permissions=new PermissionInfo[]{permission};Shadows.shadowOf(context.getPackageManager()).installPackage(diag);denyPermission=true;
        client.run();await(()->client.phase==ValidationClient.Phase.COMPLETE);assertEquals(1,binds);
        CompatibilityReport.ServiceCheck found=null;for(CompatibilityReport.ServiceCheck service:client.report.services)if(service.className.equals(DiagnosticProtocol.SERVICE))found=service;
        assertNotNull(found);assertEquals(DiagnosticProtocol.PERMISSION,found.permission);assertEquals(Integer.valueOf(PermissionInfo.PROTECTION_SIGNATURE),found.permissionProtectionLevel);assertFalse(found.canBind);
        assertEquals(Arrays.asList(7,4),fake.readTypes);
    }
}
