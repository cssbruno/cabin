package com.cabin.platform

import org.junit.Assert.*
import org.junit.Test

class SyuVehicleProtocolTest {
    @Test fun `same fields on an unrelated profile never become vehicle telemetry`() {
        val state = SyuVehicleProtocol.decode(21, mapOf(99 to 120, 105 to 2, 309 to 8))
        assertFalse(state.tripSupported)
        assertFalse(state.hybridSupported)
        assertNull(state.averageConsumption)
        assertNull(state.batterySegments)
        assertTrue(SyuVehicleProtocol.codes(21).isEmpty())
    }
    @Test fun `consumption requires fresh units and rejects sentinel`() {
        assertNull(SyuVehicleProtocol.decode(459073, mapOf(99 to 120)).averageConsumption)
        assertNull(SyuVehicleProtocol.decode(459073, mapOf(99 to 65535, 105 to 2)).averageConsumption)
        assertNull(SyuVehicleProtocol.decode(459073, mapOf(99 to 120, 105 to 3)).averageConsumption)
        val state = SyuVehicleProtocol.decode(459073, mapOf(99 to 123, 100 to 145, 105 to 2))
        assertEquals(12.3, state.averageConsumption!!, 0.0001)
        assertEquals(14.5, state.previousConsumption!!, 0.0001)
        assertEquals("L/100 km", state.consumptionUnit)
    }
    @Test fun `battery segments are not invented percentages`() {
        assertEquals(8, SyuVehicleProtocol.decode(5898538, mapOf(309 to 8)).batterySegments)
        assertNull(SyuVehicleProtocol.decode(5898538, mapOf(309 to 11)).batterySegments)
        assertNull(SyuVehicleProtocol.decode(5898538, emptyMap()).batterySegments)
    }
    @Test fun `lighting only uses the routed BNR screen and bounded enum readings`() {
        assertTrue(SyuVehicleProtocol.decode(1048874, mapOf(123 to 2)).lighting.isEmpty())
        assertEquals(2, SyuVehicleProtocol.decode(393514, mapOf(123 to 2)).lighting[SyuLightingSetting.HEADLIGHT_DELAY])
        assertTrue(SyuVehicleProtocol.decode(393514, mapOf(123 to 4)).lighting.isEmpty())
    }
    @Test fun `Ford pressures use source scaling and retain per tire warning states`() {
        val state = SyuVehicleProtocol.decode(1376590, mapOf(146 to 80, 147 to 255, 150 to 2, 151 to 9))
        assertEquals(220.0, state.tires[0].pressureKpa!!, 0.0001)
        assertEquals(2, state.tires[0].warning)
        assertNull(state.tires[1].pressureKpa)
        assertNull(state.tires[1].warning)
        assertNull(state.tires[2].pressureKpa)
        assertTrue(SyuVehicleProtocol.decode(21, mapOf(146 to 80)).tires.isEmpty())
    }
    @Test fun `amplifier settings are bounded and belong only to the routed profile`() {
        assertEquals(9, SyuVehicleProtocol.decode(393537, mapOf(201 to 9)).amplifier[SyuAmplifierSetting.BALANCE])
        assertTrue(SyuVehicleProtocol.decode(393537, mapOf(201 to 19)).amplifier.isEmpty())
        assertTrue(SyuVehicleProtocol.decode(262465, mapOf(201 to 9)).amplifier.isEmpty())
    }

    @Test fun `vehicle samples expire before decoding`() {
        val samples = TeyesTelemetryFreshness()
        samples.update(99, 123, 0)
        samples.update(105, 2, 0)
        assertNotNull(SyuVehicleProtocol.decode(459073, samples.airSnapshot(59999)).averageConsumption)
        assertNull(SyuVehicleProtocol.decode(459073, samples.airSnapshot(60000)).averageConsumption)
    }
}
