package com.cabin.platform

import android.os.Binder
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], manifest = Config.NONE)
class SyuBinderTransportTest {
    private fun remote(body: (Int, Parcel, Parcel) -> Boolean) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            assertEquals(0, flags)
            data.enforceInterface(SyuBinderTransport.MODULE_DESCRIPTOR)
            return body(code, data, requireNotNull(reply))
        }
    }

    @Test fun `direct module binder does not receive a toolkit transaction`() {
        val module = object : Binder() {
            init { attachInterface(null, SyuBinderTransport.MODULE_DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                fail("Direct module must not receive a toolkit lookup")
                return false
            }
        }
        assertSame(module, SyuBinderTransport.getModule(module, 1))
    }

    @Test fun `command arguments match synchronous reference stub`() {
        val module = remote { code, data, reply ->
            assertEquals(1, code)
            assertEquals(42, data.readInt())
            assertArrayEquals(intArrayOf(2, 3), data.createIntArray())
            assertNull(data.createFloatArray())
            assertNull(data.createStringArray())
            reply.writeNoException()
            true
        }
        SyuBinderTransport.transact(module, 1, {
            it.writeInt(42); it.writeIntArray(intArrayOf(2, 3)); it.writeFloatArray(null); it.writeStringArray(null)
        }, {})
    }

    @Test fun `remote exception is surfaced instead of reporting success`() {
        val module = remote { _, _, reply -> reply.writeException(SecurityException("denied")); true }
        assertThrows(SecurityException::class.java) { SyuBinderTransport.register(module, Binder(), 0) }
    }

    @Test fun `Joying void calls accept an empty reply`() {
        val callback = Binder()
        val calls = mutableListOf<Int>()
        val module = remote { code, data, _ ->
            calls += code
            if (code == 3 || code == 4) {
                assertSame(callback, data.readStrongBinder())
                assertEquals(1000, data.readInt())
                if (code == 3) assertEquals(1, data.readInt())
            } else {
                assertEquals(42, data.readInt())
                assertArrayEquals(intArrayOf(2), data.createIntArray())
                assertNull(data.createFloatArray())
                assertNull(data.createStringArray())
            }
            assertEquals(0, data.dataAvail())
            true
        }
        SyuBinderTransport.register(module, callback, 1000)
        SyuBinderTransport.unregister(module, callback, 1000)
        SyuBinderTransport.transact(module, 1, {
            it.writeInt(42); it.writeIntArray(intArrayOf(2)); it.writeFloatArray(null); it.writeStringArray(null)
        }, {})
        assertEquals(listOf(3, 4, 1), calls) // No command retries on an empty response.
    }

    @Test fun `empty toolkit response is never treated as a void module response`() {
        val toolkit = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.syu.ipc.IRemoteToolkit")
                assertEquals(1, code)
                assertEquals(7, data.readInt())
                return true
            }
        }
        assertThrows(RemoteException::class.java) { SyuBinderTransport.getModule(toolkit, 7) }
    }

    @Test fun `unhandled calls and empty readback replies fail`() {
        assertThrows(RemoteException::class.java) {
            SyuBinderTransport.unregister(remote { _, _, _ -> false }, Binder(), 0)
        }
        assertThrows(RemoteException::class.java) {
            SyuBinderTransport.transact(remote { _, _, _ -> true }, 2, {}, {})
        }
    }

    @Test fun `oversized reply is rejected before decoder executes`() {
        var decoded = false
        val module = remote { _, _, reply ->
            reply.writeNoException()
            reply.writeByteArray(ByteArray(SyuBinderTransport.MAX_PARCEL_BYTES))
            true
        }
        assertThrows(RemoteException::class.java) {
            SyuBinderTransport.transact(module, 2, {}, { decoded = true })
        }
        assertFalse(decoded)
    }
}
