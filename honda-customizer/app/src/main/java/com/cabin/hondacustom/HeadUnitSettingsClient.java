package com.cabin.hondacustom;

import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Serialized reads/writes with foreground session invalidation and no writes during discovery. */
public final class HeadUnitSettingsClient {
    public enum Phase { DISCONNECTED, CONNECTING, READING, READY, WRITING }
    public Phase phase=Phase.DISCONNECTED;
    public String status="Connect to read settings from the original Honda head unit.";
    public final Map<Integer,Integer> values=new LinkedHashMap<>();
    public final Map<Integer,String> unavailable=new LinkedHashMap<>();
    public Integer destination;
    private final Context context;private final Runnable changed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile Session session;private volatile boolean destroyed;private Runnable timeout;
    private final class Session implements ServiceConnection {
        volatile boolean active=true;boolean bound;HeadUnitSettingsProtocol protocol;
        public void onServiceConnected(ComponentName name,IBinder binder){
            if(!valid(this))return;
            phase=Phase.READING;status="Reading Honda head-unit settings…";changed.run();
            execute(this,()->{protocol=new HeadUnitSettingsProtocol(binder);return snapshot(this);},"Head-unit settings loaded.");
        }
        public void onServiceDisconnected(ComponentName name){if(valid(this))fail("Honda head-unit service disconnected. Any in-flight change is unconfirmed.");}
    }
    private static final class Snapshot {
        Integer destination;final Map<Integer,Integer> values=new LinkedHashMap<>();final Map<Integer,String> unavailable=new LinkedHashMap<>();
    }
    private interface Operation {Snapshot run()throws Exception;}
    public HeadUnitSettingsClient(Context context,Runnable changed){this.context=context;this.changed=changed;}
    private static boolean same(Integer a,Integer b){return a==null?b==null:a.equals(b);}
    private boolean valid(Session s){return !destroyed&&s!=null&&s.active&&session==s;}
    private void ensure(Session s)throws RemoteException{if(!valid(s))throw new RemoteException("Head-unit session ended");}
    public void connect(){
        if(destroyed||phase!=Phase.DISCONNECTED)return;
        Session s=new Session();session=s;phase=Phase.CONNECTING;status="Connecting to Honda head-unit service…";changed.run();if(!valid(s))return;arm(s);
        try{s.bound=context.bindService(new Intent(HeadUnitSettingsProtocol.DESCRIPTOR).setComponent(new ComponentName(HeadUnitSettingsProtocol.PACKAGE,HeadUnitSettingsProtocol.SERVICE)),s,Context.BIND_AUTO_CREATE);
            if(!s.bound){if(valid(s))fail("Original Honda head-unit service is unavailable.");}else if(!valid(s))unbind(s);
        }catch(RuntimeException e){if(valid(s))fail("Cannot connect to Honda head unit: "+e.getMessage());}
    }
    private Snapshot snapshot(Session s)throws Exception{
        Snapshot result=new Snapshot();ensure(s);
        try{result.destination=s.protocol.destination();}catch(HeadUnitSettingsProtocol.Unavailable e){result.destination=null;}
        for(HeadUnitSettings.Entry entry:HeadUnitSettings.ENTRIES){
            ensure(s);
            try{
                int raw=s.protocol.read(entry);
                if(entry.needsDestination()&&result.destination==null){result.unavailable.put(entry.type,"Honda destination unavailable; use the original editor.");continue;}
                int value=entry.convert(raw,result.destination);result.values.put(entry.type,value);
                if(!entry.known(value))result.unavailable.put(entry.type,"Unknown Honda value; use the original editor.");
            }catch(HeadUnitSettingsProtocol.Unavailable e){result.unavailable.put(entry.type,e.getMessage());}
        }
        return result;
    }
    public void refresh(){Session s=session;if(!valid(s)||phase!=Phase.READY)return;phase=Phase.READING;status="Refreshing head-unit settings…";changed.run();execute(s,()->snapshot(s),"Head-unit settings refreshed.");}
    public boolean editable(HeadUnitSettings.Entry entry){return HeadUnitSettings.trusted(entry)&&valid(session)&&phase==Phase.READY&&values.containsKey(entry.type)&&!unavailable.containsKey(entry.type)&&!entry.options(destination).isEmpty();}
    public void change(HeadUnitSettings.Entry entry,int expectedValue,int desired,boolean parked){
        if(!parked||!editable(entry)||!entry.options(destination).containsKey(desired)||values.get(entry.type)!=expectedValue){status="Change unavailable. Read current supported settings while parked.";changed.run();return;}
        Session s=session;Integer expectedDestination=destination;
        phase=Phase.WRITING;status="Applying head-unit setting and checking service readback…";changed.run();
        execute(s,()->{
            ensure(s);Integer currentDestination=expectedDestination;
            if(entry.needsDestination()){
                currentDestination=s.protocol.destination();
                if(!same(currentDestination,expectedDestination))throw new RemoteException("Honda destination changed; reload before editing");
            }
            int current=entry.convert(s.protocol.read(entry),currentDestination);
            if(current!=expectedValue)throw new RemoteException("Honda value changed since confirmation; refresh before editing");
            ensure(s);
            if(current!=desired)s.protocol.write(entry,desired,currentDestination);
            ensure(s);
            int actual=entry.convert(s.protocol.read(entry),currentDestination);
            if(actual!=desired)throw new RemoteException("Head-unit readback did not confirm the requested value");
            Snapshot result=snapshot(s);
            if(!same(result.values.get(entry.type),desired))throw new RemoteException("Head-unit value changed during verification");
            return result;
        },"Head-unit setting confirmed by service readback. Visible behavior has not been independently verified.");
    }
    private void execute(Session s,Operation operation,String success){if(!valid(s))return;arm(s);try{worker.execute(()->{
        try{ensure(s);Snapshot result=operation.run();main.post(()->{if(!valid(s))return;cancelTimeout();values.clear();values.putAll(result.values);unavailable.clear();unavailable.putAll(result.unavailable);destination=result.destination;phase=Phase.READY;status=success;changed.run();});}
        catch(Exception e){main.post(()->{if(valid(s))fail("Head-unit request failed: "+e.getMessage()+". Any attempted write is unconfirmed; reconnect to read current values.");});}
    });}catch(RejectedExecutionException e){if(valid(s))fail("Head-unit worker is unavailable; reconnect before retrying.");}}
    private void arm(Session s){if(!valid(s))return;cancelTimeout();timeout=()->{if(valid(s))fail("Head-unit request timed out. Any in-flight change has an unknown outcome; reconnect before retrying.");};main.postDelayed(timeout,20000);}
    private void cancelTimeout(){if(timeout!=null)main.removeCallbacks(timeout);timeout=null;}
    private void unbind(Session s){if(s.bound){s.bound=false;try{context.unbindService(s);}catch(IllegalArgumentException ignored){}}}
    private void clearSession(){
        Session s=session;session=null;if(s!=null){s.active=false;unbind(s);}
        cancelTimeout();values.clear();unavailable.clear();destination=null;phase=Phase.DISCONNECTED;
    }
    private void fail(String message){clearSession();status=message;changed.run();}
    public void disconnect(){
        if(phase==Phase.DISCONNECTED&&session==null)return;
        boolean unconfirmed=phase==Phase.WRITING;clearSession();
        status=unconfirmed?"Disconnected during head-unit change. Outcome unknown; reconnect to read current values.":"Head-unit service disconnected.";changed.run();
    }
    public void destroy(){destroyed=true;disconnect();worker.shutdown();}
}
