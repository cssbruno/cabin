package com.cabin.platform

/** CommpassActi/RZCCommpassActi and service v, not WC's unrelated 102/103 commands. */
internal object CabinHondaCompass {
    private val separateBnrSettings = setOf(393514, 459050, 524586, 590122, 655658, 721194, 983338, 2621738)
    val fields = setOf(18, 50)
    fun supports(profile: Int) = (profile and 65535) == 298 && profile !in separateBnrSettings

    fun command(profile: Int, field: Int, value: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!supports(profile)) return null
        return when {
            field == 18 && value in 1..15 && raw[18] in 1..15 -> 102 to listOf(value)
            field == 50 && value in 0..1 && raw[50] in 0..1 -> 104 to listOf(value)
            else -> null
        }
    }

    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> = buildList {
        if (!supports(profile)) return@buildList
        raw[18]?.takeIf { it in 1..15 }?.let { value ->
            add(FytSyuReading("honda_compass_2023", 18, setOf(18), value.toString(),
                options = (1..15).associateWith(Int::toString), label = if (pt) "Zona da bússola" else "Compass zone"))
        }
        raw[50]?.takeIf { it in 0..1 }?.let { value ->
            val choices = if (pt) listOf("Desligado", "Ligado") else listOf("Off", "On")
            add(FytSyuReading("honda_compass_2023", 50, setOf(50), choices[value], value == 1,
                choices.withIndex().associate { it.index to it.value },
                if (pt) "Câmera com a seta à direita" else "Camera with right turn signal"))
        }
    }
}
