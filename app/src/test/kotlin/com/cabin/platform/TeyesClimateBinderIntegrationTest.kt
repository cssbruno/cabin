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
    fun `fresh fan feedback permits fan commands without an AC update`() {
        emit(1000, 1048874)
        emit(21, 3)
        assertTrue(controller.state.value.fanControlsAvailable)
        assertFalse(controller.state.value.controlsAvailable)
        controller.setFan(6)
        controller.setAc(true)
        drain()
        assertEquals(listOf(105 to listOf(173, 6)), context.module.commands)
        assertEquals(3, controller.state.value.fanLevel)
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

    @Test fun `RZC Civic temperature taps send exact SYU press release pairs and wait for feedback`() {
        emit(1000, 1048874)
        emit(27, 44)
        emit(28, 46)
        controller.adjustTemperature(TeyesTemperatureZone.DRIVER, true)
        drain()
        assertTrue(context.module.commands.isEmpty()) // Unit metadata is mandatory.
        emit(37, 0)
        assertEquals(44, controller.state.value.leftTemperature)
        assertEquals(46, controller.state.value.rightTemperature)
        assertFalse(controller.state.value.fahrenheit)
        for ((zone, up) in listOf(TeyesTemperatureZone.DRIVER to true, TeyesTemperatureZone.DRIVER to false,
            TeyesTemperatureZone.PASSENGER to true, TeyesTemperatureZone.PASSENGER to false)) {
            controller.adjustTemperature(zone, up)
            drain()
        }
        assertEquals(listOf(3, 2, 5, 4).flatMap { listOf(107 to listOf(it, 1), 107 to listOf(it, 0)) }, context.module.commands)
        assertEquals(44, controller.state.value.leftTemperature)
        emit(27, 45)
        assertEquals(45, controller.state.value.leftTemperature)
    }

    @Test fun `temperature limits block outward steps but permit returning from LOW and HIGH`() {
        emit(1000, 1114410)
        emit(27, -2)
        emit(28, -3)
        emit(37, 0)
        controller.adjustTemperature(TeyesTemperatureZone.DRIVER, false)
        controller.adjustTemperature(TeyesTemperatureZone.PASSENGER, true)
        drain()
        assertTrue(context.module.commands.isEmpty())
        controller.adjustTemperature(TeyesTemperatureZone.DRIVER, true)
        controller.adjustTemperature(TeyesTemperatureZone.PASSENGER, false)
        drain()
        assertEquals(listOf(107 to listOf(3, 1), 107 to listOf(3, 0), 107 to listOf(4, 1), 107 to listOf(4, 0)), context.module.commands)
    }

    @Test fun `XP Civic does not inherit RZC temperature writes`() {
        emit(1000, 196906)
        emit(11, 1)
        emit(21, 3)
        emit(27, 44)
        emit(28, 46)
        emit(37, 0)
        controller.adjustTemperature(TeyesTemperatureZone.DRIVER, true)
        drain()
        assertTrue(context.module.commands.isEmpty())
        assertNull(controller.state.value.leftTemperature)
    }

    @Test fun `stale temperature feedback disables writes even while other vehicle data stays live`() {
        emit(1000, 1048874)
        emit(27, 44)
        emit(37, 0)
        shadowOf(worker.looper).idleFor(61, java.util.concurrent.TimeUnit.SECONDS)
        emit(21, 3)
        controller.adjustTemperature(TeyesTemperatureZone.DRIVER, true)
        drain()
        assertTrue(context.module.commands.isEmpty())
        assertFalse(25 in controller.state.value.availableCodes)
    }

    @Test fun `complete RZC climate switches use source verified keys and confirmed states`() {
        emit(1000, 1048874)
        mapOf(10 to 1, 12 to 1, 13 to 1, 14 to 1, 65 to 1, 16 to 1).forEach { (code, value) -> emit(code, value) }
        val state = controller.state.value
        assertTrue(state.power && state.auto && state.dual && state.recirculating && state.frontDefrost && state.rearDefrost)
        val controls = listOf(TeyesClimateSwitch.POWER, TeyesClimateSwitch.AUTO, TeyesClimateSwitch.DUAL,
            TeyesClimateSwitch.RECIRCULATION, TeyesClimateSwitch.FRONT_DEFROST, TeyesClimateSwitch.REAR_DEFROST)
        controls.forEach { controller.toggleClimate(it); drain() }
        assertEquals(listOf(1, 21, 16, 25, 19, 20).flatMap { listOf(107 to listOf(it, 1), 107 to listOf(it, 0)) }, context.module.commands)
        assertTrue(controller.state.value.auto) // No optimistic state mutation.
        emit(13, 0)
        assertFalse(controller.state.value.auto)
    }

    @Test fun `switch commands require their own fresh valid feedback`() {
        emit(1000, 1048874)
        emit(11, 1)
        emit(21, 3)
        controller.toggleClimate(TeyesClimateSwitch.AUTO)
        drain()
        assertTrue(context.module.commands.isEmpty())
        emit(13, 1)
        controller.toggleClimate(TeyesClimateSwitch.AUTO)
        drain()
        assertEquals(listOf(107 to listOf(21, 1), 107 to listOf(21, 0)), context.module.commands)
        shadowOf(worker.looper).idleFor(61, java.util.concurrent.TimeUnit.SECONDS)
        emit(21, 3)
        controller.toggleClimate(TeyesClimateSwitch.AUTO)
        drain()
        assertEquals(2, context.module.commands.size)
    }

    @Test fun `reconnecting drops pending temperature switch AC and fan actions`() {
        emit(1000, 1048874)
        mapOf(11 to 1, 21 to 3, 13 to 1, 27 to 44, 37 to 0).forEach { (code, value) -> emit(code, value) }
        controller.retryConnection()
        controller.adjustTemperature(TeyesTemperatureZone.DRIVER, true)
        controller.toggleClimate(TeyesClimateSwitch.AUTO)
        controller.setAc(false)
        controller.setFan(5)
        drain()
        assertTrue(context.module.commands.isEmpty())
    }

    @Test fun `generic vehicle data cannot become legacy motion readings`() {
        emit(1000, 21)
        emit(89, 3)
        emit(90, 4)
        emit(1, 1)
        val state = controller.state.value
        assertNull(state.speedKph)
        assertNull(state.engineRpm)
        assertTrue(state.frontLeftDoorOpen)
        assertTrue(37 in state.availableCodes)
    }

    @Test fun `BNR lighting commands require fresh feedback and preserve enum values`() {
        emit(1000, 393514)
        controller.setVehicleLighting(SyuLightingSetting.HEADLIGHT_DELAY, 2)
        drain()
        assertTrue(context.module.commands.isEmpty())
        emit(123, 1)
        controller.setVehicleLighting(SyuLightingSetting.HEADLIGHT_DELAY, 2)
        controller.setVehicleLighting(SyuLightingSetting.HEADLIGHT_DELAY, 4)
        drain()
        assertEquals(listOf(105 to listOf(5, 2)), context.module.commands)
    }

    @Test fun `factory amplifier rejects stale unknown and out of range commands`() {
        emit(1000, 393537)
        controller.setFactoryAmplifier(SyuAmplifierSetting.BALANCE, 10)
        drain()
        assertTrue(context.module.commands.isEmpty())
        emit(201, 9)
        controller.setFactoryAmplifier(SyuAmplifierSetting.BALANCE, 10)
        controller.setFactoryAmplifier(SyuAmplifierSetting.BALANCE, 19)
        drain()
        assertEquals(listOf(2 to listOf(2, 10)), context.module.commands)
        assertEquals(9, controller.state.value.syuVehicle.amplifier[SyuAmplifierSetting.BALANCE])
    }

    @Test fun `tire discovery queries once and publishes independent wheel data`() {
        emit(1000, 1376590)
        emit(1000, 1376590)
        assertEquals(listOf(0 to listOf(0)), context.module.commands)
        emit(146, 80)
        emit(150, 2)
        assertEquals(220.0, controller.state.value.syuVehicle.tires[0].pressureKpa!!, 0.0001)
        assertEquals(2, controller.state.value.syuVehicle.tires[0].warning)
        assertNull(controller.state.value.syuVehicle.tires[1].pressureKpa)
    }

    @Test fun `camera mode is not a door sample and sends its own transport frame`() {
        emit(1000, 131109)
        emit(4, 1)
        assertFalse(controller.state.value.rearRightDoorOpen)
        assertFalse(controller.state.value.doorsAvailable)
        assertEquals(1, controller.state.value.syuVehicle.factoryControls[SyuFactoryControl.CAMERA_MODE])
        controller.setFactoryControl(SyuFactoryControl.CAMERA_MODE, 2)
        controller.setFactoryControl(SyuFactoryControl.MIRROR_SYNC, 1)
        drain()
        assertEquals(listOf(2 to listOf(2)), context.module.commands)
    }

    @Test fun `mirror availability is checked again before dispatch`() {
        emit(1000, 17)
        emit(148, 1) // Value alone is not a WC capability flag.
        controller.setFactoryControl(SyuFactoryControl.MIRROR_SYNC, 1)
        drain()
        assertTrue(context.module.commands.isEmpty())
        emit(148, 0x100)
        controller.setFactoryControl(SyuFactoryControl.MIRROR_SYNC, 1)
        drain()
        assertEquals(listOf(67 to listOf(1)), context.module.commands)
        assertEquals(0, controller.state.value.syuVehicle.factoryControls[SyuFactoryControl.MIRROR_SYNC])
    }

    @Test fun `Ford profile dispatches its own Air commands and subscribes to its defined fields`() {
        emit(1000, 21)
        val air = requireNotNull(controller.state.value.syuAir)
        assertTrue(air.actions.contains("C_AIR_TEMP_LEFT_ADD"))
        emit(27, 44)
        controller.sendAirAction("C_AIR_TEMP_LEFT_ADD")
        drain()
        assertEquals(listOf(0 to listOf(13, 1), 0 to listOf(13, 0)), context.module.commands)
        assertEquals(44, controller.state.value.syuAir?.readings?.get("U_AIR_TEMP_LEFT"))
        controller.sendAirAction("UNREGISTERED_ACTION")
        drain()
        assertEquals(2, context.module.commands.size)
    }

    @Test fun `charging sends verified frames only after capability feedback`() {
        emit(1000, 655377)
        context.module.commands.clear()
        emit(300, 13)
        controller.setFactoryControl(SyuFactoryControl.CHARGE_CURRENT, 3)
        drain()
        assertTrue(context.module.commands.isEmpty())
        emit(299, 128)
        controller.setFactoryControl(SyuFactoryControl.CHARGE_CURRENT, 3)
        drain()
        assertEquals(listOf(145 to listOf(1, 255)), context.module.commands)
        assertEquals(2, controller.state.value.syuVehicle.factoryControls[SyuFactoryControl.CHARGE_CURRENT])
    }

    @Test fun `ambient controls send their verified frame`() {
        emit(1000, 4260138)
        emit(270, 1)
        context.module.commands.clear()
        controller.setFactoryControl(SyuFactoryControl.AMBIENT_PALETTE, 1)
        drain()
        assertEquals(listOf(109 to listOf(1, 2)), context.module.commands)
    }

    @Test fun `seat preset uses direct verified command`() {
        emit(1000, 1769874)
        emit(200, 0)
        context.module.commands.clear()
        controller.setFactoryControl(SyuFactoryControl.SEAT_PRESET, 2)
        drain()
        assertEquals(listOf(1 to listOf(152, 2)), context.module.commands)
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
        val reply = Parcel.obtain()
        try {
            parcel.writeInterfaceToken("com.syu.ipc.IModuleCallback")
            parcel.writeInt(code)
            parcel.writeIntArray(intArrayOf(value))
            parcel.writeFloatArray(null)
            parcel.writeStringArray(null)
            assertTrue(callback.transact(1, parcel, reply, 0))
            reply.readException()
        } finally {
            parcel.recycle()
            reply.recycle()
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
            assertEquals(0, flags)
            requireNotNull(reply)
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
            reply.writeNoException()
            return true
        }
    }
}
