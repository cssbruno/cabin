package com.cabin.platform

/** MustangCarEQSet, routed by FordIndexAct/MustangIndexAct; service eb commands 7/8. */
internal object CabinFordAmplifier {
    private val profiles = setOf(1048910, 1900878, 2031950, 1376590, 1442126, 1507662, 1573198, 1638734, 1704270)
    val fields = (91..98).toSet() + 130
    fun supports(profile: Int) = profile in profiles
    private fun options(field: Int, pt: Boolean): Map<Int, String> = when (field) {
        in 91..95 -> (0..14).associateWith { (it - 7).toString() }
        96 -> (if (pt) listOf("Desligado", "Baixo", "Médio", "Alto") else listOf("Off", "Low", "Medium", "High")).withIndex().associate { it.index to it.value }
        97 -> mapOf(0 to "3D", 1 to "Surround")
        98 -> (0..30).associateWith(Int::toString)
        130 -> mapOf(0 to if (pt) "Todos os passageiros" else "All passengers", 1 to if (pt) "Motorista" else "Driver")
        else -> emptyMap()
    }
    fun command(profile: Int, field: Int, value: Int, current: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!supports(profile)) return null
        val valid = options(field, false)
        if (value !in valid || current[field] !in valid) return null
        return if (field == 98) 7 to listOf(value) else 8 to listOf(if (field == 130) 7 else field - 91, value)
    }
    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> {
        if (!supports(profile)) return emptyList()
        val names = if (pt) listOf("Agudos", "Médios", "Graves", "Balanço dianteiro/traseiro", "Balanço esquerdo/direito", "Volume conforme a velocidade", "Modo de som", "Volume original", "Posição de escuta")
            else listOf("Treble", "Midrange", "Bass", "Front/rear balance", "Left/right balance", "Speed-compensated volume", "Sound mode", "Factory volume", "Listening position")
        return fields.mapIndexedNotNull { i, field ->
            val opts = options(field, pt)
            raw[field]?.let(opts::get)?.let { FytSyuReading("ford_0334", field, setOf(field), it, options = opts, label = names[i]) }
        }
    }
}
