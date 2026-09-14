package com.cabin.hondacustom;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;

public final class MainActivity extends Activity {
    private FytClient client; private LinearLayout rows; private TextView status; private CheckBox parked; private Button connect;
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setPadding(12,8,12,8);return t;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(16,12,16,12);
        root.addView(text("Honda Customizer · FYT",26));
        status=text("",17);root.addView(status);
        LinearLayout actions=new LinearLayout(this);
        connect=new Button(this);connect.setOnClickListener(v->{parked.setChecked(false);if(client.connected())client.disconnect();else client.connect();});actions.addView(connect);
        Button refresh=new Button(this);refresh.setText("Refresh");refresh.setOnClickListener(v->{parked.setChecked(false);client.connect();});actions.addView(refresh);root.addView(actions);
        Button report=new Button(this);report.setText("Report");report.setOnClickListener(v->report());actions.addView(report);
        parked=new CheckBox(this);parked.setText(R.string.parked);parked.setOnCheckedChangeListener((b,on)->render());root.addView(parked);
        ScrollView scroll=new ScrollView(this);rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        client=new FytClient(this,this::render);setContentView(root);render();
    }
    private void render(){if(client==null)return;
        status.setText(client.status);connect.setText(client.connected()?"Disconnect":"Connect to FYT");rows.removeAllViews();
        if(!client.connected()&&parked.isChecked())parked.setChecked(false);
        if(!FytProtocol.supported(client.profile())){
            rows.addView(text(client.connected()&&client.profile()!=0?"Your FYT service is connected. This CAN decoder needs a setting map. Use Report to copy its detected profile and service version.":"Connect to load settings for your FYT CANBUS profile.\n\nAvailable controls depend on the installed CAN decoder. Only verified FYT settings are shown.",18));return;
        }
        for(FytProtocol.Control c:FytProtocol.CONTROLS)if(FytProtocol.visible(client.profile(),c)){
            Integer value=client.values.get(c.field);Button b=new Button(this);
            b.setText(c.title+"\n"+(value==null?"Waiting for FYT value":c.options[value])+(value!=null&&!client.busy()&&!client.editable(c)?" · Refresh required":""));
            b.setEnabled(parked.isChecked()&&client.editable(c));b.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(c.title).setItems(c.options,(dialog,index)->
                new AlertDialog.Builder(this).setTitle("Apply setting?").setMessage(c.title+": "+c.options[index]).setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->client.change(c,index,parked.isChecked())).show()
            ).setNegativeButton("Cancel",null).show());rows.addView(b);
        }
        rows.addView(text("Missing or expired values require Refresh. Changes are confirmed only when FYT returns the selected value.",14));
    }
    private void report(){String content=client.report();TextView body=text(content,15);body.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("FYT connection report").setView(scroll).setNegativeButton("Close",null).setPositiveButton("Copy",(d,w)->{
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("FYT report",content));Toast.makeText(this,"FYT report copied",Toast.LENGTH_SHORT).show();}).show();
    }
    @Override protected void onStop(){super.onStop();if(client!=null)client.disconnect();}
    @Override protected void onDestroy(){if(client!=null)client.destroy();super.onDestroy();}
}
