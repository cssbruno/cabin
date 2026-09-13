package com.cabin.hondacustom;

import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Main-thread editor state. One worker serializes Binder calls; callbacks belong to one connection. */
public final class MeterClient {
    public enum Phase {DISCONNECTED,CONNECTING,READING,READY,WRITING,VERIFYING}
    public interface Observer {void updated();}
    private final Context context;
    private final Observer observer;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Honda meter Binder");t.setDaemon(true);return t;});
    public Phase phase=Phase.DISCONNECTED;
    public String status="Connect to the original Honda meter service to load instrument contents.";
    public MeterContents live;
    private MeterContents.Capabilities capabilities;
    private final List<Integer> draft=new ArrayList<>();
    private volatile Session session;
    private volatile long generation;
    private int request;
    private MeterContents pending;
    private final Runnable timeout=()->fail("Meter request timed out. Outcome unknown; reconnect and read before retrying.");
    private final class Session implements ServiceConnection {
        final long token;
        final MeterProtocol.Callback callback;
        volatile MeterProtocol protocol;
        volatile boolean mode;
        boolean bound;
        Session(long token){
            this.token=token;
            callback=new MeterProtocol.Callback(new MeterProtocol.Events(){
                public void data(int result,MeterContents data){post(()->onData(result,data));}
                public void changed(int result){post(()->onChanged(result));}
                private void post(Runnable r){main.post(()->{if(current(Session.this))r.run();});}
            });
        }
        public void onServiceConnected(ComponentName name,IBinder binder){
            if(!current(this))return;
            io.execute(()->{try{
                if(!current(this))return;
                protocol=new MeterProtocol(binder);protocol.register(callback);
                if(!current(this))return;
                MeterContents.Capabilities found=protocol.capabilities();
                main.post(()->{if(current(this)){capabilities=found;read(MeterProtocol.REQUEST_CURRENT);}});
            }catch(Exception e){main.post(()->{if(current(this))fail(message(e));});}});
        }
        public void onServiceDisconnected(ComponentName name){if(current(this))fail("Honda meter service disconnected. Reconnect to read fresh contents.");}
    }
    public MeterClient(Context context,Observer observer){this.context=context;this.observer=observer;}
    private boolean current(Session s){return session==s&&s.token==generation;}
    private void note(String text){status=text;observer.updated();}
    private String message(Exception e){return e.getClass().getSimpleName()+": "+(e.getMessage()==null?"Meter service request failed":e.getMessage());}
    private void arm(){main.removeCallbacks(timeout);main.postDelayed(timeout,20000);}
    private void disarm(){main.removeCallbacks(timeout);}
    private interface Work{void run(Session s)throws Exception;}
    private void send(Work work){
        Session active=session;if(active==null)return;
        io.execute(()->{try{if(current(active))work.run(active);}catch(Exception e){main.post(()->{if(current(active))fail(message(e));});}});
    }
    public void connect(){
        if(phase!=Phase.DISCONNECTED)return;
        Session active=new Session(++generation);session=active;phase=Phase.CONNECTING;
        note("Connecting to Honda instrument contents…");arm();
        Intent intent=new Intent(MeterProtocol.DESCRIPTOR).setComponent(new ComponentName(MeterProtocol.PACKAGE,MeterProtocol.SERVICE));
        try{active.bound=context.bindService(intent,active,Context.BIND_AUTO_CREATE);
            if(!active.bound)fail("Honda meter service unavailable. This requires the original Honda ExternalDisplayApService.");
        }catch(Exception e){fail(message(e));}
    }
    public List<Integer> draft(){return Collections.unmodifiableList(new ArrayList<>(draft));}
    public Set<Integer> available(){return capabilities==null?Collections.emptySet():capabilities.available;}
    public boolean isProtected(int id){return capabilities!=null&&capabilities.protectedIds.contains(id);}
    public boolean dirty(){return live!=null&&!live.contents().equals(draft);}
    public boolean editable(){return phase==Phase.READY&&session!=null&&live!=null&&live.preset>=1&&capabilities!=null;}
    public boolean canAdd(int id){return editable()&&draft.size()<live.max&&available().contains(id)&&!draft.contains(id);}
    public void add(int id){if(!canAdd(id)){note("This content cannot be added to the current preset.");return;}draft.add(id);note("Content added to draft. Save to apply.");}
    public void remove(int index){
        if(!editable()||index<0||index>=draft.size()||isProtected(draft.get(index))){note("This content cannot be removed.");return;}
        draft.remove(index);note("Content removed from draft. Save to apply.");
    }
    public void move(int from,int to){
        if(!editable()||from<0||to<0||from>=draft.size()||to>=draft.size())return;
        Integer id=draft.remove(from);draft.add(to,id);note("Content order updated in draft. Save to apply.");
    }
    public void discard(){if(phase!=Phase.READY||live==null)return;draft.clear();draft.addAll(live.contents());note("Draft discarded; showing last verified contents.");}
    /** Reads the active vehicle preset; callers explicitly discard a dirty draft first. */
    public void refresh(){if(phase!=Phase.READY)return;if(dirty()){note("Save or discard the draft before reloading.");return;}read(MeterProtocol.REQUEST_CURRENT);}
    /** Opens a saved preset for editing; activating it uses VehicleInfoManager setting 03/5B. */
    public void loadPreset(int preset){
        if(phase!=Phase.READY||preset<1||preset>3)return;
        if(dirty()){note("Save or discard the draft before switching presets.");return;}read(preset);
    }
    /** OEM request 4 supplies defaults as a draft for the selected writable preset. */
    public void loadDefaults(){if(!editable())return;if(dirty()){note("Save or discard the draft before loading defaults.");return;}read(MeterProtocol.REQUEST_DEFAULT);}
    private void read(int requested){
        request=requested;phase=Phase.READING;note(requested==4?"Loading default contents…":"Reading Honda instrument contents…");arm();
        send(s->{MeterContents.Capabilities found=s.protocol.capabilities();
            main.post(()->{if(current(s))capabilities=found;});
            if(current(s))s.protocol.read(requested);
        });
    }
    private void onData(int result,MeterContents data){
        if(phase!=Phase.READING&&phase!=Phase.VERIFYING)return;
        if(result!=MeterProtocol.REPLY_OK){fail("Honda could not read instrument contents ("+result+").");return;}
        if(data==null||!data.validReply()){fail("Honda returned an unsupported or invalid contents layout.");return;}
        int expected=request==MeterProtocol.REQUEST_DEFAULT?0:request;
        if(request!=MeterProtocol.REQUEST_CURRENT&&data.preset!=expected)return;
        disarm();
        if(phase==Phase.VERIFYING){
            if(pending==null||!pending.matches(data)){fail("Meter readback did not confirm the saved order. Reconnect before another change.");return;}
            pending=null;live=data;draft.clear();draft.addAll(data.contents());phase=Phase.READY;
            note("Instrument contents confirmed by Honda and verified by readback.");return;
        }
        if(request==MeterProtocol.REQUEST_DEFAULT){
            if(live==null||capabilities==null||!capabilities.permits(live,data.contents())){fail("Default layout cannot safely replace this preset with the reported capabilities.");return;}
            draft.clear();draft.addAll(data.contents());phase=Phase.READY;note("Default contents loaded into draft. Save to apply to preset "+live.preset+".");return;
        }
        live=data;draft.clear();draft.addAll(data.contents());phase=Phase.READY;
        note(data.preset==0?"Default contents loaded. Open preset 1, 2 or 3 to edit.":"Preset "+data.preset+" loaded • "+data.count+" / "+data.max+" contents.");
    }
    public void save(boolean parked){
        if(!parked||!editable()||!capabilities.permits(live,draft)){note("Save unavailable. Load a supported preset while parked and preserve required contents.");return;}
        if(!dirty()){note("No content changes to save.");return;}
        final MeterContents baseline=live;
        final List<Integer> order=new ArrayList<>(draft);
        pending=MeterContents.outgoing(live.preset,order);final MeterContents desired=pending;
        phase=Phase.WRITING;note("Saving instrument contents; waiting for Honda confirmation…");arm();
        send(s->{
            MeterContents.Capabilities fresh=s.protocol.capabilities();
            if(!fresh.permits(baseline,order))throw new IllegalStateException("Honda content capabilities changed; reload before saving");
            if(!current(s))return;
            s.mode=true;s.protocol.mode(true);
            if(current(s))s.protocol.change(desired);
        });
    }
    private void onChanged(int result){
        if(phase!=Phase.WRITING)return;
        if(result!=MeterProtocol.REPLY_OK){fail("Honda rejected the instrument contents change ("+result+"). Reconnect to read current contents.");return;}
        disarm();phase=Phase.VERIFYING;request=pending.preset;
        final int preset=request;note("Honda acknowledged the save; verifying preset "+preset+"…");arm();
        send(s->{s.protocol.mode(false);s.mode=false;if(current(s))s.protocol.read(preset);});
    }
    private void fail(String text){disconnectInternal();note(text);}
    public void disconnect(){
        if(phase==Phase.DISCONNECTED)return; // Preserve the actual failure when Activity.onStop follows it.
        boolean unverified=phase==Phase.WRITING||phase==Phase.VERIFYING;
        disconnectInternal();
        note(unverified?"Disconnected during save. Outcome unknown; reconnect and read the vehicle before retrying."
                :"Instrument contents disconnected. Reconnect for fresh values.");
    }
    private void disconnectInternal(){
        disarm();Session old=session;session=null;++generation;pending=null;live=null;capabilities=null;draft.clear();phase=Phase.DISCONNECTED;
        if(old!=null){
            io.execute(()->{if(old.protocol!=null){if(old.mode)try{old.protocol.mode(false);}catch(Exception ignored){}
                try{old.protocol.unregister(old.callback);}catch(Exception ignored){}}});
            if(old.bound)try{context.unbindService(old);}catch(IllegalArgumentException ignored){}
        }
    }
    public void destroy(){disconnectInternal();io.shutdown();}
}
