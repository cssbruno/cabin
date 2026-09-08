package com.cabin.background

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CabinProjectionExitTest {
    @Test
    fun `close app stops the foreground owner and cannot return to background`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val controller = Robolectric.buildService(CabinProjectionService::class.java).create()
        val service = controller.get()
        val manager = CabinManager(application)
        val preferences = application.getSharedPreferences("carlink_background_service", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("should_run", true).commit()
        CabinProjectionService.registerActivityManager(manager)
        CabinProjectionService.refreshForegroundCapabilitiesFromVisibleActivity()
        try {
            completeOnMain { CabinProjectionService.stopForAppExit(application, manager) }

            assertFalse(preferences.getBoolean("should_run", true))
            assertFalse(CabinProjectionService.hasRunningSession())
            assertFalse(CabinProjectionService.returnRunningManager(manager))
            assertTrue(shadowOf(service).isForegroundStopped)
            assertEquals(CabinManager.State.DISCONNECTED, manager.state)
            // A queued pre-exit connect intent cannot resurrect the stopping owner.
            assertEquals(
                Service.START_NOT_STICKY,
                service.onStartCommand(Intent(application, CabinProjectionService::class.java)
                    .setAction(CabinProjectionService.ACTION_CONNECT), 0, 2),
            )
            assertFalse(CabinProjectionService.hasRunningSession())
        } finally {
            CabinProjectionService.unregisterActivityManager(manager)
            controller.destroy()
            runBlocking { manager.releaseAndWait() }
        }
    }

    @Test
    fun `close app clears persisted reconnect intent even without a live service`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val manager = CabinManager(application)
        val preferences = application.getSharedPreferences("carlink_background_service", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("should_run", true).commit()
        try {
            completeOnMain { CabinProjectionService.stopForAppExit(application, manager) }
            assertFalse(preferences.getBoolean("should_run", true))
            assertFalse(CabinProjectionService.hasRunningSession())
        } finally {
            runBlocking { manager.releaseAndWait() }
        }
    }

    private fun completeOnMain(block: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val result = scope.async { block() }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!result.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("Background teardown should finish", result.isCompleted)
            runBlocking { result.await() }
        } finally {
            scope.cancel()
        }
    }
}
