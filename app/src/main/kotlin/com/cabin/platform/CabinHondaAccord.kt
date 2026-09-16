package com.cabin.platform

/** XP Accord IX: n0/t1 service commands and XPAccord9 settings/screen client. */
internal object CabinHondaAccord {
    private val low = setOf(41, 65577)
    private val high = setOf(77, 65613, 131149, 196685, 262221)
    fun supports(profile: Int) = profile in low || profile in high
    fun dialect(profile: Int, callback: String): String? {
        val name = when (profile) {
            in low -> "0041_XP1_ACCORD9_Lo"
            in high -> "0077_XP1_ACCORD9_H"
            else -> return null
        }
        return "honda_accord_xp".takeIf { callback == "Lcom/syu/module/canbus/Callback_$name;" }
    }
    // Own even hidden fields: vendor enum metadata contains unrelated lock/warning labels.
    val fields = (18..32).toSet() + setOf(34, 60)
    private val commands = mapOf(20 to 0, 21 to 1, 19 to 2, 18 to 3, 24 to 4, 23 to 5, 22 to 6,
        25 to 10, 26 to 11, 27 to 12, 28 to 13, 29 to 66, 30 to 67, 31 to 68, 32 to 69, 34 to 65, 60 to 102)
    private fun visible(profile: Int, field: Int): Boolean = supports(profile) && when (field) {
        22, 27, 28, 29, 60 -> profile in high
        30, 31, 32, 34 -> profile in low
        else -> field in commands
    }
    fun options(field: Int, pt: Boolean): Map<Int, String> {
        fun labels(en: List<String>, br: List<String>) = (if (pt) br else en).mapIndexed { i, s -> i to s }.toMap()
        return when (field) {
            18, 19 -> labels(listOf("On refuel", "Ignition off", "Manual"), listOf("Ao abastecer", "Ao desligar", "Manual"))
            20 -> (0..10).associateWith { (it - 5).toString() }
            21, 25, 28, 60 -> labels(listOf("Off", "On"), listOf("Desligado", "Ligado"))
            22 -> labels(listOf("Minimum", "Low", "Medium", "High", "Maximum"), listOf("Mínima", "Baixa", "Média", "Alta", "Máxima"))
            23 -> mapOf(0 to "0 s", 1 to "15 s", 2 to "30 s", 3 to "60 s")
            24 -> mapOf(0 to "15 s", 1 to "30 s", 2 to "60 s")
            26 -> mapOf(0 to "30 s", 1 to "60 s", 2 to "90 s")
            27 -> labels(listOf("Low", "High"), listOf("Baixo", "Alto"))
            29 -> labels(listOf("Normal", "Dim", "Off"), listOf("Normal", "Escurecida", "Desligada"))
            30, 31, 32 -> (0..10).associateWith { (it - 5).toString() }
            34 -> labels(listOf("Amber", "Red", "Violet", "Blue"), listOf("Âmbar", "Vermelho", "Violeta", "Azul"))
            else -> emptyMap()
        }
    }
    val labels = mapOf(
        18 to ("Trip B reset" to "Zerar viagem B"), 19 to ("Trip A reset" to "Zerar viagem A"),
        20 to ("Outside temperature adjustment" to "Ajuste da temperatura externa"),
        21 to ("Economy backlight" to "Iluminação de economia"), 22 to ("Automatic light sensitivity" to "Sensibilidade dos faróis"),
        23 to ("Headlight delay" to "Tempo dos faróis"), 24 to ("Interior light delay" to "Tempo da luz interna"),
        25 to ("Lock confirmation" to "Confirmação de travamento"), 26 to ("Automatic relock delay" to "Tempo para travar novamente"),
        27 to ("Keyless beep volume" to "Volume do aviso da chave"), 28 to ("Keyless beep" to "Aviso da chave"),
        29 to ("Factory screen mode" to "Modo da tela original"), 30 to ("Factory screen brightness" to "Brilho da tela original"),
        31 to ("Factory screen contrast" to "Contraste da tela original"), 32 to ("Factory screen black level" to "Nível de preto da tela original"),
        34 to ("Factory screen color" to "Cor da tela original"), 60 to ("Camera on right turn" to "Câmera ao virar à direita"))
    fun command(profile: Int, field: Int, value: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!visible(profile, field) || value !in options(field, false) || raw[field] !in options(field, false)) return null
        return commands.getValue(field) to listOf(value)
    }
    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> = commands.keys.mapNotNull { field ->
        if (!visible(profile, field)) return@mapNotNull null
        val options = options(field, pt)
        val text = options[raw[field]] ?: return@mapNotNull null
        val label = labels.getValue(field)
        FytSyuReading("honda_accord_xp", field, setOf(field), text, options = options, label = if (pt) label.second else label.first)
    }
    fun requests(published: Set<Int>) = buildList {
        if ((18..28).all { it in published }) add(100 to listOf(50, 0))
        if (setOf(29, 30, 31, 32, 34).all { it in published }) add(100 to listOf(211, 0))
    }
    fun actions(profile: Int) = if (!supports(profile)) emptySet() else buildSet {
        add(FytVehicleAction.RESET_VEHICLE_SETTINGS)
        add(FytVehicleAction.CALIBRATE_TIRE_PRESSURE)
        if (profile in high) add(FytVehicleAction.RESET_SERVICE_INTERVAL)
    }
    fun action(profile: Int, action: FytVehicleAction): Pair<Int, List<Int>>? {
        if (action !in actions(profile)) return null
        val command = when (action) {
            FytVehicleAction.RESET_SERVICE_INTERVAL -> 14
            FytVehicleAction.RESET_VEHICLE_SETTINGS -> 15
            FytVehicleAction.CALIBRATE_TIRE_PRESSURE -> 17
            else -> return null
        }
        return command to listOf(0)
    }
}
