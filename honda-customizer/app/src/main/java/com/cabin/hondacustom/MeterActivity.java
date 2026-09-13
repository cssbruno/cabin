package com.cabin.hondacustom;

import android.app.*;
import android.os.Bundle;
import android.widget.*;
import java.util.*;

/** A local draft is edited first; only Save sends the ordered contents to Honda. */
public final class MeterActivity extends Activity {
    private MeterClient client;
    private TextView status;
    private LinearLayout contents;
    private CheckBox parked;
    private Button connect,add,save,discard,defaults;
    private final List<Button> presets=new ArrayList<>();
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);return t;}
    private Button button(String label,Runnable onClick){Button b=new Button(this);b.setText(label);b.setMinHeight(dp(48));b.setOnClickListener(v->onClick.run());return b;}
    private void cell(LinearLayout row,Button button){row.addView(button,new LinearLayout.LayoutParams(0,dp(50),1));}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        client=new MeterClient(this,this::render);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(10),dp(16),0);
        root.addView(text("Instrument-panel contents",25));
        status=text(client.status,16);root.addView(status);
        LinearLayout top=new LinearLayout(this);
        connect=button("Connect",()->{
            if(client.phase==MeterClient.Phase.DISCONNECTED)client.connect();else discardBefore(()->client.disconnect());
        });cell(top,connect);
        for(int i=1;i<=3;i++){final int preset=i;Button b=button("Preset "+i,()->discardBefore(()->client.loadPreset(preset)));presets.add(b);cell(top,b);}
        root.addView(top);
        parked=new CheckBox(this);parked.setText(R.string.parked);parked.setOnCheckedChangeListener((b,on)->render());root.addView(parked);
        LinearLayout actions=new LinearLayout(this);
        add=button("Add",this::addContent);save=button("Save",this::confirmSave);
        discard=button("Discard",()->discardBefore(()->{}));defaults=button("Defaults",()->discardBefore(()->client.loadDefaults()));
        cell(actions,add);cell(actions,save);cell(actions,discard);cell(actions,defaults);root.addView(actions);
        root.addView(text("Select the active preset in Vehicle settings → Panel configuration. Save edits before leaving this screen or switching apps.",14));
        contents=new LinearLayout(this);contents.setOrientation(LinearLayout.VERTICAL);ScrollView scroll=new ScrollView(this);scroll.addView(contents);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);render();
    }
    private void render(){if(status==null||contents==null)return;
        status.setText(client.status+(client.live==null?"":"\nPreset "+client.live.preset+" · "+client.draft().size()+" / "+client.capacity()+" items"));connect.setText(client.phase==MeterClient.Phase.DISCONNECTED?"Connect":"Disconnect");
        boolean ready=client.phase==MeterClient.Phase.READY;
        for(Button b:presets)b.setEnabled(ready);
        if(client.phase==MeterClient.Phase.DISCONNECTED&&parked.isChecked())parked.setChecked(false);
        boolean editable=client.editable();boolean canAdd=false;
        for(int id:client.available())if(client.canAdd(id)){canAdd=true;break;}
        add.setEnabled(canAdd);save.setEnabled(editable&&client.dirty()&&parked.isChecked());
        discard.setEnabled(ready&&client.dirty());defaults.setEnabled(editable);
        contents.removeAllViews();List<Integer> draft=client.draft();
        for(int i=0;i<draft.size();i++){
            final int index=i,id=draft.get(i);LinearLayout row=new LinearLayout(this);
            TextView name=text((i+1)+". "+MeterContents.label(id)+(client.isProtected(id)?" · required":""),17);
            row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            Button up=button("↑",()->client.move(index,index-1));up.setContentDescription("Move "+MeterContents.label(id)+" up");up.setEnabled(editable&&i>0);
            Button down=button("↓",()->client.move(index,index+1));down.setContentDescription("Move "+MeterContents.label(id)+" down");down.setEnabled(editable&&i+1<draft.size());
            Button remove=button("Remove",()->client.remove(index));remove.setEnabled(editable&&!client.isProtected(id));
            row.addView(up,new LinearLayout.LayoutParams(dp(52),dp(52)));row.addView(down,new LinearLayout.LayoutParams(dp(52),dp(52)));row.addView(remove,new LinearLayout.LayoutParams(dp(100),dp(52)));
            contents.addView(row);
        }
        if(draft.isEmpty())contents.addView(text(ready?"This preset has no contents. Use Add to choose supported items.":"Connect to read the available contents and saved presets from Honda.",18));
    }
    private void addContent(){
        List<Integer> ids=new ArrayList<>();List<String> labels=new ArrayList<>();
        for(int id:client.available())if(client.canAdd(id)){ids.add(id);labels.add(MeterContents.label(id));}
        if(ids.isEmpty())return;
        new AlertDialog.Builder(this).setTitle("Add display item").setItems(labels.toArray(new String[0]),(d,w)->client.add(ids.get(w))).setNegativeButton("Cancel",null).show();
    }
    private void confirmSave(){if(client.live==null)return;
        StringBuilder preview=new StringBuilder("Preset "+client.live.preset+"\n\n");
        for(int id:client.draft())preview.append("• ").append(MeterContents.label(id)).append('\n');
        new AlertDialog.Builder(this).setTitle("Save these panel contents?").setMessage(preview.toString()).setNegativeButton("Cancel",null)
            .setPositiveButton("Save",(d,w)->client.save(parked.isChecked())).show();
    }
    private void discardBefore(Runnable next){
        if(client.phase==MeterClient.Phase.WRITING||client.phase==MeterClient.Phase.VERIFYING){
            new AlertDialog.Builder(this).setTitle("Leave while saving?")
                .setMessage("A save is in progress. Leaving stops confirmation; Honda may still apply it. Reconnect and read the preset before retrying.")
                .setNegativeButton("Keep waiting",null).setPositiveButton("Leave",(d,w)->next.run()).show();return;
        }
        if(!client.dirty()){next.run();return;}
        new AlertDialog.Builder(this).setTitle("Discard unsaved contents?").setMessage("Your draft has not been sent to Honda.")
            .setNegativeButton("Keep editing",null).setPositiveButton("Discard",(d,w)->{client.discard();next.run();}).show();
    }
    @Override public void onBackPressed(){discardBefore(this::finish);}
    @Override protected void onStop(){if(client!=null)client.disconnect();super.onStop();}
    @Override protected void onDestroy(){if(client!=null)client.destroy();super.onDestroy();}
}
