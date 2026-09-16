package com.cabin.platform

/** RZC 17CRV settings contract; not the BNR settings with overlapping selectors. */
internal object CabinHondaRzcSettings {
    private data class Switch(val selector: Int, val english: String, val portuguese: String)
    private val switches = mapOf(
        109 to Switch(50, "Reverse tone", "Som de ré"),
        151 to Switch(39, "Traffic sign icons", "Ícones de placas de trânsito"),
        152 to Switch(40, "Heads-up warning", "Aviso de atenção"),
        153 to Switch(41, "Seat position memory linkage", "Vincular memória da posição do banco"),
        154 to Switch(42, "Seat belt pretensioner mode", "Modo do pré-tensionador do cinto"),
        155 to Switch(43, "Static guidelines", "Linhas-guia fixas"),
        156 to Switch(44, "Dynamic guidelines", "Linhas-guia dinâmicas"),
        157 to Switch(45, "Camera after reversing", "Câmera após a marcha à ré"),
        159 to Switch(47, "Rear view reminders", "Avisos da visão traseira"),
        161 to Switch(49, "Rear multifunction system", "Sistema multifuncional traseiro"),
        166 to Switch(51, "Automatic tailgate opening", "Abertura automática do porta-malas"),
        173 to Switch(52, "Seat entry and exit assistance", "Auxílio do banco ao entrar e sair"),
        175 to Switch(74, "Rear seat reminder", "Aviso do banco traseiro"),
        176 to Switch(70, "Tailgate sensor", "Sensor do porta-malas"),
        178 to Switch(72, "Turn-by-turn guidance", "Orientação de navegação no painel"),
        190 to Switch(81, "Remote window control", "Controle remoto dos vidros"),
        191 to Switch(75, "Lockout prevention", "Prevenção de travamento com a chave dentro"),
        192 to Switch(76, "Automatic high beam", "Farol alto automático"),
        194 to Switch(77, "Traffic sign recognition", "Reconhecimento de placas de trânsito"),
        195 to Switch(78, "Traffic sign recognition warning", "Aviso de reconhecimento de placas"),
        158 to Switch(46, "Parking space width", "Largura da vaga"),
        177 to Switch(71, "Mirror folding", "Rebatimento dos retrovisores"),
        179 to Switch(73, "Straight-line assistance activation", "Ativação do auxílio de direção em linha reta"),
        193 to Switch(79, "Overspeed warning offset", "Margem do aviso de velocidade"),
        197 to Switch(82, "Blind-spot warning", "Aviso de ponto cego"),
        196 to Switch(97, "Reverse camera delay", "Atraso da câmera de ré"),
    )
    private val enumFields = setOf(158, 177, 179, 193, 197)
    private fun options(field: Int, pt: Boolean): Map<Int, String> {
        fun label(en: String, translated: String) = if (pt) translated else en
        val values = when (field) {
            158 -> listOf(label("Narrow", "Estreita"), label("Wide", "Larga"))
            177 -> listOf(label("Manual", "Manual"), label("Automatic", "Automático"))
            179 -> listOf(label("With cruise control", "Com o controle de cruzeiro"), label("Within the specified speed range", "Na faixa de velocidade definida"))
            193 -> (0..3).map { "+${it * 5} km/h" }
            197 -> listOf(label("Visual warning", "Aviso visual"), label("Visual and audible warning", "Aviso visual e sonoro"))
            else -> listOf(label("Off", "Desligado"), label("On", "Ligado"))
        }
        return values.withIndex().associate { it.index to it.value }
    }
    val fields: Set<Int> = switches.keys
    fun supports(profile: Int) = CabinHondaTrip.supportsTripB(profile)

    fun command(profile: Int, field: Int, value: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!supports(profile) || value !in options(field, false) || raw[field] !in options(field, false)) return null
        return switches[field]?.let { 105 to listOf(it.selector, value) }
    }

    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> {
        if (!supports(profile)) return emptyList()
        return switches.mapNotNull { (field, setting) ->
            val choices = options(field, pt)
            val value = raw[field]?.takeIf { it in choices } ?: return@mapNotNull null
            FytSyuReading("honda_rzc_settings_2023", field, setOf(field), choices[value], if (field in enumFields) null else value == 1,
                choices, if (pt) setting.portuguese else setting.english)
        }
    }
}
