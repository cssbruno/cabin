package com.cabin.hondacustom;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import java.util.*;

/** Native editors for traced integer preferences; OEM editors retain locale and image side effects. */
public final class HeadUnitActivity extends Activity {
    private CheckBox parked;
    private HeadUnitSettingsClient client;
    private HeadUnitSettingsClient.Phase renderedPhase=HeadUnitSettingsClient.Phase.DISCONNECTED;
    private TextView status;
    private LinearLayout controls;
    private Button connect,refresh;
    private final List<AlertDialog> dialogs=new ArrayList<>();
    private final List<Button> shortcuts=new ArrayList<>();
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setPadding(0,dp(6),0,dp(8));return view;}
    private Button button(LinearLayout root,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setMinHeight(dp(52));b.setOnClickListener(v->action.run());root.addView(b);return b;}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        client=new HeadUnitSettingsClient(this,this::render);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(16),dp(20),dp(16));
        root.addView(text("Head-unit settings",26));
        root.addView(text("Read and edit the original Honda head-unit preferences. These settings affect the head unit; cluster settings are separate.",17));
        status=text(client.status,16);root.addView(status);
        connect=button(root,"Connect to head unit",()->{if(client.phase==HeadUnitSettingsClient.Phase.DISCONNECTED)client.connect();else client.disconnect();});
        refresh=button(root,"Refresh head-unit values",()->client.refresh());
        parked=new CheckBox(this);parked.setText(R.string.parked);root.addView(parked);
        parked.setOnCheckedChangeListener((b,checked)->{
            if(!checked&&client.phase==HeadUnitSettingsClient.Phase.WRITING)client.disconnect();
            render();
        });
        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);root.addView(controls);
        button(root,"Display adjustment",()->{client.disconnect();startActivity(new Intent(this,DisplayActivity.class));});
        root.addView(text("Original Honda editors",21));
        root.addView(text("Menu color, language and imported wallpaper keep Honda's original image and language workflows.",16));
        addShortcut(root,"Head-unit language",OemScreens.Screen.LANGUAGE);
        addShortcut(root,"Display, clock, wallpaper, sound and climate popup",OemScreens.Screen.SYSTEM);
        button(root,"Back",this::finish);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);render();
    }
    private void render(){
        if(controls==null)return;
        status.setText(client.status);connect.setText(client.phase==HeadUnitSettingsClient.Phase.DISCONNECTED?"Connect to head unit":"Disconnect head unit");
        refresh.setEnabled(client.phase==HeadUnitSettingsClient.Phase.READY);
        boolean disconnected=client.phase==HeadUnitSettingsClient.Phase.DISCONNECTED&&renderedPhase!=HeadUnitSettingsClient.Phase.DISCONNECTED;
        renderedPhase=client.phase;
        if(disconnected&&parked.isChecked())parked.setChecked(false);
        for(Button button:shortcuts){OemScreens.Screen screen=(OemScreens.Screen)button.getTag();button.setEnabled((client.phase==HeadUnitSettingsClient.Phase.READY||client.phase==HeadUnitSettingsClient.Phase.DISCONNECTED)&&OemScreens.unavailable(this,screen)==null);}
        controls.removeAllViews();
        for(HeadUnitSettings.Entry entry:HeadUnitSettings.ENTRIES){
            Integer value=client.values.get(entry.type);String unavailable=client.unavailable.get(entry.type);
            if(value==null&&unavailable==null)continue;
            String label=entry.title+": "+(value==null?"Unavailable":entry.label(value));
            Button edit=button(controls,label,()->choose(entry));edit.setEnabled(parked.isChecked()&&client.editable(entry));
            if(unavailable!=null)controls.addView(text(unavailable,14));
        }
    }
    private void choose(HeadUnitSettings.Entry entry){
        if(!client.editable(entry)||!parked.isChecked())return;
        final int expected=client.values.get(entry.type);
        Map<Integer,String> options=entry.options(client.destination);
        List<Integer> keys=new ArrayList<>(options.keySet());List<String> labels=new ArrayList<>(options.values());
        show(new AlertDialog.Builder(this).setTitle(entry.title).setItems(labels.toArray(new String[0]),(d,index)->{
            int desired=keys.get(index);if(desired==expected)return;
            show(new AlertDialog.Builder(this).setTitle("Apply head-unit setting?").setMessage(entry.title+"\n\n"+entry.label(expected)+" → "+labels.get(index))
                .setNegativeButton("Cancel",null).setPositiveButton("Apply",(dialog,which)->client.change(entry,expected,desired,parked.isChecked())).create());
        }).setNegativeButton("Cancel",null).create());
    }
    private void show(AlertDialog dialog){dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();}
    private void addShortcut(LinearLayout root,String label,OemScreens.Screen screen){
        Button button=button(root,label,()->{
            boolean confirmed=parked.isChecked();
            if(!confirmed){status.setText("Confirm that the car is parked before opening Honda settings.");return;}
            client.disconnect();String failure=OemScreens.open(this,screen,true);parked.setChecked(false);if(failure!=null)status.setText(failure);
        });
        button.setTag(screen);shortcuts.add(button);String reason=OemScreens.unavailable(this,screen);
        if(reason!=null)root.addView(text(reason,14));
    }
    @Override protected void onPause(){
        for(AlertDialog dialog:new ArrayList<>(dialogs))dialog.dismiss();
        if(parked!=null)parked.setChecked(false);if(client!=null)client.disconnect();super.onPause();
    }
    @Override protected void onDestroy(){if(client!=null)client.destroy();super.onDestroy();}
}
