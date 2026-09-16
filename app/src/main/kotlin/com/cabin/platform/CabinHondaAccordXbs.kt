package com.cabin.platform

/** XBS Accord IX r8/wd: same setting meanings as XP, different fields and screen commands. */
internal object CabinHondaAccordXbs {
    private val low = ((18..28).toSet() - setOf(22, 27, 28)).associateWith { it } + (29..32).associateWith { it } + (34 to 34)
    private val high = mapOf(51 to 19, 52 to 18, 53 to 20, 54 to 22, 55 to 23, 56 to 24,
        57 to 25, 58 to 26, 59 to 28, 63 to 27, 80 to 30, 81 to 31, 82 to 32)
    private val baseCommands = mapOf(18 to 3, 19 to 2, 20 to 0, 21 to 1, 22 to 6, 23 to 5, 24 to 4,
        25 to 10, 26 to 11, 27 to 12, 28 to 13, 29 to 22, 30 to 23, 31 to 24, 32 to 25, 34 to 21)
    private val extraCommands = mapOf(60 to 34, 61 to 26, 62 to 27, 64 to 28, 66 to 31, 67 to 33, 68 to 29, 69 to 30, 70 to 32, 83 to 35)
    fun supports(profile: Int) = profile == 262 || profile == 410
    fun dialect(profile: Int, callback: String): String? {
        val name = when (profile) { 262 -> "0262_XBS_XP1_ACCORD9_Lo"; 410 -> "0410_XBS_XP1_ACCORD9"; else -> return null }
        return "honda_accord_xbs".takeIf { callback == "Lcom/syu/module/canbus/Callback_$name;" }
    }
    private fun mapping(profile: Int) = if (profile == 262) low else if (profile == 410) high else emptyMap()
    fun fields(profile: Int): Set<Int> = mapping(profile).keys + if (profile == 410) extraCommands.keys + 79 else emptySet()
    private fun options(profile: Int, field: Int, pt: Boolean): Map<Int, String> {
        fun labels(en: List<String>, br: List<String>) = (if (pt) br else en).mapIndexed { i, s -> i to s }.toMap()
        if (profile == 262 && field == 34) return labels(listOf("Blue", "Amber", "Red", "Green", "Violet"), listOf("Azul", "Âmbar", "Vermelho", "Verde", "Violeta"))
        mapping(profile)[field]?.let { return CabinHondaAccord.options(it, pt) }
        if (profile != 410 || field !in extraCommands) return emptyMap()
        return when (field) {
            68 -> labels(listOf("Near", "Medium", "Far"), listOf("Perto", "Médio", "Longe"))
            70 -> labels(listOf("Standard", "Wide", "Warnings only"), listOf("Normal", "Amplo", "Somente avisos"))
            else -> labels(listOf("Off", "On"), listOf("Desligado", "Ligado"))
        }
    }
    private val labels = mapOf(60 to ("Remote start" to "Partida remota"), 61 to ("Economy backlight" to "Iluminação de economia"),
        62 to ("Smart key start guidance" to "Orientação de partida com chave"), 64 to ("Idle stop" to "Parada automática do motor"),
        66 to ("ACC warning tone" to "Aviso sonoro do ACC"), 67 to ("LKAS suspension tone" to "Aviso de pausa do LKAS"),
        68 to ("Forward collision warning distance" to "Distância do alerta de colisão"), 69 to ("Traffic sign recognition" to "Reconhecimento de placas"),
        70 to ("Lane departure assistance" to "Assistência de saída de faixa"), 83 to ("Camera on turn signal" to "Câmera com a seta"))
    fun command(profile: Int, field: Int, value: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        val options = options(profile, field, false)
        if (value !in options || raw[field] !in options) return null
        val command = mapping(profile)[field]?.let(baseCommands::get) ?: extraCommands[field] ?: return null
        return command to listOf(value)
    }
    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> = fields(profile).mapNotNull { field ->
        val options = options(profile, field, pt)
        val text = options[raw[field]] ?: return@mapNotNull null
        val label = mapping(profile)[field]?.let(CabinHondaAccord.labels::get) ?: labels.getValue(field)
        FytSyuReading("honda_accord_xbs", field, setOf(field), text, options = options, label = if (pt) label.second else label.first)
    }
    fun requests(profile: Int, published: Set<Int>): List<Pair<Int, List<Int>>> = buildList {
        if (!supports(profile)) return@buildList
        if (setOf(1, 2, 7).all { it in published }) add(100 to listOf(if (profile == 262) 5 else 8, 1))
        if ((12..17).all { it in published }) add(100 to listOf(if (profile == 262) 5 else 8, 2))
        if ((if (profile == 262) low.keys.filter { it in 18..28 }.toSet() else (51..64).toSet()).all { it in published })
            add(100 to listOf(if (profile == 262) 4 else 10, 0))
        if ((if (profile == 262) setOf(29, 30, 31, 32, 34) else (79..83).toSet()).all { it in published }) add(100 to listOf(11, 0))
    }
    fun actions(profile: Int) = if (!supports(profile)) emptySet() else buildSet {
        add(FytVehicleAction.RESET_HONDA_TRIP_HISTORY)
        add(FytVehicleAction.RESET_VEHICLE_SETTINGS)
        add(FytVehicleAction.CALIBRATE_TIRE_PRESSURE)
        // wd has no command 14, although the stock activity displays that button.
        if (profile == 262) add(FytVehicleAction.RESET_SERVICE_INTERVAL)
    }
    fun action(profile: Int, action: FytVehicleAction): Pair<Int, List<Int>>? {
        if (action !in actions(profile)) return null
        return when (action) {
            FytVehicleAction.RESET_HONDA_TRIP_HISTORY -> 101 to listOf(3)
            FytVehicleAction.RESET_SERVICE_INTERVAL -> 14 to listOf(0)
            FytVehicleAction.RESET_VEHICLE_SETTINGS -> 15 to listOf(0)
            FytVehicleAction.CALIBRATE_TIRE_PRESSURE -> 17 to listOf(0)
            else -> null
        }
    }
}
