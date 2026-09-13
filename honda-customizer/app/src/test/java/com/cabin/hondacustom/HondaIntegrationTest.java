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
public class HondaIntegrationTest {
    private HondaClient client; private FakeService fake; private Catalog catalog;
    static final Setting TACH=new Setting(3,0x5c,1,0,new int[]{1,2},new boolean[]{false,true});
    static class FakeService extends Binder {
        volatile IBinder listener;volatile int reads,changes,modeOn,modeOff,lastCategory;volatile Setting written;
        boolean reject;
        FakeService(){attachInterface(null,HondaProtocol.DESCRIPTOR);}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            data.enforceInterface(HondaProtocol.DESCRIPTOR);
            switch(code){
                case 0x4d:listener=data.readStrongBinder();break;
                case 0x4e:data.readStrongBinder();break;
                case 0x4f:if(data.readInt()==1)modeOn++;else modeOff++;break;
                case 0x51:break;
                case 0x52:lastCategory=data.readInt();reads++;break;
                case 0x53:assertEquals(1,data.readInt());
                    // Independent decoder uses the stock wire order, not Setting.CREATOR.
                    written=new Setting(data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.createIntArray(),data.createBooleanArray());changes++;break;
                default:return false;
            }
            reply.writeNoException();reply.writeInt(reject?-1:0);return true;
        }
        void categories(int... categories)throws Exception{Parcel p=Parcel.obtain();try{p.writeInterfaceToken(HondaProtocol.CALLBACK);p.writeIntArray(categories);listener.transact(2,p,null,IBinder.FLAG_ONEWAY);}finally{p.recycle();}}
        void values(Setting... values)throws Exception{Parcel p=Parcel.obtain();try{p.writeInterfaceToken(HondaProtocol.CALLBACK);p.writeTypedList(Arrays.asList(values));listener.transact(3,p,null,IBinder.FLAG_ONEWAY);}finally{p.recycle();}}
        void result(int type,int result)throws Exception{Parcel p=Parcel.obtain();try{p.writeInterfaceToken(HondaProtocol.CALLBACK);p.writeInt(type);p.writeInt(result);listener.transact(1,p,null,IBinder.FLAG_ONEWAY);}finally{p.recycle();}}
    }
    @Before public void setup()throws Exception{
        catalog=new Catalog(RuntimeEnvironment.getApplication());fake=new FakeService();
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public boolean bindService(Intent i,ServiceConnection c,int f){assertEquals(HondaProtocol.SERVICE,i.getComponent().getClassName());c.onServiceConnected(i.getComponent(),fake);return true;}
            @Override public void unbindService(ServiceConnection c){}
        };
        client=new HondaClient(context,()->{});
    }
    @After public void close(){client.destroy();}
    void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end){ShadowLooper.idleMainLooper();Thread.sleep(5);}ShadowLooper.idleMainLooper();assertTrue("Condition not reached; state="+client.phase+" "+client.status,condition.getAsBoolean());}
    void ready()throws Exception{client.connect();await(()->client.phase==HondaClient.Phase.DISCOVERING);fake.categories(3);await(()->fake.reads==1);fake.values(TACH);await(()->client.phase==HondaClient.Phase.READY);}
    @Test public void wireFormatPreservesFieldsAndNullArrays()throws Exception{
        HondaProtocol protocol=new HondaProtocol(fake);protocol.change(TACH.withValue(2));
        assertEquals(3,fake.written.category);assertEquals(0x5c,fake.written.id);assertEquals(2,fake.written.value);assertEquals(0,fake.written.type);
        assertArrayEquals(new int[]{1,2},fake.written.range);assertArrayEquals(new boolean[]{false,true},fake.written.fob);
        Parcel p=Parcel.obtain();try{new Setting(1,2,3,4,null,null).writeToParcel(p,0);p.setDataPosition(0);Setting decoded=Setting.CREATOR.createFromParcel(p);assertNull(decoded.range);assertNull(decoded.fob);}finally{p.recycle();}
    }
    @Test public void catalogHasUniqueSettingsAndVerifiedSpeedLabels(){
        assertEquals(313,catalog.entries.size());assertEquals(313,catalog.byKey.size());assertEquals("km/h",catalog.byKey.get("3:54").label(1));assertEquals("mph",catalog.byKey.get("3:54").label(2));assertEquals("On",catalog.byKey.get("3:92").label(1));
    }
    @Test public void discoveryDoesNotWriteModeOrValues()throws Exception{ready();assertEquals(0,fake.changes);assertEquals(0,fake.modeOn);assertEquals(0,fake.modeOff);assertEquals(1,client.values.size());}
    @Test public void writeRequiresCallbackThenMatchingReadback()throws Exception{
        ready();client.change(catalog.byKey.get(TACH.key()),2,true);await(()->fake.changes==1);
        assertEquals(HondaClient.Phase.WRITING,client.phase);assertEquals(1,client.values.get(TACH.key()).value);
        fake.result(2,0);ShadowLooper.idleMainLooper();assertEquals(HondaClient.Phase.WRITING,client.phase);
        fake.result(3,0);await(()->fake.reads==2);assertEquals(HondaClient.Phase.VERIFYING,client.phase);
        fake.values(TACH.withValue(2));await(()->client.phase==HondaClient.Phase.READY);assertEquals(2,client.values.get(TACH.key()).value);assertTrue(client.status.contains("verified"));
    }
    @Test public void missingParkAndInvalidValuesCannotWrite()throws Exception{
        ready();Catalog.Entry e=catalog.byKey.get(TACH.key());client.change(e,2,false);client.change(e,999,true);assertEquals(0,fake.changes);assertEquals(HondaClient.Phase.READY,client.phase);
    }
    @Test public void mismatchDisconnectsInsteadOfReportingSuccess()throws Exception{
        ready();client.change(catalog.byKey.get(TACH.key()),2,true);await(()->fake.changes==1);fake.result(3,0);await(()->fake.reads==2);fake.values(TACH);await(()->client.phase==HondaClient.Phase.DISCONNECTED);assertTrue(client.status.contains("did not confirm"));assertTrue(client.values.isEmpty());
    }
    @Test public void timeoutInvalidatesLateCallback()throws Exception{
        ready();client.change(catalog.byKey.get(TACH.key()),2,true);await(()->fake.changes==1);ShadowLooper.idleMainLooper(21,TimeUnit.SECONDS);
        assertEquals(HondaClient.Phase.DISCONNECTED,client.phase);fake.result(3,0);fake.values(TACH.withValue(2));ShadowLooper.idleMainLooper();assertEquals(HondaClient.Phase.DISCONNECTED,client.phase);assertTrue(client.values.isEmpty());
    }
    @Test public void binderRejectionFailsClosed()throws Exception{ready();fake.reject=true;client.change(catalog.byKey.get(TACH.key()),2,true);await(()->client.phase==HondaClient.Phase.DISCONNECTED);assertTrue(client.status.contains("rejected"));}
    @Test public void unknownCategoryCallbackCannotAdvanceRead()throws Exception{client.connect();await(()->client.phase==HondaClient.Phase.DISCOVERING);fake.categories(3);await(()->fake.reads==1);fake.values(new Setting(7,1,1,0,new int[]{1,2},null));ShadowLooper.idleMainLooper();assertEquals(HondaClient.Phase.READING,client.phase);assertTrue(client.values.isEmpty());}
    @Test public void mainActivityLaunchesWithoutHondaService(){try(org.robolectric.android.controller.ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup()){assertNotNull(activity.get());}}
    @Test public void disconnectPreventsQueuedSettingWrite()throws Exception{
        ready();client.change(catalog.byKey.get(TACH.key()),2,true);client.disconnect();await(()->client.phase==HondaClient.Phase.DISCONNECTED);
        fake.result(3,0);ShadowLooper.idleMainLooper();assertTrue(client.values.isEmpty());assertEquals(HondaClient.Phase.DISCONNECTED,client.phase);
    }
    @Test public void unsupportedActionAndMalformedRangeAreReadOnly(){
        Catalog.Entry tach=catalog.byKey.get(TACH.key());assertFalse(tach.editable(new Setting(3,0x5c,1,0,null,null)));
        assertFalse(tach.editable(new Setting(3,0x5c,1,0,new int[]{2,1},null)));
        assertFalse(tach.editable(new Setting(3,0x5c,1,2,new int[]{1,2},null)));
    }
    @Test public void wrongServiceDescriptorIsRejected()throws Exception{
        Binder wrong=new Binder();wrong.attachInterface(null,"some.other.service");
        try{new HondaProtocol(wrong);fail("Must reject incompatible service");}catch(RemoteException expected){}
    }
}
