package com.cabin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cabin.platform.TeyesFeaturePreferences
import com.cabin.protocol.Message
import com.cabin.protocol.MessageHeader
import com.cabin.protocol.MessageType
import com.cabin.protocol.UnpluggedMessage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CabinPhoneDisconnectTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun clearPhoneIntent() {
        application.getSharedPreferences("phone_connection_intent", 0).edit().clear().commit()
    }

    @Test
    fun `intentional disconnect suppresses unplug recovery without stopping adapter intent`() = runBlocking {
        val manager = CabinManager(application)
        val wanted = ReflectionHelpers.getField<AtomicBoolean>(manager, "shouldBeRunning")
        val lock = ReflectionHelpers.getField<Mutex>(manager, "lifecycleMutex")
        wanted.set(true)
        ReflectionHelpers.setField(manager, "pendingConnectTarget", "11:22:33:44:55:66")
        lock.lock()
        try {
            // Hold initialization/teardown busy: intent must take effect immediately,
            // before the queued Disconnect worker can acquire the lifecycle lock.
            val disconnect = manager.disconnectPhone()
            try {
                assertTrue(wanted.get())
                assertFalse(ReflectionHelpers.getField<AtomicBoolean>(manager, "phoneAutoConnectEnabled").get())
                assertNull(ReflectionHelpers.getField<String?>(manager, "pendingConnectTarget"))
                ReflectionHelpers.callInstanceMethod<Unit>(
                    manager,
                    "handleMessage",
                    ReflectionHelpers.ClassParameter.from(
                        Message::class.java,
                        UnpluggedMessage(MessageHeader(0, MessageType.UNPLUGGED)),
                    ),
                )
                assertFalse(ReflectionHelpers.getField<AtomicBoolean>(manager, "restartPending").get())
            } finally {
                disconnect.cancelAndJoin()
            }
        } finally {
            lock.unlock()
            manager.releaseAndWait()
        }
    }

    @Test
    fun `paused phone intent survives teardown and preferred phone until explicit selection`() = runBlocking {
        val preferences = TeyesFeaturePreferences.get(application)
        val original = preferences.profile.value
        preferences.update { it.copy(preferredPhone = "11:22:33:44:55:66") }
        val manager = CabinManager(application)
        val lock = ReflectionHelpers.getField<Mutex>(manager, "lifecycleMutex")
        try {
            manager.disconnectPhone().join()
            manager.stopAndWait()
            manager.resumeRequestedSession()
            val replacement = CabinManager(application)
            try {
                assertNull(ReflectionHelpers.callInstanceMethod<String?>(replacement, "phoneConnectTarget"))
                assertFalse(ReflectionHelpers.getField<AtomicBoolean>(replacement, "phoneAutoConnectEnabled").get())
            } finally {
                replacement.releaseAndWait()
            }
            assertNull(ReflectionHelpers.callInstanceMethod<String?>(manager, "phoneConnectTarget"))
            assertFalse(ReflectionHelpers.getField<AtomicBoolean>(manager, "phoneAutoConnectEnabled").get())

            lock.lock()
            try {
                manager.connectToDevice("66:55:44:33:22:11")
                assertTrue(ReflectionHelpers.getField<AtomicBoolean>(manager, "phoneAutoConnectEnabled").get())
                assertEquals("66:55:44:33:22:11", ReflectionHelpers.callInstanceMethod<String?>(manager, "phoneConnectTarget"))
                // Keep this preference/intent test independent of actual USB discovery.
                ReflectionHelpers.getField<AtomicBoolean>(manager, "shouldBeRunning").set(false)
            } finally {
                lock.unlock()
            }
        } finally {
            manager.releaseAndWait()
            preferences.update { original }
        }
    }
}
