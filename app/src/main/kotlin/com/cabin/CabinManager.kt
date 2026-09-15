package com.cabin

import com.cabin.localization.localizedString
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.PowerManager
import android.view.Surface
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.cabin.BuildConfig
import com.cabin.audio.DualStreamAudioManager
import com.cabin.audio.MicrophoneCaptureManager
import com.cabin.gnss.GnssForwarder
import com.cabin.logging.Logger
import com.cabin.logging.logDebug
import com.cabin.logging.logError
import com.cabin.logging.logInfo
import com.cabin.logging.logVideoUsb
import com.cabin.logging.logWarn
import com.cabin.media.CabinMediaBrowserService
import com.cabin.media.MediaSessionManager
import com.cabin.navigation.NavigationStateManager
import com.cabin.platform.AudioConfig
import com.cabin.platform.PlatformDetector
import com.cabin.platform.ProjectionReadinessSnapshot
import com.cabin.platform.projectionOptionalCapability
import com.cabin.platform.projectionUsbReadiness
import com.cabin.protocol.AdapterConfig
import com.cabin.protocol.AdapterDriver
import com.cabin.protocol.AudioCommand
import com.cabin.protocol.AudioDataMessage
import com.cabin.protocol.BluetoothPairedListMessage
import com.cabin.protocol.BoxSettingsMessage
import com.cabin.protocol.CommandMapping
import com.cabin.protocol.CommandMessage
import com.cabin.protocol.InfoMessage
import com.cabin.protocol.KnownDevices
import com.cabin.protocol.MediaDataMessage
import com.cabin.protocol.MediaType
import com.cabin.protocol.Message
import com.cabin.protocol.MessageSerializer
import com.cabin.protocol.MultiTouchAction
import com.cabin.protocol.OrderedTouchSender
import com.cabin.protocol.NaviFocusMessage
import com.cabin.protocol.PeerBluetoothAddressMessage
import com.cabin.protocol.PhaseMessage
import com.cabin.protocol.PhoneType
import com.cabin.protocol.PluggedMessage
import com.cabin.protocol.SessionTokenMessage
import com.cabin.protocol.StatusValueMessage
import com.cabin.protocol.AudioRoutingState
import com.cabin.protocol.StreamPurpose
import com.cabin.protocol.UnknownMessage
import com.cabin.protocol.UnpluggedMessage
import com.cabin.protocol.VideoStreamingSignal
import com.cabin.ui.settings.AdapterConfigPreference
import com.cabin.ui.settings.MicSourceConfig
import com.cabin.ui.settings.WiFiBandConfig
import com.cabin.usb.UsbDeviceWrapper
import com.cabin.util.AppExecutors
import com.cabin.util.LogCallback
import com.cabin.video.H264Renderer
import com.cabin.widget.CabinWidgetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Main Cabin Manager
 *
 * Central orchestrator for the Cabin native application:
 * - USB device lifecycle management
 * - Protocol communication via AdapterDriver
 * - Video rendering via H264Renderer
 * - Audio playback via DualStreamAudioManager
 * - Microphone capture for Siri/calls
 * - MediaSession integration for AAOS
 * - NavigationStateManager bootstrap (see `init { NavigationStateManager.initialize(context.applicationContext) }`)
 *   which enables cluster-navigation routing for NAVI_JSON/NAVI_IMAGE messages.
 * - CabinMediaBrowserService foreground-service lifecycle: CONNECTING/STREAMING start the
 *   FGS via `CabinMediaBrowserService.startConnectionForeground`, DISCONNECTED stops it
 *   via `stopConnectionForeground` (see [updateMediaSessionState]).
 * - GnssForwarder start/stop driven by `config.gpsForwarding` — started at STREAMING and
 *   on PLUGGED (START_GNSS_REPORT), stopped in stop()/release()/handleError()
 *   (NOT stopped in rebootAdapter — minor leak; reboot already implies disconnect, so low-impact).
 * - Auto-reconnect with exponential backoff and Pattern A / Pattern B / Pattern C status
 *   escalation (defined inline near the SHORT_SESSION_* constants; see [scheduleReconnect]
 *   and [handleError]).
 * - Device-management surface: DevList merging from BoxSettings, [forgetDevice],
 *   [connectToDevice] (targeted AutoConnect_By_BtAddress), [refreshDeviceList].
 * - Cluster-navigation gating: NAVI_JSON / NAVI_IMAGE are dropped unless
 *   `AdapterConfigPreference.getClusterNavigationSync()` returns true at message time
 *   (read per-message, not cached — see sites in [processMediaMetadata]).
 */
class CabinManager(
    context: Context,
    initialConfig: AdapterConfig = AdapterConfig.DEFAULT,
    /**
     * MediaSession manager owned by [MainActivity] (app-scope lifetime). Injected so the
     * underlying Media3 [androidx.media3.session.MediaLibrarySession] survives tier-2
     * CabinManager rebuilds (e.g. Apply in AdapterConfigurationDialog). Without this
     * injection, each rebuild would release + recreate the session, triggering
     * `onSessionDestroyed` on CarLauncher's MediaControllerCompat — CarLauncher then
     * fails to rebind to the new session token and the homescreen Media card renders
     * blank until a full package reinstall. Diagnosis captured 2026-04-23 (see
     * `/Users/zeno/Downloads/cabin_diagnostics/` snapshot diff). Null when Bluetooth
     * audio mode is selected (no AAOS media source advertised).
     */
    injectedMediaSessionManager: MediaSessionManager? = null,
) {
    private val context: Context = context.applicationContext

    init {
        NavigationStateManager.initialize(context.applicationContext)
        // PNG export is an explicit debug opt-in; normal navigation never enables it.
        // Enable the runtime composer (D16). Without this, lookups return null and the
        // existing static-XML / AA-bitmap paths handle the cluster icon — same behavior as
        // before D16. Turning this ON activates the per-route precompose + cluster icon
        // override pipeline. Optional debug exports have their own explicit opt-in.
        com.cabin.navigation.compose.ComposedIconStore.setEnabled(true)

        // VCU/VCUNH1 (CT5, AAOS 14) enum-only cluster gate. There the cluster is a separate QNX
        // safety-domain VM that renders a native Altia sprite from the Maneuver enum and MASKS the
        // app bitmap/URI (see documents/reference/gminfo/projection/cluster_maneuver_mapping.md §0/§4).
        // The roundabout WITH_ANGLE refinement (ManeuverMapper) still feeds that enum, but the
        // per-maneuver bitmap compose and the AA-bitmap shim are dead work there — skip both.
        // BEST-EFFORT / UNTESTED detection (no VCUNH1 hardware); harmless elsewhere (default off).
        val clusterIsEnumOnly = PlatformDetector.detect(context).isVcuCluster()
        com.cabin.navigation.compose.ComposedIconStore.setBitmapComposeEnabled(
            !clusterIsEnumOnly && !BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE,
        )
        if (clusterIsEnumOnly) {
            NavigationStateManager.setClusterEnumOnly(true)
        }
    }

    // Config can be updated when actual surface dimensions are known
    private var config: AdapterConfig = initialConfig

    companion object {
        private const val USB_WAIT_PERIOD_MS = 3000L
        private const val PAIR_TIMEOUT_MS = 15000L

        // Auto-reconnect constants
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_RECONNECT_DELAY_MS = 2000L // Start with 2 seconds
        private const val MAX_RECONNECT_DELAY_MS = 30000L // Cap at 30 seconds
        private const val TARGET_CONNECT_TIMEOUT_MS = 10_000L
        private const val MIC_RECOVERY_MAX_DELAY_MS = 10_000L
        private const val MIC_STABLE_WINDOW_MS = 10_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 90 * 60 * 1000L
        private const val WAKE_LOCK_REFRESH_MS = 60 * 60 * 1000L

        // Surface debouncing - wait for size to stabilize before updating codec
        private const val SURFACE_DEBOUNCE_MS = 150L

        // AA MOVE rate-limit interval (~60 fps budget).
        //
        // IMPORTANT — different semantics from AutoKit despite the shared 17 ms number:
        // AutoKit uses 17 ms as a STATIONARY-FINGER MOVE REPEATER: on DOWN it starts a
        // periodic timer that keeps re-sending the captured-at-DOWN position every
        // 17 ms until UP cancels it, so the phone keeps receiving MOVE updates even
        // when the finger is not moving. AutoKit does NOT rate-limit OS-delivered
        // MotionEvents — every ACTION_MOVE is sent to USB immediately.
        //
        // cabin_native uses 17 ms as the OPPOSITE: a rate-limit on OS-delivered
        // MOVE events (skip a MOVE send when <17 ms has elapsed since the previous
        // one). This caps the per-second packet rate but does NOT emit the synthetic
        // stationary-finger repeats AutoKit does. If AA ever drops projection on
        // long stationary touches, implementing AutoKit's repeater pattern may be
        // required.
        //
        // Wire-format cross-reference (verified against the AutoKit implementation):
        // type 0x05 SingleTouch, 16-byte payload, action codes 14/15/16 for
        // DOWN/MOVE/UP, coordinates normalized to 0-10000, flag word at offset 12
        // packs encoderType | (offScreen << 16). See [MessageSerializer.serializeSingleTouch].
        //
        // Applies only to the AA path (type 0x05 SingleTouch); CarPlay sessions use
        // 0x17 MultiTouch and do not consult this constant. 2026-04-20 UI_TOUCH traces
        // (2 CarPlay sessions, ~100min) show pointers=1 on every event; zero multi-touch.
        private const val AA_TOUCH_THROTTLE_NS = 17_000_000L

        // ---------------------------------------------------------------------------------
        // Reconnect-escalation patterns (in-source conventions)
        //
        // These "Pattern A/B/C" labels are named as-if from an external runbook, but the
        // runbook lives only in-source — define them here so the sites that reference them
        // (see handleError body, and the SCANNING_DEVICE branch in handleMessage) stay in sync.
        //
        //   Pattern A — "no initial response" errors in a row (consecutiveNoResponse >= 2).
        //               The adapter's USB write path appears dead; retrying won't help.
        //               Surfaced as context.localizedString(R.string.connection_status_unresponsive).
        //
        //   Pattern B — SCANNING_DEVICE command arrives after a prior PLUGGED session
        //               (hadPriorSession && reconnectAttempts > 0). The adapter is alive
        //               and scanning but the phone isn't coming back — wireless subsystem
        //               may be stuck. Surfaced as context.localizedString(R.string.connection_status_scanning_paused).
        //
        //   Pattern C — STREAMING sessions that die within SHORT_SESSION_THRESHOLD_MS,
        //               SHORT_SESSION_ESCALATION_COUNT times in a row → "connection unstable"
        //               (likely ZLP or firmware instability). Surfaced as
        //               context.localizedString(R.string.connection_status_unstable).
        //
        // Keep these labels/thresholds stable; remote logs filter on the "[ESCALATION]" tag.
        // ---------------------------------------------------------------------------------

        // Pattern C: STREAMING sessions shorter than this are "short-lived" (unstable adapter)
        private const val SHORT_SESSION_THRESHOLD_MS = 10_000L
        // Pattern C: this many consecutive short sessions → "connection unstable"
        private const val SHORT_SESSION_ESCALATION_COUNT = 2
    }

    /**
     * Connection state enum.
     */
    enum class State {
        DISCONNECTED,
        CONNECTING,
        DEVICE_CONNECTED,
        STREAMING,
    }

    /**
     * Media metadata information.
     */
    data class MediaInfo(
        val songTitle: String?,
        val songArtist: String?,
        val albumName: String?,
        val appName: String?,
        val albumCover: ByteArray?,
        val duration: Long,
        val position: Long,
        val isPlaying: Boolean,
    )

    /**
     * Information about a paired device from the adapter's DevList.
     */
    data class DeviceInfo(
        val btMac: String,
        val name: String,
        val type: String, // "CarPlay", "AndroidAuto", "HiCar"
        val lastConnected: String? = null, // timestamp (CarPlay only)
        val rfcomm: String? = null, // RFCOMM channel (CarPlay only)
    )

    /**
     * Callback interface for Cabin events.
     */
    interface Callback {
        fun onStateChanged(state: State)

        fun onStatusTextChanged(text: String)

        fun onHostUIPressed()

        /** Called when phone type becomes known (PLUGGED) or cleared (disconnect/error). */
        fun onPhoneTypeChanged(phoneType: PhoneType) {}

        /** Called when the adapter's paired device list changes. */
        fun onDeviceListChanged(devices: List<DeviceInfo>) {}
    }

    /**
     * Listener for device management events (device list changes, connection state).
     * Unlike [Callback], multiple listeners can be registered concurrently.
     */
    fun interface DeviceListener {
        fun onDeviceListChanged(devices: List<DeviceInfo>)
    }

    private val deviceListeners = mutableListOf<DeviceListener>()

    fun addDeviceListener(listener: DeviceListener) {
        synchronized(deviceListeners) { deviceListeners.add(listener) }
    }

    fun removeDeviceListener(listener: DeviceListener) {
        synchronized(deviceListeners) { deviceListeners.remove(listener) }
    }

    private fun notifyDeviceListeners(devices: List<DeviceInfo> = _deviceList) {
        val listeners = synchronized(deviceListeners) { deviceListeners.toList() }
        listeners.forEach { it.onDeviceListChanged(devices) }
    }

    /**
     * AA center-crop parameters for SurfaceView oversize/clip layout and touch remapping.
     * Null when not applicable (CarPlay, 16:9 display, or no config yet).
     */
    data class AaCropParams(
        val tierWidth: Int,
        val tierHeight: Int,
        val contentWidth: Int,
        val contentHeight: Int,
        val cropLeft: Int,
        val cropTop: Int,
    )

    /** Fixed phone-rendered frame aspect ratio used to prevent SurfaceView stretching. */
    fun configuredVideoAspectRatio(): Float =
        if (config.width > 0 && config.height > 0) config.width.toFloat() / config.height.toFloat() else 16f / 9f

    // Coroutine scope for async operations
    // A failed optional child (metadata, watchdog, delayed recovery, etc.) must not
    // cancel the manager's entire lifetime and silently disable future recovery.
    private val managerExceptionHandler =
        CoroutineExceptionHandler { _, error ->
            logError(
                "[ASYNC] Manager task failed without cancelling the session: " +
                    "${error.message ?: error.javaClass.simpleName}",
                tag = Logger.Tags.ADAPTR,
            )
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + managerExceptionHandler)
    private val touchSender = OrderedTouchSender(scope) { error ->
        logWarn("[TOUCH] Send failed: ${error.message}", tag = Logger.Tags.TOUCH)
    }

    // Kept separate from [scope] because release cancels [scope] as its final step.
    // This scope exists only to let synchronous UI/lifecycle entry points request an
    // asynchronous teardown without blocking the main thread.
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes every mutation of the USB session. */
    private val lifecycleMutex = Mutex()
    private val released = AtomicBoolean(false)
    private val shouldBeRunning = AtomicBoolean(false)
    /** Current user intent, independent from a delayed USB state transition. */
    val projectionSessionRequested: Boolean get() = shouldBeRunning.get()
    private val restartPending = AtomicBoolean(false)
    private val errorRecoveryPending = AtomicBoolean(false)
    private val negotiationResetPending = AtomicBoolean(false)

    // Current state
    private val currentState = AtomicReference(State.DISCONNECTED)
    val state: State get() = currentState.get()
    private val projectionHealth = com.cabin.platform.ProjectionHealthStore(android.os.SystemClock::elapsedRealtime)
    val dashboardState = projectionHealth.state

    /** Read-only on-demand checks. Never requests permissions, opens USB, or changes user intent. */
    fun projectionReadinessSnapshot(): ProjectionReadinessSnapshot {
        val usb = try {
            val adapters = usbManager.deviceList.values.filter { KnownDevices.isKnownDevice(it.vendorId, it.productId) }
            projectionUsbReadiness(adapters.map { device ->
                try {
                    usbManager.hasPermission(device)
                } catch (_: RuntimeException) {
                    null
                }
            })
        } catch (_: RuntimeException) {
            projectionUsbReadiness(null)
        }
        val preferences = try {
            AdapterConfigPreference.getInstance(context).getUserConfigSync()
        } catch (_: RuntimeException) {
            null
        }
        fun permissionIfRequested(requested: Boolean?, permission: String): Boolean? {
            if (requested != true) return null
            return try {
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            } catch (_: RuntimeException) {
                null
            }
        }
        val appMicrophoneRequested = preferences?.let { it.micSource == MicSourceConfig.APP }
        val gpsRequested = preferences?.gpsForwarding
        return ProjectionReadinessSnapshot(
            state = state,
            sessionRequested = shouldBeRunning.get(),
            phoneConnectionAllowed = phoneAutoConnectEnabled.get(),
            usb = usb,
            adapterOpened = usbDevice?.isOpened == true,
            phoneType = currentPhoneType,
            microphone = projectionOptionalCapability(
                requested = appMicrophoneRequested,
                permissionGranted = permissionIfRequested(appMicrophoneRequested, Manifest.permission.RECORD_AUDIO),
                backgroundCapabilitiesAvailable = sensitiveBackgroundCapabilitiesAvailable,
            ),
            location = projectionOptionalCapability(
                requested = gpsRequested,
                permissionGranted = permissionIfRequested(gpsRequested, Manifest.permission.ACCESS_FINE_LOCATION),
                backgroundCapabilitiesAvailable = sensitiveBackgroundCapabilitiesAvailable,
            ),
        )
    }

    // Session-scoped unknown data counters — reset on connect, dumped on disconnect
    private var unknownMessageTypeCount = 0
    private var unknownMediaSubtypeCount = 0
    private var unknownCommandCount = 0
    private var unknownAudioCommandCount = 0
    private var unknownPhoneTypeCount = 0
    private var unknownBoxSettingsKeyCount = 0
    private val unknownMessageTypes = mutableSetOf<Int>()     // raw type IDs seen
    private val unknownMediaSubtypes = mutableSetOf<Int>()    // raw subtype IDs seen
    private val unknownCommandIds = mutableSetOf<Int>()       // raw command IDs seen
    private val unknownAudioCommandIds = mutableSetOf<Int>()  // raw audio cmd IDs seen

    // Video frame logging throttle — log every 30th frame to reduce logcat spam
    private var videoFrameCount = 0L

    // Callback
    @Volatile private var callback: Callback? = null

    @Volatile
    private var currentStatusText: String = "Connect Adapter"

    // USB
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var usbDevice: UsbDeviceWrapper? = null

    // Wake lock to prevent CPU sleep during USB streaming
    // PARTIAL_WAKE_LOCK keeps CPU running but allows screen to turn off
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock: PowerManager.WakeLock =
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Cabin::UsbStreamingWakeLock",
        ).apply { setReferenceCounted(false) }
    private var wakeLockRefreshJob: Job? = null

    // Protocol
    @Volatile private var adapterDriver: AdapterDriver? = null

    // Video
    @Volatile private var h264Renderer: H264Renderer? = null
    @Volatile private var videoSurface: Surface? = null
    private var lastVideoDiscardWarningTime = 0L // Throttle discard warnings
    @Volatile private var headlessMode = false
    @Volatile private var videoPaused = true
    @Volatile private var videoOverlayCovered = false
    private val pendingUserVideoReset = AtomicBoolean(false)
    @Volatile private var surfaceReadyForDeferredCodec = false
    @Volatile private var hasVideoFocus = true
    @Volatile private var requestKeyframeOnCodecStart = false

    /** True once we've inferred phone type from video header (mid-session rejoin). */
    private var videoPhoneTypeInferred = false

    /** True when codec start is deferred until phone type is known (PLUGGED or video inference). */
    // Codec start decision sites — keep in sync:
    //   (1) initialize() debounce fires → startCodecIfDeferred (in the debounce callback
    //       around the `if (codecDeferred && currentPhoneType != null)` check).
    //   (2) PLUGGED CarPlay branch (~L1610-1612 — the `if (message.phoneType != ANDROID_AUTO)`
    //       guard before `startCodecIfDeferred()`).
    //   (3) videoProcessor fallback (~L2673-2675 — `if (codecDeferred) { startCodecIfDeferred() }`
    //       in createVideoProcessor.processVideoDirect). This is the actual codec-start path
    //       for mid-session rejoin; it fires earlier than the video-header keyframe-nudge block.
    //   (4) Keyframe nudge on video-header phone-type inference (~L2727 — the non-AA
    //       `scheduleDelayedKeyframe` branch). NOTE: this does NOT start the codec —
    //       scheduleDelayedKeyframe only schedules periodic FRAME commands on a timer.
    //       The codec has already been started by site (3) by the time this runs.
    //   (5) startCodecIfDeferred itself (the single implementation all the above call).
    // Any NEW codec-start trigger must be added to this list AND to the function it calls.
    @Volatile private var codecDeferred = true

    /** Actual video surface dimensions (may differ from config due to Compose inset behavior). */
    private var actualSurfaceWidth = 0
    private var actualSurfaceHeight = 0

    // Video state from adapter header — tracked for AA touch flags (AutoKit packs these in touch payload)
    // encoderType: 1=H264, 2=H265(default/initial), 4=MJPEG
    // offScreen: 0=on-screen, 1=off-screen
    // Concurrency: read by sendMultiTouch on Dispatchers.IO when packing the AA touch-frame
    // flag word. Multiple writers: videoProcessor on USB read thread (primary), stop() on
    // main thread, handleError() on the call-site thread. All writes are simple constants
    // (not RMW), so @Volatile is still sufficient — single-writer claim was incorrect.
    @Volatile private var videoEncoderType = 2

    @Volatile private var videoOffScreen = 0

    // Audio
    private var audioManager: DualStreamAudioManager? = null
    private var audioInitialized = false

    // Microphone
    private var microphoneManager: MicrophoneCaptureManager? = null
    @Volatile private var isMicrophoneCapturing = false
    private var currentMicDecodeType = 5 // 16kHz mono
    private var currentMicAudioType = 3 // Siri/voice input
    private var lastIncomingDecodeType = 5 // Track adapter's current audio format (from incoming AudioData)
    private var micSendTimer: Timer? = null

    /**
     * Voice mode tracking for proper microphone lifecycle management.
     *
     * CRITICAL: CarPlay sends PHONECALL_START before SIRI_STOP when making calls via Siri.
     * Without tracking, SIRI_STOP would kill the phone call's microphone.
     *
     * Observed sequence (from USB capture analysis):
     *   217.13s: PHONECALL_START  → mic should stay active
     *   217.26s: SIRI_STOP        → must NOT stop mic (phone call active)
     *
     * NOTE: the specific timestamps above were observed on real hardware during development;
     * the corresponding USB capture is not checked in to this repo. Treat them as anchoring
     * folklore — the *ordering* (PHONECALL_START before SIRI_STOP, ~130ms apart) is the load-
     * bearing invariant, not the absolute timestamps.
     */
    private enum class VoiceMode { NONE, SIRI, PHONECALL }

    @Volatile private var activeVoiceMode = VoiceMode.NONE

    // Audio routing state flags for two-factor PCM routing (replaces currentStreamPurpose)
    @Volatile private var isSiriAudioActive = false
    @Volatile private var isPhoneCallAudioActive = false
    @Volatile private var isAlertAudioActive = false

    // GNSS
    private var gnssForwarder: GnssForwarder? = null

    // MediaSession
    // App-scope-owned (see constructor KDoc on [injectedMediaSessionManager]). This
    // CabinManager only borrows the reference — [initialize] attaches the transport
    // callback, [release] detaches it. The MediaLibrarySession itself is NEVER released
    // from here; that's MainActivity.onDestroy's job.
    private var mediaSessionManager: MediaSessionManager? = injectedMediaSessionManager

    // Timers
    private var pairTimeout: Timer? = null
    private var frameIntervalJob: Job? = null
    private var videoLivenessJob: Job? = null
    @Volatile private var lastVideoFrameReceivedMs = 0L

    // Phone type tracking for keyframe request decisions
    /** Current phone type (CarPlay, Android Auto, etc.) from the PLUGGED message. Null when no phone connected. */
    @Volatile var currentPhoneType: PhoneType? = null
        private set

    // Auto-reconnect on USB disconnect
    private var reconnectJob: Job? = null
    private var reconnectAttempts: Int = 0
    private var targetConnectTimeoutJob: Job? = null

    // Status escalation: detect degraded adapter states and give actionable user feedback.
    // - hadPriorSession: true if PLUGGED was received at least once since last stop().
    //   Note: start() only resets it indirectly when it calls stop() internally (which
    //   requires an existing adapterDriver). After handleError() followed by a fresh
    //   start(), the flag can survive.
    //   Distinguishes "adapter broken after session died" from "normal waiting for first phone".
    // - consecutiveNoResponse: counts sequential "no initial response" errors (Pattern A: USB write dead).
    // - shortLivedStreamingCount: counts STREAMING sessions that die within SHORT_SESSION_THRESHOLD_MS
    //   (Pattern C: unstable rapid cycling from ZLP or firmware issue).
    // - lastStreamingStartMs: timestamp when STREAMING was entered, for short-session detection.
    private var hadPriorSession: Boolean = false
    private var consecutiveNoResponse: Int = 0
    private var shortLivedStreamingCount: Int = 0
    private var lastStreamingStartMs: Long = 0L

    // Phase 13 (negotiation_failed) — prevents auto-restart loop when iPhone rejects config
    private var negotiationRejected: Boolean = false

    // Targeted connect: when set, the next restart() cycle sends AutoConnect_By_BtAddress
    // instead of the normal WIFI_CONNECT (1002) auto-connect scan.
    @Volatile private var pendingConnectTarget: String? = null

    // Phone intent is independent of the adapter/service lifetime. Automatic USB,
    // wake and error recovery must retain an explicit phone Disconnect.
    private val phoneConnectionPreferences = context.getSharedPreferences("phone_connection_intent", Context.MODE_PRIVATE)
    private val phoneAutoConnectEnabled = AtomicBoolean(phoneConnectionPreferences.getBoolean("auto_connect", true))
    private val phoneConnectionRequest = AtomicReference(Any())

    // Last targeted MAC — retained after pendingConnectTarget is consumed by start(),
    // so PLUGGED handler can set _connectedBtMac even if PeerBluetoothAddress never arrives.
    @Volatile private var lastConnectTargetMac: String? = null

    // Background services may be promoted with connectedDevice only on sticky starts.
    // Keep that OS capability separate from user preferences so startLocked() cannot
    // accidentally re-enable while-in-use microphone/location features.
    @Volatile private var sensitiveBackgroundCapabilitiesAvailable = true

    private var micRecoveryJob: Job? = null
    private var micRecoveryAttempts = 0
    private var micCaptureStartedAtMs = 0L

    // Surface update debouncing - prevents repeated codec recreation during rapid surface size changes
    private var surfaceUpdateJob: Job? = null
    private var pendingSurface: Surface? = null
    private var pendingSurfaceWidth: Int = 0
    private var pendingSurfaceHeight: Int = 0
    private var pendingCallback: Callback? = null

    // Media metadata tracking
    private var lastMediaSongName: String? = null
    private var lastMediaArtistName: String? = null
    private var lastMediaAlbumName: String? = null
    private var lastMediaAppName: String? = null
    private var lastAlbumCover: ByteArray? = null
    private var lastDuration: Long = 0L
    private var lastPosition: Long = 0L
    private var lastIsPlaying: Boolean = true

    // Device identification and management. Immutable snapshots are published through the
    // volatile reference; every read-modify-write operation is serialized by deviceListLock.
    @Volatile private var _deviceList: List<DeviceInfo> = emptyList()
    private val deviceListLock = Any()
    @Volatile private var _connectedBtMac: String? = null // from PeerBluetoothAddress or BoxSettings #2

    /** The adapter's list of paired wireless devices (from BoxSettings DevList). */
    val pairedDevices: List<DeviceInfo> get() = _deviceList

    /** BT MAC of the currently connected phone (null if none). */
    val connectedBtMac: String? get() = _connectedBtMac

    /** WiFi status from PluggedMessage: 0=USB wired, 1=wireless, null=unknown. */
    @Volatile var currentWifi: Int? = null
        private set

    /** Clears cached media metadata to prevent stale data on reconnect. */
    private fun clearCachedMediaMetadata() {
        projectionHealth.clearMedia()
        lastMediaSongName = null
        lastMediaArtistName = null
        lastMediaAlbumName = null
        lastMediaAppName = null
        lastAlbumCover = null
        lastDuration = 0L
        lastPosition = 0L
        lastIsPlaying = true
        _connectedBtMac = null
        currentWifi = null
        NavigationStateManager.clear()
        mediaSessionManager?.updateTeyesClusterNavigation(com.cabin.navigation.NavigationState())
    }

    // Executors
    private val executors = AppExecutors()

    // LogCallback for Java components — routes to Logger with proper tags
    private val logCallback =
        object : LogCallback {
            override fun log(message: String) {
                this@CabinManager.log(message)
            }

            override fun log(
                tag: String,
                message: String,
            ) {
                Logger.d(message, tag)
            }

            override fun logPerf(
                tag: String,
                message: String,
            ) {
                if (Logger.isDebugLoggingEnabled() && Logger.isTagEnabled(tag)) {
                    Logger.d(message, tag)
                }
            }
        }

    /**
     * Initialize the manager with a Surface and actual surface dimensions.
     *
     * Uses SurfaceView's Surface directly for optimal HWC overlay rendering.
     * This bypasses GPU composition for lower latency and power consumption.
     *
     * Preconditions and call contract:
     * - Must be called from the main thread.
     * - Safe to call repeatedly (size changes go through a 150 ms debounce;
     *   see [SURFACE_DEBOUNCE_MS] and the [surfaceUpdateJob] path below).
     * - The callback reference is retained until the next initialize() call or [release].
     * - NOTE: the FIRST call (h264Renderer == null) BYPASSES the debounce — it proceeds
     *   immediately to build the renderer. Only subsequent calls (h264Renderer != null)
     *   use the debounce guard to coalesce rapid surface size changes from Compose layout.
     *
     * @param surface The Surface from SurfaceView to render video to
     * @param surfaceWidth Actual width of the surface in pixels
     * @param surfaceHeight Actual height of the surface in pixels
     * @param callback Callbacks for state changes and events
     */
    fun initialize(
        surface: Surface,
        surfaceWidth: Int,
        surfaceHeight: Int,
        callback: Callback,
    ) {
        val attachingToHeadlessStream = headlessMode && state == State.STREAMING
        headlessMode = false
        videoPaused = videoOverlayCovered
        surfaceReadyForDeferredCodec = false
        if (attachingToHeadlessStream) {
            // The decoder missed SPS/PPS/IDR while video was intentionally drained in
            // headless mode. Ask for a fresh random-access frame only after the final
            // visible Surface (including AA's resize) has started the codec.
            requestKeyframeOnCodecStart = true
        }

        // Round to even numbers for H.264 compatibility
        val evenWidth = surfaceWidth and 1.inv()
        val evenHeight = surfaceHeight and 1.inv()

        // Track actual surface dims for AA crop/touch calculations
        val prevSurfaceWidth = actualSurfaceWidth
        val prevSurfaceHeight = actualSurfaceHeight
        actualSurfaceWidth = evenWidth
        actualSurfaceHeight = evenHeight

        // Config resolution was pre-computed from stable WindowMetrics in MainActivity.
        // Do NOT override with surface dimensions — SurfaceView size oscillates during
        // Compose layout (systemBars insets apply asynchronously on AAOS).
        if (config.userSelectedResolution) {
            logInfo(
                "[RES] Using user-selected resolution ${config.width}x${config.height} " +
                    "(surface: ${evenWidth}x$evenHeight)",
                tag = Logger.Tags.VIDEO,
            )
        } else {
            logInfo(
                "[RES] Using pre-computed resolution ${config.width}x${config.height} " +
                    "(surface: ${evenWidth}x$evenHeight)",
                tag = Logger.Tags.VIDEO,
            )
        }

        // LIFECYCLE FIX: If renderer exists, always update surface via setOutputSurface().
        //
        // CRITICAL: Do NOT use reference equality (===) to check if Surface is "the same".
        // After app goes to background, the Surface Java object may be the same reference,
        // but the underlying native BufferQueue is DESTROYED and recreated.
        // The codec will be rendering to a dead buffer → "BufferQueue has been abandoned" error.
        //
        // Solution: Always call setOutputSurface() when initialize() is called with an existing
        // renderer. This ensures the codec always has a valid native surface.
        // See: https://developer.android.com/reference/android/media/MediaCodec#setOutputSurface
        //
        // DEBOUNCE FIX: Surface size changes rapidly during layout (996→960→965→969→992).
        // Each change triggers codec recreation. Debounce to wait for size stabilization.
        // NOTE: the FIRST initialize() call (h264Renderer == null) takes the un-debounced
        // fast path below this block — the renderer must exist before the debounce can
        // meaningfully coalesce size changes. Only subsequent calls go through the
        // debounced `surfaceUpdateJob` path.
        if (h264Renderer != null) {
            // Store pending values
            pendingSurface = surface
            pendingSurfaceWidth = evenWidth
            pendingSurfaceHeight = evenHeight
            pendingCallback = callback

            // Cancel any pending update
            surfaceUpdateJob?.cancel()

            // Debounce: wait for surface size to stabilize before updating codec
            surfaceUpdateJob =
                scope.launch {
                    delay(SURFACE_DEBOUNCE_MS)

                    // Use the latest pending values after debounce
                    val finalSurface = pendingSurface ?: return@launch
                    val finalCallback = pendingCallback ?: return@launch

                    logInfo(
                        "[LIFECYCLE] Surface stabilized at " +
                            "${pendingSurfaceWidth}x$pendingSurfaceHeight - updating codec",
                        tag = Logger.Tags.VIDEO,
                    )

                    // Persistent-divergence detector: the codec decodes at the pre-computed
                    // config (stable WindowMetrics, see L584-586); the surface dims above are the
                    // live UI area. A mismatch at first-init is an expected startup transient that
                    // the debounce coalesces away — but if it SURVIVES the debounce and lands here,
                    // the on-screen area genuinely disagrees with the projection area (visual
                    // scale/crop mismatch), not a layout artifact. Skip when the user pinned a
                    // custom resolution (config intentionally != surface then).
                    if (!config.userSelectedResolution &&
                        (pendingSurfaceWidth != config.width || pendingSurfaceHeight != config.height)
                    ) {
                        logWarn(
                            "[RES] Surface stabilized at ${pendingSurfaceWidth}x$pendingSurfaceHeight " +
                                "but codec is ${config.width}x${config.height} — persistent divergence " +
                                "(UI area != projection area, " +
                                "Δ=${config.width - pendingSurfaceWidth}x${config.height - pendingSurfaceHeight})",
                            tag = Logger.Tags.VIDEO,
                        )
                    }

                    this@CabinManager.callback = finalCallback
                    this@CabinManager.videoSurface = finalSurface
                    surfaceReadyForDeferredCodec = true

                    // If container dimensions changed during an AA session, resend
                    // BoxSettings so the phone re-renders for the new content area.
                    // prevSurfaceWidth/Height captured before actualSurface* was overwritten.
                    val dimsChanged =
                        prevSurfaceWidth > 0 && prevSurfaceHeight > 0 &&
                            (prevSurfaceWidth != pendingSurfaceWidth || prevSurfaceHeight != pendingSurfaceHeight)
                    if (dimsChanged && currentPhoneType == PhoneType.ANDROID_AUTO) {
                        resendBoxSettings()
                        // Notify UI so oversized surface recalculates for new crop params
                        finalCallback.onPhoneTypeChanged(PhoneType.ANDROID_AUTO)
                    }

                    if (codecDeferred && currentPhoneType != null) {
                        // AA resize complete — start codec with the new oversized surface
                        startCodecIfDeferred()
                    } else if (!codecDeferred && !videoOverlayCovered) {
                        // Resume with new surface - this calls setOutputSurface() internally
                        synchronized(this@CabinManager) {
                            if (!videoOverlayCovered) resumeVideoRenderer(finalSurface)
                        }
                        if (state == State.STREAMING && currentPhoneType != PhoneType.ANDROID_AUTO) {
                            scheduleDelayedKeyframe()
                        }
                    }
                }
            return
        }

        // First-time initialization - create new renderer
        this.callback = callback
        this.videoSurface = surface

        logInfo(
            "[RES] Initializing with surface ${evenWidth}x$evenHeight @ ${config.fps}fps, ${config.dpi}dpi",
            tag = Logger.Tags.VIDEO,
        )

        // Detect platform for optimal audio configuration
        // Pass user-configured sample rate from AdapterConfig (overrides platform default)
        val platformInfo = PlatformDetector.detect(context)
        val audioConfig = AudioConfig.forPlatform(platformInfo, userSampleRate = config.sampleRate)

        logInfo(
            "[PLATFORM] Using AudioConfig: sampleRate=${audioConfig.sampleRate}Hz, " +
                "bufferMult=${audioConfig.bufferMultiplier}x, prefill=${audioConfig.prefillThresholdMs}ms",
            tag = Logger.Tags.AUDIO,
        )
        logInfo(
            "[PLATFORM] Using VideoDecoder: " +
                "${platformInfo.hardwareH264DecoderName ?: "generic (createDecoderByType)"}" +
                if (platformInfo.requiresIntelMediaCodecFixes()) {
                    " [Intel VPU workaround enabled]"
                } else {
                    ""
                },
            tag = Logger.Tags.VIDEO,
        )

        // Initialize H264 renderer with Surface for direct HWC rendering
        h264Renderer =
            H264Renderer(
                config.width,
                config.height,
                surface,
                logCallback,
                executors,
                platformInfo.hardwareH264DecoderName,
            )

        // Set keyframe callback - after codec reset, we need to request a new IDR frame
        // from the adapter. Without SPS/PPS + keyframe, the decoder cannot produce output.
        h264Renderer?.setKeyframeRequestCallback {
            if (videoOverlayCovered) return@setKeyframeRequestCallback
            logInfo("[KEYFRAME] Requesting keyframe after codec reset", tag = Logger.Tags.VIDEO)
            adapterDriver?.sendCommand(CommandMapping.FRAME)
        }

        // Set CSD extraction callback — persist SPS/PPS for future codec pre-warming
        h264Renderer?.setCsdExtractedCallback { sps, spsLen, pps, ppsLen ->
            val btMac = connectedBtMac ?: return@setCsdExtractedCallback
            val cacheKey = "${btMac}_${config.width}x${config.height}"
            val prefs = context.getSharedPreferences("carlink_csd_cache", Context.MODE_PRIVATE)

            val spsData = sps.copyOf(spsLen)
            val ppsData = if (pps != null && ppsLen > 0) pps.copyOf(ppsLen) else ByteArray(0)

            prefs.edit {
                putString("sps_$cacheKey", android.util.Base64.encodeToString(spsData, android.util.Base64.NO_WRAP))
                putString("pps_$cacheKey", android.util.Base64.encodeToString(ppsData, android.util.Base64.NO_WRAP))
            }

            logInfo("[DEVICE] CSD cached for $cacheKey: SPS=${spsLen}B, PPS=${ppsLen}B", tag = Logger.Tags.VIDEO)
        }

        // Defer codec start until phone type is known (PLUGGED message).
        // For AA, the UI will resize the SurfaceView to tier AR first (oversized + clip),
        // which triggers surface destruction/creation. Starting the codec AFTER the resize
        // avoids the 60s black screen from losing the active decoder's IDR reference.
        // For CarPlay, the codec starts immediately at PLUGGED since no resize is needed.
        codecDeferred = true

        logInfo("Video subsystem initialized, codec deferred until phone type known", tag = Logger.Tags.VIDEO)

        initializeSessionSubsystems(audioConfig)
        publishCurrentSnapshot(callback)

        // A headless foreground session can be adopted after PLUGGED/STREAMING. CarPlay
        // can start immediately on the newly attached surface. Android Auto first needs
        // MainScreen's phone-type callback to resize the SurfaceView to its tier aspect ratio.
        currentPhoneType?.let { phoneType ->
            if (phoneType != PhoneType.ANDROID_AUTO) {
                startCodecIfDeferred()
            }
        }

        logInfo("CabinManager initialized", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Initialize USB-session dependencies without a video Surface.
     *
     * Used by the widget foreground service. Video packets are consumed and dropped by
     * the direct USB path until an Activity adopts this manager and calls [initialize]
     * with its real SurfaceView. Audio, metadata, controls, microphone, GNSS and adapter
     * keepalive remain fully operational in the background.
     */
    fun initializeHeadless(callback: Callback) {
        headlessMode = true
        videoPaused = true
        this.callback = callback
        if (actualSurfaceWidth <= 0 || actualSurfaceHeight <= 0) {
            actualSurfaceWidth = config.width and 1.inv()
            actualSurfaceHeight = config.height and 1.inv()
        }
        val platformInfo = PlatformDetector.detect(context)
        val audioConfig = AudioConfig.forPlatform(platformInfo, userSampleRate = config.sampleRate)
        initializeSessionSubsystems(audioConfig)
        publishCurrentSnapshot(callback)
        logInfo(
            "CabinManager initialized headlessly at ${actualSurfaceWidth}x$actualSurfaceHeight",
            tag = Logger.Tags.ADAPTR,
        )
    }

    /**
     * Detach a visible Surface while preserving USB/audio/media ownership in a foreground
     * service. The next [initialize] call creates a fresh decoder and requests a keyframe
     * after its final Surface is ready.
     */
    fun enterHeadlessMode(callback: Callback) {
        surfaceUpdateJob?.cancel()
        surfaceUpdateJob = null
        pendingSurface = null
        pendingCallback = null
        videoSurface = null
        h264Renderer?.stop()
        h264Renderer = null
        codecDeferred = true
        headlessMode = true
        videoPaused = true
        this.callback = callback
        publishCurrentSnapshot(callback)
        logInfo("CabinManager returned to headless mode", tag = Logger.Tags.ADAPTR)
    }

    /** Apply the foreground-service capabilities Android actually granted this run. */
    fun setSensitiveBackgroundCapabilitiesAvailable(available: Boolean): Job {
        sensitiveBackgroundCapabilitiesAvailable = available
        return scope.launch(Dispatchers.IO) {
            var routingChanged = false
            lifecycleMutex.lock()
            try {
                if (released.get()) return@launch
                // Read the latest capability value after taking the lock. A queued
                // restricted-service update must not undo a newer visible promotion.
                val allowed = sensitiveBackgroundCapabilitiesAvailable
                val preferences = AdapterConfigPreference.getInstance(context).getUserConfigSync()
                val desiredMic = if (allowed && preferences.micSource == MicSourceConfig.APP) "os" else "box"
                val desiredGps = allowed && preferences.gpsForwarding
                routingChanged = adapterDriver != null && config.micType != desiredMic && shouldBeRunning.get()
                val gpsChanged = config.gpsForwarding != desiredGps
                config = config.copy(micType = desiredMic, gpsForwarding = desiredGps)

                if (!allowed) {
                    stopMicrophoneCapture()
                    gnssForwarder?.stop()
                } else {
                    if (desiredGps) ensureGnssForwarder()
                    if (state == State.STREAMING && shouldBeRunning.get() && desiredGps) {
                        if (gpsChanged) adapterDriver?.sendCommand(CommandMapping.START_GNSS_REPORT)
                        // This also retries a start that previously failed for missing
                        // location permission, without needing another STREAMING event.
                        gnssForwarder?.start()
                    } else if (!desiredGps) {
                        if (gpsChanged) adapterDriver?.sendCommand(CommandMapping.STOP_GNSS_REPORT)
                        gnssForwarder?.stop()
                    }
                    if (!routingChanged && desiredMic == "os" && activeVoiceMode != VoiceMode.NONE &&
                        !isMicrophoneCapturing && shouldBeRunning.get()
                    ) {
                        startMicrophoneCapture(currentMicDecodeType, currentMicAudioType)
                    }
                }
            } finally {
                lifecycleMutex.unlock()
            }
            // Mic routing is negotiated with the adapter at session initialization.
            // GPS permission alone does not require interrupting the projection.
            if (routingChanged) requestRestart("Foreground microphone routing changed")
        }
    }

    private val gnssCreationLock = Any()

    private fun ensureGnssForwarder() = synchronized(gnssCreationLock) {
        if (gnssForwarder == null) {
            gnssForwarder = GnssForwarder(
                context = context,
                sendGnssData = { adapterDriver?.sendGnssData(it) ?: false },
                logCallback = ::log,
            )
        }
    }

    private fun initializeSessionSubsystems(audioConfig: AudioConfig) {
        if (audioManager == null) {
            audioManager = DualStreamAudioManager(context, logCallback, audioConfig)
        }
        if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) applyTeyesAudioProfile()
        if (microphoneManager == null) {
            microphoneManager = MicrophoneCaptureManager(context, logCallback)
        }
        if (config.gpsForwarding) ensureGnssForwarder()

        val session = mediaSessionManager
        if (session != null) {
            session.setMediaControlCallback(
                object : MediaSessionManager.MediaControlCallback {
                    override fun onPlay() {
                        sendKey(CommandMapping.PLAY)
                    }

                    override fun onPause() {
                        sendKey(CommandMapping.PAUSE)
                    }

                    override fun onStop() {
                        sendKey(CommandMapping.PAUSE)
                    }

                    override fun onSkipToNext() {
                        sendKey(CommandMapping.NEXT)
                    }

                    override fun onSkipToPrevious() {
                        sendKey(CommandMapping.PREV)
                    }
                },
            )
            logInfo("MediaSession transport callback attached (ADAPTER audio mode)", tag = Logger.Tags.ADAPTR)
        } else {
            logInfo("MediaSession not provided (BLUETOOTH audio mode or owner skipped)", tag = Logger.Tags.ADAPTR)
        }
    }

    private fun publishCurrentSnapshot(target: Callback) {
        target.onStateChanged(state)
        target.onStatusTextChanged(currentStatusText)
        currentPhoneType?.let(target::onPhoneTypeChanged)
    }

    /**
     * Start connection to the adapter.
     *
     * Preconditions:
     * - Assumes [initialize] has run at least once so `h264Renderer` is available.
     *   If not, logs a warning and continues in audio-only mode (video data arriving before
     *   a Surface is ready will be discarded — see the guard at the top of this function
     *   and [createVideoProcessor] which warn-and-drops when `h264Renderer == null`).
     * - Duplicate calls are coalesced under [lifecycleMutex]. A new start is accepted only
     *   from DISCONNECTED, so queued Activity/service/USB requests cannot tear down a session.
     */
    suspend fun start() {
        shouldBeRunning.set(true)
        startIfDesired()
    }

    /** Automatic wake recovery uses the existing user-intent latch, never public start(). */
    suspend fun resumeRequestedSession() {
        startIfDesired()
    }

    /** Internal start path. Unlike [start], this never resurrects a user-stopped session. */
    private suspend fun startIfDesired() {
        if (!com.cabin.platform.CarPlayBackendSelection.allowsDongle(context)) return
        withContext(Dispatchers.IO) {
            lifecycleMutex.lock()
            try {
                if (released.get() || !shouldBeRunning.get()) {
                    logWarn("Ignoring start() on released manager", tag = Logger.Tags.ADAPTR)
                    return@withContext
                }
                // Callers first inspect state and then suspend before reaching this lock.
                // Re-check under the lifecycle lock so an Activity, service, USB receiver,
                // and reconnect timer cannot queue multiple starts that tear each other down.
                if (state != State.DISCONNECTED) {
                    logDebug("[LIFECYCLE] Coalesced start() while state=$state", tag = Logger.Tags.ADAPTR)
                    return@withContext
                }
                try {
                    startLocked()
                } catch (cancelled: CancellationException) {
                    // Surface/Activity coroutines may disappear during USB discovery.
                    // Complete cleanup while still owning the lifecycle lock so the
                    // durable service can start again instead of coalescing CONNECTING.
                    withContext(NonCancellable) {
                        stopLocked()
                        if (shouldBeRunning.get() && !released.get()) {
                            setStatusText(context.localizedString(R.string.connection_status_interrupted))
                            scheduleReconnect()
                        }
                    }
                    throw cancelled
                } catch (error: Exception) {
                    logError("Connection start failed: ${error.message}", tag = Logger.Tags.USB)
                    handleErrorLocked("USB connection start failed: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                lifecycleMutex.unlock()
            }
        }
    }

    private suspend fun startLocked() {
        // Guard: Ensure H264Renderer is initialized before starting connection
        // This prevents video data from being discarded when app starts via MediaBrowserService
        // before MainActivity/Surface is ready
        if (h264Renderer == null) {
            logWarn(
                "H264Renderer not initialized - Surface not ready. " +
                    "Video will be discarded until initialize() is called with valid Surface.",
                tag = Logger.Tags.VIDEO,
            )
        }

        // Stop any existing connection before entering CONNECTING state
        // so that FGS started at CONNECTING isn't immediately stopped by stop()→DISCONNECTED
        if (adapterDriver != null || usbDevice != null) {
            stopLocked()
        }

        if (!shouldBeRunning.get() || released.get()) return

        setState(State.CONNECTING)
        setStatusText(context.localizedString(R.string.connection_status_searching))
        resetUnknownCounters()

        // Reset video renderer (only if initialized and codec is active).
        // When codecDeferred=true (fresh init or reconnect), skip reset — the codec
        // will be started by startCodecIfDeferred() at PLUGGED time with the correct
        // surface. Calling reset() here would prematurely start the codec before the
        // surface has stabilized (Compose layout may destroy/recreate the SurfaceView).
        if (!codecDeferred && !videoOverlayCovered) {
            h264Renderer?.reset()
        }

        // Initialize audio
        if (!audioInitialized) {
            audioInitialized = audioManager?.initialize() ?: false
            if (audioInitialized) {
                logInfo("Audio playback initialized", tag = Logger.Tags.AUDIO)
            }
        }

        // Find device
        log("Searching for Carlinkit device...")
        val device = findDevice()
        if (device == null) {
            if (!shouldBeRunning.get() || released.get()) {
                setState(State.DISCONNECTED)
                setStatusText(context.localizedString(R.string.connection_status_disconnected))
                return
            }
            logError("Failed to find Carlinkit device", tag = Logger.Tags.USB)
            setState(State.DISCONNECTED)
            setStatusText(context.localizedString(R.string.connection_status_waiting_retry))
            scheduleReconnect()
            return
        }

        log("Device found and opened")
        usbDevice = device
        setStatusText(context.localizedString(R.string.connection_status_opened))

        // AltVideo (USB 0x2C) gate — evaluated each session start (cheap; PlatformDetector
        // is not cached). Both checks must pass:
        //   1. BuildConfig.DEBUG — production APKs are bit-identical to pre-feature behavior.
        //   2. PlatformDetector.isAaosEmulator() — Build.PRODUCT starts with "sdk_gcar".
        //      Real GM IHU + Pixel + everything else stays inert. ClusterHomeDisplay
        //      (the consumer priv-app) currently runs only on the AAOS emulator.
        // The same boolean is mirrored to MessageSerializer (gates naviScreenInfo JSON
        // -> adapter never emits 0x2C without it) and to NaviVideoSingleton (gates the
        // UsbDeviceWrapper demux split — defense in depth).
        val naviGateEnabled =
            BuildConfig.DEBUG && PlatformDetector.detect(context).isAaosEmulator()
        MessageSerializer.includeNaviScreenInfo = naviGateEnabled
        com.carlink.ipc.NaviVideoSingleton.enabled = naviGateEnabled
        logInfo(
            if (naviGateEnabled) {
                "[NAVI_GATE] AltVideo (0x2C) forwarding ENABLED — debug build on AAOS emulator. " +
                    "naviScreenInfo will be sent in BoxSettings; 0x2C frames will be forwarded to " +
                    "zeno.carlink.ipc.NaviVideoSourceService consumers."
            } else {
                "[NAVI_GATE] AltVideo (0x2C) forwarding disabled (debug=${BuildConfig.DEBUG}, " +
                    "emulator=${PlatformDetector.detect(context).isAaosEmulator()})"
            },
            tag = Logger.Tags.VIDEO,
        )

        // Clear any stale adapter session left by a prior force-kill or crash.
        // The adapter firmware retains session state across USB reconnects. If the previous
        // app process was killed without sending DisconnectPhone+CloseDongle, the adapter
        // stays in PLUGGED/STREAMING state and ignores our OPEN command. Sending these
        // teardown commands before constructing AdapterDriver is safe on idle adapters (no-op).
        log("Clearing stale adapter session state")
        val disconnectFrame = MessageSerializer.serializeDisconnectPhone()
        val closeFrame = MessageSerializer.serializeCloseDongle()
        val disconnectWritten = device.write(disconnectFrame) == disconnectFrame.size
        val closeWritten = device.write(closeFrame) == closeFrame.size
        if (!disconnectWritten || !closeWritten) {
            logError("Failed to clear stale adapter session", tag = Logger.Tags.USB)
            device.close()
            usbDevice = null
            setState(State.DISCONNECTED)
            setStatusText(context.localizedString(R.string.connection_status_write_failed))
            scheduleReconnect()
            return
        }
        // Empirical 200ms floor — shorter values showed "adapter busy" errors on fast
        // reconnect paths. 3-host evidence for firmware 2025.10.15.1127CAY:
        // - POTATO GM AAOS (5 samples): CMD_STOP_PHONE_CONNECTION use-time 277-287ms,
        //   mean ~283ms. 200ms sleep is BELOW the envelope; raise to 300ms if races recur.
        // - AAOS emulator v120 (1 sample): 229ms use-time.
        // - macOS Cabin app SKIPS the cmd entirely and lets adapter self-teardown via
        //   "Host No Response" timeout — a viable alternative strategy. Cited:
        //   /Volumes/POTATO/cpc200/20260420/*.log, adapter_tty_215842_20APR26.log:845,
        //   adapter_tty_214729_20APR26.log:4983 (Host-No-Response path).
        Thread.sleep(320)

        // Create video processor for direct USB -> codec data flow
        // This bypasses message parsing for zero-copy performance (DIRECT_HANDOFF)
        val videoProcessor = createVideoProcessor()

        // Create and start adapter driver
        adapterDriver =
            AdapterDriver(
                usbDevice = device,
                messageHandler = ::handleMessage,
                errorHandler = ::handleError,
                logCallback = ::log,
                videoProcessor = videoProcessor,
                phoneConnectionAllowed = { phoneAutoConnectEnabled.get() },
            )

        // Determine initialization mode based on first-run state and pending changes
        val adapterConfigPref = AdapterConfigPreference.getInstance(context)
        val currentVersionCode =
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
        val initMode = adapterConfigPref.getInitializationMode(currentVersionCode)
        val pendingChanges = adapterConfigPref.getPendingChangesSync()

        // Refresh user-configurable settings from preference store before starting
        // This ensures changes made in Settings screen are applied on next connection
        // (display settings like width/height are kept from original config)
        val userConfig = adapterConfigPref.getUserConfigSync()
        val refreshedConfig =
            config.copy(
                audioTransferMode = userConfig.audioTransferMode,
                // Sample rate is hardcoded to 48kHz - not user configurable
                sampleRate = 48000,
                micType =
                    when {
                        !sensitiveBackgroundCapabilitiesAvailable -> "box"
                        userConfig.micSource == MicSourceConfig.APP -> "os"
                        else -> "box"
                    },
                wifiType =
                    when (userConfig.wifiBand) {
                        WiFiBandConfig.BAND_5GHZ -> "5ghz"
                        WiFiBandConfig.BAND_24GHZ -> "24ghz"
                    },
                callQuality = userConfig.callQuality.value,
                fps = userConfig.fps.fps,
                handDriveMode = userConfig.handDrive.value,
                gpsForwarding = userConfig.gpsForwarding && sensitiveBackgroundCapabilitiesAvailable,
                autoConnectPhone = phoneAutoConnectEnabled.get(),
            )
        config = refreshedConfig // Update stored config for other uses

        log("[INIT] Mode: ${adapterConfigPref.getInitializationInfo(currentVersionCode)}")
        log("[INIT] Audio mode: ${if (refreshedConfig.audioTransferMode) "BLUETOOTH" else "ADAPTER"}")

        setStatusText(context.localizedString(R.string.connection_status_initializing))
        val initSuccess = adapterDriver?.start(refreshedConfig, initMode.name, pendingChanges, actualSurfaceWidth, actualSurfaceHeight) ?: false

        // If a targeted connect was requested (user selected a specific device),
        // override the adapter's wifiConnect auto-connect timer with the target MAC.
        val targetMac = phoneConnectTarget()
        if (targetMac != null) {
            pendingConnectTarget = null
            targetConnectTimeoutJob?.cancel()
            targetConnectTimeoutJob = null
            logInfo("[DEVICE_MGMT] Overriding auto-connect with targeted connect: $targetMac", tag = Logger.Tags.ADAPTR)
            adapterDriver?.overrideAutoConnectWithTarget(targetMac)
            setStatusText(context.localizedString(R.string.connection_status_connecting_device))
        } else if (!phoneAutoConnectEnabled.get()) {
            setStatusText(context.localizedString(R.string.connection_status_phone_paused))
        } else {
            setStatusText(context.localizedString(R.string.connection_status_waiting_phone))
        }

        // Mark first init completed, store version, and clear pending changes
        // ONLY clear pending changes if all init messages were sent successfully —
        // otherwise the changes will be retried on next connection attempt.
        CoroutineScope(Dispatchers.IO).launch {
            if (initSuccess) {
                if (initMode == AdapterConfigPreference.InitMode.FULL) {
                    adapterConfigPref.markFirstInitCompleted()
                }
                adapterConfigPref.updateLastInitVersionCode(currentVersionCode)
                if (pendingChanges.isNotEmpty()) {
                    adapterConfigPref.clearPendingChanges()
                }
            } else {
                logWarn(
                    "[INIT] Init messages failed — pending changes preserved for retry",
                    tag = Logger.Tags.ADAPTR,
                )
            }
        }

        if (!initSuccess) {
            // A partial init sequence can leave the adapter expecting the remainder of a
            // protocol frame. Do not sit in "Waiting for phone" with a poisoned session.
            handleError("USB initialization transfer failed")
            return
        }

        // Start pair timeout
        clearPairTimeout()
        pairTimeout =
            Timer().apply {
                schedule(
                    object : TimerTask() {
                        override fun run() {
                            adapterDriver?.sendCommand(CommandMapping.WIFI_PAIR)
                        }
                    },
                    PAIR_TIMEOUT_MS,
                )
            }
    }

    /**
     * Stop and disconnect.
     *
     * @param reboot when true, issues `rebootAdapter()` (0xCD) in addition to the graceful
     *   teardown; used by the Misc-changed config path in AdapterConfigurationDialog so a
     *   settings change that requires a firmware re-init doesn't leave the adapter in a
     *   stale state. When false (default), sends only the graceful-teardown message.
     */
    fun stop(reboot: Boolean = false) {
        markProjectionStopped()
        teardownScope.launch {
            stopAndWait(reboot)
        }
    }

    /** Awaitable teardown for service/reconfiguration paths that require ordering. */
    suspend fun stopAndWait(reboot: Boolean = false) {
        markProjectionStopped()
        withContext(NonCancellable + Dispatchers.IO) {
            lifecycleMutex.lock()
            try {
                stopLocked(reboot)
            } finally {
                lifecycleMutex.unlock()
            }
        }
    }

    private fun stopLocked(reboot: Boolean = false) {
        touchSender.clear()
        logDebug("[LIFECYCLE] stop() called - clearing keyframe schedule and phoneType", tag = Logger.Tags.VIDEO)
        clearPairTimeout()
        targetConnectTimeoutJob?.cancel()
        targetConnectTimeoutJob = null
        micRecoveryJob?.cancel()
        micRecoveryJob = null
        cancelDelayedKeyframe()
        stopVideoLivenessWatchdog()
        cancelReconnect() // Cancel any pending auto-reconnect
        negotiationRejected = false // Clear rejection flag for fresh connection
        hadPriorSession = false // Reset escalation — user-initiated fresh start
        consecutiveNoResponse = 0
        shortLivedStreamingCount = 0
        currentPhoneType = null // Clear phone type on disconnect
        currentWifi = null
        videoPhoneTypeInferred = false
        codecDeferred = true // Reset for next connection
        requestKeyframeOnCodecStart = false
        videoEncoderType = 2 // Reset to H265/initial
        videoOffScreen = 0
        hasVideoFocus = true
        callback?.onPhoneTypeChanged(PhoneType.UNKNOWN)
        clearCachedMediaMetadata() // Clear stale metadata to prevent race conditions on reconnect
        activeVoiceMode = VoiceMode.NONE // Reset voice mode on disconnect
        isSiriAudioActive = false
        isPhoneCallAudioActive = false
        isAlertAudioActive = false // Reset purpose on disconnect
        stopMicrophoneCapture()

        // Stop GPS forwarding before stopping adapter (only if forwarder was created)
        if (gnssForwarder != null) {
            adapterDriver?.sendCommand(CommandMapping.STOP_GNSS_REPORT)
            gnssForwarder?.stop()
        }

        // Graceful teardown: notify adapter before killing connection.
        // Must happen before adapterDriver?.stop() (send() checks isRunning).
        adapterDriver?.sendGracefulTeardown(reboot = reboot)

        adapterDriver?.stop()
        adapterDriver = null

        usbDevice?.close()
        usbDevice = null

        // Stop audio
        if (audioInitialized) {
            audioManager?.release()
            audioInitialized = false
            logInfo("Audio released on stop", tag = Logger.Tags.AUDIO)
        }

        dumpUnknownSummary()
        setState(State.DISCONNECTED)
    }

    /**
     * Disconnect the phone while retaining the requested adapter/service lifetime.
     * Reinitialize with autoConn disabled so firmware and host recovery both stay idle.
     */
    fun disconnectPhone(): Job {
        projectionHealth.event(com.cabin.platform.ProjectionEventKind.USER_DISCONNECT)
        phoneAutoConnectEnabled.set(false)
        phoneConnectionPreferences.edit { putBoolean("auto_connect", false) }
        adapterDriver?.cancelAutoConnect()
        val request = Any().also(phoneConnectionRequest::set)
        pendingConnectTarget = null
        lastConnectTargetMac = null
        targetConnectTimeoutJob?.cancel()
        targetConnectTimeoutJob = null
        logInfo("[DEVICE_MGMT] Phone disconnected by user; automatic phone connection paused", tag = Logger.Tags.ADAPTR)
        return scope.launch(Dispatchers.IO) {
            lifecycleMutex.lock()
            try {
                if (released.get() || phoneConnectionRequest.get() !== request) return@launch
                stopLocked()
                setStatusText(context.localizedString(R.string.connection_status_phone_paused))
            } finally {
                lifecycleMutex.unlock()
            }
            // This path deliberately does not share requestRestart's coalescing: a
            // Disconnect arriving during an existing init must apply its newer intent.
            if (shouldBeRunning.get() && phoneConnectionRequest.get() === request) startIfDesired()
        }
    }

    // ==================== Device Management ====================

    private fun phoneConnectTarget(): String? {
        if (!phoneAutoConnectEnabled.get()) return null
        return pendingConnectTarget ?: if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) {
            com.cabin.platform.TeyesFeaturePreferences.get(context).profile.value.preferredPhone.ifEmpty { null }
        } else null
    }

    /**
     * Request the adapter to send a fresh list of paired devices.
     * The adapter responds with BoxSettings (0x19) containing an updated DevList.
     * The UI is notified via [Callback.onDeviceListChanged].
     */
    fun refreshDeviceList() {
        logInfo("[DEVICE_MGMT] Requesting fresh device list", tag = Logger.Tags.ADAPTR)
        scope.launch(Dispatchers.IO) {
            adapterDriver?.sendGetBtOnlineList()
        }
    }

    /**
     * Connect to a specific paired device by BT MAC address.
     *
     * If currently streaming, disconnects the active phone first and waits for
     * the UNPLUGGED → restart cycle before sending the targeted connect.
     * If idle, sends the connect request immediately.
     *
     * @param btMac Target device BT MAC (format: "XX:XX:XX:XX:XX:XX")
     */
    fun connectToDevice(btMac: String) {
        requestPhoneConnection(btMac)
    }

    /** Explicit user Connect; automatic start/wake paths must retain the paused phone intent. */
    fun connectPhone(): Job = requestPhoneConnection(null)

    private fun requestPhoneConnection(btMac: String?): Job {
        projectionHealth.event(com.cabin.platform.ProjectionEventKind.USER_CONNECT)
        logInfo("[DEVICE_MGMT] Connect to device: $btMac (current state=$state, wifi=$currentWifi)", tag = Logger.Tags.ADAPTR)
        adapterDriver?.cancelAutoConnect()
        val wasPaused = !phoneAutoConnectEnabled.getAndSet(true)
        phoneConnectionPreferences.edit { putBoolean("auto_connect", true) }
        val request = Any().also(phoneConnectionRequest::set)
        shouldBeRunning.set(true)

        // Set the pending target BEFORE disconnecting so the UNPLUGGED → restart cycle
        // sends AutoConnect_By_BtAddress instead of WIFI_CONNECT (1002).
        pendingConnectTarget = btMac
        lastConnectTargetMac = btMac
        targetConnectTimeoutJob?.cancel()
        targetConnectTimeoutJob = if (btMac != null) {
            scope.launch {
                delay(TARGET_CONNECT_TIMEOUT_MS)
                if (phoneConnectionRequest.get() === request && pendingConnectTarget == btMac) {
                    pendingConnectTarget = null
                    logWarn("[DEVICE_MGMT] Timed out switching to $btMac — rebuilding session", tag = Logger.Tags.ADAPTR)
                    setStatusText(context.localizedString(R.string.connection_status_switch_timeout))
                    requestRestart("targeted phone connect timeout")
                }
            }
        } else null

        return scope.launch(Dispatchers.IO) {
            var needsStart = false
            lifecycleMutex.lock()
            try {
                if (released.get() || !shouldBeRunning.get() || phoneConnectionRequest.get() !== request) return@launch
                if (wasPaused || (btMac == null && state != State.STREAMING && state != State.DEVICE_CONNECTED)) {
                    // The idle adapter was configured with autoConn=false. Restore
                    // firmware configuration before the explicit targeted connection.
                    stopLocked()
                    needsStart = true
                } else if (state == State.STREAMING || state == State.DEVICE_CONNECTED) {
                    if (btMac == null) return@launch // An explicit generic Connect is already satisfied.
                    logInfo("[DEVICE_MGMT] Disconnecting current phone before targeted connect to $btMac", tag = Logger.Tags.ADAPTR)
                    adapterDriver?.disconnectPhone()
                    // UNPLUGGED handler will call restart() which checks pendingConnectTarget
                } else if (state == State.DISCONNECTED) {
                    needsStart = true
                } else {
                    if (btMac == null) return@launch
                    // Not currently connected — send targeted connect directly
                    val sent = adapterDriver?.overrideAutoConnectWithTarget(btMac) ?: false
                    logInfo("[DEVICE_MGMT] AutoConnect_By_BtAddress($btMac) sent=$sent (direct, no active session)", tag = Logger.Tags.ADAPTR)
                    if (sent && phoneConnectionRequest.get() === request) {
                        pendingConnectTarget = null
                        targetConnectTimeoutJob?.cancel()
                        targetConnectTimeoutJob = null
                    }
                }
            } finally {
                lifecycleMutex.unlock()
            }
            if (needsStart && phoneConnectionRequest.get() === request) startIfDesired()
        }
    }

    /**
     * Remove a device from the adapter's paired list (DevList → DeletedDevList).
     * The adapter will no longer auto-connect to this device.
     * Refreshes the device list after removal.
     *
     * @param btMac Target device BT MAC (format: "XX:XX:XX:XX:XX:XX")
     */
    fun forgetDevice(btMac: String) {
        logInfo("[DEVICE_MGMT] Forget device: $btMac (list size=${_deviceList.size})", tag = Logger.Tags.ADAPTR)
        if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) {
            com.cabin.platform.TeyesFeaturePreferences.get(context).forgetPhone(btMac)
        }

        // Optimistically remove from local list immediately for responsive UI.
        // The adapter may take 10-20s to process and confirm via GET_BT_ONLINE_LIST.
        val devices = synchronized(deviceListLock) {
            _deviceList = _deviceList.filter { it.btMac != btMac }
            _deviceList
        }
        callback?.onDeviceListChanged(devices)
        notifyDeviceListeners(devices)

        scope.launch(Dispatchers.IO) {
            val sent = adapterDriver?.sendForgetBluetoothAddr(btMac) ?: false
            logInfo("[DEVICE_MGMT] ForgetBluetoothAddr($btMac) sent=$sent", tag = Logger.Tags.ADAPTR)
            // Refresh list from adapter to confirm removal (adapter response may be slow)
            delay(1000)
            adapterDriver?.sendGetBtOnlineList()
        }
    }

    /**
     * Restart the connection.
     * Uses Dispatchers.IO for stop()/start() — both do blocking USB I/O
     * (bulkTransfer, thread joins) that must never run on the main thread.
     */
    suspend fun restart() {
        projectionHealth.event(com.cabin.platform.ProjectionEventKind.USER_RESTART)
        shouldBeRunning.set(true)
        restartIfDesired()
    }

    /** Internal restart path used by recovery; it cannot change the user's desired state. */
    private suspend fun restartIfDesired() {
        withContext(Dispatchers.IO) {
            lifecycleMutex.lock()
            try {
                if (released.get() || !shouldBeRunning.get()) return@withContext
                setStatusText(context.localizedString(R.string.connection_status_restarting))
                stopLocked()
            } finally {
                lifecycleMutex.unlock()
            }
        }
        // Never hold the lifecycle lock across a backoff. STOP/release must be able to
        // preempt this delay, and a separate start request may safely win the race.
        delay(2000)
        if (!released.get() && shouldBeRunning.get()) startIfDesired()
    }

    private fun requestRestart(reason: String) {
        if (!shouldBeRunning.get() || released.get()) {
            logDebug("[RESTART] Ignoring recovery after stop/release: $reason", tag = Logger.Tags.ADAPTR)
            return
        }
        if (!restartPending.compareAndSet(false, true)) {
            logDebug("[RESTART] Coalesced duplicate request: $reason", tag = Logger.Tags.ADAPTR)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                restartIfDesired()
            } finally {
                restartPending.set(false)
            }
        }
    }

    /** End a rejected negotiation cleanly without entering an automatic rejection loop. */
    private fun requestNegotiationReset() {
        if (!negotiationResetPending.compareAndSet(false, true)) return
        shouldBeRunning.set(false)
        scope.launch(Dispatchers.IO) {
            lifecycleMutex.lock()
            try {
                if (!released.get()) {
                    stopLocked()
                    setStatusText(context.localizedString(R.string.connection_status_config_rejected))
                }
            } finally {
                lifecycleMutex.unlock()
                negotiationResetPending.set(false)
            }
        }
    }

    /**
     * Send a key command.
     */
    private fun sendKey(command: CommandMapping): Boolean = adapterDriver?.sendCommand(command) ?: false

    val supportsProjectionGain: Boolean get() = !config.audioTransferMode

    fun applyTeyesAudioProfile() {
        if (!BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) return
        val profile = com.cabin.platform.TeyesFeaturePreferences.get(context).profile.value
        audioManager?.setUserGains(profile.mediaGain, profile.navigationGain)
    }

    /** Phone projection commands only; these do not expose TEYES vehicle controls. */
    enum class ProjectionAction { VOICE, PLAY_PAUSE, PREVIOUS, NEXT }

    private val projectionActionPending = AtomicBoolean(false)

    /**
     * Admit one off-main projection action. True means queued, not acknowledged by the phone.
     * The lifecycle lock keeps an admitted click on its original USB session. Duplicate taps
     * are ignored until dispatch completes rather than building an unbounded command queue.
     */
    fun performProjectionAction(action: ProjectionAction): Boolean {
        val target = adapterDriver ?: return false
        val phoneRequest = phoneConnectionRequest.get()
        if (released.get() || !shouldBeRunning.get() || !phoneAutoConnectEnabled.get() || !scope.isActive || state != State.STREAMING) return false
        if (!projectionActionPending.compareAndSet(false, true)) return false
        scope.launch(Dispatchers.IO) {
            lifecycleMutex.lock()
            try {
                if (released.get() || !shouldBeRunning.get() || !phoneAutoConnectEnabled.get() ||
                    state != State.STREAMING || adapterDriver !== target || phoneConnectionRequest.get() !== phoneRequest
                ) return@launch
                when (action) {
                    ProjectionAction.PLAY_PAUSE -> target.sendCommand(CommandMapping.PLAY_PAUSE)
                    ProjectionAction.NEXT -> target.sendCommand(CommandMapping.NEXT)
                    ProjectionAction.PREVIOUS -> target.sendCommand(CommandMapping.PREV)
                    ProjectionAction.VOICE -> {
                        // No suspension between press/release, including cancellation or a
                        // failed press. Always attempt release on the SAME adapter instance.
                        try {
                            target.sendCommand(CommandMapping.SIRI)
                        } finally {
                            target.sendCommand(CommandMapping.SIRI_BUTTON_UP)
                        }
                    }
                }
            } finally {
                lifecycleMutex.unlock()
            }
        }.invokeOnCompletion { projectionActionPending.set(false) }
        return true
    }

    /** TEYES key mapping remains build-gated; its phone actions share the projection allowlist. */
    fun performTeyesKey(action: com.cabin.platform.TeyesKeyAction) {
        if (!BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) return
        val projectionAction = when (action) {
            com.cabin.platform.TeyesKeyAction.PLAY_PAUSE -> ProjectionAction.PLAY_PAUSE
            com.cabin.platform.TeyesKeyAction.NEXT -> ProjectionAction.NEXT
            com.cabin.platform.TeyesKeyAction.PREVIOUS -> ProjectionAction.PREVIOUS
            com.cabin.platform.TeyesKeyAction.VOICE -> ProjectionAction.VOICE
            com.cabin.platform.TeyesKeyAction.LAUNCHER, com.cabin.platform.TeyesKeyAction.CLIMATE, com.cabin.platform.TeyesKeyAction.VOLUME_UP,
            com.cabin.platform.TeyesKeyAction.VOLUME_DOWN, com.cabin.platform.TeyesKeyAction.MUTE,
            com.cabin.platform.TeyesKeyAction.BACK, com.cabin.platform.TeyesKeyAction.NONE,
            com.cabin.platform.TeyesKeyAction.PAGE_NEXT, com.cabin.platform.TeyesKeyAction.PAGE_PREVIOUS -> return // Activity-owned actions
        }
        performProjectionAction(projectionAction)
    }

    @Volatile private var teyesSystemDarkOverride: Boolean? = null

    fun syncTeyesAppearance(systemDarkOverride: Boolean? = null) {
        if (systemDarkOverride != null) teyesSystemDarkOverride = systemDarkOverride
        if (!BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) return
        scope.launch(Dispatchers.IO) {
            if (state != State.STREAMING && state != State.DEVICE_CONNECTED) return@launch
            val appearance = com.cabin.platform.TeyesFeaturePreferences.get(context).profile.value.appearance
            val dark = when (appearance) {
                com.cabin.platform.TeyesAppearance.DAY -> false
                com.cabin.platform.TeyesAppearance.NIGHT -> true
                com.cabin.platform.TeyesAppearance.SYSTEM -> teyesSystemDarkOverride ?: (
                    context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES)
            }
            sendKey(if (dark) CommandMapping.ENABLE_NIGHT_MODE else CommandMapping.DISABLE_NIGHT_MODE)
        }
    }

    // AA MOVE rate-limit timestamp. cabin_native skips a MOVE send when <17ms
    // elapsed since the previous one. See [AA_TOUCH_THROTTLE_NS] for the full
    // explanation — and the important distinction from AutoKit's use of the same
    // 17ms number (AutoKit's 17ms is a stationary-finger MOVE repeater, NOT a
    // rate limit).
    @Volatile private var lastAaTouchSendTimeNs = 0L

    /**
     * Send a multi-touch event (CarPlay) or single-touch event (Android Auto).
     * AA uses type 0x05 with 0..10000 ints; CarPlay uses type 0x17 with 0..1 floats.
     * Dispatched to IO — USB bulkTransfer must never block the UI thread.
     *
     * On the AA path, MOVE events are rate-limited to one per [AA_TOUCH_THROTTLE_NS]
     * window. DOWN and UP are always sent immediately. This is a cabin_native-side
     * rate limit, NOT an imitation of AutoKit; see the constant's KDoc for details.
     */
    fun sendMultiTouch(touches: List<MessageSerializer.TouchPoint>) {
        val driver = adapterDriver ?: return
        val isAA = currentPhoneType == PhoneType.ANDROID_AUTO
        val encoderType = videoEncoderType
        val offScreen = videoOffScreen
        touchSender.submit(driver, touches) { orderedTouches ->
            if (released.get() || adapterDriver !== driver) return@submit
            if (isAA && orderedTouches.isNotEmpty()) {
                // AA: send primary pointer as single-touch (type 0x05, 0..10000 ints)
                val primary = orderedTouches.first()

                // Rate-limit MOVE events to one per [AA_TOUCH_THROTTLE_NS] window.
                // Not an AutoKit-compatibility behavior — AutoKit sends every MOVE
                // immediately. See the constant's KDoc for the full comparison.
                if (primary.action == MultiTouchAction.MOVE) {
                    val now = System.nanoTime()
                    if (now - lastAaTouchSendTimeNs < AA_TOUCH_THROTTLE_NS) return@submit
                    lastAaTouchSendTimeNs = now
                }

                val xi = (primary.x * 10000).toInt()
                val yi = (primary.y * 10000).toInt()
                if (BuildConfig.DEBUG) {
                    logDebug(
                        "[AA_TOUCH] type=0x05 action=${primary.action} x=$xi y=$yi" +
                            " (norm=${primary.x},${primary.y})",
                        tag = Logger.Tags.TOUCH,
                    )
                }
                driver.sendSingleTouch(
                    x = xi,
                    y = yi,
                    action = primary.action,
                    encoderType = encoderType,
                    offScreen = offScreen,
                )
            } else {
                driver.sendMultiTouch(orderedTouches)
            }
        }
    }

    fun rebootAdapter() {
        shouldBeRunning.set(true)
        teardownScope.launch {
            lifecycleMutex.lock()
            try {
                if (released.get()) return@launch
                logWarn("[LIFECYCLE] Reboot adapter requested", tag = Logger.Tags.ADAPTR)
                stopLocked(reboot = true)
                setStatusText(context.localizedString(R.string.connection_status_rebooting))
            } finally {
                lifecycleMutex.unlock()
            }
            delay(2000)
            if (!released.get() && shouldBeRunning.get()) startIfDesired()
        }
    }

    /**
     * Release all resources.
     *
     * IRREVERSIBLE: cancels the coroutine scope via `scope.cancel()`. No coroutine launched
     * on [scope] can be revived afterwards — a new [CabinManager] instance must be created
     * for subsequent use. Must be the LAST call on this instance.
     *
     * Releases owned subsystems in LIFO order relative to initialization: stop() first (which
     * winds down the session and adapter driver), then H264Renderer, DualStreamAudioManager,
     * MicrophoneCaptureManager, GnssForwarder, MediaSessionManager, finally scope.cancel().
     */
    fun release() {
        markProjectionStopped()
        teardownScope.launch { releaseAndWait() }
    }

    /** Awaitable final release; safe to call from Activity/service lifecycle coroutines. */
    suspend fun releaseAndWait() {
        if (!released.compareAndSet(false, true)) return
        markProjectionStopped()
        scope.cancel()
        withContext(NonCancellable + Dispatchers.IO) {
            lifecycleMutex.lock()
            try {
                stopLocked()

                h264Renderer?.stop()
                h264Renderer = null

                audioManager?.release()
                audioManager = null

                microphoneManager?.stop()
                microphoneManager = null

                gnssForwarder?.stop()
                gnssForwarder = null

                // MediaSession is app-scope owned by MainActivity — do NOT release here.
                // Detaching the transport callback is sufficient: this manager must stop
                // receiving controls, while the stable session token remains service-owned.
                mediaSessionManager?.setMediaControlCallback(null)
                mediaSessionManager = null
            } finally {
                lifecycleMutex.unlock()
            }
        }

        // Cancel coroutine scope to stop any in-flight coroutines that hold
        // references to this manager and its context
        teardownScope.cancel()

        logInfo("CabinManager released", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Handle USB device detachment event.
     * Called by MainActivity when USB_DEVICE_DETACHED broadcast is received.
     *
     * This provides immediate detection of physical adapter removal,
     * rather than waiting for USB transfer errors.
     */
    fun onUsbDeviceDetached() {
        logWarn("[USB] Device detached broadcast received", tag = Logger.Tags.USB)

        // Only handle if we have an active connection
        if (state == State.DISCONNECTED) {
            logInfo("[USB] Already disconnected, ignoring detach", tag = Logger.Tags.USB)
            return
        }

        projectionHealth.event(com.cabin.platform.ProjectionEventKind.USB_DETACHED)

        // Trigger recovery through the error handler path
        // This ensures consistent recovery behavior
        handleError("USB device physically disconnected")
    }

    /** Match broadcasts to the exact adapter path currently owned by this manager. */
    fun ownsUsbDevice(device: UsbDevice): Boolean = usbDevice?.deviceName == device.deviceName

    /**
     * Resets the H.264 video decoder/renderer.
     *
     * This operation resets the MediaCodec decoder without disconnecting the USB device.
     * Useful for recovering from video decoding errors or codec issues.
     *
     */
    enum class VideoResetResult { REQUESTED, QUEUED, UNAVAILABLE }

    /** Serialize Stop admission against manual recovery before asynchronous USB teardown. */
    @Synchronized
    private fun markProjectionStopped() {
        shouldBeRunning.set(false)
        pendingUserVideoReset.set(false)
    }

    @Synchronized
    fun resetVideoDecoder(): VideoResetResult {
        if (released.get() || !shouldBeRunning.get() || h264Renderer == null || (state != State.STREAMING && state != State.DEVICE_CONNECTED)) {
            return VideoResetResult.UNAVAILABLE
        }
        projectionHealth.event(com.cabin.platform.ProjectionEventKind.PICTURE_RECOVERY)
        if (videoOverlayCovered || videoPaused || videoSurface?.isValid != true || codecDeferred) {
            pendingUserVideoReset.set(true)
            return VideoResetResult.QUEUED
        }
        logInfo("[DEVICE_OPS] Resetting H264 video decoder", tag = Logger.Tags.VIDEO)
        h264Renderer?.reset()
        logInfo("[DEVICE_OPS] H264 video decoder reset completed", tag = Logger.Tags.VIDEO)
        // reset() already sends a keyframe request via keyframeCallback — no additional FRAME needed
        return VideoResetResult.REQUESTED
    }

    /** Consume manual recovery only on a valid visible resume, never below an overlay. */
    @Synchronized
    private fun resumeVideoRenderer(surface: Surface) {
        if (videoOverlayCovered || released.get() || !shouldBeRunning.get() || !surface.isValid) return
        val renderer = h264Renderer ?: return
        if (pendingUserVideoReset.getAndSet(false)) renderer.stop()
        // One stop/resume produces a single restart/keyframe, rather than resume + reset.
        renderer.resume(surface)
    }

    internal val hasPendingUserVideoReset: Boolean get() = pendingUserVideoReset.get()

    /**
     * Handle Surface destruction - pause codec IMMEDIATELY.
     *
     * CRITICAL: This is called when SurfaceView's Surface is destroyed, which happens
     * BEFORE onStop() is called. If we wait for onStop(), the codec will try to render
     * to a dead surface causing "BufferQueue has been abandoned" errors.
     *
     * Call this from VideoSurface's onSurfaceDestroyed callback.
     */
    fun onSurfaceDestroyed(expectedSurface: Surface? = null) {
        // A disposed view can report teardown after a new view queued its surface.
        // Preserve that replacement and its pending codec update.
        if (expectedSurface != null && (pendingSurface ?: videoSurface) !== expectedSurface) return
        logInfo("[LIFECYCLE] Surface destroyed - pausing codec immediately", tag = Logger.Tags.VIDEO)

        // Cancel any pending surface updates
        surfaceUpdateJob?.cancel()
        surfaceUpdateJob = null
        pendingSurface = null

        // Clear surface reference - it's now invalid
        videoSurface = null
        videoPaused = true
        surfaceReadyForDeferredCodec = false

        // Stop codec immediately - surface is dead
        h264Renderer?.stop()
    }

    /**
     * Start the codec if it was deferred. Called when:
     * - PLUGGED(CarPlay): start immediately with current surface
     * - AA surface resize complete: new surface available at tier AR
     * - Video arrives before PLUGGED: fallback start with current surface
     */
    @Synchronized
    fun startCodecIfDeferred() {
        if (videoOverlayCovered || released.get() || !shouldBeRunning.get()) return
        if (!codecDeferred) return
        if (currentPhoneType == PhoneType.ANDROID_AUTO && !surfaceReadyForDeferredCodec) return
        if (h264Renderer == null) return
        // Use CabinManager.videoSurface (updated by debounce) — NOT renderer's
        // internal surface, which may be null if surfaceDestroyed/Created happened
        // during Compose layout after initialize() but before PLUGGED arrived.
        val surface = videoSurface
        if (surface == null || !surface.isValid) {
            logWarn(
                "[LIFECYCLE] Deferred codec start skipped — no valid surface",
                tag = Logger.Tags.VIDEO,
            )
            return // Leave codecDeferred=true; debounce or resumeVideo() will retry
        }
        codecDeferred = false
        logInfo("[LIFECYCLE] Starting deferred codec", tag = Logger.Tags.VIDEO)
        resumeVideoRenderer(surface)
        // resume() requests one keyframe after its full codec restart. Do not send a
        // second FRAME (especially on AA, where it can reset/flicker the phone UI).
        requestKeyframeOnCodecStart = false
    }

    /**
     * Compute AA center-crop parameters from the current adapter config.
     * Returns null for CarPlay, 16:9 displays, or when config has no native dims.
     * Uses config.width/height (video surface dims) to match serializeBoxSettings AR calc.
     */
    fun getAaCropParams(): AaCropParams? {
        // Use actual surface dims (ground truth from Compose layout), not config dims
        // (which come from WindowMetrics and may differ due to AAOS dock inset behavior).
        val surfW = if (actualSurfaceWidth > 0) actualSurfaceWidth else config.width
        val surfH = if (actualSurfaceHeight > 0) actualSurfaceHeight else config.height
        if (surfW <= 0 || surfH <= 0) return null

        val (tierWidth, tierHeight) =
            if (surfH > surfW) {
                when {
                    surfW >= 1080 -> Pair(1080, 1920)
                    surfW >= 720 -> Pair(720, 1280)
                    else -> Pair(480, 800)
                }
            } else {
                when {
                    surfW >= 1920 -> Pair(1920, 1080)
                    surfW >= 1280 -> Pair(1280, 720)
                    else -> Pair(800, 480)
                }
            }
        val displayAR = surfW.toFloat() / surfH.toFloat()
        val tierAR = tierWidth.toFloat() / tierHeight.toFloat()
        // Two-way fit: baked bars sit on whichever tier axis the display does NOT bind.
        // displayAR >= tierAR → bars top/bottom (crop vertically); else → bars left/right.
        return if (displayAR >= tierAR) {
            val contentHeight = ((tierWidth.toFloat() / displayAR).toInt() and 0xFFFE).coerceAtMost(tierHeight)
            if (contentHeight >= tierHeight) return null // exact AR match — no crop
            AaCropParams(tierWidth, tierHeight, tierWidth, contentHeight, 0, (tierHeight - contentHeight) / 2)
        } else {
            val contentWidth = ((tierHeight.toFloat() * displayAR).toInt() and 0xFFFE).coerceAtMost(tierWidth)
            if (contentWidth >= tierWidth) return null // exact AR match — no crop
            AaCropParams(tierWidth, tierHeight, contentWidth, tierHeight, (tierWidth - contentWidth) / 2, 0)
        }
    }

    /**
     * Resend BoxSettings to the adapter with current surface dimensions.
     * Called when display mode changes during an AA session so the phone
     * re-renders content for the new container size.
     */
    fun resendBoxSettings() {
        val driver = adapterDriver ?: return
        val w = actualSurfaceWidth
        val h = actualSurfaceHeight
        if (w <= 0 || h <= 0) return
        logInfo(
            "[LIFECYCLE] Resending BoxSettings for new surface ${w}x$h",
            tag = Logger.Tags.VIDEO,
        )
        val msg = MessageSerializer.serializeBoxSettings(config, surfaceWidth = w, surfaceHeight = h)
        scope.launch(Dispatchers.IO) { driver.send(msg) }
    }

    /**
     * Pause video decoding when app goes to background.
     *
     * On AAOS, when the app is covered by another app (e.g., Maps, Phone), the Surface
     * may remain valid but SurfaceFlinger stops consuming frames. This causes the
     * BufferQueue to fill up, stalling the decoder. When the user returns, video
     * appears blank while audio continues normally.
     *
     * This method flushes the codec to prevent BufferQueue stalls. The USB connection
     * and audio playback continue unaffected.
     *
     * NOTE: Surface destruction is handled separately by onSurfaceDestroyed() which
     * is called when the Surface is actually destroyed (may be before or after onStop).
     *
     * Call this from Activity.onStop().
     */
    @Synchronized
    fun pauseVideo() {
        logInfo("[LIFECYCLE] Pausing video for background", tag = Logger.Tags.VIDEO)
        videoPaused = true
        cancelDelayedKeyframe()
        h264Renderer?.stop()
    }

    /**
     * Resume video decoding when app returns to foreground.
     *
     * After pauseVideo(), the codec is in a flushed state. This method restarts the
     * codec and requests a keyframe so video can resume immediately.
     *
     * NOTE: The main surface update happens in initialize() when the new Surface is created.
     * If onStart() is called before the Surface is ready, we skip resume here and let
     * initialize() handle it when the Surface becomes available.
     *
     * Call this from Activity.onStart().
     */
    @Synchronized
    fun resumeVideo() {
        if (videoOverlayCovered || released.get() || !shouldBeRunning.get()) return
        logInfo("[LIFECYCLE] Resuming video for foreground", tag = Logger.Tags.VIDEO)

        // If surface is null (destroyed and not yet recreated), skip resume.
        // initialize() will handle resume when new Surface becomes available.
        val surface = videoSurface
        if (surface == null || !surface.isValid) {
            logInfo(
                "[LIFECYCLE] Surface not ready yet - resume will happen via initialize()",
                tag = Logger.Tags.VIDEO,
            )
            return
        }

        videoPaused = false

        // Do not bypass initial phone-type/AA surface readiness with an overlay close
        // or an Activity focus callback. Existing PLUGGED/resize paths finish startup.
        if (codecDeferred) {
            if (currentPhoneType != null) startCodecIfDeferred()
            if (codecDeferred) return
        } else {
            resumeVideoRenderer(surface)
        }

        // Request keyframe so video recovers immediately after background
        if (state == State.STREAMING || state == State.DEVICE_CONNECTED) {
            if (currentPhoneType != PhoneType.ANDROID_AUTO) {
                // CarPlay: restart the periodic keyframe timer (2.5s initial + 30s periodic)
                scheduleDelayedKeyframe()
            }
        }
    }

    /**
     * Recover video after settings overlay closes.
     * Schedules codec recovery off the UI thread, then requests one fresh keyframe.
     */
    fun recoverVideoFromOverlay() {
        if (videoOverlayCovered) return
        if (state != State.STREAMING) return
        logInfo("[LIFECYCLE] Recovering video after overlay close (phoneType=$currentPhoneType)", tag = Logger.Tags.VIDEO)
        h264Renderer?.flushCodec()
        // H264Renderer.reset() requests exactly one fresh IDR after its staging queue and
        // sync gate have been reset, so no second FRAME command is needed here.
    }

    /** Occlusion is independent of Activity focus: a mounted Surface may still be covered. */
    @Synchronized
    fun setVideoOverlayCovered(covered: Boolean, resumeWhenUncovered: Boolean = true) {
        if (videoOverlayCovered == covered || released.get()) return
        videoOverlayCovered = covered
        if (covered) pauseVideo()
        else if (resumeWhenUncovered) resumeVideo()
    }

    internal val isVideoOverlayCovered: Boolean get() = videoOverlayCovered

    // ==================== Private Methods ====================

    @Synchronized
    private fun setState(newState: State) {
        // Serialize the state swap and its callbacks as one transition. USB errors,
        // lifecycle calls and reconnect jobs can otherwise publish states out of order.
        val oldState = currentState.getAndSet(newState)
        if (oldState != newState) {
            projectionHealth.connection(newState)
            if (newState == State.STREAMING) syncTeyesAppearance()
            callback?.onStateChanged(newState)
            updateMediaSessionState(newState)
            CabinWidgetState.updateProjection(
                context = context,
                connectionState = newState,
                phoneType = currentPhoneType,
                wifi = currentWifi,
            )
        }
    }

    private fun setStatusText(text: String) {
        projectionHealth.status(text)
        currentStatusText = text
        callback?.onStatusTextChanged(text)
        CabinWidgetState.updateStatus(context, text)
        // Mirror status text to MediaSession placeholder so cluster, cardview, and
        // CarMediaApp surfaces all show the same user-facing state the main app UI
        // shows as the adapter transitions through DISCONNECTED → CONNECTING →
        // DEVICE_CONNECTED phases (Searching for adapter, Initializing, Waiting
        // for phone, Phone connected, Reconnecting, etc.). Single source of truth.
        //
        // Gate on state != STREAMING — once real CarPlay/AA track metadata is
        // flowing via processMediaMetadata → MediaSessionManager.publishMetadata,
        // status text would clobber the live track title/artist. The setStatusText
        // call sites during STREAMING (e.g. "Adapter not responding — reconnecting")
        // are state-recovery transitions where placeholder text is no longer
        // appropriate; the next state transition (back to CONNECTING) will rearm
        // the mirror.
        if (state != State.STREAMING) {
            mediaSessionManager?.updatePlaceholderArtist(text)
        }
    }

    /**
     * Wire the app's projection state machine to the MediaSession active-flag.
     *
     * The session is INACTIVE by default (DISCONNECTED / CONNECTING) and ACTIVE only
     * once the adapter reports PLUGGED (DEVICE_CONNECTED). This keeps Cabin out of
     * AAOS's playback-primary slot when there's no phone actually projecting, letting
     * other media apps promote freely.
     *
     * KNOWN OS DEFICIENCY — stale homescreen Media card after force-stop
     * AAOS platform bug, not app-specific. Verified 2026-04-21 on AAOS emulator +
     * GM AAOS, and reproduced identically on Apple Music 5.2.1 — a first-party
     * vendor app using stock MediaBrowserService/MediaSession. Any media app in
     * the AAOS ecosystem hits this; by extension the same failure mode is expected
     * on the GM WidgetPanel media widget and GM Cluster media panel (they consume
     * MediaSession through the same plumbing).
     *
     * Symptom: force-stopping the app and relaunching leaves CarLauncher's homescreen
     * Media card blank (only the app name shows, no metadata/artwork) even though the
     * new session reports ACTIVE + PLAYING and CarMediaService has the app set as
     * playback-primary. Root cause lives inside CarLauncher's PlaybackViewModel: it
     * caches the pre-force-stop MediaController reference and never rebinds to the
     * new session token on the inactive→active transition. Neither this refactor
     * nor an emulator restart fixes it; only a full package uninstall + reinstall
     * + emulator restart clears the cached state. See MediaSessionManager class
     * KDoc "KNOWN OS DEFICIENCY" for the full analysis. This transition wiring is
     * kept because it is semantically correct, not because it resolves any visible bug.
     */
    private fun updateMediaSessionState(state: State) {
        when (state) {
            State.CONNECTING -> {
                // Adapter is attached (USB handshake in progress). Activate the
                // MediaSession NOW with STATE_BUFFERING + playWhenReady=true so AOSP
                // CarMediaService.MediaControllerCallback.onPlaybackStateChanged sees
                // a "preparing to play" arbitration signal at USB-attach time, matching
                // v113's setStateConnecting behavior. Without this, the session stays
                // STATE_IDLE through CONNECTING and only goes BUFFERING on PLUGGED —
                // by which time an already-PLAYING source (e.g., FM) is undisplaceable
                // per CarMediaService rule "newly-PLAYING displaces non-PLAYING only".
                // See verification thread + Agent 2 AOSP source citation, 2026-05-02.
                //
                // connectingPhase=true publishes "Connecting to phone..." so cluster +
                // cardview + CarMediaApp surfaces show meaningful state during handshake.
                mediaSessionManager?.setProjectionActive(connectingPhase = true)
                // Start foreground service early to maintain process priority during handshake
                CabinMediaBrowserService.startConnectionForeground(context)
                // Acquire wake lock to ensure USB operations aren't interrupted
                acquireWakeLock()
            }

            State.DISCONNECTED -> {
                // No adapter / no phone — session fully inactive. AAOS consumers are
                // free to promote other media apps to playback primary.
                mediaSessionManager?.setInactive()
                // Clear now-playing and stop foreground service
                CabinMediaBrowserService.clearNowPlaying()
                CabinMediaBrowserService.stopConnectionForeground(context)
                // Release wake lock - CPU can sleep now
                releaseWakeLock()
            }

            State.DEVICE_CONNECTED -> {
                // PLUGGED event received — phone is attached to adapter and iAP2/AA
                // handshake has identified it as CarPlay or Android Auto. Session was
                // already activated at CONNECTING; re-publish with phase-specific text
                // ("Waiting for media...") since the user can now see we're past the
                // handshake and just waiting for the first MEDIA_DATA frame.
                mediaSessionManager?.setProjectionActive(connectingPhase = false)
                // Keep FGS up (idempotent if already started at CONNECTING).
                CabinMediaBrowserService.startConnectionForeground(context)
            }

            State.STREAMING -> {
                // Defensive: ensure FGS is running (idempotent if already started at CONNECTING)
                CabinMediaBrowserService.startConnectionForeground(context)
                // Ensure wake lock is held during streaming
                acquireWakeLock()
                // Start GPS forwarding to CarPlay (only if enabled in settings)
                if (config.gpsForwarding) {
                    gnssForwarder?.start()
                }
                // MediaSession is already ACTIVE from the DEVICE_CONNECTED transition.
                // MEDIA_DATA frames drive metadata/playback-state via
                // MediaSessionManager.updateMetadata/updatePlaybackState — no explicit
                // active-flip needed here.
            }
        }
    }

    /**
     * Acquires a partial wake lock to prevent CPU sleep during USB streaming.
     * It uses a bounded timeout for OS safety and is renewed before expiry while the
     * connection remains active. This supports long drives without an unbounded lock.
     */
    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            logInfo("[WAKE_LOCK] Acquired partial wake lock for USB streaming", tag = Logger.Tags.USB)
        }
        if (wakeLockRefreshJob?.isActive != true) {
            wakeLockRefreshJob =
                scope.launch {
                    while (state != State.DISCONNECTED) {
                        delay(WAKE_LOCK_REFRESH_MS)
                        if (state == State.DISCONNECTED) break
                        if (wakeLock.isHeld) wakeLock.release()
                        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                        logDebug("[WAKE_LOCK] Renewed for active USB session", tag = Logger.Tags.USB)
                    }
                }
        }
    }

    /**
     * Releases the wake lock, allowing CPU to sleep.
     */
    private fun releaseWakeLock() {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = null
        if (wakeLock.isHeld) {
            wakeLock.release()
            logInfo("[WAKE_LOCK] Released wake lock", tag = Logger.Tags.USB)
        }
    }

    private suspend fun findDevice(): UsbDeviceWrapper? {
        var attempts = 0

        while (attempts < 10 && shouldBeRunning.get() && !released.get()) {
            val candidates = UsbDeviceWrapper.findAll(context, usbManager) { log(it) }
            for (candidate in candidates) {
                if (!shouldBeRunning.get() || released.get()) return null
                setStatusText(context.localizedString(R.string.connection_status_found))
                var retained = false
                try {
                    if (candidate.openWithPermission()) {
                        log("Carlinkit device found!")
                        retained = true
                        return candidate
                    }
                } finally {
                    if (!retained) candidate.close()
                }
                logWarn("Unable to open one attached adapter; trying the next", tag = Logger.Tags.USB)
            }

            attempts++
            if (attempts < 10 && shouldBeRunning.get() && !released.get()) {
                delay(USB_WAIT_PERIOD_MS)
            }
        }
        return null
    }

    private fun handleMessage(message: Message) {
        // HAZARD: runs on the USB read thread (single-threaded per AdapterDriver contract).
        // Several branches below do synchronous, non-trivial work that technically violates
        // AdapterDriver's "return quickly" contract:
        //   - `tryPrestageCodecCsd` performs a SharedPreferences read + Base64 decode.
        //   - `notifyDeviceListeners` invokes listener callbacks synchronously on this thread.
        //   - `audioManager?.writeAudio` can potentially block on AudioTrack state.
        // This survives today because SharedPreferences is effectively cached by the OS after
        // first read and AudioTrack writes rarely block in the non-blocking modes we use. Any
        // new work added here should either be trivial or dispatched to a background scope.
        when (message) {
            is PluggedMessage -> {
                // Store wifi status for UI (0=USB, 1=wireless)
                currentWifi = message.wifi

                logInfo(
                    "[PLUGGED] Device plugged: phoneType=${message.phoneType}, wifi=${message.wifi}",
                    tag = Logger.Tags.VIDEO,
                )
                if (message.phoneType == PhoneType.UNKNOWN) {
                    unknownPhoneTypeCount++
                    logWarn(
                        "[PLUGGED] Unknown phoneType raw id=${message.rawPhoneType} " +
                            "(0x${message.rawPhoneType.toString(16)})",
                        tag = Logger.Tags.PROTO_UNKNOWN,
                    )
                }
                clearPairTimeout()
                cancelDelayedKeyframe() // Stop any existing timer (clean slate)

                // Reset reconnect attempts and escalation on successful connection
                reconnectAttempts = 0
                consecutiveNoResponse = 0
                shortLivedStreamingCount = 0
                hadPriorSession = true

                // Store phone type for keyframe request decisions during recovery
                currentPhoneType = message.phoneType
                logDebug("[PLUGGED] Stored currentPhoneType=$currentPhoneType", tag = Logger.Tags.VIDEO)

                // Infer connected BT MAC if not yet known (adapter may not send
                // PeerBluetoothAddress or BoxSettings #2 in every session).
                if (_connectedBtMac == null) {
                    // Priority 1: user explicitly selected this device via connectToDevice()
                    val targetMac = lastConnectTargetMac
                    if (targetMac != null) {
                        _connectedBtMac = targetMac
                        lastConnectTargetMac = null
                        logInfo("[PLUGGED] Set connectedBtMac=$targetMac from user-selected target", tag = Logger.Tags.ADAPTR)
                    } else if (_deviceList.isNotEmpty()) {
                        // Priority 2: match PLUGGED phoneType against DevList entries by type
                        val typeMatch = when (message.phoneType) {
                            PhoneType.CARPLAY, PhoneType.CARPLAY_WIRELESS -> "CarPlay"
                            PhoneType.ANDROID_AUTO -> "AndroidAuto"
                            PhoneType.HI_CAR -> "HiCar"
                            else -> null
                        }
                        if (typeMatch != null) {
                            val matched = _deviceList.filter { it.type == typeMatch }
                            if (matched.size == 1) {
                                _connectedBtMac = matched[0].btMac
                                logInfo("[PLUGGED] Inferred connectedBtMac=${_connectedBtMac} from DevList (type=$typeMatch)", tag = Logger.Tags.ADAPTR)
                            } else {
                                logDebug("[PLUGGED] Cannot infer MAC: ${matched.size} devices match type=$typeMatch", tag = Logger.Tags.ADAPTR)
                            }
                        }
                    }
                } else {
                    // Already known — clear the target since connection succeeded
                    lastConnectTargetMac = null
                }

                // Enrich device list: if connected device has unknown type (came from
                // BluetoothPairedList which doesn't carry type), update it from PLUGGED phoneType.
                val connMac = _connectedBtMac
                if (connMac != null) {
                    val typeStr = when (message.phoneType) {
                        PhoneType.CARPLAY, PhoneType.CARPLAY_WIRELESS -> "CarPlay"
                        PhoneType.ANDROID_AUTO -> "AndroidAuto"
                        PhoneType.HI_CAR -> "HiCar"
                        else -> null
                    }
                    if (typeStr != null) {
                        val device = _deviceList.find { it.btMac == connMac }
                        if (device != null && device.type.isEmpty()) {
                            val devices = synchronized(deviceListLock) {
                                _deviceList = _deviceList.map {
                                    if (it.btMac == connMac) it.copy(type = typeStr) else it
                                }
                                _deviceList
                            }
                            logInfo("[PLUGGED] Enriched device $connMac type → $typeStr", tag = Logger.Tags.ADAPTR)
                            callback?.onDeviceListChanged(devices)
                            notifyDeviceListeners(devices)
                        }
                    }
                }

                // Set AA mode on renderer for AA-specific behaviors:
                // first-frame skip, frame cache replay, crop scaling mode
                h264Renderer?.setAndroidAutoMode(message.phoneType == PhoneType.ANDROID_AUTO)

                // For CarPlay: start codec now (no surface resize needed).
                // For AA: onPhoneTypeChanged triggers UI resize → surfaceDestroyed/Created →
                // debounce path calls startCodecIfDeferred() with the new oversized surface.
                if (message.phoneType != PhoneType.ANDROID_AUTO) {
                    startCodecIfDeferred()
                }

                callback?.onPhoneTypeChanged(message.phoneType)

                // Enable GPS forwarding for CarPlay navigation (only if enabled in settings)
                if (config.gpsForwarding) {
                    adapterDriver?.sendCommand(CommandMapping.START_GNSS_REPORT)
                }

                setState(State.DEVICE_CONNECTED)
                setStatusText(context.localizedString(R.string.connection_status_phone_connected))

                // Proactively kick AltVideo (0x2C) streaming — step 1 of the three-step 508
                // handshake (see RE_Documention/02_Protocol_Reference/video_protocol.md
                // "Handshake Sequence"). On wireless CarPlay the adapter never initiates with
                // 508 — it sends only 506 (audio nav focus) — so without this proactive send
                // the handshake never starts and _AltScreenSetup is never invoked.
                // Step 3 (echo the adapter's 508 reply) lives in the CommandMessage handler.
                // Gated on debug+emulator + CarPlay (AA has no 0x2C path).
                if (com.carlink.ipc.NaviVideoSingleton.enabled &&
                    (message.phoneType == PhoneType.CARPLAY ||
                        message.phoneType == PhoneType.CARPLAY_WIRELESS)
                ) {
                    scope.launch {
                        // Small delay lets the adapter finish its post-PLUGGED handshake
                        // (REQUEST_VIDEO_FOCUS / REQUEST_NAVI_FOCUS arrive ~3-4s after PLUGGED).
                        // Send 508 after that so the adapter's session-init has set
                        // g_bSupportNaviScreen and the iPhone has registered featureAltScreen.
                        delay(2000)
                        val sent = adapterDriver?.sendCommand(
                            CommandMapping.REQUEST_NAVI_SCREEN_FOCUS,
                        ) ?: false
                        logInfo(
                            "[NAVI_FWD] Proactive REQUEST_NAVI_SCREEN_FOCUS (508) sent=$sent " +
                                "— kicking 0x2C AltVideo streaming",
                            tag = Logger.Tags.VIDEO,
                        )
                    }
                }
            }

            is UnpluggedMessage -> {
                if (!phoneAutoConnectEnabled.get()) {
                    setStatusText(context.localizedString(R.string.connection_status_phone_paused))
                } else if (negotiationRejected) {
                    logDebug(
                        "[PHASE] Unplugged after negotiation rejection — not restarting",
                        tag = Logger.Tags.ADAPTR,
                    )
                } else {
                    setStatusText(context.localizedString(R.string.connection_status_phone_unplugged))
                    requestRestart("phone unplugged")
                }
            }

            // VideoStreamingSignal indicates video data was processed directly by videoProcessor
            // No data to process here - just update state
            VideoStreamingSignal -> {
                clearPairTimeout()

                if (state != State.STREAMING) {
                    logInfo("Video streaming started (direct processing)", tag = Logger.Tags.VIDEO)
                    lastStreamingStartMs = System.currentTimeMillis()
                    setState(State.STREAMING)
                    setStatusText(context.localizedString(R.string.connection_status_streaming))
                    startVideoLivenessWatchdog()

                    if (currentPhoneType != PhoneType.ANDROID_AUTO) {
                        // CarPlay: delayed keyframe request 2.5s after first video.
                        // The adapter's natural SPS+PPS+IDR arrives at session start and decodes
                        // immediately. This delayed request serves as a safety net for cold-start
                        // decoder poisoning — empirically an Intel/MediaCodec-specific risk:
                        // - POTATO GM AAOS gminfo37 OMX.Intel.hw_vd.h264: 1-of-3 sessions had
                        //   Rx=48/Dec=0 zombie reset ~4min in, recovered via 2.5s IDR + 30s periodic.
                        // - macOS Cabin (VTDecompression/AVFoundation, firmware 2025.10.15.1127CAY):
                        //   0 resets across 24-minute session with ONLY 1 FRAME cmd at start.
                        // - AAOS emulator v120 (c2.goldfish.h264.decoder): 30s periodic FRAME
                        //   confirmed, 0 poisoning events across 3min.
                        // The 30s periodic is conservative defense for Intel MediaCodec; host-side
                        // FRAME cadence is policy, not firmware/iPhone requirement.
                        scheduleDelayedKeyframe()
                    }
                }
                // Video data already processed directly by videoProcessor (DIRECT_HANDOFF)
            }

            is AudioDataMessage -> {
                clearPairTimeout()
                processAudioData(message)
            }

            is MediaDataMessage -> {
                clearPairTimeout()
                processMediaMetadata(message)
            }

            is CommandMessage -> {
                if (message.command == CommandMapping.REQUEST_HOST_UI) {
                    callback?.onHostUIPressed()
                } else if (message.command == CommandMapping.REQUEST_VIDEO_FOCUS) {
                    hasVideoFocus = true
                    logDebug("[VIDEO_FOCUS] Adapter requested video focus", tag = Logger.Tags.VIDEO)
                } else if (message.command == CommandMapping.RELEASE_VIDEO_FOCUS) {
                    hasVideoFocus = false
                    lastVideoFrameReceivedMs = System.currentTimeMillis()
                    logDebug("[VIDEO_FOCUS] Adapter released video focus", tag = Logger.Tags.VIDEO)
                } else if (message.command == CommandMapping.WIFI_DISCONNECTED) {
                    // WiFi status notification - adapter's WiFi hotspot has no phone connected
                    // This is informational only, NOT a session termination signal
                    // Real disconnects come via UnpluggedMessage (0x04)
                    logDebug("[WIFI] Adapter WiFi status: not connected", tag = Logger.Tags.ADAPTR)
                } else if (message.command == CommandMapping.SCANNING_DEVICE) {
                    if (hadPriorSession && reconnectAttempts > 0) {
                        // Pattern B: adapter alive and scanning, but phone lost after a prior session.
                        // Adapter's wireless subsystem may be stuck.
                        setStatusText(context.localizedString(R.string.connection_status_scanning_paused))
                        logWarn(
                            "[ESCALATION] Pattern B: adapter scanning after prior session " +
                                "(reconnect attempt $reconnectAttempts)",
                            tag = Logger.Tags.ADAPTR,
                        )
                    } else {
                        setStatusText(context.localizedString(R.string.connection_status_scanning))
                    }
                    logDebug("[CMD] SCANNING_DEVICE", tag = Logger.Tags.ADAPTR)
                } else if (message.command == CommandMapping.BT_CONNECTED ||
                    message.command == CommandMapping.DEVICE_FOUND
                ) {
                    setStatusText(context.localizedString(R.string.connection_status_phone_found))
                    logDebug("[CMD] ${message.command.name}", tag = Logger.Tags.ADAPTR)
                } else if (message.command == CommandMapping.REQUEST_NAVI_SCREEN_FOCUS) {
                    // Step 3 of the three-step 508 handshake: adapter sent 508 back in reply to
                    // our proactive step-1 send (CommandMessage handler in PluggedMessage at ~L1805).
                    // Echo another 508 to confirm; the adapter then completes _AltScreenSetup
                    // and starts emitting Type 0x2C. See RE_Documention/02_Protocol_Reference/
                    // video_protocol.md "Handshake Sequence" for the wire diagram.
                    // Gated on NaviVideoSingleton.enabled so production APKs stay inert.
                    logDebug(
                        "[CMD] REQUEST_NAVI_SCREEN_FOCUS (id=508) received from adapter",
                        tag = Logger.Tags.ADAPTR,
                    )
                    if (com.carlink.ipc.NaviVideoSingleton.enabled) {
                        val sent = adapterDriver?.sendCommand(
                            CommandMapping.REQUEST_NAVI_SCREEN_FOCUS,
                        ) ?: false
                        logInfo(
                            "[NAVI_FWD] Echoed 508 back to adapter sent=$sent — completing focus handshake",
                            tag = Logger.Tags.VIDEO,
                        )
                    }
                } else if (message.command == CommandMapping.RELEASE_NAVI_SCREEN_FOCUS) {
                    // Adapter signaled end of nav video. Echo 509 back and notify the
                    // forwarder so consumer overlays hide deterministically.
                    logDebug(
                        "[CMD] RELEASE_NAVI_SCREEN_FOCUS (id=509) received from adapter",
                        tag = Logger.Tags.ADAPTR,
                    )
                    if (com.carlink.ipc.NaviVideoSingleton.enabled) {
                        adapterDriver?.sendCommand(CommandMapping.RELEASE_NAVI_SCREEN_FOCUS)
                        com.carlink.ipc.NaviVideoSingleton.forwarder.onStreamStop()
                        logInfo(
                            "[NAVI_FWD] Echoed 509 back + stopped forwarder",
                            tag = Logger.Tags.VIDEO,
                        )
                    }
                } else if (message.command == CommandMapping.INVALID) {
                    unknownCommandCount++
                    unknownCommandIds.add(message.rawId)
                    logWarn(
                        "[CMD] Unknown command id=${message.rawId} (0x${message.rawId.toString(16)})",
                        tag = Logger.Tags.PROTO_UNKNOWN,
                    )
                } else {
                    logDebug(
                        "[CMD] ${message.command.name} (id=${message.rawId})",
                        tag = Logger.Tags.ADAPTR,
                    )
                }
            }

            is BoxSettingsMessage -> {
                if (message.isPhoneInfo) {
                    // BoxSettings #2: phone connected — has MDModel, btMacAddr
                    val phoneModel = message.json.optString("MDModel", "").ifEmpty { null }
                    val btMac = message.json.optString("btMacAddr", "").ifEmpty { null }
                    if (btMac != null) {
                        _connectedBtMac = btMac
                        tryPrestageCodecCsd(btMac)
                    }
                    // Phone link metadata (sent after phone connects)
                    val linkType = message.json.optString("MDLinkType", "").ifEmpty { null }
                    val osVersion = message.json.optString("MDOSVersion", "").ifEmpty { null }
                    val linkVersion = message.json.optString("MDLinkVersion", "").ifEmpty { null }
                    val cpuTemp = message.json.optInt("cpuTemp", -1).takeIf { it >= 0 }
                    logInfo(
                        "[DEVICE] Phone identified: model=$phoneModel, bt=$btMac" +
                            (if (linkType != null) ", link=$linkType" else "") +
                            (if (osVersion != null) ", os=$osVersion" else "") +
                            (if (linkVersion != null) ", ver=$linkVersion" else "") +
                            (if (cpuTemp != null) ", cpuTemp=${cpuTemp}°C" else ""),
                        tag = Logger.Tags.ADAPTR,
                    )
                } else {
                    // BoxSettings #1: adapter info — has DevList of previously paired devices
                    val devices = synchronized(deviceListLock) {
                        _deviceList = parseDevList(message.json)
                        _deviceList
                    }
                    callback?.onDeviceListChanged(devices)
                    notifyDeviceListeners(devices)
                    // Adapter capabilities
                    val hiCar = message.json.optInt("HiCar", -1).takeIf { it >= 0 }
                    val supportFeatures = message.json.optString("supportFeatures", "").ifEmpty { null }
                    val channelList = message.json.optString("ChannelList", "").ifEmpty { null }
                    logInfo(
                        "[DEVICE] Paired devices: ${_deviceList.size}" +
                            (if (hiCar != null) ", HiCar=$hiCar" else "") +
                            (if (supportFeatures != null) ", features=$supportFeatures" else "") +
                            (if (channelList != null) ", channels=$channelList" else ""),
                        tag = Logger.Tags.ADAPTR,
                    )
                    _deviceList.forEachIndexed { idx, dev ->
                        logDebug(
                            "[DEVICE] DevList[$idx]: mac=${dev.btMac}, name=${dev.name}, " +
                                "type=${dev.type}, last=${dev.lastConnected ?: "n/a"}",
                            tag = Logger.Tags.ADAPTR,
                        )
                    }
                    // Hot-rejoin: if exactly 1 paired device, try cache lookup now
                    // (adapter may skip BoxSettings #2 and PeerBluetoothAddress)
                    if (_deviceList.size == 1) {
                        val mac = _deviceList[0].btMac
                        _connectedBtMac = mac
                        tryPrestageCodecCsd(mac)
                    }
                }

                // Detect unknown JSON keys from adapter firmware updates
                val knownKeys = setOf(
                    "uuid", "MFD", "boxType", "OemName", "productType", "hwVersion",
                    "supportLinkType", "WiFiChannel", "DevList", "ver", "mfd",
                    "MDModel", "btMacAddr", "buildModel", "iOSVer", "linkType",
                    "boxName", "wifiName", "btName", "oemIconLabel", "wifiPasswd",
                    "androidWorkMode", "phoneMode", "DashboardInfo",
                    "AndroidAutoSizeW", "AndroidAutoSizeH", "naviScreenInfo",
                    "AdvancedFeatures", "GNSSCapability", "callQuality",
                    "HiCar", "supportFeatures", "CusCode", "ChannelList",
                    "MDLinkType", "MDOSVersion", "MDLinkVersion", "cpuTemp",
                )
                val unknownKeys = message.json.keys().asSequence()
                    .filter { it !in knownKeys }
                    .toList()
                if (unknownKeys.isNotEmpty()) {
                    unknownBoxSettingsKeyCount += unknownKeys.size
                    val preview = unknownKeys.joinToString { key ->
                        "$key=${message.json.opt(key)}"
                    }
                    logWarn(
                        "[BOX_SETTINGS] Unknown keys: $preview",
                        tag = Logger.Tags.PROTO_UNKNOWN,
                    )
                }
            }

            is PeerBluetoothAddressMessage -> {
                _connectedBtMac = message.macAddress
                logInfo("[DEVICE] Peer BT address: ${message.macAddress}", tag = Logger.Tags.ADAPTR)
                tryPrestageCodecCsd(message.macAddress)
            }

            // Phase message — Phase 0 during STREAMING (with negotiationRejected=false) is
            // treated as a session termination that triggers restart(); Phase 0 in any other
            // state is normal session negotiation and is logged without action.
            is PhaseMessage -> {
                if (message.phase == 13) {
                    logWarn(
                        "[PHASE] Phase 13 (negotiation_failed) — Phone rejected configuration",
                        tag = Logger.Tags.ADAPTR,
                    )
                    negotiationRejected = true
                    setStatusText(context.localizedString(R.string.connection_status_config_reset))
                    requestNegotiationReset()
                } else if (message.phase == 0 && negotiationRejected) {
                    logDebug(
                        "[PHASE] Phase 0 after negotiation rejection — not restarting",
                        tag = Logger.Tags.ADAPTR,
                    )
                } else if (message.phase == 0 && state == State.STREAMING) {
                    logWarn(
                        "[PHASE] Phase 0 during STREAMING — session terminated by adapter",
                        tag = Logger.Tags.ADAPTR,
                    )
                    setStatusText(context.localizedString(R.string.connection_status_session_terminated))
                    requestRestart("phase 0 during streaming")
                } else if (message.phase == 0) {
                    logDebug(
                        "[PHASE] Phase 0 during $state — ignoring (normal session negotiation)",
                        tag = Logger.Tags.ADAPTR,
                    )
                } else if (message.phase == 7) {
                    setStatusText(context.localizedString(R.string.connection_status_phone_connecting))
                    logDebug("[PHASE] ${message.phase} (${message.phaseName})", tag = Logger.Tags.ADAPTR)
                } else {
                    logDebug("[PHASE] ${message.phase} (${message.phaseName})", tag = Logger.Tags.ADAPTR)
                }
            }

            // Navigation focus — echo back as recommended precaution (testing inconclusive)
            is NaviFocusMessage -> {
                if (message.isRequest) {
                    logDebug("[NAVI] Navigation video focus requested — echoing 508", tag = Logger.Tags.ADAPTR)
                    adapterDriver?.sendCommand(CommandMapping.REQUEST_NAVI_SCREEN_FOCUS)
                } else {
                    logDebug("[NAVI] Navigation video focus released — echoing 509", tag = Logger.Tags.ADAPTR)
                    adapterDriver?.sendCommand(CommandMapping.RELEASE_NAVI_SCREEN_FOCUS)
                    // Tell forwarder to broadcast onStreamEnded to bound consumers. Idempotent
                    // and safe to call when gate is false (the forwarder no-ops on !streamActive,
                    // and on prod/non-emulator no sinks are ever registered anyway).
                    com.carlink.ipc.NaviVideoSingleton.forwarder.onStreamStop()
                }
            }

            // Diagnostic messages — logged for protocol completeness and event correlation
            is StatusValueMessage -> {
                logDebug("[STATUS] Value=${message.value} (0x${message.value.toString(16)})", tag = Logger.Tags.ADAPTR)
            }

            is SessionTokenMessage -> {
                logDebug("[SESSION] Token received (${message.payloadSize}B encrypted)", tag = Logger.Tags.ADAPTR)
            }

            is BluetoothPairedListMessage -> {
                logInfo(
                    "[DEVICE] BluetoothPairedList: ${message.devices.size} devices (existing=${_deviceList.size})",
                    tag = Logger.Tags.ADAPTR,
                )
                if (message.devices.isNotEmpty()) {
                    // MERGE into existing list — 0x12 is sent multiple times:
                    // at init (all devices) and after BT connect (just the connecting device).
                    // Never shrink the list; only add/update entries.
                    // BoxSettings DevList (0x19) is the authoritative full list.
                    val update = synchronized(deviceListLock) {
                        val existingByMac = _deviceList.associateBy { it.btMac }.toMutableMap()
                        var changed = false
                        for ((mac, name) in message.devices) {
                            val existing = existingByMac[mac]
                            if (existing != null) {
                                if (existing.name != name) {
                                    existingByMac[mac] = existing.copy(name = name)
                                    changed = true
                                }
                            } else {
                                existingByMac[mac] = DeviceInfo(
                                    btMac = mac,
                                    name = name,
                                    type = "",
                                )
                                changed = true
                            }
                        }
                        if (changed || _deviceList.isEmpty()) {
                            _deviceList = existingByMac.values.toList()
                            _deviceList
                        } else {
                            null
                        }
                    }
                    if (update != null) {
                        val devices = update
                        devices.forEachIndexed { idx, dev ->
                            logDebug(
                                "[DEVICE] PairedList[$idx]: mac=${dev.btMac}, name=${dev.name}, type=${dev.type.ifEmpty { "unknown" }}",
                                tag = Logger.Tags.ADAPTR,
                            )
                        }
                        callback?.onDeviceListChanged(devices)
                        notifyDeviceListeners(devices)
                    }
                }
            }

            is InfoMessage -> {
                logDebug("[INFO] ${message.label}: ${message.value}", tag = Logger.Tags.ADAPTR)
            }

            is UnknownMessage -> {
                unknownMessageTypeCount++
                unknownMessageTypes.add(message.header.rawType)
                logWarn(
                    "[UNKNOWN] Unrecognized message type=0x${message.header.rawType.toString(16)} " +
                        "(${message.header.length}B) payload=${message.hexPreview()}",
                    tag = Logger.Tags.PROTO_UNKNOWN,
                )
            }
        }

        // Handle audio commands for mic capture
        if (message is AudioDataMessage && message.command != null) {
            handleAudioCommand(message.command, message.decodeType, message.rawCommandId)
        }
    }

    private fun processAudioData(message: AudioDataMessage) {
        // Handle volume ducking
        message.volumeDuration?.let {
            audioManager?.setDucking(message.volume)
            return
        }

        // Skip command messages
        if (message.command != null) return

        // Skip if no audio data
        val audioData = message.data ?: return

        // Track adapter's current audio decodeType (for mic format negotiation)
        lastIncomingDecodeType = message.decodeType

        // Write audio with offset+length to avoid copy
        // Two-factor routing: state flags + format match (not purpose) to prevent transition artifacts
        audioManager?.writeAudio(
            audioData,
            message.audioDataOffset,
            message.audioDataLength,
            message.audioType,
            message.decodeType,
            AudioRoutingState(
                isSiriActive = isSiriAudioActive,
                isPhoneCallActive = isPhoneCallAudioActive,
                isAlertActive = isAlertAudioActive,
            ),
        )
    }

    private fun handleAudioCommand(command: AudioCommand, messageDecodeType: Int = 5, rawCommandId: Int = command.id) {
        logDebug(
            "[AUDIO_CMD] ${command.name} (id=${command.id} siri=$isSiriAudioActive call=$isPhoneCallAudioActive alert=$isAlertAudioActive)",
            tag = Logger.Tags.AUDIO,
        )

        when (command) {
            AudioCommand.AUDIO_TBT_START,
            AudioCommand.AUDIO_NAVI_START -> {
                // TBT_START (byte 15) and NAVI_START (byte 6) both start nav audio.
                // TBT_START arrives ~97ms before NAVI_START in turn-by-turn sequences.
                // Whichever arrives first prepares the nav path; the second is a no-op
                // since onNavStarted() is idempotent.
                val cmdName = if (command == AudioCommand.AUDIO_TBT_START) "TBT_START" else "NAVI_START"
                logInfo("[AUDIO_CMD] Navigation audio START ($cmdName)", tag = Logger.Tags.AUDIO)
                audioManager?.onNavStarted()
            }

            AudioCommand.AUDIO_NAVI_STOP -> {
                logInfo("[AUDIO_CMD] Navigation audio STOP command received", tag = Logger.Tags.AUDIO)
                // Signal nav stopped - stop accepting new packets, but don't flush yet
                // (NAVI_COMPLETE will handle final cleanup including focus abandon)
                audioManager?.onNavStopped()
            }

            AudioCommand.AUDIO_NAVI_COMPLETE -> {
                logInfo("[AUDIO_CMD] Navigation audio COMPLETE command received", tag = Logger.Tags.AUDIO)
                // Explicit end-of-prompt signal from adapter - clean shutdown + abandon nav focus
                audioManager?.stopNavTrack()
            }

            AudioCommand.AUDIO_SIRI_START -> {
                logInfo("[AUDIO_CMD] Siri started - enabling microphone (mode: SIRI)", tag = Logger.Tags.MIC)
                activeVoiceMode = VoiceMode.SIRI
                isSiriAudioActive = true
                setPurpose(StreamPurpose.SIRI, command)
                startMicrophoneCapture(decodeType = 5, audioType = 3)
            }

            AudioCommand.AUDIO_PHONECALL_START -> {
                val micDecodeType = lastIncomingDecodeType
                logInfo("[AUDIO_CMD] Phone call started - enabling microphone (mode: PHONECALL, decodeType=$micDecodeType)", tag = Logger.Tags.MIC)
                activeVoiceMode = VoiceMode.PHONECALL
                isPhoneCallAudioActive = true
                endPurpose(StreamPurpose.RINGTONE, command) // Call answered — ringtone is over
                setPurpose(StreamPurpose.PHONE_CALL, command)
                startMicrophoneCapture(decodeType = micDecodeType, audioType = 3)
            }

            AudioCommand.AUDIO_SIRI_STOP -> {
                // CRITICAL: Don't stop mic if phone call is active (Siri-initiated call scenario)
                // USB capture shows: PHONECALL_START arrives ~130ms BEFORE SIRI_STOP
                // NOTE: "~130ms" was observed on real hardware during development; the specific
                // USB capture is not checked in to this repo. The guard below is what matters:
                // if activeVoiceMode == PHONECALL, we keep the mic open regardless of ordering.
                // Always clear siri routing flag (audio routing is independent of mic lifecycle)
                isSiriAudioActive = false
                // Pause SIRI AudioTrack regardless of branch — the silence-write idle
                // path keeps it PLAYING otherwise, leaving the USAGE_ASSISTANT volume
                // context active and stealing volume routing from MEDIA after Siri ends.
                audioManager?.stopSiriTrack()
                if (activeVoiceMode == VoiceMode.PHONECALL) {
                    logInfo(
                        "[AUDIO_CMD] Siri stopped but phone call active - keeping mic for call",
                        tag = Logger.Tags.MIC,
                    )
                    logDebug(
                        "[AUDIO_PURPOSE] SIRI_STOP: siri routing cleared, keeping mic for PHONE_CALL",
                        tag = Logger.Tags.AUDIO_DEBUG,
                    )
                    // Siri and PHONE_CALL have separate focus identities. Release Siri now,
                    // but keep the microphone and PHONE_CALL focus alive.
                    endPurpose(StreamPurpose.SIRI, command)
                } else {
                    logInfo("[AUDIO_CMD] Siri stopped - disabling microphone", tag = Logger.Tags.MIC)
                    activeVoiceMode = VoiceMode.NONE
                    endPurpose(StreamPurpose.SIRI, command)
                    stopMicrophoneCapture()
                }
            }

            AudioCommand.AUDIO_PHONECALL_STOP -> {
                logInfo("[AUDIO_CMD] Phone call stopped - disabling microphone", tag = Logger.Tags.MIC)
                activeVoiceMode = VoiceMode.NONE
                isPhoneCallAudioActive = false
                // Pause the phone-call AudioTrack BEFORE abandoning focus so AAOS
                // sees no active USAGE_VOICE_COMMUNICATION player. Without this,
                // the silence-write idle path keeps the track PLAYING and AAOS
                // keeps routing volume keys to the CALL group + ducking media.
                audioManager?.stopPhoneCallTrack()
                endPurpose(StreamPurpose.PHONE_CALL, command)
                stopMicrophoneCapture()
            }

            AudioCommand.AUDIO_MEDIA_START -> {
                logDebug("[AUDIO_CMD] Media audio START command received", tag = Logger.Tags.AUDIO)
                // Adapter signals media resuming — clear any residual adapter ducking from
                // a previous voice session (Siri/phone call). The adapter does not send an
                // explicit volume=1.0 restore packet; MEDIA_START is the session boundary.
                audioManager?.setDucking(1.0f)
                endPurpose(StreamPurpose.RINGTONE, command) // Safety net — abandon before MEDIA focus request
                setPurpose(StreamPurpose.MEDIA, command)
            }

            AudioCommand.AUDIO_MEDIA_STOP -> {
                logDebug("[AUDIO_CMD] Media audio STOP command received", tag = Logger.Tags.AUDIO)
                endPurpose(StreamPurpose.MEDIA, command)
            }

            AudioCommand.AUDIO_ALERT_START -> {
                logDebug("[AUDIO_CMD] Alert started", tag = Logger.Tags.AUDIO)
                isAlertAudioActive = true
                audioManager?.onAlertStarted()
                setPurpose(StreamPurpose.ALERT, command)
            }

            AudioCommand.AUDIO_ALERT_STOP -> {
                logDebug("[AUDIO_CMD] Alert stopped", tag = Logger.Tags.AUDIO)
                isAlertAudioActive = false
                audioManager?.onAlertStopped()
                endPurpose(StreamPurpose.RINGTONE, command) // Call declined/missed — ringtone is over
            }

            AudioCommand.AUDIO_OUTPUT_START -> {
                logDebug("[AUDIO_CMD] Audio output START command received", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_OUTPUT_STOP -> {
                logDebug("[AUDIO_CMD] Audio output STOP command received", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_INCOMING_CALL_INIT -> {
                // No-op: RINGTONE has no AudioTrack slot and no audio data routing.
                // ALERT_START follows ~1s later and independently handles focus.
                // Previously this called setPurpose(RINGTONE) which acquired AUDIOFOCUS_GAIN_TRANSIENT
                // but was never abandoned (no INCOMING_CALL_STOP exists in the protocol), causing
                // permanent media silence after phone calls.
                logInfo("[AUDIO_CMD] Incoming call ring (AudioCmd 14)", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_INPUT_CONFIG -> {
                logDebug("[AUDIO_CMD] AUDIO_INPUT_CONFIG received (decodeType=$messageDecodeType)", tag = Logger.Tags.AUDIO)
                lastIncomingDecodeType = messageDecodeType
                // If mic is active, restart capture at the adapter's requested format
                // (mirrors Autokit: case 3 → a.i = w.a → h.h(c(w.a, true)))
                if (isMicrophoneCapturing && currentMicDecodeType != messageDecodeType) {
                    logInfo("[AUDIO_CMD] INPUT_CONFIG: switching mic from decodeType=$currentMicDecodeType to $messageDecodeType", tag = Logger.Tags.MIC)
                    stopMicrophoneCapture()
                    startMicrophoneCapture(decodeType = messageDecodeType, audioType = currentMicAudioType)
                }
            }

            AudioCommand.UNKNOWN -> {
                unknownAudioCommandCount++
                unknownAudioCommandIds.add(rawCommandId)
                logWarn(
                    "[AUDIO_CMD] Unknown audio command rawId=$rawCommandId (0x${rawCommandId.toString(16)})",
                    tag = Logger.Tags.PROTO_UNKNOWN,
                )
            }
        }
    }

    /** Request AudioFocus for a stream purpose. */
    private fun setPurpose(purpose: StreamPurpose, trigger: AudioCommand) {
        if (!config.audioTransferMode) {
            audioManager?.onPurposeChanged(purpose)
        }
        logDebug(
            "[AUDIO_PURPOSE] → $purpose (trigger: ${trigger.name} voiceMode: $activeVoiceMode)",
            tag = Logger.Tags.AUDIO_DEBUG,
        )
    }

    /** Abandon AudioFocus for a stream purpose. */
    private fun endPurpose(purpose: StreamPurpose, trigger: AudioCommand) {
        if (!config.audioTransferMode) {
            audioManager?.onPurposeEnded(purpose)
        }
        logDebug(
            "[AUDIO_PURPOSE] Ended $purpose (trigger: ${trigger.name})",
            tag = Logger.Tags.AUDIO_DEBUG,
        )
    }

    private fun startMicrophoneCapture(
        decodeType: Int,
        audioType: Int,
        recoveryAttempt: Boolean = false,
    ) {
        if (!sensitiveBackgroundCapabilitiesAvailable) return
        if (!recoveryAttempt) {
            micRecoveryJob?.cancel()
            micRecoveryJob = null
            micRecoveryAttempts = 0
        }
        if (isMicrophoneCapturing) {
            if (currentMicDecodeType == decodeType && currentMicAudioType == audioType) {
                return
            }
            stopMicrophoneCapture()
        }

        // Keep the requested format even if AudioRecord cannot start yet. Recovery
        // must retry this call's format, not the previous call or Siri's defaults.
        currentMicDecodeType = decodeType
        currentMicAudioType = audioType
        val started = microphoneManager?.start(decodeType) ?: false
        if (started) {
            isMicrophoneCapturing = true
            micCaptureStartedAtMs = System.currentTimeMillis()

            // Start send loop
            // 20ms period paired with the 320B/640B chunk sizes in sendMicrophoneData (both
            // derived from 20ms @ 8kHz/16kHz mono PCM). If either changes without the other,
            // the mic stream underruns.
            micSendTimer =
                Timer().apply {
                    scheduleAtFixedRate(
                        object : TimerTask() {
                            override fun run() {
                                try {
                                    sendMicrophoneData()
                                } catch (error: Exception) {
                                    logError("[MIC] Send loop failed: ${error.message}", tag = Logger.Tags.MIC)
                                    stopMicrophoneCapture()
                                }
                            }
                        },
                        0,
                        20,
                    ) // 20ms interval
                }

            logInfo("Microphone capture started", tag = Logger.Tags.MIC)
        } else if (activeVoiceMode != VoiceMode.NONE) {
            scheduleMicrophoneRecovery()
        }
    }

    private fun stopMicrophoneCapture() {
        micRecoveryJob?.cancel()
        micRecoveryJob = null
        micSendTimer?.cancel()
        micSendTimer = null
        microphoneManager?.stop()
        if (!isMicrophoneCapturing) return
        isMicrophoneCapturing = false

        logInfo("Microphone capture stopped", tag = Logger.Tags.MIC)
    }

    private fun sendMicrophoneData() {
        if (!isMicrophoneCapturing) return

        val mic = microphoneManager ?: return
        if (!mic.isCapturing()) {
            logWarn("[MIC] Capture backend stopped unexpectedly — scheduling recovery", tag = Logger.Tags.MIC)
            micSendTimer?.cancel()
            micSendTimer = null
            microphoneManager?.stop()
            isMicrophoneCapturing = false
            scheduleMicrophoneRecovery()
            return
        }

        val chunkSize = if (currentMicDecodeType == 3) 320 else 640 // 20ms at 8kHz or 16kHz mono
        val data = mic.readChunk(maxBytes = chunkSize) ?: return
        if (data.isNotEmpty()) {
            adapterDriver?.sendAudio(
                data = data,
                decodeType = currentMicDecodeType,
                audioType = currentMicAudioType,
            )
        }
    }

    private fun scheduleMicrophoneRecovery() {
        if (activeVoiceMode == VoiceMode.NONE || released.get()) return
        if (micRecoveryJob?.isActive == true) return

        val livedMs = System.currentTimeMillis() - micCaptureStartedAtMs
        if (livedMs >= MIC_STABLE_WINDOW_MS) {
            micRecoveryAttempts = 0
            micCaptureStartedAtMs = System.currentTimeMillis()
        }
        val exponent = micRecoveryAttempts.coerceAtMost(5)
        val delayMs = (500L shl exponent).coerceAtMost(MIC_RECOVERY_MAX_DELAY_MS)
        micRecoveryAttempts++
        micRecoveryJob =
            scope.launch {
                delay(delayMs)
                micRecoveryJob = null
                if (activeVoiceMode != VoiceMode.NONE && !isMicrophoneCapturing && !released.get()) {
                    logInfo("[MIC] Recovery attempt $micRecoveryAttempts after ${delayMs}ms", tag = Logger.Tags.MIC)
                    startMicrophoneCapture(currentMicDecodeType, currentMicAudioType, recoveryAttempt = true)
                }
            }
    }

    // DEBUG helper: emit a possibly-long iAP2 hex string across multiple log lines so the
    // full payload survives logcat's per-line ~4000 char cap. Single short strings fit in
    // one line. Strip together with processMediaMetadata's [NAVI_JSON_RX] block before
    // production release.
    private fun logIap2Field(
        name: String,
        hex: String,
    ) {
        val chunkSize = 3500
        val total = (hex.length + chunkSize - 1) / chunkSize
        if (total <= 1) {
            logInfo("[NAVI_JSON_RX:$name len=${hex.length}] $hex", tag = Logger.Tags.NAVI)
            return
        }
        for (i in 0 until total) {
            val s = i * chunkSize
            val e = minOf(s + chunkSize, hex.length)
            logInfo(
                "[NAVI_JSON_RX:$name len=${hex.length} chunk=${i + 1}/$total] ${hex.substring(s, e)}",
                tag = Logger.Tags.NAVI,
            )
        }
    }

    private fun processMediaMetadata(message: MediaDataMessage) {
        // Route NaviJSON to NavigationStateManager for cluster display (only if enabled)
        if (message.type == MediaType.NAVI_JSON) {
            if (!AdapterConfigPreference.getInstance(context).getClusterNavigationSync()) {
                NavigationStateManager.clear()
                com.cabin.navigation.compose.ComposedIconStore.clear()
                mediaSessionManager?.updateTeyesClusterNavigation(com.cabin.navigation.NavigationState())
                return
            }
            // DEBUG: log full NaviJSON receipt so we can confirm the patched ARMiPhoneIAP2
            // is emitting `_iap2` / `_iap2m` recovery fields. Each _iap2* hex payload (up to
            // ~3KB for 0x5202 maneuver bursts) is emitted on its own log line and chunked at
            // 3500 chars to stay under Android's per-line logcat limit (~4000). Decoder-side
            // re-assembly: concatenate all "[NAVI_JSON_RX:_iap2m] chunk=N/M payload=..." lines
            // for the same timestamp. Strip before production-grade release.
            val keys = message.payload.keys.sorted()
            // Dump primitive Navi* values (skip _iap2/_iap2m which are huge hex — those go on
            // their own chunked lines below). Lets us correlate per-step events against the
            // 0x5202 maneuver list during the cursor-walk-matcher design pass.
            val naviPrimitives = message.payload
                .filterKeys { it.startsWith("Navi") }
                .entries
                .joinToString(", ") { (k, v) ->
                    val s = v.toString()
                    "$k=${if (s.length > 80) s.take(80) + "…" else s}"
                }
            logInfo("[NAVI_JSON_RX] keys=$keys${if (naviPrimitives.isNotEmpty()) " | $naviPrimitives" else ""}", tag = Logger.Tags.NAVI)
            // v6.1: parse raw 0x5201 RouteGuidanceUpdate for the wire cursor + state and
            // feed NavigationStateManager BEFORE onNaviJson runs (same USB read thread).
            // Set null on absent _iap2 OR parse failure to prevent stale-cursor reuse —
            // see Iap2StateParser.parse contract.
            val iap2Hex = message.payload["_iap2"] as? String
            if (iap2Hex != null) {
                logIap2Field("_iap2", iap2Hex)
                com.cabin.navigation.NavigationStateManager.setLastIap2State(
                    com.cabin.navigation.Iap2StateParser.parse(iap2Hex)
                )
            } else {
                com.cabin.navigation.NavigationStateManager.setLastIap2State(null)
            }
            (message.payload["_iap2m"] as? String)?.let { iap2m ->
                logIap2Field("_iap2m", iap2m)
                // Populate the runtime icon composer cache. Parses the burst synchronously
                // (cheap) on this USB read thread, then dispatches the per-maneuver
                // compose+render to a background worker so it never blocks video reads.
                // Per-step events later call ComposedIconStore.lookup (static fallback until
                // the background fill completes). Gated by ComposedIconStore.enabled — when
                // off, lookup returns null and the static-XML / AA-bitmap paths handle the
                // cluster icon. See app/src/main/kotlin/com/cabin/navigation/compose/.
                val dispatched = com.cabin.navigation.compose.ComposedIconStore.populateFromIap2m(iap2m)
                if (dispatched != null) {
                    logInfo("[NAVI_JSON_RX] Composer dispatched $dispatched maneuvers for background compose", tag = Logger.Tags.NAVI)
                }
            }
            // NOTE: getClusterNavigationSync() is read at message time, NOT cached —
            // a user toggling cluster navigation in AdapterConfigurationDialog takes effect
            // on the next NAVI_* message, no restart needed.
            NavigationStateManager.onNaviJson(message.payload)
            mediaSessionManager?.updateTeyesClusterNavigation(NavigationStateManager.state.value)
            return
        }

        // Route AA maneuver icons to NavigationStateManager (sub-type 201)
        if (message.type == MediaType.NAVI_IMAGE) {
            // NOTE: getClusterNavigationSync() is read at message time, NOT cached —
            // a user toggling cluster navigation in AdapterConfigurationDialog takes effect
            // on the next NAVI_* message, no restart needed.
            if (AdapterConfigPreference.getInstance(context).getClusterNavigationSync()) {
                val imageData = message.payload["NaviImage"] as? ByteArray
                if (imageData != null) {
                    NavigationStateManager.onNaviImage(imageData)
                } else {
                    logWarn("[NAVI_ICON] NAVI_IMAGE message with no image data", tag = Logger.Tags.NAVI)
                }
            }
            return
        }

        // Android Auto album art arrives as a standalone MEDIA_DATA subtype 2 message (PNG).
        // Cache it and push a metadata update so MediaSession gets the cover immediately,
        // without waiting for the next subtype-1 JSON tick which carries no image bytes.
        if (message.type == MediaType.ALBUM_COVER_AA) {
            val coverBytes = message.payload["AlbumCover"] as? ByteArray
            if (coverBytes != null) {
                lastAlbumCover = coverBytes
                mediaSessionManager?.updateMetadata(
                    title = lastMediaSongName,
                    artist = lastMediaArtistName,
                    album = lastMediaAlbumName,
                    appName = lastMediaAppName,
                    albumArt = lastAlbumCover,
                    duration = lastDuration,
                )
            }
            return
        }

        // CallStatus JSON (subtype 100) — iAP2 CallStateEngine forwarding.
        // Log at INFO for release troubleshooting. Not consumed for UI — CarPlay/AA
        // projection already shows call screen, and cluster requires system privilege.
        if (message.type == MediaType.CALL_STATUS) {
            val status = message.payload["CallStatus"] as? Int ?: -1
            val direction = message.payload["CallDirection"] as? Int
            val name = message.payload["CallName"] as? String
            val number = message.payload["CallNumber"] as? String
            val statusName = when (status) {
                0 -> "idle"
                1 -> "dialing"
                2 -> "ringing"
                3 -> "connected"
                4 -> "disconnecting"
                else -> "?$status"
            }
            val dirName = when (direction) {
                1 -> "incoming"
                2 -> "outgoing"
                else -> ""
            }
            val caller = name ?: number ?: ""
            logInfo(
                "[CALL_STATUS] $statusName $dirName${if (caller.isNotEmpty()) " caller=$caller" else ""}",
                tag = Logger.Tags.PHONE,
            )
            return
        }

        // Unknown MediaData subtype — log everything for protocol discovery
        if (message.type == MediaType.UNKNOWN) {
            unknownMediaSubtypeCount++
            val subtype = message.payload["_unknownSubtype"]
            if (subtype is Int) unknownMediaSubtypes.add(subtype)
            val hex = message.payload["_hexPreview"] ?: ""
            logWarn(
                "[MEDIA_UNKNOWN] Unrecognized MediaData subtype=$subtype " +
                    "(${message.header.length}B) payload=$hex",
                tag = Logger.Tags.PROTO_UNKNOWN,
            )
            return
        }

        val payload = message.payload

        // Extract new song title (if present)
        val newSongName = (payload["MediaSongName"] as? String)?.takeIf { it.isNotEmpty() }

        // WHY we snapshot previous state before the song-change reset:
        // Android Auto sends metadata in split increments across consecutive
        // frames (frame 1: title only; frame 2: artist only; frame 3: album).
        // The prior metadataChanged predicate compared only title+cover, so
        // artist/album/appName-only updates were silently dropped — cluster
        // showed title without artist until the next song change. Capturing
        // previous* here lets metadataChanged below detect incremental changes
        // even after lastMediaArtistName/etc. have been overwritten.
        val previousSongName = lastMediaSongName
        val previousArtist = lastMediaArtistName
        val previousAlbum = lastMediaAlbumName
        val previousAppName = lastMediaAppName
        val previousDuration = lastDuration

        // Detect song change — clear all cached metadata to prevent stale data mixing
        if (newSongName != null && newSongName != previousSongName) {
            lastMediaSongName = null
            lastMediaArtistName = null
            lastMediaAlbumName = null
            lastAlbumCover = null
            lastDuration = 0L
            lastPosition = 0L
            // Keep appName - typically doesn't change mid-session
        }

        // Extract text metadata
        newSongName?.let {
            lastMediaSongName = it
        }
        val newArtist = (payload["MediaArtistName"] as? String)?.takeIf { it.isNotEmpty() }
        newArtist?.let { lastMediaArtistName = it }
        val newAlbum = (payload["MediaAlbumName"] as? String)?.takeIf { it.isNotEmpty() }
        newAlbum?.let { lastMediaAlbumName = it }
        val newAppName = (payload["MediaAPPName"] as? String)?.takeIf { it.isNotEmpty() }
        newAppName?.let { lastMediaAppName = it }

        // Process album cover after song change detection
        val albumCover = payload["AlbumCover"] as? ByteArray
        if (albumCover != null) {
            lastAlbumCover = albumCover
        }

        // Extract playback fields (missing before: position, duration, play status)
        val duration = (payload["MediaSongDuration"] as? Number)?.toLong() ?: lastDuration
        val position = (payload["MediaSongPlayTime"] as? Number)?.toLong() ?: lastPosition
        val playStatus = (payload["MediaPlayStatus"] as? Number)?.toInt()
        val isPlaying = if (playStatus != null) playStatus == 1 else lastIsPlaying

        // Cache playback fields
        lastDuration = duration
        lastPosition = position
        lastIsPlaying = isPlaying

        val mediaInfo =
            MediaInfo(
                songTitle = lastMediaSongName,
                songArtist = lastMediaArtistName,
                albumName = lastMediaAlbumName,
                appName = lastMediaAppName,
                albumCover = lastAlbumCover,
                duration = duration,
                position = position,
                isPlaying = isPlaying,
            )

        projectionHealth.media(mediaInfo.songTitle, mediaInfo.songArtist, mediaInfo.isPlaying)

        // WHY this broader predicate: AA's incremental metadata stream would
        // otherwise suppress legitimate artist/album/appName/duration-only
        // updates. Position-only ticks (~95% of messages) still short-circuit
        // because none of the guarded fields change between them.
        val metadataChanged =
            (newSongName != null && newSongName != previousSongName) ||
                (newArtist != null && newArtist != previousArtist) ||
                (newAlbum != null && newAlbum != previousAlbum) ||
                (newAppName != null && newAppName != previousAppName) ||
                albumCover != null ||
                (duration > 0 && duration != previousDuration)

        if (metadataChanged) {
            // Full metadata update (title/artist/album/cover/duration)
            mediaSessionManager?.updateMetadata(
                title = mediaInfo.songTitle,
                artist = mediaInfo.songArtist,
                album = mediaInfo.albumName,
                appName = mediaInfo.appName,
                albumArt = mediaInfo.albumCover,
                duration = duration,
            )

            // Update foreground notification with current now-playing
            CabinMediaBrowserService.updateNowPlaying(mediaInfo.songTitle, mediaInfo.songArtist)
        }

        // Always update playback state (position ticks are the common case)
        mediaSessionManager?.updatePlaybackState(playing = isPlaying, position = position)

        if (BuildConfig.DEBUG) {
            val songChanged = newSongName != null && newSongName != previousSongName
            logDebug(
                "[MEDIA_DATA] " +
                    (if (songChanged) "[SONG_CHANGE] " else "") +
                    "title=${newSongName ?: "·"} artist=${newArtist ?: "·"} album=${newAlbum ?: "·"} " +
                    "app=${newAppName ?: "·"} art=${albumCover?.size ?: 0}B " +
                    "dur=${duration}ms pos=${position}ms playing=$isPlaying " +
                    "→ pushedMetadata=$metadataChanged",
                tag = Logger.Tags.MEDIA,
            )
        }
    }

    /**
     * Error handler for adapter communication failures.
     *
     * Performs full session cleanup (matching stop()) so that start() called
     * from reconnect sees a clean slate. Without this, start() finds a non-null
     * adapterDriver, calls stop(), which calls cancelReconnect() — killing the
     * very coroutine that invoked start().
     *
     * For USB disconnects, schedules auto-reconnect with exponential backoff.
     */
    private fun handleError(error: String) {
        if (!errorRecoveryPending.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            lifecycleMutex.lock()
            try {
                if (!released.get()) handleErrorLocked(error)
            } finally {
                lifecycleMutex.unlock()
                errorRecoveryPending.set(false)
            }
        }
    }

    private fun handleErrorLocked(error: String) {
        touchSender.clear()
        // NOTE: `hadPriorSession` is intentionally NOT reset here (it's only reset in
        // stop()). This preserves escalation context across mid-session failures so that
        // Pattern A/B/C status messages can correctly distinguish "adapter broken after a
        // prior session" from "never connected".
        clearPairTimeout()

        logError("Adapter error: $error", tag = Logger.Tags.ADAPTR)

        // Full session state reset (mirrors stop() minus cancelReconnect/graceful teardown)
        cancelDelayedKeyframe()
        stopVideoLivenessWatchdog()
        negotiationRejected = false
        pendingConnectTarget = null
        lastConnectTargetMac = null
        currentPhoneType = null
        currentWifi = null
        videoPhoneTypeInferred = false
        codecDeferred = true
        requestKeyframeOnCodecStart = false
        videoEncoderType = 2
        videoOffScreen = 0
        callback?.onPhoneTypeChanged(PhoneType.UNKNOWN)
        clearCachedMediaMetadata()
        activeVoiceMode = VoiceMode.NONE
        isSiriAudioActive = false
        isPhoneCallAudioActive = false
        isAlertAudioActive = false
        stopMicrophoneCapture()
        gnssForwarder?.stop()

        // Stop adapter driver (heartbeat, reading loop) and close USB.
        // Skip graceful teardown — USB is likely dead.
        adapterDriver?.stop()
        adapterDriver = null
        usbDevice?.close()
        usbDevice = null

        if (audioInitialized) {
            audioManager?.release()
            audioInitialized = false
        }

        // Pattern A: track consecutive "no initial response" errors (adapter USB write dead)
        // Pattern C: track short-lived STREAMING sessions (unstable adapter)
        val isNoResponse = error.contains("no initial response")
        if (isNoResponse) {
            consecutiveNoResponse++
        } else {
            consecutiveNoResponse = 0
        }

        if (lastStreamingStartMs > 0) {
            val sessionDuration = System.currentTimeMillis() - lastStreamingStartMs
            if (sessionDuration < SHORT_SESSION_THRESHOLD_MS) {
                shortLivedStreamingCount++
            }
            lastStreamingStartMs = 0L
        }

        setState(State.DISCONNECTED)

        // Schedule auto-reconnect for USB disconnect errors
        if (isUsbDisconnectError(error)) {
            // Escalate status based on observed patterns
            if (consecutiveNoResponse >= 2) {
                // Pattern A: adapter USB write dead — retrying won't help
                setStatusText(context.localizedString(R.string.connection_status_unresponsive))
                logWarn("[ESCALATION] Pattern A: $consecutiveNoResponse consecutive no-response errors", tag = Logger.Tags.USB)
            } else if (shortLivedStreamingCount >= SHORT_SESSION_ESCALATION_COUNT) {
                // Pattern C: sessions keep dying within seconds
                setStatusText(context.localizedString(R.string.connection_status_unstable))
                logWarn("[ESCALATION] Pattern C: $shortLivedStreamingCount short-lived sessions", tag = Logger.Tags.USB)
            } else if (isNoResponse) {
                setStatusText(context.localizedString(R.string.connection_status_unresponsive_retry))
            }
            scheduleReconnect()
        }
    }

    /**
     * Checks if an error indicates USB disconnect (physical or transfer failure).
     */
    private fun isUsbDisconnectError(error: String): Boolean {
        val lowerError = error.lowercase()
        return lowerError.contains("disconnect") ||
            lowerError.contains("detach") ||
            lowerError.contains("transfer") ||
            lowerError.contains("usb")
    }

    /**
     * Schedule an auto-reconnect attempt with exponential backoff.
     *
     * After USB disconnect, attempts to reconnect automatically:
     * - Attempt 1: 2 seconds delay
     * - Attempt 2: 4 seconds delay
     * - Attempt 3: 8 seconds delay
     * - Attempt 4: 16 seconds delay
     * - Attempt 5: 30 seconds delay (capped)
     *
     * Gives up after MAX_RECONNECT_ATTEMPTS to prevent infinite loops.
     */
    private fun scheduleReconnect() {
        // Cancel any existing reconnect attempt
        reconnectJob?.cancel()
        if (!shouldBeRunning.get() || released.get()) return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logWarn(
                "[RECONNECT] Max attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up. " +
                    "noResponse=$consecutiveNoResponse shortSessions=$shortLivedStreamingCount hadPrior=$hadPriorSession",
                tag = Logger.Tags.USB,
            )
            val giveUpMessage = when {
                consecutiveNoResponse >= 2 -> context.localizedString(R.string.connection_status_unresponsive)
                shortLivedStreamingCount >= SHORT_SESSION_ESCALATION_COUNT -> context.localizedString(R.string.connection_status_unstable)
                hadPriorSession -> context.localizedString(R.string.connection_status_phone_retry)
                else -> context.localizedString(R.string.connection_status_replug)
            }
            // After give-up: reconnectAttempts is reset to 0, but shortLivedStreamingCount
            // / consecutiveNoResponse are NOT reset. A user who manually taps reconnect
            // after give-up will immediately trip Pattern A/C escalation on the first
            // failure. Intentional so the escalation context survives the "give up" → "user
            // retries" boundary; but the UX can surprise.
            reconnectAttempts = 0
            setStatusText(giveUpMessage)
            // Stop FGS since we're no longer attempting to reconnect
            CabinMediaBrowserService.stopConnectionForeground(context)
            return
        }

        // Maintain foreground priority during reconnect delay to prevent LMK kill
        CabinMediaBrowserService.startConnectionForeground(context)

        // Calculate delay with exponential backoff, capped at max
        val delay =
            minOf(
                INITIAL_RECONNECT_DELAY_MS * (1L shl reconnectAttempts),
                MAX_RECONNECT_DELAY_MS,
            )
        reconnectAttempts++

        logInfo(
            "[RECONNECT] Scheduling attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delay}ms",
            tag = Logger.Tags.USB,
        )

        setStatusText(context.localizedString(R.string.connection_status_retry_count, reconnectAttempts, MAX_RECONNECT_ATTEMPTS))

        reconnectJob =
            scope.launch {
                delay(delay)

                // Only attempt if still disconnected
                if (state == State.DISCONNECTED && shouldBeRunning.get() && !released.get()) {
                    logInfo("[RECONNECT] Attempting reconnection...", tag = Logger.Tags.USB)
                    try {
                        withContext(Dispatchers.IO) { startIfDesired() }
                    } catch (e: Exception) {
                        logError("[RECONNECT] Reconnection failed: ${e.message}", tag = Logger.Tags.USB)
                        // handleError will be called by start() failure, which will schedule next attempt
                    }
                } else {
                    logInfo("[RECONNECT] Already connected, cancelling reconnect", tag = Logger.Tags.USB)
                    reconnectAttempts = 0
                }
            }
    }

    /**
     * Cancel any pending reconnect attempt.
     */
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
    }

    private fun clearPairTimeout() {
        pairTimeout?.cancel()
        pairTimeout = null
    }

    /**
     * Schedule keyframe requests for CarPlay sessions.
     *
     * 1. Initial delayed request (2.5s): The adapter sends a natural SPS+PPS+IDR at session
     *    start which the codec decodes immediately. This delayed request serves as a cold-start
     *    safety net — if the decoder is poisoned (observed on Intel hardware, first session only),
     *    the fresh IDR on a now-warm codec clears the poisoned state.
     *
     * 2. Periodic interval (30s): Passive self-healing against mid-session decoder corruption
     *    from platform instability. The GM Info 3.7 Intel Atom x7-A3960 platform has a
     *    poorly designed VPU/USB subsystem where silent decoder corruption can occur from
     *    USB bulk stalls, GHS hypervisor interrupts, or Intel VPU firmware bugs — factors
     *    outside the app's control. The watchdog only catches complete decode failure (Rx>0,
     *    Dec=0), NOT progressive quality degradation from corrupted reference frames.
     *    A periodic IDR is the only fix for silent corruption.
     *    NOTE: the GM Info 3.7 / Intel Atom x7-A3960 rationale was observed on real hardware
     *    during development; there is no in-tree capture or datasheet reference that proves
     *    the VPU/USB failure modes listed above. Treat it as the author's best explanation
     *    for why the periodic keyframe exists — the *behavior* (30s IDR cures silent
     *    corruption in the field) is what's load-bearing.
     *
     *    CarPlay encoder teardown is invisible to the user at any reasonable interval.
     *    The 30s value is configurable per-platform — 2s was used historically with no
     *    user-visible impact. Shorter intervals trade iPhone encoder overhead for faster
     *    corruption recovery. Adjust the delay value below as needed.
     *
     * Cancels any pending request first. AA must NOT use this — FRAME resets phone UI.
     */
    @Synchronized
    private fun scheduleDelayedKeyframe() {
        frameIntervalJob?.cancel()
        if (videoOverlayCovered) {
            frameIntervalJob = null
            return
        }
        frameIntervalJob =
            scope.launch(Dispatchers.IO) {
                logInfo("[FRAME_INTERVAL] CarPlay keyframe scheduled (2.5s initial, 30s periodic)", tag = Logger.Tags.VIDEO)

                // Initial delayed keyframe — cold-start safety net
                delay(2500)
                val initialSent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                logInfo("[FRAME_INTERVAL] CarPlay initial keyframe sent=$initialSent", tag = Logger.Tags.VIDEO)

                // Periodic keyframe — mid-session self-healing for unstable platforms (GM Intel VPU)
                var requestCount = 0
                while (isActive) {
                    delay(30000)
                    requestCount++
                    val sent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                    logDebug("[FRAME_INTERVAL] CarPlay periodic keyframe #$requestCount sent=$sent", tag = Logger.Tags.VIDEO)
                }
            }
    }

    /**
     * Cancel any pending delayed keyframe request.
     */
    @Synchronized
    private fun cancelDelayedKeyframe() {
        val wasActive = frameIntervalJob?.isActive == true
        if (wasActive) {
            logDebug("[FRAME_INTERVAL] Cancelling pending delayed keyframe", tag = Logger.Tags.VIDEO)
            frameIntervalJob?.cancel()
        }
        frameIntervalJob = null
    }

    /** Recover projection video when USB stays alive but valid source frames stop. */
    private fun startVideoLivenessWatchdog() {
        stopVideoLivenessWatchdog()
        lastVideoFrameReceivedMs = System.currentTimeMillis()
        videoLivenessJob =
            scope.launch(Dispatchers.IO) {
                var recoveryRequested = false
                while (isActive && state == State.STREAMING) {
                    delay(2000)
                    val phoneType = currentPhoneType ?: continue
                    val visibleAndFocused =
                        !headlessMode && !videoPaused && !videoOverlayCovered && videoOffScreen == 0 &&
                            hasVideoFocus && videoSurface?.isValid == true
                    if (!visibleAndFocused) {
                        // AA legitimately stops or throttles video while another screen owns
                        // focus or the Activity is backgrounded. Keep USB/audio alive.
                        lastVideoFrameReceivedMs = System.currentTimeMillis()
                        recoveryRequested = false
                        continue
                    }
                    val silentFor = System.currentTimeMillis() - lastVideoFrameReceivedMs
                    val requestAfterMs = if (phoneType == PhoneType.ANDROID_AUTO) 6_000L else 8_000L
                    val restartAfterMs = if (phoneType == PhoneType.ANDROID_AUTO) 12_000L else 18_000L
                    val protocolName = if (phoneType == PhoneType.ANDROID_AUTO) "AA" else "CarPlay"
                    when {
                        silentFor >= restartAfterMs && recoveryRequested -> {
                            logWarn("[VIDEO_WATCHDOG] $protocolName source silent for ${silentFor}ms — restarting", tag = Logger.Tags.VIDEO)
                            requestRestart("$protocolName video source stalled")
                            return@launch
                        }
                        silentFor >= requestAfterMs && !recoveryRequested -> {
                            recoveryRequested = true
                            val sent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                            logWarn("[VIDEO_WATCHDOG] $protocolName source silent for ${silentFor}ms — FRAME sent=$sent", tag = Logger.Tags.VIDEO)
                        }
                        silentFor < 3_000 -> recoveryRequested = false
                    }
                }
            }
    }

    private fun stopVideoLivenessWatchdog() {
        videoLivenessJob?.cancel()
        videoLivenessJob = null
        lastVideoFrameReceivedMs = 0L
    }

    /**
     * Create a video processor for direct USB -> codec data flow.
     * [DIRECT_HANDOFF]: Data is already in a buffer. Feed codec directly or drop.
     *
     * Video header structure (20 bytes):
     * - offset 0: width (4 bytes)
     * - offset 4: height (4 bytes)
     * - offset 8: encoderState (4 bytes) - flags bitmask: bit 0=offScreen, bits 2-3=encoderType (raw→0=H265, 1=H264, 2=MJPEG; mapping verified against AutoKit source). Echoed in touch-payload flag word for AutoKit compat.
     * - offset 12: pts (4 bytes) - SOURCE PRESENTATION TIMESTAMP (milliseconds, logged only — codec uses elapsed-time PTS)
     * - offset 16: flags (4 bytes) - usually 0 (reserved)
     */
    private fun createVideoProcessor(): UsbDeviceWrapper.VideoDataProcessor {
        return object : UsbDeviceWrapper.VideoDataProcessor {
            override fun processVideoDirect(
                data: ByteArray,
                dataLength: Int,
                sourcePtsMs: Int,
            ): Boolean {
                if (dataLength <= 20) {
                    logDebug("[VIDEO] Ignoring malformed frame: ${dataLength}B", tag = Logger.Tags.VIDEO_USB)
                    return false
                }
                lastVideoFrameReceivedMs = System.currentTimeMillis()
                // Parse encoder state from video header for touch flags (AutoKit compatibility).
                // Offset 8: bitmask convention — bit 0 = offScreen, bits 2-3 = encoderType.
                // Confirmed against AutoKit: its video-header parser extracts `(i9 >> 2) & 3` and
                // applies the exact mapping below (0→H265, 1→H264, 2→MJPEG), then stores both
                // values in member fields and packs them into the touch payload at offset 12
                // as `encoderType | (offScreen << 16)` — identical to [MessageSerializer.serializeSingleTouch].
                // Values echoed in touch payload only — not used for codec decisions.
                if (dataLength >= 12) {
                    val flags =
                        (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8) or
                            ((data[10].toInt() and 0xFF) shl 16) or ((data[11].toInt() and 0xFF) shl 24)
                    videoOffScreen = flags and 1
                    val rawEncoder = (flags shr 2) and 3
                    videoEncoderType =
                        when (rawEncoder) {
                            0 -> 2

                            // H265
                            1 -> 1

                            // H264
                            2 -> 4

                            // MJPEG
                            else -> 2
                        }
                }

                // Infer phone type from video header if PLUGGED was missed (mid-session rejoin).
                // Video header: [0..3]=width, [4..7]=height (LE int32). AA tiers are exactly
                // 800x480, 1280x720, or 1920x1080. CarPlay uses arbitrary display dims.
                if (!videoPhoneTypeInferred && currentPhoneType == null && dataLength >= 8) {
                    videoPhoneTypeInferred = true
                    val w =
                        (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) or
                            ((data[2].toInt() and 0xFF) shl 16) or ((data[3].toInt() and 0xFF) shl 24)
                    val h =
                        (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8) or
                            ((data[6].toInt() and 0xFF) shl 16) or ((data[7].toInt() and 0xFF) shl 24)
                    val isAaTier = (w == 1920 && h == 1080) || (w == 1280 && h == 720) || (w == 800 && h == 480)
                    if (isAaTier) {
                        logInfo("[VIDEO] Inferred ANDROID_AUTO from video header ${w}x$h (mid-session)", tag = Logger.Tags.VIDEO)
                        currentPhoneType = PhoneType.ANDROID_AUTO
                        h264Renderer?.setAndroidAutoMode(true)
                        callback?.onPhoneTypeChanged(PhoneType.ANDROID_AUTO)
                    } else {
                        logInfo("[VIDEO] Inferred CARPLAY from video header ${w}x$h (mid-session)", tag = Logger.Tags.VIDEO)
                        currentPhoneType = PhoneType.CARPLAY
                        h264Renderer?.setAndroidAutoMode(false)
                        callback?.onPhoneTypeChanged(PhoneType.CARPLAY)
                        scheduleDelayedKeyframe()
                    }
                }

                // Keep USB draining and header/phone-type state current, but never queue
                // frames to an intentionally covered output surface.
                if (videoOverlayCovered) return true

                val renderer =
                    h264Renderer ?: run {
                        // Keep parsing the lightweight video header above while headless so a
                        // missed PLUGGED message does not lose phone-type/touch-mode state.
                        val now = System.currentTimeMillis()
                        if (now - lastVideoDiscardWarningTime > 2000) {
                            lastVideoDiscardWarningTime = now
                            logWarn("Video frame discarded - H264Renderer not initialized.", tag = Logger.Tags.VIDEO)
                        }
                        return true
                    }

                // Infer phone type before starting. Android Auto must resize its SurfaceView
                // first; CarPlay and unknown protocols can use the current surface directly.
                if (codecDeferred && currentPhoneType != PhoneType.ANDROID_AUTO) {
                    startCodecIfDeferred()
                }

                if (++videoFrameCount % 30 == 0L) {
                    logVideoUsb { "processVideoDirect: frame=$videoFrameCount dataLength=$dataLength, pts=$sourcePtsMs" }
                }

                // Skip 20-byte video header, feed H.264 data directly to codec
                renderer.feedDirect(data, 20, dataLength - 20)
                return true
            }
        }
    }

    private fun tryPrestageCodecCsd(btMac: String) {
        val cacheKey = "${btMac}_${config.width}x${config.height}"
        val prefs = context.getSharedPreferences("carlink_csd_cache", Context.MODE_PRIVATE)
        val spsB64 = prefs.getString("sps_$cacheKey", null) ?: return
        val ppsB64 = prefs.getString("pps_$cacheKey", null) ?: return

        val sps = android.util.Base64.decode(spsB64, android.util.Base64.NO_WRAP)
        val pps = android.util.Base64.decode(ppsB64, android.util.Base64.NO_WRAP)

        logInfo("[DEVICE] CSD cache hit for $cacheKey — pre-warming codec", tag = Logger.Tags.VIDEO)
        h264Renderer?.configureWithCsd(sps, pps)
    }

    private fun parseDevList(json: JSONObject): List<DeviceInfo> {
        val arr = json.optJSONArray("DevList") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id", "")
            if (id.isEmpty()) return@mapNotNull null
            DeviceInfo(
                btMac = id,
                name = obj.optString("name", id), // fallback to MAC if no name
                type = obj.optString("type", ""),
                lastConnected = obj.optString("time", "").ifEmpty { null },
                rfcomm = obj.optString("rfcomm", "").ifEmpty { null },
            )
        }
    }

    private fun log(message: String) {
        logDebug(message, tag = Logger.Tags.ADAPTR)
    }

    // NOTE: [dumpUnknownSummary] fires only from [stop]. Both [handleError] and
    // [rebootAdapter] silently discard the per-session unknown-message counters when they
    // reset state. If you ever need PROTO_UNKNOWN forensics from an error-recovery path
    // (e.g. debugging why a session died from an unrecognized command), add a
    // dumpUnknownSummary() call on those exit paths too — today only "clean" stops emit
    // the [SESSION_SUMMARY] log.
    private fun resetUnknownCounters() {
        unknownMessageTypeCount = 0
        unknownMediaSubtypeCount = 0
        unknownCommandCount = 0
        unknownAudioCommandCount = 0
        unknownPhoneTypeCount = 0
        unknownBoxSettingsKeyCount = 0
        unknownMessageTypes.clear()
        unknownMediaSubtypes.clear()
        unknownCommandIds.clear()
        unknownAudioCommandIds.clear()
        videoFrameCount = 0L
    }

    private fun dumpUnknownSummary() {
        val total = unknownMessageTypeCount + unknownMediaSubtypeCount +
            unknownCommandCount + unknownAudioCommandCount +
            unknownPhoneTypeCount + unknownBoxSettingsKeyCount
        if (total == 0) return

        val parts = mutableListOf<String>()
        if (unknownMessageTypeCount > 0)
            parts += "msgTypes=${unknownMessageTypeCount}x${unknownMessageTypes.map { "0x${it.toString(16)}" }}"
        if (unknownMediaSubtypeCount > 0)
            parts += "mediaSubtypes=${unknownMediaSubtypeCount}x$unknownMediaSubtypes"
        if (unknownCommandCount > 0)
            parts += "commands=${unknownCommandCount}x${unknownCommandIds.map { "0x${it.toString(16)}" }}"
        if (unknownAudioCommandCount > 0)
            parts += "audioCmds=${unknownAudioCommandCount}x$unknownAudioCommandIds"
        if (unknownPhoneTypeCount > 0)
            parts += "phoneTypes=${unknownPhoneTypeCount}x"
        if (unknownBoxSettingsKeyCount > 0)
            parts += "boxSettingsKeys=${unknownBoxSettingsKeyCount}x"
        logWarn(
            "[SESSION_SUMMARY] Unknown data received this session ($total total): ${parts.joinToString(", ")}",
            tag = Logger.Tags.PROTO_UNKNOWN,
        )
    }
}
