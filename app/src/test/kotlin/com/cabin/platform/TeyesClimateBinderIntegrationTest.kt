package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class TeyesClimateBinderIntegrationTest {
    private lateinit var context: ToolkitContext
    private lateinit var controller: TeyesClimateController
    private lateinit var worker: HandlerThread

    @Before
    fun setup() {
        context = ToolkitContext(ApplicationProvider.getApplicationContext())
        TeyesVehicleDataPreferences.get(context).select(TeyesVehicleDataLayout.CIVIC_0298)
        controller = TeyesClimateController(context) {}
        worker =
            TeyesClimateController::class.java.getDeclaredField("worker").let {
                it.isAccessible = true
                it.get(controller) as HandlerThread
            }
        assertTrue(controller.start())
        drain()
    }

    @After
    fun cleanup() {
        // Capture the looper before close: HandlerThread.getLooper() becomes null after exit.
        val looper = worker.looper
        controller.close()
        try {
            shadowOf(looper).idle()
        } catch (error: IllegalStateException) {
            // close() can finish on the worker before Robolectric submits its idle task.
            // Only this already-quitting outcome is expected; all other failures propagate.
            if (error.message != "Looper is quitting") throw error
        }
        worker.join(1_000)
        assertFalse("Vehicle worker must terminate after close", worker.isAlive)
        TeyesVehicleDataPreferences.get(context).select(TeyesVehicleDataLayout.LEGACY)
    }

    @Test
    fun `reference Binder fields normalize and commands need actual fresh feedback`() {
        assertTrue(context.module.registrations.containsAll(listOf(1000, 0, 1, 2, 3, 4, 5, 11, 18, 19, 21, 179, 180, 181)))
        emit(1000, 1048874)
        controller.setAc(true)
        drain()
        assertTrue(context.module.commands.isEmpty())

        emit(11, 0)
        emit(21, 3)
        emit(19, 1)
        emit(20, 1)
        emit(1, 1)
        emit(179, 1)
        emit(180, 1)
        emit(181, 80)
        emit(137, 1)
        emit(89, 3)
        emit(90, 2)

        val state = controller.state.value
        assertTrue(state.controlsAvailable)
        assertFalse(state.ac)
        assertEquals(3, state.fanLevel)
        assertTrue(state.blowBody)
        assertTrue(state.blowFoot)
        assertTrue(state.frontLeftDoorOpen)
        assertEquals(-80, state.oilServiceDistance)
        assertTrue(state.oilServiceDistanceMiles)
        assertNull(state.oilLifePercent)
        assertNull(state.speedKph)
        assertNull(state.engineRpm)

        controller.setAc(true)
        controller.setFan(5)
        controller.setAirflow(TeyesAirflowMode.UP_FOOT)
        drain()
        assertEquals(listOf(105 to listOf(172, 1), 105 to listOf(173, 5), 105 to listOf(172, 6)), context.module.commands)
        assertFalse("Commands must not invent a positive A/C acknowledgement", controller.state.value.ac)
    }

    @Test
    fun `retry replaces callback owner and rejects old cached feedback`() {
        emit(1000, 1048874)
        emit(11, 1)
        emit(21, 4)
        val oldCallback = requireNotNull(context.module.callback)
        controller.retryConnection()
        drain()
        assertTrue(oldCallback !== context.module.callback)
        send(oldCallback, 1000, 1048874)
        send(oldCallback, 11, 1)
        send(oldCallback, 21, 7)
        drain()
        assertFalse(controller.state.value.controlsAvailable)
        assertEquals(0, controller.state.value.profileId)
        emit(1000, 1048874)
        emit(11, 0)
        emit(21, 2)
        assertTrue(controller.state.value.controlsAvailable)
        assertEquals(2, controller.state.value.fanLevel)
    }

    private fun drain() = shadowOf(worker.looper).idle()

    private fun emit(
        code: Int,
        value: Int,
    ) {
        send(requireNotNull(context.module.callback), code, value)
        drain()
    }

    private fun send(
        callback: IBinder,
        code: Int,
        value: Int,
    ) {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInterfaceToken("com.syu.ipc.IModuleCallback")
            parcel.writeInt(code)
            parcel.writeIntArray(intArrayOf(value))
            parcel.writeFloatArray(null)
            parcel.writeStringArray(null)
            assertTrue(callback.transact(1, parcel, null, IBinder.FLAG_ONEWAY))
        } finally {
            parcel.recycle()
        }
    }

    private class ToolkitContext(base: Context) : ContextWrapper(base) {
        val module = Module()
        private val toolkit =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    assertEquals(1, code)
                    data.enforceInterface("com.syu.ipc.IRemoteToolkit")
                    assertEquals(7, data.readInt())
                    requireNotNull(reply).writeNoException()
                    reply.writeStrongBinder(module)
                    return true
                }
            }

        override fun getApplicationContext(): Context = this

        override fun bindService(
            service: Intent,
            conn: ServiceConnection,
            flags: Int,
        ): Boolean {
            conn.onServiceConnected(ComponentName("com.syu.ms", "app.ToolkitService"), toolkit)
            return true
        }

        override fun unbindService(conn: ServiceConnection) = Unit
    }

    private class Module : Binder() {
        var callback: IBinder? = null
        val registrations = mutableSetOf<Int>()
        val commands = mutableListOf<Pair<Int, List<Int>>>()

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            data.enforceInterface("com.syu.ipc.IRemoteModule")
            when (code) {
                1 -> {
                    commands += data.readInt() to requireNotNull(data.createIntArray()).toList()
                    data.createFloatArray()
                    data.createStringArray()
                }
                3 -> {
                    callback = data.readStrongBinder()
                    registrations += data.readInt()
                    assertEquals(1, data.readInt())
                }
                4 -> {
                    data.readStrongBinder()
                    data.readInt()
                }
                else -> return false
            }
            return true
        }
    }
}
