package com.cabin.platform

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ClimateNoticeMode { SUMMARY, PANEL, OFF }

enum class ProjectionControlSide { LEFT, RIGHT }

data class ProjectionPreferencesState(
    val focusControls: Boolean = true,
    val vehicleHud: Boolean = false,
    val climateNoticeMode: ClimateNoticeMode = ClimateNoticeMode.SUMMARY,
    val returnWhenReady: Boolean = true,
    val controlSide: ProjectionControlSide = ProjectionControlSide.RIGHT,
    val controlHideSeconds: Int = 0,
    val blackoutMinutes: Int = 0,
    val toolOrder: List<String> = listOf("phone", "settings", "blackout"),
    val bezelPercent: Int = 0,
)

/** Presentation is scoped to the active driver, with legacy app-wide values as migration defaults. */
class ProjectionPreferences internal constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val drivers = context.applicationContext.getSharedPreferences("teyes_features_v1", Context.MODE_PRIVATE)
    private var activeSlot = (drivers.all["active"] as? Int ?: 0).coerceIn(0, 2)
    private val driverListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "active" || key == null) {
            activeSlot = (drivers.all["active"] as? Int ?: 0).coerceIn(0, 2)
            mutableState.value = readState()
        }
    }
    private val mutableState = MutableStateFlow(readState())
    private val restoreListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutableState.value = readState() }
    init {
        drivers.registerOnSharedPreferenceChangeListener(driverListener)
        preferences.registerOnSharedPreferenceChangeListener(restoreListener)
    }
    val state: StateFlow<ProjectionPreferencesState> = mutableState.asStateFlow()

    fun setControlHideSeconds(value: Int) { update { putInt(key("hide_seconds"), value.takeIf { it in listOf(0, 5, 10, 20) } ?: 0) } }
    fun setBlackoutMinutes(value: Int) { update { putInt(key("blackout_minutes"), value.takeIf { it in listOf(0, 1, 5, 15, 30) } ?: 0) } }
    fun setToolOrder(value: List<String>) { update { putString(key("tool_order"), value.filter { it in listOf("phone", "settings", "blackout") }.distinct().take(3).joinToString(",")) } }
    internal val driverSlot: Int get() = (drivers.all["active"] as? Int ?: 0).coerceIn(0, 2)
    internal fun restoreBezelForDriver(slot: Int, value: Int): Boolean {
        val saved = preferences.edit().putInt("driver.${slot.coerceIn(0, 2)}.bezel", value.coerceIn(0, 10)).commit()
        mutableState.value = readState()
        return saved
    }
    fun setBezelPercent(value: Int) { update { putInt(key("bezel"), value.coerceIn(0, 10)) } }
    private fun key(value: String) = "driver.$activeSlot.$value"

    fun setFocusControls(value: Boolean) {
        update { putBoolean(key(KEY_FOCUS_CONTROLS), value) }
    }

    fun setVehicleHud(value: Boolean) {
        update { putBoolean(key(KEY_VEHICLE_HUD), value) }
    }

    fun setClimateNoticeMode(mode: ClimateNoticeMode) {
        update { putString(key(KEY_CLIMATE_NOTICE_MODE), mode.name) }
    }

    fun setReturnWhenReady(value: Boolean) {
        update { putBoolean(key(KEY_RETURN_WHEN_READY), value) }
    }

    fun setControlSide(side: ProjectionControlSide) {
        update { putString(key(KEY_CONTROL_SIDE), side.name) }
    }

    /** Persist one coherent presentation state after the backup has been validated and confirmed. */
    @Synchronized
    internal fun replace(state: ProjectionPreferencesState): Boolean {
        val persisted =
            preferences.edit()
                .putBoolean(key(KEY_FOCUS_CONTROLS), state.focusControls)
                .putBoolean(key(KEY_VEHICLE_HUD), state.vehicleHud)
                .putString(key(KEY_CLIMATE_NOTICE_MODE), state.climateNoticeMode.name)
                .putBoolean(key(KEY_RETURN_WHEN_READY), state.returnWhenReady)
                .putString(key(KEY_CONTROL_SIDE), state.controlSide.name)
                .putInt(key("hide_seconds"), state.controlHideSeconds)
                .putInt(key("blackout_minutes"), state.blackoutMinutes)
                .putString(key("tool_order"), state.toolOrder.joinToString(","))
                .putInt(key("bezel"), state.bezelPercent.coerceIn(0, 10))
                .commit()
        mutableState.value = readState()
        return persisted
    }

    @Synchronized
    private fun update(edit: SharedPreferences.Editor.() -> Unit) {
        // apply() updates this process's preference map synchronously and persists off
        // the UI thread. Publish after that map update so observers see one coherent state.
        preferences.edit().apply(edit).apply()
        mutableState.value = readState()
    }

    private fun readState(): ProjectionPreferencesState {
        // Typed getters throw on corrupt/older values. Unknown types or modes retain
        // the quiet default presentation rather than inventing a migration.
        val all = preferences.all
        val values = all.toMutableMap()
        all.filterKeys { it.startsWith("driver.$activeSlot.") }.forEach { (key, value) -> values[key.removePrefix("driver.$activeSlot.")] = value }
        return ProjectionPreferencesState(
            controlHideSeconds = (values["hide_seconds"] as? Int)?.takeIf { it in listOf(0, 5, 10, 20) } ?: 0,
            blackoutMinutes = (values["blackout_minutes"] as? Int)?.takeIf { it in listOf(0, 1, 5, 15, 30) } ?: 0,
            toolOrder = (values["tool_order"] as? String)?.split(",")?.filter { it in listOf("phone", "settings", "blackout") }?.distinct() ?: listOf("phone", "settings", "blackout"),
            bezelPercent = (values["bezel"] as? Int ?: 0).coerceIn(0, 10),
            focusControls = values[KEY_FOCUS_CONTROLS] as? Boolean ?: true,
            vehicleHud = values[KEY_VEHICLE_HUD] as? Boolean ?: false,
            climateNoticeMode =
                ClimateNoticeMode.entries.firstOrNull { it.name == values[KEY_CLIMATE_NOTICE_MODE] }
                    ?: ClimateNoticeMode.SUMMARY,
            returnWhenReady = values[KEY_RETURN_WHEN_READY] as? Boolean ?: true,
            controlSide =
                ProjectionControlSide.entries.firstOrNull { it.name == values[KEY_CONTROL_SIDE] }
                    ?: ProjectionControlSide.RIGHT,
        )
    }

    companion object {
        internal const val PREFERENCES_NAME = "projection_presentation_v1"
        private const val KEY_FOCUS_CONTROLS = "focus_controls"
        private const val KEY_VEHICLE_HUD = "vehicle_hud"
        private const val KEY_CLIMATE_NOTICE_MODE = "climate_notice_mode"
        private const val KEY_RETURN_WHEN_READY = "return_when_ready"
        private const val KEY_CONTROL_SIDE = "control_side"

        @Volatile private var instance: ProjectionPreferences? = null

        fun getInstance(context: Context): ProjectionPreferences =
            instance ?: synchronized(this) {
                instance ?: ProjectionPreferences(context.applicationContext).also { instance = it }
            }
    }
}
