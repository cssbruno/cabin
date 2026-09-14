package com.cabin.hondacustom;

import android.os.*;
import java.util.*;

/** Decoders only for response schemas established by the OEM request tasks. */
public final class DiagnosticData {
    private DiagnosticData(){}
    public static final class HardwareError {
        public final int code;
        public final String time,info,extra;
        HardwareError(int code,String time,String info,String extra){this.code=code;this.time=time;this.info=info;this.extra=extra;}
        public String codeText(){return String.format(Locale.US,"0x%04X",code);}
        public String report(){return codeText()+" | "+time+" | "+info+" | "+extra;}
    }
    static int integer(Bundle data,String key){Object value=data==null?null:data.get(key);if(!(value instanceof Integer))throw new IllegalArgumentException("Missing or invalid diagnostic "+key);return (Integer)value;}
    static boolean bool(Bundle data,String key){Object value=data==null?null:data.get(key);if(!(value instanceof Boolean))throw new IllegalArgumentException("Missing or invalid diagnostic "+key);return (Boolean)value;}
    static String optionalText(Bundle data,String key){Object value=data.get(key);if(value==null)return "";if(!(value instanceof String))throw new IllegalArgumentException("Invalid diagnostic "+key);return (String)value;}
    static void success(Bundle data){int error=integer(data,"error");if(error!=0)throw new IllegalArgumentException("Honda diagnostic error "+error);}
    private static Parcelable[] array(Bundle data,String key){if(!data.containsKey(key))return new Parcelable[0];Object value=data.get(key);if(!(value instanceof Parcelable[]))throw new IllegalArgumentException("Missing or invalid diagnostic "+key);return (Parcelable[])value;}
    public static List<HardwareError> hardware(Bundle data){
        success(data);List<HardwareError> rows=new ArrayList<>();
        for(Parcelable raw:array(data,"data")){
            if(!(raw instanceof Bundle))throw new IllegalArgumentException("Invalid hardware history row");Bundle row=(Bundle)raw;
            rows.add(new HardwareError(integer(row,"ecode"),optionalText(row,"time"),optionalText(row,"info"),optionalText(row,"extra_info")));
        }
        return Collections.unmodifiableList(rows);
    }
    public static List<List<String>> telematics(Bundle data){
        success(data);List<List<String>> rows=new ArrayList<>();
        for(Parcelable raw:array(data,"rows")){
            if(!(raw instanceof Bundle))throw new IllegalArgumentException("Invalid telematics row");Object columns=((Bundle)raw).get("columns");
            if(!(columns instanceof String[]))throw new IllegalArgumentException("Invalid telematics columns");
            List<String> values=new ArrayList<>();for(String value:(String[])columns)values.add(value==null?"":value);
            rows.add(Collections.unmodifiableList(values));
        }
        return Collections.unmodifiableList(rows);
    }
    public static final class ClearProgress {
        public final boolean complete;public final int percent;
        ClearProgress(boolean complete,int percent){this.complete=complete;this.percent=percent;}
    }
    public static ClearProgress clear(Bundle data){
        success(data);int status=integer(data,"status"),progress=integer(data,"progress");boolean more=bool(data,"continue");
        if(status==-1||status==-2)throw new IllegalArgumentException("Honda hardware history deletion failed (status "+status+")");
        if(progress<0||progress>100||!(status==0&&more||status==1&&!more&&progress==100))throw new IllegalArgumentException("Invalid deletion progress response");
        return new ClearProgress(status==1,progress);
    }
}
