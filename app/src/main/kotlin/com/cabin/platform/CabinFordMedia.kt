package com.cabin.platform

/** Original Ford radio/CD callbacks, not Android audio or CarPlay controls. */
internal object CabinFordMedia {
    fun supports(profile: Int) = profile in setOf(1048910, 1900878, 2031950)
    private fun radio(profile: Int) = profile in setOf(1048910, 2031950)
    val fields = (113..129).toSet() + 131
    private val bands = mapOf(1 to "FM1", 2 to "FM2", 3 to "FM3", 16 to "AM1", 17 to "AM2")
    fun releaseFrame(profile: Int, field: Int): Pair<Int, List<Int>>? =
        if (supports(profile) && field in setOf(114, 115, 122, 126, 127, 128, 129, 131)) 9 to listOf(169, 0) else null

    fun command(profile: Int, field: Int, value: Int, current: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!supports(profile)) return null
        val radioField = field in setOf(114, 115, 131)
        if (radioField && (!radio(profile) || current[113] != 1) || !radioField && current[113] != 2) return null
        val key = when (field) {
            114 -> {
                if (current[114] !in bands || value !in bands) return null
                if (value < 16) value else value - 12
            }
            115 -> {
                if (current[114] !in bands || current[115] !in 1..65535 || value !in 6..9) return null
                value
            }
            131 -> {
                if (current[131] !in 0..6 || value !in 1..6) return null
                value + 9
            }
            122 -> {
                if (current[122] !in 0..65535 || value !in setOf(33, 34, 42, 43)) return null
                value
            }
            126 -> {
                if (current[126] !in setOf(1, 2, 3, 255) || value != 44) return null
                value
            }
            127 -> {
                if (current[127] !in 0..255 || value !in 0..1) return null
                if (value == 1) 36 else 35
            }
            128, 129 -> {
                if (current[field] !in 0..1 || value !in 0..1) return null
                if (field == 128) (if (value == 1) 39 else 40) else (if (value == 1) 37 else 38)
            }
            else -> return null
        }
        return 9 to listOf(169, key)
    }

    fun read(profile: Int, raw: Map<Int, Int>, payloads: Map<Int, FytRawSample>, pt: Boolean): List<FytSyuReading> = buildList {
        if (!supports(profile)) return@buildList
        fun label(en: String, br: String) = if (pt) br else en
        fun row(id: Int, name: String, text: String, options: Map<Int, String> = emptyMap(), deps: Set<Int> = setOf(id, 113)) {
            add(FytSyuReading("ford_0334", id, deps, text, options = options, label = name))
        }
        if (raw[113] == 1 && radio(profile)) {
            raw[114]?.let(bands::get)?.let { row(114, label("Radio band", "Faixa do rádio"), it, bands) }
            val band = raw[114]
            if (band in bands) raw[115]?.takeIf { it in 1..65535 }?.let { frequency ->
                val text = if (band!! < 16) "${frequency / 100}.${(frequency % 100).toString().padStart(2, '0')} MHz" else "$frequency kHz"
                row(115, label("Frequency", "Frequência"), text, mapOf(
                    6 to label("Next station", "Próxima estação"), 7 to label("Previous station", "Estação anterior"),
                    8 to label("Tune up", "Aumentar frequência"), 9 to label("Tune down", "Diminuir frequência")), setOf(113, 114, 115))
            }
            raw[131]?.takeIf { it in 0..6 }?.let { row(131, label("Radio preset", "Memória do rádio"),
                if (it == 0) "—" else it.toString(), (1..6).associateWith(Int::toString)) }
            for (id in 116..121) payloads[id]?.strings?.singleOrNull()?.takeIf { it.isNotBlank() && it.length <= 128 }?.let {
                row(id, label("Preset", "Memória") + " ${id - 115}", it)
            }
        }
        if (raw[113] == 2) {
            val track = raw[122]?.takeIf { it in 0..65535 }
            val total = raw[123]?.takeIf { it in 0..65535 }
            if (track != null && total != null) row(122, label("CD track", "Faixa do CD"), "$track / $total",
                mapOf(33 to label("Previous", "Anterior"), 34 to label("Next", "Próxima"),
                    42 to label("Rewind · 0.25 s", "Retroceder · 0,25 s"), 43 to label("Forward · 0.25 s", "Avançar · 0,25 s")), setOf(113, 122, 123))
            for (id in 124..125) raw[id]?.let(::time)?.let { row(id, if (id == 124) label("CD elapsed time", "Tempo decorrido do CD") else label("CD duration", "Duração do CD"), it) }
            val statuses = mapOf(1 to label("No disc", "Sem disco"), 2 to label("Reading", "Lendo"), 3 to label("Ejecting", "Ejetando"), 255 to label("Error", "Erro"))
            raw[126]?.let(statuses::get)?.let { row(126, label("CD status", "Estado do CD"), it, mapOf(44 to label("Eject", "Ejetar"))) }
            raw[127]?.takeIf { it in 0..255 }?.let { row(127, label("CD playback", "Reprodução do CD"),
                if (it == 1) label("Playing", "Reproduzindo") else label("Paused", "Pausado"),
                mapOf(0 to label("Pause", "Pausar"), 1 to label("Play", "Reproduzir"))) }
            for (id in 128..129) raw[id]?.takeIf { it in 0..1 }?.let {
                val options = mapOf(0 to label("Off", "Desligado"), 1 to label("On", "Ligado"))
                row(id, if (id == 128) label("CD repeat", "Repetir CD") else label("CD shuffle", "CD aleatório"), options.getValue(it), options)
            }
        }
    }

    private fun time(packed: Int): String? {
        if (packed !in 0..0xffffff) return null
        val hour = packed shr 16
        val minute = packed shr 8 and 255
        val second = packed and 255
        if (minute > 59 || second > 59) return null
        return listOf(hour, minute, second).joinToString(":") { it.toString().padStart(2, '0') }
    }
}
