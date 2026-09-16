package com.cabin.platform

/** WC Golf hybrid's July 2023 data and charging settings. RZC has a different contract. */
internal object CabinGolfHybrid {
    private const val PROFILE = 655377
    fun fields(profile: Int) = if (profile == PROFILE) (271..275).toSet() + setOf(177, 293, 294, 295, 296, 297, 303) else emptySet()
    private fun choices(field: Int, portuguese: Boolean): Map<Int, String> = when (field) {
        272 -> mapOf(5 to "5 A", 10 to "10 A", 13 to "13 A", 255 to "MAX")
        273 -> (listOf(254) + (32..59) + 255).associateWith { when (it) {
            254 -> "MIN"; 255 -> "MAX"; else -> String.format(java.util.Locale.ROOT, "%.1f °C", it / 2.0)
        } }
        274 -> if (portuguese) mapOf(0 to "Desligado", 1 to "Ligado") else mapOf(0 to "Off", 1 to "On")
        275 -> (0..10).associateWith { "${it * 10}%" }
        else -> emptyMap()
    }
    private fun setting(field: Int, raw: Map<Int, Int>): Int? {
        if (field !in 272..275) return null
        val capability = raw[271]?.takeIf { it in 0..255 } ?: return null
        if (capability and (128 shr (field - 272)) == 0) return null
        return raw[field]?.takeIf { it in choices(field, false) }
    }
    fun command(profile: Int, field: Int, target: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (profile != PROFILE || setting(field, raw) == null || target !in choices(field, false)) return null
        return 145 to listOf(field - 271, target)
    }
    fun widgetValues(profile: Int, protocol: String, legacy: Map<Int, Int>, raw: Map<Int, Int>): Map<Int, Int> {
        if (profile != PROFILE || protocol != "golf_wc_2023") return legacy
        val mapping = mapOf(299 to 271, 300 to 272, 301 to 273, 302 to 274, 303 to 275, 312 to 294, 321 to 303)
        return (legacy - mapping.keys) + mapping.mapNotNull { (output, source) -> raw[source]?.let { output to it } }.toMap()
    }
    fun read(profile: Int, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> = buildList {
        if (profile != PROFILE) return@buildList
        fun label(en: String, pt: String) = if (portuguese) pt else en
        val names = mapOf(272 to label("Maximum charging current", "Corrente máxima de carga"),
            273 to label("Cabin conditioning temperature", "Temperatura de climatização"),
            274 to label("Climate control from battery", "Climatização pela bateria"),
            275 to label("Minimum battery charge", "Carga mínima da bateria"),
            293 to label("Driving potential", "Autonomia potencial"), 295 to label("Driving mileage", "Distância percorrida"),
            296 to label("Electric mileage", "Distância elétrica"), 297 to label("Electric/fuel mileage ratio", "Relação de distância elétrica/combustível"),
            303 to label("Battery charge", "Carga da bateria"), 294 to label("Energy flow", "Fluxo de energia"))
        for (field in 272..275) setting(field, raw)?.let { value ->
            val options = choices(field, portuguese)
            add(FytSyuReading("golf_wc_2023", field, setOf(271, field), options.getValue(value), options = options, label = names.getValue(field)))
        }
        fun row(field: Int, text: String, dependencies: Set<Int> = setOf(field)) {
            add(FytSyuReading("golf_wc_2023", field, dependencies, text, label = names.getValue(field)))
        }
        raw[177]?.takeIf { it in 0..1 }?.let { unit ->
            for (field in listOf(293, 295, 296)) raw[field]?.takeIf { it in 0..65534 }?.let {
                row(field, "$it ${if (unit == 0) "mi" else "km"}", setOf(field, 177))
            }
        }
        for (field in listOf(297, 303)) raw[field]?.takeIf { it in 0..100 }?.let { row(field, "$it%") }
        when (raw[294]) { 1 -> row(294, label("Charging", "Carregando")); 2 -> row(294, label("Discharging", "Descarregando")) }
    }
}
