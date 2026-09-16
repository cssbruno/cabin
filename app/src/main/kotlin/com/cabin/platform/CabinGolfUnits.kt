package com.cabin.platform

/** Golf7FunctionalUnitActi and Golf7Data, July 2023. Keys represent confirmed feedback values. */
internal object CabinGolfUnits {
    fun fields(profile: Int): Set<Int> = setOf(66, 67, 68, 69, 83, 84) + when (profile) {
        655377 -> setOf(276)
        655520 -> setOf(343)
        else -> emptySet()
    }
    private fun choices(protocol: String, field: Int): Map<Int, String> {
        val wc = protocol == "golf_wc_2023"
        val list = when (field) {
            83 -> if (wc) listOf("mi", "km") else listOf("km", "mi")
            84 -> if (wc) listOf("mph", "km/h") else listOf("km/h", "mph")
            66 -> if (wc) listOf("°F", "°C") else listOf("°C", "°F")
            67 -> if (wc) listOf("L", "gal (US)", "gal (UK)") else listOf("L", "gal (UK)", "gal (US)")
            68 -> if (wc) listOf("L/100 km", "km/L", "mpg (US)", "mpg (UK)") else listOf("mpg (UK)", "L/100 km", "mpg (US)", "km/L")
            69 -> if (wc) listOf("kPa", "bar", "psi") else listOf("bar", "psi", "kPa")
            276, 343 -> listOf("kWh/100 km", "km/kWh")
            else -> return emptyMap()
        }
        return list.withIndex().associate { it.index to it.value }
    }
    private fun value(profile: Int, protocol: String, field: Int, raw: Map<Int, Int>): Int? {
        if (!CabinGolfSettings.supports(protocol) || field !in fields(profile)) return null
        val encoded = raw[field]?.takeIf { it in 0..65535 } ?: return null
        val value = if (protocol == "golf_wc_2023" && field != 83) {
            if (encoded shr 8 == 0) return null
            encoded and 255
        } else encoded
        return value.takeIf { it in choices(protocol, field) }
    }
    fun command(profile: Int, protocol: String, field: Int, target: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (value(profile, protocol, field, raw) == null || target !in choices(protocol, field)) return null
        if (field == 343) return 160 to listOf(150, target)
        val command = when (field) { 83 -> 1; 84 -> 2; 66 -> 87; 67 -> 88; 68 -> 89; 69 -> 90; 276 -> 146; else -> return null }
        // WC's command value for these three units is the inverse of its feedback value.
        val wire = if (protocol == "golf_wc_2023" && field in setOf(83, 84, 66)) 1 - target else target
        return command to listOf(wire)
    }
    fun read(profile: Int, protocol: String, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> {
        val labels = if (portuguese) mapOf(83 to "Unidade de distância", 84 to "Unidade de velocidade", 66 to "Unidade de temperatura",
            67 to "Unidade de volume", 68 to "Unidade de consumo", 69 to "Unidade de pressão", 276 to "Unidade de consumo elétrico", 343 to "Unidade de consumo elétrico")
        else mapOf(83 to "Distance unit", 84 to "Speed unit", 66 to "Temperature unit", 67 to "Volume unit",
            68 to "Fuel consumption unit", 69 to "Pressure unit", 276 to "Electric consumption unit", 343 to "Electric consumption unit")
        return fields(profile).mapNotNull { field -> value(profile, protocol, field, raw)?.let { value ->
            val options = choices(protocol, field)
            FytSyuReading(protocol, field, setOf(field), options.getValue(value), options = options, label = labels.getValue(field))
        } }
    }
}
