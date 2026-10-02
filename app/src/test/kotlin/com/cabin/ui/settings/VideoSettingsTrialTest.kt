package com.cabin.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoSettingsTrialTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun reset() {
        context.getSharedPreferences("video_settings_trial_v1", 0).edit().clear().commit()
        context.getSharedPreferences("carlink_adapter_config_sync_cache", 0).edit().clear().commit()
    }
    private fun freshProcessOwner(): VideoSettingsTrial = ReflectionHelpers.callConstructor(VideoSettingsTrial::class.java,
        ReflectionHelpers.ClassParameter.from(Context::class.java, context))
    @Test fun processRestartRestoresJournalBeforeConfigurationIsReadAndRetainsUntilDurableRepair() {
        val journal = context.getSharedPreferences("video_settings_trial_v1", 0)
        journal.edit().putBoolean("pending", true).putString("resolution", "1280x720").putInt("fps", 30).putInt("bezel", 4).commit()
        val cache = context.getSharedPreferences("carlink_adapter_config_sync_cache", 0)
        cache.edit().putString("video_resolution", "1920x1080").putInt("fps", 60).commit()
        val trial = freshProcessOwner()
        assertEquals("1280x720" to 30, trial.recoverSynchronously())
        assertEquals("1280x720", cache.getString("video_resolution", null))
        assertEquals(30, cache.getInt("fps", 0))
        assertTrue(journal.getBoolean("pending", false))
        trial.finishStartupRecovery()
        assertFalse(journal.contains("pending"))
        assertNull(trial.recoverSynchronously())
    }
    @Test fun rollbackTargetsOriginalDriverAfterProfileSwitch() {
        val presentation = context.getSharedPreferences("projection_presentation_v1", 0)
        presentation.edit().clear().putInt("driver.0.bezel", 8).putInt("driver.1.bezel", 9).commit()
        context.getSharedPreferences("teyes_features_v1", 0).edit().putInt("active", 1).commit()
        context.getSharedPreferences("video_settings_trial_v1", 0).edit()
            .putBoolean("pending", true).putInt("driver_slot", 0).putInt("bezel", 4).commit()
        freshProcessOwner().recoverSynchronously()
        assertEquals(4, presentation.getInt("driver.0.bezel", -1))
        assertEquals(9, presentation.getInt("driver.1.bezel", -1))
    }
    @Test fun deadlineCountsElapsedTimeIncludingSuspensionAndRoundsUp() {
        assertEquals(45, remainingTrialSeconds(50_000, 5_000))
        assertEquals(35, remainingTrialSeconds(50_000, 15_001))
        assertEquals(1, remainingTrialSeconds(50_000, 49_999))
        assertEquals(0, remainingTrialSeconds(50_000, 50_000))
        assertEquals(0, remainingTrialSeconds(50_000, 100_000))
        assertEquals(45, remainingTrialSeconds(50_000, 0))
    }
    @Test fun unrelatedStartupDoesNotAlterVideoSettingsAndKeepNeedsAnActiveTrial() {
        val cache = context.getSharedPreferences("carlink_adapter_config_sync_cache", 0)
        cache.edit().putInt("fps", 60).commit()
        val trial = freshProcessOwner()
        assertNull(trial.recoverSynchronously())
        assertEquals(60, cache.getInt("fps", 0))
        assertFalse(trial.keep(false))
        assertFalse(trial.keep(true))
    }
}
