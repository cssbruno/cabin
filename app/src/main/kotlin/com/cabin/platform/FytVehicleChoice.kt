package com.cabin.platform

/** A command with enumerated choices and no verified current-value callback. */
enum class FytVehicleChoice {
    LANGUAGE;
    fun title(portuguese: Boolean) = if (portuguese) "Idioma do veículo" else "Vehicle language"
}

internal object CabinHondaLanguage {
    private val rzc = listOf("简体中文", "English", "繁體中文", "ไทย", "Bahasa Melayu", "Bahasa Indonesia",
        "한국어", "Deutsch", "Italiano", "Français", "Español", "Nederlands", "Svenska", "Norsk", "Dansk",
        "Português", "Ελληνικά", "Polski", "Türkçe", "Русский", "Čeština", "Magyar", "Română", "Slovenščina",
        "العربية", "Български", "עברית", "Latviešu", "Lietuvių", "Srpski", "Hrvatski", "Slovenčina", "Suomi")
        .withIndex().associate { it.index to it.value }
    fun options(profile: Int, protocol: String): Map<Int, String> = when {
        protocol == "honda_wc_0321" -> mapOf(1 to "English", 2 to "简体中文", 3 to "繁體中文")
        protocol == "honda_0298" && CabinHondaRzcSettings.supports(profile) -> rzc
        else -> emptyMap()
    }
    fun command(profile: Int, protocol: String, value: Int): Pair<Int, List<Int>>? {
        if (value !in options(profile, protocol)) return null
        return if (protocol == "honda_wc_0321") 112 to listOf(1, value) else 105 to listOf(85, value)
    }
}
