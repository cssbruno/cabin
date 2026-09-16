package com.cabin.platform

/** LZ Spirior/Civic instruments and XC Acura amplifier, independent wire contracts. */
internal object CabinHondaSpecialized {
    private val acura = setOf(12452293, 12911044, 12976580, 13042116)
    private val civic = setOf(15729093, 15794629)
    private val selectors = mapOf(81 to 33, 82 to 34, 83 to 35, 84 to 36, 85 to 37, 87 to 38, 88 to 48, 89 to 49)
    fun dialect(profile: Int, callback: String): String? {
        val name = when (profile) {
            in acura -> "0452_XC_Honda_Acura"
            in civic -> "0453_LZ_Honda_06Civic"
            197033 -> "0425_LuZhen_Spirior"
            else -> return null
        }
        return "honda_specialized".takeIf { callback == "Lcom/syu/module/canbus/Callback_$name;" }
    }
    fun fields(profile: Int): Set<Int> = when (profile) {
        in acura -> selectors.keys + 86
        in civic -> setOf(81)
        197033 -> (7..16).toSet()
        else -> emptySet()
    }
    fun motionFields(profile: Int) = if (profile == 197033) setOf(7, 8) else emptySet()
    fun nonMotionFields(profile: Int) = if (profile in acura) setOf(89) else emptySet()
    fun motion(profile: Int, raw: Map<Int, Int>): Map<Int, Int> = buildMap {
        if (profile != 197033) return@buildMap
        raw[7]?.takeIf { it in 0..4000 }?.let { put(89, it / 10) }
        raw[8]?.takeIf { it in 0..7000 }?.let { put(90, it) }
    }
    fun requests(profile: Int, published: Set<Int>): List<Pair<Int, List<Int>>> =
        if (profile in acura && selectors.keys.all { it in published }) listOf(1 to listOf(115)) else emptyList()
    private fun validAmp(field: Int, value: Int) = if (field == 87) value in 0..7 else value in -128..127
    fun command(profile: Int, field: Int, value: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (profile !in acura || field !in selectors) return null
        val current = raw[field]?.takeIf { validAmp(field, it) } ?: return null
        if (value !in setOf(33, 49)) return null
        // Commands are increase/decrease operations, not invented absolute amplifier levels.
        if (value == 33 && current == (if (field == 87) 7 else 127)) return null
        if (value == 49 && current == (if (field == 87) 0 else -128)) return null
        return 0 to listOf(selectors.getValue(field), value)
    }
    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> = buildList {
        fun label(en: String, br: String) = if (pt) br else en
        fun row(field: Int, text: String, name: String, choices: Map<Int, String> = emptyMap()) {
            add(FytSyuReading("honda_specialized", field, setOf(field), text, options = choices, label = name))
        }
        if (profile in acura) {
            val names = mapOf(81 to label("Factory volume", "Volume original"), 82 to label("Bass", "Graves"),
                83 to label("Treble", "Agudos"), 84 to label("Balance", "Balanço"), 85 to "Fader",
                87 to label("Speed compensated volume", "Volume conforme a velocidade"),
                88 to label("Center speaker", "Alto-falante central"), 89 to "Subwoofer")
            for ((field, name) in names) raw[field]?.takeIf { validAmp(field, it) }?.let {
                val choices = mapOf(49 to label("Decrease", "Diminuir"), 33 to label("Increase", "Aumentar"))
                    .filterKeys { command(profile, field, it, raw) != null }
                row(field, it.toString(), name, choices)
            }
        }
        if (profile in civic) raw[81]?.takeIf { it in 0..0xffffff }?.let { row(81, "$it km", label("Odometer", "Odômetro")) }
        if (profile == 197033) {
            raw[7]?.takeIf { it in 0..4000 }?.let { row(7, "${it / 10}.${it % 10} km/h", label("Speed", "Velocidade")) }
            raw[8]?.takeIf { it in 0..7000 }?.let { row(8, "$it RPM", label("Engine speed", "Rotação do motor")) }
            raw[9]?.takeIf { it in 0..0xffffff }?.let { row(9, "$it km", label("Odometer", "Odômetro")) }
            val lights = listOf(label("High beam", "Farol alto"), label("Low beam", "Farol baixo"),
                label("Front fog lights", "Faróis de neblina dianteiros"), label("Rear fog lights", "Faróis de neblina traseiros"),
                label("Brake lights", "Luzes de freio"), label("Left turn signal", "Seta esquerda"), label("Right turn signal", "Seta direita"))
            lights.forEachIndexed { index, name -> raw[10 + index]?.takeIf { it in 0..1 }?.let {
                row(10 + index, if (it == 1) label("On", "Ligado") else label("Off", "Desligado"), name)
            } }
        }
    }
}
