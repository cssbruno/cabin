package com.cabin.hondacustom;
import android.content.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class HondaTachometerPreferenceTest {
 HondaClient client; HondaIntegrationTest.FakeService vehicle; Unit unit; Catalog.Entry entry;
 boolean bindingFailure,delayBinding; ServiceConnection delayed; int unbound;
 static class Unit extends Binder {
  volatile int writes,reads,stored=-1; boolean reject,mismatch;
  Unit(){attachInterface(null,UnitInfoProtocol.DESCRIPTOR);}
  @Override protected boolean onTransact(int code,Parcel p,Parcel r,int flags)throws RemoteException{
   p.enforceInterface(UnitInfoProtocol.DESCRIPTOR);assertEquals(1,p.readInt());assertEquals(0x21,p.readInt());
   Object value=p.readValue(Integer.class.getClassLoader());assertNull(p.readArrayList(null));assertNull(p.createIntArray());assertEquals(0,p.dataAvail());
   if(code==0x1d){assertTrue(value instanceof Integer);stored=(Integer)value;writes++;r.writeNoException();r.writeInt(reject?-1:0);return true;}
   if(code==0x1e){assertNull(value);reads++;r.writeNoException();r.writeInt(0);r.writeInt(1);r.writeInt(0x21);r.writeValue(mismatch?1:stored);r.writeList(null);r.writeIntArray(null);return true;}
   return false;
  }
 }
 @Before public void setup()throws Exception{unit=new Unit();vehicle=new HondaIntegrationTest.FakeService();entry=new Catalog(RuntimeEnvironment.getApplication()).byKey.get("3:92");
  Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){
   @Override public boolean bindService(Intent i,ServiceConnection c,int flags){
    if(i.getComponent().getClassName().equals(HondaProtocol.SERVICE)){c.onServiceConnected(i.getComponent(),vehicle);return true;}
    assertEquals(UnitInfoProtocol.SERVICE,i.getComponent().getClassName());if(bindingFailure)return false;if(delayBinding){delayed=c;return true;}c.onServiceConnected(i.getComponent(),unit);return true;
   }
   @Override public void unbindService(ServiceConnection c){unbound++;}
  };client=new HondaClient(context,()->{});
 }
 @After public void close(){client.destroy();}
 void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end){ShadowLooper.idleMainLooper();Thread.sleep(5);}ShadowLooper.idleMainLooper();assertTrue(client.phase+" "+client.status,condition.getAsBoolean());}
 void ready()throws Exception{client.connect();await(()->client.phase==HondaClient.Phase.DISCOVERING);vehicle.categories(3);await(()->vehicle.reads==1);vehicle.values(HondaIntegrationTest.TACH);await(()->client.phase==HondaClient.Phase.READY);assertEquals(0,unit.writes);}
 @Test public void preferenceIsVerifiedBeforeVehicleRequest()throws Exception{ready();client.changeSystemTachometer(entry,2,true);await(()->vehicle.changes==1);assertEquals(0,unit.stored);assertEquals(1,unit.reads);vehicle.result(3,0);await(()->vehicle.reads==2);vehicle.values(HondaIntegrationTest.TACH.withValue(2));await(()->client.phase==HondaClient.Phase.READY);assertTrue(client.status.contains("preference also verified"));assertTrue(unbound>0);}
 @Test public void bindingFailureCannotChangeVehicle()throws Exception{ready();bindingFailure=true;client.changeSystemTachometer(entry,2,true);await(()->client.phase==HondaClient.Phase.DISCONNECTED);assertEquals(0,vehicle.changes);assertEquals(0,unit.writes);}
 @Test public void preferenceRejectionReportsPartialOutcome()throws Exception{ready();unit.reject=true;client.changeSystemTachometer(entry,2,true);await(()->client.phase==HondaClient.Phase.DISCONNECTED);assertEquals(0,vehicle.changes);assertTrue(client.status.contains("preference may have changed"));}
 @Test public void readbackMismatchCannotChangeVehicle()throws Exception{ready();unit.mismatch=true;client.changeSystemTachometer(entry,2,true);await(()->client.phase==HondaClient.Phase.DISCONNECTED);assertEquals(0,vehicle.changes);assertTrue(client.status.contains("mismatch"));}
 @Test public void vehicleFailureAfterPreferenceHasHonestOutcome()throws Exception{ready();client.changeSystemTachometer(entry,2,true);await(()->vehicle.changes==1);vehicle.result(3,9);await(()->client.phase==HondaClient.Phase.DISCONNECTED);assertTrue(client.status.contains("preference may have changed"));assertEquals(0,unit.stored);}
 @Test public void timedOutBindingAndStaleConnectionDoNotWrite()throws Exception{ready();delayBinding=true;client.changeSystemTachometer(entry,2,true);assertNotNull(delayed);ShadowLooper.idleMainLooper(21,TimeUnit.SECONDS);assertEquals(HondaClient.Phase.DISCONNECTED,client.phase);delayed.onServiceConnected(new ComponentName(UnitInfoProtocol.PACKAGE,UnitInfoProtocol.SERVICE),unit);ShadowLooper.idleMainLooper();assertEquals(0,unit.writes);assertEquals(0,vehicle.changes);}
}
