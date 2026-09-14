package com.cabin.hondacustom;

import android.content.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static com.cabin.hondacustom.DiagnosticProtocolTest.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class DiagnosticClientTest {
    private DiagnosticClient client;private Service service;
    private Context context(){return new ContextWrapper(RuntimeEnvironment.getApplication()){
        @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){assertEquals(DiagnosticProtocol.SERVICE,intent.getComponent().getClassName());connection.onServiceConnected(intent.getComponent(),service);return true;}
        @Override public void unbindService(ServiceConnection connection){}
    };}
    @Before public void setup(){service=new Service();client=new DiagnosticClient(context(),()->{});}
    @After public void close(){if(service.release!=null)service.release.countDown();client.destroy();}
    private void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end){ShadowLooper.idleMainLooper();Thread.sleep(5);}ShadowLooper.idleMainLooper();assertTrue(client.phase+": "+client.status,condition.getAsBoolean());}
    private void ready()throws Exception{client.connect();await(()->client.phase==DiagnosticClient.Phase.READY);}
    private void loaded()throws Exception{ready();client.readHardware();await(()->service.requests==1);service.respond(service.lastId,hardware(0x515));await(()->client.hardwareLoaded);}
    @Test public void connectDoesNotReadOrDeleteAutomatically()throws Exception{ready();assertEquals(0,service.requests);assertFalse(client.canClearHardware());}
    @Test public void clearRequiresFreshHistoryParkedAndSpecificConfirmation()throws Exception{
        ready();client.clearHardware(true,true);assertEquals(0,service.requests);client.readHardware();await(()->service.requests==1);service.respond(service.lastId,hardware(0x515));await(()->client.hardwareLoaded);
        client.clearHardware(false,true);client.clearHardware(true,false);assertEquals(1,service.requests);assertEquals(DiagnosticClient.Phase.READY,client.phase);
    }
    @Test public void deletionNeedsCompleteAckAndEmptyReadback()throws Exception{
        loaded();client.clearHardware(true,true);await(()->service.requests==2);assertEquals(0x0a0901,service.lastKind);int clear=service.lastId;
        service.message(clear,progress(1,100,false));ShadowLooper.idleMainLooper();assertEquals(DiagnosticClient.Phase.CLEARING,client.phase);
        service.respond(clear+20,progress(1,100,false));ShadowLooper.idleMainLooper();assertEquals(DiagnosticClient.Phase.CLEARING,client.phase);
        service.respond(clear,progress(0,40,true));ShadowLooper.idleMainLooper();assertEquals(2,service.requests);assertTrue(client.status.contains("40%"));
        service.respond(clear,progress(1,100,false));await(()->service.requests==3);assertEquals(DiagnosticClient.Phase.VERIFYING,client.phase);assertEquals(0x0a0201,service.lastKind);
        service.respond(clear,progress(1,100,false));ShadowLooper.idleMainLooper();assertEquals(DiagnosticClient.Phase.VERIFYING,client.phase);
        service.respond(service.lastId,hardware());await(()->client.phase==DiagnosticClient.Phase.READY);assertTrue(client.status.contains("verified empty"));assertFalse(client.canClearHardware());
    }
    @Test public void nonemptyReadbackDoesNotClaimSuccessfulClear()throws Exception{
        loaded();client.clearHardware(true,true);await(()->service.requests==2);service.respond(service.lastId,progress(1,100,false));await(()->service.requests==3);
        service.respond(service.lastId,hardware(0x515));await(()->client.phase==DiagnosticClient.Phase.DISCONNECTED);assertTrue(client.status.contains("still contains"));
    }
    @Test public void negativeStatusAndMalformedRepliesFailClosed()throws Exception{
        loaded();client.clearHardware(true,true);await(()->service.requests==2);service.respond(service.lastId,progress(-1,100,false));await(()->client.phase==DiagnosticClient.Phase.DISCONNECTED);assertEquals(2,service.requests);
        ready();client.readHardware();await(()->service.requests==3);service.respond(service.lastId,new Bundle());await(()->client.phase==DiagnosticClient.Phase.DISCONNECTED);assertFalse(client.hardwareLoaded);
    }
    @Test public void earlyCallbackBeforeRequestReturnIsRetainedAndCorrelated()throws Exception{
        ready();service.early=hardware(0x515);service.release=new CountDownLatch(1);client.readHardware();await(()->service.entered);
        assertEquals(DiagnosticClient.Phase.READING,client.phase);service.release.countDown();await(()->client.hardwareLoaded);assertEquals(0x515,client.history.get(0).code);
    }
    @Test public void timeoutCancelsAndTerminatesThenIgnoresOldSession()throws Exception{
        loaded();IBinder old=service.listener;client.clearHardware(true,true);await(()->service.requests==2);int id=service.lastId;
        ShadowLooper.idleMainLooper(31,TimeUnit.SECONDS);assertEquals(DiagnosticClient.Phase.DISCONNECTED,client.phase);await(()->service.terminated==1);assertEquals(id,service.cancelled);
        ready();client.readHardware();await(()->service.requests==3);Service.send(old,1,service.lastId,hardware());ShadowLooper.idleMainLooper();assertFalse(client.hardwareLoaded);
        service.respond(service.lastId,hardware());await(()->client.hardwareLoaded);
    }
    @Test public void rejectedRequestAndPermissionDenialAreExplicit()throws Exception{
        ready();service.rejectRequest=true;client.readHardware();await(()->client.phase==DiagnosticClient.Phase.DISCONNECTED);assertTrue(client.status.contains("rejected"));client.destroy();
        client=new DiagnosticClient(new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){throw new SecurityException("denied");}
        },()->{});client.connect();assertEquals(DiagnosticClient.Phase.DISCONNECTED,client.phase);assertTrue(client.status.contains("OEM signature"));
    }
    @Test public void telematicsReadKeepsOriginalColumnsAndDoesNotEnableHardwareClear()throws Exception{
        ready();client.readTelematics();await(()->service.requests==1);assertEquals(0x2a0201,service.lastKind);
        Bundle row=new Bundle();row.putStringArray("columns",new String[]{"B1234","Original text"});Bundle data=new Bundle();data.putInt("error",0);data.putParcelableArray("rows",new Parcelable[]{row});
        service.respond(service.lastId,data);await(()->client.telematicsLoaded);assertEquals("B1234",client.telematics.get(0).get(0));assertFalse(client.canClearHardware());assertTrue(client.report().contains("not transmission"));await(()->service.cancelled==service.lastId);
    }
    @Test public void observerReconnectCannotDispatchOldClearInNewSession()throws Exception{
        client.destroy();final boolean[] switchOnce={true};client=new DiagnosticClient(context(),()->{if(client.phase==DiagnosticClient.Phase.CLEARING&&switchOnce[0]){switchOnce[0]=false;client.disconnect();client.connect();}});
        loaded();client.clearHardware(true,true);await(()->service.initialized==2&&client.phase==DiagnosticClient.Phase.READY);assertEquals(1,service.requests);assertFalse(client.hardwareLoaded);
    }
    @Test public void synchronousBindDisconnectDoesNotLeakRegistration(){
        client.destroy();final int[] unbound={0};client=new DiagnosticClient(new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){connection.onServiceDisconnected(intent.getComponent());return true;}
            @Override public void unbindService(ServiceConnection connection){unbound[0]++;}
        },()->{});client.connect();assertEquals(1,unbound[0]);assertEquals(DiagnosticClient.Phase.DISCONNECTED,client.phase);
    }
    private static android.view.View find(android.view.View view,Class<?> type,String label){
        if(type.isInstance(view)&&(label==null||view instanceof android.widget.TextView&&label.contentEquals(((android.widget.TextView)view).getText())))return view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.view.View found=find(group.getChildAt(i),type,label);if(found!=null)return found;}}
        return null;
    }
    @Test public void pausedDiagnosticActivityDismissesConfirmationAndInvalidatesParkedState()throws Exception{
        try(org.robolectric.android.controller.ActivityController<DiagnosticsActivity> controller=Robolectric.buildActivity(DiagnosticsActivity.class).setup()){
            DiagnosticsActivity activity=controller.get();java.lang.reflect.Field field=DiagnosticsActivity.class.getDeclaredField("client");field.setAccessible(true);((DiagnosticClient)field.get(activity)).destroy();client.destroy();
            java.lang.reflect.Method render=DiagnosticsActivity.class.getDeclaredMethod("render");render.setAccessible(true);
            client=new DiagnosticClient(context(),()->{try{render.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});field.set(activity,client);loaded();
            android.view.ViewGroup content=activity.findViewById(android.R.id.content);assertTrue(content.getChildAt(0) instanceof android.widget.ScrollView);
            android.widget.CheckBox parked=(android.widget.CheckBox)find(content,android.widget.CheckBox.class,null);parked.setChecked(true);
            android.widget.Button clear=(android.widget.Button)find(content,android.widget.Button.class,"Clear hardware history");assertTrue(clear.isEnabled());clear.performClick();
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing());controller.pause();
            assertFalse(dialog.isShowing());assertFalse(parked.isChecked());assertEquals(DiagnosticClient.Phase.DISCONNECTED,client.phase);
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();assertEquals(1,service.requests);
        }
    }
    @Test public void stalledDescriptorDoesNotInitializeAfterDisconnect()throws Exception{
        CountDownLatch descriptorEntered=new CountDownLatch(1),descriptorRelease=new CountDownLatch(1);
        service=new Service(){@Override public String getInterfaceDescriptor(){descriptorEntered.countDown();try{descriptorRelease.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return DiagnosticProtocol.DESCRIPTOR;}};
        client.connect();assertTrue(descriptorEntered.await(5,TimeUnit.SECONDS));client.disconnect();descriptorRelease.countDown();java.lang.reflect.Field io=DiagnosticClient.class.getDeclaredField("io");io.setAccessible(true);
        ((ExecutorService)io.get(client)).submit(()->{}).get(5,TimeUnit.SECONDS);assertEquals(0,service.initialized);assertEquals(0,service.terminated);
    }
    @Test public void destroyedClientDoesNotBindAgainAndDestroyIsIdempotent(){
        client.destroy();final int[] binds={0};client=new DiagnosticClient(new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){binds[0]++;return true;}
        },()->{});client.destroy();client.destroy();client.connect();assertEquals(0,binds[0]);assertEquals(DiagnosticClient.Phase.DISCONNECTED,client.phase);
    }
    @Test public void cancellationBeforeRequestReturnIsNotLost()throws Exception{
        ready();service.earlyCancel=true;service.release=new CountDownLatch(1);client.readHardware();await(()->service.entered);
        service.release.countDown();await(()->client.phase==DiagnosticClient.Phase.DISCONNECTED);assertTrue(client.status.contains("cancelled"));
    }
    @Test public void diagnosticsActivityLaunchesWithoutOemService(){try(org.robolectric.android.controller.ActivityController<DiagnosticsActivity> activity=Robolectric.buildActivity(DiagnosticsActivity.class).setup()){assertNotNull(activity.get());}}
}
