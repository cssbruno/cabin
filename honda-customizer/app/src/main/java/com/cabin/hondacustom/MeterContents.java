package com.cabin.hondacustom;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.*;

/** OEM CustomizeData wire object. Runtime max is decoded as 10 or 15 by the OEM service. */
public final class MeterContents implements Parcelable {
    public final int preset, count, max;
    private final int[] ids;
    public MeterContents(int preset, int count, int max, int[] ids) {
        this.preset=preset; this.count=count; this.max=max; this.ids=ids==null?null:ids.clone();
    }
    public int[] ids(){return ids==null?null:ids.clone();}
    public List<Integer> contents(){
        if(ids==null||count<0||count>ids.length)return Collections.emptyList();
        List<Integer> out=new ArrayList<>();for(int i=0;i<count;i++)out.add(ids[i]);
        return Collections.unmodifiableList(out);
    }
    /** Unknown layouts remain unavailable instead of substituting an assumed maximum. */
    public boolean validReply(){
        if(preset<0||preset>3||(max!=10&&max!=15)||count<0||count>max||ids==null||count>ids.length)return false;
        Set<Integer> seen=new HashSet<>();
        for(int i=0;i<count;i++)if(ids[i]<0||ids[i]>255||!seen.add(ids[i]))return false;
        return true;
    }
    public static MeterContents outgoing(int preset,List<Integer> order){
        int[] ids=new int[order.size()];for(int i=0;i<ids.length;i++)ids[i]=order.get(i);
        // OEM CustomizeManager.createCustomizeData leaves max at its default zero.
        return new MeterContents(preset,ids.length,0,ids);
    }
    public boolean matches(MeterContents other){return other!=null&&preset==other.preset&&contents().equals(other.contents());}
    @Override public int describeContents(){return 0;}
    @Override public void writeToParcel(Parcel out,int flags){out.writeInt(preset);out.writeInt(count);out.writeInt(max);out.writeIntArray(ids);}
    public static final Creator<MeterContents> CREATOR=new Creator<MeterContents>(){
        public MeterContents createFromParcel(Parcel in){return new MeterContents(in.readInt(),in.readInt(),in.readInt(),in.createIntArray());}
        public MeterContents[] newArray(int size){return new MeterContents[size];}
    };

    // Verified CustomizeConst.ContentsId.CUSTOMIZE_METER; availability is always discovered live.
    private static final int[] METER_IDS={0,1,2,3,4,5,6,7,8,9,10,14,17,24,25,26,31,32,33,34,35};
    public static final class Capabilities {
        public final Set<Integer> available, protectedIds;
        public Capabilities(boolean[] display,int[] protectedIds,boolean content66,boolean content67){
            if(display==null||protectedIds==null)throw new IllegalArgumentException("Missing Honda content capabilities");
            LinkedHashSet<Integer> available=new LinkedHashSet<>();
            for(int id:METER_IDS)if(id<display.length&&display[id])available.add(id);
            // The OEM editor always offers DA 64/65; 66/67 additionally require isEnableContents==1.
            available.add(64);available.add(65);if(content66)available.add(66);if(content67)available.add(67);
            this.available=Collections.unmodifiableSet(available);
            Set<Integer> keep=new HashSet<>();for(int id:protectedIds)if(id>=0&&id<=255)keep.add(id);
            this.protectedIds=Collections.unmodifiableSet(keep);
        }
        public boolean permits(MeterContents baseline,List<Integer> order){
            if(baseline==null||!baseline.validReply()||baseline.preset<1||order==null||order.size()>baseline.max)return false;
            Set<Integer> seen=new HashSet<>();List<Integer> old=baseline.contents();
            for(Integer id:order)if(id==null||!seen.add(id)||(!old.contains(id)&&!available.contains(id)))return false;
            for(Integer id:old)if(protectedIds.contains(id)&&!seen.contains(id))return false;
            return true;
        }
    }
    /** Resource-backed names; unknown items deliberately retain their original numeric ID. */
    public static String label(int id){
        switch(id){
            case 0:return "Fuel";case 1:return "Average fuel";case 2:return "Speed alarm";case 3:return "Tachometer";
            case 4:return "TSR";case 5:return "AFP";case 6:return "Seatbelt reminder";
            case 7:return "Maintenance";case 8:return "Blank 1";case 9:return "Blank 2";case 10:return "Customization";
            case 14:return "Average speed";case 17:return "Energy flow";case 24:return "G-meter";case 25:return "Boost";
            case 26:return "Stopwatch";case 31:return "Maintenance 2";case 32:return "REV indicator";
            case 33:return "Throttle / brake";case 34:return "BEV trip computer";case 35:return "BEV range";
            case 64:return "Audio";case 65:return "Phone";case 66:return "Mail";case 67:return "Turn-by-turn directions";
            default:return "Display item "+id;
        }
    }
}
