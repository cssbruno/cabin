package com.cabin.hondacustom;

import android.os.*;

/** Original DiagService ABI. Request identifiers are assigned by Honda, not request kinds. */
public final class DiagnosticProtocol {
    public static final String PACKAGE="com.mitsubishielectric.ada.appservice.diag";
    public static final String SERVICE=PACKAGE+".DiagService", DESCRIPTOR=PACKAGE+".IDiagService";
    // The misspelling Listner is part of the OEM interface descriptor.
    public static final String CALLBACK=PACKAGE+".IDiagServiceListner";
    public static final String PERMISSION="com.mitsubishielectric.ada.permission.diag.ACCESS_DIAG_SERVICE";
    public static final int HARDWARE_READ=0x0a0201,HARDWARE_CLEAR=0x0a0901,TELEMATICS_READ=0x2a0201;
    public interface Events {void response(int requestId,Bundle data);void cancelled(int requestId);void malformed();}
    public static final class Callback extends Binder {
        private final Events events;
        public Callback(Events events){this.events=events;attachInterface(null,CALLBACK);}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(CALLBACK);return true;}
            if(code<1||code>7)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(CALLBACK);
            try{
                if(code==1){int id=data.readInt();Bundle b=data.readInt()==0?null:Bundle.CREATOR.createFromParcel(data);
                    if(b!=null){b.setClassLoader(Bundle.class.getClassLoader());b.size();}events.response(id,b);}
                else if(code==7)events.cancelled(data.readInt());
                // Other OEM callbacks are unrelated messages or LET/PSI factory input, never acknowledgements.
            }catch(RuntimeException malformed){events.malformed();}
            return true;
        }
    }
    private final IBinder remote;
    public DiagnosticProtocol(IBinder remote)throws RemoteException{
        if(remote==null||!DESCRIPTOR.equals(remote.getInterfaceDescriptor()))throw new RemoteException("Incompatible Honda diagnostic service");this.remote=remote;
    }
    private interface Writer{void write(Parcel data);}
    private int call(int code,Writer writer,boolean result)throws RemoteException{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
            data.writeInterfaceToken(DESCRIPTOR);if(writer!=null)writer.write(data);
            if(!remote.transact(code,data,reply,0))throw new RemoteException("Honda diagnostic transaction unavailable: "+code);
            reply.readException();return result?reply.readInt():0;
        }finally{data.recycle();reply.recycle();}
    }
    public void initialize(Callback callback)throws RemoteException{
        if(call(1,p->{p.writeStrongBinder(callback);p.writeInt(2);},true)==0)throw new RemoteException("Honda rejected diagnostic initialization");
    }
    public void terminate()throws RemoteException{if(call(2,null,true)==0)throw new RemoteException("Honda diagnostic termination failed");}
    private int request(int kind)throws RemoteException{
        int id=call(3,p->{p.writeInt(kind);p.writeInt(0);},true);
        if(id<0)throw new RemoteException("Honda rejected diagnostic request "+Integer.toHexString(kind));return id;
    }
    public int readHardware()throws RemoteException{return request(HARDWARE_READ);}
    public int readTelematics()throws RemoteException{return request(TELEMATICS_READ);}
    /** The caller must show a specific destructive confirmation before passing true. */
    public int clearHardware(boolean confirmed)throws RemoteException{
        if(!confirmed)throw new IllegalArgumentException("Hardware history deletion requires explicit confirmation");return request(HARDWARE_CLEAR);
    }
    public void cancel(int id)throws RemoteException{if(id>=0)call(4,p->p.writeIntArray(new int[]{id}),false);}
}
