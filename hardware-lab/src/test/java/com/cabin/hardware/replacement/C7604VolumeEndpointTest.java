package com.cabin.hardware.replacement;

import android.os.IBinder;
import android.os.Parcel;
import java.io.IOException;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class C7604VolumeEndpointTest {
    static class Fixture implements AutoCloseable {
        final C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus();
        final RadioMcuTransportTest.Device device=new RadioMcuTransportTest.Device();
        final List<Boolean> muteWrites=new ArrayList<>();
        final C7604AudioSession audio=new C7604AudioSession(new C7604Writer(bus),new AmplifierMute(muteWrites::add));
        final C7604VolumeEndpoint endpoint;
        Fixture(SoundVolumeMemory.Store store) throws Exception {
            audio.start(new C7604ProgramImage(new int[] {2,0},new byte[] {1},new byte[] {2}),1,
                new C7604SourceRouting(Map.of(1,0)).resolve(1,false,-1,false,false,true),enabled->{},-20,8,8,high->{},ms->{});
            endpoint=new C7604VolumeEndpoint(audio,new RadioMcuTransport(device),audio.soundModule(Map.of()),
                new SoundVolumeMemory(3,new int[] {1,2,3},store),new C7604VolumeCurve(new int[] {-1450,-700,-600,-500}),1);
        }
        public void close() { endpoint.close(); }
    }
    @Test public void binderCommandAppliesAndPersistsVolumeAndPreservesOtherSoundFields() throws Exception {
        List<int[]> saved=new ArrayList<>();
        try(Fixture f=new Fixture(saved::add); ModuleToolkitBridge toolkit=new ModuleToolkitBridge(Map.of(4,f.endpoint))) {
            f.endpoint.activate(1,0,0,0,true,true);
            Parcel request=Parcel.obtain(),reply=Parcel.obtain(); IBinder module;
            try {
                request.writeInterfaceToken(ModuleToolkitBridge.TOOLKIT); request.writeInt(4);
                toolkit.transact(1,request,reply,0); reply.readException(); module=reply.readStrongBinder();
            } finally { request.recycle(); reply.recycle(); }
            request=Parcel.obtain(); reply=Parcel.obtain();
            try {
                request.writeInterfaceToken(ModuleToolkitBridge.MODULE); request.writeInt(0);
                request.writeIntArray(new int[] {-1}); request.writeFloatArray(null); request.writeStringArray(null);
                module.transact(1,request,reply,0); reply.readException();
            } finally { request.recycle(); reply.recycle(); }
            assertArrayEquals(new int[] {2},f.endpoint.cached(2).get(0).integers());
            assertArrayEquals(new int[] {0},f.endpoint.cached(3).get(0).integers());
            assertArrayEquals(new int[] {2,2,3},saved.get(0));
            assertArrayEquals(new int[] {8,8},f.endpoint.cached(8).get(0).integers());
            assertEquals(4,f.device.writes);
        }
    }
    @Test public void policyInvalidationBlocksCommandsAndSourceActivationRestoresOwnBank() throws Exception {
        try(Fixture f=new Fixture(levels->{})) {
            assertTrue(f.endpoint.cached(2).isEmpty());
            assertThrows(IllegalStateException.class,()->f.endpoint.command(0,ModulePayload.integers(2)));
            f.endpoint.activate(1,0,0,0,false,true); f.endpoint.invalidatePolicy();
            int writes=f.device.writes;
            assertThrows(IllegalStateException.class,()->f.endpoint.command(0,ModulePayload.integers(-1)));
            assertEquals(writes,f.device.writes); assertTrue(f.endpoint.cached(0x43).isEmpty());
            f.endpoint.activate(2,0,0,0,false,false);
            assertArrayEquals(new int[] {2},f.endpoint.cached(2).get(0).integers());
            assertThrows(UnsupportedOperationException.class,()->f.endpoint.command(0,ModulePayload.integers(-6)));
            assertFalse(f.endpoint.cached(2).isEmpty());
        }
    }
    @Test public void persistenceFailureAfterHardwareWriteRetiresStateAndMutes() throws Exception {
        try(Fixture f=new Fixture(levels->{throw new IOException("disk full");})) {
            f.endpoint.activate(1,0,0,0,false,true);
            assertThrows(IllegalStateException.class,()->f.endpoint.command(0,ModulePayload.integers(2)));
            assertTrue(f.endpoint.cached(2).isEmpty()); assertEquals(1,f.device.closes);
            assertEquals(Boolean.TRUE,f.muteWrites.get(f.muteWrites.size()-1));
            assertThrows(IllegalStateException.class,()->f.endpoint.command(0,ModulePayload.integers(1)));
        }
    }
}
