package com.cabin.reports;

import android.os.SystemClock;
import org.json.*;
import java.util.ArrayDeque;

/** Bounded, app-owned diagnostics only; no system logcat, phone identities or credentials. */
public final class DebugJournal {
    private static final ArrayDeque<JSONObject> events = new ArrayDeque<>();
    public static synchronized void record(String area, String event, String detail) {
        try {
            events.addLast(new JSONObject().put("elapsedMs", SystemClock.elapsedRealtime())
                .put("area", area).put("event", event).put("detail", detail == null ? "" : detail.substring(0, Math.min(1000, detail.length()))));
            while (events.size() > 100) events.removeFirst();
        } catch (JSONException ignored) { }
    }
    public static synchronized JSONArray snapshot() { return new JSONArray(events); }
    public static synchronized void clear() { events.clear(); }
    public static String decorate(String report) {
        try {
            JSONObject result;
            try { result = new JSONObject(report); }
            catch (JSONException ex) { result = new JSONObject().put("reportText", report); }
            return result.put("debugEvents", snapshot()).put("exportCapturedAtMs", System.currentTimeMillis()).toString(2);
        } catch (JSONException ex) { throw new IllegalStateException(ex); }
    }
    private DebugJournal() { }
}
