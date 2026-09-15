package com.cabin.joying

import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class JoyingFactoryBluetoothTest {
    @Test fun `native identity validates MAC and bounds UTF8 name`() {
        val value = JoyingFactoryBluetooth.normalized("é".repeat(64), "aa:bb:cc:dd:ee:ff")!!
        assertEquals("AABBCCDDEEFF", value.second)
        assertTrue(value.first.toByteArray(Charsets.UTF_8).size <= 63)
        assertNull(JoyingFactoryBluetooth.normalized("Cabin", "02:00:00:00:00:00"))
        assertNull(JoyingFactoryBluetooth.normalized("Cabin", "not-a-mac"))
        assertNull(JoyingFactoryBluetooth.normalized(null, "aabbccddeeff"))
    }
    @Test fun `factory identity follows nullable AIDL arrays`() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(14); parcel.writeIntArray(null); parcel.writeFloatArray(null)
            parcel.writeStringArray(arrayOf("AA:BB:CC:DD:EE:FF")); parcel.setDataPosition(0)
            assertEquals(14 to "AA:BB:CC:DD:EE:FF", JoyingFactoryBluetooth.readIdentity(parcel))
        } finally { parcel.recycle() }
    }
    @Test fun `unbounded array count is rejected before allocation`() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(15); parcel.writeInt(Int.MAX_VALUE); parcel.writeInt(0); parcel.writeInt(0)
            parcel.setDataPosition(0)
            assertNull(JoyingFactoryBluetooth.readIdentity(parcel))
        } finally { parcel.recycle() }
    }
    @Test fun `hands-free cut is released once on session close`() {
        val commands = mutableListOf<Pair<Int, Int>>()
        val module = object : android.os.Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(com.cabin.platform.SyuBinderTransport.MODULE_DESCRIPTOR)
                if (code == 3) {
                    val callback = data.readStrongBinder()!!
                    val field = data.readInt()
                    val payload = Parcel.obtain()
                    val result = Parcel.obtain()
                    try {
                        payload.writeInterfaceToken(JoyingFactoryBluetooth.CALLBACK)
                        payload.writeInt(field)
                        payload.writeIntArray(when (field) { 9 -> intArrayOf(1); 13 -> intArrayOf(0); else -> null })
                        payload.writeFloatArray(null)
                        payload.writeStringArray(when (field) {
                            6, 14 -> arrayOf("AA:BB:CC:DD:EE:FF")
                            15 -> arrayOf("Joying")
                            else -> null
                        })
                        callback.transact(1, payload, result, 0)
                    } finally { payload.recycle(); result.recycle() }
                } else if (code == 1) {
                    commands += data.readInt() to data.createIntArray()!!.single()
                }
                reply?.writeNoException()
                return true
            }
        }
        val toolkit = object : android.os.Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.syu.ipc.IRemoteToolkit")
                assertEquals(2, data.readInt())
                reply!!.writeNoException(); reply.writeStrongBinder(module)
                return true
            }
        }
        val context = object : android.content.ContextWrapper(androidx.test.core.app.ApplicationProvider.getApplicationContext()) {
            override fun bindService(intent: android.content.Intent, connection: android.content.ServiceConnection, flags: Int): Boolean {
                connection.onServiceConnected(android.content.ComponentName("com.syu.ms", "Toolkit"), toolkit)
                return true
            }
            override fun unbindService(connection: android.content.ServiceConnection) = Unit
        }
        val identities = mutableListOf<Pair<String, String>>()
        val bridge = JoyingFactoryBluetooth(context, { it() }) { name, mac -> identities += name to mac }
        bridge.start()
        assertEquals(listOf("Joying" to "AABBCCDDEEFF"), identities)
        bridge.cutHandsFree()
        bridge.cutHandsFree()
        assertEquals(listOf(13 to 1), commands)
        bridge.close()
        bridge.close()
        assertEquals(listOf(13 to 1, 13 to 0), commands)
    }

}
