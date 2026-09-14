package com.cabin.hardware.replacement;

import android.os.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class ModuleToolkitBridgeTest {
    private IBinder module(ModuleToolkitBridge toolkit,int id) throws Exception {
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try { data.writeInterfaceToken(ModuleToolkitBridge.TOOLKIT); data.writeInt(id); assertTrue(toolkit.transact(1,data,reply,0)); reply.readException(); return reply.readStrongBinder(); }
        finally { data.recycle(); reply.recycle(); }
    }
    private void subscription(IBinder module,int code,IBinder callback,int field,int cache) throws Exception {
        Parcel data=Parcel.obtain();
        try { data.writeInterfaceToken(ModuleToolkitBridge.MODULE); data.writeStrongBinder(callback); data.writeInt(field); if(code==3) data.writeInt(cache); assertTrue(module.transact(code,data,null,IBinder.FLAG_ONEWAY)); }
        finally { data.recycle(); }
    }
    @Test public void typedGetHasPresenceMarkerAndAllThreeArrays() throws Exception {
        ModulePayload value=new ModulePayload(new int[] {7,9},new float[] {1.5f},new String[] {"Radio",null});
        ModuleToolkitBridge toolkit=new ModuleToolkitBridge(Map.of(1,new ModuleEndpoint() {
            public ModulePayload get(int code,ModulePayload args) { assertEquals(42,code); assertEquals(value,args); return value; }
        }));
        try {
            assertNull(module(toolkit,19)); IBinder module=module(toolkit,1); Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try { data.writeInterfaceToken(ModuleToolkitBridge.MODULE); data.writeInt(42); value.write(data); assertTrue(module.transact(2,data,reply,0)); reply.readException(); assertEquals(1,reply.readInt()); assertEquals(value,ModulePayload.read(reply)); assertEquals(0,reply.dataAvail()); }
            finally { data.recycle(); reply.recycle(); }
        } finally { toolkit.close(); }
    }
    @Test public void indexedCallbacksUseOneWayAndInvalidateBeforeReplacement() throws Exception {
        List<ModulePayload> values=new ArrayList<>(); values.add(ModulePayload.integers(0,8800)); values.add(ModulePayload.integers(1,10100));
        List<ModulePayload> received=new ArrayList<>();
        ModuleToolkitBridge toolkit=new ModuleToolkitBridge(Map.of(1,new ModuleEndpoint() { public List<ModulePayload> cached(int field) { return new ArrayList<>(values); } }));
        Binder callback=new Binder() {
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) {
                assertEquals(IBinder.FLAG_ONEWAY,flags); assertNull(reply); data.enforceInterface(ModuleToolkitBridge.CALLBACK); assertEquals(4,data.readInt()); received.add(ModulePayload.read(data)); return true;
            }
        };
        try {
            IBinder module=module(toolkit,1); subscription(module,3,callback,4,1); assertEquals(values,received);
            received.clear(); values.remove(0); toolkit.poll(); assertEquals(List.of(ModulePayload.EMPTY,values.get(0)),received);
            received.clear(); subscription(module,4,callback,4,0); values.clear(); toolkit.poll(); assertTrue(received.isEmpty());
        } finally { toolkit.close(); }
    }
    @Test public void oneWayCommandsCannotClaimDeliveryAndRejectionsAreCounted() throws Exception {
        CommandJournal journal=new CommandJournal(); ModuleToolkitBridge toolkit=new ModuleToolkitBridge(Map.of(1,new RadioPreviewModule(journal)));
        try {
            IBinder module=module(toolkit,1);
            for(int code:new int[] {3,999}) { Parcel data=Parcel.obtain(); try { data.writeInterfaceToken(ModuleToolkitBridge.MODULE); data.writeInt(code); ModulePayload.EMPTY.write(data); assertTrue(module.transact(1,data,null,1)); } finally { data.recycle(); } }
            assertEquals(1,journal.total()); assertEquals(1,toolkit.rejectedRequests());
        } finally { toolkit.close(); }
    }
    @Test public void malformedLengthsAreRejectedBeforeEndpointExecution() throws Exception {
        int[] calls={0}; ModuleToolkitBridge toolkit=new ModuleToolkitBridge(Map.of(7,new ModuleEndpoint() { public void command(int code,ModulePayload args) { calls[0]++; } }));
        try {
            IBinder module=module(toolkit,7); Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try {
                data.writeInterfaceToken(ModuleToolkitBridge.MODULE); data.writeInt(1); data.writeInt(Integer.MAX_VALUE);
                try { module.transact(1,data,reply,0); reply.readException(); fail(); } catch(IllegalArgumentException expected) { }
            } finally { data.recycle(); reply.recycle(); }
            assertEquals(0,calls[0]); assertEquals(1,toolkit.rejectedRequests());
        } finally { toolkit.close(); }
    }
    @Test public void payloadCopiesInputsAndRejectsNonFiniteValuesAndOversizedStrings() {
        int[] integers={1}; ModulePayload payload=ModulePayload.integers(integers); integers[0]=2; assertArrayEquals(new int[] {1},payload.integers());
        payload.integers()[0]=3; assertArrayEquals(new int[] {1},payload.integers());
        assertThrows(IllegalArgumentException.class,() -> new ModulePayload(null,new float[] {Float.NaN},null));
        assertThrows(IllegalArgumentException.class,() -> new ModulePayload(null,null,new String[] {"x".repeat(257)}));
    }
    @Test public void closeDisposesEveryEndpointOnceAndOldModuleRejectsRequests() throws Exception {
        int[] closes={0}; ModuleToolkitBridge toolkit=new ModuleToolkitBridge(Map.of(7,new ModuleEndpoint() { public void close() { closes[0]++; } }));
        IBinder module=module(toolkit,7); toolkit.close(); toolkit.close(); assertEquals(1,closes[0]); assertNull(module(toolkit,7));
        Parcel data=Parcel.obtain(); try { data.writeInterfaceToken(ModuleToolkitBridge.MODULE); data.writeInt(0); ModulePayload.EMPTY.write(data); module.transact(1,data,null,1); } finally { data.recycle(); }
        assertEquals(1,toolkit.rejectedRequests());
    }
}
