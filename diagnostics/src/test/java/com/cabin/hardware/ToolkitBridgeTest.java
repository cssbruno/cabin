package com.cabin.hardware;

import android.os.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ToolkitBridgeTest {
    private SimulatedBackend backend;
    private ToolkitBridge toolkit;
    private IBinder can;
    @Before public void setup() throws Exception {
        backend = new SimulatedBackend(); toolkit = new ToolkitBridge(backend); can = module(7);
    }
    @After public void close() { toolkit.close(); }
    private IBinder module(int id) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ToolkitBridge.TOOLKIT); data.writeInt(id);
            assertTrue(toolkit.transact(1, data, reply, 0));
            reply.readException(); return reply.readStrongBinder();
        } finally { data.recycle(); reply.recycle(); }
    }
    private void subscribe(IBinder callback, int code, boolean register, boolean cached) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ToolkitBridge.MODULE); data.writeStrongBinder(callback); data.writeInt(code);
            if (register) data.writeInt(cached ? 1 : 0);
            assertTrue(can.transact(register ? 3 : 4, data, reply, 0));
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }
    private static final class Client extends Binder {
        final List<Integer> readings = new ArrayList<>();
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            assertEquals(1, code); assertEquals(0, flags); assertNotNull(reply);
            data.enforceInterface(ToolkitBridge.CALLBACK);
            assertEquals(37, data.readInt());
            readings.add(data.createIntArray()[0]);
            assertNull(data.createFloatArray()); assertNull(data.createStringArray());
            assertEquals(0, data.dataAvail()); reply.writeNoException(); return true;
        }
    }
    @Test public void clientReceivesCachedAndChangedFieldsThenUnsubscribes() throws Exception {
        Client client = new Client(); subscribe(client, 37, true, true);
        assertEquals(List.of(0), client.readings);
        backend.toggleDoor(); assertEquals(List.of(0, 1), client.readings);
        subscribe(client, 37, false, false); backend.toggleDoor();
        assertEquals(List.of(0, 1), client.readings); assertEquals(0, toolkit.subscriberCount());
    }
    @Test public void absentFieldsAndUnsupportedModulesNeverFabricateData() throws Exception {
        Client client = new Client(); subscribe(client, 137, true, true);
        backend.toggleDoor(); assertTrue(client.readings.isEmpty());
        assertNull(module(4)); assertNull(module(-1));
        assertFalse(backend.snapshot().containsKey(137));
    }
    @Test public void duplicateRegistrationDoesNotDuplicateUpdates() throws Exception {
        Client client = new Client(); subscribe(client, 37, true, false); subscribe(client, 37, true, false);
        backend.toggleDoor(); assertEquals(List.of(1), client.readings); assertEquals(1, toolkit.subscriberCount());
    }
    @Test public void commandIsRejectedWithoutChangingSimulatedState() throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ToolkitBridge.MODULE); data.writeInt(105);
            data.writeIntArray(new int[]{44, 1}); data.writeFloatArray(null); data.writeStringArray(null);
            assertTrue(can.transact(1, data, reply, 0));
            reply.readException();
            assertEquals(1, toolkit.rejectedCommands()); assertEquals(Integer.valueOf(0), backend.snapshot().get(37));
        } finally { data.recycle(); reply.recycle(); }
    }
    @Test public void closingDisconnectsClientsAndStopsUpdates() throws Exception {
        Client client = new Client(); subscribe(client, 37, true, false);
        toolkit.close(); backend.toggleDoor();
        assertEquals(0, toolkit.subscriberCount()); assertTrue(client.readings.isEmpty()); assertNull(module(7));
    }
    @Test public void malformedHugeCommandArrayIsRejectedBeforeAllocation() throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ToolkitBridge.MODULE); data.writeInt(105); data.writeInt(Integer.MAX_VALUE);
            can.transact(1, data, reply, 0);
            assertThrows(RuntimeException.class, reply::readException);
            assertEquals(0, toolkit.rejectedCommands());
        } finally { data.recycle(); reply.recycle(); }
    }
    @Test public void truncatedCommandIsRejected() throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ToolkitBridge.MODULE); data.writeInt(105);
            can.transact(1, data, reply, 0);
            assertThrows(RuntimeException.class, reply::readException);
            assertEquals(0, toolkit.rejectedCommands());
        } finally { data.recycle(); reply.recycle(); }
    }
    @Test public void failedCallbackIsRemoved() throws Exception {
        Binder deadClient = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) { return false; }
        };
        subscribe(deadClient, 37, true, true);
        assertEquals(0, toolkit.subscriberCount());
    }
    @Test public void exceptionFromSynchronousCallbackRemovesClient() throws Exception {
        Binder client = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                assertEquals(0, flags);
                assertNotNull(reply);
                reply.writeException(new SecurityException("Callback rejected"));
                return true;
            }
        };
        subscribe(client, 37, true, true);
        assertEquals(0, toolkit.subscriberCount());
    }
    @Test public void emptyCallbackReplyRemovesClient() throws Exception {
        Binder client = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) { return true; }
        };
        subscribe(client, 37, true, true);
        assertEquals(0, toolkit.subscriberCount());
    }

}
