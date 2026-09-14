package com.cabin.hondacustom;

import android.os.*;
import java.util.*;

/** Verified Wc_16Civic_Pannel contract; see documents/research/HONDA-FYT-PANEL.md. */
final class FytProtocol {
    static final String TOOLKIT="com.syu.ipc.IRemoteToolkit", MODULE="com.syu.ipc.IRemoteModule", CALLBACK="com.syu.ipc.IModuleCallback";
    static final int PROFILE=1000;
    static final class Control {
        final String title; final int field,command,key,base; final boolean lowByte; final String[] options;
        Control(String title,int field,int key,int base,String... options){this(title,field,106,key,true,base,options);}
        Control(String title,int field,int command,int key,boolean lowByte,int base,String... options){this.title=title;this.field=field;this.command=command;this.key=key;this.lowByte=lowByte;this.base=base;this.options=options;}
        Integer decode(int raw){if(raw<0||raw>65535)return null;int v=(lowByte?(raw&255):raw)-base;return v>=0&&v<options.length?v:null;}
    }
    static final List<Control> CONTROLS=Collections.unmodifiableList(Arrays.asList(
        new Control("Navigation directions",109,16,0,"Off","On"),
        new Control("Warning messages",110,15,0,"Off","On"),
        new Control("Panel configuration",111,14,0,"Type 1","Type 2","Type 3"),
        new Control("Reverse tone",88,9,0,"Off","On"),
        new Control("Speed reminders",65,6,0,"Off","On"),
        new Control("Message reminders",66,7,0,"Off","On"),
        new Control("Idle-stop reminders",67,8,0,"Off","On"),
        new Control("Eco background lighting",68,5,0,"Off","On"),
        new Control("Traffic sign display",102,13,0,"Off","On"),
        new Control("Alarm volume",69,4,1,"High","Medium","Low"),
        new Control("Trip B reset condition",70,3,1,"Refuel","Ignition off","Manual"),
        new Control("Trip A reset condition",71,2,1,"Refuel","Ignition off","Manual"),
        new Control("Outside-temperature adjustment",72,1,1,"−3","−2","−1","0","+1","+2","+3"),
        // Acrivity_RZC_17CRVSettings / AcrivitySiYuSettings; these are a separate decoder dialect.
        new Control("Speed / distance units",77,105,21,false,0,"km/h · km","mph · miles"),
        new Control("Tachometer display",78,105,22,false,0,"Off","On"),
        new Control("Tachometer setting (vendor option)",87,105,35,false,0,"Off","On")
    ));
    static boolean wc(int p){return p==0x40141||p==0x50141||p==0x60141||p==0xB0141||p==0xC0141||p==0xD0141;}
    static boolean rzc(int p){return p==0x10012a||p==0x11012a||p==0x29012a;}
    static boolean bnr(int p){return p==0x6012a||p==0x7012a||p==0x8012a||p==0x9012a||p==0xa012a||p==0xb012a||p==0xf012a||p==0x28012a;}
    static boolean supported(int p){return wc(p)||rzc(p)||bnr(p);}
    static String family(int p){return wc(p)?"WC":rzc(p)?"RZC":bnr(p)?"BNR":"Unmapped";}
    static boolean visible(int p,Control c){
        if(!supported(p)||!CONTROLS.contains(c))return false;
        if(c.command==105)return rzc(p)||(bnr(p)&&c.field!=77);
        if(!wc(p))return false;
        if(c.key>=14)return p==0x40141||p==0xC0141||p==0xD0141;
        if(c.key==9)return p==0x50141||p==0x60141||p==0xB0141;
        if(c.key==6||c.key==7)return p!=0x50141&&p!=0x60141;
        if(c.key==13)return p==0xB0141;
        return true;
    }
    interface Writer {void write(Parcel p);}
    interface Reader<T> {T read(Parcel p);}
    static <T>T exchange(IBinder binder,String descriptor,int code,Writer writer,Reader<T> reader)throws RemoteException{
        return exchange(binder,descriptor,code,writer,reader,()->{});
    }
    private static <T>T exchange(IBinder binder,String descriptor,int code,Writer writer,Reader<T> reader,Runnable beforeSend)throws RemoteException{
        if(!descriptor.equals(binder.getInterfaceDescriptor()))throw new RemoteException("Unexpected FYT interface");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(descriptor);writer.write(data);
            beforeSend.run();
            if(!binder.transact(code,data,reply,0)||reply.dataSize()>65536||reply.dataAvail()<4)throw new RemoteException("Invalid FYT reply");
            reply.readException();return reader.read(reply);
        }finally{data.recycle();reply.recycle();}
    }
    static IBinder module(IBinder toolkit)throws RemoteException{return exchange(toolkit,TOOLKIT,1,p->p.writeInt(7),Parcel::readStrongBinder);}
    static void register(IBinder module,IBinder callback,int field,boolean add)throws RemoteException{
        exchange(module,MODULE,add?3:4,p->{p.writeStrongBinder(callback);p.writeInt(field);if(add)p.writeInt(1);},p->null);
    }
    static void change(IBinder module,int profile,Control c,int value)throws RemoteException{
        change(module,profile,c,value,()->{});
    }
    static void change(IBinder module,int profile,Control c,int value,Runnable beforeSend)throws RemoteException{
        if(!visible(profile,c)||value<0||value>=c.options.length)throw new IllegalArgumentException("Unsupported FYT setting");
        exchange(module,MODULE,1,p->{p.writeInt(c.command);p.writeIntArray(new int[]{c.key,value+c.base});p.writeFloatArray(null);p.writeStringArray(null);},p->null,beforeSend);
    }
}
