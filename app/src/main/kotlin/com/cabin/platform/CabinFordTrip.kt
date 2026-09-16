package com.cabin.platform

/** FordCarInfo/FordCarInfo2 and July service f0.eb packets 0x63/0x69. */
internal object CabinFordTrip {
    private val otherScreens = setOf(590158, 1376590, 1442126, 1507662, 1573198, 1638734, 1704270)
    fun supports(profile: Int) = profile and 0xffff == 334 && profile !in otherScreens
    fun fields(profile: Int): Set<Int> = if (!supports(profile)) emptySet()
        else (74..77).toSet() + if (profile == 1114446) (176..179).toSet() else emptySet()

    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> = buildList {
        if (!supports(profile)) return@buildList
        fun row(field: Int, range: IntRange, en: String, br: String, format: (Int) -> String) {
            raw[field]?.takeIf { it in range }?.let {
                add(FytSyuReading("ford_0334", field, setOf(field), format(it), label = if (pt) br else en))
            }
        }
        row(74, 0..0xffffff, "Odometer", "Odômetro") { "$it km" }
        row(75, 0..999, "Range", "Autonomia") { "$it km" }
        row(76, 0..149, "Average consumption", "Consumo médio") { "${(it * 2 + 1) / 10}.${(it * 2 + 1) % 10} L/100 km" }
        row(77, 0..201, "Fuel remaining", "Combustível restante") { "${it / 2} L" }
        if (profile == 1114446) {
            // This is average speed, never a live speedometer input.
            row(176, 0..254, "Average speed", "Velocidade média") { "$it km/h" }
            row(177, 0..65535, "Maintenance distance", "Distância até a manutenção") { "${it / 2} km" }
            row(178, 0..100, "Battery charge", "Carga da bateria") { "$it%" }
            row(179, 0..255, "Battery voltage", "Tensão da bateria") {
                val tenths = it * 470 / 755 + 30
                "${tenths / 10}.${tenths % 10} V"
            }
        }
    }
}
