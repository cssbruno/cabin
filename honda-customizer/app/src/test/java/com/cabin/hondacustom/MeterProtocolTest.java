package com.cabin.hondacustom;

import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class MeterProtocolTest {
    static class Service extends Binder {
        volatile IBinder callback;
        volatile int reads,lastRequest,writes,on,off,unregisters;
        volatile int preset,count,max;volatile int[] ids;
        volatile boolean reject;
        volatile boolean[] display=new boolean[64];
        volatile int[] protectedIds={0};
        Service(){attachInterface(null,"com.mitsubishielectric.ada.appservice.externaldisplay.IExternalDisplayApService");display[0]=true;display[3]=true;display[14]=true;}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            assertEquals(0,flags);data.enforceInterface("com.mitsubishielectric.ada.appservice.externaldisplay.IExternalDisplayApService");
            switch(code){
                case 3:callback=data.readStrongBinder();break;
                case 4:data.readStrongBinder();unregisters++;break;
                case 10:if(data.readInt()==1)on++;else off++;break;
                case 14:reply.writeNoException();reply.writeBooleanArray(display);return true;
                case 15:reply.writeNoException();reply.writeIntArray(protectedIds);return true;
                case 23:int id=data.readInt();reply.writeNoException();reply.writeInt(id==66?1:0);return true;
                case 16:lastRequest=data.readInt();reads++;break;
                case 17:assertEquals(1,data.readInt());preset=data.readInt();count=data.readInt();max=data.readInt();ids=data.createIntArray();writes++;break;
                default:return false;
            }
            reply.writeNoException();reply.writeInt(reject?0:1);return true;
        }
        void data(int result,int preset,int count,int max,int... ids)throws Exception{sendData(callback,result,preset,count,max,ids);}
        static void sendData(IBinder callback,int result,int preset,int count,int max,int... ids)throws Exception{
            Parcel p=Parcel.obtain();try{p.writeInterfaceToken("com.mitsubishielectric.ada.appservice.externaldisplay.IContentsCustomizeListener");
                p.writeInt(result);p.writeInt(1);p.writeInt(preset);p.writeInt(count);p.writeInt(max);p.writeIntArray(ids);
                callback.transact(1,p,null,IBinder.FLAG_ONEWAY);
            }finally{p.recycle();}
        }
        void changed(int result)throws Exception{Parcel p=Parcel.obtain();try{p.writeInterfaceToken("com.mitsubishielectric.ada.appservice.externaldisplay.IContentsCustomizeListener");p.writeInt(result);callback.transact(2,p,null,IBinder.FLAG_ONEWAY);}finally{p.recycle();}}
    }
    @Test public void exactWireTransactionsAndParcelable()throws Exception{
        Service s=new Service();MeterProtocol p=new MeterProtocol(s);
        MeterProtocol.Callback callback=new MeterProtocol.Callback(new MeterProtocol.Events(){public void data(int r,MeterContents d){}public void changed(int r){}});
        p.register(callback);assertSame(callback,s.callback);
        p.mode(true);p.read(5);p.change(MeterContents.outgoing(2,Arrays.asList(14,0,64)));p.mode(false);p.unregister(callback);
        assertEquals(5,s.lastRequest);assertEquals(2,s.preset);assertEquals(3,s.count);assertEquals(0,s.max);assertArrayEquals(new int[]{14,0,64},s.ids);
        assertEquals(1,s.on);assertEquals(1,s.off);assertEquals(1,s.unregisters);
        MeterContents.Capabilities caps=p.capabilities();assertEquals(new LinkedHashSet<>(Arrays.asList(0,3,14,64,65,66)),caps.available);assertTrue(caps.protectedIds.contains(0));
    }
    @Test public void callbacksDecodeGoldenWireAndNullablePayload()throws Exception{
        final MeterContents[] received={null};final int[] results={-1,-1};
        MeterProtocol.Callback c=new MeterProtocol.Callback(new MeterProtocol.Events(){public void data(int r,MeterContents d){results[0]=r;received[0]=d;}public void changed(int r){results[1]=r;}});
        Service s=new Service();new MeterProtocol(s).register(c);s.data(1,3,2,10,3,0,63);
        assertEquals(1,results[0]);assertEquals(3,received[0].preset);assertEquals(Arrays.asList(3,0),received[0].contents());assertTrue(received[0].validReply());s.changed(2);assertEquals(2,results[1]);
        Parcel p=Parcel.obtain();try{p.writeInterfaceToken(MeterProtocol.CALLBACK);p.writeInt(0);p.writeInt(0);c.transact(1,p,null,IBinder.FLAG_ONEWAY);assertNull(received[0]);assertEquals(0,results[0]);}finally{p.recycle();}
    }
    @Test public void incompatibleOrRejectedServiceFailsClosed()throws Exception{
        Binder wrong=new Binder();wrong.attachInterface(null,"wrong");try{new MeterProtocol(wrong);fail();}catch(RemoteException expected){}
        Service s=new Service();s.reject=true;try{new MeterProtocol(s).read(5);fail();}catch(RemoteException expected){}
    }
    @Test public void capabilityModelKeepsUnknownExistingButNeverAddsUnknown(){
        boolean[] display=new boolean[4];display[3]=true;
        MeterContents.Capabilities c=new MeterContents.Capabilities(display,new int[]{99},false,false);
        MeterContents baseline=new MeterContents(1,2,10,new int[]{99,3});
        assertTrue(c.permits(baseline,Arrays.asList(3,99,64)));assertFalse(c.permits(baseline,Arrays.asList(3,64)));
        assertFalse(c.permits(baseline,Arrays.asList(3,99,67)));assertFalse(c.permits(baseline,Arrays.asList(3,99,99)));
        assertFalse(new MeterContents(1,2,12,new int[]{0,3}).validReply());assertFalse(new MeterContents(1,3,10,new int[]{0,3}).validReply());
    }
    @Test public void defensiveCopiesProtectState(){int[] ids={0};MeterContents c=new MeterContents(1,1,10,ids);ids[0]=4;c.ids()[0]=5;assertEquals(Arrays.asList(0),c.contents());}
}
