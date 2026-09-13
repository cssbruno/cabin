package com.cabin.hondacustom;

import android.os.Parcel;
import android.os.Parcelable;

public final class Setting implements Parcelable {
    public final int category, id, value, type;
    public final int[] range;
    public final boolean[] fob;
    public Setting(int category, int id, int value, int type, int[] range, boolean[] fob) {
        this.category=category; this.id=id; this.value=value; this.type=type;
        this.range=range==null?null:range.clone(); this.fob=fob==null?null:fob.clone();
    }
    public String key() { return category+":"+id; }
    public Setting withValue(int v) { return new Setting(category,id,v,type,range,fob); }
    public boolean bounded(int v) { return range!=null && range.length==2 && range[0]<=range[1] && v>=range[0] && v<=range[1]; }
    @Override public void writeToParcel(Parcel p,int flags) {
        p.writeInt(category);p.writeInt(id);p.writeInt(value);p.writeInt(type);p.writeIntArray(range);p.writeBooleanArray(fob);
    }
    @Override public int describeContents(){return 0;}
    public static final Creator<Setting> CREATOR=new Creator<Setting>() {
        public Setting createFromParcel(Parcel p){return new Setting(p.readInt(),p.readInt(),p.readInt(),p.readInt(),p.createIntArray(),p.createBooleanArray());}
        public Setting[] newArray(int size){return new Setting[size];}
    };
}
