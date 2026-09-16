package com.cabin.platform

/** FordTireAct and f0.eb (0334), July 2023. Pressures are always raw * 2.75 kPa. */
internal object CabinFordTires {
    const val CALLBACK = "Lcom/syu/module/canbus/Callback_0334_RZC_XP1_Focus2015;"
    val fields = (78..89).toSet() + 180
    private val withoutTemperature = setOf(1376590, 1442126, 1507662, 1573198, 1638734, 1704270)
    fun telemetry(profile: Int, previous: SyuVehicleTelemetry, raw: Map<Int, Int>): SyuVehicleTelemetry = previous.copy(
        tireProfileId = profile,
        tires = (0..3).map { wheel -> SyuTireReading(
            raw[78 + wheel]?.takeIf { it in 0..254 }?.times(2.75),
            raw[82 + wheel]?.takeIf { it in 0..7 },
        ) },
    )
    fun read(profile: Int, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> = buildList {
        val wheels = if (portuguese) listOf("Dianteiro esquerdo", "Dianteiro direito", "Traseiro esquerdo", "Traseiro direito")
            else listOf("Front left", "Front right", "Rear left", "Rear right")
        val warnings = if (portuguese) listOf("Normal", "Pressão alta", "Pressão baixa", "Vazamento rápido", "Falha do sensor", "Bateria do sensor fraca", "Sensor sem sinal", "Vazamento rápido")
            else listOf("Normal", "High pressure", "Low pressure", "Rapid leak", "Sensor failure", "Sensor battery low", "Sensor signal lost", "Rapid leak")
        val unit = raw[180]?.takeIf { it in 0..2 }
        fun row(id: Int, text: String, name: String, dependencies: Set<Int> = setOf(id)) {
            add(FytSyuReading("ford_0334", id, dependencies, text, label = name))
        }
        for (wheel in 0..3) {
            if (unit != null) raw[78 + wheel]?.takeIf { it in 0..254 }?.let { value ->
                // Match the reference's display precision while preserving exact kPa in the widget model.
                val text = when (unit) {
                    0 -> "${value * 275 / 100} kPa"
                    1 -> "${value * 399 / 1000}.${value * 399 / 100 % 10} psi"
                    else -> "${value * 275 / 10000}.${value * 275 / 1000 % 10} bar"
                }
                row(78 + wheel, text, wheels[wheel] + if (portuguese) " · pressão" else " · pressure", setOf(78 + wheel, 180))
            }
            raw[82 + wheel]?.takeIf { it in 0..7 }?.let { value ->
                row(82 + wheel, warnings[value], wheels[wheel] + if (portuguese) " · aviso" else " · warning")
            }
            if (profile !in withoutTemperature) raw[86 + wheel]?.takeIf { it in 1..254 }?.let { value ->
                row(86 + wheel, "${value - 60}°C", wheels[wheel] + if (portuguese) " · temperatura" else " · temperature")
            }
        }
    }
}
