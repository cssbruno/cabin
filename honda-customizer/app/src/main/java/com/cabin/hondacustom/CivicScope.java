package com.cabin.hondacustom;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;

/** Offline evidence filter only. Live vehicle discovery remains authoritative. */
public final class CivicScope {
    private final Set<String> candidates=new HashSet<>();
    public CivicScope(Context context)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=context.getAssets().open("civic-scope.json")){
            byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1)bytes.write(buffer,0,count);
        }
        JSONArray rows=new JSONObject(bytes.toString("UTF-8")).getJSONArray("candidates");
        for(int i=0;i<rows.length();i++)candidates.add(rows.getString(i));
    }
    public boolean contains(Catalog.Entry entry){return candidates.contains(entry.key());}
}
