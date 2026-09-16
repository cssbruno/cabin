package com.cabin.platform

/** Factory displays use typed callbacks: y5 CR-V arrays and bk Elysion strings. */
internal object CabinHondaFactoryMedia {
    private val elysion = setOf(197051, 7078331)
    fun dialect(profile: Int, callback: String): String? {
        val name = when (profile) {
            188 -> "0188_XBS_XP1_CRV2012"
            in elysion -> "0443_WC2_12ELYSION"
            else -> return null
        }
        return "honda_factory_media".takeIf { callback == "Lcom/syu/module/canbus/Callback_$name;" }
    }
    fun hasTrip(profile: Int) = profile in elysion
    fun fields(profile: Int): Set<Int> = when (profile) {
        188 -> (1..6).toSet()
        in elysion -> (30..46).toSet()
        else -> emptySet()
    }
    private fun transport(pt: Boolean): Map<Int, String> = if (pt)
        mapOf(1 to "Reproduzir", 2 to "Pausar", 3 to "Retroceder", 4 to "Avançar", 9 to "Trocar pasta", 11 to "Alternar aleatório")
        else mapOf(1 to "Play", 2 to "Pause", 3 to "Rewind", 4 to "Forward", 9 to "Change folder", 11 to "Cycle shuffle")
    fun command(profile: Int, field: Int, value: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (profile != 188 || field != 1 || raw[1] !in 0..7 || value !in transport(false)) return null
        // y5.cmd(1,[10]) indexes past a four-element array on its fourth call (confirmed in smali).
        // Do not expose that broken repeat-cycle operation. The service releases transport keys itself.
        return 1 to listOf(value)
    }
    fun read(profile: Int, raw: Map<Int, Int>, payloads: Map<Int, FytRawSample>, pt: Boolean): List<FytSyuReading> = buildList {
        fun l(en: String, br: String) = if (pt) br else en
        fun row(id: Int, text: String, name: String, choices: Map<Int, String> = emptyMap(), deps: Set<Int> = setOf(id)) {
            add(FytSyuReading("honda_factory_media", id, deps, text, options = choices, label = name))
        }
        if (profile == 188) {
            val states = mapOf(0 to l("Stopped", "Parado"), 1 to l("Playing", "Reproduzindo"), 2 to l("Paused", "Pausado"),
                3 to l("Stopped", "Parado"), 4 to l("Stopped", "Parado"), 5 to l("Scanning tracks", "Buscando faixas"),
                6 to l("Scanning folders", "Buscando pastas"), 7 to l("Loading", "Carregando"))
            raw[1]?.let(states::get)?.let { row(1, it, l("Factory playback", "Reprodução original"), transport(pt)) }
            raw[2]?.let(mapOf(0 to "USB", -128 to "iPod", 1 to "Bluetooth")::get)?.let { row(2, it, l("Media source", "Fonte de mídia")) }
            val modes = listOf(l("Normal", "Normal"), l("Repeat track", "Repetir faixa"), l("Repeat folder", "Repetir pasta"),
                l("Shuffle folder", "Aleatório na pasta"), l("Shuffle all", "Aleatório em todas"))
            raw[3]?.takeIf { it in modes.indices }?.let { row(3, modes[it], l("Playback mode", "Modo de reprodução")) }
            payloads[4]?.integers?.takeIf { it.size == 2 && it[0] in 0..5999 && it[1] in 0..59 }?.let {
                row(4, "%02d:%02d:%02d".format(java.util.Locale.ROOT, it[0] / 60, it[0] % 60, it[1]), l("Playback time", "Tempo de reprodução"))
            }
            payloads[5]?.integers?.takeIf { it.size == 2 && it.all { value -> value in 0..9999 } }?.let {
                row(5, "${it[0]} / ${it[1]}", l("Track", "Faixa"))
            }
            raw[6]?.takeIf { it in 0..100 }?.let { row(6, "$it%", l("Playback progress", "Progresso da reprodução")) }
        }
        if (profile in elysion) {
            for (field in 33..36) payloads[field]?.strings?.singleOrNull()?.takeIf { it.length <= 256 }?.trim { it <= ' ' }?.takeIf { it.isNotEmpty() }?.let {
                val selected = raw[32] == 1 && raw[30] == field - 32
                row(field, (if (selected) "▶ " else "") + it, l("Factory display", "Tela original") + " ${field - 32}",
                    deps = if (selected) setOf(field, 30, 32) else setOf(field))
            }
            val flags = mapOf(37 to l("Automatic station selection", "Seleção automática de estação"), 38 to l("Stereo", "Estéreo"),
                39 to l("Station scan", "Busca de estações"), 41 to "USB", 43 to "Bluetooth")
            for ((field, name) in flags) raw[field]?.takeIf { it in 0..1 }?.let {
                row(field, if (it == 1) l("On", "Ligado") else l("Off", "Desligado"), name)
            }
            val cd = listOf(l("Normal", "Normal"), l("Repeat track", "Repetir faixa"), l("Shuffle", "Aleatório"), l("Scan", "Busca"))
            val usb = listOf(l("Normal", "Normal"), l("Repeat track", "Repetir faixa"), l("Repeat folder", "Repetir pasta"),
                l("Shuffle folder", "Aleatório na pasta"), l("Shuffle all", "Aleatório em todas"), l("Scan all", "Buscar em todas"), l("Scan folder", "Buscar na pasta"))
            raw[40]?.takeIf { it in cd.indices }?.let { row(40, cd[it], l("Disc playback mode", "Modo de reprodução do disco")) }
            raw[42]?.takeIf { it in usb.indices }?.let { row(42, usb[it], l("USB playback mode", "Modo de reprodução USB")) }
            raw[44]?.takeIf { it in 1..5 }?.let { row(44, "$it/5", l("Phone signal", "Sinal do telefone")) }
            raw[45]?.takeIf { it in 1..5 }?.let { row(45, "$it/5", l("Phone battery", "Bateria do telefone")) }
            raw[46]?.takeIf { it in 0..255 }?.let { row(46, it.toString(), l("Factory volume", "Volume original")) }
        }
    }
}
