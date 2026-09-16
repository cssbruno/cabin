package com.cabin.platform

/** Explorer seat support/massage, FordCarSeatInfo and eb packet 0x64. */
internal object CabinFordSeats {
    fun supports(profile: Int) = profile == 590158
    val fields = (100..111).toSet()
    private val supportSelectors = mapOf(102 to 0, 103 to 1, 104 to 2, 107 to 3, 108 to 4, 109 to 5)
    private val massageSelectors = mapOf(105 to 6, 106 to 7, 110 to 8, 111 to 9)
    private fun modeField(field: Int) = if (field in 102..106) 100 else 101
    fun releaseFrame(profile: Int, field: Int): Pair<Int, List<Int>>? =
        if (supports(profile)) supportSelectors[field]?.let { 11 to listOf(167, it, 0) } else null

    fun command(profile: Int, field: Int, value: Int, current: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!supports(profile)) return null
        val selector = when (field) {
            100, 101 -> {
                if (current[field] !in 0..2 || value !in 1..2) return null
                field - 90
            }
            in supportSelectors -> {
                if (current[modeField(field)] != 1 || current[field] !in 0..15 || value !in 1..2) return null
                supportSelectors.getValue(field)
            }
            in massageSelectors -> {
                if (current[modeField(field)] != 2 || current[field] !in 0..2 || value !in 0..2) return null
                massageSelectors.getValue(field)
            }
            else -> return null
        }
        return 11 to listOf(167, selector, value)
    }

    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> = buildList {
        if (!supports(profile)) return@buildList
        val modes = if (pt) listOf("Inativo", "Apoio lombar", "Massagem") else listOf("Inactive", "Lumbar support", "Massage")
        val levels = if (pt) listOf("Desligado", "Baixo", "Alto") else listOf("Off", "Low", "High")
        for (side in 0..1) {
            val mode = raw[100 + side]?.takeIf { it in modes.indices } ?: continue
            val seat = if (pt) (if (side == 0) "Banco esquerdo" else "Banco direito") else (if (side == 0) "Left seat" else "Right seat")
            add(FytSyuReading("ford_0334", 100 + side, setOf(100 + side), modes[mode],
                options = mapOf(1 to modes[1], 2 to modes[2]), label = seat))
            val first = 102 + side * 5
            if (mode == 1) for (zone in 0..2) raw[first + zone]?.takeIf { it in 0..15 }?.let { value ->
                add(FytSyuReading("ford_0334", first + zone, setOf(first + zone, 100 + side), value.toString(),
                    options = mapOf(1 to if (pt) "Aumentar · 0,25 s" else "Increase · 0.25 s", 2 to if (pt) "Diminuir · 0,25 s" else "Decrease · 0.25 s"),
                    label = "$seat · ${modes[1]} ${zone + 1}"))
            }
            if (mode == 2) for (zone in 0..1) raw[first + 3 + zone]?.takeIf { it in levels.indices }?.let { value ->
                add(FytSyuReading("ford_0334", first + 3 + zone, setOf(first + 3 + zone, 100 + side), levels[value],
                    options = levels.withIndex().associate { it.index to it.value }, label = "$seat · ${modes[2]} ${zone + 1}"))
            }
        }
    }
}
