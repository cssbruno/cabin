package com.cabin.platform

import com.cabin.localization.localizedString
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Climate and vehicle state from the compatible FYT/SYU CANBUS toolkit. */
data class TeyesClimateState(
    val connected: Boolean = false,
    val syuAir: SyuAirState? = null,
    val syuVehicle: SyuVehicleTelemetry = SyuVehicleTelemetry(),
    val health: TeyesTelemetryHealth = TeyesTelemetryHealth.DISCONNECTED,
    val lastUpdateElapsedRealtimeMs: Long? = null,
    /** Fresh app-canonical codes, normalized from the selected firmware layout; not raw Binder IDs. */
    val availableCodes: Set<Int> = emptySet(),
    val doorsAvailable: Boolean = false,
    val controlsSupported: Boolean = false,
    val controlsAvailable: Boolean = false,
    val fanControlsAvailable: Boolean = false,
    val controlUnavailableReason: String? = null,
    val profileId: Int = 0,
    val vehicleDataLayout: TeyesVehicleDataLayout = TeyesVehicleDataLayout.LEGACY,
    val power: Boolean = false,
    val ac: Boolean = false,
    val auto: Boolean = false,
    val dual: Boolean = false,
    val recirculating: Boolean = false,
    val frontDefrost: Boolean = false,
    val rearDefrost: Boolean = false,
    val leftTemperature: Int? = null,
    val rightTemperature: Int? = null,
    val fahrenheit: Boolean = false,
    val fanLevel: Int = 0,
    val speedKph: Int? = null,
    val engineRpm: Int? = null,
    val oilLifePercent: Int? = null,
    val oilServiceDistance: Int? = null,
    val oilServiceDistanceMiles: Boolean = false,
    val hoodOpen: Boolean = false,
    val frontLeftDoorOpen: Boolean = false,
    val frontRightDoorOpen: Boolean = false,
    val rearLeftDoorOpen: Boolean = false,
    val rearRightDoorOpen: Boolean = false,
    val bootOpen: Boolean = false,
    val rearTemperature: Int? = null,
    val rearFanLevel: Int = 0,
    val rearAuto: Boolean = false,
    val driverSeatCooling: Int = 0,
    val driverSeatHeating: Int = 0,
    val passengerSeatCooling: Int = 0,
    val passengerSeatHeating: Int = 0,
    val blowUp: Boolean = false,
    val blowBody: Boolean = false,
    val blowFoot: Boolean = false,
)

enum class TeyesClimateSwitch(val key: Int, val feedbackCode: Int) {
    POWER(1, 32), AUTO(21, 20), DUAL(16, 30), RECIRCULATION(25, 21), FRONT_DEFROST(19, 22), REAR_DEFROST(20, 23);

    fun active(state: TeyesClimateState): Boolean = when (this) {
        POWER -> state.power
        AUTO -> state.auto
        DUAL -> state.dual
        RECIRCULATION -> state.recirculating
        FRONT_DEFROST -> state.frontDefrost
        REAR_DEFROST -> state.rearDefrost
    }
}

enum class TeyesTemperatureZone { DRIVER, PASSENGER }

enum class TeyesAirflowMode { BODY, BODY_FOOT, FOOT, UP_FOOT }

/**
 * Binder client for the shared FYT/SYU `com.syu.ms` toolkit service.
 *
 * It uses the vendor's stable AIDL wire contract, but contains no copied vendor code.
 * Commands are limited to the controls used by TEYES's own Honda Civic A/C screen.
 */
class TeyesClimateController(
    context: Context,
    private val onClimateChanged: () -> Unit,
) : AutoCloseable {
    companion object {
        private const val TOOLKIT_DESCRIPTOR = "com.syu.ipc.IRemoteToolkit"
        private const val MODULE_DESCRIPTOR = "com.syu.ipc.IRemoteModule"
        private const val CALLBACK_DESCRIPTOR = "com.syu.ipc.IModuleCallback"
        private const val MODULE_CANBUS = 7
        private const val PROFILE_2016_CIVIC_ALT = TeyesClimateControlPolicy.VERIFIED_ALTERNATE_PROFILE
        private val UPDATE_CODES =
            intArrayOf(
                1000,
                0, 1, 2, 3, 4, 5, 10, 11, 12, 13, 14, 16, 18, 19,
                20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,
                36, 37, 38, 39, 40, 41,
                51, 52, 53, 54, 55, 56, 57,
                65, 73, 77, 89, 90, 91, 92, 93, 94, 95, 96, 97, 137, 179, 180, 181,
            )
        private val CLIMATE_POPUP_CODES =
            (20..35).toSet() + (51..57).toSet() + setOf(73, 91, 92, 93, 94, 95, 96, 97)
    }

    private val appContext = context.applicationContext
    private val tripHistory by lazy(LazyThreadSafetyMode.NONE) { TripHistory(appContext) }
    private val tripRecorder = TripRecorder { profile, trip -> tripHistory.save(profile, trip) }
    private val tireHistory by lazy(LazyThreadSafetyMode.NONE) { TireHistory(appContext) }
    private val airRegistry by lazy(LazyThreadSafetyMode.NONE) { SyuAirRegistry.load(appContext) }
    private val registeredCodes = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var airProfile: SyuAirProfile? = null
    private val dataPreferences = TeyesVehicleDataPreferences.get(appContext)
    private var activeLayout = dataPreferences.layout.value
    private val worker = HandlerThread("TeyesTelemetry").apply { start() }
    private val handler = Handler(worker.looper)
    private val started = AtomicBoolean(false)
    private val suspended = AtomicBoolean(false)
    private var lastCachedRefresh = 0L
    private val closed = AtomicBoolean(false)
    private val connectionEpoch = AtomicLong(0)
    private val samples = TeyesTelemetryFreshness()
    private val popupPolicy = TeyesClimatePopupPolicy()
    private val reconnectPolicy = TeyesTelemetryReconnectPolicy()
    private val mutableState = MutableStateFlow(TeyesClimateState(controlUnavailableReason = appContext.localizedString(com.cabin.R.string.vehicle_status_disconnected)))
    val state: StateFlow<TeyesClimateState> = mutableState.asStateFlow()
    private var moduleBinder: IBinder? = null
    private var useDirectCanService = false
    private var connection: ServiceConnection? = null
    private var callback: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var lastProfile: Int? = null
    private var connectedAt: Long? = null
    private val retry = Runnable { bind() }
    private val bindTimeout = Runnable { disconnectAndRetry() }
    private val freshnessTick =
        object : Runnable {
            override fun run() {
                if (closed.get() || suspended.get()) return
                if (activeLayout != dataPreferences.layout.value) {
                    activeLayout = dataPreferences.layout.value
                    // Rebind with a new callback owner: queued samples from the old dialect cannot leak across.
                    disconnectAndRetry()
                }
                if (connectedAt?.let { SystemClock.elapsedRealtime() - it >= 60_000L } == true) reconnectPolicy.reset()
                publishState()
                val now = SystemClock.elapsedRealtime()
                if (moduleBinder != null && now - lastCachedRefresh >= 15_000L) {
                    lastCachedRefresh = now
                    try {
                        val remote = moduleBinder!!
                        val listener = callback!!
                        // Refresh quiet door/climate/settings values, never cached speed/RPM.
                        registeredCodes.toList().filter { it !in setOf(89, 90, 149, 151) }.forEach {
                            SyuBinderTransport.register(remote, listener, it)
                        }
                    } catch (_: Exception) { disconnectAndRetry() }
                }
                handler.postDelayed(this, 1_000L)
            }
        }

    private fun createCallback(owner: ServiceConnection): IBinder =
        object : Binder() {
            init {
                attachInterface(null, CALLBACK_DESCRIPTOR)
            }

            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(CALLBACK_DESCRIPTOR)
                    return true
                }
                if (code != 1) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                // Only a small integer sample is consumed. Never allocate unused vendor
                // float/string arrays, or trust an unbounded array length from IPC.
                if (data.dataAvail() > 4096 || data.dataAvail() < 8) return false
                val sample = try {
                    val updateCode = data.readInt()
                    if (updateCode !in registeredCodes) { reply?.writeNoException(); return true }
                    val count = data.readInt()
                    if (count == -1 || count == 0) { reply?.writeNoException(); return true }
                    if (count !in 1..16 || count > data.dataAvail() / 4) return false
                    updateCode to data.readInt()
                } catch (_: RuntimeException) {
                    return false
                }
                if (closed.get()) { reply?.writeNoException(); return true }
                handler.post {
                    if (!closed.get() && connection === owner && moduleBinder != null) {
                        update(sample.first, sample.second)
                    }
                }
                reply?.writeNoException()
                return true
            }
        }

    private fun createConnection(): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                val owner = this
                handler.post {
                    if (closed.get() || connection !== owner) return@post
                    handler.removeCallbacks(bindTimeout)
                    val module = try {
                        if (useDirectCanService) {
                            service.takeIf { it.interfaceDescriptor == MODULE_DESCRIPTOR }
                        } else getCanbusModule(service)
                    } catch (error: Exception) {
                        android.util.Log.w("CabinCAN", "CAN service interface unavailable", error)
                        null
                    }
                    if (module == null) {
                        com.cabin.reports.DebugJournal.record("CAN", "module_unavailable", name.flattenToShortString())
                        android.util.Log.w("CabinCAN", "CAN module unavailable through ${name.flattenToShortString()}; trying alternate service")
                        disconnectAndRetry()
                        return@post
                    }
                    moduleBinder = module
                    com.cabin.reports.DebugJournal.record("CAN", "connected", name.flattenToShortString())
                    connectedAt = SystemClock.elapsedRealtime()
                    callback = createCallback(owner)
                    val death =
                        IBinder.DeathRecipient {
                            handler.post { if (connection === owner && !closed.get()) disconnectAndRetry() }
                        }
                    deathRecipient = death
                    try {
                        module.linkToDeath(death, 0)
                    } catch (_: RemoteException) {
                        disconnectAndRetry()
                        return@post
                    }
                    popupPolicy.reset(SystemClock.elapsedRealtime())
                    UPDATE_CODES.forEach { if (moduleBinder != null) register(it) }
                    publishState()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                onLost(this)
            }

            override fun onBindingDied(name: ComponentName) {
                onLost(this)
            }

            override fun onNullBinding(name: ComponentName) {
                onLost(this)
            }
        }

    private fun onLost(owner: ServiceConnection) {
        if (closed.get()) return
        handler.post { if (connection === owner && !closed.get()) disconnectAndRetry() }
    }

    /** Accepts an asynchronous start; actual Binder availability is reported by [state]. */
    fun start(): Boolean {
        if (closed.get()) return false
        if (!started.compareAndSet(false, true)) return true
        handler.post {
            if (!closed.get()) {
                bind()
                handler.post(freshnessTick)
            }
        }
        return true
    }

    /** Explicit user retry or read-only refresh; re-registers cached vendor fields without CAN writes. */
    fun retryConnection() {
        if (closed.get()) return
        if (!started.get()) {
            start()
            return
        }
        handler.post {
            if (closed.get()) return@post
            handler.removeCallbacks(retry)
            clearConnection()
            reconnectPolicy.reset()
            bind()
        }
    }

    fun suspendUpdates() {
        if (closed.get() || !suspended.compareAndSet(false, true)) return
        handler.post {
            handler.removeCallbacks(retry); handler.removeCallbacks(freshnessTick)
            clearConnection()
        }
    }

    fun resumeUpdates() {
        if (closed.get() || !suspended.compareAndSet(true, false)) return
        handler.post {
            if (!closed.get() && !suspended.get()) {
                reconnectPolicy.reset(); bind()
                handler.removeCallbacks(freshnessTick); handler.post(freshnessTick)
            }
        }
    }

    private fun bind() {
        if (suspended.get() || closed.get() || connection != null) return
        val nextConnection = createConnection()
        connection = nextConnection
        mutableState.value = TeyesClimateState(health = TeyesTelemetryHealth.CONNECTING, controlUnavailableReason = appContext.localizedString(com.cabin.R.string.vehicle_status_waiting_profile))
        val intent =
            if (useDirectCanService) fytCanbusIntent(appContext) else fytToolkitIntent(appContext)
        com.cabin.reports.DebugJournal.record("CAN", "bind", intent.component.toString())
        val accepted =
            try {
                appContext.bindService(intent, nextConnection, Context.BIND_AUTO_CREATE)
            } catch (error: Exception) {
                android.util.Log.w("CabinCAN", "Cannot bind ${intent.component}", error)
                com.cabin.reports.DebugJournal.record("CAN", "bind_failed", error.toString())
                false
            }
        if (accepted) {
            handler.postDelayed(bindTimeout, 10_000L)
        } else {
            com.cabin.reports.DebugJournal.record("CAN", "bind_rejected", intent.component.toString())
            disconnectAndRetry()
        }
    }

    fun setFactoryControl(control: SyuFactoryControl, value: Int) {
        if (closed.get()) return
        val expectedProfile = mutableState.value.profileId
        val expectedEpoch = connectionEpoch.get()
        val frame = SyuFactoryProtocol.frame(expectedProfile, control, value) ?: return
        handler.post {
            if (closed.get() || expectedEpoch != connectionEpoch.get() || activeLayout != dataPreferences.layout.value) return@post
            publishState()
            val current = mutableState.value
            if (moduleBinder == null || current.profileId != expectedProfile ||
                control !in current.syuVehicle.factoryControls) return@post
            command(frame.first, frame.second.toIntArray())
        }
    }

    fun setFactoryAmplifier(setting: SyuAmplifierSetting, value: Int) {
        if (closed.get() || value !in 0..18) return
        val expectedEpoch = connectionEpoch.get()
        val expectedProfile = mutableState.value.profileId
        handler.post {
            if (closed.get() || expectedEpoch != connectionEpoch.get() || activeLayout != dataPreferences.layout.value) return@post
            publishState()
            val current = mutableState.value
            if (moduleBinder == null || current.profileId != expectedProfile ||
                current.profileId != SyuVehicleProtocol.AMPLIFIER_PROFILE || setting !in current.syuVehicle.amplifier) return@post
            command(2, intArrayOf(setting.commandKey, value))
        }
    }

    fun setVehicleLighting(setting: SyuLightingSetting, value: Int) {
        if (closed.get() || value !in setting.values.indices) return
        val expectedProfile = mutableState.value.profileId
        val expectedEpoch = connectionEpoch.get()
        handler.post {
            if (closed.get() || expectedEpoch != connectionEpoch.get() ||
                activeLayout != dataPreferences.layout.value) return@post
            publishState()
            val current = mutableState.value
            if (moduleBinder == null || current.profileId != expectedProfile ||
                current.profileId !in SyuVehicleProtocol.lightingProfiles || setting !in current.syuVehicle.lighting) return@post
            command(105, intArrayOf(setting.commandKey, value))
        }
    }

    fun sendAirAction(action: String) {
        if (closed.get()) return
        val expectedProfile = mutableState.value.syuAir?.profileId ?: return
        val expectedEpoch = connectionEpoch.get()
        handler.post {
            if (closed.get() || expectedEpoch != connectionEpoch.get() || activeLayout != dataPreferences.layout.value) return@post
            publishState()
            val current = mutableState.value.syuAir ?: return@post
            if (moduleBinder == null || current.profileId != expectedProfile || !current.canSend(action)) return@post
            val frames = airProfile?.commands?.get(action) ?: return@post
            for (frame in frames) {
                if (moduleBinder == null || expectedEpoch != connectionEpoch.get()) break
                command(frame.command, frame.values.toIntArray())
            }
        }
    }

    fun setAc(enabled: Boolean) {
        postControl {
            if (isAlternateProfile()) {
                command(107, intArrayOf(2, if (enabled) 1 else 0))
            } else {
                command(105, intArrayOf(172, if (enabled) 1 else 2))
            }
        }
    }

    fun setFan(level: Int) {
        val safeLevel = level.coerceIn(1, 7)
        postControl(fanOnly = true) {
            if (isAlternateProfile()) {
                command(107, intArrayOf(25, safeLevel))
            } else {
                command(105, intArrayOf(173, safeLevel))
            }
        }
    }

    /** One tap reproduces the SYU Air key press/release; readings change only on CAN feedback. */
    fun adjustTemperature(zone: TeyesTemperatureZone, increase: Boolean) {
        if (closed.get()) return
        val expectedProfile = mutableState.value.profileId
        val expectedEpoch = connectionEpoch.get()
        handler.post {
            if (closed.get() || activeLayout != dataPreferences.layout.value) return@post
            publishState()
            val state = mutableState.value
            if (connectionEpoch.get() != expectedEpoch || state.profileId != expectedProfile || !TeyesClimateControlPolicy.canAdjustTemperature(state, zone, increase)) return@post
            val key = when (zone) {
                TeyesTemperatureZone.DRIVER -> if (increase) 3 else 2
                TeyesTemperatureZone.PASSENGER -> if (increase) 5 else 4
            }
            command(107, intArrayOf(key, 1))
            command(107, intArrayOf(key, 0))
        }
    }

    fun toggleClimate(control: TeyesClimateSwitch) {
        if (closed.get()) return
        val expectedProfile = mutableState.value.profileId
        val expectedEpoch = connectionEpoch.get()
        handler.post {
            if (closed.get() || activeLayout != dataPreferences.layout.value || connectionEpoch.get() != expectedEpoch) return@post
            publishState()
            if (mutableState.value.profileId != expectedProfile || !TeyesClimateControlPolicy.canToggle(mutableState.value, control)) return@post
            command(107, intArrayOf(control.key, 1))
            command(107, intArrayOf(control.key, 0))
        }
    }

    fun setAirflow(mode: TeyesAirflowMode) {
        postControl { setAirflowOnWorker(mode) }
    }

    private fun setAirflowOnWorker(mode: TeyesAirflowMode) {
        if (isAlternateProfile()) {
            val key =
                when (mode) {
                    TeyesAirflowMode.BODY -> 9
                    TeyesAirflowMode.BODY_FOOT -> 24
                    TeyesAirflowMode.FOOT -> 10
                    TeyesAirflowMode.UP_FOOT -> 23
                }
            command(107, intArrayOf(key, 1))
            command(107, intArrayOf(key))
        } else {
            val value =
                when (mode) {
                    TeyesAirflowMode.BODY -> 3
                    TeyesAirflowMode.BODY_FOOT -> 4
                    TeyesAirflowMode.FOOT -> 5
                    TeyesAirflowMode.UP_FOOT -> 6
                }
            command(105, intArrayOf(172, value))
        }
    }

    private fun update(
        code: Int,
        value: Int,
    ) {
        com.cabin.reports.DebugJournal.record("CAN", "callback", "field=$code; value=$value")
        val now = SystemClock.elapsedRealtime()
        if (code == 1000 && lastProfile != null && lastProfile != 0 && lastProfile != value) {
            // New callback ownership also rejects old-profile updates already queued on the worker.
            disconnectAndRetry()
            return
        }
        if (code == 1000) {
            val newProfile = lastProfile != value
            lastProfile = value
            // Preserve established Civic firmware layouts. Other profiles use their SYU definitions.
            airProfile = if (TeyesClimateControlPolicy.supports(value)) null else airRegistry.profiles[value]
            SyuVehicleProtocol.codes(value).forEach { if (it !in registeredCodes) register(it) }
            airProfile?.fields?.values?.distinct()?.forEach { if (it !in registeredCodes) register(it) }
            if (newProfile) SyuVehicleProtocol.queryFrames(value).forEach { (code, payload) ->
                if (moduleBinder != null) command(code, payload.toIntArray())
            }
        }
        val changed = samples.update(code, value, now)
        publishState()
        val climateField =
            if (activeLayout == TeyesVehicleDataLayout.CIVIC_0298 &&
                TeyesClimateControlPolicy.isCivic0298(lastProfile ?: 0)
            ) {
                code in setOf(11, 18, 19, 20, 21) ||
                    (TeyesClimateControlPolicy.supportsTemperature(lastProfile ?: 0) && code in setOf(10, 12, 13, 14, 16, 27, 28, 37, 65))
            } else {
                code in CLIMATE_POPUP_CODES
            }
        if (popupPolicy.shouldShow(changed, climateField, now)) onClimateChanged()
    }

    private fun publishState() {
        if (closed.get()) return
        val now = SystemClock.elapsedRealtime()
        val rawValues = samples.snapshot(now)
        val profile = rawValues[1000] ?: 0
        val values = if (airProfile != null || profile in SyuFactoryProtocol.cameraProfiles || profile in setOf(SyuFactoryProtocol.HYBRID_PROFILE, SyuFactoryProtocol.AMBIENT_PROFILE, SyuFactoryProtocol.SEAT_PRESET_PROFILE)) {
            // Shared SYU air fields must never be interpreted as legacy Civic gauges.
            buildMap {
                put(1000, profile)
                // Profile 131109 uses field 4 for camera mode, not the shared door dialect.
                for (door in if (profile == 131109) IntRange.EMPTY else 0..5) rawValues[door]?.takeIf { it in 0..1 }?.let { put(door + 36, it) }
            }
        } else TeyesClimateControlPolicy.climateValues(profile, rawValues, activeLayout)
        val alternate = profile == PROFILE_2016_CIVIC_ALT
        val mode = values[73]
        val airValues = samples.airSnapshot(now)
        val airState = airProfile?.let { definition ->
            SyuAirState(definition.id, definition.name,
                definition.fields.mapNotNull { (name, code) -> airValues[code]?.let { name to it } }.toMap(),
                definition.commands.keys, definition.low, definition.high, definition.unavailable, definition.temperatureFormats)
        }
        mutableState.value =
            TeyesClimateState(
                connected = moduleBinder != null,
                syuAir = airState,
                syuVehicle = SyuVehicleProtocol.decode(profile, airValues),
                health =
                    when {
                        moduleBinder == null && connection != null -> TeyesTelemetryHealth.CONNECTING
                        moduleBinder == null -> TeyesTelemetryHealth.DISCONNECTED
                        values.keys.none { it != 1000 } && airValues.keys.none { it in (airProfile?.fields?.values ?: emptyList()) || it in SyuVehicleProtocol.codes(profile) } -> TeyesTelemetryHealth.STALE
                        else -> TeyesTelemetryHealth.LIVE
                    },
                lastUpdateElapsedRealtimeMs = samples.lastUpdateElapsedRealtimeMs,
                availableCodes = values.keys.toSet(),
                doorsAvailable = (36..41).all { values.containsKey(it) },
                controlsSupported = TeyesClimateControlPolicy.supports(profile),
                controlsAvailable = TeyesClimateControlPolicy.canControl(moduleBinder != null, profile, values),
                fanControlsAvailable = TeyesClimateControlPolicy.canControlFan(moduleBinder != null, profile, values),
                controlUnavailableReason =
                    when {
                        moduleBinder == null -> appContext.localizedString(com.cabin.R.string.vehicle_status_disconnected)
                        profile == 0 -> appContext.localizedString(com.cabin.R.string.vehicle_status_waiting_profile)
                        !TeyesClimateControlPolicy.supports(profile) -> appContext.localizedString(com.cabin.R.string.vehicle_status_unverified, profile)
                        !TeyesClimateControlPolicy.canControl(true, profile, values) ->
                            appContext.localizedString(com.cabin.R.string.vehicle_status_fresh)
                        else -> null
                    },
                profileId = profile,
                vehicleDataLayout = activeLayout,
                power = values[32] == 1,
                ac = values[if (alternate) 30 else 24] == 1,
                auto = values[20] == 1,
                dual = !alternate && values[30] == 1,
                recirculating = if (activeLayout == TeyesVehicleDataLayout.CIVIC_0298 && TeyesClimateControlPolicy.supportsTemperature(profile)) values[21] == 1 else values[21] == 0,
                frontDefrost = values[22] == 1,
                rearDefrost = values[23] == 1,
                leftTemperature = values[25],
                rightTemperature = values[31],
                fahrenheit = values[33] == 1,
                fanLevel = (values[if (alternate) 35 else 29] ?: 0).coerceIn(0, 7),
                speedKph = values[89]?.takeIf { it in 0..400 },
                engineRpm = values[90]?.takeIf { it in 0..10_000 },
                oilLifePercent = values[137]?.takeIf { it in 0..100 },
                oilServiceDistance = values[181]?.let { if (values[180] == 1) -it else it },
                oilServiceDistanceMiles = values[179] == 1,
                hoodOpen = values[36] == 1,
                frontLeftDoorOpen = values[37] == 1,
                frontRightDoorOpen = values[38] == 1,
                rearLeftDoorOpen = values[39] == 1,
                rearRightDoorOpen = values[40] == 1,
                bootOpen = values[41] == 1,
                rearTemperature = values[52],
                rearFanLevel = (values[56] ?: 0).coerceIn(0, 7),
                rearAuto = values[57] == 1,
                driverSeatCooling = (values[94] ?: 0).coerceIn(0, 3),
                driverSeatHeating = (values[95] ?: 0).coerceIn(0, 3),
                passengerSeatCooling = (values[96] ?: 0).coerceIn(0, 3),
                passengerSeatHeating = (values[97] ?: 0).coerceIn(0, 3),
                blowUp = if (alternate) mode == 4 else values[28] == 1 || values[91] == 1,
                blowBody = if (alternate) mode == 6 || mode == 5 else values[26] == 1 || values[92] == 1,
                blowFoot = if (alternate) mode == 3 || mode == 4 || mode == 5 else values[27] == 1 || values[93] == 1,
            )
        tireHistory.record(mutableState.value, now)
        tripRecorder.observe(mutableState.value, now, System.currentTimeMillis())
    }

    private fun postControl(fanOnly: Boolean = false, action: () -> Unit) {
        if (closed.get()) return
        val expectedProfile = mutableState.value.profileId
        val expectedEpoch = connectionEpoch.get()
        handler.post {
            if (closed.get() || connectionEpoch.get() != expectedEpoch) return@post
            // Do not issue a command during the preference-change/rebind window.
            if (activeLayout != dataPreferences.layout.value) return@post
            publishState()
            if (mutableState.value.profileId != expectedProfile) return@post
            // The legacy fallback did not identify its supported numeric profile.
            // Never treat an arbitrary vehicle profile as a Honda control interface.
            if (if (fanOnly) mutableState.value.fanControlsAvailable else mutableState.value.controlsAvailable) action()
        }
    }

    private fun isAlternateProfile(): Boolean = mutableState.value.profileId == PROFILE_2016_CIVIC_ALT

    private fun getCanbusModule(toolkit: IBinder): IBinder? =
        try { SyuBinderTransport.getModule(toolkit, MODULE_CANBUS) } catch (_: Exception) { null }

    private fun register(updateCode: Int) {
        if (!registeredCodes.add(updateCode)) return
        transactModule(3) { data ->
            data.writeStrongBinder(callback)
            data.writeInt(updateCode)
            data.writeInt(1)
        }
    }

    private fun command(
        commandCode: Int,
        ints: IntArray,
    ) {
        transactModule(1) { data ->
            data.writeInt(commandCode)
            data.writeIntArray(ints)
            data.writeFloatArray(null)
            data.writeStringArray(null)
        }
    }

    private fun transactModule(
        code: Int,
        body: (Parcel) -> Unit,
    ) {
        val remote = moduleBinder ?: return
        try {
            SyuBinderTransport.transact(remote, code, body, {})
        } catch (_: Exception) {
            disconnectAndRetry()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Publish unavailable immediately, even if vendor IPC cleanup is still queued.
        tripRecorder.finish()
        mutableState.value = TeyesClimateState(controlUnavailableReason = appContext.localizedString(com.cabin.R.string.vehicle_status_disconnected))
        handler.removeCallbacksAndMessages(null)
        handler.post {
            clearConnection()
            worker.quitSafely()
        }
    }

    private fun disconnectAndRetry() {
        // Some FYT builds expose the CAN module directly even when toolkit lookup fails.
        // Keep a working route on transient disconnect; alternate only failed setup attempts.
        if (moduleBinder == null) useDirectCanService = !useDirectCanService
        clearConnection()
        if (closed.get()) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, reconnectPolicy.nextDelayMs())
    }

    private fun clearConnection() {
        connectionEpoch.incrementAndGet()
        handler.removeCallbacks(bindTimeout)
        val oldModule = moduleBinder
        val oldCallback = callback
        val oldDeath = deathRecipient
        moduleBinder = null
        deathRecipient = null
        callback = null
        airProfile = null
        connectedAt = null
        // Release the old subscription even if the exported service remains alive after unbinding.
        // Use the captured binder directly: failed cleanup must not recursively start another retry.
        if (oldModule != null && oldCallback != null && oldModule.isBinderAlive) {
            for (updateCode in registeredCodes.toList()) {
                try {
                    SyuBinderTransport.unregister(oldModule, oldCallback, updateCode)
                } catch (_: Exception) {
                    break
                }
            }
        }
        registeredCodes.clear()
        if (oldModule != null && oldDeath != null) {
            try {
                oldModule.unlinkToDeath(oldDeath, 0)
            } catch (_: Exception) {
                // A dead vendor process may already have removed the recipient.
            }
        }
        val oldConnection = connection
        connection = null
        if (oldConnection != null) {
            try {
                appContext.unbindService(oldConnection)
            } catch (_: IllegalArgumentException) {
                // Includes rejected bindings and a service already removed by firmware.
            }
        }
        samples.clear()
        lastProfile = null
        tripRecorder.finish()
        mutableState.value = TeyesClimateState(controlUnavailableReason = appContext.localizedString(com.cabin.R.string.vehicle_status_disconnected))
    }
}
