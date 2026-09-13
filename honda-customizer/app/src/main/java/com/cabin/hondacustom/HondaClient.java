package com.cabin.hondacustom;

import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Main-thread state machine. Binder work runs off the UI thread, in order. */
public final class HondaClient {
    public enum Phase { DISCONNECTED, CONNECTING, DISCOVERING, READING, READY, WRITING, VERIFYING, ACTION, MAINTENANCE_READING, PREFERENCE_WRITING }
    public interface Observer {void updated();}
    private final Context context; private final Observer observer;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Honda Binder");t.setDaemon(true);return t;});
    public final Map<String,Setting> values=new TreeMap<>();
    public final List<String> log=new ArrayList<>();
    public Phase phase=Phase.DISCONNECTED;
    public String status="Connect on the original Honda head unit to discover supported settings.";
    private volatile long generation=0; private volatile Session session;
    private final ArrayDeque<Integer> queue=new ArrayDeque<>();
    private enum Action { TPMS, RESET, MAINTENANCE }
    private Action action;
    private volatile UnitConnection unitConnection;
    private boolean destroyed;
    private int[] maintenanceStatus;
    private boolean maintenanceReadback;
    private boolean verifyingAction;
    private int reading=-1; private Setting pending; private String afterRead;
    private final Runnable timeout=()->fail("Timed out. The outcome is unknown; reconnect and read the vehicle before retrying.");
    private static final class Request {
        volatile boolean started;
        boolean accepted;
        final List<Runnable> deferred=new ArrayList<>();
    }
    private class Session implements ServiceConnection {
        final long token; final HondaProtocol.Callback callback; final HondaProtocol.MaintenanceCallback maintenanceCallback; HondaProtocol protocol;
        boolean bound; volatile boolean mode, maintenanceMode, maintenanceRegistered, preferenceAttempted, preferenceVerified;
        volatile Request request;
        Session(long token){this.token=token;callback=new HondaProtocol.Callback(new HondaProtocol.Events(){
            public void result(int type,int result){post(()->onResult(type,result));}
            public void categories(int[] categories){post(()->onCategories(categories));}
            public void values(List<Setting> values){post(()->onValues(values));}
            private void post(Runnable r){deliver(Session.this,r);}
        });maintenanceCallback=new HondaProtocol.MaintenanceCallback(status->{final int[] copy=status==null?null:status.clone();
            deliver(Session.this,()->onMaintenanceStatus(copy));});}
        public void onServiceConnected(ComponentName name,IBinder binder){
            if(!current(this))return;
            io.execute(()->{try{
                if(!current(this))return;
                protocol=new HondaProtocol(binder);if(!current(this))return;protocol.register(callback);
                main.post(()->{if(current(this))refresh();});
            }catch(Exception e){main.post(()->{if(current(this))fail(message(e));});}});
        }
        public void onServiceDisconnected(ComponentName name){if(current(this))fail("Honda service disconnected. Reconnect to obtain fresh values.");}
    }
    private class UnitConnection implements ServiceConnection {
        final Session owner; final Catalog.Entry entry; final int value; boolean bound;
        UnitConnection(Session owner,Catalog.Entry entry,int value){this.owner=owner;this.entry=entry;this.value=value;}
        public void onServiceConnected(ComponentName name,IBinder binder){
            if(unitConnection!=this||!current(owner))return;
            io.execute(()->{try{
                if(unitConnection!=this||!current(owner))return;
                UnitInfoProtocol unit=new UnitInfoProtocol(binder);
                if(unitConnection!=this||!current(owner))return;
                owner.preferenceAttempted=true;unit.setTachometer(value);
                if(!current(owner))return;
                if(unit.getTachometer()!=value)throw new RemoteException("Head-unit preference readback mismatch");
                main.post(()->{if(unitConnection!=this||!current(owner))return;
                    owner.preferenceVerified=true;releaseUnit();disarm();phase=Phase.READY;change(entry,value,true);
                });
            }catch(Exception e){main.post(()->{if(unitConnection==this&&current(owner))fail(message(e));});}});
        }
        public void onServiceDisconnected(ComponentName name){if(unitConnection==this&&current(owner))fail("Head-unit preference service disconnected.");}
    }
    private void unbind(UnitConnection connection){if(connection.bound){connection.bound=false;try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}}}
    private void unbind(Session connection){if(connection.bound){connection.bound=false;try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}}}
    private void releaseUnit(){UnitConnection old=unitConnection;unitConnection=null;if(old!=null)unbind(old);}
    private boolean preferenceAttempted(){return session!=null&&session.preferenceAttempted;}
    private boolean preferenceVerified(){return session!=null&&session.preferenceVerified;}
    private void clearPreference(){if(session!=null){session.preferenceAttempted=false;session.preferenceVerified=false;}}
    /** System-menu equivalent: store/read back the HU preference before requesting the vehicle change. */
    public void changeSystemTachometer(Catalog.Entry entry,int value,boolean parked){
        Setting live=values.get(entry.key());
        if(!parked||phase!=Phase.READY||session==null||entry.category!=3||entry.id!=0x5c||!entry.allows(live,value)){
            note("Tachometer change unavailable. Discover live support while parked.");return;
        }
        clearPreference();phase=Phase.PREFERENCE_WRITING;
        note("Saving the head-unit tachometer preference before the vehicle change…");arm();
        UnitConnection connection=new UnitConnection(session,entry,value);unitConnection=connection;
        try{connection.bound=context.bindService(new Intent(UnitInfoProtocol.DESCRIPTOR).setComponent(new ComponentName(UnitInfoProtocol.PACKAGE,UnitInfoProtocol.SERVICE)),connection,Context.BIND_AUTO_CREATE);
            if(unitConnection!=connection||!current(connection.owner)){unbind(connection);return;}
            if(!connection.bound)fail("Original Honda UnitInfo service is unavailable.");
        }catch(Exception e){fail(message(e));}
    }
    public HondaClient(Context context,Observer observer){this.context=context;this.observer=observer;}
    private boolean current(Session s){return !destroyed&&s!=null&&session==s&&generation==s.token;}
    private void note(String message){status=message;log.add(new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+message);if(log.size()>100)log.remove(0);observer.updated();}
    private void arm(){main.removeCallbacks(timeout);main.postDelayed(timeout,20000);}
    private void disarm(){main.removeCallbacks(timeout);}
    private String message(Exception e){String m=e.getMessage();return e.getClass().getSimpleName()+": "+(m==null?"Service request failed":m);}
    private interface Work {void run(HondaProtocol p) throws Exception;}
    private void send(Work work){Session s=session;if(s==null)return;io.execute(()->{
        try{if(!current(s))return;if(s.protocol==null)throw new IllegalStateException("Honda service is not connected");work.run(s.protocol);}
        catch(Exception e){main.post(()->{if(current(s))fail(message(e));});}
    });}
    private void deliver(Session owner,Runnable event){
        final Request request=owner.request;
        if(request==null||!request.started)return;
        main.post(()->{
            if(!current(owner)||owner.request!=request)return;
            if(request.accepted)event.run();else request.deferred.add(event);
        });
    }
    private void sendRequest(Work work){sendRequest(null,work);}
    private void sendRequest(Work prepare,Work work){
        final Session owner=session;if(!current(owner))return;
        final Request request=new Request();owner.request=request;
        io.execute(()->{try{
            if(!current(owner))return;
            if(prepare!=null)prepare.run(owner.protocol);
            if(!current(owner)||owner.request!=request)return;
            request.started=true;work.run(owner.protocol);
            main.post(()->{
                if(!current(owner)||owner.request!=request)return;
                request.accepted=true;
                for(Runnable event:new ArrayList<>(request.deferred)){
                    if(!current(owner)||owner.request!=request)break;
                    event.run();
                }
                request.deferred.clear();
            });
        }catch(Exception e){main.post(()->{if(current(owner)&&owner.request==request)fail(message(e));});}});
    }
    public void connect(){
        if(destroyed||phase!=Phase.DISCONNECTED)return;
        values.clear();pending=null;queue.clear();final Session connection=new Session(++generation);session=connection;
        phase=Phase.CONNECTING;note("Connecting to Honda VehicleInfoManager…");arm();
        Intent intent=new Intent(HondaProtocol.DESCRIPTOR).setComponent(new ComponentName(HondaProtocol.PACKAGE,HondaProtocol.SERVICE));
        try{connection.bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);if(!current(connection)){unbind(connection);return;}if(!connection.bound)fail("Honda service not found. This APK requires the original Mitsubishi Electric Honda head unit.");}
        catch(Exception e){fail(message(e));}
    }
    public void refresh(){
        if(session==null||session.protocol==null||!(phase==Phase.READY||phase==Phase.CONNECTING))return;
        beginDiscovery(null);
    }
    private void beginDiscovery(String completion){
        values.clear();queue.clear();pending=null;afterRead=completion;
        phase=Phase.DISCOVERING;note("Discovering supported vehicle settings…");arm();sendRequest(HondaProtocol::discover);
    }
    private void onCategories(int[] categories){
        if(phase!=Phase.DISCOVERING)return;
        disarm();if(categories==null){fail("Honda returned no category data.");return;}
        TreeSet<Integer> sorted=new TreeSet<>();for(int c:categories)if(c>0&&c<256)sorted.add(c);
        queue.addAll(sorted);readNext();
    }
    private void readNext(){
        disarm();if(queue.isEmpty()){
            phase=Phase.READY;reading=-1;verifyingAction=false;String msg=afterRead;afterRead=null;
            note(msg==null?"Connected • "+values.size()+" live settings loaded.":msg);return;
        }
        reading=queue.removeFirst();phase=pending==null&&!verifyingAction?Phase.READING:Phase.VERIFYING;
        note((pending==null?"Reading ":"Verifying ")+Catalog.category(reading)+"…");arm();final int c=reading;sendRequest(p->p.read(c));
    }
    private void onValues(List<Setting> returned){
        if(phase!=Phase.READING&&phase!=Phase.VERIFYING)return;
        if(returned==null){fail("Honda returned an invalid setting list.");return;}
        // Reject unrelated broadcasts from another stock client; never advance on another category.
        if(!returned.isEmpty())for(Setting s:returned)if(s==null||s.category!=reading)return;
        disarm();final int category=reading;
        Iterator<Setting> old=values.values().iterator();while(old.hasNext())if(old.next().category==category)old.remove();
        for(Setting s:returned)values.put(s.key(),s);
        if(pending!=null){
            Setting actual=values.get(pending.key());
            if(actual==null||actual.value!=pending.value){fail("Readback did not confirm the requested value. Reconnect before another change.");return;}
            afterRead="Change confirmed by Honda and verified by readback."+(preferenceVerified()?" Head-unit tachometer preference also verified.":"");pending=null;clearPreference();
        }
        readNext();
    }
    public void change(Catalog.Entry entry,int value,boolean parked){
        Setting live=values.get(entry.key());
        if(!parked||phase!=Phase.READY||session==null||!entry.allows(live,value)){note("Change unavailable. Use a current supported setting while parked.");return;}
        // The System-menu flow must verify both stores even when discovery cached the desired value.
        if(live.value==value&&!preferenceVerified()){note("The vehicle already reports that value.");clearPreference();return;}
        pending=live.withValue(value);phase=Phase.WRITING;session.mode=true;
        note("Sending change; waiting for Honda confirmation…");arm();final Setting desired=pending;
        final Session active=session;
        sendRequest(p->p.mode(true),p->p.change(desired));
    }
    /** Capability comes from this connection's completed discovery, never the bundled catalog. */
    public boolean canCalibrateTpms(){
        Setting live=values.get("1:1");
        return phase==Phase.READY&&session!=null&&live!=null&&live.type==2;
    }
    public boolean canResetCustomization(){
        if(phase!=Phase.READY||session==null)return false;
        // The OEM reset targets its discovered function list, not a caller-supplied category.
        for(Setting live:values.values())if(live.type==0||live.type==1)return true;
        return false;
    }
    public Map<Integer,String> maintenanceOptions(){
        return phase==Phase.READY?HondaMaintenance.options(maintenanceStatus):Collections.emptyMap();
    }
    public void requestMaintenanceStatus(){
        if(phase!=Phase.READY||session==null)return;
        readMaintenance(false);
    }
    private void readMaintenance(boolean afterAction){
        maintenanceStatus=null;maintenanceReadback=afterAction;phase=Phase.MAINTENANCE_READING;
        note(afterAction?"Honda acknowledged maintenance reset; reading current maintenance status…":"Reading supported maintenance items…");arm();
        final Session active=session;
        sendRequest(p->{if(active.maintenanceRegistered){p.unregisterMaintenance(active.maintenanceCallback);active.maintenanceRegistered=false;}},
            p->{active.maintenanceRegistered=true;p.registerMaintenance(active.maintenanceCallback);});
    }
    private void onMaintenanceStatus(int[] status){
        if(status==null||status.length<3){if(phase==Phase.MAINTENANCE_READING)fail("Honda returned invalid maintenance status.");return;}
        maintenanceStatus=status;
        if(phase==Phase.MAINTENANCE_READING){
            disarm();phase=Phase.READY;
            note(maintenanceReadback?"Honda acknowledged maintenance reset; maintenance status reloaded. Service completion is not verified.":
                "Maintenance status loaded • "+HondaMaintenance.options(status).size()+" supported reset choices.");
            maintenanceReadback=false;
        }else observer.updated();
    }
    public void resetMaintenance(int item,boolean parked){
        if(!parked||session==null||!maintenanceOptions().containsKey(item)){
            note("Maintenance reset unavailable. Load a supported item while parked.");return;
        }
        action=Action.MAINTENANCE;phase=Phase.ACTION;session.mode=true;session.maintenanceMode=true;
        note("Requesting maintenance reset; waiting for Honda acknowledgement…");arm();final Session active=session;
        sendRequest(p->{p.mode(true);if(current(active))p.maintenanceMode(true);},p->p.resetMaintenance(item));
    }
    /** Call only after the UI's explicit action-specific confirmation. */
    public void calibrateTpms(boolean parked){startAction(Action.TPMS,parked,canCalibrateTpms());}
    /** Resets all vehicle customizations supported by the OEM service's discovery. */
    public void resetCustomization(boolean parked){startAction(Action.RESET,parked,canResetCustomization());}
    private void startAction(Action requested,boolean parked,boolean supported){
        if(!parked||!supported){note("Action unavailable. Connect and discover support while parked.");return;}
        action=requested;phase=Phase.ACTION;session.mode=true;
        note(requested==Action.TPMS?"Requesting TPMS calibration; waiting for Honda acknowledgement…":"Requesting vehicle customization defaults; waiting for Honda acknowledgement…");
        arm();final Session active=session;
        sendRequest(p->p.mode(true),p->{if(requested==Action.TPMS)p.calibrateTpms();else p.resetCustomization();});
    }
    private void onResult(int type,int result){
        if(phase==Phase.ACTION){
            // OEM sendTPMSNotifyCustomizeResult uses type 1; sendRequestCustomizeReset uses 0.
            if(action==null||type!=(action==Action.TPMS?1:action==Action.MAINTENANCE?2:0))return;
            disarm();if(result!=0){fail("Honda rejected the action ("+result+"). Reconnect before retrying.");return;}
            final Action completed=action;action=null;final Session active=session;
            send(p->{if(active.maintenanceMode){p.maintenanceMode(false);active.maintenanceMode=false;}
                p.mode(false);active.mode=false;});
            if(completed==Action.MAINTENANCE){readMaintenance(true);return;}
            verifyingAction=true;
            if(completed==Action.RESET){
                beginDiscovery("Honda acknowledged the customization reset; current settings reloaded. Default values were not independently verified.");
            }else{
                afterRead="Honda accepted the TPMS calibration request; live TPMS settings reloaded. This does not confirm calibration has finished.";
                queue.clear();queue.add(1);readNext();
            }
            return;
        }
        if(phase==Phase.WRITING&&type==3){
            disarm();if(result!=0){fail("Honda rejected the change ("+result+"). Reconnect to read current values.");return;}
            final Session active=session;
            send(p->{p.mode(false);active.mode=false;});
            queue.clear();queue.add(pending.category);readNext();
        } else if(result!=0&&(phase==Phase.DISCOVERING||phase==Phase.READING||phase==Phase.VERIFYING)) {
            fail("Honda could not complete the read (type "+type+", result "+result+").");
        }
    }
    private void fail(String message){String detail=preferenceAttempted()?" The head-unit preference may have changed; the vehicle change is not confirmed.":"";disconnectInternal();note(message+detail);}
    public void disconnect(){String detail=preferenceAttempted()?" A head-unit preference write was attempted; vehicle outcome is not confirmed.":"";disconnectInternal();note("Disconnected. Reconnect to read fresh vehicle values."+detail);}
    private void disconnectInternal(){
        disarm();releaseUnit();clearPreference();Session s=session;session=null;++generation;pending=null;action=null;verifyingAction=false;maintenanceStatus=null;maintenanceReadback=false;afterRead=null;queue.clear();reading=-1;
        phase=Phase.DISCONNECTED;values.clear();
        if(s!=null){
            io.execute(()->{if(s.protocol!=null){
                if(s.maintenanceMode)try{s.protocol.maintenanceMode(false);}catch(Exception ignored){}
                if(s.maintenanceRegistered)try{s.protocol.unregisterMaintenance(s.maintenanceCallback);}catch(Exception ignored){}
                if(s.mode)try{s.protocol.mode(false);}catch(Exception ignored){}
                try{s.protocol.unregister(s.callback);}catch(Exception ignored){}}});
            unbind(s);
        }
    }
    public void destroy(){if(destroyed)return;disconnectInternal();destroyed=true;io.shutdown();}
}
