package com.cabin.hondacustom;

import android.app.*;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.*;
import java.util.*;

/** Explicit confirmed edits; moving a picker alone never writes to the head unit. */
public final class DisplayActivity extends Activity {
    private DisplayClient client;private TextView status,mode;private LinearLayout controls;
    private Button connect,refresh;private CheckBox parked;
    private final List<AlertDialog> dialogs=new ArrayList<>();
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(22,36,54));view.setPadding(0,dp(8),0,dp(8));return view;}
    private Button button(LinearLayout parent,String label,Runnable action){Button view=new Button(this);view.setText(label);view.setMinHeight(dp(48));view.setOnClickListener(v->action.run());parent.addView(view);return view;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);client=new DisplayClient(this,this::render);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(12),dp(18),dp(18));body.setBackgroundColor(Color.rgb(243,246,249));
        body.addView(text("Display adjustment",25));body.addView(text("Brightness, contrast and black level for the original Honda head-unit display.",17));
        status=text(client.status,16);body.addView(status);
        connect=button(body,"Connect to Honda display",()->{if(client.phase==DisplayClient.Phase.DISCONNECTED)client.connect();else client.disconnect();});
        refresh=button(body,"Refresh current values",client::refresh);mode=text("",16);body.addView(mode);
        parked=new CheckBox(this);parked.setText("The vehicle is parked");body.addView(parked);parked.setOnCheckedChangeListener((b,on)->render());
        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);body.addView(controls);
        button(body,"Back",this::finish);ScrollView scroll=new ScrollView(this);scroll.addView(body);setContentView(scroll);render();
    }
    private void render(){
        if(controls==null)return;status.setText(client.status);connect.setText(client.phase==DisplayClient.Phase.DISCONNECTED?"Connect to Honda display":"Disconnect display");
        refresh.setEnabled(client.phase==DisplayClient.Phase.READY);if(client.phase==DisplayClient.Phase.DISCONNECTED)parked.setChecked(false);
        DisplayProtocol.Parameters current=client.value;mode.setText(current==null?"":"Current display mode: "+current.modeLabel());controls.removeAllViews();
        for(DisplayProtocol.Field field:DisplayProtocol.Field.values()){
            Button edit=button(controls,field.label+": "+(current==null?"Not read":Integer.toString(current.value(field))),()->choose(field));
            edit.setEnabled(current!=null&&current.writable()&&client.phase==DisplayClient.Phase.READY&&parked.isChecked());
        }
    }
    private void choose(DisplayProtocol.Field field){
        DisplayProtocol.Parameters current=client.value;if(current==null)return;
        String[] labels=new String[field.max-field.min+1];for(int i=0;i<labels.length;i++)labels[i]=Integer.toString(field.min+i);
        show(new AlertDialog.Builder(this).setTitle(field.label).setSingleChoiceItems(labels,current.value(field)-field.min,(dialog,index)->{
            dialog.dismiss();int desired=field.min+index;
            show(new AlertDialog.Builder(this).setTitle("Apply display change?").setMessage(field.label+": "+desired)
                    .setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->client.change(field,desired,parked.isChecked())).create());
        }).setNegativeButton("Cancel",null).create());
    }
    private void show(AlertDialog dialog){dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();}
    @Override protected void onPause(){for(AlertDialog dialog:new ArrayList<>(dialogs))dialog.dismiss();parked.setChecked(false);client.disconnect();super.onPause();}
    @Override protected void onDestroy(){client.destroy();super.onDestroy();}
}
