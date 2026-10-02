package com.cabin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cabin.platform.ConnectionStage
import com.cabin.platform.ConnectionFailure
import com.cabin.platform.PhoneConnectionPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CabinConnectionRecoveryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var manager: CabinManager
    @Before fun setup() {
        app.getSharedPreferences("phone_connection_intent", 0).edit().clear().commit()
        manager = CabinManager(app)
    }
    @After fun cleanup() = runBlocking { manager.releaseAndWait() }
    @Test fun userStopCancelsStageDeadlineAndPreservesStoppedIntentOnWake() = runBlocking {
        ReflectionHelpers.getField<AtomicBoolean>(manager, "shouldBeRunning").set(true)
        invokeStage(ConnectionStage.PHONE)
        val timer = ReflectionHelpers.getField<Job>(manager, "stageJob")
        manager.stopAndWait()
        assertTrue(timer.isCancelled)
        assertEquals(ConnectionStage.IDLE, manager.connectionProgress.value.stage)
        manager.resumeRequestedSession()
        assertFalse(manager.projectionSessionRequested)
        assertEquals(CabinManager.State.DISCONNECTED, manager.state)
    }
    @Test fun retryPauseIsIndependentOfSavedPhoneIntent() {
        val prefs = app.getSharedPreferences("phone_connection_intent", 0)
        prefs.edit().putString("phone.private", "MANUAL").commit()
        manager.pauseRetries(true)
        assertTrue(manager.connectionProgress.value.retriesPaused)
        assertEquals(0L, manager.connectionProgress.value.retryAtMs)
        assertEquals(PhoneConnectionPreference.MANUAL, manager.phonePreference("private"))
        manager.pauseRetries(false)
        assertFalse(manager.connectionProgress.value.retriesPaused)
        assertEquals(PhoneConnectionPreference.MANUAL, manager.phonePreference("private"))
        assertFalse(manager.projectionSessionRequested)
    }
    @Test fun typedReasonCarriesSpecificRecoveryRatherThanParsingStatusText() {
        ReflectionHelpers.callInstanceMethod<Unit>(manager, "setFailure", ReflectionHelpers.ClassParameter.from(ConnectionFailure::class.java, ConnectionFailure.CORRUPT_PACKET))
        assertEquals(ConnectionFailure.CORRUPT_PACKET, manager.connectionProgress.value.failure)
        assertTrue(manager.connectionProgress.value.failure.retryable)
        ReflectionHelpers.callInstanceMethod<Unit>(manager, "setFailure", ReflectionHelpers.ClassParameter.from(ConnectionFailure::class.java, ConnectionFailure.PERMISSION_NEEDED))
        assertFalse(manager.connectionProgress.value.failure.retryable)
    }
    @Test fun queuedOldTransportErrorCannotTearDownReplacementSession() = runBlocking {
        val mutex = ReflectionHelpers.getField<Mutex>(manager, "lifecycleMutex")
        val generation = ReflectionHelpers.getField<AtomicLong>(manager, "transportGeneration")
        val pending = ReflectionHelpers.getField<AtomicBoolean>(manager, "errorRecoveryPending")
        ReflectionHelpers.getField<AtomicBoolean>(manager, "shouldBeRunning").set(true)
        generation.set(1L)
        mutex.lock()
        try {
            val method = CabinManager::class.java.getDeclaredMethod("handleError", String::class.java, ConnectionFailure::class.java, java.lang.Long.TYPE)
            method.isAccessible = true
            method.invoke(manager, "old adapter error", ConnectionFailure.USB_DETACHED, 1L)
            assertTrue(pending.get())
            generation.set(2L)
            ReflectionHelpers.getField<AtomicReference<CabinManager.State>>(manager, "currentState").set(CabinManager.State.STREAMING)
        } finally { mutex.unlock() }
        withTimeout(5_000) { while (pending.get()) delay(10) }
        assertEquals(CabinManager.State.STREAMING, manager.state)
        assertEquals(ConnectionFailure.NONE, manager.connectionProgress.value.failure)
        assertEquals(2L, generation.get())
    }
    private fun invokeStage(stage: ConnectionStage) {
        ReflectionHelpers.callInstanceMethod<Unit>(manager, "stage", ReflectionHelpers.ClassParameter.from(ConnectionStage::class.java, stage))
    }
}
