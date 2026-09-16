package com.cabin.platform

/** July 2023 WC Honda service x: these IDs and command selectors differ from RZC. */
internal object CabinHondaWcSettings {
    val fields = (47..72).toSet() + (85..113).toSet() - setOf(103, 104)
    private val booleanFields = setOf(47, 49, 54, 55, 57, 58, 60, 62, 63, 65, 66, 67, 68, 88, 90, 92, 93, 95, 96, 98, 99, 100, 102, 106, 107, 108, 109, 110, 112, 113)
    private val panelCommands = mapOf(65 to 6, 66 to 7, 67 to 8, 68 to 5, 69 to 4, 70 to 3, 71 to 2, 72 to 1,
        88 to 9, 98 to 11, 99 to 12, 100 to 10, 102 to 13, 109 to 16, 110 to 15, 111 to 14)
    private val assistanceCommands = mapOf(61 to 4, 62 to 3, 63 to 2, 64 to 1, 96 to 8, 97 to 7, 101 to 9)
    private val hiddenLightingProfiles = setOf(328001, 393537)
    private fun options(profile: Int, field: Int, pt: Boolean): Map<Int, String> {
        fun label(en: String, translated: String) = if (pt) translated else en
        if (field in 49..51 && profile in hiddenLightingProfiles) return emptyMap()
        if (field in 109..111 && profile !in setOf(262465, 786753, 852289)) return emptyMap()
        if (field == 88 && profile !in hiddenLightingProfiles && profile != 721217) return emptyMap()
        if (field in setOf(65, 66) && profile in hiddenLightingProfiles) return emptyMap()
        if (field == 67 && profile != 721217) return emptyMap()
        if (field in 89..90 && profile !in setOf(328001, 393537, 1179969, 1245505, 1311041, 524609, 590145, 459073)) return emptyMap()
        val values = when (field) {
            in booleanFields -> listOf(label("Off", "Desligado"), label("On", "Ligado"))
            48 -> listOf(label("Left", "Esquerda"), label("Right", "Direita"))
            85 -> listOf("0 s", "2 s")
            89 -> listOf(label("Any time", "A qualquer momento"), label("After unlocking", "Após destravar"))
            91 -> listOf(label("Off", "Desligado"), label("Low", "Baixo"), label("Medium", "Médio"), label("High", "Alto"))
            105 -> listOf(label("Narrow", "Estreito"), label("Wide", "Largo"))
            50, 51 -> listOf(label("Minimum", "Mínimo"), label("Low", "Baixo"), label("Medium", "Médio"), label("High", "Alto"), label("Maximum", "Máximo"))
            52 -> listOf("0 s", "15 s", "30 s", "60 s")
            // Zero is explicitly INVALID in the source, not an off option.
            53 -> return mapOf(1 to "15 s", 2 to "30 s", 3 to "60 s")
            59 -> return mapOf(1 to "30 s", 2 to "60 s", 3 to "90 s")
            56 -> listOf(label("Low", "Baixo"), label("High", "Alto"))
            61 -> return mapOf(1 to label("Normal", "Normal"), 2 to label("Wide", "Amplo"), 3 to label("Warning only", "Apenas aviso"))
            64 -> return mapOf(1 to label("Far", "Longe"), 2 to label("Medium", "Médio"), 3 to label("Near", "Perto"))
            69 -> return mapOf(1 to label("High", "Alto"), 2 to label("Medium", "Médio"), 3 to label("Low", "Baixo"))
            70, 71 -> return mapOf(1 to label("On refueling", "Ao abastecer"), 2 to label("Ignition off", "Ao desligar a ignição"), 3 to label("Manually", "Manualmente"))
            72 -> return (1..7).associateWith { (it - 4).toString() }
            94 -> listOf("0 s", "2 s")
            97 -> listOf(label("Off", "Desligado"), label("Visual warning", "Aviso visual"), label("Visual and tactile warning", "Aviso visual e tátil"))
            101 -> return mapOf(1 to label("Standard", "Padrão"), 2 to label("Delayed", "Tardio"), 3 to label("Warning only", "Apenas aviso"), 4 to label("Early", "Antecipado"))
            111 -> (1..3).map { label("Type $it", "Tipo $it") }
            86 -> listOf(label("Off", "Desligado"), label("Shift to P", "Ao colocar em P"), label("Ignition off", "Ao desligar a ignição"))
            87 -> listOf(label("Off", "Desligado"), label("Vehicle speed", "Pela velocidade"), label("Shift from P", "Ao sair de P"))
            else -> return emptyMap()
        }
        return values.withIndex().associate { it.index to it.value }
    }

    fun command(profile: Int, field: Int, value: Int, current: Map<Int, Int>): Pair<Int, List<Int>>? {
        val choices = options(profile, field, false)
        if (value !in choices || current[field] !in choices) return null
        when (field) {
            47 -> return 0 to listOf(value)
            85 -> return 109 to listOf(value)
            in 105..108 -> return 110 to listOf(13, (108 - field) * 2 + value)
        }
        val (code, selector) = when (field) {
            48 -> 110 to 21
            89, 90 -> 1 to (field - 88)
            91, 92 -> 2 to (field - 84)
            112 -> 110 to 17
            113 -> 110 to 16
            in 49..53 -> 102 to (54 - field)
            in 54..57 -> 103 to (58 - field)
            in 58..60 -> 104 to (61 - field)
            in panelCommands -> 106 to panelCommands.getValue(field)
            in assistanceCommands -> 105 to assistanceCommands.getValue(field)
            93, 94 -> 110 to 12
            95 -> 110 to 14
            86 -> 104 to 5
            87 -> 104 to 4
            else -> return null
        }
        // LaneWatch duration shares selector 12 with enable; values 4/5 select
        // duration while values 0/1 enable/disable it.
        return code to listOf(selector, if (field == 94) value + 4 else value)
    }

    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> = fields.mapNotNull { field ->
        val choices = options(profile, field, pt)
        val value = raw[field] ?: return@mapNotNull null
        val text = choices[value] ?: return@mapNotNull null
        val (en, translated) = when (field) {
            47 -> "Camera with right turn signal" to "Câmera com a seta à direita"
            48 -> "Steering wheel side" to "Lado do volante"
            49 -> "Headlights with wipers" to "Faróis com limpadores"
            50 -> "Automatic interior light sensitivity" to "Sensibilidade da luz interna automática"
            51 -> "Automatic headlight sensitivity" to "Sensibilidade dos faróis automáticos"
            52 -> "Headlight off delay" to "Tempo para desligar os faróis"
            53 -> "Interior light dimming delay" to "Tempo para reduzir a luz interna"
            54 -> "Keyless beep" to "Som da chave presencial"
            55 -> "Keyless light confirmation" to "Confirmação luminosa da chave presencial"
            56 -> "Alarm volume" to "Volume do alarme"
            57 -> "Horn with remote start" to "Buzina na partida remota"
            58 -> "Walk-away locking" to "Travar ao se afastar"
            59 -> "Automatic relock delay" to "Tempo para travar novamente"
            60 -> "Remote lock confirmation" to "Confirmação de travamento remoto"
            61 -> "Lane departure warning mode" to "Modo de aviso de saída de faixa"
            62 -> "Lane assist pause tone" to "Som de pausa do assistente de faixa"
            63 -> "Vehicle ahead detection tone" to "Som de detecção do veículo à frente"
            64 -> "Forward warning distance" to "Distância do aviso de colisão"
            65 -> "Speed reminders" to "Avisos de velocidade"
            66 -> "Message notifications" to "Avisos de mensagens"
            67 -> "Engine start-stop economy tips" to "Dicas de economia do sistema start-stop"
            68 -> "Economy background illumination" to "Iluminação de economia de combustível"
            69 -> "Warning volume" to "Volume dos avisos"
            70 -> "Trip B reset condition" to "Condição para zerar viagem B"
            71 -> "Trip A reset condition" to "Condição para zerar viagem A"
            72 -> "Outside temperature adjustment" to "Ajuste da temperatura externa"
            85 -> "Camera delay after turn signal" to "Tempo da câmera após desligar a seta"
            89 -> "Remote tailgate opening" to "Abertura remota do porta-malas"
            90 -> "Power tailgate" to "Porta-malas elétrico"
            91 -> "Speed compensated volume" to "Volume conforme a velocidade"
            92 -> "Surround sound" to "Som surround"
            105 -> "Parking space width" to "Largura da vaga"
            106 -> "Camera after reversing" to "Câmera após a marcha à ré"
            107 -> "Dynamic guidelines" to "Linhas-guia dinâmicas"
            108 -> "Static guidelines" to "Linhas-guia fixas"
            112 -> "Preset mode" to "Modo predefinido"
            113 -> "Automatic camera at low speed" to "Câmera automática em baixa velocidade"
            88 -> "Reverse tone" to "Som de ré"
            93 -> "LaneWatch with turn signal" to "LaneWatch com a seta"
            94 -> "LaneWatch display delay" to "Tempo de exibição do LaneWatch"
            95 -> "Rear view reminders" to "Avisos da visão traseira"
            96 -> "Heads-up warning" to "Aviso de atenção"
            97 -> "Driver attention warning" to "Aviso de atenção do motorista"
            98 -> "Seat position memory linkage" to "Vincular memória da posição do banco"
            99 -> "Seat belt pretensioner mode" to "Modo do pré-tensionador do cinto"
            100 -> "Switch lock" to "Bloqueio do interruptor"
            101 -> "Lane departure prevention" to "Prevenção de saída de faixa"
            102 -> "Traffic sign display" to "Exibição de placas de trânsito"
            109 -> "Turn-by-turn display" to "Instruções de navegação no painel"
            110 -> "Warning messages" to "Mensagens de aviso"
            111 -> "Instrument panel type" to "Tipo do painel de instrumentos"
            86 -> "Automatic unlocking" to "Destravamento automático"
            else -> "Automatic locking" to "Travamento automático"
        }
        FytSyuReading("honda_wc_settings_2023", field, setOf(field), text,
            checked = if (field in booleanFields) value == 1 else null,
            options = choices, label = if (pt) translated else en)
    }
}
