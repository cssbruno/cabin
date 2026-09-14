package com.cabin.hondacustom;

import android.os.*;

/** Exact OEM SettingData in/out Parcel contract; separate from tachometer and vehicle writes. */
public final class HeadUnitSettingsProtocol {
    public static final String PACKAGE="com.mitsubishielectric.ada.appservice.unitinfomanager";
    public static final String SERVICE=PACKAGE+".UnitInformationManagerApService";
    public static final String DESCRIPTOR=PACKAGE+".IUnitInformationManagerApService";
    public static final class Unavailable extends Exception {Unavailable(String message){super(message);}}
    private final IBinder remote;
    public HeadUnitSettingsProtocol(IBinder binder)throws RemoteException{
        if(!DESCRIPTOR.equals(binder.getInterfaceDescriptor()))throw new RemoteException("Unexpected Honda head-unit service");
        remote=binder;
    }
    private static void payload(Parcel data,int type,Integer value){
        data.writeInt(1);data.writeInt(type);data.writeValue(value);data.writeList(null);data.writeIntArray(null);
    }
    private int read(int transaction,int type)throws RemoteException,Unavailable{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken(DESCRIPTOR);payload(data,type,null);
            if(!remote.transact(transaction,data,reply,0))throw new RemoteException("Unsupported Honda read transaction");
            reply.readException();int result=reply.readInt();
            if(result!=0)throw new Unavailable("Honda does not provide this setting ("+result+")");
            if(reply.readInt()!=1||reply.readInt()!=type)throw new Unavailable("Unexpected Honda setting response");
            Object value=reply.readValue(Integer.class.getClassLoader());
            java.util.ArrayList<?> list=reply.readArrayList(null);int[] shortcuts=reply.createIntArray();
            if(!(value instanceof Integer)||list!=null||shortcuts!=null)throw new Unavailable("Unsupported Honda setting format");
            return (Integer)value;
        }finally{data.recycle();reply.recycle();}
    }
    public int destination()throws RemoteException,Unavailable{
        int value=read(0x21,4);
        if(value<0||value>35)throw new Unavailable("Unknown Honda destination");
        return value;
    }
    public int read(HeadUnitSettings.Entry entry)throws RemoteException,Unavailable{
        if(!HeadUnitSettings.trusted(entry))throw new IllegalArgumentException("Unverified head-unit setting");
        return read(0x1e,entry.type);
    }
    public void write(HeadUnitSettings.Entry entry,int displayValue,Integer destination)throws RemoteException{
        if(!HeadUnitSettings.trusted(entry)||!entry.options(destination).containsKey(displayValue))throw new IllegalArgumentException("Unverified head-unit value");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken(DESCRIPTOR);payload(data,entry.type,entry.convert(displayValue,destination));
            if(!remote.transact(0x1d,data,reply,0))throw new RemoteException("Unsupported Honda write transaction");
            reply.readException();int result=reply.readInt();
            if(result!=0)throw new RemoteException("Honda rejected head-unit setting ("+result+")");
        }finally{data.recycle();reply.recycle();}
    }
}
