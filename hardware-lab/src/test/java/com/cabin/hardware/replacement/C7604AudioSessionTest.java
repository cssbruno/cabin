package com.cabin.hardware.replacement;
import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class C7604AudioSessionTest {
    @Test public void resolvedVolumeAppliesDspThenMcuBeforeReleasingUserMute() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); List<Boolean> muteWrites=new ArrayList<>();
        C7604AudioSession session=new C7604AudioSession(new C7604Writer(bus),new AmplifierMute(muteWrites::add));
        session.start(image(),1,route(),enabled->{},-20,8,8,high->{},ms->{}); session.userMute(true);
        int before=bus.writes.size();
        RadioMcuTransportTest.Device device=new RadioMcuTransportTest.Device() {
            @Override public void write(byte[] frame) throws IOException {
                assertEquals(before+2,bus.writes.size());
                assertEquals(writes==0 ? Boolean.TRUE : Boolean.FALSE,muteWrites.get(muteWrites.size()-1));
                super.write(frame);
            }
        };
        try(RadioMcuTransport mcu=new RadioMcuTransport(device)) {
            session.applyVolumeCommand(SoundVolumeCommand.resolve(2,1,2,1,true,true),
                new C7604VolumeCurve(new int[] {-1450,-700,-600}),0,0,0,mcu);
            assertEquals(2,device.writes); assertEquals(true,session.diagnostics().get("ready"));
        } finally { session.close(); }
    }
    @Test public void notificationFailureMutesAudioAndRetiresBothConnections() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); List<Boolean> muteWrites=new ArrayList<>();
        C7604AudioSession session=new C7604AudioSession(new C7604Writer(bus),new AmplifierMute(muteWrites::add));
        session.start(image(),1,route(),enabled->{},-20,8,8,high->{},ms->{});
        RadioMcuTransportTest.Device device=new RadioMcuTransportTest.Device(); device.fail=true;
        RadioMcuTransport mcu=new RadioMcuTransport(device);
        assertThrows(IOException.class,()->session.applyVolumeCommand(SoundVolumeCommand.resolve(2,1,2,1,false,true),
            new C7604VolumeCurve(new int[] {-1450,-700,-600}),0,0,0,mcu));
        assertFalse(mcu.isOpen()); assertEquals(false,session.diagnostics().get("ready"));
        assertEquals(List.of(true,false,true),muteWrites); assertEquals(1,device.writes); assertEquals(1,device.closes);
    }
    @Test public void volumePreservesUserMuteAndRejectsInvalidCalibrationWithoutIo() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); List<Boolean> writes=new ArrayList<>();
        try(C7604AudioSession session=new C7604AudioSession(new C7604Writer(bus),new AmplifierMute(writes::add))) {
            session.start(image(),1,route(),enabled->{},-20,8,8,high->{},ms->{});
            session.userMute(true);
            C7604VolumeCurve curve=new C7604VolumeCurve(new int[] {-1450,-700,-600});
            int before=bus.writes.size();
            assertThrows(IllegalArgumentException.class,()->session.volume(curve,3,0,0,0));
            assertEquals(before,bus.writes.size());
            assertEquals(true,session.diagnostics().get("ready"));
            session.volume(curve,2,0,0,0);
            assertEquals(before+2,bus.writes.size());
            assertEquals(C7604Gain.word(-60),session.diagnostics().get("lastAppliedGainWord"));
            assertEquals(List.of(true,false,true),writes);
        }
    }
    @Test public void failedVolumeCommitMutesAndRetiresIssuedSoundEndpoint() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); List<Boolean> writes=new ArrayList<>();
        C7604Writer writer=new C7604Writer(bus);
        C7604AudioSession session=new C7604AudioSession(writer,new AmplifierMute(writes::add));
        session.start(image(),1,route(),enabled->{},-20,8,8,high->{},ms->{});
        ModuleEndpoint endpoint=session.soundModule(Map.of());
        bus.failAt=bus.writes.size()+2;
        C7604VolumeCurve curve=new C7604VolumeCurve(new int[] {-1450,-700,-600});
        assertThrows(IOException.class,()->session.volume(curve,2,0,0,0));
        assertEquals(List.of(true,false,true),writes);
        assertFalse(writer.programLoaded()); assertTrue(endpoint.cached(8).isEmpty());
        assertNull(session.diagnostics().get("lastAppliedGainWord"));
        assertThrows(IllegalStateException.class,()->session.volume(curve,1,0,0,0));
        assertThrows(IllegalStateException.class,()->endpoint.command(3,ModulePayload.integers(8,8)));
        session.close(); assertEquals(List.of(true,false,true),writes);
    }
    private C7604ProgramImage image() { return new C7604ProgramImage(new int[] {2,0},new byte[] {1},new byte[] {2}); }
    private C7604SourceRouting.Plan route() { return new C7604SourceRouting(Map.of(1,0)).resolve(1,false,-1,false,false,true); }
    @Test public void holdsMuteUntilAllConfigurationCompletesAndPreservesUserMuteAcrossRouting() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); List<Boolean> writes=new ArrayList<>();
        AmplifierMute mute=new AmplifierMute(value->{if(!value) { assertEquals(0x86,bus.writes.get(bus.writes.size()-1)[2]&255); } writes.add(value);});
        try(C7604AudioSession session=new C7604AudioSession(new C7604Writer(bus),mute)) {
            session.start(image(),1,route(),enabled->assertEquals(Boolean.TRUE,mute.lastApplied()),-20,8,8,
                high->assertEquals(Boolean.TRUE,mute.lastApplied()),ms->{});
            assertEquals(List.of(true,false),writes); assertEquals(true,session.diagnostics().get("ready"));
            session.userMute(true); session.route(route(),enabled->assertEquals(Boolean.TRUE,mute.lastApplied()));
            assertEquals(List.of(true,false,true),writes);
        }
        assertEquals(Boolean.TRUE,mute.lastApplied());
    }
    @Test public void failedRoutingNeverReleasesStartupMuteAndClosesWriter() {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); C7604Writer writer=new C7604Writer(bus); List<Boolean> writes=new ArrayList<>();
        AmplifierMute mute=new AmplifierMute(writes::add); C7604AudioSession session=new C7604AudioSession(writer,mute);
        assertThrows(IOException.class,()->session.start(image(),1,route(),enabled->{throw new IOException("platform failed");},-20,8,8,high->{},ms->{}));
        assertEquals(List.of(true),writes); assertFalse(writer.programLoaded()); assertTrue(mute.reasons().contains(AmplifierMute.Reason.FAULT));
        assertEquals(false,session.diagnostics().get("ready"));
    }
    @Test public void closingIssuedEndpointMutesAndRetiresTheAudioSession() throws Exception {
        C7604ProgramTest.Bus bus=new C7604ProgramTest.Bus(); List<Boolean> writes=new ArrayList<>();
        C7604AudioSession session=new C7604AudioSession(new C7604Writer(bus),new AmplifierMute(writes::add));
        session.start(image(),1,route(),enabled->{},-20,8,8,high->{},ms->{});
        ModuleEndpoint endpoint=session.soundModule(Map.of(20,new C7604SoundModule.Band(1000,10)));
        assertArrayEquals(new int[] {8,8},endpoint.cached(8).get(0).integers());
        endpoint.command(1,ModulePayload.integers(20,16)); endpoint.close();
        assertEquals(List.of(true,false,true),writes); assertTrue(endpoint.cached(1).isEmpty());
        assertThrows(IllegalStateException.class,()->endpoint.command(3,ModulePayload.integers(8,8)));
    }
}
