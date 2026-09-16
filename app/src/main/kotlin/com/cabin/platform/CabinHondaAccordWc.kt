package com.cabin.platform

/** WC Accord IX, verified against i0 and o0/c1, including relative camera adjustments. */
internal object CabinHondaAccordWc {
    private val high = setOf(37, 131109)
    private val low = setOf(42, 59, 65578, 131114)
    private val newer = setOf(65578, 131114)
    fun languages(profile: Int) = if (profile in low) mapOf(1 to "English", 2 to "中文") else emptyMap()
    fun language(profile: Int, value: Int): Pair<Int, List<Int>>? = if (value in languages(profile)) 17 to listOf(value) else null
    fun supports(profile: Int) = profile in high || profile in low
    fun dialect(profile: Int, callback: String): String? {
        val name = when (profile) {
            in high -> "0037_WC2_Accord9"
            59 -> "0059_WC2_ACCORD9_Lo_ZYC785"
            in low -> "0042_WC2_ACCORD9_Lo"
            else -> return null
        }
        return "honda_accord_wc".takeIf { callback == "Lcom/syu/module/canbus/Callback_$name;" }
    }
    fun fields(profile: Int) = if (profile in high) setOf(1, 3, 4) else setOf(18, 24, 25, 26, 27, 28, 29, 30, 32, 33, 34, 64)
    private fun options(profile: Int, field: Int, pt: Boolean): Map<Int, String> {
        fun labels(en: List<String>, br: List<String>, start: Int = 0) = (if (pt) br else en).mapIndexed { i, s -> i + start to s }.toMap()
        if (profile in high) return when (field) {
            1 -> labels(listOf("Bright", "Dim", "Off"), listOf("Clara", "Escurecida", "Desligada"))
            3 -> labels(listOf("Blue", "Amber", "Red", "Violet"), listOf("Azul", "Âmbar", "Vermelho", "Violeta"), 1)
            4 -> labels(listOf("Wide", "Standard", "Downward"), listOf("Ampla", "Normal", "Para baixo"))
            else -> emptyMap()
        }
        if (profile !in low) return emptyMap()
        return when (field) {
            18 -> (0..10).associateWith { (it - 5).toString() }
            24, 29 -> labels(listOf("Off", "On"), listOf("Desligado", "Ligado"))
            25 -> mapOf(1 to "30 s", 2 to "60 s", 3 to "90 s")
            26 -> mapOf(0 to "0 s", 1 to "15 s", 2 to "30 s", 3 to "60 s")
            27 -> mapOf(1 to "15 s", 2 to "30 s", 3 to "60 s")
            28, 30 -> labels(listOf("On refuel", "Ignition off", "Manual"), listOf("Ao abastecer", "Ao desligar", "Manual"), 1)
            32, 33, 34 -> if (profile == 59) emptyMap() else (0..10).associateWith { (if (field == 32) it else it - 5).toString() }
            64 -> if (profile in newer) labels(listOf("Off", "On"), listOf("Desligado", "Ligado")) else emptyMap()
            else -> emptyMap()
        }
    }
    private val labels = mapOf(
        1 to ("Factory screen mode" to "Modo da tela original"), 3 to ("Factory screen color" to "Cor da tela original"),
        4 to ("Camera view" to "Visão da câmera"), 18 to ("Outside temperature adjustment" to "Ajuste da temperatura externa"),
        24 to ("Lock confirmation" to "Confirmação de travamento"), 25 to ("Automatic relock delay" to "Tempo para travar novamente"),
        26 to ("Headlight delay" to "Tempo dos faróis"), 27 to ("Interior light delay" to "Tempo da luz interna"),
        28 to ("Trip A reset" to "Zerar viagem A"), 29 to ("Economy backlight" to "Iluminação de economia"),
        30 to ("Trip B reset" to "Zerar viagem B"), 32 to ("Camera brightness" to "Brilho da câmera"),
        33 to ("Camera color" to "Cor da câmera"), 34 to ("Camera contrast" to "Contraste da câmera"),
        64 to ("Reverse video" to "Vídeo da ré"))
    fun command(profile: Int, field: Int, value: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        val options = options(profile, field, false)
        if (value !in options || raw[field] !in options) return null
        val command = if (profile in high) mapOf(1 to 0, 3 to 1, 4 to 2)[field]
            else mapOf(24 to 2, 25 to 1, 26 to 4, 27 to 3, 18 to 5, 28 to 6, 29 to 8, 30 to 7, 32 to 11, 33 to 12, 34 to 13, 64 to 16)[field]
        command ?: return null
        if (profile in low && field in 32..34) {
            val current = raw.getValue(field)
            // Service accepts a single step, not an absolute value. Wait for its callback before another step.
            if (value == current + 1) return command to listOf(-1)
            if (value == current - 1) return command to listOf(-2)
            return null
        }
        return command to listOf(value)
    }
    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> = fields(profile).mapNotNull { field ->
        val options = options(profile, field, pt)
        val current = raw[field] ?: return@mapNotNull null
        val text = options[current] ?: return@mapNotNull null
        val choices = if (profile in low && field in 32..34) options.filterKeys { it == current - 1 || it == current + 1 } else options
        val label = labels.getValue(field)
        FytSyuReading("honda_accord_wc", field, setOf(field), text, options = choices, label = if (pt) label.second else label.first)
    }
    fun actions(profile: Int) = if (profile in newer) setOf(FytVehicleAction.CALIBRATE_TIRE_PRESSURE) else emptySet()
    fun action(profile: Int, action: FytVehicleAction): Pair<Int, List<Int>>? =
        if (action in actions(profile)) 14 to listOf(1) else null
}
