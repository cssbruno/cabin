package com.cabin.platform

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SteeringAppMappingTest {
    private lateinit var prefs: TeyesFeaturePreferences
    private lateinit var router: TeyesKeyRouter
    private var now = 0L
    private val launched = mutableListOf<String>()
    private val actions = mutableListOf<TeyesKeyAction>()
    private val code = KeyEvent.KEYCODE_F1
    private val app = "example.maps/.MainActivity"

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("teyes_features_v1", 0).edit().clear().commit()
        prefs = TeyesFeaturePreferences(context)
        router = TeyesKeyRouter(prefs, { now }, { launched.add(it); true }) { actions.add(it) }
    }

    private fun down(repeat: Int = 0) = router.dispatch(KeyEvent(0, now, KeyEvent.ACTION_DOWN, code, repeat))
    private fun up(flags: Int = 0) = router.dispatch(KeyEvent(0, now, KeyEvent.ACTION_UP, code, 0, 0, 0, 0, flags))

    @Test fun `learning an app does not launch it and replaces the built in action`() {
        prefs.mapKey(code, TeyesKeyAction.NEXT)
        router.learnApp(app)
        assertTrue(down()); assertTrue(up())
        assertTrue(actions.isEmpty()); assertTrue(launched.isEmpty())
        assertNull(prefs.keyAction(code)); assertEquals(app, prefs.keyApp(code))
        down(); down(1); up(); up()
        assertEquals(listOf(app), launched)
    }

    @Test fun `long app press suppresses short action and canceled press never launches`() {
        prefs.mapKey(code, TeyesKeyAction.NEXT)
        prefs.mapKeyApp(code, app, true)
        down(); now += 649; up()
        assertEquals(listOf(TeyesKeyAction.NEXT), actions)
        down(); now += 650; up()
        assertEquals(listOf(app), launched)
        down(); now += 1000; up(KeyEvent.FLAG_CANCELED)
        down(); now += 1000; router.cancelPressedKeys(); up()
        assertEquals(1, launched.size)
    }

    @Test fun `none consumes event and removing mapping restores passthrough`() {
        prefs.mapKeyApp(code, app)
        prefs.mapKey(code, TeyesKeyAction.NONE)
        assertNull(prefs.keyApp(code))
        assertTrue(down()); assertTrue(up())
        assertTrue(actions.isEmpty()); assertTrue(launched.isEmpty())
        prefs.mapKey(code, null)
        assertFalse(down()); assertFalse(up())
    }

    @Test fun `reset removes both kinds of mapping but preserves profiles and shortcuts`() {
        prefs.update { it.copy(name = "Keep") }
        prefs.setShortcut(TeyesShortcut.RADIO, app)
        prefs.mapKey(code, TeyesKeyAction.BACK)
        prefs.mapKeyApp(code, app, true)
        prefs.resetKeyMappings()
        assertTrue(prefs.mappedKeys().isEmpty()); assertTrue(prefs.mappedKeyApps(true).isEmpty())
        assertEquals("Keep", prefs.profile.value.name)
        assertEquals(app, prefs.shortcut(TeyesShortcut.RADIO))
    }

    private fun snapshot() = prefs.configurationSnapshot().copy(
        projection = ProjectionPreferencesState(), measurementUnit = MeasurementUnit.SYSTEM,
    )

    @Test fun `backup round trip preserves app press types and schema 3 remains readable`() {
        prefs.mapKeyApp(code, app)
        prefs.mapKeyApp(code, "example.music/.Main", true)
        val original = snapshot()
        val text = TeyesConfigurationBackup.encode(original)
        assertEquals(original, TeyesConfigurationBackup.decode(text))
        prefs.resetKeyMappings()
        assertTrue(prefs.replaceConfiguration(TeyesConfigurationBackup.decode(text)))
        assertEquals(app, prefs.keyApp(code))
        assertEquals("example.music/.Main", prefs.keyApp(code, true))
        val old = JSONObject(text).apply { put("schema", 3); remove("keyApps"); remove("longKeyApps") }
        val decoded = TeyesConfigurationBackup.decode(old.toString())
        assertTrue(decoded.keyApps.isEmpty()); assertTrue(decoded.longKeyApps.isEmpty())
    }

    @Test fun `invalid and conflicting app imports fail before changing preferences`() {
        prefs.mapKey(code, TeyesKeyAction.NEXT)
        val before = prefs.configurationSnapshot()
        val bad = listOf(
            snapshot().copy(keyApps = mapOf(code to app)),
            snapshot().copy(keyApps = mapOf(KeyEvent.KEYCODE_POWER to app)),
            snapshot().copy(keyApps = mapOf(KeyEvent.KEYCODE_F2 to "not a component")),
        )
        bad.forEach {
            assertThrows(IllegalArgumentException::class.java) { prefs.replaceConfiguration(it) }
            assertEquals(before, prefs.configurationSnapshot())
        }
    }

    @Test fun `failed launch is consumed once and reports unavailability`() {
        router = TeyesKeyRouter(prefs, { now }, { false }) { actions.add(it) }
        prefs.mapKeyApp(code, app)
        down(); assertTrue(up())
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(context.getString(com.cabin.R.string.steering_app_unavailable), router.status.value)
        assertFalse(up())
    }
}
