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
@Config(sdk = [35], manifest = Config.NONE)
class SyuBinderTransportTest {
    private fun remote(body: (Int, Parcel, Parcel) -> Boolean) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            assertEquals(0, flags)
            data.enforceInterface(SyuBinderTransport.MODULE_DESCRIPTOR)
            return body(code, data, requireNotNull(reply))
        }
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

    @Test fun `unhandled and empty replies fail`() {
        assertThrows(RemoteException::class.java) {
            SyuBinderTransport.unregister(remote { _, _, _ -> false }, Binder(), 0)
        }
        assertThrows(RemoteException::class.java) {
            SyuBinderTransport.unregister(remote { _, _, _ -> true }, Binder(), 0)
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
