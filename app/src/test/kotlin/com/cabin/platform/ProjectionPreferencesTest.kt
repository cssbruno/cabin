package com.cabin.platform

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ProjectionPreferencesTest {
    private lateinit var context: Context
    private lateinit var preferences: ProjectionPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ProjectionPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        preferences = ProjectionPreferences(context)
    }

    @Test
    fun `defaults prefer focus summary and no vehicle HUD`() {
        assertEquals(ProjectionPreferencesState(), preferences.state.value)
        assertTrue(preferences.state.value.focusControls)
        assertFalse(preferences.state.value.vehicleHud)
        assertEquals(ClimateNoticeMode.SUMMARY, preferences.state.value.climateNoticeMode)
        assertTrue(preferences.state.value.returnWhenReady)
    }

    @Test
    fun `setters publish immediately without changing unrelated preferences`() {
        preferences.setFocusControls(false)
        assertEquals(ProjectionPreferencesState(focusControls = false), preferences.state.value)
        preferences.setVehicleHud(true)
        assertEquals(ProjectionPreferencesState(focusControls = false, vehicleHud = true), preferences.state.value)
        preferences.setClimateNoticeMode(ClimateNoticeMode.PANEL)
        preferences.setReturnWhenReady(false)
        assertEquals(
            ProjectionPreferencesState(false, true, ClimateNoticeMode.PANEL, false),
            preferences.state.value,
        )
    }

    @Test
    fun `all choices survive a new preference owner`() {
        preferences.setControlSide(ProjectionControlSide.LEFT)
        preferences.setFocusControls(false)
        preferences.setVehicleHud(true)
        preferences.setClimateNoticeMode(ClimateNoticeMode.OFF)
        preferences.setReturnWhenReady(false)
        assertEquals(preferences.state.value, ProjectionPreferences(context).state.value)
    }

    @Test
    fun `corrupt stored types safely use defaults`() {
        context.getSharedPreferences(ProjectionPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString("focus_controls", "false")
            .putInt("vehicle_hud", 1)
            .putBoolean("climate_notice_mode", true)
            .putFloat("return_when_ready", 0f)
            .putInt("control_side", 42)
            .commit()
        assertEquals(ProjectionPreferencesState(), ProjectionPreferences(context).state.value)
    }

    @Test
    fun `control side defaults safely and preserves unrelated settings`() {
        preferences.setControlSide(ProjectionControlSide.LEFT)
        assertEquals(ProjectionPreferencesState(controlSide = ProjectionControlSide.LEFT), preferences.state.value)
        context.getSharedPreferences(ProjectionPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString("control_side", "unknown").commit()
        assertEquals(ProjectionControlSide.RIGHT, ProjectionPreferences(context).state.value.controlSide)
    }

    @Test
    fun `unknown notice modes fall back to summary`() {
        context.getSharedPreferences(ProjectionPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString("climate_notice_mode", "FUTURE_MODE")
            .commit()
        assertEquals(ClimateNoticeMode.SUMMARY, ProjectionPreferences(context).state.value.climateNoticeMode)
    }

    @Test
    fun `every known notice mode round trips exactly`() {
        ClimateNoticeMode.entries.forEach { mode ->
            preferences.setClimateNoticeMode(mode)
            assertEquals(mode, preferences.state.value.climateNoticeMode)
            assertEquals(mode, ProjectionPreferences(context).state.value.climateNoticeMode)
        }
    }

    @Test
    fun `projection preferences leave driver profile storage untouched`() {
        val driverPreferences = context.getSharedPreferences("teyes_features_v1", Context.MODE_PRIVATE)
        driverPreferences.edit().putString("driver.0.name", "Keep this profile").commit()
        val before = driverPreferences.all
        preferences.setFocusControls(false)
        preferences.setVehicleHud(true)
        preferences.setClimateNoticeMode(ClimateNoticeMode.OFF)
        preferences.setReturnWhenReady(false)
        assertEquals(before, driverPreferences.all)
    }

    @Test
    fun `application and wrapped context share a singleton`() {
        assertSame(
            ProjectionPreferences.getInstance(context),
            ProjectionPreferences.getInstance(ContextWrapper(context)),
        )
    }
}
