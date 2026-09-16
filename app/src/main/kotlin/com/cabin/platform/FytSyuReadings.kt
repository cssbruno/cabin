package com.cabin.platform

data class FytSyuReading(
    val screen: String,
    val viewId: Int,
    val fields: Set<Int>,
    val text: String? = null,
    val checked: Boolean? = null,
    val options: Map<Int, String> = emptyMap(),
    val label: String? = null,
)

internal data class FytSyuClientFields(
    val callback: String = "",
    val names: Map<Int, List<String>> = emptyMap(),
    val screens: Set<String> = emptySet(),
    val display: FytSyuDisplay? = null,
)

internal fun interface FytSyuDisplay {
    fun read(raw: Map<Int, Int>): List<FytSyuReading>
    fun readPayloads(raw: Map<Int, Int>, payloads: Map<Int, FytRawSample>): List<FytSyuReading> = read(raw)
}

/** Explicit actions with no invented feedback field or arbitrary command input. */
enum class FytVehicleAction {
    RESET_TRIP_SINCE_START, RESET_TRIP_LONG_TERM, RESET_HONDA_TRIP_HISTORY, RESET_HONDA_AMPLIFIER, RESET_SERVICE_INTERVAL, RESET_VEHICLE_SETTINGS, CALIBRATE_TIRE_PRESSURE, CALIBRATE_COMPASS, INITIALIZE_PANORAMA;

    fun title(portuguese: Boolean): String = when (this) {
        INITIALIZE_PANORAMA -> if (portuguese) "Inicializar imagem panorâmica" else "Initialize panoramic image"
        CALIBRATE_COMPASS -> if (portuguese) "Calibrar bússola" else "Calibrate compass"
        RESET_TRIP_SINCE_START -> if (portuguese) "Zerar dados desde a partida" else "Reset data since start"
        RESET_SERVICE_INTERVAL -> if (portuguese) "Zerar intervalo de manutenção" else "Reset service interval"
        RESET_VEHICLE_SETTINGS -> if (portuguese) "Restaurar configurações do veículo" else "Reset vehicle settings"
        CALIBRATE_TIRE_PRESSURE -> if (portuguese) "Calibrar monitor de pressão dos pneus" else "Calibrate tire pressure monitor"
        RESET_HONDA_AMPLIFIER -> if (portuguese) "Restaurar amplificador original" else "Reset factory amplifier"
        RESET_HONDA_TRIP_HISTORY -> if (portuguese) "Apagar histórico de viagens" else "Clear trip history"
        RESET_TRIP_LONG_TERM -> if (portuguese) "Zerar dados de longo prazo" else "Reset long-term driving data"
    }

    fun confirmation(portuguese: Boolean): String = when (this) {
        INITIALIZE_PANORAMA -> if (portuguese) "Iniciar a inicialização da imagem panorâmica?" else "Start panoramic image initialization?"
        CALIBRATE_COMPASS -> if (portuguese) "Iniciar a calibração da bússola do veículo?" else "Start vehicle compass calibration?"
        RESET_TRIP_SINCE_START, RESET_TRIP_LONG_TERM, RESET_HONDA_TRIP_HISTORY ->
            if (portuguese) "Apagar estes dados de viagem?" else "Clear these driving data?"
        RESET_HONDA_AMPLIFIER -> if (portuguese) "Restaurar os ajustes do amplificador original?" else "Restore factory amplifier settings?"
        RESET_SERVICE_INTERVAL -> if (portuguese) "Zerar o contador de manutenção do veículo?" else "Reset the vehicle's service counter?"
        RESET_VEHICLE_SETTINGS -> if (portuguese) "Restaurar as configurações de fábrica do veículo?" else "Restore the vehicle's factory settings?"
        CALIBRATE_TIRE_PRESSURE -> if (portuguese) "Iniciar a calibração do monitor de pressão dos pneus?" else "Start tire pressure monitor calibration?"
    }

    fun confirmLabel(portuguese: Boolean): String = if (this == INITIALIZE_PANORAMA) {
        if (portuguese) "Iniciar" else "Start"
    } else if (this == CALIBRATE_TIRE_PRESSURE || this == CALIBRATE_COMPASS)
        if (portuguese) "Calibrar" else "Calibrate"
    else if (portuguese) "Restaurar" else "Reset"

}
