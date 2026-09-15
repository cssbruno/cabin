package com.cabin.joying;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import androidx.test.core.app.ApplicationProvider;
import kotlin.Unit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAudioManager;
import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, shadows = JoyingAudioFocusTest.FocusShadow.class)
public class JoyingAudioFocusTest {
    private JoyingAudioFocus focus;
    private final ArrayList<Boolean> commands = new ArrayList<>();
    @Before public void setup() {
        FocusShadow.last = null;
        FocusShadow.result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        Context context = ApplicationProvider.getApplicationContext();
        focus = new JoyingAudioFocus(context, play -> { commands.add(play); return Unit.INSTANCE; });
    }
    @After public void cleanup() { focus.close(); }
    private AudioManager.OnAudioFocusChangeListener listener(AudioFocusRequest request) throws Exception {
        return (AudioManager.OnAudioFocusChangeListener) AudioFocusRequest.class
            .getDeclaredMethod("getOnAudioFocusChangeListener").invoke(request);
    }
    @Test public void staleFocusLossCannotPauseANewSession() throws Exception {
        focus.updateAudio(1);
        AudioFocusRequest old = FocusShadow.last;
        focus.reset();
        focus.updateAudio(1);
        listener(old).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        assertTrue(commands.isEmpty());
        listener(FocusShadow.last).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertEquals(Boolean.FALSE, commands.get(0));
    }
    @Test public void voiceAndCallsRequestSpeechFocus() {
        focus.updateAudio(0x801);
        assertEquals(AudioAttributes.USAGE_ASSISTANT, FocusShadow.last.getAudioAttributes().getUsage());
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, FocusShadow.last.getFocusGain());
        focus.updateCall(1);
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, FocusShadow.last.getAudioAttributes().getUsage());
    }
    @Test public void deniedFocusPausesNativeMedia() {
        FocusShadow.result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        focus.updateAudio(1);
        assertEquals(Boolean.FALSE, commands.get(0));
    }
    @Test public void duplicateNativeStateDoesNotReacquireFocus() {
        focus.updateAudio(1);
        AudioFocusRequest first = FocusShadow.last;
        focus.updateAudio(1);
        assertSame(first, FocusShadow.last);
        focus.reset();
        focus.updateAudio(1);
        assertNotSame(first, FocusShadow.last);
    }
    @Implements(AudioManager.class)
    public static class FocusShadow extends ShadowAudioManager {
        static android.media.AudioFocusRequest last;
        static int result;
        @Implementation public int requestAudioFocus(android.media.AudioFocusRequest request) { last = request; return result; }
        @Implementation public int abandonAudioFocusRequest(android.media.AudioFocusRequest request) { return AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
    }
}
