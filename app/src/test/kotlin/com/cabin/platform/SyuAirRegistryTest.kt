package com.cabin.platform

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SyuAirRegistryTest {
    private fun registry() = SyuAirRegistry.parse(File("src/main/assets/syu/air-profiles.json").readText())

    @Test fun `catalog covers the shared Air screens with explicit vehicle identities`() {
        val registry = registry()
        assertEquals(2033, registry.profiles.size)
        assertTrue(registry.profiles.values.count { it.commands.isNotEmpty() } > 1800)
        assertNull(registry.profiles[123456789])
    }

    @Test fun `Ford Toyota GM and VW retain different command and release formats`() {
        val registry = registry()
        val examples = mapOf(
            21 to listOf(SyuAirFrame(0, listOf(13, 1)), SyuAirFrame(0, listOf(13, 0))),
            196729 to listOf(SyuAirFrame(17, listOf(13, 1)), SyuAirFrame(17, listOf(13, 0))),
            393277 to listOf(SyuAirFrame(10, listOf(3, 1)), SyuAirFrame(10, listOf(3, 0))),
            131112 to listOf(SyuAirFrame(0, listOf(14, 1)), SyuAirFrame(0, listOf(0, 0, 0, 0, 0, 0))),
        )
        examples.forEach { (profile, frames) ->
            assertEquals("Profile $profile", frames, registry.profiles.getValue(profile).commands["C_AIR_TEMP_LEFT_ADD"])
        }
    }

    @Test fun `Toyota firmware invariant commands retain bit packing and full release`() {
        val registry = registry()
        for (id in listOf(524400, 786544)) {
            val commands = registry.profiles.getValue(id).commands
            assertEquals(listOf(
                SyuAirFrame(48, listOf(0, 2, 0, 0, 0, 0, 0)),
                SyuAirFrame(48, listOf(0, 0, 0, 0, 0, 0, 0)),
            ), commands["C_AIR_WIND_ADD"])
            assertEquals(listOf(
                SyuAirFrame(48, listOf(0, 0, 0, 0, 0, 32, 0)),
                SyuAirFrame(48, listOf(0, 0, 0, 0, 0, 0, 0)),
            ), commands["C_REAR_WIND_UP"])
            for (zone in listOf("LEFT", "RIGHT")) {
                assertNull(commands["C_AIR_TEMP_${zone}_ADD"])
                assertNull(commands["C_AIR_TEMP_${zone}_SUB"])
            }
        }
    }

    @Test fun `actions require their associated fresh readings and respect temperature limits`() {
        val profile = registry().profiles.getValue(21)
        val empty = SyuAirState(21, profile.name, emptyMap(), profile.commands.keys, -2, -3, -1)
        assertFalse(empty.canSend("C_AIR_TEMP_LEFT_ADD"))
        assertFalse(empty.copy(readings = mapOf("U_AIR_AC" to 1)).canSend("C_AIR_TEMP_LEFT_ADD"))
        assertTrue(empty.copy(readings = mapOf("U_AIR_TEMP_LEFT" to 44)).canSend("C_AIR_TEMP_LEFT_ADD"))
        assertFalse(empty.copy(readings = mapOf("U_AIR_TEMP_LEFT" to -3)).canSend("C_AIR_TEMP_LEFT_ADD"))
        assertFalse(empty.copy(readings = mapOf("U_AIR_TEMP_LEFT" to -1)).canSend("C_AIR_TEMP_LEFT_ADD"))
        assertFalse(empty.copy(readings = mapOf("U_AIR_TEMP_LEFT" to 44)).canSend("UNREGISTERED_ACTION"))
    }

    @Test fun `temperature displays use verified conversion and never assume missing units`() {
        val profile = registry().profiles.getValue(139)
        val state = SyuAirState(profile.id, profile.name, mapOf("U_AIR_TEMP_LEFT" to 220), profile.commands.keys,
            profile.low, profile.high, profile.unavailable, profile.temperatureFormats)
        assertEquals("—", state.temperatureText("U_AIR_TEMP_LEFT"))
        assertEquals("22°C", state.copy(readings = state.readings + ("U_AIR_TEMP_UNIT" to 0)).temperatureText("U_AIR_TEMP_LEFT"))
        assertEquals("72°F", state.copy(readings = mapOf("U_AIR_TEMP_LEFT" to 72, "U_AIR_TEMP_UNIT" to 1)).temperatureText("U_AIR_TEMP_LEFT"))
    }

    @Test fun `generic readings do not inherit Civic door scaling rules`() {
        val samples = TeyesTelemetryFreshness()
        samples.update(1, 44, 0)
        assertEquals(44, samples.airSnapshot(59_999)[1])
        assertFalse(samples.airSnapshot(60_000).containsKey(1))
        assertFalse(samples.snapshot(1).containsKey(1))
    }
}
