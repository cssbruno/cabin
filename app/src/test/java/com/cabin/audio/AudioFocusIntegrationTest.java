package com.cabin.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import androidx.test.core.app.ApplicationProvider;
import com.cabin.platform.AudioConfig;
import com.cabin.protocol.StreamPurpose;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAudioManager;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, shadows = AudioFocusIntegrationTest.FocusAudioManager.class)
public class AudioFocusIntegrationTest {
    private DualStreamAudioManager manager;
    @Before public void setup() {
        FocusAudioManager.result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        FocusAudioManager.last = null;
        Context context = ApplicationProvider.getApplicationContext();
        manager = new DualStreamAudioManager(context, message -> {}, AudioConfig.Companion.getDEFAULT());
    }
    @After public void cleanup() { manager.release(); }

    @Test public void navigationUsesTransientDuckingAndSpeechAttributes() {
        manager.onPurposeChanged(StreamPurpose.NAVIGATION);
        AudioFocusRequest request = FocusAudioManager.last;
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request.getFocusGain());
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, request.getAudioAttributes().getUsage());
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.getAudioAttributes().getContentType());
    }

    @Test public void deniedFocusMutesEveryPurposeUntilGranted() throws Exception {
        for (StreamPurpose purpose : StreamPurpose.values()) {
            FocusAudioManager.result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            manager.onPurposeChanged(purpose);
            assertEquals(0f, volume(purpose), 0f);
            FocusAudioManager.result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            manager.onPurposeChanged(purpose);
            assertEquals(1f, volume(purpose), 0f);
            manager.onPurposeEnded(purpose);
        }
    }

    @Test public void delayedFocusStaysMutedUntilGainCallback() throws Exception {
        FocusAudioManager.result = AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
        manager.onPurposeChanged(StreamPurpose.MEDIA);
        assertEquals(0f, volume(StreamPurpose.MEDIA), 0f);
        listener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(1f, volume(StreamPurpose.MEDIA), 0f);
    }

    @Test public void transientLossMutesNavigationWithoutOverwritingUserGain() throws Exception {
        manager.setUserGains(.7f, .6f);
        manager.onPurposeChanged(StreamPurpose.NAVIGATION);
        AudioManager.OnAudioFocusChangeListener listener = listener();
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertEquals(0f, volume(StreamPurpose.NAVIGATION), 0f);
        manager.setUserGains(.8f, .5f);
        assertEquals(0f, volume(StreamPurpose.NAVIGATION), 0f);
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(.5f, volume(StreamPurpose.NAVIGATION), 0f);
    }

    @Test public void abandonedListenerCannotMuteNewMediaSession() throws Exception {
        manager.onPurposeChanged(StreamPurpose.MEDIA);
        AudioManager.OnAudioFocusChangeListener old = listener();
        manager.onPurposeEnded(StreamPurpose.MEDIA);
        manager.onPurposeChanged(StreamPurpose.MEDIA);
        old.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        assertEquals(1f, volume(StreamPurpose.MEDIA), 0f);
        listener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        assertEquals(0f, volume(StreamPurpose.MEDIA), 0f);
    }

    @Test public void repeatedStartRetiresPreviousListenerWithoutStop() throws Exception {
        manager.onPurposeChanged(StreamPurpose.MEDIA);
        AudioManager.OnAudioFocusChangeListener old = listener();
        manager.onPurposeChanged(StreamPurpose.MEDIA);
        assertNotSame(old, listener());
        old.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        assertEquals(1f, volume(StreamPurpose.MEDIA), 0f);
    }

    @Test public void focusDuckingCombinesWithAdapterDuckingWithoutIncreasingVolume() throws Exception {
        manager.onPurposeChanged(StreamPurpose.MEDIA);
        manager.setUserGains(.8f, 1f);
        manager.setDucking(.1f);
        AudioManager.OnAudioFocusChangeListener listener = listener();
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertEquals(.08f, volume(StreamPurpose.MEDIA), .0001f);
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(.08f, volume(StreamPurpose.MEDIA), .0001f);
    }

    private AudioManager.OnAudioFocusChangeListener listener() throws Exception {
        Method method = AudioFocusRequest.class.getDeclaredMethod("getOnAudioFocusChangeListener");
        method.setAccessible(true);
        return (AudioManager.OnAudioFocusChangeListener) method.invoke(FocusAudioManager.last);
    }

    private float volume(StreamPurpose purpose) throws Exception {
        Method method = DualStreamAudioManager.class.getDeclaredMethod("effectiveVolume", StreamPurpose.class);
        method.setAccessible(true);
        return (Float) method.invoke(manager, purpose);
    }

    @Implements(AudioManager.class)
    public static class FocusAudioManager extends ShadowAudioManager {
        static int result;
        static android.media.AudioFocusRequest last;
        @Implementation public int requestAudioFocus(android.media.AudioFocusRequest request) {
            last = request;
            return result;
        }
        @Implementation public int abandonAudioFocusRequest(android.media.AudioFocusRequest request) {
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }
}
