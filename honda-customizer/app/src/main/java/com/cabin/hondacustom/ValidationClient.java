package com.cabin.hondacustom;

import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** Read-only preflight; the only vendor operations are UnitInfo identity getters. */
public final class ValidationClient {
    public enum Phase { IDLE, RUNNING, COMPLETE }
    public Phase phase=Phase.IDLE;
    public String status="Run read-only checks on the head unit to collect a compatibility report.";
    public CompatibilityReport report;
    private final Context context; private final Runnable updated;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"Honda validation");thread.setDaemon(true);return thread;});
    private volatile Session active; private volatile boolean destroyed;
    private Runnable timeout;
    private final class Session implements ServiceConnection {
        boolean bound;
        @Override public void onServiceConnected(ComponentName component,IBinder binder){
            if(!current(this))return;
            io.execute(()->{
                String model=null,modelError=null,destinationError=null;Integer destination=null;
                try{
                    if(!current(this))return;
                    UnitInfoProtocol protocol=new UnitInfoProtocol(binder);
                    if(!current(this))return;
                    try{model=protocol.getVehicleModel();}catch(Exception e){modelError=detail(e);}
                    if(!current(this))return;
                    try{destination=protocol.getDestinationCode();}catch(Exception e){destinationError=detail(e);}
                }catch(Exception e){modelError=detail(e);destinationError="UnitInfo interface could not be read";}
                final String modelValue=model,modelProblem=modelError,destinationProblem=destinationError;
                final Integer destinationValue=destination;
                main.post(()->{
                    if(!current(this))return;
                    report.model=modelValue;report.destinationCode=destinationValue;
                    report.modelError=modelProblem;report.destinationError=destinationProblem;
                    report.identityStatus=modelProblem==null&&destinationProblem==null?"READ_SUCCEEDED":modelValue!=null||destinationValue!=null?"PARTIAL_READ":"READ_FAILED";
                    complete(this,"Read-only checks finished. Package availability and identity reads do not prove vehicle behavior.");
                });
            });
        }
        @Override public void onServiceDisconnected(ComponentName component){
            if(current(this)){report.identityStatus="SERVICE_DISCONNECTED";complete(this,"Honda identity service disconnected; package findings are still available.");}
        }
    }
    public ValidationClient(Context context,Runnable updated){this.context=context;this.updated=updated;}
    private boolean current(Session session){return !destroyed&&active==session;}
    private static String detail(Exception e){return e.getClass().getSimpleName()+": "+(e.getMessage()==null?"Read unavailable":e.getMessage());}
    public void run(){
        if(destroyed||phase==Phase.RUNNING)return;
        final Session session=new Session();active=session;report=null;phase=Phase.RUNNING;
        status="Checking installed original Honda services…";updated.run();
        if(!current(session))return;
        timeout=()->{if(current(session)){
            if(report==null)report=new CompatibilityReport();report.identityStatus="TIMED_OUT";
            complete(session,"Read-only checks timed out. No setting changes were requested.");
        }};main.postDelayed(timeout,20000);
        io.execute(()->{
            try{
                if(!current(session))return;
                final CompatibilityReport collected=CompatibilityReport.collect(context);
                main.post(()->{
                    if(!current(session))return;
                    report=collected;
                    CompatibilityReport.ServiceCheck identity=report.identityService();
                    if(identity==null||!identity.canBind){report.identityStatus="PREFLIGHT_BLOCKED";complete(session,"Package checks finished. The original Honda identity service is unavailable to this app.");return;}
                    report.identityStatus="READING";status="Reading Honda model and destination identifiers…";updated.run();
                    if(!current(session))return;
                    try{
                        session.bound=context.bindService(new Intent(UnitInfoProtocol.DESCRIPTOR).setComponent(identity.component()),session,Context.BIND_AUTO_CREATE);
                        if(!current(session)){unbind(session);return;}
                        if(!session.bound){report.identityStatus="BIND_REJECTED";complete(session,"Android did not bind the Honda identity service.");}
                    }catch(RuntimeException e){
                        if(current(session)){report.identityStatus="BIND_FAILED";report.modelError=detail(e);complete(session,"Honda identity binding failed: "+detail(e));}
                    }
                });
            }catch(Exception e){main.post(()->{
                if(current(session)){report=new CompatibilityReport();report.identityStatus="PACKAGE_INSPECTION_FAILED";report.modelError=detail(e);complete(session,"Package inspection failed: "+detail(e));}
            });}
        });
    }
    private void unbind(Session session){if(session.bound){session.bound=false;try{context.unbindService(session);}catch(IllegalArgumentException ignored){}}}
    private void complete(Session session,String message){
        if(!current(session))return;active=null;unbind(session);if(timeout!=null)main.removeCallbacks(timeout);timeout=null;
        phase=Phase.COMPLETE;status=message;updated.run();
    }
    public void cancel(){
        Session session=active;if(session==null)return;
        if(report==null)report=new CompatibilityReport();report.identityStatus="CANCELLED";
        complete(session,"Read-only checks cancelled. No setting changes were requested.");
    }
    public void destroy(){
        if(destroyed)return;destroyed=true;Session session=active;active=null;
        if(timeout!=null)main.removeCallbacks(timeout);timeout=null;
        if(session!=null){unbind(session);if(report==null)report=new CompatibilityReport();report.identityStatus="CANCELLED";phase=Phase.COMPLETE;status="Read-only checks cancelled.";updated.run();}
        io.shutdown();
    }
}
