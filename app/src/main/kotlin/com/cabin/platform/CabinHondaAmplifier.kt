package com.cabin.platform

/** ActiAMP_Bnr, service v command 108 and packet 0x31. */
internal object CabinHondaAmplifier {
    private val profiles = setOf(590122, 721194, 2097450, 3277098, 2556202, 2490666)
    fun supports(profile: Int) = profile in profiles
    val fields = (139..147).toSet()
    private fun range(field: Int) = when (field) {
        139 -> 0..40
        140, 141 -> 0..18
        in 142..145 -> 0..12
        146 -> 0..3
        147 -> 0..1
        else -> IntRange.EMPTY
    }

    fun command(profile: Int, field: Int, value: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!supports(profile) || value !in range(field) || raw[field] !in range(field)) return null
        return 108 to listOf(if (field == 139) 9 else field - 139, value)
    }

    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> {
        if (!supports(profile)) return emptyList()
        fun label(en: String, translated: String) = if (pt) translated else en
        fun valueText(field: Int, value: Int): String = when (field) {
            140 -> when { value < 9 -> "${label("Front", "Frente")} ${9 - value}"; value > 9 -> "${label("Rear", "Trás")} ${value - 9}"; else -> "0" }
            141 -> when { value < 9 -> "${label("Left", "Esquerda")} ${9 - value}"; value > 9 -> "${label("Right", "Direita")} ${value - 9}"; else -> "0" }
            in 142..145 -> (value - 6).let { if (it > 0) "+$it" else it.toString() }
            146 -> listOf(label("Off", "Desligado"), label("Low", "Baixo"), label("Medium", "Médio"), label("High", "Alto"))[value]
            147 -> if (value == 1) label("On", "Ligado") else label("Off", "Desligado")
            else -> value.toString()
        }
        return fields.mapNotNull { field ->
            val value = raw[field]?.takeIf { it in range(field) } ?: return@mapNotNull null
            val name = when (field) {
                139 -> label("Factory amplifier volume", "Volume do amplificador original")
                140 -> label("Front/rear balance", "Balanço dianteiro/traseiro")
                141 -> label("Left/right balance", "Balanço esquerdo/direito")
                142 -> label("Treble", "Agudos")
                143 -> label("Midrange", "Médios")
                144 -> label("Bass", "Graves")
                145 -> label("Bass · second control", "Graves · segundo controle")
                146 -> label("Speed compensated volume", "Volume conforme a velocidade")
                else -> "DTS"
            }
            FytSyuReading("honda_amplifier_2023", field, setOf(field), valueText(field, value),
                checked = if (field == 147) value == 1 else null,
                options = range(field).associateWith { valueText(field, it) }, label = name)
        }
    }
}
