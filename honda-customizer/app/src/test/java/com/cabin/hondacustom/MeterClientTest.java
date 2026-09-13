package com.cabin.hondacustom;

import android.content.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class MeterClientTest {
    private MeterClient client;private MeterProtocolTest.Service service;
    @Before public void setup(){
        service=new MeterProtocolTest.Service();
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){
                assertEquals(MeterProtocol.PACKAGE,intent.getComponent().getPackageName());assertEquals(MeterProtocol.SERVICE,intent.getComponent().getClassName());
                connection.onServiceConnected(intent.getComponent(),service);return true;
            }
            @Override public void unbindService(ServiceConnection connection){}
        };
        client=new MeterClient(context,()->{});
    }
    @After public void close(){client.destroy();}
    private void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<end){ShadowLooper.idleMainLooper();Thread.sleep(5);}
        ShadowLooper.idleMainLooper();assertTrue(client.phase+": "+client.status,condition.getAsBoolean());
    }
    private void ready()throws Exception{client.connect();await(()->service.reads==1);assertEquals(5,service.lastRequest);service.data(1,2,2,10,0,3);await(()->client.phase==MeterClient.Phase.READY);}
    @Test public void connectionReadsCapabilitiesWithoutWriting()throws Exception{
        ready();assertEquals(0,service.writes);assertEquals(0,service.on);assertTrue(client.editable());assertTrue(client.isProtected(0));
        assertTrue(client.available().contains(66));assertFalse(client.available().contains(67));assertEquals(Arrays.asList(0,3),client.draft());
    }
    @Test public void editingHonorsCapabilitiesProtectionAndParkedGate()throws Exception{
        ready();client.remove(0);assertEquals(Arrays.asList(0,3),client.draft());client.add(67);assertEquals(2,client.draft().size());
        client.add(14);client.move(2,0);client.remove(2);assertEquals(Arrays.asList(14,0),client.draft());
        client.save(false);assertEquals(0,service.writes);assertEquals(MeterClient.Phase.READY,client.phase);
        client.discard();assertFalse(client.dirty());assertEquals(Arrays.asList(0,3),client.draft());
    }
    @Test public void saveRequiresAckAndMatchingPresetReadback()throws Exception{
        ready();client.add(14);client.move(2,0);client.save(true);await(()->service.writes==1);
        assertArrayEquals(new int[]{14,0,3},service.ids);assertEquals(2,service.preset);assertEquals(0,service.max);
        // Unsolicited data before acknowledgement cannot confirm a write.
        service.data(1,2,3,10,14,0,3);ShadowLooper.idleMainLooper();assertEquals(MeterClient.Phase.WRITING,client.phase);
        service.changed(1);await(()->service.reads==2);assertEquals(2,service.lastRequest);assertEquals(1,service.off);
        service.data(1,1,3,10,14,0,3);ShadowLooper.idleMainLooper();assertEquals(MeterClient.Phase.VERIFYING,client.phase);
        service.data(1,2,3,10,14,0,3);await(()->client.phase==MeterClient.Phase.READY);assertTrue(client.status.contains("verified"));assertFalse(client.dirty());
    }
    @Test public void presetsAndDefaultsUseDistinctReadRequests()throws Exception{
        ready();client.loadPreset(3);await(()->service.reads==2);assertEquals(3,service.lastRequest);service.data(1,3,2,10,0,14);await(()->client.phase==MeterClient.Phase.READY);
        client.loadDefaults();await(()->service.reads==3);assertEquals(4,service.lastRequest);service.data(1,0,2,10,0,3);await(()->client.phase==MeterClient.Phase.READY);
        assertEquals(3,client.live.preset);assertTrue(client.dirty());client.save(true);await(()->service.writes==1);assertEquals(3,service.preset);assertArrayEquals(new int[]{0,3},service.ids);
    }
    @Test public void dirtyDraftBlocksSilentPresetSwitch()throws Exception{ready();client.add(14);client.loadPreset(1);client.refresh();client.loadDefaults();assertEquals(1,service.reads);assertTrue(client.dirty());}
    @Test public void runtimeCapabilityChangeBlocksSave()throws Exception{
        ready();client.remove(1);service.protectedIds=new int[]{0,3};client.save(true);await(()->client.phase==MeterClient.Phase.DISCONNECTED);assertEquals(0,service.writes);assertNull(client.live);
    }
    @Test public void readbackMismatchFailsClosed()throws Exception{
        ready();client.add(14);client.save(true);await(()->service.writes==1);service.changed(1);await(()->service.reads==2);
        service.data(1,2,2,10,0,3);await(()->client.phase==MeterClient.Phase.DISCONNECTED);assertTrue(client.status.contains("did not confirm"));assertNull(client.live);
    }
    @Test public void rejectedAckNeverStartsReadback()throws Exception{
        ready();client.add(14);client.save(true);await(()->service.writes==1);service.changed(2);await(()->client.phase==MeterClient.Phase.DISCONNECTED);assertEquals(1,service.reads);
    }
    @Test public void timeoutAndOldSessionCallbacksCannotReviveState()throws Exception{
        ready();IBinder old=service.callback;client.add(14);client.save(true);await(()->service.writes==1);
        ShadowLooper.idleMainLooper(21,TimeUnit.SECONDS);assertEquals(MeterClient.Phase.DISCONNECTED,client.phase);assertNull(client.live);
        client.connect();await(()->service.reads==2);MeterProtocolTest.Service.sendData(old,1,2,2,10,0,3);ShadowLooper.idleMainLooper();assertEquals(MeterClient.Phase.READING,client.phase);assertNull(client.live);
        service.data(1,2,2,10,0,3);await(()->client.phase==MeterClient.Phase.READY);
    }
    @Test public void malformedReplyAndMissingCapabilityDataAreUnavailable()throws Exception{
        client.connect();await(()->service.reads==1);service.data(1,2,2,12,0,3);await(()->client.phase==MeterClient.Phase.DISCONNECTED);assertFalse(client.editable());
        service.display=null;client.connect();await(()->client.phase==MeterClient.Phase.DISCONNECTED);assertNull(client.live);
    }
    @Test public void leavingDuringSavePreservesUnknownOutcome()throws Exception{
        ready();client.add(14);client.save(true);await(()->service.writes==1);client.disconnect();
        assertEquals(MeterClient.Phase.DISCONNECTED,client.phase);assertTrue(client.status.contains("Outcome unknown"));
        String reason=client.status;client.disconnect();assertEquals(reason,client.status);
        service.changed(1);ShadowLooper.idleMainLooper();assertEquals(MeterClient.Phase.DISCONNECTED,client.phase);assertNull(client.live);
    }
    @Test public void defaultsRefreshCapacityWithoutOverwritingBaselineOrWriting()throws Exception{
        Arrays.fill(service.display,true);ready();
        client.loadDefaults();await(()->service.reads==2);
        service.data(1,0,11,15,0,1,2,3,4,5,6,7,8,9,10);await(()->client.phase==MeterClient.Phase.READY);
        assertEquals(15,client.capacity());assertEquals(10,client.live.max);assertEquals(Arrays.asList(0,3),client.live.contents());
        assertEquals(2,client.live.preset);assertEquals(11,client.draft().size());assertEquals(0,service.writes);assertTrue(client.canAdd(14));
        client.save(true);await(()->service.writes==1);assertEquals(11,service.count);assertEquals(2,service.preset);
    }
    @Test public void smallerDefaultCapacityRestrictsDraftEvenWithLargerBaseline()throws Exception{
        Arrays.fill(service.display,true);client.connect();await(()->service.reads==1);
        service.data(1,2,12,15,0,1,2,3,4,5,6,7,8,9,10,14);await(()->client.phase==MeterClient.Phase.READY);
        client.loadDefaults();await(()->service.reads==2);service.data(1,0,10,10,0,1,2,3,4,5,6,7,8,9);await(()->client.phase==MeterClient.Phase.READY);
        assertEquals(10,client.capacity());assertEquals(12,client.live.count);assertFalse(client.canAdd(14));client.add(14);assertEquals(10,client.draft().size());
        client.save(true);await(()->service.writes==1);assertEquals(10,service.count);assertEquals(2,service.preset);
    }
    @Test public void successfulSavePublishesNewProtectionAndAvailability()throws Exception{
        ready();client.add(14);service.protectedIds=new int[]{0,3};service.content66=false;
        client.save(true);await(()->service.writes==1);service.changed(1);await(()->service.reads==2);
        service.data(1,2,3,10,0,3,14);await(()->client.phase==MeterClient.Phase.READY);
        assertTrue(client.isProtected(3));assertFalse(client.available().contains(66));client.remove(1);assertEquals(Arrays.asList(0,3,14),client.draft());
    }
    @Test public void synchronousDisconnectDuringBindStillUnbindsRegistration(){
        client.destroy();final int[] unbinds={0};
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){connection.onServiceDisconnected(intent.getComponent());return true;}
            @Override public void unbindService(ServiceConnection connection){unbinds[0]++;}
        };
        client=new MeterClient(context,()->{});client.connect();assertEquals(MeterClient.Phase.DISCONNECTED,client.phase);assertEquals(1,unbinds[0]);
    }
    @Test public void observerCancellationDoesNotStartBinding(){
        client.destroy();final int[] binds={0};
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){binds[0]++;return true;}
        };
        client=new MeterClient(context,()->{if(client.phase==MeterClient.Phase.CONNECTING)client.disconnect();});
        client.connect();assertEquals(MeterClient.Phase.DISCONNECTED,client.phase);assertEquals(0,binds[0]);
        String status=client.status;ShadowLooper.idleMainLooper(21,TimeUnit.SECONDS);assertEquals(status,client.status);
    }
    @Test public void observerReconnectCannotSendPreviousDraftOnNewSession()throws Exception{
        client.destroy();final boolean[] reconnect={true};
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){connection.onServiceConnected(intent.getComponent(),service);return true;}
            @Override public void unbindService(ServiceConnection connection){}
        };
        client=new MeterClient(context,()->{if(client.phase==MeterClient.Phase.WRITING&&reconnect[0]){reconnect[0]=false;client.disconnect();client.connect();}});
        ready();client.add(14);client.save(true);await(()->service.reads==2);
        assertEquals(0,service.writes);assertEquals(MeterClient.Phase.READING,client.phase);
        service.data(1,2,2,10,0,3);await(()->client.phase==MeterClient.Phase.READY);assertFalse(client.dirty());
    }
    @Test public void defaultPresetCannotBeWrittenDirectly()throws Exception{
        client.connect();await(()->service.reads==1);service.data(1,0,2,10,0,3);await(()->client.phase==MeterClient.Phase.READY);assertFalse(client.editable());client.add(14);client.save(true);assertEquals(0,service.writes);
    }
}
