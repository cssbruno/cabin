package com.cabin.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale

/** Recomposer-wide policy: Material transitions and child dialogs inherit the same scale. */
internal class CabinMotionDurationScale(context: Context) : MotionDurationScale, AutoCloseable {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences(ACCESSIBILITY_FILE, Context.MODE_PRIVATE)
    private var scale by mutableFloatStateOf(readScale())
    override val scaleFactor: Float get() = scale
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "reduced_motion") scale = readScale()
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { scale = readScale() }
    }
    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        runCatching { application.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer) }
    }
    private fun readScale(): Float {
        if (preferences.all["reduced_motion"] == true) return 0f
        val system = runCatching { Settings.Global.getFloat(application.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        return if (system.isFinite() && system >= 0f) system else 1f
    }
    override fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        runCatching { application.contentResolver.unregisterContentObserver(observer) }
    }
}
