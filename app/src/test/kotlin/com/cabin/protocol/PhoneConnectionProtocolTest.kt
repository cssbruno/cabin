package com.cabin.protocol

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import androidx.test.core.app.ApplicationProvider
import com.cabin.usb.UsbDeviceWrapper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [PhoneConnectionProtocolTest.RecordingConnection::class])
class PhoneConnectionProtocolTest {
    @Test
    fun `idle phone configuration disables firmware autoconnect and explicit resume restores it`() {
        for (enabled in listOf(false, true)) {
            val wire = MessageSerializer.serializeBoxSettings(AdapterConfig.DEFAULT.copy(autoConnectPhone = enabled), syncTime = 0)
            val payload = JSONObject(String(wire.copyOfRange(HEADER_SIZE, wire.size), Charsets.UTF_8))
            assertEquals(enabled, payload.getBoolean("autoConn"))
        }
    }

    @Test
    fun `paused connection rejects scan and target but keeps adapter commands working`() {
        val allowed = AtomicBoolean(false)
        val (driver, writes) = driver { allowed.get() }
        assertFalse(driver.sendCommand(CommandMapping.WIFI_CONNECT))
        assertFalse(driver.sendAutoConnectByBtAddress("11:22:33:44:55:66"))
        assertTrue(writes.isEmpty())
        assertTrue(driver.sendGetBtOnlineList())
        assertEquals(1, writes.size)
        allowed.set(true)
        assertTrue(driver.overrideAutoConnectWithTarget("11:22:33:44:55:66"))
        assertEquals(2, writes.size)
        assertFalse(driver.sendCommand(CommandMapping.WIFI_CONNECT))
    }

    @Test
    fun `disconnect then explicit connect cannot revive a scan waiting for the USB writer`() {
        val admitted = CountDownLatch(1)
        val allowed = AtomicBoolean(true)
        val (driver, writes) = driver {
            admitted.countDown()
            allowed.get()
        }
        val writeLock = ReflectionHelpers.getField<Any>(driver, "writeLock")
        val result = AtomicBoolean(true)
        val worker: Thread
        synchronized(writeLock) {
            worker = thread { result.set(driver.sendCommand(CommandMapping.WIFI_CONNECT)) }
            assertTrue(admitted.await(2, TimeUnit.SECONDS))
            allowed.set(false)
            driver.cancelAutoConnect()
            allowed.set(true)
        }
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertFalse(result.get())
        assertTrue(writes.isEmpty())
        assertTrue(driver.overrideAutoConnectWithTarget("66:55:44:33:22:11"))
        assertEquals(1, writes.size)
    }

    private fun driver(allowed: () -> Boolean): Pair<AdapterDriver, List<ByteArray>> {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val wrapper = UsbDeviceWrapper(
            application,
            application.getSystemService(Context.USB_SERVICE) as UsbManager,
            Shadow.newInstanceOf(UsbDevice::class.java),
            {},
        )
        val connection = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        val recording = Shadow.extract<RecordingConnection>(connection)
        ReflectionHelpers.setField(wrapper, "connection", connection)
        ReflectionHelpers.setField(wrapper, "outEndpoint", Shadow.newInstanceOf(UsbEndpoint::class.java))
        val driver = AdapterDriver(wrapper, {}, { throw AssertionError(it) }, {}, phoneConnectionAllowed = allowed)
        ReflectionHelpers.getField<AtomicBoolean>(driver, "isRunning").set(true)
        return driver to recording.writes
    }

    @Implements(UsbDeviceConnection::class)
    class RecordingConnection {
        val writes = CopyOnWriteArrayList<ByteArray>()

        @Implementation
        fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int {
            writes.add(buffer.copyOfRange(offset, offset + length))
            return length
        }
    }
}
