package com.cabin.hardware.replacement;

import java.util.*;

/** Offline output sink. Recording bytes does not imply device delivery or state changes. */
public final class CommandJournal {
    private final ArrayDeque<Map<String,Object>> entries = new ArrayDeque<>();
    private long sequence;
    public synchronized void record(int module,int command,byte[] frame) {
        record(module,command,"MCU",frame);
    }
    public synchronized void record(int module,int command,String transport,byte[] frame) {
        Map<String,Object> entry=new LinkedHashMap<>(); entry.put("sequence",++sequence); entry.put("module",module); entry.put("command",command);
        StringBuilder hex=new StringBuilder(); for(byte value:frame) hex.append(String.format(Locale.ROOT,"%02X",value&255));
        entry.put("transport",transport); entry.put("frameHex",hex.toString()); entry.put("delivery","offline only");
        if(entries.size()==64) entries.removeFirst(); entries.addLast(Collections.unmodifiableMap(entry));
    }
    public synchronized List<Map<String,Object>> snapshot() { return new ArrayList<>(entries); }
    public synchronized long total() { return sequence; }
}
