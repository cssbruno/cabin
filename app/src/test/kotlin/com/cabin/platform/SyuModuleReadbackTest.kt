package com.cabin.platform

import android.os.BadParcelableException
import android.os.Binder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, shadows = [Utf16ParcelShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SyuModuleReadbackTest {
    @Test fun `get uses synchronous module transaction with typed request and result`() {
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(2, code)
                assertEquals(0, flags)
                data.enforceInterface("com.syu.ipc.IRemoteModule")
                assertEquals(123, data.readInt()) // Synthetic test code, not a hardware command.
                assertArrayEquals(intArrayOf(7, -3), data.createIntArray())
                assertArrayEquals(floatArrayOf(0.5f), data.createFloatArray(), 0f)
                assertArrayEquals(arrayOf("input", null), data.createStringArray())
                reply!!.writeNoException()
                reply.writeInt(1)
                reply.writeIntArray(intArrayOf(42))
                reply.writeFloatArray(floatArrayOf(1.25f))
                reply.writeStringArray(arrayOf("Rádio 🎵", null, ""))
                return true
            }
        }
        val result = SyuModuleReadback.get(remote, 123, SyuModuleObject(intArrayOf(7, -3), floatArrayOf(0.5f), arrayOf("input", null)))!!
        assertArrayEquals(intArrayOf(42), result.ints)
        assertArrayEquals(floatArrayOf(1.25f), result.floats, 0f)
        assertArrayEquals(arrayOf("Rádio 🎵", null, ""), result.strings)
    }

    @Test fun `nullable and empty arrays remain distinct`() {
        decode { writeInt(0) }.also { assertNull(it) }
        val nulls = decode { writeInt(1); writeIntArray(null); writeFloatArray(null); writeStringArray(null) }!!
        assertNull(nulls.ints); assertNull(nulls.floats); assertNull(nulls.strings)
        val empty = decode { writeInt(1); writeIntArray(intArrayOf()); writeFloatArray(floatArrayOf()); writeStringArray(arrayOf()) }!!
        assertEquals(0, empty.ints!!.size); assertEquals(0, empty.floats!!.size); assertEquals(0, empty.strings!!.size)
    }

    @Test fun `rejects invalid presence negative and oversized counts before allocation`() {
        assertThrows(BadParcelableException::class.java) { decode { writeInt(2) } }
        for (count in listOf(-2, 1025, Int.MAX_VALUE)) {
            assertThrows(BadParcelableException::class.java) { decode { writeInt(1); writeInt(count) } }
            assertThrows(BadParcelableException::class.java) { decode { writeInt(1); writeInt(-1); writeInt(count) } }
        }
        assertThrows(BadParcelableException::class.java) { decode { writeInt(1); writeInt(-1); writeInt(-1); writeInt(65) } }
    }

    @Test fun `rejects truncated numeric data string data and trailing data`() {
        assertThrows(BadParcelableException::class.java) { decode {} }
        assertThrows(BadParcelableException::class.java) { decode { writeInt(1); writeInt(3); writeInt(5) } }
        assertThrows(BadParcelableException::class.java) { decode { writeInt(1); writeInt(-1); writeInt(-1); writeInt(1); writeInt(10) } }
        assertThrows(BadParcelableException::class.java) { decode { writeInt(1); writeInt(-1); writeInt(-1); writeInt(1); writeInt(Int.MAX_VALUE) } }
        assertThrows(BadParcelableException::class.java) { decode { writeInt(0); writeInt(123) } }
    }

    @Test fun `oversized caller arguments are rejected before remote transaction`() {
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = error("Must not transact")
        }
        assertThrows(IllegalArgumentException::class.java) { SyuModuleReadback.get(remote, 123, SyuModuleObject(ints = IntArray(1025))) }
        assertThrows(IllegalArgumentException::class.java) { SyuModuleReadback.get(remote, 123, SyuModuleObject(strings = arrayOf("x".repeat(4097)))) }
        assertThrows(IllegalArgumentException::class.java) { SyuModuleReadback.get(remote, 123, SyuModuleObject(strings = Array(64) { "x".repeat(4096) })) }
    }

    @Test fun `remote exceptions are propagated instead of decoded as values`() {
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeException(SecurityException("Denied by vendor"))
                return true
            }
        }
        assertThrows(SecurityException::class.java) { SyuModuleReadback.get(remote, 123) }
    }

    private fun decode(write: Parcel.() -> Unit): SyuModuleObject? {
        val parcel = Parcel.obtain()
        try {
            parcel.write()
            parcel.setDataPosition(0)
            return SyuModuleObjectCodec.readNullable(parcel)
        } finally { parcel.recycle() }
    }
}
