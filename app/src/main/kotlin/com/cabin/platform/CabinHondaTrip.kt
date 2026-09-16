package com.cabin.platform

/** HondaTripActi and July 2023 service implementations v (RZC) and x (WC). */
internal object CabinHondaTrip {
    const val WC_CALLBACK = "Lcom/syu/module/canbus/Callback_0321_WC2_Honda_AllCom;"
    private val noTripB = setOf(65834, 131370, 196906, 262442, 327978, 393514, 459050, 524586, 590122, 655658, 721194, 852266, 983338, 2294058, 2621738)
    fun supportsTripB(profile: Int) = profile and 65535 == 298 && profile !in noTripB
    fun fields(profile: Int): Set<Int> = (0..17).toSet() + setOf(103, 104) +
        (if (supportsTripB(profile)) (213..223).toSet() + 226 else emptySet()) + if (profile == 1966378) setOf(174) else emptySet()
    fun requests(profile: Int, published: Set<Int>): List<Pair<Int, List<Int>>> = buildList {
        if (setOf(1, 2, 7).all { it in published }) add(100 to listOf(1))
        if (setOf(3, 4, 8, 9).all { it in published }) add(100 to listOf(2))
        if (supportsTripB(profile) && setOf(213, 214, 222, 223).all { it in published }) add(100 to listOf(4))
    }
    private fun unit(value: Int?) = when (value) { 0 -> "mpg"; 1 -> "km/L"; 2 -> "L/100 km"; else -> null }
    private fun average(raw: Map<Int, Int>, field: Int): Double? =
        if (unit(raw[7]) == null) null else raw[field]?.takeIf { it in 0..65534 }?.div(10.0)

    fun telemetry(previous: SyuVehicleTelemetry, raw: Map<Int, Int>): SyuVehicleTelemetry = previous.copy(
        tripSupported = true,
        averageConsumption = average(raw, 1), previousConsumption = average(raw, 2), consumptionUnit = unit(raw[7]),
    )

    fun read(profile: Int, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> = buildList {
        fun label(en: String, pt: String) = if (portuguese) pt else en
        fun row(id: Int, dependencies: Set<Int>, text: String, name: String, screen: String = "honda_trip_2023") {
            add(FytSyuReading(screen, id, dependencies, text, label = name))
        }
        for (field in listOf(1, 2)) average(raw, field)?.let {
            row(field, setOf(field, 7), String.format(java.util.Locale.ROOT, "%.1f %s", it, unit(raw[7])),
                if (field == 1) label("Current average consumption", "Consumo médio atual") else label("Previous average consumption", "Consumo médio anterior"))
        }
        // The source presents this as a 21-step gauge, not a calibrated numeric fuel reading.
        raw[0]?.takeIf { it in 0..254 }?.let {
            row(0, setOf(0), "${it.coerceAtMost(21)}/21", label("Instant consumption indicator", "Indicador de consumo instantâneo"))
        }
        unit(raw[6])?.let { row(6, setOf(6), it, label("Instant consumption unit", "Unidade do consumo instantâneo")) }
        val distanceUnit = when (raw[10]) { 0 -> "km"; 1 -> "mi"; else -> null }
        if (distanceUnit != null) raw[5]?.takeIf { it in 0..65534 }?.let {
            row(5, setOf(5, 10), "$it $distanceUnit", label("Remaining range", "Autonomia restante"))
        }
        fun history(trip: String, distanceFields: List<Int>, consumptionFields: List<Int>, distanceUnitField: Int, consumptionUnitField: Int) {
            val distanceUnit = when (raw[distanceUnitField]) { 0 -> "km"; 1 -> "mi"; else -> null }
            val consumptionUnit = unit(raw[consumptionUnitField])
            for (index in 0..3) {
                val period = if (index == 0) label("current", "atual") else label("history $index", "histórico $index")
                val distanceField = distanceFields[index]
                if (distanceUnit != null) raw[distanceField]?.takeIf { it in 0..16777214 && it != 65535 }?.let {
                    row(distanceField, setOf(distanceField, distanceUnitField), String.format(java.util.Locale.ROOT, "%.1f %s", it / 10.0, distanceUnit),
                        label("Trip $trip · $period distance", "Viagem $trip · distância $period"))
                }
                val consumptionField = consumptionFields[index]
                if (consumptionUnit != null) raw[consumptionField]?.takeIf { it in 0..65534 }?.let {
                    row(consumptionField, setOf(consumptionField, consumptionUnitField), String.format(java.util.Locale.ROOT, "%.1f %s", it / 10.0, consumptionUnit),
                        label("Trip $trip · $period consumption", "Viagem $trip · consumo $period"))
                }
            }
        }
        history("A", listOf(4, 12, 14, 16), listOf(3, 13, 15, 17), 9, 8)
        if (supportsTripB(profile)) history("B", listOf(213, 215, 217, 219), listOf(214, 216, 218, 220), 222, 223)
        for ((scale, unitCode) in if (supportsTripB(profile)) listOf(11 to 8, 226 to 223) else listOf(11 to 8)) {
            val scaleUnit = unit(raw[unitCode])
            if (scaleUnit != null) raw[scale]?.takeIf { it in 1..65534 }?.let {
                val trip = if (scale == 11) "A" else "B"
                row(scale, setOf(scale, unitCode), "0 · ${it / 20} · ${it / 10} $scaleUnit",
                    label("Trip $trip consumption scale", "Escala de consumo da viagem $trip"))
            }
        }
        if (supportsTripB(profile)) {
            val rangeUnit = when (raw[221]) { 0 -> "km"; 1 -> "mi"; else -> null }
            if (rangeUnit != null) raw[5]?.takeIf { it in 0..65534 }?.let {
                row(5, setOf(5, 221), "$it $rangeUnit", label("Trip B remaining range", "Autonomia restante da viagem B"), "honda_trip_b_2023")
            }
        }
        if (profile == 1966378) raw[174]?.takeIf { it in 0..100 }?.let {
            row(174, setOf(174), "$it%", label("Remaining battery", "Bateria restante"))
        }
        val hours = raw[103]?.takeIf { it in 0..65534 }
        val minutes = raw[104]?.takeIf { it in 0..59 }
        if (hours != null && minutes != null) row(103, setOf(103, 104), String.format(java.util.Locale.ROOT, "%d:%02d", hours, minutes),
            label("Driving time", "Tempo de condução"))
    }
}
