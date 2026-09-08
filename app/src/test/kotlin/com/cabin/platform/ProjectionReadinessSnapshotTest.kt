package com.cabin.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProjectionReadinessSnapshotTest {
    private val testResources get() = org.robolectric.RuntimeEnvironment.getApplication().resources

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `reading readiness preserves persisted phone pause and idle session`() =
        runBlocking {
            val preferences = context.getSharedPreferences("phone_connection_intent", Context.MODE_PRIVATE)
            preferences.edit().putBoolean("auto_connect", false).commit()
            val manager = CabinManager(context)
            try {
                repeat(3) {
                    val snapshot = manager.projectionReadinessSnapshot()
                    assertFalse(snapshot.phoneConnectionAllowed)
                    assertFalse(snapshot.sessionRequested)
                    assertFalse(snapshot.adapterOpened)
                    assertEquals(CabinManager.State.DISCONNECTED, snapshot.state)
                    assertEquals(ProjectionUsbAccess.NOT_FOUND, snapshot.usb.access)
                }
                assertFalse(preferences.getBoolean("auto_connect", true))
            } finally {
                manager.releaseAndWait()
                preferences.edit().clear().commit()
            }
        }

    @Test
    fun `running adapter intent does not imply phone auto connect intent`() =
        runBlocking {
            val manager = CabinManager(context)
            try {
                ReflectionHelpers.getField<AtomicBoolean>(manager, "shouldBeRunning").set(true)
                ReflectionHelpers.getField<AtomicBoolean>(manager, "phoneAutoConnectEnabled").set(false)
                val snapshot = manager.projectionReadinessSnapshot()
                assertTrue(snapshot.sessionRequested)
                assertFalse(snapshot.phoneConnectionAllowed)
                assertEquals("Phone connection paused", projectionReadinessPresentation(testResources, snapshot).title)
                assertEquals(CabinManager.State.DISCONNECTED, manager.state)
            } finally {
                manager.releaseAndWait()
            }
        }
}
