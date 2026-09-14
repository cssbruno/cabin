package com.cabin.hondacustom;

import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** Serial display updates preserve fresh sibling fields and require full service readback. */
public final class DisplayClient {
    public enum Phase { DISCONNECTED, CONNECTING, READING, READY, WRITING }
    public Phase phase=Phase.DISCONNECTED;
    public String status="Connect to read the original Honda display settings.";
    public DisplayProtocol.Parameters value;
    private final Context context;private final Runnable updated;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"Honda display");thread.setDaemon(true);return thread;});
    private volatile Session active;private volatile boolean destroyed;private Runnable timeout;
    private final class Session implements ServiceConnection {
        boolean bound;volatile boolean attempted;DisplayProtocol protocol;
        @Override public void onServiceConnected(ComponentName component,IBinder binder){
            if(!current(this))return;
            phase=Phase.READING;status="Reading display settings…";updated.run();
            if(!current(this))return;
            execute(this,()->{ensure(this);protocol=new DisplayProtocol(binder);ensure(this);return protocol.read();},"Display settings loaded.");
        }
        @Override public void onServiceDisconnected(ComponentName component){if(current(this))fail(this,"Honda AV service disconnected.");}
    }
    private interface Work {DisplayProtocol.Parameters run()throws Exception;}
    public DisplayClient(Context context,Runnable updated){this.context=context;this.updated=updated;}
    private boolean current(Session session){return !destroyed&&session!=null&&active==session;}
    private void ensure(Session session)throws RemoteException{if(!current(session))throw new RemoteException("Display session ended");}
    public void connect(){
        if(destroyed||phase!=Phase.DISCONNECTED)return;
        Session session=new Session();active=session;phase=Phase.CONNECTING;value=null;status="Connecting to Honda display…";updated.run();
        if(!current(session))return;arm(session);
        try{
            session.bound=context.bindService(new Intent(DESCRIPTOR_ACTION).setComponent(new ComponentName(DisplayProtocol.PACKAGE,DisplayProtocol.SERVICE)),session,Context.BIND_AUTO_CREATE);
            if(!current(session)){unbind(session);return;}
            if(!session.bound)fail(session,"Original Honda AV service is unavailable.");
        }catch(RuntimeException e){if(current(session))fail(session,"Cannot bind Honda AV service: "+e.getMessage());}
    }
    private static final String DESCRIPTOR_ACTION=DisplayProtocol.DESCRIPTOR;
    public void refresh(){
        Session session=active;if(!current(session)||phase!=Phase.READY)return;
        phase=Phase.READING;status="Refreshing display settings…";updated.run();
        if(current(session))execute(session,()->session.protocol.read(),"Display settings refreshed.");
    }
    public void change(DisplayProtocol.Field field,int desired,boolean parked){
        Session session=active;DisplayProtocol.Parameters shown=value;
        if(!current(session)||phase!=Phase.READY)return;
        if(!parked){status="Confirm that the vehicle is parked before changing the display.";updated.run();return;}
        if(field==null||!field.allows(desired)||shown==null||!shown.writable()){status="This display value or current mode is not supported for editing.";updated.run();return;}
        phase=Phase.WRITING;status="Applying display change and checking service readback…";updated.run();
        if(!current(session))return;
        execute(session,()->{
            DisplayProtocol.Parameters baseline=session.protocol.read();ensure(session);
            if(!baseline.writable()||baseline.mode!=shown.mode||baseline.type!=shown.type)throw new RemoteException("Display mode or type changed; reconnect to read the current display before editing");
            DisplayProtocol.Parameters expected=baseline.with(field,desired);
            ensure(session);session.attempted=true;session.protocol.write(expected);ensure(session);
            DisplayProtocol.Parameters actual=session.protocol.read();
            if(!expected.same(actual))throw new RemoteException("Display readback did not confirm the requested values");
            return actual;
        },"Display change confirmed by service readback.");
    }
    private void execute(Session session,Work work,String success){
        if(!current(session))return;arm(session);
        io.execute(()->{try{
            ensure(session);DisplayProtocol.Parameters result=work.run();
            main.post(()->{
                if(!current(session))return;disarm();value=result;session.attempted=false;phase=Phase.READY;
                status=result.writable()?success:"Display settings are read only in this mode or value range. Refresh after the display changes.";updated.run();
            });
        }catch(Exception e){main.post(()->{if(current(session))fail(session,e.getClass().getSimpleName()+": "+e.getMessage());});}});
    }
    private void arm(Session session){disarm();timeout=()->{if(current(session))fail(session,"Display request timed out.");};main.postDelayed(timeout,20000);}
    private void disarm(){if(timeout!=null)main.removeCallbacks(timeout);timeout=null;}
    private void unbind(Session session){if(session.bound){session.bound=false;try{context.unbindService(session);}catch(IllegalArgumentException ignored){}}}
    private void fail(Session session,String message){if(!current(session))return;close(message+(session.attempted?" A display write was attempted; its outcome is unknown. Reconnect before retrying.":" No display change was requested."));}
    private void close(String message){Session session=active;active=null;disarm();if(session!=null)unbind(session);value=null;phase=Phase.DISCONNECTED;status=message;updated.run();}
    public void disconnect(){if(active==null&&phase==Phase.DISCONNECTED)return;Session session=active;close(session!=null&&session.attempted?"Disconnected during a display write; the outcome is unknown. Reconnect to read current values.":"Display disconnected. Reconnect for fresh values.");}
    public void destroy(){if(destroyed)return;destroyed=true;disconnect();io.shutdown();}
}
