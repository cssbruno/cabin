package com.cabin.hondacustom;

import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class DiagnosticProtocolTest {
    static class Service extends Binder {
        volatile IBinder listener;volatile int initialized,terminated,requests,lastKind,lastId=-1,cancelled=-1;
        volatile boolean rejectInit,rejectRequest,entered,earlyCancel;
        volatile Bundle early;volatile CountDownLatch release;
        Service(){attachInterface(null,"com.mitsubishielectric.ada.appservice.diag.IDiagService");}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            assertEquals(0,flags);data.enforceInterface("com.mitsubishielectric.ada.appservice.diag.IDiagService");
            switch(code){
                case 1:listener=data.readStrongBinder();assertEquals(2,data.readInt());initialized++;reply.writeNoException();reply.writeInt(rejectInit?0:1);return true;
                case 2:terminated++;reply.writeNoException();reply.writeInt(1);return true;
                case 3:lastKind=data.readInt();assertEquals(0,data.readInt());lastId=101+requests;requests++;
                    if(early!=null||earlyCancel){if(earlyCancel)send(listener,7,lastId,null);else respond(lastId,early);entered=true;if(release!=null)try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RemoteException("interrupted");}}
                    reply.writeNoException();reply.writeInt(rejectRequest?-1:lastId);return true;
                case 4:int[] ids=data.createIntArray();assertEquals(1,ids.length);cancelled=ids[0];reply.writeNoException();return true;
                default:return false;
            }
        }
        void respond(int id,Bundle value)throws RemoteException{send(listener,1,id,value);}
        void message(int id,Bundle value)throws RemoteException{send(listener,2,id,value);}
        static void send(IBinder listener,int code,int id,Bundle value)throws RemoteException{
            Parcel p=Parcel.obtain();try{p.writeInterfaceToken("com.mitsubishielectric.ada.appservice.diag.IDiagServiceListner");p.writeInt(id);
                if(code!=7){p.writeInt(value==null?0:1);if(value!=null)value.writeToParcel(p,0);}listener.transact(code,p,null,IBinder.FLAG_ONEWAY);
            }finally{p.recycle();}
        }
    }
    static Bundle hardware(int... codes){Bundle data=new Bundle();data.putInt("error",0);Parcelable[] rows=new Parcelable[codes.length];
        for(int i=0;i<codes.length;i++){Bundle row=new Bundle();row.putInt("ecode",codes[i]);row.putString("time","2026/09/14");row.putString("info","detail");rows[i]=row;}
        if(rows.length>0)data.putParcelableArray("data",rows);return data;
    }
    static Bundle progress(int status,int percent,boolean more){Bundle b=new Bundle();b.putInt("error",0);b.putInt("status",status);b.putInt("progress",percent);b.putBoolean("continue",more);return b;}
    static DiagnosticProtocol.Callback callback(){return new DiagnosticProtocol.Callback(new DiagnosticProtocol.Events(){public void response(int id,Bundle data){}public void cancelled(int id){}public void malformed(){}});}
    @Test public void exactOemWireRequestIdsAndNarrowClear()throws Exception{
        Service s=new Service();DiagnosticProtocol p=new DiagnosticProtocol(s);p.initialize(callback());assertEquals(1,s.initialized);
        int read=p.readHardware();assertEquals(0x0a0201,s.lastKind);assertEquals(101,read);
        p.readTelematics();assertEquals(0x2a0201,s.lastKind);
        int clear=p.clearHardware(true);assertEquals(0x0a0901,s.lastKind);p.cancel(clear);assertEquals(clear,s.cancelled);p.terminate();assertEquals(1,s.terminated);
    }
    @Test public void missingConfirmationAndRejectedRequestsCannotSucceed()throws Exception{
        Service s=new Service();DiagnosticProtocol p=new DiagnosticProtocol(s);try{p.clearHardware(false);fail();}catch(IllegalArgumentException expected){}assertEquals(0,s.requests);
        s.rejectInit=true;try{p.initialize(callback());fail();}catch(RemoteException expected){}
        s.rejectRequest=true;try{p.readHardware();fail();}catch(RemoteException expected){}
        Binder wrong=new Binder();wrong.attachInterface(null,"other");try{new DiagnosticProtocol(wrong);fail();}catch(RemoteException expected){}
    }
    @Test public void callbackUsesRequestIdAndIgnoresOtherMessageTypes()throws Exception{
        final int[] response={-1},cancel={-1};final Bundle[] value={null};
        DiagnosticProtocol.Callback c=new DiagnosticProtocol.Callback(new DiagnosticProtocol.Events(){public void response(int id,Bundle b){response[0]=id;value[0]=b;}public void cancelled(int id){cancel[0]=id;}public void malformed(){fail();}});
        Service.send(c,1,44,hardware(0x515));assertEquals(44,response[0]);assertEquals(0x515,DiagnosticData.hardware(value[0]).get(0).code);
        Service.send(c,2,99,hardware());assertEquals(44,response[0]);Service.send(c,7,44,null);assertEquals(44,cancel[0]);
        Service.send(c,1,45,null);assertEquals(45,response[0]);assertNull(value[0]);
    }
    @Test public void dataSchemasRequireTypedSuccessAndPreserveCodeDetails(){
        assertEquals("0x0515",DiagnosticData.hardware(hardware(0x515)).get(0).codeText());assertTrue(DiagnosticData.hardware(hardware()).isEmpty());
        Bundle malformed=hardware();malformed.remove("error");try{DiagnosticData.hardware(malformed);fail();}catch(IllegalArgumentException expected){}
        malformed=hardware();malformed.putString("data","not an array");try{DiagnosticData.hardware(malformed);fail();}catch(IllegalArgumentException expected){}
        malformed=hardware();malformed.putString("error","0");try{DiagnosticData.hardware(malformed);fail();}catch(IllegalArgumentException expected){}
        Bundle row=new Bundle();row.putStringArray("columns",new String[]{"DTC123","Original description","extra"});Bundle table=new Bundle();table.putInt("error",0);table.putParcelableArray("rows",new Parcelable[]{row});
        assertEquals(Arrays.asList("DTC123","Original description","extra"),DiagnosticData.telematics(table).get(0));
        try{DiagnosticData.telematics(table).get(0).add("changed");fail();}catch(UnsupportedOperationException expected){}
    }
    @Test public void progressDoesNotMistakeFailureOrContinuationForCompletion(){
        assertFalse(DiagnosticData.clear(progress(0,50,true)).complete);assertTrue(DiagnosticData.clear(progress(1,100,false)).complete);
        for(Bundle b:new Bundle[]{progress(-1,100,false),progress(-2,100,false),progress(1,100,true),progress(0,101,true),progress(3,100,false)}){
            try{DiagnosticData.clear(b);fail();}catch(IllegalArgumentException expected){}
        }
    }
}
