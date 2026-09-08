package com.cabin

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager.ProjectionAction
import com.cabin.protocol.AdapterDriver
import com.cabin.protocol.CommandMapping
import com.cabin.protocol.HEADER_SIZE
import com.cabin.usb.UsbDeviceWrapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The actual manager dispatches into a recording USB endpoint, without real adapter access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [CabinProjectionActionTest.RecordingConnection::class])
class CabinProjectionActionTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var manager: CabinManager
    private lateinit var recording: RecordingConnection

    @Before
    fun setUp() {
        manager = CabinManager(application)
        val (driver, endpoint) = recordingDriver()
        recording = endpoint
        ReflectionHelpers.setField(manager, "adapterDriver", driver)
        wanted().set(true)
        ReflectionHelpers.getField<AtomicBoolean>(manager, "phoneAutoConnectEnabled").set(true)
        connection().set(CabinManager.State.STREAMING)
    }

    @After
    fun tearDown() = runBlocking { manager.releaseAndWait() }

    @Test
    fun `only a live streaming session can accept phone actions`() {
        CabinManager.State.entries.filter { it != CabinManager.State.STREAMING }.forEach {
            connection().set(it)
            assertFalse(manager.performProjectionAction(ProjectionAction.VOICE))
        }
        connection().set(CabinManager.State.STREAMING)
        wanted().set(false)
        assertFalse(manager.performProjectionAction(ProjectionAction.NEXT))
        wanted().set(true)
        ReflectionHelpers.setField(manager, "adapterDriver", null)
        assertFalse(manager.performProjectionAction(ProjectionAction.NEXT))
        assertTrue(recording.commands.isEmpty())
    }

    @Test
    fun `voice is a complete off main click and duplicate taps cannot interrupt it`() =
        runBlocking {
            val pressed = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            recording.onWrite = { command ->
                if (command == CommandMapping.SIRI.id) {
                    pressed.countDown()
                    check(releaseWrite.await(5, TimeUnit.SECONDS))
                }
            }
            try {
                assertTrue(manager.performProjectionAction(ProjectionAction.VOICE))
                assertTrue(pressed.await(5, TimeUnit.SECONDS))
                assertFalse(manager.performProjectionAction(ProjectionAction.NEXT))
                releaseWrite.countDown()
                awaitDispatch()
                assertEquals(listOf(CommandMapping.SIRI.id, CommandMapping.SIRI_BUTTON_UP.id), recording.commands)
                assertFalse(recording.wroteOnMain.get())
                assertTrue(manager.performProjectionAction(ProjectionAction.NEXT))
                awaitDispatch()
                assertEquals(CommandMapping.NEXT.id, recording.commands.last())
            } finally {
                releaseWrite.countDown()
            }
        }

    @Test
    fun `queued action cannot move into a replacement USB session`() =
        runBlocking {
            val lock = ReflectionHelpers.getField<Mutex>(manager, "lifecycleMutex")
            val (replacement, replacementRecording) = recordingDriver()
            lock.lock()
            try {
                assertTrue(manager.performProjectionAction(ProjectionAction.PREVIOUS))
                ReflectionHelpers.setField(manager, "adapterDriver", replacement)
            } finally {
                lock.unlock()
            }
            awaitDispatch()
            assertTrue(recording.commands.isEmpty())
            assertTrue(replacementRecording.commands.isEmpty())
        }

    @Test
    fun `stop intent cancels an admitted action waiting for lifecycle ownership`() =
        runBlocking {
            val lock = ReflectionHelpers.getField<Mutex>(manager, "lifecycleMutex")
            lock.lock()
            try {
                assertTrue(manager.performProjectionAction(ProjectionAction.PLAY_PAUSE))
                wanted().set(false)
            } finally {
                lock.unlock()
            }
            awaitDispatch()
            assertTrue(recording.commands.isEmpty())
            assertFalse(manager.performProjectionAction(ProjectionAction.PLAY_PAUSE))
        }

    @Test
    fun `new phone selection invalidates a queued action before the state changes`() =
        runBlocking {
            val lock = ReflectionHelpers.getField<Mutex>(manager, "lifecycleMutex")
            lock.lock()
            try {
                assertTrue(manager.performProjectionAction(ProjectionAction.NEXT))
                ReflectionHelpers.getField<AtomicReference<Any>>(manager, "phoneConnectionRequest").set(Any())
            } finally {
                lock.unlock()
            }
            awaitDispatch()
            assertTrue(recording.commands.isEmpty())
            ReflectionHelpers.getField<AtomicBoolean>(manager, "phoneAutoConnectEnabled").set(false)
            assertFalse(manager.performProjectionAction(ProjectionAction.NEXT))
        }

    @Test
    fun `voice release is attempted even when its press write fails`() =
        runBlocking {
            recording.failCommand = CommandMapping.SIRI.id
            assertTrue(manager.performProjectionAction(ProjectionAction.VOICE))
            awaitDispatch()
            assertEquals(listOf(CommandMapping.SIRI.id, CommandMapping.SIRI_BUTTON_UP.id), recording.commands)
        }

    @Test
    fun `play pause and previous use only their existing command mappings`() =
        runBlocking {
            assertTrue(manager.performProjectionAction(ProjectionAction.PLAY_PAUSE))
            awaitDispatch()
            assertTrue(manager.performProjectionAction(ProjectionAction.PREVIOUS))
            awaitDispatch()
            assertEquals(listOf(CommandMapping.PLAY_PAUSE.id, CommandMapping.PREV.id), recording.commands)
        }

    private fun wanted(): AtomicBoolean = ReflectionHelpers.getField(manager, "shouldBeRunning")

    private fun connection(): AtomicReference<CabinManager.State> = ReflectionHelpers.getField(manager, "currentState")

    private suspend fun awaitDispatch() {
        withTimeout(5_000) {
            while (ReflectionHelpers.getField<AtomicBoolean>(manager, "projectionActionPending").get()) delay(1)
        }
    }

    private fun recordingDriver(): Pair<AdapterDriver, RecordingConnection> {
        val wrapper =
            UsbDeviceWrapper(
                application,
                application.getSystemService(Context.USB_SERVICE) as UsbManager,
                Shadow.newInstanceOf(UsbDevice::class.java),
                {},
            )
        val endpoint = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        val recording = Shadow.extract<RecordingConnection>(endpoint)
        ReflectionHelpers.setField(wrapper, "connection", endpoint)
        ReflectionHelpers.setField(wrapper, "outEndpoint", Shadow.newInstanceOf(UsbEndpoint::class.java))
        val driver = AdapterDriver(wrapper, {}, {}, {})
        ReflectionHelpers.getField<AtomicBoolean>(driver, "isRunning").set(true)
        return driver to recording
    }

    @Implements(UsbDeviceConnection::class)
    class RecordingConnection {
        val commands = CopyOnWriteArrayList<Int>()
        val wroteOnMain = AtomicBoolean(false)
        var onWrite: (Int) -> Unit = {}
        var failCommand: Int? = null

        @Implementation
        fun bulkTransfer(
            endpoint: UsbEndpoint,
            buffer: ByteArray,
            offset: Int,
            length: Int,
            timeout: Int,
        ): Int {
            if (length < HEADER_SIZE + 4) return length
            val command = ByteBuffer.wrap(buffer, offset + HEADER_SIZE, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (Looper.myLooper() == Looper.getMainLooper()) wroteOnMain.set(true)
            commands.add(command)
            onWrite(command)
            return if (command == failCommand) -1 else length
        }
    }
}
