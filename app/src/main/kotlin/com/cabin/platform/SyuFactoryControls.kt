package com.cabin.platform

/** Source-backed settings only; these do not expose an arbitrary CAN command API. */
enum class SyuFactoryGroup { CAMERA, MIRRORS, PARKING, CHARGING, AMBIENT, SEAT_MEMORY }
enum class SyuFactoryControl(val group: SyuFactoryGroup, val field: Int, val command: Int, val maximum: Int) {
    CAMERA_MODE(SyuFactoryGroup.CAMERA, 134, 15, 2),
    MIRROR_SYNC(SyuFactoryGroup.MIRRORS, 148, 67, 1),
    MIRROR_REVERSE_DIP(SyuFactoryGroup.MIRRORS, 149, 68, 1),
    MIRROR_PARK_FOLD(SyuFactoryGroup.MIRRORS, 150, 69, 1),
    RAIN_WIPERS(SyuFactoryGroup.MIRRORS, 151, 70, 1),
    REVERSE_REAR_WIPER(SyuFactoryGroup.MIRRORS, 152, 71, 1),
    PARKING_AUTO(SyuFactoryGroup.PARKING, 116, 39, 1),
    PARKING_FRONT_VOLUME(SyuFactoryGroup.PARKING, 117, 40, 8),
    PARKING_FRONT_TONE(SyuFactoryGroup.PARKING, 118, 41, 8),
    PARKING_REAR_VOLUME(SyuFactoryGroup.PARKING, 119, 42, 8),
    PARKING_REAR_TONE(SyuFactoryGroup.PARKING, 120, 43, 8),
    CHARGE_CURRENT(SyuFactoryGroup.CHARGING, 300, 145, 3),
    CHARGE_TEMPERATURE(SyuFactoryGroup.CHARGING, 301, 145, 29),
    CHARGE_CLIMATE_BATTERY(SyuFactoryGroup.CHARGING, 302, 145, 1),
    CHARGE_MINIMUM(SyuFactoryGroup.CHARGING, 303, 145, 10),
    AMBIENT_PALETTE(SyuFactoryGroup.AMBIENT, 270, 109, 1),
    SEAT_PRESET(SyuFactoryGroup.SEAT_MEMORY, 200, 1, 2);
}

internal object SyuFactoryProtocol {
    const val HYBRID_PROFILE = 655377
    const val AMBIENT_PROFILE = 4260138
    const val SEAT_PRESET_PROFILE = 1769874
    val chargeCurrents = listOf(5, 10, 13, 255)
    val chargeTemperatures = listOf(254) + (32..59).toList() + 255
    val cameraProfiles = setOf(131114, 131109)
    // Intersection of ConstGolf.isWcGolf and explicit Golf7IndexAct profile cases.
    val wcGolfProfiles = setOf(
        17, 524305, 589841, 655377, 720913, 786449, 851985, 917521, 983057, 1048593, 1114129, 1179665, 1245201, 1310737, 1376273, 1441809, 1507345, 1572881, 1638417, 1703953, 1769489, 1835025, 1900561, 1966097, 2031633, 2097169, 2162705, 2228241, 2293777, 2359313, 2424849, 2490385, 2555921, 2621457, 2686993, 2752529, 2818065
    )

    fun controls(profile: Int): Set<SyuFactoryControl> = when (profile) {
        in cameraProfiles -> setOf(SyuFactoryControl.CAMERA_MODE)
        SEAT_PRESET_PROFILE -> setOf(SyuFactoryControl.SEAT_PRESET)
        AMBIENT_PROFILE -> setOf(SyuFactoryControl.AMBIENT_PALETTE)
        in wcGolfProfiles -> SyuFactoryControl.entries.filter {
            it.group in setOf(SyuFactoryGroup.MIRRORS, SyuFactoryGroup.PARKING) ||
                (profile == HYBRID_PROFILE && it.group == SyuFactoryGroup.CHARGING)
        }.toSet()
        else -> emptySet()
    }

    fun field(profile: Int, control: SyuFactoryControl): Int =
        if (control == SyuFactoryControl.CAMERA_MODE && profile == 131109) 4 else control.field

    fun codes(profile: Int): Set<Int> = controls(profile).map { field(profile, it) }.toSet() + if (profile == HYBRID_PROFILE) setOf(299) else emptySet()

    fun decode(profile: Int, readings: Map<Int, Int>): Map<SyuFactoryControl, Int> =
        controls(profile).mapNotNull { control ->
            val raw = readings[field(profile, control)] ?: return@mapNotNull null
            if (control == SyuFactoryControl.SEAT_PRESET) {
                raw.takeIf { it in 0..2 }?.let { control to it }
            } else if (control.group == SyuFactoryGroup.AMBIENT) {
                raw.takeIf { it in 1..2 }?.let { control to it - 1 }
            } else if (control.group == SyuFactoryGroup.CHARGING) {
                val capability = readings[299]?.takeIf { it in 0..255 } ?: return@mapNotNull null
                val mask = when (control) {
                    SyuFactoryControl.CHARGE_CURRENT -> 128
                    SyuFactoryControl.CHARGE_TEMPERATURE -> 64
                    SyuFactoryControl.CHARGE_CLIMATE_BATTERY -> 32
                    SyuFactoryControl.CHARGE_MINIMUM -> 16
                    else -> 0
                }
                if (capability and mask == 0) return@mapNotNull null
                val value = when (control) {
                    SyuFactoryControl.CHARGE_CURRENT -> chargeCurrents.indexOf(raw)
                    SyuFactoryControl.CHARGE_TEMPERATURE -> chargeTemperatures.indexOf(raw)
                    else -> raw
                }
                value.takeIf { it in 0..control.maximum }?.let { control to it }
            } else if (control.group == SyuFactoryGroup.CAMERA) {
                raw.takeIf { it in 0..2 }?.let { control to it }
            } else {
                // WC encodes availability in the upper byte; parked folding is
                // explicitly exempt in mUpdaterFoldIn. Missing feedback is never off.
                if (raw !in 0..65535 ||
                    (control != SyuFactoryControl.MIRROR_PARK_FOLD && raw shr 8 == 0)) return@mapNotNull null
                (raw and 255).takeIf { it in 0..control.maximum }?.let { control to it }
            }
        }.toMap()

    fun frame(profile: Int, control: SyuFactoryControl, value: Int): Pair<Int, List<Int>>? {
        if (control !in controls(profile) || value !in 0..control.maximum) return null
        if (control == SyuFactoryControl.SEAT_PRESET) return 1 to listOf(152, value)
        if (control.group == SyuFactoryGroup.AMBIENT) return 109 to listOf(1, value + 1)
        if (control.group == SyuFactoryGroup.CHARGING) return 145 to when (control) {
            SyuFactoryControl.CHARGE_CURRENT -> listOf(1, chargeCurrents[value])
            SyuFactoryControl.CHARGE_TEMPERATURE -> listOf(2, chargeTemperatures[value])
            SyuFactoryControl.CHARGE_CLIMATE_BATTERY -> listOf(3, value)
            SyuFactoryControl.CHARGE_MINIMUM -> listOf(4, value)
            else -> return null
        }
        return if (control == SyuFactoryControl.CAMERA_MODE) {
            if (profile == 131114) 15 to listOf(value + 4, 255) else 2 to listOf(value)
        } else control.command to listOf(value)
    }
}
