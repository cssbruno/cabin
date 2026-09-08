package com.cabin.background

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CabinExplicitPhoneConnectTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun clearPreferences() {
        application.getSharedPreferences("phone_connection_intent", Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences("carlink_background_service", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `automatic service start preserves pause while explicit Connect resumes an existing idle adapter`() = runBlocking {
        val manager = CabinManager(application)
        manager.disconnectPhone().join()
        // CONNECTING also represents an initialized adapter waiting for a phone.
        ReflectionHelpers.getField<AtomicReference<CabinManager.State>>(manager, "currentState")
            .set(CabinManager.State.CONNECTING)
        ReflectionHelpers.getField<AtomicBoolean>(manager, "shouldBeRunning").set(true)
        manager.resumeRequestedSession()
        assertFalse(ReflectionHelpers.getField<AtomicBoolean>(manager, "phoneAutoConnectEnabled").get())
        CabinProjectionService.registerActivityManager(manager)
        val controller = Robolectric.buildService(CabinProjectionService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "manager", manager)
        val previousConnect = Job()
        ReflectionHelpers.setField(service, "connectJob", previousConnect)
        val lock = ReflectionHelpers.getField<Mutex>(manager, "lifecycleMutex")
        lock.lock()
        try {
            service.onStartCommand(Intent(service, CabinProjectionService::class.java).setAction(CabinProjectionService.ACTION_CONNECT), 0, 1)
            assertFalse(ReflectionHelpers.getField<AtomicBoolean>(manager, "phoneAutoConnectEnabled").get())
            assertTrue(previousConnect.isActive)

            service.onStartCommand(Intent(service, CabinProjectionService::class.java).setAction(CabinProjectionService.ACTION_CONNECT_PHONE), 0, 2)
            assertTrue(ReflectionHelpers.getField<AtomicBoolean>(manager, "phoneAutoConnectEnabled").get())
            assertTrue(previousConnect.isCancelled)
            assertTrue(application.getSharedPreferences("phone_connection_intent", Context.MODE_PRIVATE).getBoolean("auto_connect", false))
            // Do not discover real USB hardware after releasing the held lifecycle lock.
            ReflectionHelpers.getField<AtomicBoolean>(manager, "shouldBeRunning").set(false)
        } finally {
            previousConnect.cancel()
            lock.unlock()
            controller.destroy()
            CabinProjectionService.unregisterActivityManager(manager)
            manager.releaseAndWait()
        }
    }

    @Test
    fun `explicit launcher helper uses a different action from automatic service startup`() {
        CabinProjectionService.start(application)
        assertEquals(CabinProjectionService.ACTION_CONNECT, shadowOf(application).nextStartedService.action)
        CabinProjectionService.startPhoneConnection(application)
        assertEquals(CabinProjectionService.ACTION_CONNECT_PHONE, shadowOf(application).nextStartedService.action)
    }
}
