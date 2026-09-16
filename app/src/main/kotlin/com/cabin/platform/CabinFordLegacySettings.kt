package com.cabin.platform

/** The earlier Ford RZC screen uses command families 1/4/5, not Escape/Transit's 10. */
internal object CabinFordLegacySettings {
    fun supports(profile: Int) = profile and 65535 == 334 && !CabinFordSettings.supports(profile)
    val languages = mapOf(2 to "English (UK)", 3 to "English (US)", 4 to "Deutsch", 5 to "Italiano",
        6 to "Français · 6", 7 to "Français · 7", 8 to "Español", 9 to "Español (México)",
        10 to "Türkçe", 11 to "Русский", 12 to "Nederlands", 14 to "Polski", 15 to "Čeština",
        18 to "Svenska", 19 to "Dansk", 20 to "Norsk", 21 to "Suomi", 22 to "Português",
        23 to "Português (Brasil)", 25 to "English", 26 to "한국어", 27 to "简体中文",
        28 to "繁體中文", 29 to "العربية", 31 to "ไทย")
    fun languageCommand(profile: Int, value: Int): Pair<Int, List<Int>>? =
        if (supports(profile) && value in languages) 11 to listOf(164, value, 0) else null
    private fun alternate(profile: Int) = profile in setOf(459086, 1179982, 1245518)
    private val common = setOf(28, 57, 58, 132, 180, 187, 188, 189)
    private val regular = setOf(38, 39, 40, 42, 43, 44, 45, 47, 60, 61, 62, 63, 90)
    private val alternateFields = (64..73).toSet()
    // Own hidden rows too, so generic fallback cannot restore controls hidden by the stock route.
    val fields = common + regular + alternateFields + 41
    private fun visible(profile: Int) = common + if (alternate(profile)) alternateFields else regular

    private fun options(profile: Int, field: Int, pt: Boolean): Map<Int, String> {
        if (field !in visible(profile)) return emptyMap()
        fun text(en: String, br: String) = if (pt) br else en
        val labels = when (field) {
            28 -> listOf("°C", "°F")
            42 -> listOf("km", "mi")
            44 -> listOf(text("1 flash", "1 piscada"), text("3 flashes", "3 piscadas"))
            57 -> return (1..7).associateWith { text("Color", "Cor") + " $it" }
            58 -> return (1..100).associateWith(Int::toString)
            64 -> listOf(text("Low", "Baixa"), text("Medium", "Média"), text("High", "Alta"))
            66 -> listOf("0 s", "15 s", "30 s", "60 s")
            70 -> listOf("5 min", "10 min", "15 min")
            72 -> listOf("5 min", "10 min")
            73 -> listOf("30 s", "1 min", "2 min", "3 min")
            180 -> listOf("kPa", "psi", "bar")
            else -> listOf(text("Off", "Desligado"), text("On", "Ligado"))
        }
        return labels.mapIndexed { index, value -> index to value }.toMap()
    }
    private fun current(profile: Int, field: Int, raw: Map<Int, Int>): Int? {
        if (field !in visible(profile)) return null
        val value = raw[field]?.takeIf { it in 0..255 } ?: return null
        if (field in setOf(57, 58) && value == 0) return value
        return value.takeIf { it in options(profile, field, false) }
    }
    fun command(profile: Int, field: Int, target: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!supports(profile) || current(profile, field, raw) == null || target !in options(profile, field, false)) return null
        if (field == 57 || field == 58) {
            val color = current(profile, 57, raw) ?: return null
            val level = current(profile, 58, raw) ?: return null
            return 4 to listOf(if (field == 57) target else color, if (field == 58) target else level)
        }
        val direct = mapOf(28 to 255, 180 to 254, 43 to 173, 47 to 165, 60 to 174, 62 to 175, 63 to 166)
        direct[field]?.let { return 1 to listOf(it, target) }
        if (field == 61) return 1 to listOf(171, target + 18)
        val toggles = mapOf(38 to (2 to 1), 39 to (5 to 6), 40 to (7 to 8), 42 to (14 to 15),
            44 to (3 to 4), 45 to (20 to 19), 90 to (22 to 21), 132 to (24 to 23),
            187 to (31 to 30), 188 to (33 to 32), 189 to (35 to 34))
        toggles[field]?.let { return 1 to listOf(163, if (target == 0) it.first else it.second) }
        val extended = mapOf(64 to 0, 65 to 1, 66 to 2, 67 to 3, 68 to 4, 69 to 8, 70 to 5, 71 to 6, 72 to 7, 73 to 9)
        return extended[field]?.let { 5 to listOf(it, target) }
    }
    fun read(profile: Int, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> {
        if (!supports(profile)) return emptyList()
        val names = mapOf(28 to ("Temperature unit" to "Unidade de temperatura"), 38 to ("Trailer system" to "Sistema de reboque"),
            39 to ("Information sound" to "Som de informações"), 40 to ("Warning sound" to "Som de avisos"),
            42 to ("Distance unit" to "Unidade de distância"), 43 to ("Enhanced parking" to "Estacionamento ampliado"),
            44 to ("Indicator flashes" to "Piscadas da seta"), 45 to ("Ambient lighting" to "Iluminação ambiente"),
            47 to ("Rain sensor" to "Sensor de chuva"), 57 to ("Ambient color" to "Cor ambiente"),
            58 to ("Ambient intensity" to "Intensidade ambiente"), 60 to ("Rear camera delay" to "Atraso da câmera traseira"),
            61 to ("Rear camera zoom" to "Zoom da câmera traseira"), 62 to ("Split rear view" to "Visão traseira dividida"),
            63 to ("Beeper" to "Aviso sonoro"), 64 to ("Light sensitivity" to "Sensibilidade da luz"),
            65 to ("Automatic unlock" to "Destravamento automático"), 66 to ("Welcome lighting" to "Iluminação de boas-vindas"),
            67 to ("Remote window control" to "Controle remoto dos vidros"), 68 to ("Wireless charging" to "Carga sem fio"),
            69 to ("Speed locking" to "Travamento por velocidade"), 70 to ("Interior light delay" to "Atraso da luz interna"),
            71 to ("Automatic mirror unfolding" to "Abertura automática dos retrovisores"),
            72 to ("Rear defrost duration" to "Duração do desembaçador traseiro"), 73 to ("Coming-home lighting" to "Iluminação ao chegar"),
            90 to ("Hill-start assist" to "Assistente de partida em rampa"), 132 to ("Instrument controls" to "Controles do painel"),
            180 to ("Pressure unit" to "Unidade de pressão"), 187 to ("Active City" to "Active City"),
            188 to ("Ambient light switch" to "Interruptor da luz ambiente"), 189 to ("Power-fold mirrors" to "Rebatimento elétrico dos retrovisores"))
        return visible(profile).mapNotNull { field ->
            val value = current(profile, field, raw) ?: return@mapNotNull null
            val choices = options(profile, field, portuguese)
            val name = names.getValue(field)
            FytSyuReading("ford_0334", field, setOf(field), choices[value] ?: "0",
                options = if (command(profile, field, choices.keys.first(), raw) != null) choices else emptyMap(),
                label = if (portuguese) name.second else name.first)
        }
    }
}
