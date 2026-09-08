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

/** Climate and vehicle state from the compatible FYT/SYU CANBUS toolkit. */
data class TeyesClimateState(
    val connected: Boolean = false,
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
                0, 1, 2, 3, 4, 5, 11, 18, 19,
                20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,
                36, 37, 38, 39, 40, 41,
                51, 52, 53, 54, 55, 56, 57,
                73, 77, 89, 90, 91, 92, 93, 94, 95, 96, 97, 137, 179, 180, 181,
            )
        private val CLIMATE_POPUP_CODES =
            (20..35).toSet() + (51..57).toSet() + setOf(73, 91, 92, 93, 94, 95, 96, 97)
    }

    private val appContext = context.applicationContext
    private val dataPreferences = TeyesVehicleDataPreferences.get(appContext)
    private var activeLayout = dataPreferences.layout.value
    private val worker = HandlerThread("TeyesTelemetry").apply { start() }
    private val handler = Handler(worker.looper)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val samples = TeyesTelemetryFreshness()
    private val popupPolicy = TeyesClimatePopupPolicy()
    private val reconnectPolicy = TeyesTelemetryReconnectPolicy()
    private val mutableState = MutableStateFlow(TeyesClimateState(controlUnavailableReason = appContext.localizedString(com.cabin.R.string.vehicle_status_disconnected)))
    val state: StateFlow<TeyesClimateState> = mutableState.asStateFlow()
    private var moduleBinder: IBinder? = null
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
                if (closed.get()) return
                if (activeLayout != dataPreferences.layout.value) {
                    activeLayout = dataPreferences.layout.value
                    // Rebind with a new callback owner: queued samples from the old dialect cannot leak across.
                    disconnectAndRetry()
                }
                if (connectedAt?.let { SystemClock.elapsedRealtime() - it >= 60_000L } == true) reconnectPolicy.reset()
                publishState()
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
                val updateCode = data.readInt()
                val ints = data.createIntArray()
                data.createFloatArray()
                data.createStringArray()
                if (closed.get()) return true
                ints?.firstOrNull()?.let { value ->
                    handler.post {
                        if (!closed.get() && connection === owner && moduleBinder != null && updateCode in UPDATE_CODES) {
                            update(updateCode, value)
                        }
                    }
                }
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
                    val module = getCanbusModule(service)
                    if (module == null) {
                        disconnectAndRetry()
                        return@post
                    }
                    moduleBinder = module
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

    private fun bind() {
        if (closed.get() || connection != null) return
        val nextConnection = createConnection()
        connection = nextConnection
        mutableState.value = TeyesClimateState(health = TeyesTelemetryHealth.CONNECTING, controlUnavailableReason = appContext.localizedString(com.cabin.R.string.vehicle_status_waiting_profile))
        val intent =
            fytToolkitIntent(appContext)
        val accepted =
            try {
                appContext.bindService(intent, nextConnection, Context.BIND_AUTO_CREATE)
            } catch (_: Exception) {
                false
            }
        if (accepted) {
            handler.postDelayed(bindTimeout, 10_000L)
        } else {
            disconnectAndRetry()
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
        val now = SystemClock.elapsedRealtime()
        if (code == 1000 && lastProfile != null && lastProfile != 0 && lastProfile != value) {
            // New callback ownership also rejects old-profile updates already queued on the worker.
            disconnectAndRetry()
            return
        }
        if (code == 1000) lastProfile = value
        val changed = samples.update(code, value, now)
        publishState()
        val climateField =
            if (activeLayout == TeyesVehicleDataLayout.CIVIC_0298 &&
                TeyesClimateControlPolicy.isCivic0298(lastProfile ?: 0)
            ) {
                code in setOf(11, 18, 19, 20, 21)
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
        val values = TeyesClimateControlPolicy.climateValues(profile, rawValues, activeLayout)
        val alternate = profile == PROFILE_2016_CIVIC_ALT
        val mode = values[73]
        mutableState.value =
            TeyesClimateState(
                connected = moduleBinder != null,
                health =
                    when {
                        moduleBinder == null && connection != null -> TeyesTelemetryHealth.CONNECTING
                        moduleBinder == null -> TeyesTelemetryHealth.DISCONNECTED
                        values.keys.none { it != 1000 } -> TeyesTelemetryHealth.STALE
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
                recirculating = values[21] == 0,
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
    }

    private fun postControl(fanOnly: Boolean = false, action: () -> Unit) {
        if (closed.get()) return
        handler.post {
            if (closed.get()) return@post
            // Do not issue a command during the preference-change/rebind window.
            if (activeLayout != dataPreferences.layout.value) return@post
            publishState()
            // The legacy fallback did not identify its supported numeric profile.
            // Never treat an arbitrary vehicle profile as a Honda control interface.
            if (if (fanOnly) mutableState.value.fanControlsAvailable else mutableState.value.controlsAvailable) action()
        }
    }

    private fun isAlternateProfile(): Boolean = mutableState.value.profileId == PROFILE_2016_CIVIC_ALT

    private fun getCanbusModule(toolkit: IBinder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOOLKIT_DESCRIPTOR)
            data.writeInt(MODULE_CANBUS)
            if (!toolkit.transact(1, data, reply, 0)) return null
            reply.readException()
            reply.readStrongBinder()
        } catch (_: Exception) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun register(updateCode: Int) {
        transactOneWay(3) { data ->
            data.writeStrongBinder(callback)
            data.writeInt(updateCode)
            data.writeInt(1)
        }
    }

    private fun command(
        commandCode: Int,
        ints: IntArray,
    ) {
        transactOneWay(1) { data ->
            data.writeInt(commandCode)
            data.writeIntArray(ints)
            data.writeFloatArray(null)
            data.writeStringArray(null)
        }
    }

    private fun transactOneWay(
        code: Int,
        body: (Parcel) -> Unit,
    ) {
        val remote = moduleBinder ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(MODULE_DESCRIPTOR)
            body(data)
            if (!remote.transact(code, data, null, IBinder.FLAG_ONEWAY)) disconnectAndRetry()
        } catch (_: RemoteException) {
            disconnectAndRetry()
        } catch (_: SecurityException) {
            disconnectAndRetry()
        } finally {
            data.recycle()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Publish unavailable immediately, even if vendor IPC cleanup is still queued.
        mutableState.value = TeyesClimateState(controlUnavailableReason = appContext.localizedString(com.cabin.R.string.vehicle_status_disconnected))
        handler.removeCallbacksAndMessages(null)
        handler.post {
            clearConnection()
            worker.quitSafely()
        }
    }

    private fun disconnectAndRetry() {
        clearConnection()
        if (closed.get()) return
        handler.removeCallbacks(retry)
        reconnectPolicy.nextDelayMs()?.let { handler.postDelayed(retry, it) }
    }

    private fun clearConnection() {
        handler.removeCallbacks(bindTimeout)
        val oldModule = moduleBinder
        val oldCallback = callback
        val oldDeath = deathRecipient
        moduleBinder = null
        deathRecipient = null
        callback = null
        connectedAt = null
        // Release the old subscription even if the exported service remains alive after unbinding.
        // Use the captured binder directly: failed cleanup must not recursively start another retry.
        if (oldModule != null && oldCallback != null && oldModule.isBinderAlive) {
            for (updateCode in UPDATE_CODES) {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(MODULE_DESCRIPTOR)
                    data.writeStrongBinder(oldCallback)
                    data.writeInt(updateCode)
                    if (!oldModule.transact(4, data, null, IBinder.FLAG_ONEWAY)) break
                } catch (_: RemoteException) {
                    break
                } catch (_: SecurityException) {
                    break
                } finally {
                    data.recycle()
                }
            }
        }
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
        mutableState.value = TeyesClimateState(controlUnavailableReason = appContext.localizedString(com.cabin.R.string.vehicle_status_disconnected))
    }
}
