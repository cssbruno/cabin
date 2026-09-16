package com.cabin.platform

/** Cabin-owned implementations of the documented SYU wire protocols. No APK or DEX access. */
internal class CabinSyuDecoder(private val profile: Int, private val protocol: String, private val portuguese: Boolean = false, private val enums: FytSyuDisplay? = null) : FytSyuDisplay {
    // Callback IDs are local to a protocol, not global sensor identifiers.
    val motionFields: Set<Int> get() = when (protocol) {
        "bagoo_audi" -> setOf(1)
        "gm_wc_0036" -> CabinGmWcReadings.motionFields
        else -> emptySet()
    }
    fun motionValues(raw: Map<Int, Int>): Map<Int, Int> = if (protocol == "gm_wc_0036") CabinGmWcReadings.motion(raw) else emptyMap()
    val cachedRefreshExcludedFields: Set<Int> get() {
        val legacyMotion = setOf(89, 90, 149, 151)
        val verifiedNonMotion = if (hasFordTires) CabinFordTires.fields +
            (if (CabinFordSettings.supports(profile)) CabinFordSettings.fields else emptySet()) +
            (if (CabinFordLegacySettings.supports(profile)) CabinFordLegacySettings.fields else emptySet())
            else emptySet()
        return (legacyMotion - verifiedNonMotion) + motionFields
    }

    val hasHondaTrip: Boolean get() = protocol in setOf("honda_0298", "honda_wc_0321")
    val ownsReadRequests: Boolean get() = hasHondaTrip || hasFordTires
    val hasFordTires: Boolean get() = protocol == "ford_0334"
    fun initialReadRequests(published: Set<Int>): List<Pair<Int, List<Int>>> = when {
        protocol == "honda_0298" -> CabinHondaTrip.requests(profile, published)
        hasFordTires -> buildList {
            if ((78..85).all { it in published }) add(0 to listOf(99, 0)) // eb requires two bytes; 0x63 publishes tires and trip data.
            if (CabinFordMedia.supports(profile) && setOf(113, 114, 115).all { it in published }) add(0 to listOf(101, 0))
            if (CabinFordSeats.supports(profile) && CabinFordSeats.fields.all { it in published }) add(0 to listOf(100, 0))
            if (CabinFordAmplifier.supports(profile) && CabinFordAmplifier.fields.all { it in published }) add(0 to listOf(98, 0))
            if (profile == 1114446 && (176..179).all { it in published }) {
                add(0 to listOf(66, 0))
                add(0 to listOf(105, 0))
            }
            if (CabinFordSettings.supports(profile) && CabinFordSettings.fields.all { it in published }) add(0 to listOf(40, 0))
        }
        else -> emptyList()
    }

    val choices: Map<FytVehicleChoice, Map<Int, String>> get() =
        (if (hasFordTires && CabinFordLegacySettings.supports(profile)) CabinFordLegacySettings.languages
            else CabinHondaLanguage.options(profile, protocol)).takeIf { it.isNotEmpty() }
            ?.let { mapOf(FytVehicleChoice.LANGUAGE to it) }.orEmpty()

    fun choiceFrame(choice: FytVehicleChoice, value: Int): Pair<Int, List<Int>>? = when (choice) {
        FytVehicleChoice.LANGUAGE -> if (hasFordTires) CabinFordLegacySettings.languageCommand(profile, value)
            else CabinHondaLanguage.command(profile, protocol, value)
    }

    val actions: Set<FytVehicleAction> get() = when {
        CabinGolfSettings.supports(protocol) -> setOf(FytVehicleAction.RESET_TRIP_SINCE_START, FytVehicleAction.RESET_TRIP_LONG_TERM)
        protocol == "honda_wc_0321" -> setOf(FytVehicleAction.RESET_HONDA_TRIP_HISTORY,
            FytVehicleAction.RESET_SERVICE_INTERVAL, FytVehicleAction.RESET_VEHICLE_SETTINGS, FytVehicleAction.CALIBRATE_TIRE_PRESSURE)
        protocol == "honda_0298" -> buildSet {
            if (CabinHondaAmplifier.supports(profile)) add(FytVehicleAction.RESET_HONDA_AMPLIFIER)
            if (CabinHondaCompass.supports(profile)) add(FytVehicleAction.CALIBRATE_COMPASS)
            if (CabinHondaTrip.supportsTripB(profile)) addAll(setOf(FytVehicleAction.RESET_SERVICE_INTERVAL,
                FytVehicleAction.RESET_VEHICLE_SETTINGS, FytVehicleAction.CALIBRATE_TIRE_PRESSURE, FytVehicleAction.INITIALIZE_PANORAMA))
        }
        else -> emptySet()
    }

    fun actionFrame(action: FytVehicleAction): Pair<Int, List<Int>>? {
        if (action !in actions) return null
        return when (action) {
            FytVehicleAction.RESET_TRIP_SINCE_START -> 84 to listOf(1)
            FytVehicleAction.RESET_TRIP_LONG_TERM -> 85 to listOf(1)
            FytVehicleAction.RESET_HONDA_TRIP_HISTORY -> 101 to listOf(3)
            FytVehicleAction.RESET_HONDA_AMPLIFIER -> 108 to listOf(10, 0)
            FytVehicleAction.RESET_SERVICE_INTERVAL -> 105 to if (protocol == "honda_0298") listOf(14, 0) else listOf(6, 1)
            FytVehicleAction.RESET_VEHICLE_SETTINGS -> 105 to if (protocol == "honda_0298") listOf(15, 0) else listOf(5, 1)
            FytVehicleAction.CALIBRATE_TIRE_PRESSURE -> if (protocol == "honda_0298") 105 to listOf(17, 0) else 108 to listOf(0)
            FytVehicleAction.CALIBRATE_COMPASS -> 103 to emptyList()
            FytVehicleAction.INITIALIZE_PANORAMA -> 105 to listOf(48, 0)
        }
    }

    fun widgetValues(legacy: Map<Int, Int>, raw: Map<Int, Int>): Map<Int, Int> = when (protocol) {
        "honda_0298" -> legacy.toMutableMap().apply {
            // These canonical widget IDs are from an earlier service layout.
            for ((canonical, actual) in mapOf(122 to 61, 123 to 62, 124 to 63)) {
                remove(canonical)
                raw[actual]?.takeIf { it in options(actual) }?.let { put(canonical, it) }
            }
        }
        "honda_wc_0321" -> legacy - setOf(201, 202) // Not published by July service x.
        else -> legacy
    }

    private fun label(en: String, pt: String) = if (portuguese) pt else en
    private val levels get() = listOf(label("Minimum", "Mínimo"), label("Low", "Baixo"), label("Medium", "Médio"), label("High", "Alto"), label("Maximum", "Máximo"))
    private val onOff get() = listOf(label("Off", "Desligado"), label("On", "Ligado"))

    internal fun options(field: Int): Map<Int, String> {
        if (protocol != "honda_0298") return emptyMap()
        val values = when (field) {
            58, 59 -> listOf(if (profile == 1966378) label("When charging", "Ao carregar") else label("On refueling", "Ao abastecer"), label("When ignition is off", "Ao desligar a ignição"), label("Manually", "Manualmente"))
            60 -> (-5..5).map(Int::toString)
            61, 73 -> levels
            62 -> listOf("0 s", "15 s", "30 s", "60 s")
            63 -> listOf("15 s", "30 s", "60 s")
            66 -> listOf("30 s", "60 s", "90 s")
            67 -> listOf(label("Driver door opens", "Ao abrir a porta do motorista"), label("Shift to P", "Ao colocar em P"), label("Ignition off", "Ao desligar a ignição"), onOff[0])
            68 -> listOf(label("Vehicle speed", "Pela velocidade"), label("Shift from P", "Ao sair de P"), onOff[0])
            65 -> if (CabinHondaRzcSettings.supports(profile)) listOf(label("Driver door", "Porta do motorista"), label("All doors", "Todas as portas")) else onOff
            71 -> listOf(label("All doors", "Todas as portas"), label("Driver door", "Porta do motorista"))
            74 -> listOf(levels[3], levels[2], levels[1])
            77 -> listOf("km/h · km", "mph · miles")
            81, 108 -> listOf(levels[1], levels[3])
            110 -> listOf(label("Any time", "A qualquer momento"), label("When unlocked", "Quando destravado"))
            85 -> listOf(label("Far", "Longe"), levels[2], label("Near", "Perto"))
            86 -> listOf(levels[2], label("Wide", "Amplo"), label("Warning only", "Apenas aviso"))
            114 -> listOf(onOff[0], label("Visual warning", "Aviso visual"), label("Visual and tactile warning", "Aviso visual e tátil"))
            in booleanFields -> onOff
            else -> return emptyMap()
        }
        return values.withIndex().associate { it.index to it.value }
    }

    override fun read(raw: Map<Int, Int>): List<FytSyuReading> = readPayloads(raw, emptyMap())

    override fun readPayloads(raw: Map<Int, Int>, payloads: Map<Int, FytRawSample>): List<FytSyuReading> = buildList {
        fun row(id: Int, text: String, dependencies: Set<Int> = setOf(id), checked: Boolean? = null) {
            val choices = options(id).takeIf { values -> values.keys.any { command(id, it, raw) != null } }.orEmpty()
            add(FytSyuReading(protocol, id, dependencies, text, checked, choices, fieldLabel(id)))
        }
        when (protocol) {
            "bagoo_audi" -> raw[1]?.takeIf { it in 0..6400 }?.let { row(1, String.format(java.util.Locale.ROOT, "%.4f km/h", it / 16.0f)) }
            "honda_0298" -> {
                (setOf(58,59,60,61,62,63,66,67,68,71,73,74,77,81,85,86,108,110,114) + booleanFields).sorted().forEach { id ->
                    if (CabinHondaRzcSettings.supports(profile) && id in CabinHondaRzcSettings.fields) return@forEach
                    val value = raw[id] ?: return@forEach
                    val choices = options(id)
                    choices[value]?.let { row(id, it, checked = if (id in booleanFields && !(id == 65 && CabinHondaRzcSettings.supports(profile))) value == 1 else null) }
                }
                val unit = raw[33]?.takeIf { it in 0..1 }
                if (unit != null) for (id in listOf(25,31,52)) raw[id]?.let { value ->
                    val text = when (value) {
                        -2 -> "LOW"; -3 -> "HIGH"
                        in 1..255 -> if (unit == 1) "$value°F" else "${value.toBigDecimal().multiply(java.math.BigDecimal("0.5")).stripTrailingZeros().toPlainString()}°C"
                        else -> null
                    }
                    text?.let { row(id, it, setOf(id,33)) }
                }
                val distance = raw[137]; val distanceUnit = raw[135]; val negative = raw[136]
                if (distance != null && distance in 0..65535 && distanceUnit != null && negative != null && distanceUnit in 0..1 && negative in 0..1) {
                    row(137, "${if (negative == 1) "-" else ""}$distance ${if (distanceUnit == 1) "mi" else "km"}", setOf(135,136,137))
                }
                for (id in 94..97) raw[id]?.takeIf { it in 0..3 }?.let { row(id, it.toString()) }
            }
        }
        if (protocol == "gm_wc_0036") {
            addAll(CabinGmWcReadings.read(raw, portuguese))
            addAll(CabinGmWcSettings.read(raw, portuguese))
        }
        if (protocol == "honda_wc_0321") addAll(CabinHondaWcSettings.read(profile, raw, portuguese))
        if (protocol == "honda_0298") {
            addAll(CabinHondaAmplifier.read(profile, raw, portuguese))
            addAll(CabinHondaRzcCamera.read(profile, raw, portuguese))
            addAll(CabinHondaPanorama.read(profile, raw, portuguese))
            addAll(CabinHondaCompass.read(profile, raw, portuguese))
            addAll(CabinHondaRzcSettings.read(profile, raw, portuguese))
        }
        if (hasFordTires) {
            addAll(CabinFordTires.read(profile, raw, portuguese))
            addAll(CabinFordTrip.read(profile, raw, portuguese))
            addAll(CabinFordAmplifier.read(profile, raw, portuguese))
            addAll(CabinFordSeats.read(profile, raw, portuguese))
            addAll(CabinFordMedia.read(profile, raw, payloads, portuguese))
            addAll(CabinFordSettings.read(profile, raw, portuguese))
            addAll(CabinFordLegacySettings.read(profile, raw, portuguese))
        }
        if (hasHondaTrip) addAll(CabinHondaTrip.read(profile, raw, portuguese))
        if (CabinGolfSettings.supports(protocol)) {
            addAll(CabinGolfSettings.read(profile, protocol, raw, portuguese))
            addAll(CabinGolfUnits.read(profile, protocol, raw, portuguese))
            addAll(CabinGolfLighting.read(protocol, raw, portuguese))
            addAll(CabinGolfAmbient.read(profile, protocol, raw, portuguese))
            addAll(CabinGolfHybrid.read(profile, raw, portuguese))
            addAll(CabinGolfRzcCharging.read(profile, raw, portuguese))
            addAll(CabinGolfWcCharging.read(profile, raw, portuguese))
        }
        val displayedFields = flatMap { it.fields }
        val ownFields = buildSet {
            addAll(displayedFields)
            if (protocol == "gm_wc_0036") addAll(CabinGmWcReadings.fields + CabinGmWcSettings.fields)
            if (protocol == "honda_0298") {
                addAll((58..87).toSet() + booleanFields + setOf(25, 31, 33, 52, 94, 95, 96, 97, 108, 110, 114, 135, 136, 137))
                if (CabinHondaAmplifier.supports(profile)) addAll(CabinHondaAmplifier.fields)
                if (CabinHondaRzcCamera.supports(profile)) addAll(CabinHondaRzcCamera.fields)
                if (CabinHondaPanorama.supports(profile)) addAll(CabinHondaPanorama.fields)
                if (CabinHondaCompass.supports(profile)) addAll(CabinHondaCompass.fields)
                if (CabinHondaRzcSettings.supports(profile)) addAll(CabinHondaRzcSettings.fields)
            }
            if (protocol == "honda_wc_0321") addAll(CabinHondaWcSettings.fields)
            if (hasFordTires) addAll(CabinFordTires.fields + CabinFordTrip.fields(profile))
            if (hasFordTires && CabinFordMedia.supports(profile)) addAll(CabinFordMedia.fields)
            if (hasFordTires && CabinFordSeats.supports(profile)) addAll(CabinFordSeats.fields)
            if (hasFordTires && CabinFordAmplifier.supports(profile)) addAll(CabinFordAmplifier.fields)
            if (hasFordTires && CabinFordSettings.supports(profile)) addAll(CabinFordSettings.fields)
            if (hasFordTires && CabinFordLegacySettings.supports(profile)) addAll(CabinFordLegacySettings.fields)
            if (hasHondaTrip) addAll(CabinHondaTrip.fields(profile))
            if (CabinGolfSettings.supports(protocol)) {
                addAll(CabinGolfSettings.fields(protocol))
                addAll(CabinGolfUnits.fields(profile))
                addAll(CabinGolfLighting.fields)
                addAll(CabinGolfAmbient.fields)
                addAll(CabinGolfHybrid.fields(profile))
                addAll(CabinGolfRzcCharging.fields(profile))
                addAll(CabinGolfWcCharging.fields(profile))
            }
        }
        addAll(enums?.read(raw).orEmpty().filter { row -> row.fields.none { it in ownFields } })
    }

    fun releaseFrame(field: Int): Pair<Int, List<Int>>? = if (hasFordTires) CabinFordSeats.releaseFrame(profile, field) ?: CabinFordMedia.releaseFrame(profile, field) else null

    internal fun command(field: Int, value: Int, current: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (protocol == "gm_wc_0036") return CabinGmWcSettings.command(field, value, current)
        if (hasFordTires && field in CabinFordMedia.fields && CabinFordMedia.supports(profile))
            return CabinFordMedia.command(profile, field, value, current)
        if (hasFordTires && field in CabinFordSeats.fields && CabinFordSeats.supports(profile))
            return CabinFordSeats.command(profile, field, value, current)
        if (hasFordTires && field in CabinFordAmplifier.fields && CabinFordAmplifier.supports(profile))
            return CabinFordAmplifier.command(profile, field, value, current)
        if (hasFordTires) return if (CabinFordSettings.supports(profile)) CabinFordSettings.command(profile, field, value, current)
            else CabinFordLegacySettings.command(profile, field, value, current)
        if (protocol == "honda_wc_0321") return CabinHondaWcSettings.command(profile, field, value, current)
        if (CabinGolfSettings.supports(protocol)) return if (field in CabinGolfUnits.fields(profile))
            CabinGolfUnits.command(profile, protocol, field, value, current)
        else if (field in CabinGolfHybrid.fields(profile)) CabinGolfHybrid.command(profile, field, value, current)
        else if (field in CabinGolfRzcCharging.fields(profile)) CabinGolfRzcCharging.command(profile, field, value, current)
        else if (field in CabinGolfWcCharging.fields(profile)) CabinGolfWcCharging.command(profile, field, value, current)
        else if (field in CabinGolfLighting.fields) CabinGolfLighting.command(protocol, field, value, current)
        else if (field in CabinGolfAmbient.fields) CabinGolfAmbient.command(profile, protocol, field, value, current)
        else CabinGolfSettings.command(profile, protocol, field, value, current)
        if (protocol == "honda_0298" && CabinHondaRzcSettings.supports(profile) && field in CabinHondaRzcSettings.fields) return CabinHondaRzcSettings.command(profile, field, value, current)
        if (protocol == "honda_0298" && field in CabinHondaCompass.fields) return CabinHondaCompass.command(profile, field, value, current)
        if (protocol == "honda_0298" && field in CabinHondaPanorama.fields) return CabinHondaPanorama.command(profile, field, value, current)
        if (protocol == "honda_0298" && field in CabinHondaRzcCamera.fields) return CabinHondaRzcCamera.command(profile, field, value, current)
        if (protocol == "honda_0298" && field in CabinHondaAmplifier.fields) return CabinHondaAmplifier.command(profile, field, value, current)
        if (protocol != "honda_0298" || value !in options(field) || current[field] !in options(field)) return null
        if (field in setOf(109,112,113) && profile !in bnrToggleProfiles) return null
        val parameter = if (field == 114) { if (profile == 983338) 41 else 36 } else commandParameters[field] ?: return null
        return 105 to listOf(parameter, value)
    }

    private fun fieldLabel(id: Int): String? = when (id) {
        58 -> label("Trip A reset", "Zerar viagem A")
        59 -> label("Trip B reset", "Zerar viagem B")
        60 -> label("Outside temperature adjustment", "Ajuste da temperatura externa")
        61 -> label("Automatic headlight sensitivity", "Sensibilidade dos faróis automáticos")
        62 -> label("Headlight off delay", "Tempo para desligar os faróis")
        63 -> label("Interior light delay", "Tempo da luz interna")
        64 -> label("Lock confirmation", "Confirmação de travamento")
        65 -> label("Key and remote unlock mode", "Modo de destravamento pela chave")
        66 -> label("Automatic relock delay", "Tempo para travar novamente")
        67 -> label("Automatic unlocking", "Destravamento automático")
        68 -> label("Automatic locking", "Travamento automático")
        69 -> label("Keyless beep", "Som da chave presencial")
        70 -> label("Remote engine start", "Partida remota")
        71 -> label("Unlock doors", "Portas destravadas")
        72 -> label("Keyless light confirmation", "Confirmação luminosa da chave presencial")
        73 -> label("Interior illumination", "Iluminação interna")
        74 -> label("Warning volume", "Volume dos avisos")
        75 -> label("Fuel economy illumination", "Iluminação de economia de combustível")
        76 -> label("New message notification", "Aviso de nova mensagem")
        77 -> label("Distance and speed units", "Unidades de distância e velocidade")
        78 -> label("Tachometer", "Conta-giros")
        79 -> label("Walk-away locking", "Travar ao se afastar")
        80 -> label("Headlights with wipers", "Faróis com limpadores")
        81 -> label("Alarm volume", "Volume do alarme")
        82 -> label("Automatic engine stop", "Parada automática do motor")
        83 -> label("Vehicle ahead alert", "Aviso de veículo à frente")
        84 -> label("Lane assist pause alert", "Aviso de pausa do assistente de faixa")
        85 -> label("Forward warning distance", "Distância do aviso de colisão")
        86 -> label("Lane departure warning", "Aviso de saída de faixa")
        87 -> label("Tachometer setting", "Configuração do conta-giros")
        108 -> label("Keyless beep volume", "Volume da chave presencial")
        109 -> label("Reverse beep", "Aviso sonoro de ré")
        110 -> label("Remote tailgate opening", "Abertura remota do porta-malas")
        111 -> label("Power tailgate", "Porta-malas elétrico")
        112 -> label("Driving position memory", "Memória da posição de dirigir")
        113 -> label("Seat entry assistance", "Auxílio de entrada pelo banco")
        114 -> label("Driver attention warning", "Aviso de atenção do motorista")
        149 -> label("Driver fatigue warning", "Aviso de fadiga")
        150 -> label("AWD information", "Informações da tração integral")
        137 -> label("Oil service distance", "Distância até a troca de óleo")
        else -> null
    }

    companion object {
        private val commandParameters = mapOf(58 to 2, 59 to 3, 60 to 0, 61 to 6, 62 to 5, 63 to 4,
            64 to 10, 65 to 9, 66 to 11, 67 to 8, 68 to 7, 69 to 13, 70 to 24, 71 to 25,
            72 to 26, 73 to 27, 74 to 18, 75 to 19, 76 to 20, 77 to 21, 78 to 22, 79 to 23,
            80 to 28, 81 to 30, 82 to 29, 83 to 32, 84 to 33, 85 to 31, 86 to 34, 87 to 35,
            109 to 36, 110 to 37, 111 to 38, 112 to 39, 113 to 40, 149 to 42, 150 to 43)
        private val bnrToggleProfiles = setOf(393514,459050,524586,590122,655658,721194,1048874,1114410,2031914,2097450,2621738)
        private val booleanFields = setOf(64,65,69,70,72,75,76,78,79,80,82,83,84,87,109,111,112,113,149,150)
    }
}
