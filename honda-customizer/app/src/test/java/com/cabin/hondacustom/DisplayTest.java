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

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class DisplayTest {
    private DisplayClient client;private Context context;private Fake fake;
    private boolean synchronousDisconnect;private int binds,unbinds;
    static class Fake extends Binder {
        int[] parameters={1,0,4,2,-1,9,10,20};int[] written;
        volatile int reads,writes;boolean noValue,reject,mismatch;
        boolean blockRead,blockWrite;CountDownLatch blocked,release;
        Fake(){attachInterface(null,DisplayProtocol.DESCRIPTOR);}
        private void waitHere(){blocked.countDown();try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            data.enforceInterface("com.mitsubishielectric.ada.appservice.avapservice.IAvApService");assertEquals(0,flags);assertEquals(0,data.readInt());
            if(code==0x2c){assertEquals(0,data.dataAvail());reads++;if(blockRead)waitHere();reply.writeNoException();reply.writeInt(noValue?0:1);if(!noValue)for(int value:parameters)reply.writeInt(value);return true;}
            if(code==0x2d){assertEquals(1,data.readInt());written=new int[8];for(int i=0;i<8;i++)written[i]=data.readInt();assertEquals(0,data.dataAvail());writes++;
                if(blockWrite)waitHere();if(!reject&&!mismatch)parameters=written.clone();reply.writeNoException();reply.writeInt(reject?0:1);return true;}
            throw new AssertionError("Unexpected AV transaction "+code);
        }
    }
    @Before public void setup(){
        fake=new Fake();context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){
                assertEquals(DisplayProtocol.SERVICE,intent.getComponent().getClassName());binds++;
                if(synchronousDisconnect)connection.onServiceDisconnected(intent.getComponent());else connection.onServiceConnected(intent.getComponent(),fake);return true;
            }
            @Override public void unbindService(ServiceConnection connection){unbinds++;}
        };client=new DisplayClient(context,()->{});
    }
    @After public void close(){if(fake.release!=null)fake.release.countDown();client.destroy();}
    private void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end){ShadowLooper.idleMainLooper();Thread.sleep(5);}
        ShadowLooper.idleMainLooper();assertTrue(client.phase+": "+client.status,condition.getAsBoolean());
    }
    private void ready()throws Exception{client.connect();await(()->client.phase==DisplayClient.Phase.READY);}
    @Test public void readParsesEightFieldsWithoutWrites()throws Exception{
        ready();assertEquals(0,fake.writes);assertEquals(1,client.value.mode);assertEquals(0,client.value.type);
        assertEquals(4,client.value.brightness);assertEquals(2,client.value.contrast);assertEquals(-1,client.value.blackLevel);assertEquals(9,client.value.tint);assertEquals(10,client.value.density);assertEquals(20,client.value.illStep);
    }
    @Test public void writeUsesFreshFullBaselineAndConfirmsAllFields()throws Exception{
        ready();fake.parameters[3]=-4;fake.parameters[5]=7;fake.parameters[7]=18;
        client.change(DisplayProtocol.Field.BRIGHTNESS,6,true);await(()->fake.writes==1&&client.phase==DisplayClient.Phase.READY);
        assertArrayEquals(new int[]{1,0,6,-4,-1,7,10,18},fake.written);assertEquals(3,fake.reads);assertTrue(client.status.contains("confirmed by service readback"));
    }
    @Test public void parkedAndRangeGatesPreventWriting()throws Exception{
        ready();client.change(DisplayProtocol.Field.BRIGHTNESS,6,false);client.change(DisplayProtocol.Field.CONTRAST,6,true);
        client.change(DisplayProtocol.Field.BLACK_LEVEL,-6,true);client.change(DisplayProtocol.Field.BRIGHTNESS,11,true);assertEquals(0,fake.writes);
    }
    @Test public void offAndOnModesAreReadOnlyEvenThoughOemOffReturnsTrue()throws Exception{
        fake.parameters[0]=0;ready();assertFalse(client.value.writable());client.change(DisplayProtocol.Field.BRIGHTNESS,6,true);assertEquals(0,fake.writes);
        fake.parameters[0]=3;client.refresh();await(()->client.phase==DisplayClient.Phase.READY);assertFalse(client.value.writable());client.change(DisplayProtocol.Field.BRIGHTNESS,6,true);assertEquals(0,fake.writes);
    }
    @Test public void modeChangeBeforeWriteStopsMutation()throws Exception{
        ready();fake.parameters[0]=2;client.change(DisplayProtocol.Field.BRIGHTNESS,6,true);await(()->client.phase==DisplayClient.Phase.DISCONNECTED);
        assertEquals(0,fake.writes);assertTrue(client.status.contains("mode or type changed"));
    }
    @Test public void foreignTypeAndMissingValueAreRejected()throws Exception{
        fake.parameters[1]=1;client.connect();await(()->client.phase==DisplayClient.Phase.DISCONNECTED);assertEquals(0,fake.writes);
        fake.parameters[1]=0;fake.noValue=true;client.connect();await(()->client.phase==DisplayClient.Phase.DISCONNECTED);assertNull(client.value);
    }
    @Test public void rejectedWriteCannotReportSuccess()throws Exception{
        ready();fake.reject=true;client.change(DisplayProtocol.Field.CONTRAST,-5,true);await(()->client.phase==DisplayClient.Phase.DISCONNECTED);
        assertTrue(client.status.contains("rejected"));assertTrue(client.status.contains("outcome is unknown"));assertNull(client.value);
    }
    @Test public void nonmatchingReadbackCannotReportSuccess()throws Exception{
        ready();fake.mismatch=true;client.change(DisplayProtocol.Field.BLACK_LEVEL,5,true);await(()->client.phase==DisplayClient.Phase.DISCONNECTED);
        assertTrue(client.status.contains("did not confirm"));assertNull(client.value);
    }
    @Test public void cancelDuringFreshReadPreventsWrite()throws Exception{
        ready();fake.blockRead=true;fake.blocked=new CountDownLatch(1);fake.release=new CountDownLatch(1);
        client.change(DisplayProtocol.Field.BRIGHTNESS,6,true);assertTrue(fake.blocked.await(3,TimeUnit.SECONDS));client.disconnect();fake.blockRead=false;fake.release.countDown();
        client.connect();await(()->client.phase==DisplayClient.Phase.READY);assertEquals(0,fake.writes);
    }
    @Test public void timeoutDuringWritePreservesUnknownOutcomeAndIgnoresLateReply()throws Exception{
        ready();fake.blockWrite=true;fake.blocked=new CountDownLatch(1);fake.release=new CountDownLatch(1);
        client.change(DisplayProtocol.Field.BRIGHTNESS,6,true);assertTrue(fake.blocked.await(3,TimeUnit.SECONDS));ShadowLooper.idleMainLooper(21,TimeUnit.SECONDS);
        assertEquals(DisplayClient.Phase.DISCONNECTED,client.phase);assertTrue(client.status.contains("outcome is unknown"));fake.release.countDown();
        client.connect();await(()->client.phase==DisplayClient.Phase.READY);assertEquals(6,client.value.brightness);assertEquals(1,fake.writes);
    }
    @Test public void synchronousDisconnectUnbindsReturnedBinding(){
        synchronousDisconnect=true;client.connect();assertEquals(DisplayClient.Phase.DISCONNECTED,client.phase);assertEquals(1,unbinds);client.disconnect();assertEquals(1,unbinds);
    }
    @Test public void observerDestroyDoesNotBindOrQueueRejectedWork(){
        client.destroy();client=new DisplayClient(context,()->{if(client.phase==DisplayClient.Phase.CONNECTING)client.destroy();});client.connect();assertEquals(0,binds);
    }
    @Test public void observerCancelBeforeWriteDoesNotReadOrMutate()throws Exception{
        client.destroy();client=new DisplayClient(context,()->{if(client.phase==DisplayClient.Phase.WRITING)client.disconnect();});ready();
        int reads=fake.reads;client.change(DisplayProtocol.Field.BRIGHTNESS,6,true);assertEquals(reads,fake.reads);assertEquals(0,fake.writes);assertEquals(DisplayClient.Phase.DISCONNECTED,client.phase);
    }
    @Test public void wrongServiceDescriptorIsRejected()throws Exception{
        Binder other=new Binder();other.attachInterface(null,"other");try{new DisplayProtocol(other);fail();}catch(RemoteException expected){}
    }
    @Test public void displayScreenDoesNotAutoConnectOrLaunch(){
        try(org.robolectric.android.controller.ActivityController<DisplayActivity> activity=Robolectric.buildActivity(DisplayActivity.class).setup()){
            assertNull(Shadows.shadowOf(activity.get()).getNextStartedActivity());assertEquals(0,binds);
        }
    }
    @Test public void repeatedLifecycleDisconnectPreservesFailedWriteOutcome()throws Exception{
        ready();fake.reject=true;client.change(DisplayProtocol.Field.BRIGHTNESS,6,true);await(()->client.phase==DisplayClient.Phase.DISCONNECTED);
        String failure=client.status;assertTrue(failure.contains("outcome is unknown"));client.disconnect();client.disconnect();client.destroy();assertEquals(failure,client.status);
    }
}
