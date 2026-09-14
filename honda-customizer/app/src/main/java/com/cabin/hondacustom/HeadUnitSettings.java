package com.cabin.hondacustom;

import java.util.*;

/** Allowlist from OEM DaSettings wrappers and UnitInfoManagerConst, not a general preference editor. */
public final class HeadUnitSettings {
    private HeadUnitSettings() {}
    public static final class Entry {
        public final int type;
        public final String title;
        private final Map<Integer,String> labels;
        private Entry(int type,String title,Object... pairs) {
            this.type=type;this.title=title;Map<Integer,String> choices=new LinkedHashMap<>();
            for(int i=0;i<pairs.length;i+=2)choices.put((Integer)pairs[i],(String)pairs[i+1]);
            labels=Collections.unmodifiableMap(choices);
        }
        public boolean needsDestination(){return type==0x4a||type==2||type==3;}
        public boolean known(int value){return labels.containsKey(value);}
        public String label(int value){String label=labels.get(value);return label==null?"Unknown Honda value ("+value+")":label;}
        public Map<Integer,String> options(Integer destination){
            Map<Integer,String> result=new LinkedHashMap<>(labels);
            if(needsDestination()&&(destination==null||destination<0||destination>35))return Collections.emptyMap();
            if(type==2&&(destination==1||destination==4))result.remove(3); // No analog clock in KJ/KH.
            if(type==3){
                result.remove(4); // Imported wallpaper needs the OEM file/import workflow.
                if(destination!=0&&destination!=5&&destination!=6&&destination!=7)result.remove(3);
            }
            return result;
        }
        public int convert(int value,Integer destination){
            if(type==0x4a){
                if(destination==null)throw new IllegalArgumentException("Honda destination is unavailable");
                return destination==1&&known(value)?1-value:value; // KJ UI/raw inversion is symmetric.
            }
            return value;
        }
    }
    public static final List<Entry> ENTRIES=Collections.unmodifiableList(Arrays.asList(
        new Entry(0x4a,"Clock format",0,"12-hour",1,"24-hour"),
        new Entry(0x15,"Clock display",0,"Off",1,"On"),
        new Entry(2,"Clock style",0,"Off",1,"Small digital",2,"Digital",3,"Analog"),
        new Entry(3,"Clock background",0,"Blank",1,"Galaxy",2,"Metallic",3,"Time zone",4,"Imported wallpaper"),
        new Entry(0x4b,"Climate popup duration",0,"Never",1,"5 seconds",2,"10 seconds",3,"20 seconds"),
        new Entry(0x20,"Touch-panel sensitivity",0,"Low",2,"High"),
        new Entry(0x17,"Voice command tips",0,"Off",1,"On"),
        new Entry(0x40,"Voice prompts",0,"Off",1,"On"),
        new Entry(0x4e,"Swipe direction",0,"Normal",1,"Reverse"),
        new Entry(0x6e,"Four-way switch gestures",0,"Off",1,"On"),
        new Entry(0x6f,"Volume gestures",0,"Off",1,"On")
    ));
    public static Entry find(int type){for(Entry entry:ENTRIES)if(entry.type==type)return entry;return null;}
    public static boolean trusted(Entry entry){return entry!=null&&find(entry.type)==entry;}
}
