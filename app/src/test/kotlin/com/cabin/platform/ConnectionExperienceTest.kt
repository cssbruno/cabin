package com.cabin.platform

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cabin.audio.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ConnectionExperienceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun reset() {
        listOf("connection_summaries_v1", "projection_presentation_v1", "audio_experience_v1", "teyes_features_v1").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        TeyesFeaturePreferences.get(context).endGuest()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun historyIsOptInBoundedAndDeletionPersists() {
        val history = ConnectionHistory(context)
        val now = System.currentTimeMillis()
        history.append(ConnectionSessionSummary(now, 6000, ConnectionFailure.USB_DETACHED, 1000))
        assertTrue(history.read().isEmpty())
        history.enabled = true
        repeat(120) { history.append(ConnectionSessionSummary(now, it.toLong(), ConnectionFailure.WRITE_FAILED, 250)) }
        assertEquals(100, ConnectionHistory(context).read().size)
        assertEquals(20L, history.read().first().durationMs)
        history.retentionDays = 1
        assertTrue(history.read(now + 2 * 86_400_000).isEmpty())
        history.enabled = false
        assertFalse(context.getSharedPreferences("connection_summaries_v1", Context.MODE_PRIVATE).contains("sessions"))
    }
    @Test fun legacyPresentationMigratesPerDriverAndExternalRestoreRefreshesObservers() {
        val storage = context.getSharedPreferences("projection_presentation_v1", Context.MODE_PRIVATE)
        storage.edit().putBoolean("vehicle_hud", true).putString("control_side", "LEFT").commit()
        val presentation = ProjectionPreferences(context)
        assertTrue(presentation.state.value.vehicleHud)
        presentation.setControlHideSeconds(10)
        presentation.setVehicleHud(false)
        val drivers = context.getSharedPreferences("teyes_features_v1", Context.MODE_PRIVATE)
        drivers.edit().putInt("active", 1).commit()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(presentation.state.value.vehicleHud)
        assertEquals(0, presentation.state.value.controlHideSeconds)
        presentation.setBlackoutMinutes(5)
        drivers.edit().putInt("active", 0).commit()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(presentation.state.value.vehicleHud)
        assertEquals(10, presentation.state.value.controlHideSeconds)
        assertEquals(0, presentation.state.value.blackoutMinutes)
        storage.edit().putInt("driver.0.hide_seconds", 20).commit()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(20, presentation.state.value.controlHideSeconds)
    }
    @Test fun corruptPresentationChoicesCannotCauseUnboundedTimersOrInsets() {
        val storage = context.getSharedPreferences("projection_presentation_v1", Context.MODE_PRIVATE)
        storage.edit().putInt("driver.0.hide_seconds", -1).putInt("driver.0.blackout_minutes", Int.MAX_VALUE)
            .putInt("driver.0.bezel", 100).putString("driver.0.tool_order", "phone,unknown,phone,settings").commit()
        val state = ProjectionPreferences(context).state.value
        assertEquals(0, state.controlHideSeconds)
        assertEquals(0, state.blackoutMinutes)
        assertEquals(10, state.bezelPercent)
        assertEquals(listOf("phone", "settings"), state.toolOrder)
    }
    @Test fun namedPresetsAreBoundedPerDriverAndRenamingDoesNotDuplicate() {
        val audio = AudioExperience(context)
        repeat(8) { assertTrue(audio.save(0, AudioGainPreset("Preset $it", .5f, .7f))) }
        assertFalse(audio.save(0, AudioGainPreset("Overflow", .3f, .4f)))
        assertTrue(audio.presets(1).isEmpty())
        assertTrue(audio.save(0, AudioGainPreset("Renamed", .2f, .9f), "Preset 0"))
        assertEquals(8, audio.presets(0).size)
        assertEquals(.9f, AudioExperience(context).presets(0).last().navigation, 0f)
        assertFalse(audio.save(1, AudioGainPreset("Bad", Float.NaN, 0f)))
        audio.delete(0, "Renamed")
        assertEquals(7, audio.presets(0).size)
    }
    @Test fun guestCannotPersistRenameOrDeleteDriverPresets() {
        val drivers = TeyesFeaturePreferences.get(context)
        val audio = AudioExperience(context)
        assertTrue(audio.save(0, AudioGainPreset("Saved", .5f, .7f)))
        val before = context.getSharedPreferences("audio_experience_v1", 0).all
        drivers.beginGuest()
        try {
            assertFalse(audio.save(0, AudioGainPreset("Guest", .1f, .2f)))
            assertFalse(audio.save(0, AudioGainPreset("Renamed", .1f, .2f), "Saved"))
            audio.delete(0, "Saved")
            assertEquals(before, context.getSharedPreferences("audio_experience_v1", 0).all)
            drivers.update { it.copy(mediaGain = .1f, navigationGain = .2f) }
            assertEquals(.1f, drivers.profile.value.mediaGain, 0f)
        } finally { drivers.endGuest() }
        assertEquals(1f, drivers.profile.value.mediaGain, 0f)
        assertTrue(audio.save(0, AudioGainPreset("Renamed", .1f, .2f), "Saved"))
    }
    @Test fun bufferProfilesKeepPlatformRoutingAndFormatAndVaryLatency() {
        val base = AudioConfig.GM_AAOS
        assertEquals(base, AudioBufferProfile.PLATFORM.apply(base))
        AudioBufferProfile.entries.forEach {
            val candidate = it.apply(base)
            assertEquals(base.sampleRate, candidate.sampleRate)
            assertEquals(base.performanceMode, candidate.performanceMode)
            assertTrue(candidate.navBufferCapacityMs >= candidate.prefillThresholdMs)
        }
        assertTrue(AudioBufferProfile.RESPONSIVE.apply(base).prefillThresholdMs < AudioBufferProfile.RESILIENT.apply(base).prefillThresholdMs)
    }
}
