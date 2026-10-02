package com.cabin.ui.settings

import org.junit.Assert.*
import org.junit.Test

class MaintenanceFormInputTest {
    @Test fun `manual decimal input accepts comma or point without interpreting grouping`() {
        mapOf("125,90" to 125.9, "125.90" to 125.9, ",5" to .5, ".5" to .5, " 1000,5 " to 1000.5, "0" to 0.0, "10000000" to 10000000.0).forEach { (input, expected) ->
            assertEquals(input, expected, maintenanceDecimal(input)!!, 0.0)
        }
        listOf("1.234,56", "1,234.56", "1 234,56", "1,2,3", "NaN", "Infinity", "-1", "1e5", "10000000,1", "", ",").forEach { input -> assertNull(input, maintenanceDecimal(input)) }
    }
}
