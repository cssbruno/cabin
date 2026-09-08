package com.cabin.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.roundToInt

enum class MeasurementUnit { SYSTEM, METRIC, IMPERIAL }

/** Display units only. Vehicle and navigation inputs retain their original metric values. */
class MeasurementPreferences internal constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableUnit = MutableStateFlow(read())
    val unit = mutableUnit.asStateFlow()

    fun select(value: MeasurementUnit) {
        preferences.edit().putString(KEY_UNIT, value.name).apply()
        mutableUnit.value = value
    }

    /** Called after a validated backup has been confirmed, from the IO dispatcher. */
    internal fun replace(value: MeasurementUnit): Boolean {
        val persisted = preferences.edit().putString(KEY_UNIT, value.name).commit()
        mutableUnit.value = value
        return persisted
    }

    private fun read(): MeasurementUnit =
        MeasurementUnit.entries.firstOrNull { it.name == preferences.all[KEY_UNIT] } ?: MeasurementUnit.SYSTEM

    companion object {
        internal const val PREFERENCES_NAME = "measurement_presentation_v1"
        private const val KEY_UNIT = "unit"
        @Volatile private var instance: MeasurementPreferences? = null

        fun get(context: Context): MeasurementPreferences =
            instance ?: synchronized(this) {
                instance ?: MeasurementPreferences(context).also { instance = it }
            }
    }
}

/** One conversion policy for native readings and phone-supplied guidance. */
object MeasurementFormatter {
    const val METERS_PER_MILE = 1609.344
    private const val METERS_PER_FOOT = 0.3048
    private val imperialCountries = setOf("US", "GB", "MM", "LR")

    fun isImperial(unit: MeasurementUnit, locale: Locale = Locale.getDefault()): Boolean =
        when (unit) {
            MeasurementUnit.SYSTEM -> locale.country.uppercase(Locale.ROOT) in imperialCountries
            MeasurementUnit.METRIC -> false
            MeasurementUnit.IMPERIAL -> true
        }

    fun speedValue(kph: Double, unit: MeasurementUnit, locale: Locale = Locale.getDefault()): Double =
        if (isImperial(unit, locale)) kph / 1.609344 else kph

    fun speedLabel(unit: MeasurementUnit, locale: Locale = Locale.getDefault()): String =
        if (isImperial(unit, locale)) "mph" else "km/h"

    fun speed(kph: Double, unit: MeasurementUnit, locale: Locale = Locale.getDefault()): String =
        "${speedValue(kph, unit, locale).roundToInt()} ${speedLabel(unit, locale)}"

    fun distance(meters: Double, unit: MeasurementUnit, locale: Locale = Locale.getDefault()): String {
        val positive = meters.coerceAtLeast(0.0)
        return if (isImperial(unit, locale)) {
            if (positive < 305.0) "${(positive / METERS_PER_FOOT).roundToInt()} ft"
            else String.format(locale, "%.1f mi", positive / METERS_PER_MILE)
        } else {
            if (positive < 1000.0) "${positive.roundToInt()} m"
            else String.format(locale, "%.1f km", positive / 1000.0)
        }
    }

    /** Maintenance distances can be negative when service is overdue. */
    fun serviceDistance(value: Int, sourceMiles: Boolean, unit: MeasurementUnit, locale: Locale = Locale.getDefault()): String {
        val kilometers = if (sourceMiles) value * 1.609344 else value.toDouble()
        val imperial = isImperial(unit, locale)
        val converted = if (imperial) kilometers / 1.609344 else kilometers
        return "${converted.roundToInt()} ${if (imperial) "mi" else "km"}"
    }
}
