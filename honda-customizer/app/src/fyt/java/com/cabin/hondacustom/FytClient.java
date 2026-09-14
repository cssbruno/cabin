package com.cabin.hondacustom;

import android.content.*;
import android.content.pm.ResolveInfo;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

final class FytClient {
    static final long FRESH_MS=30000, TIMEOUT_MS=8000;
    final Map<Integer,Integer> values=new HashMap<>();
    private final Map<Integer,Long> received=new HashMap<>();
    private final Context context; private final Runnable observer;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService[] workers={Executors.newSingleThreadExecutor(),Executors.newSingleThreadExecutor()};
    private volatile Session session;
    private volatile int profile;
    private boolean closed;
    private int lastProfile;
    private final List<String> trace=new ArrayList<>();
    private volatile Write pending;
    private static final class Write {
        final FytProtocol.Control control; final int value; final long baselineTime;
        volatile boolean started;
        boolean accepted; Integer feedback;
        Write(FytProtocol.Control control,int value,long baselineTime){this.control=control;this.value=value;this.baselineTime=baselineTime;}
    }
    String status="Disconnected · FYT service only";
    FytClient(Context context,Runnable observer){this.context=context;this.observer=observer;}
    boolean connected(){return session!=null;}
    int profile(){return profile;}
    boolean busy(){return pending!=null;}
    private final class Session implements ServiceConnection {
        final boolean direct; final ExecutorService worker;
        Session(boolean direct){this.direct=direct;worker=workers[direct?1:0];}
        volatile IBinder module; boolean bound,ready;
        final List<Integer> registered=new ArrayList<>();
        final IBinder callback=new Binder(){
            {attachInterface(null,FytProtocol.CALLBACK);}
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
                if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(FytProtocol.CALLBACK);return true;}
                if(code!=1)return super.onTransact(code,data,reply,flags);
                data.enforceInterface(FytProtocol.CALLBACK);
                if(data.dataAvail()<8||data.dataAvail()>4096)return false;
                int field=data.readInt(),count=data.readInt();
                if(count>16||count< -1||count>data.dataAvail()/4)return false;
                if(count>0){int raw=data.readInt();long arrived=SystemClock.elapsedRealtime();
                    Write request=pending;
                    // Record ownership at callback arrival, not when the UI drains its queue.
                    Write observed=request!=null&&request.started?request:null;
                    main.post(()->update(Session.this,field,raw,arrived,observed));}
                if(reply!=null)reply.writeNoException();return true;
            }
        };
        @Override public void onServiceConnected(ComponentName name,IBinder toolkit){
            if(session!=this)return;
            worker.execute(()->{try{
                if(direct){if(!FytProtocol.MODULE.equals(toolkit.getInterfaceDescriptor()))throw new RemoteException("Unexpected direct CANBUS interface");module=toolkit;}
                else module=FytProtocol.module(toolkit);
                if(module==null)throw new RemoteException("FYT CANBUS module 7 unavailable");
                if(session!=this)return;
                registered.add(FytProtocol.PROFILE);FytProtocol.register(module,callback,FytProtocol.PROFILE,true);
            }catch(Exception e){main.post(()->connectionFailed(this,"FYT connection failed: "+e.getMessage()));}});
        }
        @Override public void onServiceDisconnected(ComponentName name){fail(this,"FYT service disconnected");}
        @Override public void onBindingDied(ComponentName name){fail(this,"FYT binding ended; reconnect");}
        @Override public void onNullBinding(ComponentName name){connectionFailed(this,"FYT service unavailable");}
    }
    void connect(){
        if(closed)return;trace.clear();lastProfile=0;disconnect();bindRoute(false);
    }
    private void bindRoute(boolean direct){
        if(closed)return;
        Session owner=new Session(direct);session=owner;status=direct?"Connecting to FYT direct CANBUS…":"Connecting to FYT toolkit…";observer.run();
        Intent intent=new Intent(direct?"com.syu.ms.canbus":"com.syu.ms.toolkit").setPackage("com.syu.ms");
        ResolveInfo resolved=null;
        try{resolved=context.getPackageManager().resolveService(intent,0);}catch(RuntimeException ignored){}
        ComponentName target=new ComponentName("com.syu.ms",direct?"app.ModuleService":"app.ToolkitService");
        if(resolved!=null&&resolved.serviceInfo!=null&&"com.syu.ms".equals(resolved.serviceInfo.packageName)&&resolved.serviceInfo.exported&&resolved.serviceInfo.enabled)
            target=new ComponentName(resolved.serviceInfo.packageName,resolved.serviceInfo.name);
        intent.setComponent(target);
        trace.add("Bind: "+intent.getAction()+" → "+target.flattenToShortString());
        try{owner.bound=context.bindService(intent,owner,Context.BIND_AUTO_CREATE);
            // A synchronous binding callback may already have disconnected this owner.
            if(session!=owner){releaseBinding(owner);return;}
            if(!owner.bound){connectionFailed(owner,"FYT service unavailable on this unit");return;}
        }catch(RuntimeException e){connectionFailed(owner,"FYT connection denied: "+e.getMessage());return;}
        main.postDelayed(()->{if(session==owner&&!owner.ready)connectionFailed(owner,"FYT discovery did not finish; reconnect");},TIMEOUT_MS);
    }
    private void connectionFailed(Session owner,String message){
        if(session!=owner)return;
        if(owner.direct){fail(owner,message);return;}
        trace.add(message);disconnect();bindRoute(true);
    }
    private void update(Session owner,int field,int raw,long arrived,Write observed){
        if(session!=owner)return;
        if(field==FytProtocol.PROFILE){
            if(profile==raw&&profile!=0)return;
            if(profile!=0&&profile!=raw){fail(owner,"FYT profile changed; reconnect to reload settings");return;}
            profile=raw;lastProfile=raw;
            trace.add(String.format(Locale.US,"FYT profile: 0x%X (%d), %s",raw,raw,FytProtocol.family(raw)));
            if(!FytProtocol.supported(raw)){owner.ready=true;status=String.format(Locale.US,"FYT connected · profile 0x%X has no mapped controls yet. Copy the Report.",raw);observer.run();return;}
            status=String.format(Locale.US,"Loading FYT settings · profile 0x%X",raw);observer.run();
            if(session!=owner||closed)return;
            owner.worker.execute(()->{try{
                // The profile is established before registration requests cached setting values.
                for(FytProtocol.Control c:FytProtocol.CONTROLS){if(session!=owner)return;if(!FytProtocol.visible(raw,c))continue;owner.registered.add(c.field);FytProtocol.register(owner.module,owner.callback,c.field,true);}
                main.post(()->{if(session==owner){owner.ready=true;status=String.format(Locale.US,"FYT connected · profile 0x%X",raw);observer.run();}});
            }catch(Exception e){main.post(()->connectionFailed(owner,"FYT setting discovery failed: "+e.getMessage()));}});
            return;
        }
        for(FytProtocol.Control c:FytProtocol.CONTROLS)if(c.field==field&&FytProtocol.visible(profile,c)){
            Integer value=c.decode(raw);
            if(value==null){values.remove(field);received.remove(field);}else{values.put(field,value);received.put(field,arrived);}
            if(observed!=null&&pending==observed&&observed.control==c){observed.feedback=value;finishIfConfirmed(observed);}
            observer.run();
            main.postDelayed(()->{if(session==owner)observer.run();},FRESH_MS+1);return;
        }
    }
    boolean editable(FytProtocol.Control c){
        Session s=session;Long time=received.get(c.field);
        return s!=null&&s.ready&&s.module!=null&&pending==null&&FytProtocol.visible(profile,c)&&values.containsKey(c.field)&&time!=null&&SystemClock.elapsedRealtime()-time<FRESH_MS;
    }
    void change(FytProtocol.Control c,int value,boolean parked){
        if(!parked||!editable(c)||value<0||value>=c.options.length)return;
        final Session owner=session;final int expectedProfile=profile;
        final Write request=new Write(c,value,received.get(c.field));
        pending=request;status="Sending "+c.title+" to FYT…";observer.run();
        if(closed||session!=owner||pending!=request)return;
        owner.worker.execute(()->{
            if(session!=owner||profile!=expectedProfile||pending!=request)return;
            if(SystemClock.elapsedRealtime()-request.baselineTime>=FRESH_MS){main.post(()->fail(owner,"FYT value expired before sending; reconnect"));return;}
            try{FytProtocol.change(owner.module,expectedProfile,c,value,()->{
                    // Descriptor lookup is also IPC and may block. Recheck immediately before dispatch.
                    if(session!=owner||profile!=expectedProfile||pending!=request||SystemClock.elapsedRealtime()-request.baselineTime>=FRESH_MS)
                        throw new IllegalStateException("FYT request expired or disconnected before sending");
                    request.started=true;
                });
                main.post(()->{if(session==owner&&pending==request){request.accepted=true;status="Waiting for FYT feedback: "+c.title;finishIfConfirmed(request);observer.run();}});
            }catch(Exception e){main.post(()->fail(owner,"FYT write failed; outcome unconfirmed: "+e.getMessage()));}
        });
        main.postDelayed(()->{if(session==owner&&pending==request)fail(owner,"No matching FYT feedback; change unconfirmed. Reconnect to read current values.");},TIMEOUT_MS);
    }
    private void finishIfConfirmed(Write request){
        if(pending==request&&request.accepted&&request.feedback!=null&&request.feedback==request.value){pending=null;status=request.control.title+": confirmed by FYT feedback";}
    }
    private void releaseBinding(Session owner){if(owner.bound){owner.bound=false;try{context.unbindService(owner);}catch(RuntimeException ignored){}}}
    private void fail(Session owner,String message){if(session!=owner)return;trace.add(message);disconnect();status=message;observer.run();}
    String report(){
        StringBuilder out=new StringBuilder("Honda Customizer · FYT\n");
        out.append("Unit: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\nAndroid: ").append(Build.VERSION.RELEASE).append("\n");
        try{android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo("com.syu.ms",0);out.append("FYT service: ").append(info.versionName).append(" / ").append(info.versionCode).append("\n");}catch(Exception e){out.append("FYT package not found\n");}
        out.append("Status: ").append(status).append(String.format(Locale.US,"\nLast profile: 0x%X (%d), %s\n",lastProfile,lastProfile,FytProtocol.family(lastProfile)));
        for(String line:trace)out.append(line).append('\n');
        for(FytProtocol.Control c:FytProtocol.CONTROLS)if(FytProtocol.visible(profile,c))out.append(c.title).append(" [").append(c.field).append("]: ").append(values.containsKey(c.field)?c.options[values.get(c.field)]:"no feedback").append('\n');
        return out.toString();
    }
    void disconnect(){
        boolean unconfirmed=pending!=null;
        Session old=session;session=null;profile=0;pending=null;values.clear();received.clear();main.removeCallbacksAndMessages(null);
        if(old!=null){
            releaseBinding(old);
            old.worker.execute(()->{if(old.module!=null)for(int field:old.registered)try{FytProtocol.register(old.module,old.callback,field,false);}catch(Exception ignored){}});
        }
        status=unconfirmed?"Disconnected · last change unconfirmed; reconnect to read values":"Disconnected · FYT service only";observer.run();
    }
    void destroy(){if(closed)return;closed=true;disconnect();for(ExecutorService worker:workers)worker.shutdown();}
}
