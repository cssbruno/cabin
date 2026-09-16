package com.cabin.platform

/** WC GM capability byte + selected value, KlcComfort/Lock/LightAct and h0. */
internal object CabinGmWcSettings {
    private data class Setting(val command: Int, val selector: Int, val en: String, val pt: String)
    private val settings = linkedMapOf(
        20 to Setting(4, 1, "Easy-exit seat", "Saída facilitada do banco"),
        21 to Setting(4, 3, "Easy-exit steering column", "Saída facilitada da coluna de direção"),
        22 to Setting(4, 2, "Mirror tilt in reverse", "Inclinar retrovisor em ré"),
        23 to Setting(4, 4, "Automatic mirror folding", "Rebatimento automático dos retrovisores"),
        24 to Setting(4, 5, "Driver personalization", "Preferências do motorista"),
        25 to Setting(4, 6, "Rear wiper in reverse", "Limpador traseiro em ré"),
        26 to Setting(4, 7, "Easy-exit steering tilt", "Inclinação do volante para saída"),
        27 to Setting(5, 1, "Prevent automatic locking", "Impedir travamento automático"),
        28 to Setting(5, 2, "Automatic locking", "Travamento automático"),
        29 to Setting(5, 3, "Unlocking · automatic transmission", "Destravamento · câmbio automático"),
        30 to Setting(5, 4, "Delayed locking", "Travamento com atraso"),
        31 to Setting(5, 5, "Unlocking · manual transmission", "Destravamento · câmbio manual"),
        137 to Setting(5, 6, "Anti-lockout", "Proteção contra travamento da chave"),
        32 to Setting(6, 1, "Remote locking feedback", "Confirmação de travamento remoto"),
        33 to Setting(6, 2, "Remote unlocking feedback", "Confirmação de destravamento remoto"),
        34 to Setting(6, 3, "Remote unlocking doors", "Portas destravadas pelo controle"),
        35 to Setting(6, 4, "Relock after remote unlocking", "Retravar após destravamento remoto"),
        36 to Setting(6, 5, "Relock remotely opened door", "Retravar porta aberta pelo controle"),
        37 to Setting(6, 6, "Recognize driver's key", "Reconhecer chave do motorista"),
        38 to Setting(6, 7, "Allow remote start", "Permitir partida remota"),
        39 to Setting(6, 8, "Passive unlocking doors", "Portas destravadas por aproximação"),
        40 to Setting(6, 10, "Walk-away locking", "Travamento ao se afastar"),
        41 to Setting(6, 9, "Key left behind reminder", "Aviso de chave esquecida"),
        42 to Setting(6, 11, "Remote sliding door behavior", "Abertura remota da porta deslizante"),
        70 to Setting(6, 12, "Remote window control", "Controle remoto dos vidros"),
        43 to Setting(7, 1, "Vehicle locator lights", "Luzes de localização do veículo"),
        44 to Setting(7, 2, "Headlight delay", "Temporizador dos faróis"),
        138 to Setting(7, 3, "Position lights", "Luzes de posição"),
    )
    val fields = settings.keys
    private fun options(field: Int, pt: Boolean): Map<Int, String> {
        if (field !in settings) return emptyMap()
        val off = if (pt) "Desligado" else "Off"
        val entries = when (field) {
            21 -> if (pt) listOf(off, "Recolher", "Elevar", "Recolher e elevar") else listOf(off, "Retract", "Raise", "Retract and raise")
            29, 31 -> if (pt) listOf(off, "Porta do motorista", "Todas as portas") else listOf(off, "Driver door", "All doors")
            32 -> if (pt) listOf("Luzes", "Luzes e buzina", "Buzina", off) else listOf("Lights", "Lights and horn", "Horn", off)
            33 -> listOf(off, if (pt) "Luzes" else "Lights")
            34, 39 -> if (pt) listOf("Porta do motorista", "Todas as portas") else listOf("Driver door", "All doors")
            40 -> if (pt) listOf(off, "Ligado", "Ligado com buzina") else listOf(off, "On", "On with horn")
            42 -> if (pt) listOf("Destravar todas e abrir a deslizante", "Destravar e abrir somente a deslizante")
                else listOf("Unlock all and open sliding door", "Unlock and open sliding door only")
            44 -> listOf(off, "30 s", "60 s", "120 s")
            else -> listOf(off, if (pt) "Ligado" else "On")
        }
        return entries.withIndex().associate { it.index to it.value }
    }
    private fun value(field: Int, raw: Int?): Int? = raw?.takeIf { it in 0x100..0x1ff }
        ?.and(255)?.takeIf { it in options(field, false) }

    fun command(field: Int, target: Int, current: Map<Int, Int>): Pair<Int, List<Int>>? {
        val setting = settings[field] ?: return null
        if (value(field, current[field]) == null || target !in options(field, false)) return null
        // Capability bits describe availability only; never send them as the target value.
        return setting.command to listOf(setting.selector, target)
    }
    fun read(raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> = settings.mapNotNull { (field, setting) ->
        val selected = value(field, raw[field]) ?: return@mapNotNull null
        val opts = options(field, pt)
        FytSyuReading("gm_wc_0036", field, setOf(field), opts.getValue(selected), options = opts,
            label = if (pt) setting.pt else setting.en)
    }
}
