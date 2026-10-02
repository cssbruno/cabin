package com.cabin.platform

import org.junit.Assert.*
import org.junit.Test

class VehicleSettingCategoryTest {
    @Test fun `same field ID is categorized by protocol rather than translated text`() {
        val honda = FytSyuReading("honda_0298", 61, setOf(61), label = "Arbitrary translated text")
        val golf = honda.copy(screen = "golf_wc_2023")
        assertEquals(VehicleSettingCategory.LIGHTS, VehicleSettingCategory.reading(393514, honda))
        assertEquals(VehicleSettingCategory.INSTRUMENTS, VehicleSettingCategory.reading(17, golf))
        assertEquals(VehicleSettingCategory.PARKING, VehicleSettingCategory.reading(42,
            FytSyuReading("honda_accord_wc", 32, setOf(32), label = "Camera brightness")))
        assertEquals(VehicleSettingCategory.OTHER, VehicleSettingCategory.reading(17, golf.copy(screen = "unknown")))
    }

    @Test fun `mixed native settings all have functional categories in both decoder languages`() {
        for (pt in listOf(false, true)) {
            val decoders: List<Pair<Int, (Map<Int, Int>) -> List<FytSyuReading>>> = listOf(
                393514 to { raw -> CabinSyuDecoder(393514, "honda_0298", pt).read(raw) },
                0x10012a to { raw -> CabinHondaRzcSettings.read(0x10012a, raw, pt) },
                262465 to { raw -> CabinHondaWcSettings.read(262465, raw, pt) },
                917838 to { raw -> CabinFordSettings.read(917838, raw, pt) },
                334 to { raw -> CabinFordLegacySettings.read(334, raw, pt) },
                459086 to { raw -> CabinFordLegacySettings.read(459086, raw, pt) },
                36 to { raw -> CabinGmWcSettings.read(raw, pt) },
                17 to { raw -> CabinGolfSettings.read(17, "golf_wc_2023", raw, pt) },
                655520 to { raw -> CabinGolfSettings.read(655520, "golf_rzc_2023", raw, pt) },
                42 to { raw -> CabinHondaAccordWc.read(42, raw, pt) },
                410 to { raw -> CabinHondaAccordXbs.read(410, raw, pt) },
            )
            for ((profile, read) in decoders) {
                val rows = (0..7).flatMap { value ->
                    listOf(value, value + 256).flatMap { rawValue -> read((0..470).associateWith { rawValue }) }
                }.filter { it.options.isNotEmpty() }.distinctBy { it.screen to it.viewId }
                assertTrue("No settings exercised for $profile", rows.isNotEmpty())
                for (row in rows) assertNotEquals("$profile ${row.screen}:${row.viewId} ${row.label}",
                    VehicleSettingCategory.OTHER, VehicleSettingCategory.reading(profile, row))
            }
        }
    }

    @Test fun `service commands appear under their actual purpose`() {
        assertEquals(VehicleSettingCategory.AUDIO, VehicleSettingCategory.action(FytVehicleAction.RESET_HONDA_AMPLIFIER))
        assertEquals(VehicleSettingCategory.PARKING, VehicleSettingCategory.action(FytVehicleAction.INITIALIZE_PANORAMA))
        assertEquals(VehicleSettingCategory.PARKING, VehicleSettingCategory.control(SyuFactoryControl.HONDA_REVERSE_TONE))
        assertEquals(VehicleSettingCategory.SERVICE, VehicleSettingCategory.control(SyuFactoryControl.HONDA_TRIP_A_RESET))
    }
}
