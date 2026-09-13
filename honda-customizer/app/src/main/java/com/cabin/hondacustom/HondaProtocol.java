package com.cabin.hondacustom;

import android.os.*;
import java.util.List;

/** Wire contract independently implemented from the supplied Honda firmware. */
public final class HondaProtocol {
    public static final String PACKAGE="com.mitsubishielectric.ada.appservice.vehicleinfomanager";
    public static final String SERVICE=PACKAGE+".VehicleInfoManagerApService";
    public static final String DESCRIPTOR=PACKAGE+".IVehicleInfoManagerApService";
    public static final String CALLBACK=PACKAGE+".IVehicleVehicleCustomizeInformationListener";
    public interface Events { void result(int type,int result); void categories(int[] categories); void values(List<Setting> values); }
    public static final class Callback extends Binder {
        private final Events events;
        public Callback(Events events){this.events=events;attachInterface(null,CALLBACK);}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(CALLBACK);return true;}
            if(code<1||code>3)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(CALLBACK);
            if(code==1)events.result(data.readInt(),data.readInt());
            if(code==2)events.categories(data.createIntArray());
            if(code==3)events.values(data.createTypedArrayList(Setting.CREATOR));
            // The stock interface sends these callbacks one-way, without a reply header.
            return true;
        }
    }
    public static final String MAINTENANCE_CALLBACK=PACKAGE+".IVehicleMaintenanceInformationListener";
    public interface MaintenanceEvents {void status(int[] status);}
    public static final class MaintenanceCallback extends Binder {
        private final MaintenanceEvents events;
        public MaintenanceCallback(MaintenanceEvents events){this.events=events;attachInterface(null,MAINTENANCE_CALLBACK);}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(MAINTENANCE_CALLBACK);return true;}
            if(code!=1)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(MAINTENANCE_CALLBACK);events.status(data.createIntArray());return true;
        }
    }
    private final IBinder remote;
    public HondaProtocol(IBinder remote) throws RemoteException {
        if(!DESCRIPTOR.equals(remote.getInterfaceDescriptor()))throw new RemoteException("Unexpected Honda service interface");
        this.remote=remote;
    }
    private interface Writer {void write(Parcel p);}
    private int call(int code,Writer writer) throws RemoteException {
        Parcel data=Parcel.obtain(), reply=Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);if(writer!=null)writer.write(data);
            if(!remote.transact(code,data,reply,0))throw new RemoteException("Unsupported transaction: "+code);
            reply.readException();return reply.readInt();
        } finally {data.recycle();reply.recycle();}
    }
    public void register(IBinder listener) throws RemoteException {check(call(0x4d,p->p.writeStrongBinder(listener)));}
    public void unregister(IBinder listener) throws RemoteException {check(call(0x4e,p->p.writeStrongBinder(listener)));}
    public void mode(boolean active) throws RemoteException {check(call(0x4f,p->p.writeInt(active?1:0)));}
    // OEM IVehicleInfoManagerApService.Stub: reset=0x50, TPMS=0x54.
    // Both return request status immediately; completion arrives through result callbacks.
    public void registerMaintenance(IBinder listener)throws RemoteException{check(call(0x16,p->p.writeStrongBinder(listener)));}
    public void unregisterMaintenance(IBinder listener)throws RemoteException{check(call(0x17,p->p.writeStrongBinder(listener)));}
    public void maintenanceMode(boolean active)throws RemoteException{check(call(0x55,p->p.writeInt(active?1:0)));}
    public void resetMaintenance(int item)throws RemoteException{
        if(item < -1||item>11)throw new IllegalArgumentException("Unknown maintenance item");
        check(call(0x56,p->{p.writeInt(item==-1?0:1);p.writeInt(item==-1?0:item);}));
    }
    public void resetCustomization() throws RemoteException {check(call(0x50,null));}
    public void calibrateTpms() throws RemoteException {check(call(0x54,null));}
    public void discover() throws RemoteException {check(call(0x51,null));}
    public void read(int category) throws RemoteException {check(call(0x52,p->p.writeInt(category)));}
    public void change(Setting setting) throws RemoteException {check(call(0x53,p->{p.writeInt(1);setting.writeToParcel(p,0);}));}
    private void check(int result) throws RemoteException {if(result!=0)throw new RemoteException("Honda rejected request ("+result+")");}
}
