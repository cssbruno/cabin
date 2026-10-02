package com.cabin.ui.settings

import android.content.Context
import android.os.SystemClock
import com.cabin.platform.ProjectionPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun remainingTrialSeconds(deadlineMs: Long, nowMs: Long): Int =
    ((deadlineMs - nowMs).coerceIn(0L, 45_000L).plus(999L) / 1000L).toInt()

/** Journal is committed before experimental settings. A new process always restores an unconfirmed trial. */
class VideoSettingsTrial private constructor(private val context: Context) {
    data class State(val remainingSeconds: Int = 0, val reverted: Long = 0, val active: Boolean = false, val error: Boolean = false)
    private val prefs = context.getSharedPreferences("video_settings_trial_v1", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var timer: Job? = null
    private var deadlineElapsedMs = 0L
    private val reverting = java.util.concurrent.atomic.AtomicBoolean(false)
    private var rollback: (() -> Unit)? = null
    private var previousManager: com.cabin.CabinManager? = null
    private var requiresNewRenderer = true
    fun pictureUsable(manager: com.cabin.CabinManager): Boolean = mutableState.value.active && !mutableState.value.error &&
        (!requiresNewRenderer || previousManager !== manager) && manager.pictureDelivery().getOrElse(2) { -1 } > 0
    fun onRollback(callback: (() -> Unit)?) { rollback = callback }

    /** Call before constructing the first adapter preferences/configuration in a process. */
    internal fun recoverSynchronously(): Pair<String, Int>? {
        if (prefs.all["pending"] != true || mutableState.value.active) return null
        val oldResolution = prefs.all["resolution"] as? String ?: "AUTO"
        val oldFps = prefs.all["fps"] as? Int ?: 30
        context.getSharedPreferences("carlink_adapter_config_sync_cache", Context.MODE_PRIVATE).edit()
            .putString("video_resolution", oldResolution).putInt("fps", oldFps).commit()
        restoreBezel()
        // Keep the marker until the durable DataStore repair completes.
        return oldResolution to oldFps
    }
    internal fun finishStartupRecovery() { prefs.edit().remove("pending").commit() }

    fun begin(preferences: AdapterConfigPreference, source: com.cabin.CabinManager? = null, requireNewRenderer: Boolean = true): Boolean {
        if (mutableState.value.active || prefs.all["pending"] == true) return false
        val presentation = ProjectionPreferences.getInstance(context)
        val saved = prefs.edit().putBoolean("pending", true)
            .putString("resolution", preferences.getVideoResolutionSync().toStorageString())
            .putInt("fps", preferences.getFpsSync().fps)
            .putInt("driver_slot", presentation.driverSlot)
            .putInt("bezel", presentation.state.value.bezelPercent).commit()
        if (!saved) { mutableState.value = mutableState.value.copy(error = true); return false }
        previousManager = source
        requiresNewRenderer = requireNewRenderer
        mutableState.value = mutableState.value.copy(active = true, remainingSeconds = 45, error = false)
        timer?.cancel()
        deadlineElapsedMs = SystemClock.elapsedRealtime() + 45_000L
        timer = scope.launch {
            while (true) {
                val remaining = remainingTrialSeconds(deadlineElapsedMs, SystemClock.elapsedRealtime())
                mutableState.value = mutableState.value.copy(remainingSeconds = remaining)
                if (remaining == 0) { revert(); break }
                delay(1000)
            }
        }
        return true
    }
    fun keep(pictureUsable: Boolean): Boolean {
        if (!mutableState.value.active || !pictureUsable || reverting.get() || mutableState.value.error) return false
        if (remainingTrialSeconds(deadlineElapsedMs, SystemClock.elapsedRealtime()) == 0) { revert(); return false }
        if (!prefs.edit().remove("pending").commit()) return false
        timer?.cancel(); timer = null
        mutableState.value = mutableState.value.copy(active = false, remainingSeconds = 0)
        previousManager = null
        return true
    }
    fun revert() {
        if (!mutableState.value.active || !reverting.compareAndSet(false, true)) return
        timer?.cancel(); timer = null
        // A separate child survives cancellation of the countdown or dialog.
        scope.launch {
            try {
            val config = AdapterConfigPreference.getInstance(context)
            withContext(Dispatchers.IO) {
                config.setVideoResolution(VideoResolutionConfig.fromStorageString(prefs.all["resolution"] as? String))
                config.setFps(FpsConfig.entries.firstOrNull { it.fps == prefs.all["fps"] } ?: FpsConfig.DEFAULT)
                restoreBezel()
                check(prefs.edit().remove("pending").commit())
            }
            mutableState.value = mutableState.value.copy(active = false, remainingSeconds = 0, reverted = mutableState.value.reverted + 1, error = false)
            reverting.set(false)
            previousManager = null
            val callback = rollback
            if (callback != null) callback() else com.cabin.background.CabinProjectionService.reconfigureHeadlessAfterVideoTrial()
            } catch (error: Exception) {
                reverting.set(false)
                mutableState.value = mutableState.value.copy(error = true)
                com.cabin.logging.logWarn("Video trial restore failed: ${error.javaClass.simpleName}", tag = "VIDEO_TRIAL")
            }
        }
    }
    private fun restoreBezel() {
        val presentation = ProjectionPreferences.getInstance(context)
        check(presentation.restoreBezelForDriver(prefs.all["driver_slot"] as? Int ?: presentation.driverSlot, prefs.all["bezel"] as? Int ?: 0))
    }
    companion object {
        @Volatile private var instance: VideoSettingsTrial? = null
        fun get(context: Context): VideoSettingsTrial = instance ?: synchronized(this) {
            instance ?: VideoSettingsTrial(context.applicationContext).also { instance = it }
        }
    }
}
