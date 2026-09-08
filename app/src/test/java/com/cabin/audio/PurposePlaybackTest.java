package com.cabin.audio;

import android.content.Context;
import android.media.AudioTrack;
import androidx.test.core.app.ApplicationProvider;
import com.cabin.platform.AudioConfig;
import com.cabin.protocol.AudioRoutingState;
import com.cabin.protocol.StreamPurpose;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAudioTrack;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, shadows = PurposePlaybackTest.ScriptedAudioTrack.class)
public class PurposePlaybackTest {
    private DualStreamAudioManager manager;
    private Object playback;
    private Method drain;

    @Before
    public void setUp() throws Exception {
        ScriptedAudioTrack.results.clear();
        ScriptedAudioTrack.writes.clear();
        ScriptedAudioTrack.writeEntered = null;
        ScriptedAudioTrack.allowWrite = null;
        Context context = ApplicationProvider.getApplicationContext();
        manager = new DualStreamAudioManager(context, message -> {}, AudioConfig.Companion.getDEFAULT());
        Class<?> type = Class.forName(DualStreamAudioManager.class.getName() + "$AudioPlaybackThread");
        Constructor<?> constructor = type.getDeclaredConstructor(DualStreamAudioManager.class);
        constructor.setAccessible(true);
        playback = constructor.newInstance(manager);
        field("playbackThread").set(manager, playback);
        ((AtomicBoolean) field("isRunning").get(manager)).set(true);
        drain = type.getDeclaredMethod("processPurposeSlots");
        drain.setAccessible(true);
        createSlot("siriSlot", StreamPurpose.SIRI, AudioFormats.INSTANCE.getFORMAT_5());
        createSlot("phoneCallSlot", StreamPurpose.PHONE_CALL, AudioFormats.INSTANCE.getFORMAT_5());
        createSlot("alertSlot", StreamPurpose.ALERT, AudioFormats.INSTANCE.getFORMAT_4());
    }

    @After
    public void tearDown() {
        if (ScriptedAudioTrack.allowWrite != null) ScriptedAudioTrack.allowWrite.countDown();
        manager.release();
    }

    @Test
    public void siriStopSerializesWithInFlightPrefill() throws Exception {
        assertStopSerializes("siriSlot", new AudioRoutingState(true, false, false), manager::stopSiriTrack);
    }

    @Test
    public void callStopSerializesWithInFlightPrefill() throws Exception {
        assertStopSerializes("phoneCallSlot", new AudioRoutingState(false, true, false), manager::stopPhoneCallTrack);
    }

    private void assertStopSerializes(String slotName, AudioRoutingState routing, Runnable stop) throws Exception {
        byte[] audio = pcm(80, 16000, 1, (byte) 0x31);
        write(audio, 5, routing);
        AudioTrack track = track(slotName);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allow = new CountDownLatch(1);
        CountDownLatch stopEntered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        ScriptedAudioTrack.writeEntered = entered;
        ScriptedAudioTrack.allowWrite = allow;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { drain.invoke(playback); } catch (Throwable error) { failure.set(error); }
        });
        Thread stopper = new Thread(() -> {
            stopEntered.countDown();
            try { stop.run(); } catch (Throwable error) { failure.set(error); }
            finally { stopped.countDown(); }
        });
        writer.start();
        try {
            assertTrue("Playback must reach the native write", entered.await(2, TimeUnit.SECONDS));
            stopper.start();
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS));
            assertFalse("STOP cannot complete while the worker can still call play", stopped.await(100, TimeUnit.MILLISECONDS));
        } finally {
            allow.countDown();
            writer.join(2000);
            if (stopper.getState() != Thread.State.NEW) stopper.join(2000);
        }
        assertFalse(writer.isAlive());
        assertFalse(stopper.isAlive());
        assertNull(failure.get());
        assertNotEquals(AudioTrack.PLAYSTATE_PLAYING, track.getPlayState());
        ScriptedAudioTrack.writes.clear();
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        drain.invoke(playback);
        assertNotEquals(AudioTrack.PLAYSTATE_PLAYING, track.getPlayState());
        assertTrue("Ended voice sessions must not write idle silence", ScriptedAudioTrack.writes.isEmpty());
    }

    @Test
    public void shortAlertsFinishIndependentlyAtStop() throws Exception {
        manager.onAlertStarted();
        byte[] first = pcm(50, 48000, 2, (byte) 0x31);
        writeAlert(first);
        drain.invoke(playback);
        assertTrue(ScriptedAudioTrack.writes.isEmpty());
        manager.onAlertStopped();
        drain.invoke(playback);
        assertEquals(1, ScriptedAudioTrack.writes.size());
        assertArrayEquals(first, ScriptedAudioTrack.writes.get(0));
        assertEquals(AudioTrack.PLAYSTATE_STOPPED, track("alertSlot").getPlayState());

        ScriptedAudioTrack.writes.clear();
        manager.onAlertStarted();
        byte[] second = pcm(50, 48000, 2, (byte) 0x42);
        writeAlert(second);
        manager.onAlertStopped();
        drain.invoke(playback);
        assertEquals(1, ScriptedAudioTrack.writes.size());
        assertArrayEquals(second, ScriptedAudioTrack.writes.get(0));
    }

    @Test
    public void shortAlertStartsWithinBoundWithoutStopCommand() throws Exception {
        manager.onAlertStarted();
        byte[] audio = pcm(20, 48000, 2, (byte) 0x31);
        writeAlert(audio);
        drain.invoke(playback);
        assertTrue(ScriptedAudioTrack.writes.isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofMillis(81));
        drain.invoke(playback);
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track("alertSlot").getPlayState());
        assertArrayEquals(audio, ScriptedAudioTrack.writes.get(0));
    }

    @Test
    public void nextAlertDiscardsUnfinishedPartialWrite() throws Exception {
        manager.onAlertStarted();
        byte[] oldAudio = pcm(80, 48000, 2, (byte) 0x31);
        ScriptedAudioTrack.results.add(100);
        ScriptedAudioTrack.results.add(0);
        writeAlert(oldAudio);
        drain.invoke(playback);
        manager.onAlertStopped();
        manager.onAlertStarted();
        ScriptedAudioTrack.writes.clear();
        byte[] newAudio = pcm(50, 48000, 2, (byte) 0x42);
        writeAlert(newAudio);
        manager.onAlertStopped();
        drain.invoke(playback);
        assertEquals(1, ScriptedAudioTrack.writes.size());
        assertArrayEquals(newAudio, ScriptedAudioTrack.writes.get(0));
    }

    @Test
    public void subMillisecondAlertAndTrailingFrameBothFinish() throws Exception {
        byte[] frame = new byte[] {0x31, 0x31, 0x31, 0x31};
        manager.onAlertStarted();
        writeAlert(frame);
        manager.onAlertStopped();
        drain.invoke(playback);
        assertEquals(1, ScriptedAudioTrack.writes.size());
        assertArrayEquals(frame, ScriptedAudioTrack.writes.get(0));
        assertEquals(AudioTrack.PLAYSTATE_STOPPED, track("alertSlot").getPlayState());

        manager.onAlertStarted();
        writeAlert(pcm(80, 48000, 2, (byte) 0x42));
        drain.invoke(playback);
        ScriptedAudioTrack.writes.clear();
        writeAlert(frame);
        manager.onPurposeChanged(StreamPurpose.ALERT);
        manager.onAlertStopped();
        drain.invoke(playback);
        assertEquals(1, ScriptedAudioTrack.writes.size());
        assertArrayEquals(frame, ScriptedAudioTrack.writes.get(0));
        assertEquals(AudioTrack.PLAYSTATE_STOPPED, track("alertSlot").getPlayState());
        assertTrue(((java.util.Map<?, ?>) field("activeFocusRequests").get(manager)).isEmpty());
    }

    @Test
    public void emptyAlertStopAbandonsFocusImmediately() throws Exception {
        manager.onAlertStarted();
        manager.onPurposeChanged(StreamPurpose.ALERT);
        manager.onAlertStopped();
        assertTrue(((java.util.Map<?, ?>) field("activeFocusRequests").get(manager)).isEmpty());
    }

    private void createSlot(String name, StreamPurpose purpose, AudioFormatConfig format) throws Exception {
        Method create = DualStreamAudioManager.class.getDeclaredMethod("createPurposeSlot", StreamPurpose.class, AudioFormatConfig.class);
        create.setAccessible(true);
        field(name).set(manager, create.invoke(manager, purpose, format));
    }

    private AudioTrack track(String name) throws Exception {
        Object slot = field(name).get(manager);
        Field track = slot.getClass().getDeclaredField("track");
        track.setAccessible(true);
        return (AudioTrack) track.get(slot);
    }

    private void writeAlert(byte[] bytes) {
        write(bytes, 4, new AudioRoutingState(false, false, true));
    }

    private void write(byte[] bytes, int decodeType, AudioRoutingState routing) {
        manager.writeAudio(bytes, 0, bytes.length, AudioStreamType.MEDIA, decodeType, routing);
    }

    private static byte[] pcm(int ms, int rate, int channels, byte sample) {
        byte[] bytes = new byte[ms * rate * channels * 2 / 1000];
        Arrays.fill(bytes, sample);
        return bytes;
    }

    private static Field field(String name) throws Exception {
        Field field = DualStreamAudioManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Implements(AudioTrack.class)
    public static class ScriptedAudioTrack extends ShadowAudioTrack {
        static final ArrayDeque<Integer> results = new ArrayDeque<>();
        static final List<byte[]> writes = new ArrayList<>();
        static volatile CountDownLatch writeEntered;
        static volatile CountDownLatch allowWrite;

        @Implementation
        protected static int native_get_min_buff_size(int sampleRate, int channelCount, int format) { return 1024; }

        @Implementation
        protected int native_write_byte(byte[] data, int offset, int size, int format, boolean blocking) {
            CountDownLatch entered = writeEntered;
            CountDownLatch allow = allowWrite;
            if (entered != null) {
                writeEntered = null;
                entered.countDown();
                try {
                    if (!allow.await(3, TimeUnit.SECONDS)) throw new AssertionError("Test write was not released");
                } catch (InterruptedException error) { throw new AssertionError(error); }
            }
            int accepted = results.isEmpty() ? size : Math.min(results.remove(), size);
            if (accepted > 0) writes.add(Arrays.copyOfRange(data, offset, offset + accepted));
            return accepted;
        }
    }
}
