package com.cabin.platform

import org.junit.Assert.*
import org.junit.Test

class FytFirmwareTest {
    @Test fun `known vendor APK selects its verified decoder`() {
        assertEquals(TeyesVehicleDataLayout.JOYING_2023,
            fytFirmwareLayout("4b428302e29c9e2503ccf7844a127f5bed59450736317629aa42b5eb35a9b577"))
    }

    @Test fun `Joying canonical climate fields are not remapped as newer Civic fields`() {
        val raw = mapOf(1000 to 262442, 24 to 1, 29 to 4, 21 to 0, 37 to 1,
            11 to 0, 89 to 13, 90 to 1, 179 to 0, 180 to 0, 181 to 13)
        assertEquals(mapOf(1000 to 262442, 24 to 1, 29 to 4, 21 to 0, 37 to 1),
            TeyesClimateControlPolicy.climateValues(262442, raw, TeyesVehicleDataLayout.JOYING_2023))
    }

    @Test fun `vehicle profile alone never selects a firmware decoder`() {
        val layout = fytFirmwareLayout("different firmware")
        assertEquals(TeyesVehicleDataLayout.UNKNOWN, layout)
        val raw = mapOf(1000 to 262442, 89 to 13, 90 to 1, 179 to 0, 180 to 0, 181 to 13)
        assertEquals(mapOf(1000 to 262442), TeyesClimateControlPolicy.climateValues(262442, raw, layout))
    }
}
