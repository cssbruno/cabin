package com.cabin.platform

/** Facts from the pinned HondaTripActi and HondaIndexActi reference screens. */
data class SyuVehicleTelemetry(
    val tripSupported: Boolean = false,
    val hybridSupported: Boolean = false,
    val averageConsumption: Double? = null,
    val previousConsumption: Double? = null,
    val consumptionUnit: String? = null,
    val batterySegments: Int? = null,
    val lighting: Map<SyuLightingSetting, Int> = emptyMap(),
    val amplifier: Map<SyuAmplifierSetting, Int> = emptyMap(),
    val tires: List<SyuTireReading> = emptyList(),
    val factoryControls: Map<SyuFactoryControl, Int> = emptyMap(),
    val factoryCapabilities: Set<SyuFactoryControl> = emptySet(),
    val energy: SyuEnergyTelemetry? = null,
)

internal object SyuVehicleProtocol {
    // Explicit identities in HondaTripActi.init; no low-word/family wildcard.
    val tripProfiles = setOf(
        459073, 524609, 590145, 786730, 786753, 852266, 852289, 917825,
        1179969, 1245505, 1311041, 1769770, 1835306, 1900842, 2425130,
        2490666, 2556202, 2687274, 2752810, 3014954, 3080490, 3080513,
        3146049, 3211562, 3277098, 3932458, 3997994, 4194602, 4784426,
        4849962, 4915521, 5439786, 5505345, 5570881,
    )
    // HondaIndexActi.showHondaEVSettings, not all Honda hybrid variants.
    val hybridProfiles = setOf(5636394, 5701930, 5767466, 5833002, 5898538,
        5964074, 6029610, 6095146, 6750506, 6816042, 6881578, 6947114)

    val tireProfiles = setOf(1376590, 1442126, 1507662, 1573198, 1638734, 1704270)
    const val AMPLIFIER_PROFILE = 393537

    // Same read requests made when the source trip/tire screen resumes.
    fun queryFrames(profile: Int): List<Pair<Int, List<Int>>> = when {
        profile == SyuFactoryProtocol.HYBRID_PROFILE -> listOf(98 to listOf(3))
        profile in tireProfiles -> listOf(0 to listOf(0))
        profile in tripProfiles -> listOf(100 to listOf(1), 100 to listOf(3))
        else -> emptyList()
    }

    fun codes(profile: Int): Set<Int> = buildSet {
        addAll(SyuFactoryProtocol.codes(profile))
        if (profile == SyuFactoryProtocol.HYBRID_PROFILE) addAll(listOf(312, 321))
        if (profile in tireProfiles) addAll(146..153)
        if (profile == AMPLIFIER_PROFILE) addAll(SyuAmplifierSetting.entries.map { it.code })
        if (profile in tripProfiles) addAll(listOf(99, 100, 105))
        if (profile in hybridProfiles) add(309)
        if (profile in lightingProfiles) addAll(SyuLightingSetting.entries.map { it.code })
    }

    // HondaIndexActi.isBNRSiYuOrGuanDao routes these exact profiles to AcrivitySiYuSettings.
    val lightingProfiles = setOf(393514, 459050, 524586, 590122, 655658, 721194, 2621738)

    fun decode(profile: Int, readings: Map<Int, Int>): SyuVehicleTelemetry {
        val trip = profile in tripProfiles
        val hybrid = profile in hybridProfiles
        // Unknown/missing units and 65535 are unavailable, never zero consumption.
        val unit = if (trip) when (readings[105]) {
            0 -> "mpg"; 1 -> "km/L"; 2 -> "L/100 km"; else -> null
        } else null
        fun consumption(code: Int) = if (unit != null) readings[code]?.takeIf { it in 0..65534 }?.div(10.0) else null
        return SyuVehicleTelemetry(trip, hybrid, consumption(99), consumption(100), unit,
            if (hybrid) readings[309]?.takeIf { it in 0..10 } else null,
            if (profile in lightingProfiles) SyuLightingSetting.entries.mapNotNull { setting ->
                readings[setting.code]?.takeIf { it in setting.values.indices }?.let { setting to it }
            }.toMap() else emptyMap(),
            if (profile == AMPLIFIER_PROFILE) SyuAmplifierSetting.entries.mapNotNull { setting ->
                readings[setting.code]?.takeIf { it in 0..18 }?.let { setting to it }
            }.toMap() else emptyMap(),
            if (profile in tireProfiles) (0..3).map { wheel ->
                SyuTireReading(readings[146 + wheel]?.takeIf { it in 0..254 }?.times(2.75),
                    readings[150 + wheel]?.takeIf { it in 0..7 })
            } else emptyList(), SyuFactoryProtocol.decode(profile, readings), SyuFactoryProtocol.controls(profile), decodeEnergy(profile, readings))
    }
}


/** AcrivitySiYuSettings: values are selected enums, not arbitrary bytes. */
enum class SyuLightingSetting(val code: Int, val commandKey: Int, val values: List<Int>) {
    SENSITIVITY(122, 6, listOf(1, 2, 3, 4, 5)),
    HEADLIGHT_DELAY(123, 5, listOf(0, 15, 30, 60)),
    INTERIOR_DELAY(124, 4, listOf(15, 30, 60));
}

/** Wc_16Civic_AMPSetActi, exposed only by FunctionalActi for profile 393537. */
enum class SyuAmplifierSetting(val code: Int, val commandKey: Int) {
    BALANCE(201, 2), FADER(202, 3);
}

data class SyuTireReading(val pressureKpa: Double? = null, val warning: Int? = null)

/** Golf7Electric_information_Acti: direction does not distinguish plug charging from regeneration. */
enum class SyuEnergyDirection { CHARGING, DISCHARGING }
data class SyuEnergyTelemetry(val batteryPercent: Int?, val direction: SyuEnergyDirection?)
internal fun decodeEnergy(profile: Int, readings: Map<Int, Int>): SyuEnergyTelemetry? =
    if (profile != SyuFactoryProtocol.HYBRID_PROFILE) null else SyuEnergyTelemetry(
        readings[321]?.takeIf { it in 0..100 },
        when (readings[312]) { 1 -> SyuEnergyDirection.CHARGING; 2 -> SyuEnergyDirection.DISCHARGING; else -> null },
    )
