package com.cabin.hondacustom;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

public final class MainActivity extends Activity {
    private Catalog catalog; private CivicScope scope; private HondaClient client;
    private LinearLayout rows; private TextView status,count; private Button connect,refresh;
    private CheckBox parked,all; private EditText search; private boolean initialized;
    private final List<Button> tools=new ArrayList<>();
    private boolean maintenanceRequested;
    private AlertDialog serviceDialog;
    private final int ink=Color.rgb(22,36,54), muted=Color.rgb(91,105,121), blue=Color.rgb(20,82,138);
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private Button button(String title,View.OnClickListener listener){Button b=new Button(this);b.setText(title);b.setMinHeight(dp(48));b.setOnClickListener(listener);return b;}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        try{catalog=new Catalog(this);scope=new CivicScope(this);}catch(Exception e){TextView error=text("Could not load the settings catalog: "+e.getMessage(),20,ink);setContentView(error);return;}
        client=new HondaClient(this,this::render);
        LinearLayout root=vertical();root.setPadding(dp(18),dp(12),dp(18),0);root.setBackgroundColor(Color.rgb(243,246,249));
        TextView title=text("Civic · Honda Customizer",25,ink);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        LinearLayout destinations=new LinearLayout(this);
        tool(destinations,"Panel",()->open(MeterActivity.class));
        tool(destinations,"Cameras",()->open(CameraActivity.class));
        tool(destinations,"Head unit",()->open(HeadUnitActivity.class));
        tool(destinations,"Service",this::serviceActions);root.addView(destinations);
        status=text(client.status,16,blue);status.setPadding(0,dp(12),0,dp(8));root.addView(status);
        LinearLayout actions=new LinearLayout(this);
        connect=button("Connect",v->{parked.setChecked(false);if(client.phase==HondaClient.Phase.DISCONNECTED)client.connect();else client.disconnect();});
        refresh=button("Refresh",v->client.refresh());
        actions.addView(connect,new LinearLayout.LayoutParams(0,dp(50),1));actions.addView(refresh,new LinearLayout.LayoutParams(0,dp(50),1));
        actions.addView(button("Report",v->report()),new LinearLayout.LayoutParams(0,dp(50),1));root.addView(actions);
        parked=new CheckBox(this);parked.setText(R.string.parked);parked.setTextColor(ink);parked.setOnCheckedChangeListener((b,on)->renderRows());root.addView(parked);
        LinearLayout filters=new LinearLayout(this);
        search=new EditText(this);search.setSingleLine(true);search.setHint("Search settings");search.setTextSize(16);
        filters.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
        all=new CheckBox(this);all.setText("Civic catalog");all.setOnCheckedChangeListener((b,on)->renderRows());filters.addView(all);root.addView(filters);
        count=text("",13,muted);count.setPadding(0,dp(8),0,dp(8));root.addView(count);
        ScrollView scroll=new ScrollView(this);rows=vertical();scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int c){renderRows();}public void afterTextChanged(Editable e){}});
        initialized=true;setContentView(root);render();
    }
    private void tool(LinearLayout row,String label,Runnable action){Button b=button(label,v->action.run());tools.add(b);row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}
    private void open(Class<? extends Activity> activity){client.disconnect();parked.setChecked(false);startActivity(new Intent(this,activity));}
    private void render(){if(!initialized)return;
        status.setText(client.status);connect.setText(client.phase==HondaClient.Phase.DISCONNECTED?"Connect":"Disconnect");
        refresh.setEnabled(client.phase==HondaClient.Phase.READY);
        for(Button b:tools)b.setEnabled(client.phase==HondaClient.Phase.READY||client.phase==HondaClient.Phase.DISCONNECTED);
        if(client.phase==HondaClient.Phase.DISCONNECTED)parked.setChecked(false);
        renderRows();
        if(maintenanceRequested&&client.phase==HondaClient.Phase.DISCONNECTED)maintenanceRequested=false;
        if(maintenanceRequested&&client.phase==HondaClient.Phase.READY){maintenanceRequested=false;showMaintenanceItems();}
    }
    private void renderRows(){if(!initialized)return;rows.removeAllViews();
        String query=search.getText().toString().toLowerCase(Locale.ROOT);int shown=0,last=-1;
        List<Catalog.Entry> list=new ArrayList<>(catalog.entries);
        Collections.sort(list,(a,b)->a.category!=b.category?a.category-b.category:a.id-b.id);
        for(Catalog.Entry e:list){Setting live=client.values.get(e.key());if(live==null&&(!all.isChecked()||!scope.contains(e)))continue;
            if(!(e.title+" "+e.description+" "+Catalog.category(e.category)+" "+e.key()).toLowerCase(Locale.ROOT).contains(query))continue;
            shown++;if(last!=e.category){last=e.category;TextView header=text(Catalog.category(last).toUpperCase(Locale.ROOT),14,blue);header.setTypeface(null,Typeface.BOLD);header.setPadding(0,dp(14),0,dp(8));rows.addView(header);}
            LinearLayout card=vertical();card.setPadding(dp(14),dp(12),dp(14),dp(12));card.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams margin=new LinearLayout.LayoutParams(-1,-2);margin.bottomMargin=dp(8);rows.addView(card,margin);
            TextView title=text(e.title,18,ink);title.setTypeface(null,Typeface.BOLD);card.addView(title);
            String value=live==null?"Civic candidate · not reported by this vehicle":"Current: "+e.label(live.value);
            card.addView(text(value,16,live==null?muted:blue));
            boolean can=live!=null&&e.editable(live)&&client.phase==HondaClient.Phase.READY&&parked.isChecked();
            if(live!=null&&!e.editable(live))card.addView(text("Read only — no verified value editor for this setting.",13,muted));
            if(e.title.startsWith("Setting "))card.addView(text(e.description,14,muted));
            card.setOnClickListener(v->{if(can)edit(e);else if(live!=null&&e.editable(live)&&client.phase==HondaClient.Phase.READY)Toast.makeText(this,"Confirm that you are parked to enable changes.",Toast.LENGTH_SHORT).show();});
            if(can){TextView hint=text("Tap to change  ›",14,blue);hint.setPadding(0,dp(6),0,0);card.addView(hint);}
        }
        int unmapped=0;for(String key:client.values.keySet())if(!catalog.byKey.containsKey(key))unmapped++;
        count.setText(getString(R.string.setting_count,all.isChecked()?"Catalog":"Live vehicle",shown)+(unmapped>0?getString(R.string.unknown_count,unmapped):""));
        if(shown==0){TextView empty=text(all.isChecked()?"No matching settings.":"Connect to load settings supported by your Civic.\n\nCivic catalog lets you browse documented candidates while disconnected. Availability depends on the car and original Honda unit.",18,muted);empty.setPadding(dp(8),dp(24),dp(8),0);rows.addView(empty);}
    }
    private void edit(Catalog.Entry e){Setting live=client.values.get(e.key());if(live==null)return;
        if(live.type==0){List<Integer> keys=new ArrayList<>();List<String> names=new ArrayList<>();
            for(Map.Entry<Integer,String> option:e.options.entrySet())if(e.allows(live,option.getKey())){keys.add(option.getKey());names.add(option.getValue());}
            new AlertDialog.Builder(this).setTitle(e.title).setItems(names.toArray(new String[0]),(d,which)->confirm(e,keys.get(which))).setNegativeButton("Cancel",null).show();
        }else{
            EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);input.setText(String.format(Locale.US,"%d",live.value));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(e.title).setMessage("Allowed range: "+live.range[0]+" to "+live.range[1]).setView(input).setPositiveButton("Continue",null).setNegativeButton("Cancel",null).create();
            dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int val=Integer.parseInt(input.getText().toString());if(!live.bounded(val))throw new IllegalArgumentException();dialog.dismiss();confirm(e,val);}catch(Exception ex){input.setError("Enter a value in the allowed range");}}));dialog.show();
        }
    }
    private void confirm(Catalog.Entry entry,int value){new AlertDialog.Builder(this).setTitle("Apply vehicle setting?")
        .setMessage(entry.title+"\n\nNew value: "+entry.label(value)+"\n\nKeep the car parked while the change is confirmed.")
        .setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{
            if(entry.category==3&&entry.id==0x5c)client.changeSystemTachometer(entry,value,parked.isChecked());
            else client.change(entry,value,parked.isChecked());
        }).show();}
    private void report(){
        StringBuilder out=new StringBuilder("Honda Customizer 2.0\nTarget: original Honda VehicleInfoManager\nCivic catalog is an offline candidate filter; live values come from Honda discovery.\n");
        out.append("State: ").append(client.phase).append("\n").append(client.status).append("\n\n");
        for(Setting s:client.values.values()){Catalog.Entry e=catalog.byKey.get(s.key());out.append(e==null?s.key():e.title).append(" [").append(s.key()).append("] = ").append(s.value).append("; type=").append(s.type).append("; range=").append(Arrays.toString(s.range)).append("\n");}
        out.append("\nSession log\n");for(String line:client.log)out.append(line).append("\n");
        final String content=out.toString();TextView body=text(content,14,ink);body.setPadding(dp(16),dp(12),dp(16),dp(12));body.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Connection report").setView(scroll).setPositiveButton("Copy",(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Honda report",content));Toast.makeText(this,"Report copied",Toast.LENGTH_SHORT).show();}).setNegativeButton("Close",null).show();
    }
    private void serviceActions(){
        LinearLayout panel=vertical();panel.setPadding(dp(16),dp(12),dp(16),dp(12));
        panel.addView(text("Keep the car parked. Actions require a live connection and Honda's confirmation.",16,ink));
        Button tpms=button("Calibrate tire-pressure system",v->new AlertDialog.Builder(this).setTitle("Start TPMS calibration?")
            .setMessage("Use after checking and adjusting tire pressures. Honda may require driving afterward to finish calibration.")
            .setNegativeButton("Cancel",null).setPositiveButton("Start calibration",(d,w)->{closeServiceActions();client.calibrateTpms(parked.isChecked());}).show());
        tpms.setEnabled(parked.isChecked()&&client.canCalibrateTpms());panel.addView(tpms);
        Button reset=button("Restore vehicle customization defaults",v->new AlertDialog.Builder(this).setTitle("Restore vehicle defaults?")
            .setMessage("This resets the vehicle customization settings discovered by Honda. Your current choices will be replaced. It does not erase head-unit apps or files.")
            .setNegativeButton("Cancel",null).setPositiveButton("Restore defaults",(d,w)->{closeServiceActions();client.resetCustomization(parked.isChecked());}).show());
        reset.setEnabled(parked.isChecked()&&client.canResetCustomization());panel.addView(reset);
        Button maintenance=button("Maintenance items and service reset",v->{closeServiceActions();maintenanceRequested=true;client.requestMaintenanceStatus();});
        maintenance.setEnabled(client.phase==HondaClient.Phase.READY);panel.addView(maintenance);
        panel.addView(button("Honda diagnostics",v->{
            if(!parked.isChecked()){Toast.makeText(this,"Confirm that the car is parked first.",Toast.LENGTH_SHORT).show();return;}
            String unavailable=OemScreens.unavailable(this,OemScreens.Screen.DIAGNOSTICS);
            if(unavailable!=null){new AlertDialog.Builder(this).setMessage(unavailable).setPositiveButton("OK",null).show();return;}
            closeServiceActions();client.disconnect();String error=OemScreens.open(this,OemScreens.Screen.DIAGNOSTICS,true);
            if(error!=null)new AlertDialog.Builder(this).setMessage(error).setPositiveButton("OK",null).show();
        }));
        panel.addView(text("Diagnostics opens Honda's installed diagnostic app. This app does not generate raw OBD commands.",14,muted));
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);
        serviceDialog=new AlertDialog.Builder(this).setTitle("Service actions").setView(scroll).setNegativeButton("Close",null).show();
    }
    private void closeServiceActions(){if(serviceDialog!=null){serviceDialog.dismiss();serviceDialog=null;}}
    private void showMaintenanceItems(){
        Map<Integer,String> options=client.maintenanceOptions();
        if(options.isEmpty()){new AlertDialog.Builder(this).setTitle("Maintenance").setMessage("Honda did not report any supported maintenance-reset items.").setPositiveButton("OK",null).show();return;}
        List<Integer> items=new ArrayList<>(options.keySet());List<String> labels=new ArrayList<>(options.values());
        new AlertDialog.Builder(this).setTitle("Maintenance reset").setItems(labels.toArray(new String[0]),(dialog,index)->{
            if(!parked.isChecked()){Toast.makeText(this,"Confirm that the car is parked first.",Toast.LENGTH_SHORT).show();return;}
            new AlertDialog.Builder(this).setTitle("Reset completed service?").setMessage(labels.get(index)+"\n\nOnly reset an item after its maintenance has been performed.")
                .setNegativeButton("Cancel",null).setPositiveButton("Reset service item",(d,w)->client.resetMaintenance(items.get(index),parked.isChecked())).show();
        }).setNegativeButton("Cancel",null).show();
    }
    @Override protected void onStop(){super.onStop();if(client!=null&&client.phase!=HondaClient.Phase.DISCONNECTED)client.disconnect();}
    @Override protected void onDestroy(){if(client!=null)client.destroy();super.onDestroy();}
}
