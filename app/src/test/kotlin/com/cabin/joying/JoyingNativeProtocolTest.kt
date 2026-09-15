package com.cabin.joying

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.EOFException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class JoyingNativeProtocolTest {
    private val payload = byteArrayOf(0, 0, 0, 1, 0x65, 7, 8)
    private fun packet() = byteArrayOf(payload.size.toByte(), 0, 0, 0) + payload

    @Test fun `initial link state uses stock query with no request payload`() {
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(0xd702, code)
                data.enforceInterface(JoyingNativeProtocol.DESCRIPTOR)
                assertEquals(0, data.dataAvail())
                reply!!.writeInt(4)
                return true
            }
        }
        assertEquals(4, JoyingNativeProtocol.command(binder, JoyingNativeProtocol.LINK_STATE))
    }

    @Test fun `reads fragmented and consecutive length prefixed access units`() {
        val input = object : ByteArrayInputStream(packet() + packet()) {
            override fun read(data: ByteArray, offset: Int, length: Int) = super.read(data, offset, minOf(1, length))
        }
        assertArrayEquals(payload, JoyingNativeProtocol.readFrame(input))
        assertArrayEquals(payload, JoyingNativeProtocol.readFrame(input))
        assertNull(JoyingNativeProtocol.readFrame(input))
    }

    @Test(expected = EOFException::class) fun `truncated frame is rejected`() {
        JoyingNativeProtocol.readFrame(ByteArrayInputStream(packet().dropLast(1).toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class) fun `oversized unsigned length rejected before allocation`() {
        JoyingNativeProtocol.readFrame(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1)))
    }

    @Test(expected = IllegalArgumentException::class) fun `non Annex B data rejected`() {
        JoyingNativeProtocol.readFrame(ByteArrayInputStream(byteArrayOf(4, 0, 0, 0, 1, 2, 3, 4)))
    }

    @Test fun `screen uses shifted native transaction and scalar payload`() {
        var called = false
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(0xd202, code)
                data.enforceInterface(JoyingNativeProtocol.DESCRIPTOR)
                assertEquals(3, data.readInt())
                assertEquals(0, data.dataAvail())
                reply!!.writeInt(0)
                called = true
                return true
            }
        }
        JoyingNativeProtocol.command(binder, JoyingNativeProtocol.SCREEN, intArrayOf(3))
        assertTrue(called)
    }

    @Test fun `touch sends six scalar integers with no array length prefix`() {
        val expected = intArrayOf(120, 50, 1, 600, 400, 0)
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(0xca02, code)
                data.enforceInterface(JoyingNativeProtocol.DESCRIPTOR)
                assertArrayEquals(expected, IntArray(6) { data.readInt() })
                assertEquals(0, data.dataAvail())
                reply!!.writeInt(0)
                return true
            }
        }
        JoyingNativeProtocol.command(binder, JoyingNativeProtocol.TOUCH, expected)
    }

    @Test(expected = IllegalStateException::class) fun `rejected native transaction is surfaced`() {
        JoyingNativeProtocol.command(object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) = false
        }, JoyingNativeProtocol.SCREEN, intArrayOf(3))
    }
}
