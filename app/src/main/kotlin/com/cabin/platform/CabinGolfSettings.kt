package com.cabin.platform

/** July 2023 Golf7FunctionalMirrorsAndWipersActi wire contract, implemented by Cabin. */
internal object CabinGolfSettings {
    private val wcProfiles = setOf(17, 65553, 131089, 393233, 458769, 131342, 327950, 262414, 270, 45, 196625, 262161, 327697, 589841, 720913, 655377, 524305, 786449, 851985, 917521, 983057, 1048593, 1114129, 1179665, 1245201, 1310737, 1376273, 1441809, 1507345, 1572881, 1638417, 1703953, 1769489, 1835025, 1900561, 1966097, 2031633, 2097169, 2162705, 2228241, 2293777, 2359313, 2424849, 2490385, 2555921, 2621457, 2686993, 2752529, 2818065)
    private val rzcProfiles = setOf(160, 131232, 196768, 262304, 327840, 458912, 524448, 589984, 655520, 721056, 786592, 852128, 917664, 983200, 1048736, 1179808, 1310880, 1376416, 1441952, 1507488, 1573024, 1638560, 1704096, 1769632, 1835168, 1900704, 1966240, 2031776, 2097312, 2162848, 2228384, 2293920, 2359456, 2424992, 2490528, 2556064, 2621600, 2687136, 2752672, 2818208, 2883744, 2949280, 3014816, 3080352, 3145888, 3211424, 3276960, 3342496, 3408032, 3473568, 3539104, 3604640)
    private val mirrorFields = (51..55).toSet()
    private val parkingFields = (19..25).toSet()
    fun fields(protocol: String) = mirrorFields + parkingFields + (56..64) + setOf(39, 40, 41, 85, 145, 146) +
        if (protocol == "golf_wc_2023") setOf(194, 200, 234) else setOf(335, 336, 337, 340, 341, 342, 368, 369)

    fun dialect(profile: Int, callback: String): String? = when {
        profile in wcProfiles && callback == "Lcom/syu/module/canbus/Callback_0017_WC2_GaoErFu7;" -> "golf_wc_2023"
        profile in rzcProfiles && callback == "Lcom/syu/module/canbus/Callback_0160_RZC_XP1_DaZhong_GaoErFu7;" -> "golf_rzc_2023"
        else -> null
    }
    // Old widget IDs are canonical here, never raw IDs in the July 2023 firmware.
    fun widgetValues(protocol: String, legacy: Map<Int, Int>, raw: Map<Int, Int>): Map<Int, Int> {
        if (!supports(protocol)) return legacy
        val canonical = (mirrorFields + (19..23)).associateWith { it + 97 }
        return (legacy - canonical.values.toSet()) + if (protocol == "golf_wc_2023")
            canonical.mapNotNull { (field, output) -> value(protocol, field, raw[field])?.let { output to (256 + it) } }.toMap()
        else emptyMap()
    }
    fun supports(protocol: String) = protocol == "golf_wc_2023" || protocol == "golf_rzc_2023"
    private fun value(protocol: String, field: Int, raw: Int?): Int? {
        if (!supports(protocol) || field !in fields(protocol) || raw == null || raw !in 0..65535) return null
        if (field == 24 && protocol != "golf_wc_2023") return null
        val range = when (field) { in 20..23 -> 0..8; 39, 40 -> 0..2; 24 -> 1..2; else -> 0..1 }
        if (protocol == "golf_wc_2023") {
            if (field != 53 && raw shr 8 == 0) return null
            return (raw and 255).takeIf { it in range }
        }
        return raw.takeIf { it in range }
    }
    fun command(profile: Int, protocol: String, field: Int, target: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (value(protocol, field, raw[field]) == null) return null
        if (field == 335 || field == 24) return null // 335 has no action; mode 24 wire/feedback parity still under review.
        if (field == 194 && profile in setOf(393233, 458769)) return null
        if (target !in (when (field) { in 20..23 -> 0..8; 39, 40 -> 0..2; else -> 0..1 })) return null
        return when (field) {
            in 51..55 -> (field + 16) to listOf(target)
            in 56..64 -> (field + 19) to listOf(target)
            85 -> 0 to listOf(target)
            in 19..23 -> (field + 20) to listOf(target)
            25 -> 30 to listOf(target)
            in 39..41 -> (field + 33) to listOf(target)
            145 -> 106 to listOf(4, target)
            146 -> 106 to listOf(5, target)
            in 340..342 -> 160 to listOf(field - 238, target)
            368 -> 160 to listOf(115, target)
            369 -> 160 to listOf(117, target)
            194 -> 110 to listOf(target)
            200 -> 111 to listOf(target)
            234 -> 132 to listOf(target)
            336 -> 160 to listOf(58, target)
            337 -> 160 to listOf(59, target)
            else -> null
        }
    }
    fun read(profile: Int, protocol: String, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> {
        fun label(en: String, pt: String) = if (portuguese) pt else en
        val labels = mapOf(
            51 to label("Mirror synchronization", "Sincronizar espelhos"),
            52 to label("Reverse mirror dip", "Inclinar espelho em ré"),
            53 to label("Fold mirrors when parked", "Recolher espelhos ao estacionar"),
            54 to label("Automatic rain wipers", "Limpadores automáticos na chuva"),
            55 to label("Rear wiper in reverse", "Limpador traseiro em ré"),
            56 to label("Display current consumption", "Exibir consumo instantâneo"),
            57 to label("Display average consumption", "Exibir consumo médio"),
            58 to label("Display convenience consumers", "Exibir consumo dos acessórios"),
            59 to label("Display economy tips", "Exibir dicas de economia"),
            60 to label("Display travel time", "Exibir tempo de viagem"),
            61 to label("Display distance traveled", "Exibir distância percorrida"),
            62 to label("Display average speed", "Exibir velocidade média"),
            63 to label("Display digital speed", "Exibir velocidade digital"),
            64 to label("Display speed warning", "Exibir aviso de velocidade"),
            85 to label("Display oil temperature", "Exibir temperatura do óleo"),
            39 to label("Convenience window opening", "Abertura dos vidros pela chave"),
            40 to label("Door unlocking", "Destravamento das portas"),
            41 to label("Automatic locking", "Travamento automático"),
            145 to label("Vehicle key activation", "Ativação da chave do veículo"),
            146 to label("Easy Open", "Abertura do porta-malas por sensor"),
            340 to label("Front window opening setting", "Configuração de abertura dos vidros dianteiros"),
            341 to label("Rear window opening setting", "Configuração de abertura dos vidros traseiros"),
            342 to label("Sliding sunroof setting", "Configuração do teto solar"),
            368 to label("Interior monitoring system", "Monitoramento do interior"),
            369 to label("Sound feedback", "Confirmação sonora"),
            19 to label("Automatic parking sensors", "Sensores de estacionamento automáticos"),
            20 to label("Front parking volume", "Volume dos sensores dianteiros"),
            21 to label("Front parking tone", "Tom dos sensores dianteiros"),
            22 to label("Rear parking volume", "Volume dos sensores traseiros"),
            23 to label("Rear parking tone", "Tom dos sensores traseiros"),
            24 to label("Parking mode", "Modo de estacionamento"),
            25 to label("Parking sensor sound", "Som dos sensores de estacionamento"),
            194 to label("Exit parking assistance", "Assistência para sair da vaga"),
            200 to label("Off-road icon", "Ícone fora de estrada"),
            234 to label("Parking brake setting", "Configuração do freio de estacionamento"),
            335 to label("Parking activation", "Ativação do estacionamento"),
            336 to label("Parking brake setting", "Configuração do freio de estacionamento"),
            337 to label("Exit parking assistance", "Assistência para sair da vaga"))
        val onOff = mapOf(0 to label("Off", "Desligado"), 1 to label("On", "Ligado"))
        return fields(protocol).mapNotNull { field -> value(protocol, field, raw[field])?.let { value ->
            if (field == 194 && profile in setOf(393233, 458769)) return@mapNotNull null
            val choices = when (field) {
                in 20..23 -> (0..8).associateWith { (it + if (protocol == "golf_rzc_2023") 1 else 0).toString() }
                39 -> (if (protocol == "golf_wc_2023") listOf(label("All windows", "Todos os vidros"), label("Driver window", "Vidro do motorista"), onOff.getValue(0))
                    else listOf(onOff.getValue(0), label("Driver window", "Vidro do motorista"), label("All windows", "Todos os vidros"))).withIndex().associate { it.index to it.value }
                40 -> (if (protocol == "golf_wc_2023") listOf(label("All doors", "Todas as portas"), label("Driver door", "Porta do motorista"), label("Vehicle side", "Lado do veículo"))
                    else listOf(label("Vehicle side", "Lado do veículo"), label("Driver door", "Porta do motorista"), label("All doors", "Todas as portas"))).withIndex().associate { it.index to it.value }
                24 -> mapOf(1 to label("Bay parking", "Vaga perpendicular"), 2 to label("Parallel parking", "Vaga paralela"))
                else -> onOff
            }
            val writable = choices.filterKeys { command(profile, protocol, field, it, raw) != null }
            FytSyuReading(protocol, field, setOf(field), choices.getValue(value),
                if (field !in 20..24 && field !in 39..40) value == 1 else null, writable, labels.getValue(field))
        } }
    }
}
