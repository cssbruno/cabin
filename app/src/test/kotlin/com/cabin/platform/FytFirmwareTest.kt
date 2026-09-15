package com.cabin.platform

import org.junit.Assert.*
import org.junit.Test

class FytFirmwareTest {
    @Test fun `Joying canonical climate fields are not remapped as newer Civic fields`() {
        val raw = mapOf(1000 to 262442, 24 to 1, 29 to 4, 21 to 0, 37 to 1,
            11 to 0, 89 to 13, 90 to 1, 179 to 0, 180 to 0, 181 to 13)
        assertEquals(mapOf(1000 to 262442, 24 to 1, 29 to 4, 21 to 0, 37 to 1),
            TeyesClimateControlPolicy.climateValues(262442, raw, TeyesVehicleDataLayout.JOYING_2023))
    }

    @Test fun `vehicle profile alone never selects a firmware decoder`() {
        val layout = FytCodeDetector.layout(emptyList())
        assertEquals(TeyesVehicleDataLayout.UNKNOWN, layout)
        val raw = mapOf(1000 to 262442, 89 to 13, 90 to 1, 179 to 0, 180 to 0, 181 to 13)
        assertEquals(mapOf(1000 to 262442), TeyesClimateControlPolicy.climateValues(262442, raw, layout))
    }
}
