package com.cabin.hondacustom;

import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Main-thread state machine; one serial worker, explicit request IDs and per-connection callbacks. */
public final class DiagnosticClient {
    public enum Phase {DISCONNECTED,CONNECTING,READY,READING,CLEARING,VERIFYING}
    public interface Observer {void updated();}
    private final Context context;private final Observer observer;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Honda diagnostics");t.setDaemon(true);return t;});
    public Phase phase=Phase.DISCONNECTED;
    public String status="Connect to read Honda head-unit diagnostic records.";
    public List<DiagnosticData.HardwareError> history=Collections.emptyList();
    public List<List<String>> telematics=Collections.emptyList();
    public boolean hardwareLoaded,telematicsLoaded;
    private volatile Session session;private volatile long generation;private volatile boolean destroyed;
    private Operation operation;
    private static final class Reply {final int id;final Bundle data;final boolean cancelled;Reply(int id,Bundle data,boolean cancelled){this.id=id;this.data=data;this.cancelled=cancelled;}}
    private static final class Operation {
        final int kind;final boolean verify;volatile int id=-1;
        final List<Reply> early=new ArrayList<>();
        Operation(int kind,boolean verify){this.kind=kind;this.verify=verify;}
    }
    private final Runnable timeout=()->fail("Diagnostic request timed out. Any deletion outcome is unknown; reconnect and read before retrying.");
    private final class Session implements ServiceConnection {
        final long token;final DiagnosticProtocol.Callback callback;
        volatile DiagnosticProtocol protocol;volatile boolean initializeAttempted;boolean bound;
        Session(long token){this.token=token;callback=new DiagnosticProtocol.Callback(new DiagnosticProtocol.Events(){
            public void response(int id,Bundle data){main.post(()->{if(current(Session.this))receive(id,data);});}
            public void cancelled(int id){main.post(()->{if(current(Session.this))receiveCancel(id);});}
            public void malformed(){main.post(()->{if(current(Session.this))fail("Honda returned a malformed diagnostic callback.");});}
        });}
        public void onServiceConnected(ComponentName name,IBinder binder){
            if(!current(this))return;
            io.execute(()->{try{
                if(!current(this))return;protocol=new DiagnosticProtocol(binder);if(!current(this))return;initializeAttempted=true;protocol.initialize(callback);
                main.post(()->{if(current(this)){disarm();phase=Phase.READY;note("Diagnostic service connected. Choose which records to read.");}});
            }catch(Exception e){error(this,e);}});
        }
        public void onServiceDisconnected(ComponentName name){if(current(this))fail("Honda diagnostic service disconnected. Any deletion outcome is unknown.");}
    }
    public DiagnosticClient(Context context,Observer observer){this.context=context;this.observer=observer;}
    private boolean current(Session s){return !destroyed&&s!=null&&session==s&&generation==s.token;}
    private void note(String text){status=text;observer.updated();}
    private void arm(){main.removeCallbacks(timeout);main.postDelayed(timeout,30000);}
    private void disarm(){main.removeCallbacks(timeout);}
    private String message(Exception e){
        if(e instanceof SecurityException)return "Diagnostic permission denied: Honda requires the OEM signature permission ACCESS_DIAG_SERVICE.";
        return e.getClass().getSimpleName()+": "+(e.getMessage()==null?"Diagnostic service request failed":e.getMessage());
    }
    private void error(Session s,Exception e){main.post(()->{if(current(s))fail(message(e));});}
    public void connect(){
        if(destroyed||phase!=Phase.DISCONNECTED)return;
        Session s=new Session(++generation);session=s;phase=Phase.CONNECTING;
        note("Connecting to Honda diagnostics…");if(!current(s))return;arm();
        Intent intent=new Intent(DiagnosticProtocol.DESCRIPTOR).setComponent(new ComponentName(DiagnosticProtocol.PACKAGE,DiagnosticProtocol.SERVICE));
        try{s.bound=context.bindService(intent,s,Context.BIND_AUTO_CREATE);
            if(!current(s)){if(s.bound){s.bound=false;try{context.unbindService(s);}catch(IllegalArgumentException ignored){}}return;}
            if(!s.bound)fail("Honda diagnostic service unavailable. The original OEM service and its signature permission are required.");
        }catch(Exception e){if(current(s))fail(message(e));}
    }
    public void readHardware(){if(phase==Phase.READY)start(DiagnosticProtocol.HARDWARE_READ,false);}
    public void readTelematics(){if(phase==Phase.READY)start(DiagnosticProtocol.TELEMATICS_READ,false);}
    public boolean canClearHardware(){return phase==Phase.READY&&session!=null&&hardwareLoaded&&!history.isEmpty();}
    /** Deletes only the head-unit hardware error history, after the UI's specific confirmation. */
    public void clearHardware(boolean parked,boolean confirmed){
        if(!parked||!confirmed||!canClearHardware()){note("Read hardware history while parked and confirm its deletion first.");return;}
        start(DiagnosticProtocol.HARDWARE_CLEAR,false);
    }
    private void start(int kind,boolean verify){
        Session s=session;if(s==null)return;
        Operation op=new Operation(kind,verify);operation=op;
        phase=verify?Phase.VERIFYING:kind==DiagnosticProtocol.HARDWARE_CLEAR?Phase.CLEARING:Phase.READING;
        if(kind==DiagnosticProtocol.HARDWARE_READ){hardwareLoaded=false;history=Collections.emptyList();}
        if(kind==DiagnosticProtocol.TELEMATICS_READ){telematicsLoaded=false;telematics=Collections.emptyList();}
        note(verify?"Deletion acknowledged; reading hardware history to verify…":kind==DiagnosticProtocol.HARDWARE_CLEAR?"Deleting head-unit hardware error history…":kind==DiagnosticProtocol.HARDWARE_READ?"Reading head-unit hardware error history…":"Reading telematics DTC records…");
        if(!current(s)||operation!=op)return;arm();
        io.execute(()->{try{
            if(!current(s))return;
            op.id=kind==DiagnosticProtocol.HARDWARE_READ?s.protocol.readHardware():kind==DiagnosticProtocol.TELEMATICS_READ?s.protocol.readTelematics():s.protocol.clearHardware(true);
            main.post(()->{
                if(!current(s)||operation!=op)return;
                List<Reply> early=new ArrayList<>(op.early);op.early.clear();
                for(Reply reply:early)if(operation==op){if(reply.cancelled)receiveCancel(reply.id);else receive(reply.id,reply.data);}
            });
        }catch(Exception e){error(s,e);}});
    }
    private void receiveCancel(int id){
        Operation op=operation;if(op==null)return;
        if(op.id<0){op.early.add(new Reply(id,null,true));return;}
        if(op.id==id)fail("Honda cancelled the diagnostic request. Reconnect for fresh records.");
    }
    private void receive(int id,Bundle data){
        Operation op=operation;if(op==null)return;
        if(op.id<0){op.early.add(new Reply(id,data,false));return;}
        if(op.id!=id)return;
        try{
            if(op.kind==DiagnosticProtocol.HARDWARE_CLEAR){
                DiagnosticData.ClearProgress progress=DiagnosticData.clear(data);
                if(!progress.complete){note("Deleting hardware history: "+progress.percent+"%…");return;}
                disarm();operation=null;start(DiagnosticProtocol.HARDWARE_READ,true);return;
            }
            if(op.kind==DiagnosticProtocol.HARDWARE_READ){
                List<DiagnosticData.HardwareError> records=DiagnosticData.hardware(data);
                if(op.verify&&!records.isEmpty()){fail("Deletion was acknowledged, but readback still contains hardware errors. Reconnect to inspect current records.");return;}
                history=records;hardwareLoaded=true;
            }else{
                telematics=DiagnosticData.telematics(data);telematicsLoaded=true;
                // OEM TCU information remains a subscription (isFinished=false); stop after this snapshot.
                Session active=session;int completedId=op.id;
                io.execute(()->{try{if(current(active))active.protocol.cancel(completedId);}catch(Exception e){error(active,e);}});
            }
            disarm();operation=null;phase=Phase.READY;
            note(op.verify?"Hardware history deletion acknowledged and verified empty by readback.":op.kind==DiagnosticProtocol.HARDWARE_READ?"Hardware history read: "+history.size()+" records.":"Telematics DTC data read: "+telematics.size()+" rows.");
        }catch(RuntimeException e){fail(message(e));}
    }
    public String report(){
        StringBuilder out=new StringBuilder("Honda diagnostic readings\n").append(status).append("\n\nHead-unit hardware history (not engine OBD codes)\n");
        if(!hardwareLoaded)out.append("Not loaded\n");else if(history.isEmpty())out.append("No stored entries\n");
        for(DiagnosticData.HardwareError item:history)out.append(item.report()).append('\n');
        out.append("\nTelematics DTC records (not transmission diagnostics)\n");
        if(!telematicsLoaded)out.append("Not loaded\n");else if(telematics.isEmpty())out.append("No returned rows\n");
        for(List<String> row:telematics){for(int i=0;i<row.size();i++){if(i>0)out.append(" | ");out.append(row.get(i));}out.append('\n');}
        return out.toString();
    }
    private void fail(String text){disconnectInternal();note(text);}
    public void disconnect(){if(phase==Phase.DISCONNECTED)return;boolean write=phase==Phase.CLEARING||phase==Phase.VERIFYING;disconnectInternal();note(write?"Disconnected during deletion. Outcome unknown; reconnect and read before retrying.":"Diagnostics disconnected. Reconnect for fresh records.");}
    private void disconnectInternal(){
        disarm();Session old=session;Operation pending=operation;session=null;++generation;operation=null;
        phase=Phase.DISCONNECTED;hardwareLoaded=false;telematicsLoaded=false;history=Collections.emptyList();telematics=Collections.emptyList();
        if(old!=null){io.execute(()->{if(old.protocol!=null&&old.initializeAttempted){if(pending!=null)try{old.protocol.cancel(pending.id);}catch(Exception ignored){}
            try{old.protocol.terminate();}catch(Exception ignored){}}});
            if(old.bound){old.bound=false;try{context.unbindService(old);}catch(IllegalArgumentException ignored){}}
        }
    }
    public void destroy(){if(destroyed)return;destroyed=true;disconnectInternal();io.shutdown();}
}
