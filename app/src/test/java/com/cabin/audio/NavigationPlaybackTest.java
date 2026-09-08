package com.cabin.audio;

import android.content.Context;
import android.media.AudioTrack;

import androidx.test.core.app.ApplicationProvider;

import com.cabin.platform.AudioConfig;
import com.cabin.protocol.AudioRoutingState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAudioTrack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, shadows = NavigationPlaybackTest.ScriptedAudioTrack.class)
public class NavigationPlaybackTest {
    private DualStreamAudioManager manager;
    private Object playback;
    private Method drain;

    @Before
    public void setUp() throws Exception {
        ScriptedAudioTrack.results.clear();
        ScriptedAudioTrack.writes.clear();
        ScriptedAudioTrack.failCreation = false;
        Context context = ApplicationProvider.getApplicationContext();
        manager = new DualStreamAudioManager(context, message -> {}, AudioConfig.Companion.getDEFAULT());
        // Drive the real playback pass explicitly so prompt boundaries and partial
        // writes have deterministic ordering rather than relying on sleep timings.
        Class<?> playbackClass = Class.forName(DualStreamAudioManager.class.getName() + "$AudioPlaybackThread");
        Constructor<?> constructor = playbackClass.getDeclaredConstructor(DualStreamAudioManager.class);
        constructor.setAccessible(true);
        playback = constructor.newInstance(manager);
        field("playbackThread").set(manager, playback);
        ((AtomicBoolean) field("isRunning").get(manager)).set(true);
        drain = playbackClass.getDeclaredMethod("processNavigation");
        drain.setAccessible(true);
    }

    @After
    public void tearDown() {
        manager.release();
    }

    @Test
    public void promptAfterCancelledPrefillStartsOnSameTrack() throws Exception {
        write(pcm(20, (byte) 0x31));
        drain.invoke(playback);
        AudioTrack original = track();
        assertEquals(AudioTrack.PLAYSTATE_STOPPED, original.getPlayState());

        manager.stopNavTrack();
        manager.onNavStarted();
        byte[] nextPrompt = pcm(40, (byte) 0x42);
        write(nextPrompt);
        drain.invoke(playback);

        assertSame(original, track());
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track().getPlayState());
        assertEquals(1, ScriptedAudioTrack.writes.size());
        assertArrayEquals(nextPrompt, ScriptedAudioTrack.writes.get(0));
    }

    @Test
    public void nextPacketRetriesATransientTrackCreationFailure() throws Exception {
        ScriptedAudioTrack.failCreation = true;
        write(pcm(40, (byte) 0x31));
        assertNull(track());

        ScriptedAudioTrack.failCreation = false;
        write(pcm(40, (byte) 0x42));
        drain.invoke(playback);
        assertNotNull(track());
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track().getPlayState());
    }

    @Test
    public void completedPromptDoesNotReplayPartialWriteIntoNextPrompt() throws Exception {
        leavePartialPrompt();
        manager.stopNavTrack();
        assertNextPromptContainsOnlyNewAudio();
    }

    @Test
    public void endMarkerDiscardsPartialWriteBeforeNextPrompt() throws Exception {
        leavePartialPrompt();
        byte[] marker = new byte[64];
        Arrays.fill(marker, (byte) 0xff);
        write(marker);
        assertNextPromptContainsOnlyNewAudio();
    }

    private void leavePartialPrompt() throws Exception {
        byte[] oldPrompt = pcm(40, (byte) 0x31);
        ScriptedAudioTrack.results.add(oldPrompt.length / 2);
        ScriptedAudioTrack.results.add(0);
        write(oldPrompt);
        drain.invoke(playback);
        assertEquals(oldPrompt.length / 2, ScriptedAudioTrack.writes.get(0).length);
    }

    private void assertNextPromptContainsOnlyNewAudio() throws Exception {
        ScriptedAudioTrack.writes.clear();
        manager.onNavStarted();
        byte[] nextPrompt = pcm(40, (byte) 0x42);
        write(nextPrompt);
        drain.invoke(playback);
        assertEquals("Old residual bytes must not be appended after the new prompt", 1, ScriptedAudioTrack.writes.size());
        assertArrayEquals(nextPrompt, ScriptedAudioTrack.writes.get(0));
    }

    private void write(byte[] bytes) {
        manager.writeAudio(bytes, 0, bytes.length, AudioStreamType.NAVIGATION, 4, new AudioRoutingState());
    }

    private static byte[] pcm(int milliseconds, byte sample) {
        byte[] bytes = new byte[milliseconds * 48000 * 2 * 2 / 1000];
        Arrays.fill(bytes, sample);
        return bytes;
    }

    private AudioTrack track() throws Exception {
        return (AudioTrack) field("navTrack").get(manager);
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
        static boolean failCreation;

        @Implementation
        protected static int native_get_min_buff_size(int sampleRate, int channelCount, int format) {
            return failCreation ? AudioTrack.ERROR_BAD_VALUE : 1024;
        }

        @Implementation
        protected int native_write_byte(byte[] data, int offset, int size, int format, boolean blocking) {
            int accepted = results.isEmpty() ? size : Math.min(results.remove(), size);
            if (accepted > 0) writes.add(Arrays.copyOfRange(data, offset, offset + accepted));
            return accepted;
        }
    }
}
