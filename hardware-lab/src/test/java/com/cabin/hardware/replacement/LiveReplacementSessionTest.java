package com.cabin.hardware.replacement;

import android.os.IBinder;
import android.os.Parcel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class LiveReplacementSessionTest {
    static class Device implements McuTransport {
        final ReplacementRuntimeTest.Feed input = new ReplacementRuntimeTest.Feed();
        byte[] sent; boolean closed;
        public int read(byte[] buffer) throws java.io.IOException { return input.read(buffer); }
        public void close() { closed=true; input.close(); }
        @Override public void write(byte[] frame) { sent=frame.clone(); }
    }
    private IBinder radio(LiveReplacementSession session) throws Exception {
        Parcel request=Parcel.obtain(),reply=Parcel.obtain();
        try {
            request.writeInterfaceToken(ModuleToolkitBridge.TOOLKIT); request.writeInt(1);
            session.toolkit().transact(1,request,reply,0); reply.readException(); return reply.readStrongBinder();
        } finally { request.recycle(); reply.recycle(); }
    }
    private void command(IBinder module,int code) throws Exception {
        Parcel request=Parcel.obtain(),reply=Parcel.obtain();
        try {
            request.writeInterfaceToken(ModuleToolkitBridge.MODULE); request.writeInt(code); ModulePayload.EMPTY.write(request);
            module.transact(1,request,reply,0); reply.readException();
        } finally { request.recycle(); reply.recycle(); }
    }
    @Test public void binderCommandWritesOwnedTransportAndCloseRetiresOldBinder() throws Exception {
        Device device=new Device(); LiveReplacementSession session=new LiveReplacementSession(device,null,()->0L);
        IBinder module=radio(session);
        try {
            command(module,3);
            assertArrayEquals(RadioCommandPlanner.frame(1,3),device.sent);
            assertEquals(1L,session.diagnostics().get("completedSerialWrites"));
            assertEquals(true,session.diagnostics().get("transmitEnabled"));
        } finally { session.close(); }
        assertTrue(device.closed); assertEquals(false,session.diagnostics().get("transmitEnabled"));
        assertThrows(IllegalStateException.class,()->command(module,4));
        assertArrayEquals(RadioCommandPlanner.frame(1,3),device.sent);
    }
    @Test public void configuredSoundModuleAcceptsBinderCommandAndClosesWithSession() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); C7604Writer writer=new C7604Writer(bus);
        writer.loadProgram(new C7604ProgramImage(new int[] {2,0},new byte[] {1},new byte[] {2}),ms->{}); bus.writes.clear();
        C7604SoundModule sound=new C7604SoundModule(writer,java.util.Map.of(20,new C7604SoundModule.Band(1000,10)),false);
        Device device=new Device();
        try(LiveReplacementSession session=new LiveReplacementSession(device,null,()->0L,sound)) {
            Parcel request=Parcel.obtain(),reply=Parcel.obtain(); IBinder module;
            try {
                request.writeInterfaceToken(ModuleToolkitBridge.TOOLKIT); request.writeInt(4);
                session.toolkit().transact(1,request,reply,0); reply.readException(); module=reply.readStrongBinder(); assertNotNull(module);
            } finally { request.recycle(); reply.recycle(); }
            request=Parcel.obtain(); reply=Parcel.obtain();
            try {
                request.writeInterfaceToken(ModuleToolkitBridge.MODULE); request.writeInt(1); ModulePayload.integers(20,16).write(request);
                module.transact(1,request,reply,0); reply.readException();
                assertArrayEquals(C7604Equalizer.plan(20,1000,10,16).packet(),bus.writes.get(0)); assertEquals(2,bus.writes.size());
            } finally { request.recycle(); reply.recycle(); }
        }
        assertFalse(writer.programLoaded()); assertTrue(sound.cached(1).isEmpty()); assertTrue(device.closed);
    }
}
