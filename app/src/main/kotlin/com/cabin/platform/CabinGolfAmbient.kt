package com.cabin.platform

/** Native ambient color contract from Golf7FunctionalLightActi, July 2023. */
internal object CabinGolfAmbient {
    val fields = setOf(139, 339, 371, 372, 385, 395, 399, 400, 401, 402)

    private fun allowed(profile: Int, protocol: String): IntRange = when {
        protocol == "golf_wc_2023" -> 0..33
        protocol != "golf_rzc_2023" || profile == 3342496 -> IntRange.EMPTY
        profile == 3604640 -> 0..20
        else -> 0..10
    }

    private fun current(profile: Int, protocol: String, raw: Map<Int, Int>): Int? {
        val encoded = raw[139]?.takeIf { it in 0..65535 } ?: return null
        if (protocol == "golf_wc_2023" && encoded shr 8 == 0) return null
        return (encoded and 255).takeIf { it in allowed(profile, protocol) }
    }

    fun command(profile: Int, protocol: String, field: Int, target: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (field != 139) {
            val options = extraOptions(profile, protocol, field, false)
            if (raw[field] !in options || target !in options) return null
            if (field == 400 && target == 2) return null
            val selector = when (field) {
                339 -> 74
                385 -> 75
                371, 372 -> 76
                395 -> 77
                399 -> 79
                400 -> 101
                401, 402 -> 78
                else -> return null
            }
            return 160 to listOf(selector, if (field == 402) target or 128 else target)
        }
        if (current(profile, protocol, raw) == null || target !in allowed(profile, protocol)) return null
        return 105 to listOf(12, target)
    }

    private fun colorRead(profile: Int, protocol: String, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> {
        val value = current(profile, protocol, raw) ?: return emptyList()
        fun label(en: String, pt: String) = if (portuguese) pt else en
        fun color(value: Int): String = when {
            value == 0 -> label("Off", "Desligado")
            value == 1 -> label("White", "Branco")
            value == 2 -> label("Orange", "Laranja")
            value == 3 -> label("Blue", "Azul")
            protocol == "golf_wc_2023" -> label("Color", "Cor") + " ${value - 3}"
            value == 4 -> label("Red", "Vermelho")
            value == 5 -> label("Orange-yellow", "Amarelo alaranjado")
            value == 6 -> label("Earth yellow", "Amarelo terroso")
            value == 7 -> label("Green", "Verde")
            value == 8 -> label("Sapphire blue", "Azul safira")
            value == 9 -> label("Dark sky blue", "Azul-celeste escuro")
            value == 10 -> label("Pink", "Rosa")
            else -> label("Color", "Cor") + " $value"
        }
        return listOf(FytSyuReading(protocol, 139, setOf(139), color(value),
            options = allowed(profile, protocol).associateWith(::color),
            label = label("Ambient lighting color", "Cor da iluminação ambiente")))
    }

    private fun extraOptions(profile: Int, protocol: String, field: Int, portuguese: Boolean): Map<Int, String> {
        if (protocol != "golf_rzc_2023") return emptyMap()
        fun label(en: String, pt: String) = if (portuguese) pt else en
        if (field in setOf(371, 372, 395) && profile != 2818208) return emptyMap()
        if (field in setOf(399, 400, 401, 402) && profile != 3342496) return emptyMap()
        if (field in setOf(339, 385) && profile == 3342496) return emptyMap()
        return when (field) {
            339 -> mapOf(0 to label("Off", "Desligado"), 1 to label("Manual", "Manual"), 2 to label("Automatic", "Automático"))
            385 -> (1..3).associateWith { label("Type", "Tipo") + " $it" }
            371 -> mapOf(0 to label("Off", "Desligado"), 1 to label("On", "Ligado"))
            372 -> mapOf(1 to label("White", "Branco"), 4 to label("Green", "Verde"), 5 to label("Blue", "Azul"))
            395 -> (0..100).associateWith { "$it%" }
            399 -> mapOf(0 to label("Same", "Iguais"), 1 to label("Different", "Diferentes"))
            // Feedback 2 is Off, but the source buttons skip it as a command target.
            // Value 8 is also displayed as Off by the source.
            400 -> mapOf(1 to label("Individual", "Individual"), 2 to label("Off", "Desligado"), 3 to label("Infinite", "Infinito"),
                4 to label("Eternal", "Eterno"), 5 to label("Desire", "Desejo"),
                6 to label("Intoxication", "Enlevo"), 7 to label("Vitality", "Vitalidade"),
                8 to label("Off", "Desligado"))
            401, 402 -> (1..10).associateWith { label("Color", "Cor") + " $it" }
            else -> emptyMap()
        }
    }

    fun read(profile: Int, protocol: String, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> = buildList {
        addAll(colorRead(profile, protocol, raw, portuguese))
        fun label(en: String, pt: String) = if (portuguese) pt else en
        val labels = mapOf(339 to label("Ambient lighting mode", "Modo da iluminação ambiente"),
            385 to label("Ambient lighting vehicle type", "Tipo de veículo da iluminação ambiente"),
            371 to label("Three-color lighting", "Iluminação de três cores"),
            372 to label("Three-color lighting color", "Cor da iluminação de três cores"),
            395 to label("Glovebox lighting", "Iluminação do porta-luvas"),
            399 to label("Color pairing", "Combinação de cores"),
            400 to label("Ambient lighting scene", "Cena da iluminação ambiente"),
            401 to label("First color", "Primeira cor"), 402 to label("Second color", "Segunda cor"))
        for ((field, name) in labels) {
            val current = raw[field] ?: continue
            val options = extraOptions(profile, protocol, field, portuguese)
            val text = options[current] ?: continue
            val displayedOptions = when (field) {
                395 -> options.filterKeys { it % 10 == 0 || it == current }
                400 -> options.filterKeys { it != 2 }
                else -> options
            }
            add(FytSyuReading(protocol, field, setOf(field), text, options = displayedOptions, label = name))
        }
    }
}
