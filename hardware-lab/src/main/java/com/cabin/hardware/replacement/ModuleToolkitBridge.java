package com.cabin.hardware.replacement;

import android.os.*;
import java.util.*;

/** General observed toolkit/module wire contract. Endpoints provide actual semantics.
 * Callback delivery is one-way, as in the reference. poll() belongs on a worker.
 */
public final class ModuleToolkitBridge extends Binder implements AutoCloseable {
    public static final String TOOLKIT="com.syu.ipc.IRemoteToolkit", MODULE="com.syu.ipc.IRemoteModule", CALLBACK="com.syu.ipc.IModuleCallback";
    private final Map<Integer,ModuleBinder> modules=new TreeMap<>();
    private boolean closed;
    private long rejected;
    public ModuleToolkitBridge(Map<Integer,ModuleEndpoint> endpoints) {
        attachInterface(null,TOOLKIT);
        if(endpoints.size()>20) throw new IllegalArgumentException("Too many modules");
        endpoints.forEach((id,endpoint) -> { if(id<0 || id>19 || endpoint==null) throw new IllegalArgumentException("Invalid module"); modules.put(id,new ModuleBinder(endpoint)); });
    }
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) {
        if(code==INTERFACE_TRANSACTION) { if(reply!=null) reply.writeString(TOOLKIT); return true; }
        if(code!=1 || flags!=0 || reply==null) return false;
        bound(data); data.enforceInterface(TOOLKIT); need(data,4); int id=data.readInt(); exhausted(data);
        synchronized(this) { reply.writeNoException(); reply.writeStrongBinder(closed ? null : modules.get(id)); }
        return true;
    }
    private static void bound(Parcel data) { if(data.dataSize()>16384) throw new IllegalArgumentException("Oversized request"); }
    private static void need(Parcel data,int bytes) { if(data.dataAvail()<bytes) throw new IllegalArgumentException("Truncated request"); }
    private static void exhausted(Parcel data) { if(data.dataAvail()!=0) throw new IllegalArgumentException("Trailing request bytes"); }
    public synchronized long rejectedRequests() { return rejected; }
    public synchronized Set<Integer> moduleIds() { return new TreeSet<>(closed ? Collections.emptySet() : modules.keySet()); }
    private synchronized void reject() { rejected++; }
    private synchronized boolean isClosed() { return closed; }
    private final class Subscription implements IBinder.DeathRecipient {
        final ModuleBinder module; final IBinder binder;
        final Map<Integer,List<ModulePayload>> last=new HashMap<>();
        Subscription(ModuleBinder module,IBinder binder) { this.module=module; this.binder=binder; }
        public void binderDied() { module.remove(binder); }
    }
    private final class ModuleBinder extends Binder {
        final ModuleEndpoint endpoint;
        final Map<IBinder,Subscription> clients=new HashMap<>();
        ModuleBinder(ModuleEndpoint endpoint) { this.endpoint=endpoint; attachInterface(null,MODULE); }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code==INTERFACE_TRANSACTION) { if(reply!=null) reply.writeString(MODULE); return true; }
            boolean oneWay=flags==IBinder.FLAG_ONEWAY;
            if(code<1 || code>4 || (flags!=0 && !oneWay) || code==2 && (oneWay || reply==null) || !oneWay && reply==null) return false;
            try {
                bound(data); data.enforceInterface(MODULE);
                if(isClosed()) throw new IllegalStateException("Toolkit closed");
                if(code==1 || code==2) {
                    need(data,4); int request=data.readInt(); ModulePayload args=ModulePayload.read(data); exhausted(data);
                    if(code==1) endpoint.command(request,args);
                    else {
                        ModulePayload response=endpoint.get(request,args); reply.writeNoException(); reply.writeInt(response==null ? 0 : 1);
                        if(response!=null) response.write(reply); return true;
                    }
                } else {
                    need(data,4); IBinder callback=data.readStrongBinder(); need(data,code==3 ? 8 : 4);
                    int field=data.readInt(), cached=code==3 ? data.readInt() : 0; exhausted(data);
                    if(callback==null || field<0 || field>4095 || cached<0 || cached>1) throw new IllegalArgumentException("Invalid subscription");
                    if(code==3) register(callback,field,cached==1); else unregister(callback,field);
                }
                if(!oneWay) reply.writeNoException(); return true;
            } catch(RuntimeException ex) { reject(); if(!oneWay) throw ex; return true; }
        }
        private List<ModulePayload> values(int field) {
            List<ModulePayload> current=endpoint.cached(field);
            if(current==null || current.size()>64) throw new IllegalStateException("Invalid module cache");
            for(ModulePayload value:current) if(value==null) throw new IllegalStateException("Null cached tuple");
            return Collections.unmodifiableList(new ArrayList<>(current));
        }
        private void register(IBinder binder,int field,boolean cached) throws RemoteException {
            List<ModulePayload> current=values(field);
            synchronized(this) {
                if(isClosed()) return;
                Subscription subscription=clients.get(binder);
                if(subscription==null) {
                    if(clients.size()>=32) throw new IllegalStateException("Too many module clients");
                    subscription=new Subscription(this,binder); binder.linkToDeath(subscription,0); clients.put(binder,subscription);
                }
                if(subscription.last.size()>=128 && !subscription.last.containsKey(field)) throw new IllegalStateException("Too many subscribed fields");
                subscription.last.put(field,current);
            }
            if(cached) for(ModulePayload value:current) send(binder,field,value);
        }
        private synchronized void unregister(IBinder binder,int field) {
            Subscription subscription=clients.get(binder);
            if(subscription!=null) { subscription.last.remove(field); if(subscription.last.isEmpty()) remove(binder); }
        }
        private synchronized void remove(IBinder binder) {
            Subscription subscription=clients.remove(binder); if(subscription!=null) binder.unlinkToDeath(subscription,0);
        }
        private void send(IBinder binder,int field,ModulePayload value) {
            synchronized(this) { Subscription subscription=clients.get(binder); if(isClosed() || subscription==null || !subscription.last.containsKey(field)) return; }
            Parcel data=Parcel.obtain();
            try { data.writeInterfaceToken(CALLBACK); data.writeInt(field); value.write(data); if(!binder.transact(1,data,null,IBinder.FLAG_ONEWAY)) remove(binder); }
            catch(RemoteException | RuntimeException ex) { remove(binder); }
            finally { data.recycle(); }
        }
        void poll() {
            List<Subscription> subscriptions;
            synchronized(this) { subscriptions=new ArrayList<>(clients.values()); }
            for(Subscription subscription:subscriptions) {
                Set<Integer> fields;
                synchronized(this) { fields=new TreeSet<>(subscription.last.keySet()); }
                for(int field:fields) {
                    List<ModulePayload> next=values(field), previous;
                    synchronized(this) {
                        if(clients.get(subscription.binder)!=subscription || !subscription.last.containsKey(field)) continue;
                        previous=subscription.last.get(field); if(next.equals(previous)) continue;
                        subscription.last.put(field,next);
                    }
                    // An empty tuple invalidates the whole field before indexed values are replaced.
                    if(!previous.isEmpty()) send(subscription.binder,field,ModulePayload.EMPTY);
                    for(ModulePayload value:next) send(subscription.binder,field,value);
                }
            }
        }
        void dispose() { synchronized(this) { for(IBinder binder:new ArrayList<>(clients.keySet())) remove(binder); } endpoint.close(); }
    }
    public void poll() {
        List<ModuleBinder> current; synchronized(this) { if(closed) return; current=new ArrayList<>(modules.values()); }
        for(ModuleBinder module:current) { try { module.poll(); } catch(RuntimeException ex) { reject(); } }
    }
    @Override public void close() {
        List<ModuleBinder> current; synchronized(this) { if(closed) return; closed=true; current=new ArrayList<>(modules.values()); }
        for(ModuleBinder module:current) { try { module.dispose(); } catch(RuntimeException ex) { reject(); } }
    }
}
