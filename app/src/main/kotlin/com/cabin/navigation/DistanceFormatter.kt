package com.cabin.navigation

import androidx.car.app.model.Distance
import com.cabin.platform.MeasurementFormatter
import com.cabin.platform.MeasurementUnit
import java.util.Locale

/**
 * Converts raw meter values from iAP2 NaviJSON to locale-appropriate Distance objects.
 *
 * iAP2 always sends distances in meters (NaviRemainDistance, NaviDistanceToDestination;
 * see usb_protocol.md:352-354). The Car App Library Distance.create() requires an
 * explicit display unit — it does NOT auto-convert between feet/miles/meters/km — so
 * this object encodes the locale + threshold policy in one place.
 *
 * Imperial (US/UK/MM/LR): < 1000 ft (305m) → feet, >= 1000 ft → miles
 * Metric (everywhere else): < 1000m → meters, >= 1000m → kilometers
 *
 * UNIT_MILES_P1 / UNIT_KILOMETERS_P1: the "_P1" suffix is the Car App Library's
 * convention for "one decimal place in the rendered string" (e.g., "0.2 mi").
 *
 * SYSTEM reads the current region on each call. A manual preference remains unchanged
 * when the app language changes. An unknown region defaults to metric.
 *
 * A manual unit preference takes precedence over the locale without changing raw meters.
 */
object DistanceFormatter {
    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_THRESHOLD = 305 // ~1000 feet
    private const val KM_THRESHOLD = 1000

    /**
     * Convert raw meters to the selected measurement units for display.
     *
     * @param meters Raw distance in meters from NaviJSON
     * @return Distance object with correct display unit and converted value
     */
    fun toDistance(meters: Int, unit: MeasurementUnit = MeasurementUnit.SYSTEM, locale: Locale = Locale.getDefault()): Distance =
        if (MeasurementFormatter.isImperial(unit, locale)) {
            toImperial(meters)
        } else {
            toMetric(meters)
        }

    private fun toImperial(meters: Int): Distance =
        if (meters < FEET_THRESHOLD) {
            // Display in feet, rounded to nearest 50 for readability.
            // Floor of 50 ft: at arrival/very-close (0-24m), we show "50 ft" rather
            // than "0 ft". The cluster's terminal-maneuver glyph carries the real
            // semantic (arrived / destination), so a 50-ft floor is a non-issue.
            val feet = meters / METERS_PER_FOOT
            val rounded = (Math.round(feet / 50.0) * 50).coerceAtLeast(50).toDouble()
            Distance.create(rounded, Distance.UNIT_FEET)
        } else {
            // Display in miles with 1 decimal place
            val miles = meters / METERS_PER_MILE
            Distance.create(miles, Distance.UNIT_MILES_P1)
        }

    private fun toMetric(meters: Int): Distance =
        if (meters < KM_THRESHOLD) {
            // Display in meters, rounded to nearest 50 for readability.
            // Floor of 50 m: same rationale as the imperial feet path — see toImperial.
            val rounded = (Math.round(meters / 50.0) * 50).coerceAtLeast(50).toDouble()
            Distance.create(rounded, Distance.UNIT_METERS)
        } else {
            // Display in kilometers with 1 decimal place
            val km = meters / 1000.0
            Distance.create(km, Distance.UNIT_KILOMETERS_P1)
        }

}
