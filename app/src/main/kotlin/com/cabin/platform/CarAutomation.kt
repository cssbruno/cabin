package com.cabin.platform

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.*

internal fun quietHours(hour: Int, start: Int, end: Int): Boolean =
    if (start == end) true else if (start < end) hour in start until end else hour >= start || hour < end

/** NOAA fractional-year solar elevation; 90.833° zenith accounts for the apparent solar disk. */
internal fun solarNight(time: Long, latitude: Double, longitude: Double): Boolean {
    require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
    val utc = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
    val hour = utc.hour + utc.minute / 60.0
    val gamma = 2 * PI / utc.toLocalDate().lengthOfYear() * (utc.dayOfYear - 1 + (hour - 12) / 24)
    val equation = 229.18 * (.000075 + .001868 * cos(gamma) - .032077 * sin(gamma) - .014615 * cos(2 * gamma) - .040849 * sin(2 * gamma))
    val declination = .006918 - .399912 * cos(gamma) + .070257 * sin(gamma) - .006758 * cos(2 * gamma) + .000907 * sin(2 * gamma) - .002697 * cos(3 * gamma) + .00148 * sin(3 * gamma)
    val angle = Math.toRadians((hour * 60 + equation + 4 * longitude) / 4 - 180)
    val lat = Math.toRadians(latitude)
    val zenith = acos((sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(angle)).coerceIn(-1.0, 1.0))
    return zenith > Math.toRadians(90.833)
}

internal fun automationPreferences(context: Context) = context.applicationContext.getSharedPreferences("cabin_automation", Context.MODE_PRIVATE)

@Composable
internal fun rememberAutomationValues(file: String = "cabin_automation"): Map<String, *> {
    val context = LocalContext.current
    val prefs = remember(context, file) { context.getSharedPreferences(file, Context.MODE_PRIVATE) }
    var values by remember(prefs) { mutableStateOf(prefs.all.toMap()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> values = prefs.all.toMap() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return values
}

@Composable
internal fun rememberCarAutomation(vehicle: TeyesClimateState, systemDark: Boolean): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { automationPreferences(context) }
    val values = rememberAutomationValues()
    val solar = values["solar"] == true
    val parked = values["parked"] == true
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var location by remember { mutableStateOf<Location?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        val hour = java.time.LocalTime.now().hour
        if (prefs.all["quiet"] == true && quietHours(hour, (prefs.all["start"] as? Int ?: 22).coerceIn(0,23), (prefs.all["end"] as? Int ?: 7).coerceIn(0,23))) {
            val audio = context.getSystemService(AudioManager::class.java)
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC), maximum / 5), 0)
        }
        while (true) { now = System.currentTimeMillis(); delay(30_000) }
    }
    LaunchedEffect(lifecycle, solar, parked, values["permissionRevision"]) {
        if (!solar && !parked) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@repeatOnLifecycle
            val manager = context.getSystemService(LocationManager::class.java)
            callbackFlow {
                val listener = object : LocationListener {
                    override fun onLocationChanged(value: Location) { trySend(value) }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                try { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000, 10f, listener, Looper.getMainLooper()) }
                catch (_: SecurityException) { close() }
                catch (_: IllegalArgumentException) { close() }
                awaitClose { try { manager.removeUpdates(listener) } catch (_: SecurityException) { } }
            }.collect { location = it }
        }
    }
    LaunchedEffect(location, values["saveParking"], parked, vehicle.profileId, now) {
        val requestedAt = values["saveParkingTime"] as? Long
        if (values.containsKey("saveParking") && (!parked || values["saveParking"] != vehicle.profileId || requestedAt == null || System.currentTimeMillis() - requestedAt !in 0..120_000)) {
            prefs.edit().remove("saveParking").remove("saveParkingTime").apply()
            return@LaunchedEffect
        }
        if (parked && values["saveParking"] == vehicle.profileId) {
            location?.takeIf { it.hasAccuracy() && it.accuracy <= 100 && android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..60_000_000_000L }?.let {
                prefs.edit().putString("parking.${vehicle.profileId}", "${it.latitude},${it.longitude}")
                    .putLong("parkingTime.${vehicle.profileId}", System.currentTimeMillis()).remove("saveParking").remove("saveParkingTime").apply()
            }
        }
    }
    val fix = location?.takeIf { android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..3_600_000_000_000L }
    return if (solar && fix != null) solarNight(now, fix.latitude, fix.longitude) else systemDark
}
