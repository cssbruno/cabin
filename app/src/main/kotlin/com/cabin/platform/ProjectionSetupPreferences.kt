package com.cabin.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProjectionSetupStep { USB, PHONE, AUDIO, PERMISSIONS, COMPLETE }

enum class ProjectionSetupCheck { AUDIO, MICROPHONE, TOUCH }

data class ProjectionSetupProgress(
    val step: ProjectionSetupStep = ProjectionSetupStep.USB,
    val completed: Boolean = false,
    val verified: Set<ProjectionSetupCheck> = emptySet(),
)

/** Guide progress only: visiting or completing setup never changes connection intent. */
class ProjectionSetupPreferences internal constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableProgress = MutableStateFlow(read())
    val progress = mutableProgress.asStateFlow()

    fun visit(step: ProjectionSetupStep) {
        preferences.edit().putString("step", step.name).apply()
        mutableProgress.value = read()
    }

    fun complete() {
        preferences.edit().putBoolean("completed", true).putString("step", ProjectionSetupStep.USB.name).apply()
        mutableProgress.value = read()
    }

    fun verify(check: ProjectionSetupCheck, verified: Boolean) {
        val next = mutableProgress.value.verified.toMutableSet()
        if (verified) next.add(check) else next.remove(check)
        preferences.edit().putStringSet("verified", next.map { it.name }.toSet()).apply()
        mutableProgress.value = read()
    }

    /** Resets only guide progress and manual checks, never saved adapter or connection settings. */
    fun restart() {
        preferences.edit().clear().apply()
        mutableProgress.value = read()
    }

    private fun read() = ProjectionSetupProgress(
        step = ProjectionSetupStep.entries.firstOrNull { it.name == preferences.all["step"] } ?: ProjectionSetupStep.USB,
        completed = preferences.all["completed"] as? Boolean ?: false,
        verified = (preferences.all["verified"] as? Set<*>)?.mapNotNull { value ->
            ProjectionSetupCheck.entries.firstOrNull { it.name == value }
        }?.toSet().orEmpty(),
    )

    companion object {
        internal const val PREFERENCES_NAME = "projection_setup_v1"
        @Volatile private var instance: ProjectionSetupPreferences? = null

        fun get(context: Context): ProjectionSetupPreferences =
            instance ?: synchronized(this) { instance ?: ProjectionSetupPreferences(context).also { instance = it } }
    }
}

/** A stale connected snapshot must never permit a microphone or speaker test. */
internal fun projectionDiagnosticsAllowed(
    parked: Boolean,
    snapshot: ProjectionReadinessSnapshot,
    sessionIdle: Boolean,
): Boolean = parked && sessionIdle && !snapshot.sessionRequested &&
    snapshot.state == com.cabin.CabinManager.State.DISCONNECTED
