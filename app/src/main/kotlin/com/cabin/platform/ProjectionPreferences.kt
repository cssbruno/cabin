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
)

/** App-wide presentation choices, stored separately from driver profiles and included in schema-2 backups. */
class ProjectionPreferences internal constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(readState())
    val state: StateFlow<ProjectionPreferencesState> = mutableState.asStateFlow()

    fun setFocusControls(value: Boolean) {
        update { putBoolean(KEY_FOCUS_CONTROLS, value) }
    }

    fun setVehicleHud(value: Boolean) {
        update { putBoolean(KEY_VEHICLE_HUD, value) }
    }

    fun setClimateNoticeMode(mode: ClimateNoticeMode) {
        update { putString(KEY_CLIMATE_NOTICE_MODE, mode.name) }
    }

    fun setReturnWhenReady(value: Boolean) {
        update { putBoolean(KEY_RETURN_WHEN_READY, value) }
    }

    fun setControlSide(side: ProjectionControlSide) {
        update { putString(KEY_CONTROL_SIDE, side.name) }
    }

    /** Persist one coherent presentation state after the backup has been validated and confirmed. */
    @Synchronized
    internal fun replace(state: ProjectionPreferencesState): Boolean {
        val persisted =
            preferences.edit()
                .putBoolean(KEY_FOCUS_CONTROLS, state.focusControls)
                .putBoolean(KEY_VEHICLE_HUD, state.vehicleHud)
                .putString(KEY_CLIMATE_NOTICE_MODE, state.climateNoticeMode.name)
                .putBoolean(KEY_RETURN_WHEN_READY, state.returnWhenReady)
                .putString(KEY_CONTROL_SIDE, state.controlSide.name)
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
        val values = preferences.all
        return ProjectionPreferencesState(
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
