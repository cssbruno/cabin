package com.cabin.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRecoveryActionsTest {
    @Test
    fun `explicit restart begins now and survives settings disposal during its gap`() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
            val resume = CompletableDeferred<Unit>()
            var issued = false
            var completed = false
            var finished = false
            val job =
                launchSettingsRestart(scope, restart = {
                    issued = true
                    resume.await()
                    completed = true
                }, onFinished = { finished = true })
            assertTrue(issued)
            scope.cancel()
            assertFalse(completed)
            resume.complete(Unit)
            job.join()
            assertTrue(completed)
            assertTrue(finished)
        }

    @Test
    fun `recovery completion does not override a later stop decision`() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
            val resume = CompletableDeferred<Unit>()
            var desired = false
            var restarted = false
            val job =
                launchSettingsRestart(scope, restart = {
                    // Mirrors CabinManager's ownership contract: explicit intent before the gap,
                    // then re-check desired state and release before starting the transport.
                    desired = true
                    resume.await()
                    if (desired) restarted = true
                }, onFinished = {})
            desired = false
            scope.cancel()
            resume.complete(Unit)
            job.join()
            assertFalse(restarted)
        }

    @Test
    fun `large text gets a single control column before button labels are squeezed`() {
        assertEquals(2, settingsControlColumns(800f, 1f))
        assertEquals(1, settingsControlColumns(1024f, 1.5f))
        assertEquals(1, settingsControlColumns(1200f, 2f))
        assertEquals(1, settingsControlColumns(480f, 1f))
    }

    @Test
    fun `cluster host reset needs both real Android platform capabilities`() {
        assertTrue(canResetAndroidClusterHost(automotive = true, templatesHost = true))
        assertFalse(canResetAndroidClusterHost(automotive = false, templatesHost = true))
        assertFalse(canResetAndroidClusterHost(automotive = true, templatesHost = false))
        assertFalse(canResetAndroidClusterHost(automotive = false, templatesHost = false))
    }
}
