package com.cabin.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ProjectionSetupPreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ProjectionSetupPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `guide resumes its step and completion removes first run invitation`() {
        val preferences = ProjectionSetupPreferences(context)
        assertEquals(ProjectionSetupProgress(), preferences.progress.value)
        preferences.visit(ProjectionSetupStep.PERMISSIONS)
        assertEquals(ProjectionSetupStep.PERMISSIONS, ProjectionSetupPreferences(context).progress.value.step)
        assertFalse(preferences.progress.value.completed)
        preferences.complete()
        assertEquals(ProjectionSetupProgress(completed = true), ProjectionSetupPreferences(context).progress.value)
        preferences.visit(ProjectionSetupStep.AUDIO)
        assertTrue(preferences.progress.value.completed)
    }

    @Test
    fun `reading and completing the guide cannot change durable Stop or phone pause`() {
        val service = context.getSharedPreferences("carlink_background_service", Context.MODE_PRIVATE)
        service.edit().putBoolean("should_run", false).commit()
        val phone = context.getSharedPreferences("phone_connection_intent", Context.MODE_PRIVATE)
        phone.edit().putBoolean("auto_connect", false).commit()
        val serviceBefore = service.all
        val phoneBefore = phone.all
        ProjectionSetupPreferences(context).apply {
            ProjectionSetupStep.entries.forEach(::visit)
            complete()
        }
        assertEquals(serviceBefore, service.all)
        assertEquals(phoneBefore, phone.all)
    }

    @Test
    fun `unknown or corrupt progress safely reopens the first step`() {
        context.getSharedPreferences(ProjectionSetupPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString("step", "FUTURE_STEP").putString("completed", "true").commit()
        assertEquals(ProjectionSetupProgress(), ProjectionSetupPreferences(context).progress.value)
    }

    @Test
    fun `diagnostics need parked acknowledgment and two independent idle checks`() {
        val idle = ProjectionReadinessSnapshot()
        assertTrue(projectionDiagnosticsAllowed(true, idle, true))
        assertFalse(projectionDiagnosticsAllowed(false, idle, true))
        assertFalse(projectionDiagnosticsAllowed(true, idle, false))
        assertFalse(projectionDiagnosticsAllowed(true, idle.copy(sessionRequested = true), true))
        CabinManager.State.entries.filter { it != CabinManager.State.DISCONNECTED }.forEach { state ->
            assertFalse(projectionDiagnosticsAllowed(true, idle.copy(state = state), true))
        }
    }
}
