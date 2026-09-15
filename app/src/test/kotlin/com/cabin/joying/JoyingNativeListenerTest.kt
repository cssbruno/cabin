package com.cabin.joying

import android.os.Binder
import android.os.Parcel
import android.os.PersistableBundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class JoyingNativeListenerTest {
    private fun send(id: Int, write: (Parcel) -> Unit): JoyingNativeListener.Event? {
        var event: JoyingNativeListener.Event? = null
        val listener = JoyingNativeListener { event = it }
        val request = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(JoyingNativeListener.DESCRIPTOR)
            write(request)
            assertTrue(listener.transact((id shl 8) or 1, request, reply, 0))
            assertEquals(0, reply.readInt())
            return event
        } finally { request.recycle(); reply.recycle() }
    }
    @Test fun `native phone and voice state callbacks preserve their IDs`() {
        for (id in listOf(100, 107, 108, 109)) assertEquals(JoyingNativeListener.Event.State(id, 2), send(id) { it.writeInt(2) })
    }
    @Test fun `native audio and hotspot bundle parsed with presence marker`() {
        val event = send(110) {
            it.writeInt(1)
            PersistableBundle().apply { putInt("\$CarPlay.AudioState", 0x801); putInt("\$CarPlay.WifiApCtl", 1) }.writeToParcel(it, 0)
        } as JoyingNativeListener.Event.Bundle
        assertEquals(0x801, event.values.getInt("\$CarPlay.AudioState"))
        assertEquals(1, event.values.getInt("\$CarPlay.WifiApCtl"))
        assertNull(send(110) { it.writeInt(0) })
    }
    @Test fun `outgoing bluetooth preserves payload`() {
        val bytes = byteArrayOf(1, 2, 3, -1)
        val event = send(111) { it.writeInt(bytes.size); it.writeByteArray(bytes) } as JoyingNativeListener.Event.Bluetooth
        assertArrayEquals(bytes, event.bytes)
    }
    @Test fun `native client registers listener with raw transaction three`() {
        val listener = JoyingNativeListener { }
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(3, code)
                data.enforceInterface(JoyingNativeProtocol.DESCRIPTOR)
                assertSame(listener, data.readStrongBinder())
                reply!!.writeInt(1); reply.writeNoException()
                return true
            }
        }
        JoyingNativeProtocol.registerListener(remote, listener)
    }
    @Test fun `incoming RFCOMM data uses native count and byte array envelope`() {
        val bytes = byteArrayOf(5, 6, 7)
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(0xe602, code)
                data.enforceInterface(JoyingNativeProtocol.DESCRIPTOR)
                assertEquals(3, data.readInt())
                assertArrayEquals(bytes, data.createByteArray())
                assertEquals(0, data.dataAvail())
                reply!!.writeInt(0)
                return true
            }
        }
        JoyingNativeProtocol.bluetoothBytes(remote, bytes)
    }
    @Test fun `RFCOMM connection state is a nullable persistable bundle`() {
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(0xe502, code)
                data.enforceInterface(JoyingNativeProtocol.DESCRIPTOR)
                assertEquals(1, data.readInt())
                assertEquals("SV", PersistableBundle.CREATOR.createFromParcel(data).getString("_btcmd"))
                reply!!.writeInt(0)
                return true
            }
        }
        JoyingNativeProtocol.bluetoothState(remote, "SV")
    }
    @Test fun `hotspot parameters precede SSID and password`() {
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(0xe102, code); data.enforceInterface(JoyingNativeProtocol.DESCRIPTOR)
                assertArrayEquals(intArrayOf(6, 2, 0), IntArray(3) { data.readInt() })
                assertEquals("TestAP", data.readString()); assertEquals("testpass", data.readString())
                reply!!.writeInt(0); return true
            }
        }
        JoyingNativeProtocol.command(remote, 225, intArrayOf(6, 2, 0), listOf("TestAP", "testpass"))
    }
}
