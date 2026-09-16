package com.cabin.platform

/** Base lighting controls from the July 2023 Golf7FunctionalLightActi contract. */
internal object CabinGolfLighting {
    val fields = (42..50).toSet() + setOf(78, 140, 141, 142, 143, 144, 235, 258, 267)
    private fun range(field: Int): IntRange = when (field) {
        42 -> 0..2
        in 46..48, 140, 141, 144, 267 -> 0..100
        235 -> 0..6
        258 -> 1..5
        49, 50 -> 0..30
        43, 44, 45, 78, 142, 143 -> 0..1
        else -> IntRange.EMPTY
    }
    private fun value(protocol: String, field: Int, raw: Map<Int, Int>): Int? {
        if (!CabinGolfSettings.supports(protocol) || field !in fields) return null
        if (field == 144 && protocol != "golf_rzc_2023") return null
        if (field in setOf(258, 267) && protocol != "golf_wc_2023") return null
        val encoded = raw[field]?.takeIf { it in 0..65535 } ?: return null
        val value = if (protocol == "golf_wc_2023") {
            if (encoded shr 8 == 0) return null
            encoded and 255
        } else if (field >= 140) encoded and 255 else encoded
        return value.takeIf { it in range(field) }
    }
    fun command(protocol: String, field: Int, target: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (value(protocol, field, raw) == null || target !in range(field)) return null
        return when (field) {
            140, 141 -> 105 to listOf(field - 127, target)
            142, 143 -> 105 to listOf(field - 141, target)
            144, 267 -> 105 to listOf(15, target)
            235 -> 133 to listOf(target)
            258 -> 135 to listOf(target)
            else -> (if (field == 78) 13 else field + 16) to listOf(target)
        }
    }
    fun read(protocol: String, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> {
        fun label(en: String, pt: String) = if (portuguese) pt else en
        val names = mapOf(42 to label("Headlight activation timing", "Momento de acendimento dos faróis"),
            43 to label("Headlights in rain", "Faróis na chuva"), 44 to label("Lane-change indicators", "Setas de mudança de faixa"),
            45 to label("Headlight traffic side", "Lado de circulação dos faróis"), 46 to label("Instrument illumination", "Iluminação do painel"),
            47 to label("Door illumination", "Iluminação das portas"), 48 to label("Footwell illumination", "Iluminação dos pés"),
            49 to label("Coming-home lighting", "Iluminação ao chegar"), 50 to label("Leaving-home lighting", "Iluminação ao sair"),
            78 to label("Daytime running lights", "Luzes diurnas"),
            140 to label("Interior mood lighting", "Iluminação ambiente interna"),
            141 to label("Front right lighting", "Iluminação dianteira direita"),
            142 to label("Dynamic Light Assist", "Assistente dinâmico dos faróis"),
            143 to label("Adaptive front lighting", "Iluminação dianteira adaptativa"),
            144 to label("Overall illumination", "Iluminação geral"),
            267 to label("Overall illumination", "Iluminação geral"),
            235 to label("Headlight range", "Alcance dos faróis"),
            258 to label("Lane Assist brightness", "Brilho do assistente de faixa"))
        return fields.mapNotNull { field -> value(protocol, field, raw)?.let { current ->
            fun text(value: Int): String = when (field) {
                42 -> listOf(label("Early", "Cedo"), label("Medium", "Médio"), label("Late", "Tarde"))[value]
                45 -> if ((value == 0) == (protocol == "golf_wc_2023")) label("Left-hand traffic", "Circulação pela esquerda") else label("Right-hand traffic", "Circulação pela direita")
                in 46..48, 140, 141, 144, 267 -> if (value == 0) label("Minimum", "Mínimo") else "$value%"
                235, 258 -> value.toString()
                49, 50 -> if (value == 0) label("Off", "Desligado") else "$value s"
                else -> if (value == 0) label("Off", "Desligado") else label("On", "Ligado")
            }
            val targets = when (field) {
                in 46..48, 140, 141, 144, 267 -> (0..100 step 10).toSet() + current
                49, 50 -> (0..30 step 5).toSet() + current
                else -> range(field).toSet()
            }
            FytSyuReading(protocol, field, setOf(field), text(current),
                options = targets.sorted().associateWith(::text), label = names.getValue(field))
        } }
    }
}
