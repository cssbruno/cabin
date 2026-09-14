package com.cabin.hondacustom;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class HeadUnitSettingsTest {
    HeadUnitSettingsClient client;Fake fake;Context context;boolean failBinding,delayBinding;ServiceConnection delayed;int unbinds;
    static final HeadUnitSettings.Entry FORMAT=HeadUnitSettings.find(0x4a),TOUCH=HeadUnitSettings.find(0x20),VOICE=HeadUnitSettings.find(0x40);
    static class Fake extends Binder {
        final Map<Integer,Object> values=new HashMap<>();
        volatile int reads,writes,destination=35,lastType,lastValue,blockType=-1;
        volatile boolean reject,mismatch,wrongType,missing,extraFields;
        volatile int unavailableType=-1;
        CountDownLatch entered,release;boolean blockWrite;
        Fake(){attachInterface(null,"com.mitsubishielectric.ada.appservice.unitinfomanager.IUnitInformationManagerApService");
            // Independent fixture; deliberately includes nonconsecutive touch enum and imported background.
            values.put(0x4a,0);values.put(0x15,1);values.put(2,2);values.put(3,4);values.put(0x4b,2);values.put(0x20,0);
            values.put(0x17,1);values.put(0x40,1);values.put(0x4e,0);values.put(0x6e,0);values.put(0x6f,1);
        }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            assertEquals(0,flags);data.enforceInterface("com.mitsubishielectric.ada.appservice.unitinfomanager.IUnitInformationManagerApService");
            assertEquals(1,data.readInt());int type=data.readInt();Object requested=data.readValue(Integer.class.getClassLoader());
            assertNull(data.readArrayList(null));assertNull(data.createIntArray());assertEquals(0,data.dataAvail());
            if(code==0x1d){assertTrue(requested instanceof Integer);writes++;lastType=type;lastValue=(Integer)requested;
                if(blockWrite)block();if(!reject&&!mismatch)values.put(type,requested);
                reply.writeNoException();reply.writeInt(reject?-3:0);return true;
            }
            assertTrue(code==0x1e||code==0x21);assertNull(requested);if(code==0x21)assertEquals(4,type);
            reads++;if(type==blockType&&code==0x1e)block();reply.writeNoException();
            reply.writeInt(type==unavailableType?-1:0);if(type==unavailableType)return true;
            reply.writeInt(missing?0:1);if(missing)return true;
            reply.writeInt(wrongType?999:type);reply.writeValue(code==0x21?destination:values.get(type));
            reply.writeList(extraFields?Arrays.asList(1):null);reply.writeIntArray(null);return true;
        }
        void block(){if(entered!=null){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
    }
    @Before public void setup(){fake=new Fake();context=new ContextWrapper(RuntimeEnvironment.getApplication()){
        @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){
            assertEquals("com.mitsubishielectric.ada.appservice.unitinfomanager.UnitInformationManagerApService",intent.getComponent().getClassName());
            if(failBinding)return false;if(delayBinding){delayed=connection;return true;}connection.onServiceConnected(intent.getComponent(),fake);return true;
        }
        @Override public void unbindService(ServiceConnection connection){unbinds++;}
    };client=new HeadUnitSettingsClient(context,()->{});}
    @After public void close(){if(fake.release!=null)fake.release.countDown();client.destroy();}
    void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end){ShadowLooper.idleMainLooper();Thread.sleep(5);}ShadowLooper.idleMainLooper();assertTrue(client.phase+": "+client.status,condition.getAsBoolean());}
    void drainWorker()throws Exception{Field field=HeadUnitSettingsClient.class.getDeclaredField("worker");field.setAccessible(true);((ExecutorService)field.get(client)).submit(()->{}).get(3,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    void ready()throws Exception{client.connect();await(()->client.phase==HeadUnitSettingsClient.Phase.READY);}
    @Test public void discoveryReadsAllProvenPreferencesWithoutWriting()throws Exception{ready();assertEquals(11,client.values.size());assertEquals(12,fake.reads);assertEquals(0,fake.writes);assertEquals(Integer.valueOf(35),client.destination);assertTrue(client.editable(TOUCH));}
    @Test public void exactTouchEnumWireAndReadback()throws Exception{ready();client.change(TOUCH,0,2,true);await(()->client.phase==HeadUnitSettingsClient.Phase.READY&&fake.writes==1);assertEquals(0x20,fake.lastType);assertEquals(2,fake.lastValue);assertEquals(Integer.valueOf(2),client.values.get(0x20));assertTrue(client.status.contains("service readback"));}
    @Test public void kjClockInversionAndRegionalClockChoices()throws Exception{fake.destination=1;ready();assertEquals(Integer.valueOf(1),client.values.get(0x4a));assertFalse(HeadUnitSettings.find(2).options(1).containsKey(3));client.change(FORMAT,1,0,true);await(()->client.phase==HeadUnitSettingsClient.Phase.READY&&fake.writes==1);assertEquals(1,fake.lastValue);assertEquals(Integer.valueOf(0),client.values.get(0x4a));}
    @Test public void wallpaperAndUnsupportedEnumsAreNeverWritable(){HeadUnitSettings.Entry background=HeadUnitSettings.find(3);assertTrue(background.known(4));assertFalse(background.options(35).containsKey(4));assertFalse(background.options(35).containsKey(3));assertTrue(background.options(0).containsKey(3));assertFalse(TOUCH.options(35).containsKey(1));assertNull(HeadUnitSettings.find(0x10));assertFalse(HeadUnitSettings.trusted(null));}
    @Test public void parkUnknownValueAndOldConfirmationBlockMutation()throws Exception{ready();client.change(TOUCH,0,2,false);client.change(TOUCH,0,1,true);client.change(TOUCH,2,0,true);assertEquals(0,fake.writes);assertEquals(HeadUnitSettingsClient.Phase.READY,client.phase);}
    @Test public void freshValueConflictStopsWrite()throws Exception{ready();fake.values.put(0x20,2);client.change(TOUCH,0,2,true);await(()->client.phase==HeadUnitSettingsClient.Phase.DISCONNECTED);assertEquals(0,fake.writes);assertTrue(client.status.contains("changed since confirmation"));}
    @Test public void freshDestinationConflictStopsClockWrite()throws Exception{ready();fake.destination=1;client.change(FORMAT,0,1,true);await(()->client.phase==HeadUnitSettingsClient.Phase.DISCONNECTED);assertEquals(0,fake.writes);assertTrue(client.status.contains("destination changed"));}
    @Test public void unknownDestinationDisablesOnlyRegionalEditors()throws Exception{fake.destination=99;ready();assertFalse(client.editable(FORMAT));assertTrue(client.editable(VOICE));assertEquals(0,fake.writes);}
    @Test public void unavailableAndUnknownCurrentPreferencesRemainReadOnly()throws Exception{fake.unavailableType=0x17;fake.values.put(0x20,1);ready();assertTrue(client.unavailable.containsKey(0x17));assertFalse(client.editable(TOUCH));assertTrue(client.editable(VOICE));assertEquals(0,fake.writes);}
    @Test public void malformedResponsesNeverEnableWrites()throws Exception{fake.wrongType=true;ready();assertTrue(client.values.isEmpty());assertFalse(client.editable(TOUCH));assertEquals(0,fake.writes);}
    @Test public void nonIntegerAndExtraPayloadFieldsAreRejected()throws Exception{HeadUnitSettingsProtocol protocol=new HeadUnitSettingsProtocol(fake);fake.values.put(0x20,"0");try{protocol.read(TOUCH);fail();}catch(HeadUnitSettingsProtocol.Unavailable expected){}fake.values.put(0x20,0);fake.extraFields=true;try{protocol.read(TOUCH);fail();}catch(HeadUnitSettingsProtocol.Unavailable expected){}assertEquals(0,fake.writes);}
    @Test public void serviceRejectionAndRepeatedDisconnectPreserveExplanation()throws Exception{ready();fake.reject=true;client.change(TOUCH,0,2,true);await(()->client.phase==HeadUnitSettingsClient.Phase.DISCONNECTED);String failure=client.status;assertTrue(failure.contains("rejected"));client.disconnect();assertEquals(failure,client.status);assertTrue(client.values.isEmpty());}
    @Test public void mismatchingReadbackNeverReportsSuccess()throws Exception{ready();fake.mismatch=true;client.change(TOUCH,0,2,true);await(()->client.phase==HeadUnitSettingsClient.Phase.DISCONNECTED);assertTrue(client.status.contains("did not confirm"));assertEquals(1,fake.writes);}
    @Test public void disconnectWhileFreshReadBlocksPreventsSubsequentWrite()throws Exception{ready();fake.blockType=0x20;fake.entered=new CountDownLatch(1);fake.release=new CountDownLatch(1);client.change(TOUCH,0,2,true);assertTrue(fake.entered.await(3,TimeUnit.SECONDS));client.disconnect();fake.release.countDown();await(()->unbinds==1);drainWorker();assertEquals(0,fake.writes);assertTrue(client.values.isEmpty());}
    @Test public void timeoutDuringWriteRejectsLateCompletion()throws Exception{ready();fake.blockWrite=true;fake.entered=new CountDownLatch(1);fake.release=new CountDownLatch(1);client.change(TOUCH,0,2,true);assertTrue(fake.entered.await(3,TimeUnit.SECONDS));ShadowLooper.idleMainLooper(21,TimeUnit.SECONDS);assertEquals(HeadUnitSettingsClient.Phase.DISCONNECTED,client.phase);String failure=client.status;fake.release.countDown();drainWorker();assertEquals(failure,client.status);assertTrue(failure.contains("unknown outcome"));assertTrue(client.values.isEmpty());}
    @Test public void bindingFailureAndLateBindingCannotWrite()throws Exception{failBinding=true;client.connect();assertEquals(HeadUnitSettingsClient.Phase.DISCONNECTED,client.phase);assertEquals(0,fake.reads);failBinding=false;delayBinding=true;client.connect();ShadowLooper.idleMainLooper(21,TimeUnit.SECONDS);delayed.onServiceConnected(new ComponentName(HeadUnitSettingsProtocol.PACKAGE,HeadUnitSettingsProtocol.SERVICE),fake);ShadowLooper.idleMainLooper();assertEquals(0,fake.reads);assertEquals(0,fake.writes);}
    @Test public void wrongBinderAndUnverifiedProtocolRequestsAreRejected()throws Exception{Binder other=new Binder();other.attachInterface(null,"not.honda");try{new HeadUnitSettingsProtocol(other);fail();}catch(RemoteException expected){}HeadUnitSettingsProtocol protocol=new HeadUnitSettingsProtocol(fake);try{protocol.write(TOUCH,1,35);fail();}catch(IllegalArgumentException expected){}try{protocol.read(null);fail();}catch(IllegalArgumentException expected){}assertEquals(0,fake.writes);}
    @Test public void observerCancelDuringConnectingNeverBindsOrArmsTimeout() throws Exception {
        client.destroy();final boolean[] cancelled={false};
        client=new HeadUnitSettingsClient(context,()->{if(!cancelled[0]&&client.phase==HeadUnitSettingsClient.Phase.CONNECTING){cancelled[0]=true;client.disconnect();}});
        client.connect();ShadowLooper.idleMainLooper(21,TimeUnit.SECONDS);
        assertEquals(HeadUnitSettingsClient.Phase.DISCONNECTED,client.phase);assertEquals(0,fake.reads);assertEquals(0,unbinds);assertFalse(client.status.contains("timed out"));
    }
    @Test public void observerReconnectDuringConnectingKeepsOnlyNewSession() throws Exception {
        client.destroy();final boolean[] replaced={false};
        client=new HeadUnitSettingsClient(context,()->{if(!replaced[0]&&client.phase==HeadUnitSettingsClient.Phase.CONNECTING){replaced[0]=true;client.disconnect();client.connect();}});
        ready();assertEquals(12,fake.reads);assertEquals(0,fake.writes);assertEquals(0,unbinds);
    }
    @Test public void observerDestroyDuringWritingDoesNotScheduleOnClosedExecutor() throws Exception {
        ready();client.destroy();final boolean[] destroy={false};
        client=new HeadUnitSettingsClient(context,()->{if(destroy[0]&&client.phase==HeadUnitSettingsClient.Phase.WRITING)client.destroy();});
        ready();destroy[0]=true;client.change(TOUCH,0,2,true);
        assertEquals(HeadUnitSettingsClient.Phase.DISCONNECTED,client.phase);assertEquals(0,fake.writes);
    }
    @Test public void observerReconnectDuringWritingPreservesNewConnectionTimeout() throws Exception {
        client.destroy();final boolean[] reconnect={false};
        client=new HeadUnitSettingsClient(context,()->{if(reconnect[0]&&client.phase==HeadUnitSettingsClient.Phase.WRITING){reconnect[0]=false;client.disconnect();delayBinding=true;client.connect();}});
        ready();reconnect[0]=true;client.change(TOUCH,0,2,true);
        assertEquals(HeadUnitSettingsClient.Phase.CONNECTING,client.phase);ShadowLooper.idleMainLooper(21,TimeUnit.SECONDS);
        assertEquals(HeadUnitSettingsClient.Phase.DISCONNECTED,client.phase);assertTrue(client.status.contains("timed out"));assertEquals(0,fake.writes);
    }
    private void staleSynchronousBinding(boolean throwsAfterReconnect)throws Exception{
        client.destroy();final int[] binds={0};
        Context reentrant=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){
                if(++binds[0]==1){client.disconnect();client.connect();if(throwsAfterReconnect)throw new SecurityException("old binding failed");return false;}
                connection.onServiceConnected(intent.getComponent(),fake);return true;
            }
            @Override public void unbindService(ServiceConnection connection){unbinds++;}
        };
        client=new HeadUnitSettingsClient(reentrant,()->{});ready();assertEquals(2,binds[0]);assertEquals(11,client.values.size());assertEquals(0,fake.writes);
    }
    @Test public void oldSynchronousBindFailureDoesNotKillReplacementSession()throws Exception{staleSynchronousBinding(false);}
    @Test public void oldSynchronousBindExceptionDoesNotKillReplacementSession()throws Exception{staleSynchronousBinding(true);}
    private static <T extends View>T find(View v,Class<T> type,String text){if(type.isInstance(v)&&(text==null||(v instanceof TextView&&((TextView)v).getText().toString().equals(text))))return type.cast(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){T result=find(((ViewGroup)v).getChildAt(i),type,text);if(result!=null)return result;}return null;}
    @Test public void nativeActivityConfirmationUsesFreshValueAndPauseDismissesEditor()throws Exception{
        try(org.robolectric.android.controller.ActivityController<HeadUnitActivity> controller=Robolectric.buildActivity(HeadUnitActivity.class).setup()){
            HeadUnitActivity activity=controller.get();Method render=HeadUnitActivity.class.getDeclaredMethod("render");render.setAccessible(true);Field field=HeadUnitActivity.class.getDeclaredField("client");field.setAccessible(true);((HeadUnitSettingsClient)field.get(activity)).destroy();client.destroy();
            client=new HeadUnitSettingsClient(context,()->{try{render.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});field.set(activity,client);
            View root=activity.getWindow().getDecorView();find(root,Button.class,"Connect to head unit").performClick();await(()->client.phase==HeadUnitSettingsClient.Phase.READY);
            CheckBox parked=find(root,CheckBox.class,null);parked.setChecked(true);client.disconnect();assertFalse(parked.isChecked());
            parked.setChecked(true);assertTrue(parked.isChecked());client.disconnect();assertTrue(parked.isChecked());parked.setChecked(false);
            find(root,Button.class,"Connect to head unit").performClick();await(()->client.phase==HeadUnitSettingsClient.Phase.READY);
            parked.setChecked(true);find(root,Button.class,"Touch-panel sensitivity: Low").performClick();
            AlertDialog choices=ShadowAlertDialog.getLatestAlertDialog();choices.getListView().performItemClick(choices.getListView().getChildAt(1),1,1);
            AlertDialog confirmation=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(choices,confirmation);
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->fake.writes==1&&client.phase==HeadUnitSettingsClient.Phase.READY);
            assertEquals(2,fake.lastValue);find(root,Button.class,"Touch-panel sensitivity: High").performClick();AlertDialog open=ShadowAlertDialog.getLatestAlertDialog();controller.pause().resume();
            assertFalse(open.isShowing());assertFalse(parked.isChecked());assertEquals(HeadUnitSettingsClient.Phase.DISCONNECTED,client.phase);assertEquals(1,fake.writes);
        }
    }
}
