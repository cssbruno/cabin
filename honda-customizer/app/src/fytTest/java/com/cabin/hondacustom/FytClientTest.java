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
public class FytClientTest {
    FytClient client; FakeModule module; ServiceConnection connection; int bindings,unbindings; boolean nullBinding;
    boolean missingToolkit; volatile boolean holdToolkit,toolkitEntered,releaseToolkit;
    final java.util.List<String> actions=new java.util.ArrayList<>();
    final FytProtocol.Control alarm=FytProtocol.CONTROLS.get(9);
    static class FakeModule extends Binder {
        volatile IBinder callback; volatile int writes; int command; int[] args;
        volatile boolean holdWrite, rejectWrite, released;
        volatile boolean holdDescriptor,descriptorEntered,releaseDescriptor;
        final Set<Integer> fields=Collections.synchronizedSet(new HashSet<>());
        FakeModule(){attachInterface(null,FytProtocol.MODULE);}
        @Override public String getInterfaceDescriptor(){
            if(holdDescriptor){descriptorEntered=true;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!releaseDescriptor&&System.nanoTime()<end)try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
            return super.getInterfaceDescriptor();
        }
        @Override protected boolean onTransact(int code,Parcel d,Parcel r,int flags)throws RemoteException{
            d.enforceInterface("com.syu.ipc.IRemoteModule");
            if(code==3){callback=d.readStrongBinder();fields.add(d.readInt());assertEquals(1,d.readInt());}
            else if(code==4){d.readStrongBinder();fields.remove(d.readInt());}
            else if(code==1){command=d.readInt();args=d.createIntArray();assertNull(d.createFloatArray());assertNull(d.createStringArray());writes++;
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(holdWrite&&!released&&System.nanoTime()<end)try{Thread.sleep(5);}catch(InterruptedException e){throw new RemoteException("Interrupted");}
                if(rejectWrite)throw new RemoteException("Rejected write");
            }
            else return false;
            assertEquals(0,d.dataAvail());r.writeNoException();return true;
        }
        void emit(int field,int raw)throws Exception{Parcel d=Parcel.obtain();try{d.writeInterfaceToken("com.syu.ipc.IModuleCallback");d.writeInt(field);d.writeIntArray(new int[]{raw});d.writeFloatArray(null);d.writeStringArray(null);callback.transact(1,d,null,IBinder.FLAG_ONEWAY);}finally{d.recycle();}}
    }
    @Before public void setup(){module=new FakeModule();
        Binder toolkit=new Binder(){
            {attachInterface(null,FytProtocol.TOOLKIT);}
            @Override protected boolean onTransact(int code,Parcel d,Parcel r,int flags){assertEquals(1,code);d.enforceInterface("com.syu.ipc.IRemoteToolkit");assertEquals(7,d.readInt());assertEquals(0,d.dataAvail());
                if(holdToolkit){toolkitEntered=true;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!releaseToolkit&&System.nanoTime()<end)try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
                r.writeNoException();r.writeStrongBinder(module);return true;}
        };
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public boolean bindService(Intent i,ServiceConnection c,int flags){assertEquals("com.syu.ms",i.getComponent().getPackageName());boolean direct="com.syu.ms.canbus".equals(i.getAction());assertEquals(direct?"app.ModuleService":"app.ToolkitService",i.getComponent().getClassName());assertEquals(direct?"com.syu.ms.canbus":"com.syu.ms.toolkit",i.getAction());actions.add(i.getAction());bindings++;connection=c;if(!direct&&missingToolkit)return false;if(nullBinding)c.onNullBinding(i.getComponent());else c.onServiceConnected(i.getComponent(),direct?module:toolkit);return true;}
            @Override public void unbindService(ServiceConnection c){unbindings++;}
        };
        client=new FytClient(context,()->{});
    }
    @After public void close(){releaseToolkit=true;module.released=true;module.releaseDescriptor=true;client.destroy();}
    void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end){ShadowLooper.idleMainLooper();Thread.sleep(5);}ShadowLooper.idleMainLooper();assertTrue(client.status,condition.getAsBoolean());}
    void ready()throws Exception{client.connect();await(()->module.fields.size()==1);module.emit(1000,0x40141);await(()->module.fields.size()==12);module.emit(69,2);await(()->client.editable(alarm));}
    @Test public void bindsOnlyFytModuleSeven()throws Exception{ready();assertEquals(1,bindings);assertEquals(Integer.valueOf(1),client.values.get(69));}
    @Test public void commandUsesFytEncodingAndWaitsForFeedback()throws Exception{ready();client.change(alarm,2,true);await(()->module.writes==1);assertEquals(106,module.command);assertArrayEquals(new int[]{4,3},module.args);assertTrue(client.busy());assertEquals(Integer.valueOf(1),client.values.get(69));module.emit(69,3);await(()->!client.busy());assertEquals(Integer.valueOf(2),client.values.get(69));assertTrue(client.status.contains("confirmed"));}
    @Test public void parkedRequired()throws Exception{ready();client.change(alarm,2,false);assertFalse(client.busy());assertEquals(0,module.writes);}
    @Test public void unmappedProfileKeepsFytConnectionForReport()throws Exception{client.connect();await(()->module.fields.size()==1);module.emit(1000,0x140141);await(()->client.profile()==0x140141);assertTrue(client.connected());assertTrue(client.values.isEmpty());assertTrue(client.report().contains("0x140141"));}
    @Test public void changedProfileInvalidatesValues()throws Exception{ready();module.emit(1000,0xC0141);await(()->!client.connected());assertFalse(client.editable(alarm));}
    @Test public void invalidFeedbackDisablesControl()throws Exception{ready();module.emit(69,0);await(()->!client.editable(alarm));assertFalse(client.values.containsKey(69));}
    @Test public void expiredFeedbackDisablesControl()throws Exception{ready();ShadowLooper.idleMainLooper(FytClient.FRESH_MS+1,TimeUnit.MILLISECONDS);assertFalse(client.editable(alarm));}
    @Test public void mismatchedFeedbackDoesNotConfirm()throws Exception{ready();client.change(alarm,2,true);await(()->module.writes==1);module.emit(69,1);ShadowLooper.idleMainLooper();assertTrue(client.busy());}
    @Test public void missingFeedbackTimesOut()throws Exception{ready();client.change(alarm,2,true);await(()->module.writes==1);ShadowLooper.idleMainLooper(FytClient.TIMEOUT_MS+1,TimeUnit.MILLISECONDS);assertFalse(client.connected());assertTrue(client.status.contains("unconfirmed"));}
    @Test public void oldSessionCannotRestoreValues()throws Exception{ready();IBinder old=module.callback;client.disconnect();module.callback=old;module.emit(1000,0x40141);module.emit(69,3);ShadowLooper.idleMainLooper();assertFalse(client.connected());assertTrue(client.values.isEmpty());}
    @Test public void binderDeathClearsValues()throws Exception{ready();connection.onServiceDisconnected(new ComponentName("com.syu.ms","app.ToolkitService"));assertFalse(client.connected());assertTrue(client.values.isEmpty());}
    @Test public void wrongBinderDescriptorRejected()throws Exception{try{FytProtocol.module(new Binder());fail();}catch(RemoteException expected){assertTrue(expected.getMessage().contains("interface"));}}
    @Test public void exactProfilesAndVisibility(){assertFalse(FytProtocol.supported(0x141));assertFalse(FytProtocol.visible(0x50141,FytProtocol.CONTROLS.get(0)));assertTrue(FytProtocol.visible(0x50141,FytProtocol.CONTROLS.get(3)));assertTrue(FytProtocol.visible(0xB0141,FytProtocol.CONTROLS.get(8)));assertFalse(FytProtocol.visible(0x40141,FytProtocol.CONTROLS.get(8)));}
    @Test public void oneBasedBoundsAndLowByte(){assertNull(alarm.decode(0));assertNull(alarm.decode(4));assertNull(alarm.decode(-1));assertNull(alarm.decode(65536));assertEquals(Integer.valueOf(2),alarm.decode(0x103));assertEquals(Integer.valueOf(6),FytProtocol.CONTROLS.get(12).decode(7));}
    @Test public void hiddenOrInvalidWritesRejected()throws Exception{try{FytProtocol.change(module,0x50141,FytProtocol.CONTROLS.get(0),1);fail();}catch(IllegalArgumentException expected){}try{FytProtocol.change(module,0x40141,alarm,3);fail();}catch(IllegalArgumentException expected){}assertEquals(0,module.writes);}
    @Test public void earlyFeedbackCannotConfirmRejectedTransaction()throws Exception{ready();module.holdWrite=true;module.rejectWrite=true;client.change(alarm,2,true);await(()->module.writes==1);module.emit(69,3);ShadowLooper.idleMainLooper();assertTrue("Must wait for transaction result",client.busy());assertFalse(client.status.contains("confirmed"));module.released=true;await(()->!client.connected());assertTrue(client.status.contains("unconfirmed"));}
    @Test public void earlyFeedbackIsConfirmedAfterSuccessfulTransaction()throws Exception{ready();module.holdWrite=true;client.change(alarm,2,true);await(()->module.writes==1);module.emit(69,3);ShadowLooper.idleMainLooper();assertTrue(client.busy());module.released=true;await(()->!client.busy());assertTrue(client.status.contains("confirmed"));}
    @Test public void duplicateProfileDoesNotEraseWriteOutcome()throws Exception{ready();client.change(alarm,2,true);await(()->module.writes==1);module.emit(69,3);await(()->!client.busy());String outcome=client.status;module.emit(1000,0x40141);ShadowLooper.idleMainLooper();assertEquals(outcome,client.status);}
    @Test public void synchronousNullBindingIsReleased(){nullBinding=true;client.connect();assertFalse(client.connected());assertEquals(2,unbindings);}
    @Test public void settingsSubscribeOnlyAfterVerifiedProfile()throws Exception{client.connect();await(()->module.fields.size()==1);assertTrue(module.fields.contains(1000));assertFalse(module.fields.contains(69));module.emit(1000,0x40141);await(()->module.fields.size()==12);module.emit(69,2);await(()->client.editable(alarm));}
    @Test public void unsupportedProfileNeverSubscribesSettings()throws Exception{client.connect();await(()->module.fields.size()==1);module.emit(1000,0x141);await(()->client.profile()==0x141);assertFalse(module.fields.contains(69));assertFalse(client.editable(alarm));}
    @Test public void latestFeedbackMustStillMatchWhenTransactionCompletes()throws Exception{ready();module.holdWrite=true;client.change(alarm,2,true);await(()->module.writes==1);module.emit(69,3);module.emit(69,1);ShadowLooper.idleMainLooper();module.released=true;await(()->client.status.startsWith("Waiting"));assertTrue(client.busy());}
    @Test public void disconnectDuringWriteReportsUncertainOutcome()throws Exception{ready();module.holdWrite=true;client.change(alarm,2,true);await(()->module.writes==1);client.disconnect();assertTrue(client.status.contains("unconfirmed"));module.released=true;assertFalse(client.connected());}
    @Test public void everyControlMatchesRecordedFytWireContract()throws Exception{
        int[] fields={109,110,111,88,65,66,67,68,102,69,70,71,72};
        int[] keys={16,15,14,9,6,7,8,5,13,4,3,2,1};
        int[] bases={0,0,0,0,0,0,0,0,0,1,1,1,1};
        int[] sizes={2,2,3,2,2,2,2,2,2,3,3,3,7};
        for(int i=0;i<fields.length;i++){FytProtocol.Control c=FytProtocol.CONTROLS.get(i);assertEquals(fields[i],c.field);assertEquals(sizes[i],c.options.length);
            int p=(i==3||i==8)?0xB0141:0x40141;
            for(int value=0;value<sizes[i];value++){FytProtocol.change(module,p,c,value);assertEquals(106,module.command);assertArrayEquals(new int[]{keys[i],value+bases[i]},module.args);}
        }
    }
    @Test public void disconnectDuringDescriptorLookupPreventsDispatch()throws Exception{
        ready();module.holdDescriptor=true;client.change(alarm,2,true);await(()->module.descriptorEntered);
        client.disconnect();module.releaseDescriptor=true;await(()->module.fields.isEmpty());assertEquals(0,module.writes);
    }
    @Test public void feedbackBeforeDispatchCannotConfirmLaterWrite()throws Exception{
        ready();module.holdDescriptor=true;client.change(alarm,2,true);await(()->module.descriptorEntered);
        module.emit(69,3);ShadowLooper.idleMainLooper();module.releaseDescriptor=true;
        await(()->client.status.startsWith("Waiting"));assertTrue(client.busy());
        module.emit(69,3);await(()->!client.busy());
    }
    @Test public void directCanbusFallbackUsesModuleBinderWithoutToolkitCall()throws Exception{
        missingToolkit=true;ready();assertEquals(java.util.Arrays.asList("com.syu.ms.toolkit","com.syu.ms.canbus"),actions);
        client.change(alarm,2,true);await(()->module.writes==1);assertArrayEquals(new int[]{4,3},module.args);
        assertTrue(client.report().contains("app.ModuleService"));module.emit(69,3);await(()->!client.busy());
    }
    @Test public void blockedToolkitDoesNotBlockDirectCanbusFallback()throws Exception{
        holdToolkit=true;client.connect();await(()->toolkitEntered);ShadowLooper.idleMainLooper(FytClient.TIMEOUT_MS+1,TimeUnit.MILLISECONDS);
        await(()->module.fields.contains(1000));assertTrue(actions.contains("com.syu.ms.canbus"));
        module.emit(1000,0x40141);await(()->module.fields.size()==12);module.emit(69,2);await(()->client.editable(alarm));
    }
    @Test public void rzcCivicUsesItsOwnUnitsAndTachometerCommands()throws Exception{
        missingToolkit=true;client.connect();await(()->module.fields.contains(1000));module.emit(1000,0x10012a);
        await(()->module.fields.size()==4);assertEquals(new HashSet<>(java.util.Arrays.asList(1000,77,78,87)),module.fields);
        FytProtocol.Control units=FytProtocol.CONTROLS.get(13);module.emit(77,0);await(()->client.editable(units));
        client.change(units,1,true);await(()->module.writes==1);assertEquals(105,module.command);assertArrayEquals(new int[]{21,1},module.args);
        module.emit(77,1);await(()->!client.busy());assertFalse(client.editable(alarm));
    }
    @Test public void bnrDoesNotExposeItsHiddenUnitsControl()throws Exception{
        client.connect();await(()->module.fields.contains(1000));module.emit(1000,0x6012a);await(()->module.fields.size()==3);
        assertEquals(new HashSet<>(java.util.Arrays.asList(1000,78,87)),module.fields);
        assertFalse(FytProtocol.visible(0x6012a,FytProtocol.CONTROLS.get(13)));
        FytProtocol.change(module,0x6012a,FytProtocol.CONTROLS.get(14),1);assertEquals(105,module.command);assertArrayEquals(new int[]{22,1},module.args);
    }
    @Test public void decoderDialectsNeverShareCommandsOrLowByteDecoding()throws Exception{
        for(int p:new int[]{0x10012a,0x11012a,0x29012a}){assertTrue(FytProtocol.supported(p));assertFalse(FytProtocol.visible(p,alarm));
            FytProtocol.change(module,p,FytProtocol.CONTROLS.get(15),1);assertArrayEquals(new int[]{35,1},module.args);assertEquals(105,module.command);}
        assertFalse(FytProtocol.supported(0x12012a));assertFalse(FytProtocol.visible(0x40141,FytProtocol.CONTROLS.get(14)));
        assertNull(FytProtocol.CONTROLS.get(14).decode(0x101));
    }
}
