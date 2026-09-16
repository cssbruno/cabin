package com.cabin.platform

/** July RZC Honda panorama settings, source screen Acrivity_RZC_Aodes360Settings. */
internal object CabinHondaPanorama {
    val fields = (167..172).toSet()
    fun supports(profile: Int) = CabinHondaTrip.supportsTripB(profile)

    private fun choices(field: Int, pt: Boolean): List<String> {
        fun label(en: String, translated: String) = if (pt) translated else en
        return when (field) {
            167 -> listOf(label("Front and panorama", "Frente e panorama"), label("Previous screen", "Tela anterior"),
                label("Front only", "Somente frente"), label("Left and right", "Esquerda e direita"))
            168 -> listOf(label("Rear and panorama", "Traseira e panorama"), label("Previous screen", "Tela anterior"),
                label("Wide rear view", "Visão traseira ampla"), label("Standard rear view", "Visão traseira padrão"))
            169, 172 -> listOf(label("Off", "Desligado"), label("On", "Ligado"))
            170 -> listOf(label("Reverse parking", "Estacionamento de ré"), label("Parallel parking", "Estacionamento paralelo"))
            171 -> listOf(label("Narrow area", "Área estreita"), label("Wide area", "Área ampla"))
            else -> emptyList()
        }
    }

    fun command(profile: Int, field: Int, value: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!supports(profile) || value !in choices(field, false).indices || raw[field] !in choices(field, false).indices) return null
        // The stock rear-view buttons mistakenly read 105. Feedback is field 168;
        // select the requested value against that actual feedback instead.
        return 105 to listOf(field - 114, value)
    }

    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> {
        if (!supports(profile)) return emptyList()
        fun label(en: String, translated: String) = if (pt) translated else en
        return fields.mapNotNull { field ->
            val choices = choices(field, pt)
            val value = raw[field]?.takeIf { it in choices.indices } ?: return@mapNotNull null
            val name = when (field) {
                167 -> label("Driving camera view", "Visão da câmera em movimento")
                168 -> label("Reverse camera view", "Visão da câmera de ré")
                169 -> label("Automatic camera at low speed", "Câmera automática em baixa velocidade")
                170 -> label("Preset parking mode", "Modo de estacionamento predefinido")
                171 -> label("Reverse parking area", "Área de estacionamento de ré")
                else -> label("Intersection monitor", "Monitor de cruzamentos")
            }
            FytSyuReading("honda_panorama_2023", field, setOf(field), choices[value],
                checked = if (field == 169 || field == 172) value == 1 else null,
                options = choices.withIndex().associate { it.index to it.value }, label = name)
        }
    }
}
