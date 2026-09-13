package com.cabin.hondacustom;
import android.os.*;
/** Narrow OEM System-menu tachometer preference contract; never a general setting writer. */
public final class UnitInfoProtocol {
 public static final String PACKAGE="com.mitsubishielectric.ada.appservice.unitinfomanager";
 public static final String SERVICE=PACKAGE+".UnitInformationManagerApService";
 public static final String DESCRIPTOR=PACKAGE+".IUnitInformationManagerApService";
 private final IBinder remote;
 public UnitInfoProtocol(IBinder remote)throws RemoteException{if(!DESCRIPTOR.equals(remote.getInterfaceDescriptor()))throw new RemoteException("Unexpected UnitInfo interface");this.remote=remote;}
 private void payload(Parcel p,Integer value){p.writeInt(1);p.writeInt(0x21);p.writeValue(value);p.writeList(null);p.writeIntArray(null);}
 public void setTachometer(int vehicleValue)throws RemoteException{
  if(vehicleValue!=1&&vehicleValue!=2)throw new IllegalArgumentException("Unknown tachometer value");
  Parcel p=Parcel.obtain(),r=Parcel.obtain();try{p.writeInterfaceToken(DESCRIPTOR);payload(p,vehicleValue==1?1:0);
   if(!remote.transact(0x1d,p,r,0))throw new RemoteException("Tachometer preference unsupported");r.readException();int status=r.readInt();if(status!=0)throw new RemoteException("Tachometer preference rejected ("+status+")");
  }finally{p.recycle();r.recycle();}
 }
 public int getTachometer()throws RemoteException{
  Parcel p=Parcel.obtain(),r=Parcel.obtain();try{p.writeInterfaceToken(DESCRIPTOR);payload(p,null);
   if(!remote.transact(0x1e,p,r,0))throw new RemoteException("Tachometer preference read unsupported");r.readException();int status=r.readInt();
   if(status!=0||r.readInt()!=1||r.readInt()!=0x21)throw new RemoteException("Invalid tachometer preference response");
   Object value=r.readValue(Integer.class.getClassLoader());r.readArrayList(null);r.createIntArray();
   if(!(value instanceof Integer)||((Integer)value!=0&&(Integer)value!=1))throw new RemoteException("Unknown tachometer preference");
   return (Integer)value==1?1:2;
  }finally{p.recycle();r.recycle();}
 }
}
