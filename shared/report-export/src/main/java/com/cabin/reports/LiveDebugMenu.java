package com.cabin.reports;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import org.json.*;

/** Dialog stays over the active screen so opening debug does not stop its CAN receiver. */
public final class LiveDebugMenu {
    public static void show(Context context) {
        Activity activity = activity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Session session = new Session(activity);
        session.show();
    }
    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }
    static JSONArray filter(JSONArray events, String area) {
        JSONArray result = new JSONArray();
        for (int i=0;i<events.length();i++) {
            JSONObject event = events.optJSONObject(i);
            if (event != null && ("All".equals(area) || area.equals(event.optString("area")))) result.put(event);
        }
        return result;
    }
    private static final class Session implements Application.ActivityLifecycleCallbacks {
        final Activity activity;
        final Handler handler = new Handler(Looper.getMainLooper());
        final TextView output, status;
        final ScrollView scroll;
        final AlertDialog dialog;
        final String[] filters = {"All", "CAN", "CarPlay", "export"};
        JSONArray captured = new JSONArray();
        String area = "All";
        boolean paused, closed, visible = true;
        final Runnable tick = new Runnable() {
            public void run() {
                if (closed || !visible) return;
                if (!paused) { captured = DebugJournal.snapshot(); render(); }
                handler.postDelayed(this,500);
            }
        };
        Session(Activity activity) {
            this.activity = activity;
            LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL);
            int pad = (int)(12*activity.getResources().getDisplayMetrics().density); column.setPadding(pad,pad,pad,pad);
            status = new TextView(activity); column.addView(status);
            Spinner filter = new Spinner(activity);
            filter.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,filters)); column.addView(filter);
            filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent, View view, int position,long id) { area=filters[position]; render(); }
                public void onNothingSelected(AdapterView<?> parent) { }
            });
            LinearLayout actions = new LinearLayout(activity); column.addView(actions);
            Button pause = button(actions,"Pause");
            pause.setOnClickListener(v -> {
                paused = !paused; pause.setText(paused ? "Resume" : "Pause");
                if (!paused) captured = DebugJournal.snapshot(); render();
            });
            button(actions,"Clear").setOnClickListener(v -> { DebugJournal.clear(); captured = new JSONArray(); render(); });
            button(actions,"Export").setOnClickListener(v -> {
                try {
                    String snapshot = new JSONObject().put("source","live debug menu")
                        .put("filter",area).put("paused",paused).put("visibleEvents",filter(captured,area)).toString(2);
                    ReportExport.show(activity,"cabin-live-debug.json",snapshot);
                } catch (JSONException ex) { throw new IllegalStateException(ex); }
            });
            output = new TextView(activity); output.setTypeface(Typeface.MONOSPACE); output.setTextIsSelectable(true); output.setTextSize(13);
            scroll = new ScrollView(activity); scroll.addView(output);
            column.addView(scroll,new LinearLayout.LayoutParams(-1,(int)(260*activity.getResources().getDisplayMetrics().density)));
            dialog = new AlertDialog.Builder(activity).setTitle("Live debug").setView(column).setNegativeButton("Close",null).create();
            dialog.setOnDismissListener(d -> finish());
        }
        Button button(LinearLayout row,String label) {
            Button button = new Button(activity); button.setText(label); row.addView(button,new LinearLayout.LayoutParams(0,-2,1)); return button;
        }
        void show() {
            activity.getApplication().registerActivityLifecycleCallbacks(this);
            dialog.show(); handler.post(tick);
        }
        void render() {
            if (output == null) return;
            JSONArray events = filter(captured,area);
            status.setText((paused ? "Paused" : "Live") + " · " + events.length() + " events · latest 100 retained");
            StringBuilder text = new StringBuilder();
            // Newest first: useful on a small display without forced scrolling while reading.
            for(int i=events.length()-1;i>=0;i--) {
                JSONObject event=events.optJSONObject(i);
                text.append(String.format(java.util.Locale.ROOT,"%.1fs",event.optLong("elapsedMs")/1000.0))
                    .append("  ").append(event.optString("area")).append(" / ").append(event.optString("event"))
                    .append('\n').append(event.optString("detail")).append("\n\n");
            }
            String next = text.length()==0 ? "Waiting for events. Use the car’s controls or retry the connection." : text.toString();
            if (!next.contentEquals(output.getText())) output.setText(next);
        }
        void finish() {
            if (closed) return; closed=true; handler.removeCallbacks(tick);
            activity.getApplication().unregisterActivityLifecycleCallbacks(this);
        }
        public void onActivityStopped(Activity a) { if(a==activity) { visible=false; handler.removeCallbacks(tick); } }
        public void onActivityStarted(Activity a) { if(a==activity && !closed) { visible=true; handler.removeCallbacks(tick); handler.post(tick); } }
        public void onActivityDestroyed(Activity a) { if(a==activity) { finish(); dialog.dismiss(); } }
        public void onActivityCreated(Activity a,Bundle b) { }
        public void onActivityResumed(Activity a) { }
        public void onActivityPaused(Activity a) { }
        public void onActivitySaveInstanceState(Activity a,Bundle b) { }
    }
    private LiveDebugMenu() { }
}
