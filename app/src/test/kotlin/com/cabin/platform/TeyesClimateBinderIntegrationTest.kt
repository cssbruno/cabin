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
@Config(sdk = [29], manifest = Config.NONE, shadows = [Utf16ParcelShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class TeyesClimateBinderIntegrationTest {
    private lateinit var context: ToolkitContext
    private lateinit var controller: TeyesClimateController
    private lateinit var worker: HandlerThread

    @Before
    fun setup() {
        context = ToolkitContext(ApplicationProvider.getApplicationContext())
        controller = TeyesClimateController(context) {}
        controller.firmwareDetector = { FytFirmware("2.23.0711.1001", TeyesVehicleDataLayout.CIVIC_0298) }
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
            // close() can terminate the worker between idle() checking it and posting its barrier.
            // Only Robolectric's own failed barrier on a verified terminated thread is expected.
            worker.join(1_000)
            val deadBarrier = error.message?.let { it.startsWith("post to Handler ") && it.endsWith("failed. Is handler thread dead?") } == true &&
                error.stackTrace.firstOrNull()?.className == "org.robolectric.shadows.ShadowPausedLooper\$HandlerExecutor" && !worker.isAlive
            if (error.message != "Looper is quitting" && !deadBarrier) throw error
        }
        worker.join(1_000)
        assertFalse("Vehicle worker must terminate after close", worker.isAlive)
    }

    @Test fun `detected callback IDs are subscribed normalized and cleared on profile change`() {
        controller.suspendUpdates(); drain()
        val mapping = FytDetectedProfile(262442, mapOf(24 to 324, 29 to 37, 37 to 337), "partial_match", setOf(38))
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.JOYING_2023,
            profiles = mapOf(262442 to mapping)) }
        controller.resumeUpdates(); drain()
        emit(1000, 262442)
        assertTrue(context.module.registrations.containsAll(listOf(324, 37, 337)))
        emit(324, 1); emit(37, 4); emit(337, 1)
        val state = controller.state.value
        assertTrue(state.ac && state.frontLeftDoorOpen)
        assertEquals(4, state.fanLevel)
        assertEquals("partial_match", state.fytCodeStatus)
        assertEquals(mapping.fields, state.fytDetectedFields)
        assertTrue(state.fytReadOnly)
        assertNull(state.speedKph)
        context.module.commands.clear()
        controller.setAc(false); controller.setFan(2); drain()
        assertTrue(context.module.commands.isEmpty())
        emit(1000, 1048874)
        drain()
        assertFalse(controller.state.value.ac)
        assertFalse(controller.state.value.frontLeftDoorOpen)
    }

    @Test fun `generic profile exposes payloads without inventing gauges and clears on disconnect`() {
        controller.suspendUpdates(); drain()
        val mapping = FytDetectedProfile(999999, emptyMap(), "raw_fields_only", publishedFields = setOf(89, 90, 181, 350))
        controller.firmwareDetector = { FytFirmware("vendor", TeyesVehicleDataLayout.UNKNOWN,
            resolveProfile = { mapping }) }
        controller.resumeUpdates(); drain()
        emit(1000, 999999)
        assertTrue(context.module.registrations.contains(350))
        emit(89, 50); emit(90, 2000); emit(181, 13)
        assertNull(controller.state.value.speedKph)
        assertNull(controller.state.value.engineRpm)
        assertNull(controller.state.value.oilServiceDistance)
        val parcel = Parcel.obtain()
        try {
            parcel.writeInterfaceToken("com.syu.ipc.IModuleCallback")
            parcel.writeInt(350)
            parcel.writeIntArray(intArrayOf(1, 2, 3))
            parcel.writeFloatArray(floatArrayOf(2.5f))
            parcel.writeStringArray(arrayOf("local value"))
            assertTrue(context.module.callback!!.transact(1, parcel, null, IBinder.FLAG_ONEWAY))
        } finally { parcel.recycle() }
        drain()
        assertEquals(FytRawSample(listOf(1, 2, 3), listOf(2.5f), listOf("local value")), controller.state.value.fytRawValues[350])
        controller.suspendUpdates(); drain()
        assertTrue(controller.state.value.fytRawValues.isEmpty())
    }

    @Test fun `MAIN discovery subscribes live only isolates module IDs and drops previous profile callbacks`() {
        controller.suspendUpdates(); drain()
        val mapping = FytDetectedProfile(999998, emptyMap(), "raw_fields_only",
            publishedFields = setOf(89), moduleFields = mapOf(7 to setOf(89), 0 to setOf(89, 18)))
        controller.firmwareDetector = { FytFirmware("vendor", TeyesVehicleDataLayout.UNKNOWN, resolveProfile = { mapping }) }
        controller.resumeUpdates(); drain()
        emit(1000, 999998); drain()
        assertEquals(setOf(89, 18), context.mainModule.registrations)
        val oldCallback = requireNotNull(context.mainModule.callback)
        send(oldCallback, 89, 777); drain()
        emit(89, 12)
        assertEquals(listOf(777), controller.state.value.fytMainRawValues[89]?.integers)
        assertEquals(listOf(12), controller.state.value.fytRawValues[89]?.integers)
        assertNull(controller.state.value.speedKph)
        assertTrue(context.mainModule.commands.isEmpty())
        controller.suspendUpdates(); drain()
        send(oldCallback, 89, 999); drain()
        assertTrue(controller.state.value.fytMainRawValues.isEmpty())
    }

    @Test fun `stock formatted readings drive live health and clear when the profile changes`() {
        val apk = java.io.File("../artifacts/joying-uis7862/extracted/applications/app/190000000_com.syu.canbus/190000000_com.syu.canbus.apk")
        org.junit.Assume.assumeTrue(apk.exists())
        controller.suspendUpdates(); drain()
        val client = FytSyuClientCatalog.resolver(listOf(apk)) { "res:$it" }(262442)
        val mapping = FytDetectedProfile(262442, emptyMap(), "raw_fields_only", publishedFields = setOf(61), syuClient = client)
        controller.firmwareDetector = { FytFirmware("stock", TeyesVehicleDataLayout.UNKNOWN, profiles = mapOf(262442 to mapping)) }
        controller.resumeUpdates(); drain()
        emit(1000, 262442)
        assertTrue(controller.state.value.fytSyuReadings.isEmpty())
        emit(61, 2)
        assertTrue(controller.state.value.fytSyuReadings.any { it.fields == setOf(61) && it.text == "middle" })
        assertEquals(TeyesTelemetryHealth.LIVE, controller.state.value.health)
        assertTrue(context.module.commands.isEmpty())
        emit(1000, 1)
        assertTrue(controller.state.value.fytSyuReadings.isEmpty())
    }

    @Test fun `WC GM gauges use fresh motion callbacks instead of old Civic IDs`() {
        controller.suspendUpdates(); drain()
        val registry = FytProtocolRegistry.parse(java.io.File("src/main/assets/syu/protocols-2023.json").readText())
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN, resolveProfile = registry::profile) }
        controller.resumeUpdates(); drain()
        emit(1000, 36); emit(13, 60); emit(107, 2400); emit(145, 126)
        assertEquals(60, controller.state.value.speedKph)
        assertEquals(2400, controller.state.value.engineRpm)
        assertTrue(controller.state.value.availableCodes.containsAll(listOf(89, 90)))
        context.module.registrationHistory.clear()
        shadowOf(worker.looper).idleFor(16, java.util.concurrent.TimeUnit.SECONDS)
        assertNull(controller.state.value.speedKph)
        assertNull(controller.state.value.engineRpm)
        assertFalse(context.module.registrationHistory.any { it in setOf(13, 107) })
        assertTrue(controller.state.value.fytSyuReadings.any { it.viewId == 145 && it.text == "12.6 V" })
    }

    @Test fun `Explorer support releases on timeout replacement and suspend`() {
        controller.suspendUpdates(); drain()
        val registry = FytProtocolRegistry.parse(java.io.File("src/main/assets/syu/protocols-2023.json").readText())
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN, resolveProfile = registry::profile) }
        controller.resumeUpdates(); drain()
        emit(1000, 590158); emit(100, 1); emit(102, 5); emit(103, 4)
        context.module.commands.clear()
        controller.setSyuVehicleOption(590158, 102, 1); drain()
        assertEquals(listOf(11 to listOf(167, 0, 1)), context.module.commands)
        shadowOf(worker.looper).idleFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(11 to listOf(167, 0, 0), context.module.commands.last())
        controller.setSyuVehicleOption(590158, 102, 2); drain()
        controller.setSyuVehicleOption(590158, 103, 1); drain()
        assertEquals(listOf(11 to listOf(167, 0, 2), 11 to listOf(167, 0, 0), 11 to listOf(167, 1, 1)), context.module.commands.takeLast(3))
        controller.suspendUpdates(); drain()
        assertEquals(11 to listOf(167, 1, 0), context.module.commands.last())
        val count = context.module.commands.size
        shadowOf(worker.looper).idleFor(1, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(count, context.module.commands.size)
    }

    @Test fun `Ford native tires request once and replace obsolete widget fields`() {
        controller.suspendUpdates(); drain()
        val registry = FytProtocolRegistry.parse(java.io.File("src/main/assets/syu/protocols-2023.json").readText())
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN, resolveProfile = registry::profile) }
        controller.resumeUpdates(); drain()
        emit(1000, 1376590)
        assertEquals(listOf(0 to listOf(99, 0), 0 to listOf(98, 0)), context.module.commands)
        emit(146, 80); emit(150, 1)
        assertNull(controller.state.value.syuVehicle.tires[0].pressureKpa)
        emit(78, 80); emit(82, 1); emit(180, 0)
        assertEquals(220.0, controller.state.value.syuVehicle.tires[0].pressureKpa!!, 0.0001)
        assertEquals(1, controller.state.value.syuVehicle.tires[0].warning)
        assertTrue(controller.state.value.fytSyuReadings.any { it.viewId == 78 && it.text == "220 kPa" })
        emit(78, 255)
        assertNull(controller.state.value.syuVehicle.tires[0].pressureKpa)
        controller.suspendUpdates(); drain()
        assertTrue(controller.state.value.syuVehicle.tires.isEmpty())
    }

    @Test fun `Honda trip reads request verified data and update the existing widget from actual fields`() {
        controller.suspendUpdates(); drain()
        val registry = FytProtocolRegistry.parse(java.io.File("src/main/assets/syu/protocols-2023.json").readText())
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN, resolveProfile = registry::profile) }
        controller.resumeUpdates(); drain()
        emit(1000, 262442)
        assertEquals(listOf(100 to listOf(1), 100 to listOf(2)), context.module.commands)
        assertTrue(controller.state.value.syuVehicle.tripSupported)
        emit(99, 123); emit(100, 145); emit(105, 2)
        assertNull(controller.state.value.syuVehicle.averageConsumption)
        emit(1, 123)
        assertNull(controller.state.value.syuVehicle.averageConsumption)
        emit(7, 2); emit(2, 145)
        assertEquals(12.3, controller.state.value.syuVehicle.averageConsumption!!, 0.0001)
        assertEquals(14.5, controller.state.value.syuVehicle.previousConsumption!!, 0.0001)
        emit(1, 65535)
        assertNull(controller.state.value.syuVehicle.averageConsumption)
        controller.suspendUpdates(); drain()
        assertNull(controller.state.value.syuVehicle.previousConsumption)
    }

    @Test fun `language command requires current registered profile and bounded choice`() {
        controller.suspendUpdates(); drain()
        val registry = FytProtocolRegistry.parse(java.io.File("src/main/assets/syu/protocols-2023.json").readText())
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN, resolveProfile = registry::profile) }
        controller.resumeUpdates(); drain()
        emit(1000, 321)
        controller.selectSyuVehicleChoice(321, FytVehicleChoice.LANGUAGE, 33); drain()
        assertTrue(context.module.commands.isEmpty())
        controller.selectSyuVehicleChoice(321, FytVehicleChoice.LANGUAGE, 1); drain()
        assertEquals(listOf(112 to listOf(1, 1)), context.module.commands)
        emit(1000, 17)
        controller.selectSyuVehicleChoice(321, FytVehicleChoice.LANGUAGE, 1); drain()
        controller.selectSyuVehicleChoice(17, FytVehicleChoice.LANGUAGE, 1); drain()
        assertEquals(1, context.module.commands.size)
        assertTrue(controller.state.value.fytChoices.isEmpty())
    }

    @Test fun `Honda WC uses own fields and rejects stale history reset`() {
        controller.suspendUpdates(); drain()
        val registry = FytProtocolRegistry.parse(java.io.File("src/main/assets/syu/protocols-2023.json").readText())
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN, resolveProfile = registry::profile) }
        controller.resumeUpdates(); drain()
        emit(1000, 852289)
        assertTrue(context.module.commands.isEmpty())
        emit(1, 123); emit(7, 2)
        assertEquals(12.3, controller.state.value.syuVehicle.averageConsumption!!, 0.0001)
        controller.setSyuVehicleOption(852289, 50, 3); drain()
        assertTrue(context.module.commands.isEmpty())
        emit(50, 2)
        controller.setSyuVehicleOption(852289, 50, 3); drain()
        controller.performSyuVehicleAction(852289, FytVehicleAction.RESET_HONDA_TRIP_HISTORY); drain()
        assertEquals(listOf(102 to listOf(4, 3), 101 to listOf(3)), context.module.commands)
        emit(1000, 17)
        controller.performSyuVehicleAction(852289, FytVehicleAction.RESET_HONDA_TRIP_HISTORY); drain()
        controller.performSyuVehicleAction(17, FytVehicleAction.RESET_HONDA_TRIP_HISTORY); drain()
        assertEquals(2, context.module.commands.size)
    }

    @Test fun `Golf trip reset actions require active supported profile and connection`() {
        controller.suspendUpdates(); drain()
        val registry = FytProtocolRegistry.parse(java.io.File("src/main/assets/syu/protocols-2023.json").readText())
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN, resolveProfile = registry::profile) }
        controller.resumeUpdates(); drain()
        controller.performSyuVehicleAction(17, FytVehicleAction.RESET_TRIP_SINCE_START); drain()
        assertTrue(context.module.commands.isEmpty())
        emit(1000, 17)
        assertEquals(setOf(FytVehicleAction.RESET_TRIP_SINCE_START, FytVehicleAction.RESET_TRIP_LONG_TERM), controller.state.value.fytActions)
        controller.performSyuVehicleAction(17, FytVehicleAction.RESET_TRIP_SINCE_START); drain()
        controller.performSyuVehicleAction(17, FytVehicleAction.RESET_TRIP_LONG_TERM); drain()
        assertEquals(listOf(84 to listOf(1), 85 to listOf(1)), context.module.commands)
        controller.suspendUpdates(); drain()
        controller.performSyuVehicleAction(17, FytVehicleAction.RESET_TRIP_LONG_TERM); drain()
        assertEquals(2, context.module.commands.size)
        assertTrue(controller.state.value.fytActions.isEmpty())
        assertEquals(setOf(FytVehicleAction.CALIBRATE_COMPASS), (registry.profile(262442).syuClient.display as CabinSyuDecoder).actions)
    }

    @Test fun `RZC charging refuses incomplete records and keeps the active schedules`() {
        controller.suspendUpdates(); drain()
        val registry = FytProtocolRegistry.parse(java.io.File("src/main/assets/syu/protocols-2023.json").readText())
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN, resolveProfile = registry::profile) }
        controller.resumeUpdates(); drain()
        emit(1000, 655520)
        emit(407, 7)
        controller.setSyuVehicleOption(655520, 407, 9); drain()
        assertTrue(context.module.commands.isEmpty())
        listOf(7, 35, 1, 1, 0, 1, 2, 1, 0, 1, 0, 1, 0, 1, 22, 30, 6, 0, 80)
            .forEachIndexed { i, value -> emit(407 + i, value) }
        controller.setSyuVehicleOption(655520, 407, 9); drain()
        assertEquals(listOf(143 to listOf(1, 9, 35, 210, 170, 22, 30, 6, 0, 80)), context.module.commands)
        emit(404, 1); emit(405, 0); emit(406, 1)
        controller.setSyuVehicleOption(655520, 405, 1); drain()
        assertEquals(142 to listOf(0, 7), context.module.commands.last())
        emit(1000, 17)
        controller.setSyuVehicleOption(655520, 405, 0); drain()
        assertEquals(2, context.module.commands.size)
    }

    @Test fun `Golf options use current raw fields and never climate values at old mirror IDs`() {
        controller.suspendUpdates(); drain()
        val registry = FytProtocolRegistry.parse(java.io.File("src/main/assets/syu/protocols-2023.json").readText())
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN, resolveProfile = registry::profile) }
        controller.resumeUpdates(); drain()
        emit(1000, 17)
        emit(148, 257)
        assertFalse(SyuFactoryControl.MIRROR_SYNC in controller.state.value.syuVehicle.factoryControls)
        controller.setSyuVehicleOption(17, 51, 1); drain()
        assertTrue(context.module.commands.isEmpty())
        emit(51, 256)
        controller.setSyuVehicleOption(17, 51, 1); drain()
        assertEquals(listOf(67 to listOf(1)), context.module.commands)
        assertEquals(0, controller.state.value.syuVehicle.factoryControls[SyuFactoryControl.MIRROR_SYNC])
        emit(116, 257)
        assertFalse(SyuFactoryControl.PARKING_AUTO in controller.state.value.syuVehicle.factoryControls)
        emit(19, 256)
        assertEquals(0, controller.state.value.syuVehicle.factoryControls[SyuFactoryControl.PARKING_AUTO])
        emit(1000, 1310880)
        controller.setSyuVehicleOption(17, 51, 0); drain()
        assertEquals(1, context.module.commands.size)
        shadowOf(worker.looper).idleFor(5, java.util.concurrent.TimeUnit.SECONDS)
        drain()
        emit(1000, 1310880)
        emit(55, 1)
        controller.setSyuVehicleOption(1310880, 55, 0); drain()
        assertEquals(listOf(67 to listOf(1), 71 to listOf(0)), context.module.commands)
    }

    @Test fun `Cabin vehicle options send own protocol frames only for the current live profile`() {
        controller.suspendUpdates(); drain()
        val definition = FytDetectedProfile(262442, emptyMap(), "registered_protocol", publishedFields = setOf(61),
            syuClient = FytSyuClientFields(display = CabinSyuDecoder(262442, "honda_0298")))
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN, profiles = mapOf(262442 to definition)) }
        controller.resumeUpdates(); drain()
        emit(1000, 262442)
        controller.setSyuVehicleOption(262442, 61, 3); drain()
        assertTrue(context.module.commands.isEmpty())
        emit(61, 2)
        controller.setSyuVehicleOption(262442, 61, 5); drain()
        assertTrue(context.module.commands.isEmpty())
        controller.setSyuVehicleOption(262442, 61, 3); drain()
        assertEquals(listOf(105 to listOf(6, 3)), context.module.commands)
        assertEquals("Medium", controller.state.value.fytSyuReadings.single { it.viewId == 61 }.text)
        emit(1000, 1)
        controller.setSyuVehicleOption(262442, 61, 1); drain()
        assertEquals(1, context.module.commands.size)
        controller.suspendUpdates(); drain()
        controller.setSyuVehicleOption(262442, 61, 0); drain()
        assertEquals(1, context.module.commands.size)
    }

    @Test fun `stock maintenance and temperatures use verified fields without disabling existing climate controls`() {
        controller.suspendUpdates(); drain()
        val mapping = FytDetectedProfile(262442,
            mapOf(24 to 24, 29 to 29, 25 to 25, 31 to 31, 33 to 33, 94 to 94, 179 to 135, 180 to 136, 181 to 137), "matched")
        controller.firmwareDetector = { FytFirmware("stock", TeyesVehicleDataLayout.JOYING_2023, profiles = mapOf(262442 to mapping)) }
        controller.resumeUpdates(); drain()
        emit(1000, 262442)
        emit(24, 1); emit(29, 4); emit(25, 44); emit(31, -3); emit(94, 2); emit(137, 2500); emit(181, 13)
        assertNull(controller.state.value.leftTemperature)
        assertNull(controller.state.value.oilServiceDistance)
        emit(33, 0); emit(135, 1); emit(136, 1)
        val state = controller.state.value
        assertEquals(44, state.leftTemperature); assertEquals(-3, state.rightTemperature)
        assertEquals(2, state.driverSeatCooling)
        assertEquals(-2500, state.oilServiceDistance)
        assertTrue(state.oilServiceDistanceMiles)
        assertNull(state.oilLifePercent)
        assertFalse(state.fytReadOnly)
        assertTrue(state.controlsAvailable)
    }

    @Test fun `Honda panel writes require feedback and use the live vendor module`() {
        emit(1000, 0x40141)
        assertTrue(context.module.registrations.containsAll(listOf(109, 110, 111, 69, 70, 71, 72)))
        context.module.commands.clear()
        controller.setFactoryControl(SyuFactoryControl.HONDA_PANEL_CONFIG, 2)
        drain()
        assertTrue(context.module.commands.isEmpty())
        emit(111, 0)
        controller.setFactoryControl(SyuFactoryControl.HONDA_PANEL_CONFIG, 2)
        drain()
        assertEquals(listOf(106 to listOf(14, 2)), context.module.commands)
        assertEquals(0, controller.state.value.syuVehicle.factoryControls[SyuFactoryControl.HONDA_PANEL_CONFIG])
        emit(111, 2)
        assertEquals(2, controller.state.value.syuVehicle.factoryControls[SyuFactoryControl.HONDA_PANEL_CONFIG])
        controller.suspendUpdates(); drain()
        controller.setFactoryControl(SyuFactoryControl.HONDA_PANEL_CONFIG, 1); drain()
        assertEquals(1, context.module.commands.size)
    }

    @Test fun `quiet readings refresh without republishing cached motion values`() {
        context.module.registrationHistory.clear()
        shadowOf(worker.looper).idleFor(16, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(context.module.registrationHistory.containsAll(listOf(1000, 0, 1, 11, 21)))
        assertFalse(context.module.registrationHistory.any { it in setOf(89, 90, 149, 151) })
        assertTrue(context.module.commands.isEmpty())
    }

    @Test fun `Ford settings refresh even when their IDs overlap legacy motion`() {
        controller.suspendUpdates(); drain()
        val registry = FytProtocolRegistry.parse(java.io.File("src/main/assets/syu/protocols-2023.json").readText())
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN, resolveProfile = registry::profile) }
        controller.resumeUpdates(); drain()
        emit(1000, 917838)
        context.module.registrationHistory.clear()
        context.module.commands.clear()
        shadowOf(worker.looper).idleFor(16, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(context.module.registrationHistory.containsAll(listOf(89, 149, 151)))
        assertNull(controller.state.value.speedKph)
        assertNull(controller.state.value.engineRpm)
        assertTrue(context.module.commands.isEmpty())
        emit(1000, 334)
        context.module.registrationHistory.clear()
        shadowOf(worker.looper).idleFor(16, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(context.module.registrationHistory.contains(90))
    }

    @Test fun `Audi native speed expires without refreshing cached speed as live`() {
        controller.suspendUpdates(); drain()
        val mapping = FytDetectedProfile(286, emptyMap(), "registered_protocol", publishedFields = setOf(1),
            syuClient = FytSyuClientFields(display = CabinSyuDecoder(286, "bagoo_audi")))
        controller.firmwareDetector = { FytFirmware("2.23.0718.1700", TeyesVehicleDataLayout.UNKNOWN,
            profiles = mapOf(286 to mapping)) }
        controller.resumeUpdates(); drain()
        emit(1000, 286); emit(1, 800)
        assertEquals("50.0000 km/h", controller.state.value.fytSyuReadings.single().text)
        context.module.registrationHistory.clear()
        shadowOf(worker.looper).idleFor(16, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(controller.state.value.fytSyuReadings.isEmpty())
        assertFalse(context.module.registrationHistory.contains(1))
    }

    @Test fun `integrated RZC units use Cabin direct FYT connection and returned values`() {
        controller.suspendUpdates(); drain()
        context.toolkitUnavailable = true
        controller.resumeUpdates(); drain()
        shadowOf(worker.looper).idleFor(5, java.util.concurrent.TimeUnit.SECONDS); drain()
        emit(1000, 0x10012a)
        assertTrue(context.actions.contains("com.syu.ms.canbus"))
        assertTrue(context.module.registrations.containsAll(listOf(77, 78, 87)))
        context.module.commands.clear()
        controller.setFactoryControl(SyuFactoryControl.HONDA_DISTANCE_UNITS, 1); drain()
        assertTrue(context.module.commands.isEmpty())
        emit(77, 0)
        controller.setFactoryControl(SyuFactoryControl.HONDA_DISTANCE_UNITS, 1); drain()
        assertEquals(listOf(105 to listOf(21, 1)), context.module.commands)
        assertEquals(0, controller.state.value.syuVehicle.factoryControls[SyuFactoryControl.HONDA_DISTANCE_UNITS])
        emit(77, 1)
        assertEquals(1, controller.state.value.syuVehicle.factoryControls[SyuFactoryControl.HONDA_DISTANCE_UNITS])
        controller.suspendUpdates(); drain()
        controller.setFactoryControl(SyuFactoryControl.HONDA_DISTANCE_UNITS, 0); drain()
        assertEquals(1, context.module.commands.size)
    }

    @Test fun `sleep clears vehicle data and resume requests a new connection`() {
        emit(1000, 1048874)
        emit(1, 1)
        assertTrue(controller.state.value.connected)
        controller.suspendUpdates(); drain()
        assertFalse(controller.state.value.connected)
        assertTrue(controller.state.value.availableCodes.isEmpty())
        controller.resumeUpdates(); drain()
        assertTrue(controller.state.value.connected)
        assertFalse(controller.state.value.frontLeftDoorOpen)
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

    @Test fun `direct CAN service receives data when toolkit binding is unavailable`() {
        controller.suspendUpdates(); drain()
        context.toolkitUnavailable = true
        controller.resumeUpdates(); drain()
        shadowOf(worker.looper).idleFor(5, java.util.concurrent.TimeUnit.SECONDS)
        drain()
        assertTrue(context.actions.contains("com.syu.ms.canbus"))
        emit(1000, 1048874)
        emit(1, 1)
        assertTrue(controller.state.value.connected)
        assertTrue(controller.state.value.frontLeftDoorOpen)
        assertTrue(context.module.commands.isEmpty())
    }

    @Test fun `failed toolkit subscriptions fall back to direct CAN service`() {
        controller.suspendUpdates(); drain()
        context.failToolkitRegistration = true
        context.actions.clear()
        controller.resumeUpdates(); drain()
        shadowOf(worker.looper).idleFor(5, java.util.concurrent.TimeUnit.SECONDS)
        drain()
        assertTrue(context.actions.contains("com.syu.ms.canbus"))
        assertTrue(controller.state.value.connected)
        emit(1000, 1048874)
        emit(1, 1)
        assertTrue(controller.state.value.frontLeftDoorOpen)
        assertTrue(context.module.commands.isEmpty())
    }

    @Test fun `reported XP1 profile uses detected firmware and ignores old manual selection`() {
        context.getSharedPreferences("teyes_vehicle_data_v1", Context.MODE_PRIVATE)
            .edit().putString("layout", "LEGACY").commit()
        emit(1000, 262442)
        emit(89, 13)
        emit(90, 1)
        assertEquals(262442, controller.state.value.profileId)
        assertEquals("2.23.0711.1001", controller.state.value.fytFirmwareVersion)
        assertEquals(TeyesVehicleDataLayout.CIVIC_0298, controller.state.value.vehicleDataLayout)
        assertNull(controller.state.value.speedKph)
        assertNull(controller.state.value.engineRpm)
    }

    @Test fun `Joying empty subscription replies keep CAN connected across refreshes`() {
        controller.suspendUpdates(); drain()
        context.module.emptyVoidReplies = true
        context.actions.clear()
        controller.resumeUpdates(); drain()
        assertTrue(controller.state.value.connected)
        emit(1000, 1048874)
        emit(1, 1)
        assertTrue(controller.state.value.frontLeftDoorOpen)
        shadowOf(worker.looper).idleFor(35, java.util.concurrent.TimeUnit.SECONDS)
        drain()
        assertTrue(controller.state.value.connected)
        assertEquals(1, context.actions.size)
        assertTrue(context.module.commands.isEmpty())
    }

    private fun emit(
        code: Int,
        value: Int,
    ) {
        send(requireNotNull(context.module.callback), code, value, oneWay = context.module.emptyVoidReplies)
        drain()
    }

    private fun send(
        callback: IBinder,
        code: Int,
        value: Int,
        oneWay: Boolean = false,
    ) {
        val parcel = Parcel.obtain()
        val reply = if (oneWay) null else Parcel.obtain()
        try {
            parcel.writeInterfaceToken("com.syu.ipc.IModuleCallback")
            parcel.writeInt(code)
            parcel.writeIntArray(intArrayOf(value))
            parcel.writeFloatArray(null)
            parcel.writeStringArray(null)
            assertTrue(callback.transact(1, parcel, reply, if (oneWay) IBinder.FLAG_ONEWAY else 0))
            reply?.readException()
        } finally {
            parcel.recycle()
            reply?.recycle()
        }
    }

    private class ToolkitContext(base: Context) : ContextWrapper(base) {
        val module = Module()
        val mainModule = Module(notify = 0)
        var toolkitUnavailable = false
        var failToolkitRegistration = false
        val actions = mutableListOf<String?>()
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
                    val id = data.readInt()
                    assertTrue(id == 7 || id == 0)
                    requireNotNull(reply).writeNoException()
                    reply.writeStrongBinder(if (id == 7) module else mainModule)
                    return true
                }
            }

        override fun getApplicationContext(): Context = this

        override fun bindService(
            service: Intent,
            conn: ServiceConnection,
            flags: Int,
        ): Boolean {
            actions += service.action
            module.rejectRegistration = failToolkitRegistration && service.action == "com.syu.ms.toolkit"
            if (service.action == "com.syu.ms.toolkit" && toolkitUnavailable) return false
            conn.onServiceConnected(requireNotNull(service.component), if (service.action == "com.syu.ms.canbus") module else toolkit)
            return true
        }

        override fun unbindService(conn: ServiceConnection) = Unit
    }

    private class Module(private val notify: Int = 1) : Binder() {
        init { attachInterface(null, "com.syu.ipc.IRemoteModule") }
        var callback: IBinder? = null
        var rejectRegistration = false
        var emptyVoidReplies = false
        val registrations = mutableSetOf<Int>()
        val registrationHistory = mutableListOf<Int>()
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
                    if (rejectRegistration) { reply.writeException(android.os.RemoteException("Subscription rejected")); return true }
                    callback = data.readStrongBinder()
                    val field = data.readInt()
                    registrations += field
                    registrationHistory += field
                    assertEquals(notify, data.readInt())
                }
                4 -> {
                    data.readStrongBinder()
                    data.readInt()
                }
                else -> return false
            }
            if (!emptyVoidReplies) reply.writeNoException()
            return true
        }
    }
}
