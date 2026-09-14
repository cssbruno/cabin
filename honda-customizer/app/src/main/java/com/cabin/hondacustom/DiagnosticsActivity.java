package com.cabin.hondacustom;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import java.util.*;

/** Narrow native diagnostic operations; no factory-wide reset or generic OBD write command. */
public final class DiagnosticsActivity extends Activity {
    private DiagnosticClient client;
    private TextView status,records;
    private Button connect,hardware,telematics,clear;
    private CheckBox parked;
    private final List<AlertDialog> dialogs=new ArrayList<>();
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private Button button(String name,Runnable action){Button b=new Button(this);b.setText(name);b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());return b;}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);return t;}
    @Override public void onCreate(Bundle state){super.onCreate(state);client=new DiagnosticClient(this,this::render);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(12),dp(16),0);
        root.addView(text("Honda diagnostic readings",25));
        root.addView(text("Read head-unit hardware history and telematics DTC records. Telematics is the communications unit; these are separate from engine and transmission diagnostics.",15));
        status=text(client.status,16);root.addView(status);
        connect=button("Connect diagnostics",()->{if(client.phase==DiagnosticClient.Phase.DISCONNECTED)client.connect();else leave(client::disconnect);});root.addView(connect);
        hardware=button("Read hardware history",()->client.readHardware());root.addView(hardware);
        telematics=button("Read telematics DTCs",()->client.readTelematics());root.addView(telematics);
        parked=new CheckBox(this);parked.setText(R.string.parked);parked.setOnCheckedChangeListener((b,on)->{if(!on&&(client.phase==DiagnosticClient.Phase.CLEARING||client.phase==DiagnosticClient.Phase.VERIFYING))client.disconnect();render();});root.addView(parked);
        clear=button("Clear hardware history",this::confirmClear);root.addView(clear);
        root.addView(button("Copy diagnostic report",()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Honda diagnostic readings",client.report()));Toast.makeText(this,"Diagnostic report copied",Toast.LENGTH_SHORT).show();}));
        records=text("",16);root.addView(records);ScrollView scroll=new ScrollView(this);scroll.addView(root);
        setContentView(scroll);render();
    }
    private void render(){if(records==null)return;
        status.setText(client.status);connect.setText(client.phase==DiagnosticClient.Phase.DISCONNECTED?"Connect diagnostics":"Disconnect diagnostics");
        boolean ready=client.phase==DiagnosticClient.Phase.READY;hardware.setEnabled(ready);telematics.setEnabled(ready);
        if(client.phase==DiagnosticClient.Phase.DISCONNECTED&&parked.isChecked())parked.setChecked(false);
        clear.setEnabled(client.canClearHardware()&&parked.isChecked());records.setText(client.report());
    }
    private void confirmClear(){if(!client.canClearHardware()||!parked.isChecked())return;
        show(new AlertDialog.Builder(this).setTitle("Delete hardware error history?")
            .setMessage("Delete all "+client.history.size()+" stored head-unit hardware error records? This removes diagnostic evidence and cannot be undone. It does not repair a fault or clear engine/transmission codes. Copy the report first if you need these records.")
            .setNegativeButton("Keep records",null).setPositiveButton("Delete hardware history",(d,w)->client.clearHardware(parked.isChecked(),true)).create());
    }
    private void leave(Runnable next){
        if(client.phase!=DiagnosticClient.Phase.CLEARING&&client.phase!=DiagnosticClient.Phase.VERIFYING){next.run();return;}
        show(new AlertDialog.Builder(this).setTitle("Leave during deletion?").setMessage("The deletion may still complete. Leaving stops confirmation; reconnect and read the records before retrying.")
            .setNegativeButton("Keep waiting",null).setPositiveButton("Leave",(d,w)->next.run()).create());
    }
    private void show(AlertDialog dialog){dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();}
    @Override public void onBackPressed(){leave(this::finish);}
    @Override protected void onPause(){
        for(AlertDialog dialog:new ArrayList<>(dialogs))dialog.dismiss();
        if(parked!=null)parked.setChecked(false);if(client!=null)client.disconnect();super.onPause();
    }
    @Override protected void onDestroy(){if(client!=null)client.destroy();super.onDestroy();}
}
