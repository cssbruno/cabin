package com.cabin.platform

/** Escape 2020 / Transit settings: FordCarSet_RZC2 and service f0.eb, July 2023. */
internal object CabinFordSettings {
    fun supports(profile: Int) = profile == 917838 || profile == 1114446
    val fields = (133..174).toSet()
    private val selectors = listOf(0, 1, 2, 3, 4, 5, 6, 7, 16, 17, 18, 19, 20,
        32, 33, 34, 35, 36, 37, 48, 64, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
        91, 92, 96, 97, 98, 99, 100, 112, 113, 114)
    private val names = listOf(
        "Lane assist" to "Assistente de faixa", "Warning intensity" to "Intensidade do aviso",
        "Reverse cross-traffic warning" to "Aviso de tráfego ao dar ré", "Trailer system" to "Sistema de reboque",
        "Cruise control" to "Controle de cruzeiro", "Automatic engine shutdown" to "Desligamento automático do motor",
        "ESP" to "ESP", "Remote body control" to "Controle remoto da carroceria",
        "Distance warning" to "Aviso de distância", "Active braking" to "Frenagem ativa",
        "Collision warning sensitivity" to "Sensibilidade do aviso de colisão",
        "Blind-spot detection" to "Detecção de ponto cego", "Driver alert" to "Alerta do motorista",
        "Ambient brightness" to "Brilho ambiente", "Headlight delay" to "Atraso dos faróis",
        "Automatic high beam" to "Farol alto automático", "Daytime running lights" to "Luzes diurnas",
        "Coming-home lighting" to "Iluminação ao chegar", "Interior light delay" to "Atraso da luz interna",
        "Powered tailgate" to "Porta-malas elétrico", "Automatic mirror unfolding" to "Abertura automática dos retrovisores",
        "Lock switch inhibition" to "Bloqueio do interruptor de travamento", "Lock sound feedback" to "Som de travamento",
        "Mislock warning" to "Aviso de travamento incompleto", "Remote unlock" to "Destravamento remoto",
        "Automatic unlock" to "Destravamento automático", "Remote window opening" to "Abertura remota dos vidros",
        "Remote window closing" to "Fechamento remoto dos vidros", "Remote start" to "Partida remota",
        "Remote-start climate" to "Climatização na partida remota", "Seat and steering heating" to "Aquecimento do banco e volante",
        "Remote-start duration" to "Duração da partida remota", "Speed locking" to "Travamento por velocidade",
        "One-touch unlocking" to "Destravamento com um toque", "Rain sensing" to "Sensor de chuva",
        "Extra wipe" to "Passada extra do limpador", "Window wiping" to "Limpeza dos vidros",
        "Automatic wipers" to "Limpadores automáticos", "Front wiper service mode" to "Modo de manutenção dos limpadores",
        "Pressure unit" to "Unidade de pressão", "Distance and consumption units" to "Unidades de distância e consumo",
        "Temperature unit" to "Unidade de temperatura")

    private fun options(field: Int, pt: Boolean): Map<Int, String> {
        fun text(en: String, br: String) = if (pt) br else en
        val off = text("Off", "Desligado")
        val labels = when (field) {
            133 -> listOf(text("Warning", "Aviso"), text("Assist", "Assistência"), text("Warning and assist", "Aviso e assistência"))
            134, 143 -> listOf(text("Low", "Baixa"), text("Normal", "Normal"), text("High", "Alta"))
            137 -> listOf(text("Adaptive", "Adaptativo"), text("Normal", "Normal"))
            146 -> return (0..100).associateWith { if (it == 0) off else it.toString() }
            147 -> return listOf(0, 10, 20, 120).associateWith { if (it == 0) off else "$it s" }
            150 -> listOf(off, "30 s", "60 s", "120 s")
            151, 164 -> listOf("5 min", "10 min", "15 min")
            157 -> listOf(text("All doors", "Todas as portas"), text("Driver door", "Porta do motorista"))
            162 -> listOf(text("Automatic", "Automática"), text("Last setting", "Último ajuste"))
            163 -> listOf(text("Automatic", "Automático"), off)
            172 -> listOf("psi", "kPa", "bar")
            173 -> return mapOf(0 to "mi · mpg", 2 to "km · L/100 km", 3 to "km · km/L")
            174 -> listOf("°C", "°F")
            else -> if (field in fields) listOf(off, text("On", "Ligado")) else emptyList()
        }
        return labels.mapIndexed { value, label -> value to label }.toMap()
    }
    fun command(profile: Int, field: Int, target: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!supports(profile) || field !in fields) return null
        val allowed = options(field, false)
        if (target !in allowed || raw[field] !in allowed) return null
        return 10 to listOf(selectors[field - 133], target)
    }
    fun read(profile: Int, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> {
        if (!supports(profile)) return emptyList()
        return fields.mapNotNull { field ->
            val value = raw[field] ?: return@mapNotNull null
            val choices = options(field, portuguese)
            val text = choices[value] ?: return@mapNotNull null
            val name = names[field - 133]
            FytSyuReading("ford_0334", field, setOf(field), text, options = choices,
                label = if (portuguese) name.second else name.first)
        }
    }
}
