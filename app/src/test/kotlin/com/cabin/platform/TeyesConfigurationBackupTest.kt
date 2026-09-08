package com.cabin.platform

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class TeyesConfigurationBackupTest {
    private lateinit var context: Context
    private lateinit var preferences: TeyesFeaturePreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric recreates the Application between tests, but Kotlin singleton fields
        // can retain SharedPreferences from the previous Application's isolated directory.
        listOf(ProjectionPreferences::class.java, MeasurementPreferences::class.java).forEach { owner ->
            owner.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
        context.getSharedPreferences("teyes_features_v1", Context.MODE_PRIVATE).edit().clear().commit()
        ProjectionPreferences.getInstance(context).replace(ProjectionPreferencesState())
        MeasurementPreferences.get(context).select(MeasurementUnit.SYSTEM)
        preferences = TeyesFeaturePreferences(context)
    }

    @Test fun `round trip retains independent profiles keys and shortcuts`() {
        preferences.update { it.copy(name = "Bruno", preferredPhone = "AA:BB:CC:DD:EE:FF", mediaGain = 0.4f) }
        preferences.select(1)
        preferences.update { it.copy(name = "Guest", appearance = TeyesAppearance.NIGHT, resumeOnWake = true) }
        preferences.mapKey(KeyEvent.KEYCODE_F1, TeyesKeyAction.CLIMATE)
        preferences.setShortcut(TeyesShortcut.DASHCAM, "com.example.camera/.MainActivity")
        val original = preferences.configurationSnapshot()
        assertEquals(original, TeyesConfigurationBackup.decode(TeyesConfigurationBackup.encode(original)))
        assertTrue(preferences.replaceConfiguration(original))
        assertEquals(1, preferences.profile.value.slot)
        assertEquals("Guest", preferences.profile.value.name)
        preferences.select(0)
        assertEquals("Bruno", preferences.profile.value.name)
        assertEquals(0.4f, preferences.profile.value.mediaGain)
        preferences.select(2)
        assertEquals("Driver 3", preferences.profile.value.name)
        assertFalse(preferences.profile.value.resumeOnWake)
    }

    @Test fun `malformed imported fields never partially replace existing preferences`() {
        preferences.update { it.copy(name = "Keep me") }
        preferences.mapKey(KeyEvent.KEYCODE_F1, TeyesKeyAction.NEXT)
        val before = preferences.configurationSnapshot()
        val invalid = before.copy(
            profiles = before.profiles.map { it.copy(name = "Replace me") },
            keys = mapOf(KeyEvent.KEYCODE_POWER to TeyesKeyAction.PLAY_PAUSE),
        )
        assertRejected { preferences.replaceConfiguration(invalid) }
        assertEquals(before, preferences.configurationSnapshot())
        assertRejected {
            preferences.replaceConfiguration(TeyesConfigurationBackup.decode(json().apply {
                getJSONArray("profiles").getJSONObject(2).put("preferredPhone", "not a phone")
            }.toString()))
        }
        assertEquals(before, preferences.configurationSnapshot())
    }

    @Test fun `import removes replaced mappings without touching unrelated settings or selected slot`() {
        val raw = context.getSharedPreferences("teyes_features_v1", Context.MODE_PRIVATE)
        raw.edit().putString("unrelated", "keep").commit()
        preferences.select(2)
        preferences.mapKey(KeyEvent.KEYCODE_F1, TeyesKeyAction.NEXT)
        preferences.setShortcut(TeyesShortcut.OBD, "com.example.obd/.Dashboard")
        val defaults = TeyesConfigurationSnapshot((0..2).map { TeyesDriverProfile(slot = it, name = "Restored ${it + 1}") }, emptyMap(), emptyMap())
        assertTrue(preferences.replaceConfiguration(defaults))
        assertEquals(2, preferences.profile.value.slot)
        assertEquals("Restored 3", preferences.profile.value.name)
        assertNull(preferences.keyAction(KeyEvent.KEYCODE_F1))
        assertNull(preferences.shortcut(TeyesShortcut.OBD))
        assertEquals("keep", raw.getString("unrelated", null))
    }

    @Test fun `corrupted stored preference types recover to safe defaults`() {
        val raw = context.getSharedPreferences("teyes_features_v1", Context.MODE_PRIVATE)
        raw.edit().putString("active", "bad").putInt("driver.0.name", 7)
            .putBoolean("driver.0.phone", true).putString("driver.0.media", "loud")
            .putInt("driver.0.wake", 1).putFloat("driver.0.brightness", Float.NaN)
            .putInt("key.${KeyEvent.KEYCODE_F1}", 3).putBoolean("shortcut.OBD", true)
            .putString("key.${KeyEvent.KEYCODE_POWER}", "NEXT").commit()
        val recovered = TeyesFeaturePreferences(context)
        assertEquals(TeyesDriverProfile(), recovered.profile.value)
        assertTrue(recovered.mappedKeys().isEmpty())
        assertNull(recovered.shortcut(TeyesShortcut.OBD))
        recovered.forgetPhone("AA:BB:CC:DD:EE:FF")
        assertEquals(TeyesDriverProfile(), recovered.configurationSnapshot().profiles[0])
    }

    @Test fun `strict schema rejects unsupported versions unknown fields duplicate fields and trailing text`() {
        val text = json().toString()
        assertRejected { TeyesConfigurationBackup.decode(json().put("schema", 3).toString()) }
        assertRejected { TeyesConfigurationBackup.decode(json().put("schema", "1").toString()) }
        assertRejected { TeyesConfigurationBackup.decode(json().put("extra", true).toString()) }
        assertRejected { TeyesConfigurationBackup.decode(text.replaceFirst("{", "{\"schema\":1,")) }
        assertRejected { TeyesConfigurationBackup.decode("$text {}") }
        assertRejected { TeyesConfigurationBackup.decode("/* comment */$text") }
        assertRejected { TeyesConfigurationBackup.decode("[]") }
    }

    @Test fun `profile parsing rejects type coercion unknown enums repeated slots and out of range numbers`() {
        for ((field, value) in listOf(
            "resumeOnWake" to "true", "appearance" to "AUTO",
            "nightBrightness" to 0.099999999, "mediaGain" to 1.00000001,
            "navigationGain" to "0.5", "preferredPhone" to "AA:BB:CC:DD:EE:FF\r", "name" to "\nDriver",
        )) {
            assertRejected {
                TeyesConfigurationBackup.decode(json().apply { getJSONArray("profiles").getJSONObject(0).put(field, value) }.toString())
            }
        }
        assertRejected { TeyesConfigurationBackup.decode(json().toString().replace("\"slot\":0", "\"slot\":0.0")) }
        assertRejected {
            TeyesConfigurationBackup.decode(json().apply { getJSONArray("profiles").getJSONObject(2).put("slot", 0) }.toString())
        }
    }

    @Test fun `oversized documents and invalid utf8 are rejected before use`() {
        assertRejected { TeyesConfigurationBackup.decode(" ".repeat(TeyesConfigurationBackup.MAX_BYTES + 1)) }
        assertRejected { TeyesConfigurationBackup.read(ByteArrayInputStream(ByteArray(TeyesConfigurationBackup.MAX_BYTES + 1) { 32 })) }
        assertRejected { TeyesConfigurationBackup.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertEquals(preferences.configurationSnapshot(), TeyesConfigurationBackup.read(ByteArrayInputStream(json().toString().toByteArray())))
    }

    @Test fun `invalid shortcuts and unsafe key mappings are rejected`() {
        assertFalse(TeyesConfigurationBackup.validComponent("com.example.app/.Main\nActivity"))
        assertFalse(TeyesConfigurationBackup.validComponent("https://example.com"))
        assertFalse(TeyesConfigurationBackup.validComponent("com.example.app/.Main/Extra"))
        assertTrue(TeyesConfigurationBackup.validComponent("com.example.app/com.example.app.MainActivity"))
        assertRejected { preferences.setShortcut(TeyesShortcut.OBD, "broken") }
        assertRejected {
            TeyesConfigurationBackup.decode(json().apply { getJSONObject("shortcuts").put("UNKNOWN", "com.example.app/.Main") }.toString())
        }
        assertRejected {
            TeyesConfigurationBackup.decode(json().apply {
                getJSONArray("keys").put(JSONObject().put("code", KeyEvent.KEYCODE_POWER).put("action", "NEXT"))
            }.toString())
        }
    }

    @Test fun `revision tracks profile shortcut key and import changes`() {
        val initial = preferences.revision.value
        preferences.update { it.copy(name = "Updated") }
        preferences.mapKey(KeyEvent.KEYCODE_F1, TeyesKeyAction.NEXT)
        preferences.setShortcut(TeyesShortcut.TPMS, "com.example.tpms/.Main")
        preferences.select(1)
        preferences.replaceConfiguration(preferences.configurationSnapshot())
        assertEquals(initial + 5L, preferences.revision.value)
    }

    @Test fun `version two exports and restores every presentation field after validation`() {
        val presentation = ProjectionPreferencesState(false, true, ClimateNoticeMode.OFF, false, ProjectionControlSide.LEFT)
        ProjectionPreferences.getInstance(context).replace(presentation)
        MeasurementPreferences.get(context).select(MeasurementUnit.IMPERIAL)
        val backup = TeyesConfigurationBackup.encode(preferences.configurationSnapshot())
        assertEquals(2, JSONObject(backup).getInt("schema"))
        ProjectionPreferences.getInstance(context).replace(ProjectionPreferencesState())
        MeasurementPreferences.get(context).select(MeasurementUnit.METRIC)
        val decoded = TeyesConfigurationBackup.decode(backup)
        // Reading/previewing alone must never apply settings.
        assertEquals(ProjectionPreferencesState(), ProjectionPreferences.getInstance(context).state.value)
        assertEquals(MeasurementUnit.METRIC, MeasurementPreferences.get(context).unit.value)
        assertTrue(preferences.replaceConfiguration(decoded))
        assertEquals(presentation, ProjectionPreferences.getInstance(context).state.value)
        assertEquals(MeasurementUnit.IMPERIAL, MeasurementPreferences.get(context).unit.value)
        assertEquals(presentation, ProjectionPreferences(context).state.value)
        assertEquals(MeasurementUnit.IMPERIAL, MeasurementPreferences(context).unit.value)
    }

    @Test fun `legacy backups preserve current projection and unit preferences`() {
        val legacy = json().apply {
            put("schema", 1)
            remove("projection")
            remove("measurementUnit")
        }
        val presentation = ProjectionPreferencesState(controlSide = ProjectionControlSide.LEFT, vehicleHud = true)
        ProjectionPreferences.getInstance(context).replace(presentation)
        MeasurementPreferences.get(context).select(MeasurementUnit.IMPERIAL)
        val decoded = TeyesConfigurationBackup.decode(legacy.toString())
        assertNull(decoded.projection)
        assertNull(decoded.measurementUnit)
        assertTrue(preferences.replaceConfiguration(decoded))
        assertEquals(presentation, ProjectionPreferences.getInstance(context).state.value)
        assertEquals(MeasurementUnit.IMPERIAL, MeasurementPreferences.get(context).unit.value)
    }

    @Test fun `unknown or malformed presentation settings reject without any changes`() {
        val before = preferences.configurationSnapshot()
        for (field in listOf("focusControls", "vehicleHud", "returnWhenReady")) {
            assertRejected { preferences.replaceConfiguration(TeyesConfigurationBackup.decode(json().apply {
                getJSONObject("projection").put(field, "true")
            }.toString())) }
        }
        for ((field, value) in listOf("climateNoticeMode" to "AUTO", "controlSide" to "CENTER", "extra" to true)) {
            assertRejected { TeyesConfigurationBackup.decode(json().apply { getJSONObject("projection").put(field, value) }.toString()) }
        }
        assertRejected { TeyesConfigurationBackup.decode(json().put("measurementUnit", "KNOTS").toString()) }
        assertRejected { TeyesConfigurationBackup.decode(json().apply { remove("measurementUnit") }.toString()) }
        assertRejected { preferences.replaceConfiguration(before.copy(measurementUnit = null)) }
        assertEquals(before, preferences.configurationSnapshot())
    }

    private fun json(): JSONObject = JSONObject(TeyesConfigurationBackup.encode(preferences.configurationSnapshot()))

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("Expected invalid input to be rejected", rejected)
    }
}
