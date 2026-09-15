package com.cabin.platform

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.cabin.platform.TeyesFeaturePreferences.Companion.normalized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class TeyesFeaturesTest {
    private lateinit var preferences: TeyesFeaturePreferences
    private lateinit var router: TeyesKeyRouter
    private val performed = mutableListOf<TeyesKeyAction>()
    private var nowMillis = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("teyes_features_v1", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = TeyesFeaturePreferences(context)
        preferences.select(0)
        router = TeyesKeyRouter(preferences, nowMillis = { nowMillis }) { performed.add(it) }
    }

    @Test fun `new profiles default dark and saved appearance choices survive reload`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (slot in 0..2) {
            preferences.select(slot)
            assertEquals(TeyesAppearance.NIGHT, preferences.profile.value.appearance)
        }
        for (choice in TeyesAppearance.entries) {
            preferences.update { it.copy(appearance = choice) }
            assertEquals(choice, TeyesFeaturePreferences(context).profile.value.appearance)
        }
    }

    @Test fun `new audio mappings run once on release and ignore held repeats`() {
        for (action in listOf(TeyesKeyAction.LAUNCHER, TeyesKeyAction.VOLUME_UP, TeyesKeyAction.VOLUME_DOWN, TeyesKeyAction.MUTE)) {
            preferences.mapKey(KeyEvent.KEYCODE_F1, action)
            val before = performed.size
            assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN)))
            assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN, repeat = 1)))
            assertEquals(before, performed.size)
            assertTrue(router.dispatch(event(KeyEvent.ACTION_UP)))
            assertEquals(action, performed.last())
            assertEquals(before + 1, performed.size)
        }
    }

    @Test fun `profiles are independent and restored`() {
        preferences.update { it.copy(name = "Bruno", preferredPhone = "AA:BB:CC:DD:EE:FF", mediaGain = 0.4f, resumeOnWake = true) }
        preferences.select(1)
        assertEquals("Driver 2", preferences.profile.value.name)
        assertEquals(1f, preferences.profile.value.mediaGain)
        assertFalse(preferences.profile.value.resumeOnWake)
        preferences.update { it.copy(name = "Guest", appearance = TeyesAppearance.NIGHT) }
        preferences.select(0)
        assertEquals("Bruno", preferences.profile.value.name)
        assertEquals(0.4f, preferences.profile.value.mediaGain)
        assertTrue(preferences.profile.value.resumeOnWake)
        preferences.select(1)
        assertEquals(TeyesAppearance.NIGHT, preferences.profile.value.appearance)
    }

    @Test fun `invalid phone and nonfinite gains cannot reach adapter or audio`() {
        val result =
            TeyesDriverProfile(
                preferredPhone = "ATZ\r",
                nightBrightness = Float.NaN,
                mediaGain = Float.POSITIVE_INFINITY,
                navigationGain = -1f,
            ).normalized()
        assertEquals("", result.preferredPhone)
        assertEquals(0.35f, result.nightBrightness)
        assertEquals(1f, result.mediaGain)
        assertEquals(0f, result.navigationGain)
        assertEquals(0.1f, TeyesDriverProfile(nightBrightness = -1f).normalized().nightBrightness)
    }

    @Test fun `forget phone clears it from all profiles only`() {
        preferences.update { it.copy(preferredPhone = "AA:BB:CC:DD:EE:FF", name = "Keep me") }
        preferences.select(1)
        preferences.update { it.copy(preferredPhone = "aa:bb:cc:dd:ee:ff") }
        preferences.forgetPhone("AA:BB:CC:DD:EE:FF")
        assertEquals("", preferences.profile.value.preferredPhone)
        preferences.select(0)
        assertEquals("", preferences.profile.value.preferredPhone)
        assertEquals("Keep me", preferences.profile.value.name)
    }

    @Test fun `learning saves a mapping without executing it`() {
        router.learn(TeyesKeyAction.NEXT)
        assertTrue(router.isLearning)
        assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN)))
        assertFalse(router.isLearning)
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP)))
        assertEquals(TeyesKeyAction.NEXT, preferences.keyAction(KeyEvent.KEYCODE_F1))
        assertTrue(performed.isEmpty())
    }

    @Test fun `repeat downs execute exactly once on release`() {
        preferences.mapKey(KeyEvent.KEYCODE_F1, TeyesKeyAction.PLAY_PAUSE)
        assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN)))
        repeat(5) { assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN, repeat = it + 1))) }
        assertTrue(performed.isEmpty())
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP)))
        assertEquals(listOf(TeyesKeyAction.PLAY_PAUSE), performed)
        assertFalse(router.dispatch(event(KeyEvent.ACTION_UP)))
    }

    @Test fun `canceled and focus lost presses never trigger commands`() {
        preferences.mapKey(KeyEvent.KEYCODE_F1, TeyesKeyAction.VOICE)
        router.dispatch(event(KeyEvent.ACTION_DOWN))
        router.dispatch(event(KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED))
        router.dispatch(event(KeyEvent.ACTION_DOWN))
        router.cancelPressedKeys()
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP)))
        assertTrue(performed.isEmpty())
    }

    @Test fun `learning expires and unmapped events pass through`() {
        router.learn(TeyesKeyAction.NEXT)
        nowMillis += 16_000
        assertFalse(router.dispatch(event(KeyEvent.ACTION_DOWN)))
        assertNull(preferences.keyAction(KeyEvent.KEYCODE_F1))
    }

    @Test fun `system and call keys cannot be mapped or swallowed`() {
        router.learn(TeyesKeyAction.NEXT)
        for (key in listOf(
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_HEADSETHOOK,
        )) {
            assertFalse(TeyesKeyRouter.isMappable(key))
            assertFalse(router.dispatch(event(KeyEvent.ACTION_DOWN, key)))
        }
        assertTrue(performed.isEmpty())
    }

    @Test fun `unconfigured malformed and removed shortcuts fail safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(TeyesAppShortcuts.launch(context, null))
        assertFalse(TeyesAppShortcuts.launch(context, "garbage"))
        assertFalse(TeyesAppShortcuts.launch(context, "no.such.app/.MainActivity"))
    }

    @Test fun `long hold runs only long action and focus loss suppresses both`() {
        preferences.mapKey(KeyEvent.KEYCODE_F1, TeyesKeyAction.NEXT)
        preferences.mapKey(KeyEvent.KEYCODE_F1, TeyesKeyAction.VOICE, longPress = true)
        router.dispatch(event(KeyEvent.ACTION_DOWN))
        nowMillis = 649
        router.dispatch(event(KeyEvent.ACTION_UP))
        assertEquals(listOf(TeyesKeyAction.NEXT), performed)
        router.dispatch(event(KeyEvent.ACTION_DOWN))
        nowMillis += 650
        router.dispatch(event(KeyEvent.ACTION_DOWN, repeat = 1))
        router.dispatch(event(KeyEvent.ACTION_UP))
        assertEquals(listOf(TeyesKeyAction.NEXT, TeyesKeyAction.VOICE), performed)
        router.dispatch(event(KeyEvent.ACTION_DOWN))
        nowMillis += 1000
        router.cancelPressedKeys()
        router.dispatch(event(KeyEvent.ACTION_UP))
        assertEquals(2, performed.size)
    }

    @Test fun `page mappings emit one navigation event and never control media`() = kotlinx.coroutines.runBlocking {
        val pages = mutableListOf<Int>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            router.pageChanges.collect { pages.add(it) }
        }
        preferences.mapKey(KeyEvent.KEYCODE_F1, TeyesKeyAction.PAGE_NEXT)
        router.dispatch(event(KeyEvent.ACTION_DOWN))
        router.dispatch(event(KeyEvent.ACTION_DOWN, repeat = 1))
        router.dispatch(event(KeyEvent.ACTION_UP))
        kotlinx.coroutines.yield()
        assertEquals(listOf(1), pages)
        assertTrue(performed.isEmpty())
        job.cancel()
    }

    private fun event(
        action: Int,
        key: Int = KeyEvent.KEYCODE_F1,
        repeat: Int = 0,
        flags: Int = 0,
    ) = KeyEvent(0, 0, action, key, repeat, 0, 0, 0, flags)
}
