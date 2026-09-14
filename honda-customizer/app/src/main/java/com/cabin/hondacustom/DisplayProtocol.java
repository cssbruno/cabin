package com.cabin.hondacustom;

import android.os.*;

/** Original AV display parameter ABI: eight ordered ints, current source type 0 only. */
public final class DisplayProtocol {
    public static final String PACKAGE="com.mitsubishielectric.ada.appservice.avapservice";
    public static final String SERVICE=PACKAGE+".AvApService", DESCRIPTOR=PACKAGE+".IAvApService";
    public enum Field {
        BRIGHTNESS("Brightness",0,10), CONTRAST("Contrast",-5,5), BLACK_LEVEL("Black level",-5,5);
        public final String label;public final int min,max;
        Field(String label,int min,int max){this.label=label;this.min=min;this.max=max;}
        public boolean allows(int value){return value>=min&&value<=max;}
    }
    public static final class Parameters {
        public final int mode,type,brightness,contrast,blackLevel,tint,density,illStep;
        public Parameters(int mode,int type,int brightness,int contrast,int blackLevel,int tint,int density,int illStep){
            this.mode=mode;this.type=type;this.brightness=brightness;this.contrast=contrast;this.blackLevel=blackLevel;this.tint=tint;this.density=density;this.illStep=illStep;
        }
        public int value(Field field){if(field==Field.BRIGHTNESS)return brightness;if(field==Field.CONTRAST)return contrast;return blackLevel;}
        public boolean writable(){return (mode==1||mode==2)&&type==0&&Field.BRIGHTNESS.allows(brightness)&&Field.CONTRAST.allows(contrast)&&Field.BLACK_LEVEL.allows(blackLevel);}
        public Parameters with(Field field,int value){
            if(field==null||!field.allows(value)||!writable())throw new IllegalArgumentException("Unsupported display value or mode");
            return new Parameters(mode,type,field==Field.BRIGHTNESS?value:brightness,field==Field.CONTRAST?value:contrast,field==Field.BLACK_LEVEL?value:blackLevel,tint,density,illStep);
        }
        void write(Parcel parcel){parcel.writeInt(mode);parcel.writeInt(type);parcel.writeInt(brightness);parcel.writeInt(contrast);parcel.writeInt(blackLevel);parcel.writeInt(tint);parcel.writeInt(density);parcel.writeInt(illStep);}
        public boolean same(Parameters other){return other!=null&&mode==other.mode&&type==other.type&&brightness==other.brightness&&contrast==other.contrast&&blackLevel==other.blackLevel&&tint==other.tint&&density==other.density&&illStep==other.illStep;}
        public String modeLabel(){return mode==1?"Day":mode==2?"Night":mode==0?"Display off":"On / unsupported for adjustment";}
    }
    private final IBinder remote;
    public DisplayProtocol(IBinder remote)throws RemoteException{
        if(remote==null||!DESCRIPTOR.equals(remote.getInterfaceDescriptor()))throw new RemoteException("Incompatible Honda AV service");this.remote=remote;
    }
    public Parameters read()throws RemoteException{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
            data.writeInterfaceToken(DESCRIPTOR);data.writeInt(0);
            if(!remote.transact(0x2c,data,reply,0))throw new RemoteException("Display parameter read is unavailable");
            reply.readException();if(reply.readInt()!=1)throw new RemoteException("Honda returned no display parameters");
            if(reply.dataAvail()!=32)throw new RemoteException("Unsupported display parameter wire format");
            Parameters value=new Parameters(reply.readInt(),reply.readInt(),reply.readInt(),reply.readInt(),reply.readInt(),reply.readInt(),reply.readInt(),reply.readInt());
            if(value.type!=0||value.mode<0||value.mode>3)throw new RemoteException("Unsupported Honda display type or mode");
            return value;
        }finally{data.recycle();reply.recycle();}
    }
    public void write(Parameters parameters)throws RemoteException{
        if(parameters==null||!parameters.writable())throw new IllegalArgumentException("Display settings are not writable in this mode");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
            data.writeInterfaceToken(DESCRIPTOR);data.writeInt(0);data.writeInt(1);parameters.write(data);
            if(!remote.transact(0x2d,data,reply,0))throw new RemoteException("Display parameter write is unavailable");
            reply.readException();if(reply.readInt()!=1)throw new RemoteException("Honda rejected the display change");
        }finally{data.recycle();reply.recycle();}
    }
}
