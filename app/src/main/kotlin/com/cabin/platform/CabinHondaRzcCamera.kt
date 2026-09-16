package com.cabin.platform

/** ZX Honda variants routed through RZCCommpassActi in the July client. */
internal object CabinHondaRzcCamera {
    private val profiles = setOf(3014954, 3080490, 3932458, 4194602, 3997994)
    private val voiceFields = (227..231).toSet()
    val fields = voiceFields + 188
    fun supports(profile: Int) = profile in profiles

    fun command(profile: Int, field: Int, value: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (!supports(profile)) return null
        if (field == 188 && value in 0..3 && raw[field] in 0..3) return 151 to listOf(2, value)
        if (field !in voiceFields || value !in 0..1 || voiceFields.any { raw[it] !in 0..1 }) return null
        // The firmware replaces all five switches in one frame. Never fill absent
        // companion values with zero: that would disable unrelated reminders.
        return 152 to voiceFields.map { if (it == field) value else raw.getValue(it) }
    }

    fun read(profile: Int, raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> {
        if (!supports(profile)) return emptyList()
        fun label(en: String, translated: String) = if (pt) translated else en
        return fields.mapNotNull { field ->
            val choices = if (field == 188) listOf("0 s", "3 s", "8 s", label("Until moving forward", "Até avançar"))
                else listOf(label("Off", "Desligado"), label("On", "Ligado"))
            val value = raw[field]?.takeIf { it in choices.indices } ?: return@mapNotNull null
            val name = when (field) {
                188 -> label("Reverse camera delay", "Tempo da câmera de ré")
                227 -> label("Seat belt voice reminder", "Aviso de voz do cinto")
                228 -> label("Door voice reminder", "Aviso de voz das portas")
                229 -> label("Remaining range voice reminder", "Aviso de voz da autonomia")
                230 -> label("Driver fatigue voice reminder", "Aviso de voz de fadiga")
                else -> label("Overspeed voice reminder", "Aviso de voz de excesso de velocidade")
            }
            val canChange = field == 188 || voiceFields.all { raw[it] in 0..1 }
            FytSyuReading("honda_rzc_camera_2023", field, setOf(field), choices[value],
                checked = if (field != 188) value == 1 else null,
                options = if (canChange) choices.withIndex().associate { it.index to it.value } else emptyMap(), label = name)
        }
    }
}
