package com.cabin.platform

/** July RZC hybrid: three independent 19-field schedules, sent as ten-byte records. */
internal object CabinGolfRzcCharging {
    private const val PROFILE = 655520
    fun fields(profile: Int): Set<Int> = if (profile == PROFILE)
        (404..465).toSet() + setOf(360, 373, 374, 378, 379, 380, 381) else emptySet()
    private fun base(field: Int) = 407 + ((field - 407) / 19) * 19
    private fun valid(offset: Int, value: Int) = when (offset) {
        0, 14, 16 -> value in 0..23
        1 -> value in 0..59
        6 -> value in 0..3
        15, 17 -> value == 0 || value == 30
        18 -> value in 10..100
        else -> value in 0..1
    }

    fun command(profile: Int, field: Int, target: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (profile != PROFILE) return null
        if (field in 404..406) {
            if (target !in 0..1 || (404..406).any { raw[it] !in 0..1 }) return null
            val next = raw + (field to target)
            return 142 to listOf(0, next.getValue(406) or (next.getValue(405) shl 1) or (next.getValue(404) shl 2))
        }
        if (field !in 407..463) return null
        val start = base(field)
        if (!valid(field - start, target) || (field - start == 1 && target % 5 != 0)) return null
        val values = (0..18).map { offset -> raw[start + offset]?.takeIf { valid(offset, it) } ?: return null }.toMutableList()
        values[field - start] = target
        val flags = (values[2] shl 7) or (values[3] shl 6) or (values[4] shl 5) or (values[5] shl 4) or values[6]
        val days = (7..13).fold(0) { bits, offset -> bits or (values[offset] shl (14 - offset)) }
        return 143 to listOf((start - 407) / 19 + 1, values[0], values[1], flags, days,
            values[14], values[15], values[16], values[17], values[18])
    }

    fun read(profile: Int, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> = buildList {
        if (profile != PROFILE) return@buildList
        fun label(en: String, pt: String) = if (portuguese) pt else en
        val bool = mapOf(0 to label("Off", "Desligado"), 1 to label("On", "Ligado"))
        val distances = mapOf(373 to label("Driving range", "Autonomia total"),
            374 to label("Electric range", "Autonomia elétrica"),
            379 to label("Zero-emission mileage", "Distância sem emissões"),
            380 to label("Zero-emission range", "Autonomia sem emissões"))
        for ((field, name) in distances) raw[field]?.takeIf { it in 0..65534 }?.let {
            add(FytSyuReading("golf_rzc_2023", field, setOf(field), "${it / 10} km", label = name))
        }
        raw[378]?.takeIf { it in 0..100 }?.let {
            add(FytSyuReading("golf_rzc_2023", 378, setOf(378), "$it%", label = label("Battery charge", "Carga da bateria")))
        }
        raw[381]?.takeIf { it in 0..254 }?.let {
            add(FytSyuReading("golf_rzc_2023", 381, setOf(381), "${it / 10}%",
                label = label("Electric/fuel mileage ratio", "Relação de distância elétrica/combustível")))
        }
        raw[360]?.takeIf { it in 0..255 }?.let {
            val temperature = when {
                it < 60 -> "LO"
                it > 195 -> "HI"
                else -> java.lang.String.format(java.util.Locale.ROOT, "%.1f °C", (it + 100) / 10.0)
            }
            add(FytSyuReading("golf_rzc_2023", 360, setOf(360), temperature,
                label = label("Cabin conditioning temperature", "Temperatura de climatização")))
        }
        val names = listOf(label("Departure hour", "Hora de saída"), label("Departure minute", "Minuto de saída"),
            label("Repeat", "Repetir"), label("Charging", "Carga"), label("Climate control", "Climatização"),
            label("Night charging", "Carga noturna"), label("Maximum current", "Corrente máxima"),
            label("Sunday", "Domingo"), label("Saturday", "Sábado"), label("Friday", "Sexta-feira"),
            label("Thursday", "Quinta-feira"), label("Wednesday", "Quarta-feira"), label("Tuesday", "Terça-feira"),
            label("Monday", "Segunda-feira"), label("Night start hour", "Hora de início noturno"),
            label("Night start minute", "Minuto de início noturno"), label("Night end hour", "Hora de término noturno"),
            label("Night end minute", "Minuto de término noturno"), label("Charge limit", "Limite de carga"))
        for (field in 404..406) raw[field]?.takeIf { it in 0..1 }?.let { value ->
            add(FytSyuReading("golf_rzc_2023", field, setOf(field), bool.getValue(value),
                options = if (command(profile, field, value, raw) != null) bool else emptyMap(),
                label = label("Schedule", "Programação") + " ${407 - field}"))
        }
        for (start in listOf(407, 426, 445)) for (offset in 0..18) {
            val field = start + offset
            val value = raw[field]?.takeIf { valid(offset, it) } ?: continue
            val options = when (offset) {
                0, 14, 16 -> (0..23).associateWith { "%02d".format(it) }
                1 -> (0..55 step 5).associateWith { "%02d".format(it) }
                6 -> mapOf(0 to "5 A", 1 to "10 A", 2 to "13 A", 3 to "MAX")
                15, 17 -> mapOf(0 to "00", 30 to "30")
                18 -> (10..100).associateWith { "$it%" }
                else -> bool
            }
            val text = options[value] ?: "%02d".format(value)
            val writable = command(profile, field, options.keys.first(), raw) != null
            add(FytSyuReading("golf_rzc_2023", field, setOf(field), text,
                options = if (writable) options else emptyMap(),
                label = "${label("Schedule", "Programação")} ${(start - 407) / 19 + 1} · ${names[offset]}"))
        }
        val hour = raw[464]?.takeIf { it in 0..23 }
        val minute = raw[465]?.takeIf { it in 0..59 }
        if (hour != null && minute != null) add(FytSyuReading("golf_rzc_2023", 464, setOf(464, 465),
            "%02d:%02d".format(hour, minute), label = label("Vehicle clock", "Relógio do veículo")))
    }
}
