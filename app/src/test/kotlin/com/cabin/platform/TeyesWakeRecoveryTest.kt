package com.cabin.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TeyesWakeRecoveryTest {
    @Test
    fun `wake cannot start an idle unrequested session`() = runBlocking {
        val manager = CabinManager(ApplicationProvider.getApplicationContext<Context>())
        try {
            repeat(3) { manager.resumeRequestedSession() }
            assertEquals(CabinManager.State.DISCONNECTED, manager.state)
        } finally {
            manager.releaseAndWait()
        }
    }

    @Test
    fun `stop followed by wake stays stopped`() = runBlocking {
        val manager = CabinManager(ApplicationProvider.getApplicationContext<Context>())
        try {
            manager.stopAndWait()
            manager.resumeRequestedSession()
            assertEquals(CabinManager.State.DISCONNECTED, manager.state)
        } finally {
            manager.releaseAndWait()
        }
    }

    @Test
    fun `released manager cannot be resurrected by wake`() = runBlocking {
        val manager = CabinManager(ApplicationProvider.getApplicationContext<Context>())
        manager.releaseAndWait()
        manager.resumeRequestedSession()
        assertEquals(CabinManager.State.DISCONNECTED, manager.state)
    }
}
