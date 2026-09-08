package com.cabin.ui

import com.cabin.platform.TeyesClimateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], qualifiers = "en")
class TeyesVehicleUiTest {
    private val testResources get() = org.robolectric.RuntimeEnvironment.getApplication().resources

    @Test
    fun `closed vehicle has no door warning`() {
        assertNull(vehicleDoorWarning(testResources, TeyesClimateState()))
    }

    @Test
    fun `open doors are combined into one warning`() {
        val state =
            TeyesClimateState(
                frontLeftDoorOpen = true,
                rearRightDoorOpen = true,
                bootOpen = true,
            )

        assertEquals(
            "DRIVER DOOR • REAR RIGHT DOOR • BOOT OPEN",
            vehicleDoorWarning(testResources, state),
        )
    }
}
