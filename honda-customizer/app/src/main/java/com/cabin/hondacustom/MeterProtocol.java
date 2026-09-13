package com.cabin.hondacustom;

import android.os.*;

/** Exact ExternalDisplayLib Binder ABI, independent of the OEM shared Java library. */
public final class MeterProtocol {
    public static final String PACKAGE="com.mitsubishielectric.ada.appservice.externaldisplay";
    public static final String SERVICE=PACKAGE+".ExternalDisplayApService";
    public static final String DESCRIPTOR=PACKAGE+".IExternalDisplayApService";
    public static final String CALLBACK=PACKAGE+".IContentsCustomizeListener";
    public static final int REPLY_OK=1, REQUEST_DEFAULT=4, REQUEST_CURRENT=5;
    public interface Events {void data(int result,MeterContents contents);void changed(int result);}
    public static final class Callback extends Binder {
        private final Events events;
        public Callback(Events events){this.events=events;attachInterface(null,CALLBACK);}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(CALLBACK);return true;}
            if(code!=1&&code!=2)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(CALLBACK);
            int result=data.readInt();
            if(code==1)events.data(result,data.readInt()==0?null:MeterContents.CREATOR.createFromParcel(data));
            else events.changed(result);
            return true; // OEM callbacks are one-way; no synchronous exception header.
        }
    }
    private final IBinder remote;
    public MeterProtocol(IBinder remote)throws RemoteException{
        if(remote==null||!DESCRIPTOR.equals(remote.getInterfaceDescriptor()))throw new RemoteException("Incompatible Honda meter service");
        this.remote=remote;
    }
    private interface Writer{void write(Parcel data);}
    private interface Reader<T>{T read(Parcel reply);}
    private <T>T call(int code,Writer writer,Reader<T> reader)throws RemoteException{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(DESCRIPTOR);if(writer!=null)writer.write(data);
            if(!remote.transact(code,data,reply,0))throw new RemoteException("Honda meter transaction unavailable: "+code);
            reply.readException();return reader.read(reply);
        }finally{data.recycle();reply.recycle();}
    }
    private void accepted(int code,Writer writer)throws RemoteException{
        if(!call(code,writer,p->p.readInt()!=0))throw new RemoteException("Honda meter rejected request: "+code);
    }
    public void register(Callback callback)throws RemoteException{accepted(3,p->p.writeStrongBinder(callback));}
    public void unregister(Callback callback)throws RemoteException{accepted(4,p->p.writeStrongBinder(callback));}
    public void mode(boolean enabled)throws RemoteException{accepted(10,p->p.writeInt(enabled?1:0));}
    public boolean[] displayStatus()throws RemoteException{return call(14,null,Parcel::createBooleanArray);}
    public int[] protectedIds()throws RemoteException{return call(15,null,Parcel::createIntArray);}
    public boolean enabled(int id)throws RemoteException{return call(23,p->p.writeInt(id),p->p.readInt())==1;}
    public MeterContents.Capabilities capabilities()throws RemoteException{
        return new MeterContents.Capabilities(displayStatus(),protectedIds(),enabled(66),enabled(67));
    }
    public void read(int request)throws RemoteException{
        if(request<1||request>5)throw new IllegalArgumentException("Unknown meter preset request");
        accepted(16,p->p.writeInt(request));
    }
    public void change(MeterContents contents)throws RemoteException{
        if(contents==null||contents.preset<1||contents.preset>3||contents.count<0||contents.count>15
                ||contents.ids()==null||contents.ids().length!=contents.count)throw new IllegalArgumentException("Invalid writable meter preset");
        accepted(17,p->{p.writeInt(1);contents.writeToParcel(p,0);});
    }
}
