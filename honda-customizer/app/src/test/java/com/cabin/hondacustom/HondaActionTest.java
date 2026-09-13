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
public class HondaActionTest {
 HondaClient client; Fake fake;
 static final Setting TPMS=new Setting(1,1,0,2,null,null),TACH=HondaIntegrationTest.TACH;
 static class Fake extends HondaIntegrationTest.FakeService {
  volatile int tpms,resets,maintenanceResets,maintenanceType,maintenanceItem,maintenanceOff; volatile IBinder maintenance;
  @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
   if(code!=0x50&&code!=0x54&&code!=0x16&&code!=0x17&&code!=0x55&&code!=0x56)return super.onTransact(code,data,reply,flags);
   data.enforceInterface(HondaProtocol.DESCRIPTOR);
   if(code==0x50)resets++;if(code==0x54)tpms++;
   if(code==0x16)maintenance=data.readStrongBinder();if(code==0x17){data.readStrongBinder();maintenance=null;}
   if(code==0x55&&data.readInt()==0)maintenanceOff++;
   if(code==0x56){maintenanceType=data.readInt();maintenanceItem=data.readInt();maintenanceResets++;}
   assertEquals(0,data.dataAvail());reply.writeNoException();reply.writeInt(reject?-1:0);return true;
  }
  void maintenance(int[] status)throws Exception{Parcel p=Parcel.obtain();try{p.writeInterfaceToken(HondaProtocol.MAINTENANCE_CALLBACK);p.writeIntArray(status);maintenance.transact(1,p,null,IBinder.FLAG_ONEWAY);}finally{p.recycle();}}
 }
 @Before public void setup(){fake=new Fake();Context ctx=new ContextWrapper(RuntimeEnvironment.getApplication()){
  @Override public boolean bindService(Intent i,ServiceConnection c,int f){c.onServiceConnected(i.getComponent(),fake);return true;}
  @Override public void unbindService(ServiceConnection c){}
 };client=new HondaClient(ctx,()->{});}
 @After public void close(){client.destroy();}
 void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end){ShadowLooper.idleMainLooper();Thread.sleep(5);}ShadowLooper.idleMainLooper();assertTrue(client.phase+" "+client.status,condition.getAsBoolean());}
 void ready()throws Exception{client.connect();await(()->client.phase==HondaClient.Phase.DISCOVERING);fake.categories(1,3);await(()->fake.reads==1);fake.values(TPMS);await(()->fake.reads==2);fake.values(TACH);await(()->client.phase==HondaClient.Phase.READY);}
 @Test public void noActionWithoutLiveSupportOrPark()throws Exception{client.calibrateTpms(true);client.resetCustomization(true);assertEquals(0,fake.tpms+fake.resets);ready();client.calibrateTpms(false);client.resetCustomization(false);assertEquals(0,fake.tpms+fake.resets);assertTrue(client.canCalibrateTpms());}
 @Test public void tpmsWaitsForCorrectCallbackAndReadback()throws Exception{ready();client.calibrateTpms(true);await(()->fake.tpms==1);client.resetCustomization(true);assertEquals(0,fake.resets);fake.result(3,0);ShadowLooper.idleMainLooper();assertEquals(HondaClient.Phase.ACTION,client.phase);fake.result(1,0);await(()->fake.reads==3);fake.values(TPMS);await(()->client.phase==HondaClient.Phase.READY);assertTrue(client.status.contains("does not confirm"));assertEquals(1,fake.modeOff);}
 @Test public void resetRediscoverAllSettings()throws Exception{ready();client.resetCustomization(true);await(()->fake.resets==1);fake.result(0,0);await(()->client.phase==HondaClient.Phase.DISCOVERING);assertTrue(client.values.isEmpty());fake.categories(3);await(()->fake.reads==3);fake.values(TACH);await(()->client.phase==HondaClient.Phase.READY);assertTrue(client.status.contains("not independently verified"));}
 @Test public void rejectedActionFailsClosed()throws Exception{ready();client.calibrateTpms(true);await(()->fake.tpms==1);fake.result(1,7);await(()->client.phase==HondaClient.Phase.DISCONNECTED);assertFalse(client.canCalibrateTpms());}
 @Test public void actionTimeoutCleansModeAndIgnoresLateResult()throws Exception{ready();client.resetCustomization(true);await(()->fake.resets==1);ShadowLooper.idleMainLooper(21,TimeUnit.SECONDS);await(()->fake.modeOff==1);fake.result(0,0);ShadowLooper.idleMainLooper();assertEquals(HondaClient.Phase.DISCONNECTED,client.phase);}
 @Test public void maintenanceOptionsUseVariantAndLiveFlags(){int[] status=new int[34];status[2]=1;status[22]=1;status[6]=1;status[32]=1;Map<Integer,String> options=HondaMaintenance.options(status);assertEquals(2,options.size());assertTrue(options.containsKey(10));assertTrue(options.containsKey(-1));assertFalse(options.containsKey(8));status[2]=99;assertTrue(HondaMaintenance.options(status).isEmpty());assertTrue(HondaMaintenance.options(new int[2]).isEmpty());}
 @Test public void maintenanceUsesExactItemAndCleansBothModes()throws Exception{ready();client.requestMaintenanceStatus();await(()->fake.maintenance!=null);int[] status=new int[34];status[2]=1;status[22]=1;status[6]=1;fake.maintenance(status);await(()->client.phase==HondaClient.Phase.READY);client.resetMaintenance(11,true);assertEquals(0,fake.maintenanceResets);client.resetMaintenance(10,true);await(()->fake.maintenanceResets==1);assertEquals(1,fake.maintenanceType);assertEquals(10,fake.maintenanceItem);fake.result(2,0);await(()->client.phase==HondaClient.Phase.MAINTENANCE_READING&&fake.maintenanceOff==1&&fake.maintenance!=null);fake.maintenance(status);await(()->client.phase==HondaClient.Phase.READY);assertTrue(client.status.contains("not verified"));assertEquals(1,fake.modeOff);}
 @Test public void allDueWireAndUnknownItems()throws Exception{HondaProtocol p=new HondaProtocol(fake);p.resetMaintenance(-1);assertEquals(0,fake.maintenanceType);assertEquals(0,fake.maintenanceItem);try{p.resetMaintenance(12);fail();}catch(IllegalArgumentException expected){}assertEquals(1,fake.maintenanceResets);}
}
