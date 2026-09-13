package com.cabin.hondacustom;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;

public final class Catalog {
    public static final class Entry {
        public final int category,id,type;
        public final String title,description;
        public final Map<Integer,String> options=new TreeMap<>();
        public final Set<Integer> writableOptions=new TreeSet<>();
        public final boolean routed;
        Entry(JSONObject row, boolean pt) throws JSONException {
            category=row.getInt("category");id=row.getInt("id");type=row.getInt("type");routed=row.getBoolean("hasRoute");
            description=row.optString("description"); JSONObject labels=row.optJSONObject("labels");
            JSONObject text=labels==null?null:labels.optJSONObject(pt?"pt":"(default)");
            if(text==null&&labels!=null)text=labels.optJSONObject("(default)");
            JSONObject fallback=labels==null?null:labels.optJSONObject("(default)");
            String primary=text==null?"":text.optString("title","");
            if(primary.trim().isEmpty()&&fallback!=null)primary=fallback.optString("title","");
            title=primary.trim().isEmpty()?String.format(Locale.US,"Setting %02X/%02X",category,id):primary;
            JSONArray names=text==null?null:text.optJSONArray("options");
            JSONArray fallbackNames=fallback==null?null:fallback.optJSONArray("options");
            JSONObject values=row.getJSONObject("values");
            Map<Integer,Integer> encodingCounts=new HashMap<>();
            Iterator<String> encodedKeys=values.keys();while(encodedKeys.hasNext()){
                int encoded=values.getInt(encodedKeys.next());
                Integer n=encodingCounts.get(encoded);encodingCounts.put(encoded,n==null?1:n+1);
            }
            Iterator<String> keys=values.keys();while(keys.hasNext()){
                int key=Integer.parseInt(keys.next());String label="";
                if(type==0&&names!=null&&key>0&&key<=names.length()&&!names.isNull(key-1))label=names.optString(key-1,"");
                if(label.trim().isEmpty()&&type==0&&fallbackNames!=null&&key>0&&key<=fallbackNames.length()&&!fallbackNames.isNull(key-1))label=fallbackNames.optString(key-1,"");
                int encoded=values.getInt(Integer.toString(key));
                if(!label.trim().isEmpty()&&encoded!=255&&encodingCounts.get(encoded)==1)writableOptions.add(key);
                if(label.trim().isEmpty())label="Unknown value ("+key+")";
                options.put(key,label);
            }
        }
        public String key(){return category+":"+id;}
        public String label(int value){String label=options.get(value);return label==null?Integer.toString(value):label;}
        public boolean editable(Setting live){
            if(live==null||category==9||live.type!=type||!live.bounded(live.value))return false;
            if(type==0){for(int value:writableOptions)if(live.bounded(value))return true;return false;}
            return type==1;
        }
        public boolean allows(Setting live,int value){return editable(live)&&live.bounded(value)&&(type!=0||writableOptions.contains(value));}
    }
    public final List<Entry> entries=new ArrayList<>();
    public final Map<String,Entry> byKey=new HashMap<>();
    public Catalog(Context context) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=context.getAssets().open("catalog.json")){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);}
        JSONArray array=new JSONArray(bytes.toString("UTF-8"));boolean pt=Locale.getDefault().getLanguage().equals("pt");
        for(int i=0;i<array.length();i++){Entry e=new Entry(array.getJSONObject(i),pt);entries.add(e);byKey.put(e.key(),e);}
    }
    public static String category(int value){
        switch(value){case 1:return "Calibration";case 2:return "Driver assistance";case 3:return "Instrument cluster";case 4:return "Driving position";case 5:return "Keyless access";case 6:return "Lighting";case 7:return "Doors & windows";case 8:return "Wipers";case 9:return "Maintenance";case 10:return "Tailgate";case 11:return "Driving modes";default:return "Category "+value;}
    }
}
