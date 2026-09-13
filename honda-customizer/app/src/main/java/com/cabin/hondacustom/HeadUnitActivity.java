package com.cabin.hondacustom;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;

/** Uses Honda's exported editors for settings with locale/display side effects. */
public final class HeadUnitActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this);title.setText("Head-unit settings");title.setTextSize(26);root.addView(title);
        TextView detail=new TextView(this);detail.setText("Opens the original Honda editors on this unit.\n\nLanguage here changes the head unit. Cluster language is in Vehicle settings.");detail.setTextSize(17);root.addView(detail);
        CheckBox parked=new CheckBox(this);parked.setText(R.string.parked);root.addView(parked);
        TextView status=new TextView(this);status.setTextSize(16);
        add(root,"Head-unit language",OemScreens.Screen.LANGUAGE,parked,status);
        add(root,"Display, clock, wallpaper, sound and climate popup",OemScreens.Screen.SYSTEM,parked,status);
        root.addView(status);Button back=new Button(this);back.setText("Back");back.setOnClickListener(v->finish());root.addView(back);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
    }
    private void add(LinearLayout root,String label,OemScreens.Screen screen,CheckBox parked,TextView status){
        Button button=new Button(this);button.setText(label);root.addView(button);
        String reason=OemScreens.unavailable(this,screen);
        if(reason!=null){button.setEnabled(false);TextView note=new TextView(this);note.setText(reason);root.addView(note);}
        button.setOnClickListener(v->{String failure=OemScreens.open(this,screen,parked.isChecked());parked.setChecked(false);if(failure!=null)status.setText(failure);});
    }
}
