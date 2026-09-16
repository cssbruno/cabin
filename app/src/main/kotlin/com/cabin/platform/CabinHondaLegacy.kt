package com.cabin.platform

/** Civic/CR-V factory USB/iPod, verified against w/t0/j1/s1 rather than shared screen IDs. */
internal object CabinHondaLegacy {
    private val xp = setOf(24, 47, 65560, 65583, 131119, 196655)
    fun supports(profile: Int) = profile in xp || profile in setOf(67, 76)
    fun compass(profile: Int) = profile in xp
    fun dialect(profile: Int, callback: String): String? {
        val expected = when (profile) {
            24, 65560 -> "Callback_0024_XP1_SIYU2012"
            47, 65583, 131119, 196655 -> "Callback_0047_XP1_CRV2012"
            67 -> "Callback_0067_WC3_SiYu"
            76 -> "Callback_0076_WC3_CRV"
            else -> return null
        }
        return "honda_legacy".takeIf { callback == "Lcom/syu/module/canbus/$expected;" }
    }
    fun fields(profile: Int): Set<Int> = when {
        profile in xp -> (0..6).toSet() + 8
        profile == 67 -> (0..7).toSet()
        profile == 76 -> (11..17).toSet()
        else -> emptySet()
    }
    fun motion(profile: Int, raw: Map<Int, Int>): Map<Int, Int> = if (profile == 67)
        raw[0]?.takeIf { it in 0..255 }?.let { mapOf(89 to it) }.orEmpty() else emptyMap()
    private fun statusField(profile: Int) = if (profile in xp) 0 else if (profile == 67) 7 else 17
    private fun states(profile: Int, pt: Boolean): Map<Int, String> {
        fun l(en: String, br: String) = if (pt) br else en
        return if (profile in xp) mapOf(0 to l("Stopped", "Parado"), 1 to l("Loading", "Carregando"),
            4 to l("Playing", "Reproduzindo"), 5 to l("Paused", "Pausado"))
        else mapOf(1 to l("Paused", "Pausado"), 2 to l("Playing", "Reproduzindo"), 3 to l("Fast forward", "Avançando"),
            6 to l("Stopped", "Parado"), 9 to l("Rewind", "Retrocedendo"), 12 to l("Ejecting", "Ejetando"), 13 to l("Loading", "Carregando"))
    }
    private fun transport(pt: Boolean) = if (pt) mapOf(1 to "Reproduzir", 2 to "Parar", 3 to "Avançar", 4 to "Retroceder")
        else mapOf(1 to "Play", 2 to "Stop", 3 to "Forward", 4 to "Back")
    fun command(profile: Int, field: Int, value: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!supports(profile)) return null
        if (compass(profile) && field == 8 && value in 1..15 && raw[8] in 1..15) return 1 to listOf(value)
        if (field == statusField(profile) && value in transport(false) && raw[field] in states(profile, false)) return 0 to listOf(value)
        return null
    }
    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> = buildList {
        if (!supports(profile)) return@buildList
        val offset = if (profile == 76) 10 else 0
        fun row(id: Int, text: String, en: String, br: String, options: Map<Int, String> = emptyMap(), deps: Set<Int> = setOf(id)) {
            add(FytSyuReading("honda_legacy", id, deps, text, options = options, label = if (pt) br else en))
        }
        val state = statusField(profile)
        raw[state]?.let(states(profile, pt)::get)?.let { row(state, it, "Factory playback", "Reprodução original", transport(pt)) }
        val sources = if (profile in xp) mapOf(1 to "iPod", 2 to "USB") else mapOf(13 to "USB", 14 to "iPod")
        raw[offset + 1]?.let(sources::get)?.let { row(offset + 1, it, "Media source", "Fonte de mídia") }
        raw[offset + 2]?.takeIf { it in 0..65535 && it and 255 < 60 }?.let {
            row(offset + 2, "${(it shr 8).toString().padStart(2, '0')}:${(it and 255).toString().padStart(2, '0')}", "Playback time", "Tempo de reprodução")
        }
        val track = raw[offset + 3]?.takeIf { it in 0 until 0xffffff }
        val total = raw[offset + 4]?.takeIf { it in 0 until 0xffffff }
        if (track != null && total != null) row(offset + 3, "$track / $total", "Track", "Faixa", deps = setOf(offset + 3, offset + 4))
        if (profile in xp) raw[5]?.takeIf { it in 0 until 0xffffff }?.let { row(5, it.toString(), "Folder", "Pasta") }
        else raw[offset + 5]?.takeIf { it in 0..1 }?.let { row(offset + 5,
            if (pt) (if (it == 1) "Compatível" else "Indisponível") else (if (it == 1) "Supported" else "Unavailable"), "MDI", "MDI") }
        raw[offset + 6]?.takeIf { it in 0..100 }?.let { row(offset + 6, "$it%", "Playback progress", "Progresso da reprodução") }
        if (compass(profile)) raw[8]?.takeIf { it in 1..15 }?.let { row(8, it.toString(), "Compass zone", "Zona da bússola", (1..15).associateWith(Int::toString)) }
        motion(profile, raw)[89]?.let { row(0, "$it km/h", "Speed", "Velocidade") }
    }
}
