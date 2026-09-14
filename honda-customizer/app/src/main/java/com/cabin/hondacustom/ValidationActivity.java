package com.cabin.hondacustom;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.*;
import java.util.*;

/** A copyable compatibility report and explicitly user-entered physical observations. */
public final class ValidationActivity extends Activity {
    private ValidationClient client;
    private LinearLayout checklist;
    private TextView status,summary;
    private Button run,copy,share,details;
    private final List<AlertDialog> dialogs=new ArrayList<>();
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout vertical(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(22,36,54));view.setPadding(0,dp(8),0,dp(8));return view;}
    private Button button(LinearLayout parent,String title,Runnable action){Button view=new Button(this);view.setText(title);view.setMinHeight(dp(48));view.setOnClickListener(v->action.run());parent.addView(view);return view;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);client=new ValidationClient(this,this::render);
        LinearLayout body=vertical();body.setPadding(dp(18),dp(12),dp(18),dp(18));body.setBackgroundColor(Color.rgb(243,246,249));
        body.addView(text("Honda compatibility report",25));
        body.addView(text("Checks installed services, permissions and firmware identity. This screen sends no setting changes.",17));
        status=text(client.status,16);body.addView(status);
        run=button(body,"Run read-only checks",client::run);
        summary=text("",15);body.addView(summary);
        details=button(body,"View full report",()->{
            if(client.report==null)return;
            TextView report=text(client.report.toJson(),13);report.setTextIsSelectable(true);report.setPadding(dp(14),dp(10),dp(14),dp(10));
            ScrollView scroll=new ScrollView(this);scroll.addView(report);
            show(new AlertDialog.Builder(this).setTitle("Compatibility report").setView(scroll).setPositiveButton("Close",null).create());
        });
        copy=button(body,"Copy report",()->{
            if(client.report==null)return;
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Honda compatibility report",client.report.toJson()));
            Toast.makeText(this,"Report copied",Toast.LENGTH_SHORT).show();
        });
        share=button(body,"Export / share report",()->{
            if(client.report==null)return;
            Intent intent=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,"Honda compatibility report").putExtra(Intent.EXTRA_TEXT,client.report.toJson());
            try{startActivity(Intent.createChooser(intent,"Export compatibility report"));}
            catch(ActivityNotFoundException e){Toast.makeText(this,"No export app is available. Use Copy report.",Toast.LENGTH_LONG).show();}
        });
        body.addView(text("Physical checks — your observations",21));
        body.addView(text("Every item starts as Not tested. Record what you observed on your parked car; these entries are not automatic verification. Copy or export before leaving. A new scan starts a new report.",15));
        checklist=vertical();body.addView(checklist);button(body,"Back",this::finish);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);setContentView(scroll);render();
    }
    private static String label(CompatibilityReport.Result result){
        switch(result){case USER_REPORTED_PASS:return "You reported: passed";case USER_REPORTED_FAIL:return "You reported: failed";
            case USER_REPORTED_NOT_APPLICABLE:return "You reported: not applicable";default:return "Not tested";}
    }
    private void render(){
        if(checklist==null)return;status.setText(client.status);boolean ready=client.report!=null&&client.phase!=ValidationClient.Phase.RUNNING;
        run.setEnabled(client.phase!=ValidationClient.Phase.RUNNING);copy.setEnabled(ready);share.setEnabled(ready);details.setEnabled(ready);
        StringBuilder description=new StringBuilder();
        if(client.report!=null){
            for(CompatibilityReport.ServiceCheck service:client.report.services)description.append(service.label).append(": ").append(service.canBind?"preflight available":service.problem).append('\n');
            description.append("Identity: ").append(client.report.identityStatus).append('\n');
            if(client.report.model!=null)description.append("Raw model identifier: ").append(client.report.model).append('\n');
            if(client.report.destinationCode!=null)description.append("Raw destination code: ").append(client.report.destinationCode).append('\n');
        }summary.setText(description.toString());checklist.removeAllViews();
        for(CompatibilityReport.Check check:CompatibilityReport.Check.values()){
            CompatibilityReport.Observation observation=client.report==null?null:client.report.observations.get(check);
            Button record=button(checklist,check.title+" · "+(observation==null?"Not tested":label(observation.result)),()->record(check));record.setEnabled(ready);
        }
    }
    private void record(CompatibilityReport.Check check){
        if(client.report==null)return;
        final CompatibilityReport target=client.report;CompatibilityReport.Observation previous=target.observations.get(check);
        LinearLayout content=vertical();content.setPadding(dp(16),dp(8),dp(16),dp(8));content.addView(text(check.instructions,16));
        Spinner result=new Spinner(this);String[] choices={"Not tested","I observed: passed","I observed: failed","Not applicable to my car"};
        result.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));result.setSelection(previous.result.ordinal());content.addView(result);
        EditText notes=new EditText(this);notes.setHint("What changed, what you saw, and whether you restored it");notes.setMinLines(2);notes.setText(previous.notes);content.addView(notes);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(check.title).setView(content).setNegativeButton("Cancel",null).setPositiveButton("Record observation",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(client.report!=target||client.phase==ValidationClient.Phase.RUNNING){dialog.dismiss();return;}
            try{target.record(check,CompatibilityReport.Result.values()[result.getSelectedItemPosition()],notes.getText().toString());dialog.dismiss();render();}
            catch(IllegalArgumentException e){notes.setError(e.getMessage());}
        }));show(dialog);
    }
    private void show(AlertDialog dialog){dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();}
    @Override protected void onPause(){for(AlertDialog dialog:new ArrayList<>(dialogs))dialog.dismiss();client.cancel();super.onPause();}
    @Override protected void onDestroy(){client.destroy();super.onDestroy();}
}
