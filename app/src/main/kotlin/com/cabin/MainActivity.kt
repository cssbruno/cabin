package com.cabin

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cabin.background.CabinProjectionService
import com.cabin.cluster.ClusterBindingState
import com.cabin.gnss.GnssPermissionContract
import com.cabin.logging.FileLogManager
import com.cabin.logging.LogPreset
import com.cabin.logging.Logger
import com.cabin.logging.LoggingPreferences
import com.cabin.logging.apply
import com.cabin.logging.logInfo
import com.cabin.logging.logWarn
import com.cabin.media.MediaSessionManager
import com.cabin.util.LogCallback
import com.cabin.util.WindowMetricsCompat
import com.cabin.navigation.NavigationStateManager
import com.cabin.platform.TeyesAirflowMode
import com.cabin.platform.TeyesClimateController
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.LocalTeyesKeyRouter
import com.cabin.platform.ProjectionPreferences
import com.cabin.platform.TeyesAppearance
import com.cabin.platform.TeyesFeaturePreferences
import com.cabin.platform.TeyesKeyAction
import com.cabin.platform.TeyesKeyRouter
import com.cabin.protocol.AdapterConfig
import com.cabin.protocol.KnownDevices
import com.cabin.ui.MainScreen
import com.cabin.ui.ProjectionReturnDecision
import com.cabin.ui.projectionReturnDecision
import com.cabin.ui.SettingsScreen
import com.cabin.ui.settings.AdapterConfigPreference
import com.cabin.ui.settings.DisplayMode
import com.cabin.ui.settings.DisplayModePreference
import com.cabin.ui.theme.CabinTheme
import com.cabin.util.IconAssets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Main Activity — Entry Point for Cabin Native.
 *
 * The sole Activity in the app; owns every long-lived session object.
 *
 * Responsibilities:
 *  - Boot sequencing (logging → cluster component toggle → display mode → deferred
 *    CabinManager init) on the main thread.
 *  - Ownership of [CabinManager] and [FileLogManager] (nullable to survive being
 *    destroyed before init completes).
 *  - Runtime permission chain (mic → location; Android system dialogs are sequential,
 *    so the mic callback triggers the location request rather than firing in parallel).
 *  - Display-mode policy: four [DisplayMode] branches control system-bar visibility,
 *    cutout clipping, and SafeArea metadata. See [applyDisplayMode] + [initializeCabinManager].
 *  - In-place session reinit on display-mode change via [reinitializeForDisplayMode]
 *    (tier-2 restart; replaces the historical Process.killProcess approach).
 *  - USB attach (onNewIntent, via manifest intent-filter) + detach (BroadcastReceiver)
 *    handling for faster disconnect detection than USB-transfer error paths provide.
 *  - Cluster binding lifecycle: [launchCarAppActivity] starts the Templates Host →
 *    cluster chain, [restartClusterBinding] tears it down and re-establishes.
 *  - Compose UI host: the top-level [CabinApp] composable keeps [MainScreen]
 *    continuously composed (to preserve the VideoSurface / HWC plane) and slides
 *    [SettingsScreen] on top via AnimatedVisibility rather than replacing it.
 */
class MainActivity : ComponentActivity() {
    companion object {
        private val activityOwner = ActivityInstanceOwner<MainActivity>()
        const val ACTION_SHOW_COMPACT_PROJECTION = "com.carlink.action.SHOW_COMPACT_PROJECTION"
        const val ACTION_SHOW_FULLSCREEN_PROJECTION = "com.carlink.action.SHOW_FULLSCREEN_PROJECTION"
    }

    // Nullable to prevent UninitializedPropertyAccessException if Activity
    // is destroyed before initialization completes (e.g., low memory kill)
    private var redirectedDuplicate = false
    private var cabinManager: CabinManager? = null
    private val homeNavigationRequest = mutableStateOf(0L)
    private var fileLogManager: FileLogManager? = null

    /**
     * App-scope MediaSession reference. Owned and lifecycle-managed by
     * [CabinMediaBrowserService] — this Activity only reads the live singleton via
     * [MediaSessionManager.instance] / [MediaSessionManager.getOrCreate] to pass into
     * [CabinManager]. Never released from here; the MBS releases on its own
     * `onDestroy`. Survival across tier-2 CabinManager rebuilds is now a property of
     * the Service lifecycle (Service stays alive across Activity destruction), not a
     * property of this field.
     *
     * Boot-race fix (2026-05-03): previously this Activity created the manager, which
     * meant the session didn't exist until the user tapped the launcher icon — AAOS
     * auto-launches the MBS at boot and probes onGetSession within ~50ms, so the
     * Activity-scoped initialize never won the race. Ownership moved to MBS.onCreate.
     */
    private var mediaSessionManager: MediaSessionManager? = null

    /**
     * LogCallback routed to the app's [com.cabin.logging.Logger] + any active file log.
     * Passed to [MediaSessionManager] so its lifecycle events surface through the same
     * infrastructure that [CabinManager] uses. Stable singleton — one instance per
     * Activity lifetime, safe to share across rebuilds.
     */
    private val mediaSessionLogCallback =
        object : LogCallback {
            override fun log(message: String) {
                logInfo(message, tag = "MEDIA_SESSION")
            }

            override fun log(tag: String, message: String) {
                logInfo(message, tag = tag)
            }
        }
    // Main-thread-only invariant: written in reinitializeForDisplayMode() and read from
    // initializeCabinManager()/onResume(). Both sides dispatch on the main thread
    // (decorView.post, postDelayed, lifecycle callbacks), so no @Volatile needed.
    private var currentDisplayMode: DisplayMode = DisplayMode.SYSTEM_UI_VISIBLE

    // Observable state for Compose — replacement triggers recomposition of CabinApp
    private val cabinManagerState = mutableStateOf<CabinManager?>(null)
    private val displayModeState = mutableStateOf(DisplayMode.SYSTEM_UI_VISIBLE)
    private val compactPanelState = mutableStateOf(false)
    private val climateOverlayVisibleState = mutableStateOf(false)
    private val climateSummaryVisibleState = mutableStateOf(false)
    private val windowFocusedState = mutableStateOf(false)
    private var compactPanelMode = false
    private var teyesClimateController: TeyesClimateController? = null

    // Pending reinit handler — tracked for cancellation on rapid display mode changes
    private var pendingReinitJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var systemBarRecovery: com.cabin.ui.SystemBarRecovery? = null
    private val hideClimateOverlay = Runnable {
        climateOverlayVisibleState.value = false
        climateSummaryVisibleState.value = false
    }

    // Permission launchers — chained: mic callback triggers location request
    private val locationPermissionLauncher =
        registerForActivityResult(
            GnssPermissionContract(),
        ) { isGranted ->
            logInfo("Precise location permission ${if (isGranted) "granted" else "unavailable"}", tag = "MAIN")
            if (isGranted) refreshProjectionForegroundCapabilities()
        }

    // Android permission dialogs are sequential, not overlappable — launching both
    // in parallel would drop the second. The mic callback triggers location, and
    // both denials are survivable: CabinManager still constructs; mic features
    // (Siri, AA voice, phone call capture) fail silently, and GPS forwarding
    // (AdapterConfig.gpsForwarding) is a no-op without ACCESS_FINE_LOCATION.
    private val micPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            logInfo("Microphone permission ${if (isGranted) "granted" else "denied"}", tag = "MAIN")
            if (isGranted) refreshProjectionForegroundCapabilities()
            // Chain: request location after mic dialog completes
            requestLocationPermission()
        }

    /**
     * BroadcastReceiver for USB device detachment events.
     *
     * Provides immediate detection when the Carlinkit adapter is physically
     * disconnected, enabling faster recovery than waiting for USB transfer errors
     * to surface. Filters to known Carlinkit VID/PID pairs before signaling
     * [CabinManager.onUsbDeviceDetached] — other USB device events are ignored.
     */
    private val usbDetachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                    val device =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                    device?.let {
                        // Only handle if it's a known Carlinkit device
                        if (KnownDevices.isKnownDevice(it.vendorId, it.productId) &&
                            cabinManager?.ownsUsbDevice(it) == true
                        ) {
                            logWarn(
                                "[USB_DETACH] Carlinkit device detached: VID=0x${it.vendorId.toString(16)} " +
                                    "PID=0x${it.productId.toString(16)} path=${it.deviceName}",
                                tag = "MAIN",
                            )
                            // Notify CabinManager of the detachment (null-safe)
                            cabinManager?.onUsbDeviceDetached()
                        }
                    }
                }
            }
        }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.cabin.localization.AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityOwner.claim(this)?.let { existing ->
            // Some head units launch HOME aliases in a separate task despite singleTask.
            // Forward the request before creating another manager or video surface.
            redirectedDuplicate = true
            existing.onNewIntent(Intent(intent))
            try {
                getSystemService(android.app.ActivityManager::class.java).appTasks
                    .firstOrNull { it.taskInfo.taskId == existing.taskId }?.moveToFront()
            } catch (e: RuntimeException) {
                logWarn("Could not bring existing projection task forward: ${e.message}", tag = "MAIN")
            }
            finish()
            return
        }
        val homeLaunch = BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE &&
            intent?.action != ACTION_SHOW_COMPACT_PROJECTION && intent?.action != ACTION_SHOW_FULLSCREEN_PROJECTION
        if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) homeNavigationRequest.value = if (homeLaunch) 1L else -1L
        compactPanelMode = !homeLaunch && (intent?.action == ACTION_SHOW_COMPACT_PROJECTION ||
            (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE && intent?.action == Intent.ACTION_MAIN &&
                TeyesFeaturePreferences.get(this).profile.value.compactOnLaunch))
        compactPanelState.value = compactPanelMode
        com.cabin.updates.UpdateJobService.schedule(this)

        // Enable edge-to-edge display
        enableEdgeToEdge()
        systemBarRecovery = com.cabin.ui.SystemBarRecovery(window.decorView,
            canRecover = { !isDestroyed && !isFinishing && !compactPanelMode && hasWindowFocus() &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
            hide = { types ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(types)
                }
            })

        // Keep screen on during projection
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize logging
        initializeLogging()

        // Conventional Android head units such as TEYES TPRO do not ship the AAOS
        // Templates Host. Keep the GM cluster integration dormant on those devices.
        if (supportsClusterNavigation()) {
            AdapterConfigPreference.getInstance(this).applyClusterComponentState(this)
        } else {
            logInfo("[PLATFORM] AAOS Templates Host unavailable; cluster integration disabled", tag = "MAIN")
        }

        // Load display mode preference and apply BEFORE calculating display dimensions
        // This ensures correct viewport sizing - fullscreen immersive uses full screen (1920x1080),
        // other modes use usable area excluding visible system bars
        if (compactPanelMode) {
            currentDisplayMode = DisplayMode.SYSTEM_UI_VISIBLE
            displayModeState.value = currentDisplayMode
            configureCompactProjectionWindow()
        } else {
            loadAndApplyDisplayMode()
        }

        // Defer CabinManager creation to the next main-looper tick so the decorView is
        // attached before initializeCabinManager() reads insets. WindowMetricsCompat
        // .stableWindowInsets requires an attached decorView; otherwise it returns CONSUMED
        // (all-zero) insets and the resolution mis-computes.
        // NOTE: currentWindowMetrics.bounds is the FULL display (2400x960) regardless of
        // decorFitsSystemWindows — verified via dumpsys (mBounds/mAppBounds = 2400x960 in
        // SYSTEM_UI_VISIBLE). The 788 usable height comes from subtracting the system-bar
        // insets exactly ONCE in initializeCabinManager()'s when-branch, not from a clipped
        // bounds. (Prior comment claimed bounds reflected a post-clip 2400x788 rect — false;
        // that would double-count with the inset subtraction and yield 616.)
        // Compose renders `if (manager != null)` while we wait, so the UI boots with
        // the loading overlay and swaps in as soon as the manager is ready.
        window.decorView.post {
            if (!isDestroyed && !isFinishing) {
                initializeCabinManager()
            }
        }

        // Request permissions if needed (location is chained after mic dialog)
        if (!homeLaunch) requestMicrophonePermission()

        // Register USB detachment receiver for immediate disconnect detection
        registerUsbDetachReceiver()
        initializeTeyesClimateController()

        // Launch CarAppActivity to trigger Templates Host → cluster binding chain.
        // Skipped entirely when cluster navigation is disabled — no reason to start
        // the CarAppActivity → RendererService → CabinClusterService chain.
        if (supportsClusterNavigation() && AdapterConfigPreference.getInstance(this).getClusterNavigationSync()) {
            // Delayed to avoid interrupting USB permission dialog on first connect.
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isDestroyed && !isFinishing) {
                    launchCarAppActivity()
                }
            }, 4000)
        }

        // Set up Compose UI
        // CabinManager is observed via mutableStateOf — replacement during display mode
        // reinit triggers full recomposition without Activity restart.
        setContent {
            val manager = cabinManagerState.value
            val displayMode = displayModeState.value
            val compactPanel = compactPanelState.value
            val climateOverlayVisible = climateOverlayVisibleState.value
            val climateController = teyesClimateController
            val climateState =
                climateController?.state?.collectAsState()?.value ?: TeyesClimateState()
            val teyesProfile by TeyesFeaturePreferences.get(this).profile.collectAsState()
            val systemDark = com.cabin.platform.rememberCarAutomation(climateState, isSystemInDarkTheme())
            val dark = if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) when (teyesProfile.appearance) {
                TeyesAppearance.SYSTEM -> systemDark
                TeyesAppearance.DAY -> false
                TeyesAppearance.NIGHT -> true
            } else systemDark
            LaunchedEffect(teyesProfile.nightBrightness, dark) {
                if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) {
                    window.attributes = window.attributes.apply {
                        screenBrightness = if (dark) teyesProfile.nightBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            }
            LaunchedEffect(manager, teyesProfile.mediaGain, teyesProfile.navigationGain) {
                manager?.applyTeyesAudioProfile()
            }
            LaunchedEffect(manager, teyesProfile.appearance, dark) {
                manager?.syncTeyesAppearance(systemDark)
            }
            LaunchedEffect(manager, teyesProfile.recoverOverlays) {
                if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) {
                    if (!teyesProfile.recoverOverlays && teyesOverlayPaused) {
                        mainHandler.removeCallbacks(pauseTeyesOverlay)
                        teyesOverlayPaused = false
                        manager?.resumeVideo()
                    }
                }
            }
            CompositionLocalProvider(LocalTeyesKeyRouter provides if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) teyesKeyRouter else null) {
                CabinTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        if (manager != null) {
                            CabinApp(
                                cabinManager = manager,
                                fileLogManager = fileLogManager,
                                displayMode = displayMode,
                                compactPanel = compactPanel,
                                homeRequest = homeNavigationRequest.value,
                                onOpenLauncherWindow = ::openLauncherFullscreen,
                                onExpandPanel = { setCompactPanelMode(false) },
                                onClosePanel = { finish() },
                                onOpenClimate = if (climateController != null) ::toggleTeyesClimate else null,
                                climateOverlayVisible = climateOverlayVisible,
                                climateSummaryVisible = climateSummaryVisibleState.value,
                                windowFocused = windowFocusedState.value,
                                climateState = climateState,
                                onSetClimateAc = climateController?.let { controller -> controller::setAc },
                                onSetClimateFan = climateController?.let { controller -> controller::setFan },
                                onAdjustClimateTemperature = climateController?.let { controller -> controller::adjustTemperature },
                                onToggleClimateSwitch = climateController?.let { controller -> controller::toggleClimate },
                                onAirAction = climateController?.let { controller -> controller::sendAirAction },
                                onVehicleLighting = climateController?.let { controller -> controller::setVehicleLighting },
                                onFactoryAmplifier = climateController?.let { controller -> controller::setFactoryAmplifier },
                                onFactoryControl = climateController?.let { controller -> controller::setFactoryControl },
                                onSetClimateAirflow = climateController?.let { controller -> controller::setAirflow },
                                onResetCluster = ::restartClusterBinding,
                                onRetryVehicle = { teyesClimateController?.retryConnection() },
                                onRefreshClimate = { teyesClimateController?.retryConnection() },
                                drivingGuard = teyesDrivingGuard,
                                onReinitForDisplayMode = ::reinitializeForDisplayMode,
                                // Rebuild the full manager and Surface after a display-mode reset.
                                onResetConnection = { reinitializeForDisplayMode(currentDisplayMode) },
                            )
                        }
                    }
                }
            }
        }

        if (compactPanelMode) {
            // Re-apply after the decor is attached. Some launchers replace the initial
            // LayoutParams while starting an Activity from an AppWidget PendingIntent.
            window.decorView.post {
                if (!isDestroyed && !isFinishing && compactPanelMode) {
                    configureCompactProjectionWindow()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (redirectedDuplicate) return
        // Permission results can arrive before the Activity becomes visible again.
        // Refresh here too so a first-launch grant upgrades the existing service.
        refreshProjectionForegroundCapabilities()
        if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) {
            volumeControlStream = android.media.AudioManager.STREAM_MUSIC
            cabinManager?.syncTeyesAppearance()
        }
        // Restore display mode when returning to app
        // System may have shown bars while app was in background
        if (compactPanelMode) {
            configureCompactProjectionWindow()
        } else {
            applyDisplayMode(currentDisplayMode)
        }
    }

    override fun onStart() {
        super.onStart()
        if (redirectedDuplicate) return
        // Resume video decoding when app returns to foreground
        // On AAOS, Surface may remain valid while app is in background, but
        // BufferQueue can stall. Resume codec and request keyframe for immediate video.
        teyesClimateController?.resumeUpdates()
        logInfo("[LIFECYCLE] onStart - resuming video", tag = "MAIN")
        cabinManager?.resumeVideo()
    }

    private fun refreshProjectionForegroundCapabilities() {
        // While-in-use service types must be added while the Activity is visible.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            CabinProjectionService.refreshForegroundCapabilitiesFromVisibleActivity()
        }
    }

    override fun onStop() {
        super.onStop()
        if (redirectedDuplicate) return
        teyesClimateController?.suspendUpdates()
        systemBarRecovery?.cancel()
        mainHandler.removeCallbacks(hideClimateOverlay)
        hideClimateOverlay.run()
        mainHandler.removeCallbacks(pauseTeyesOverlay)
        teyesOverlayPaused = false
        // Pause video decoding when app goes to background
        // On AAOS, when another app covers this app (Maps, Phone, etc.), the Surface
        // may remain valid but SurfaceFlinger stops consuming frames. This causes
        // BufferQueue to fill up, stalling the decoder. Flushing prevents this.
        // USB connection and audio continue unaffected.
        logInfo("[LIFECYCLE] onStop - pausing video", tag = "MAIN")
        cabinManager?.pauseVideo()
    }

    private val teyesKeyRouter by lazy {
        TeyesKeyRouter(TeyesFeaturePreferences.get(this), launchApp = { component ->
            com.cabin.platform.TeyesAppShortcuts.launch(this, component)
        }) { action ->
            if (action == TeyesKeyAction.BACK) {
                onBackPressedDispatcher.onBackPressed()
            } else if (action == TeyesKeyAction.NONE) {
                Unit
            } else if (action == TeyesKeyAction.LAUNCHER) {
                openLauncherFullscreen()
            } else if (action == TeyesKeyAction.CLIMATE) {
                if (teyesClimateController != null) toggleTeyesClimate()
            } else if (action in setOf(TeyesKeyAction.VOLUME_UP, TeyesKeyAction.VOLUME_DOWN, TeyesKeyAction.MUTE)) {
                val audio = getSystemService(android.media.AudioManager::class.java)
                val direction = when (action) {
                    TeyesKeyAction.VOLUME_UP -> android.media.AudioManager.ADJUST_RAISE
                    TeyesKeyAction.VOLUME_DOWN -> android.media.AudioManager.ADJUST_LOWER
                    else -> android.media.AudioManager.ADJUST_TOGGLE_MUTE
                }
                audio?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, android.media.AudioManager.FLAG_SHOW_UI)
            } else cabinManager?.performTeyesKey(action)
        }
    }
    private var teyesOverlayPaused = false
    private val teyesDrivingGuard = com.cabin.platform.TeyesDrivingGuard()
    private val pauseTeyesOverlay = Runnable {
        if (!hasWindowFocus() && !isFinishing && !isDestroyed &&
            TeyesFeaturePreferences.get(this).profile.value.recoverOverlays) {
            teyesOverlayPaused = true
            cabinManager?.pauseVideo()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE && teyesKeyRouter.dispatch(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE && teyesKeyRouter.dispatch(event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) teyesKeyRouter.cancelPressedKeys()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (redirectedDuplicate) return
        windowFocusedState.value = hasFocus
        if (hasFocus && !compactPanelMode) applyDisplayMode(currentDisplayMode)
        else systemBarRecovery?.cancel()
        if (!BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) return
        mainHandler.removeCallbacks(pauseTeyesOverlay)
        if (hasFocus) {
            if (teyesOverlayPaused) {
                teyesOverlayPaused = false
                cabinManager?.resumeVideo()
            }
        } else {
            teyesKeyRouter.cancelPressedKeys()
            if (TeyesFeaturePreferences.get(this).profile.value.recoverOverlays) {
                mainHandler.postDelayed(pauseTeyesOverlay, 200)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE && com.cabin.launcher.LauncherIntents.opensDashboard(intent)) {
            setCompactPanelMode(false)
            homeNavigationRequest.value = kotlin.math.abs(homeNavigationRequest.value) + 1L
            loadAndApplyDisplayMode()
            return
        }
        if (intent.action == ACTION_SHOW_COMPACT_PROJECTION || intent.action == ACTION_SHOW_FULLSCREEN_PROJECTION) {
            homeNavigationRequest.value = -kotlin.math.abs(homeNavigationRequest.value) - 1L
        }
        when (intent.action) {
            ACTION_SHOW_COMPACT_PROJECTION -> {
                setCompactPanelMode(true)
                return
            }
            ACTION_SHOW_FULLSCREEN_PROJECTION -> {
                setCompactPanelMode(false)
                return
            }
        }
        // Only re-launch cluster binding for actual USB re-attach events.
        // The "bring back" REORDER_TO_FRONT intent from launchCarAppActivity()
        // also arrives here (singleTop) — must NOT re-trigger the launch cycle.
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            // Only launch cluster binding if cluster navigation is enabled
            if (supportsClusterNavigation() && AdapterConfigPreference.getInstance(this).getClusterNavigationSync()) {
                logInfo("[LIFECYCLE] onNewIntent: USB_DEVICE_ATTACHED — re-launching cluster binding", tag = "MAIN")
                launchCarAppActivity()
            }

            // Auto-connect when adapter re-enumerates (e.g., after reboot or replug)
            val manager = cabinManager
            if (manager != null && manager.state == CabinManager.State.DISCONNECTED &&
                (homeNavigationRequest.value <= 0 || manager.projectionSessionRequested)) {
                logInfo("[LIFECYCLE] Manager disconnected — auto-starting connection", tag = "MAIN")
                CoroutineScope(Dispatchers.IO).launch {
                    manager.start()
                }
            }
        }
    }

    /** Resize the Activity over the launcher while keeping the same Surface/USB owner. */
    private fun setCompactPanelMode(enabled: Boolean) {
        if (compactPanelMode == enabled) return
        compactPanelMode = enabled
        compactPanelState.value = enabled
        if (enabled) {
            currentDisplayMode = DisplayMode.SYSTEM_UI_VISIBLE
            displayModeState.value = currentDisplayMode
            CabinProjectionService.start(applicationContext)
            configureCompactProjectionWindow()
        } else {
            val attributes = window.attributes
            attributes.width = WindowManager.LayoutParams.MATCH_PARENT
            attributes.height = WindowManager.LayoutParams.MATCH_PARENT
            attributes.gravity = Gravity.FILL
            attributes.dimAmount = 0f
            window.attributes = attributes
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            loadAndApplyDisplayMode()
        }
    }

    /** Keeps the Activity window policy in sync when Home is opened in-place. */
    private fun openLauncherFullscreen() {
        homeNavigationRequest.value = kotlin.math.abs(homeNavigationRequest.value) + 1L
        if (compactPanelMode) setCompactPanelMode(false) else loadAndApplyDisplayMode()
    }

    private fun configureCompactProjectionWindow() {
        systemBarRecovery?.update(DisplayMode.SYSTEM_UI_VISIBLE)
        val bounds = WindowMetricsCompat.displayBounds(windowManager)
        val marginPx = (24 * resources.displayMetrics.density).toInt()
        val width = ((bounds.width() * 0.82f).toInt() and 1.inv()).coerceAtMost(bounds.width() - marginPx * 2)
        val height = ((bounds.height() * 0.82f).toInt() and 1.inv()).coerceAtMost(bounds.height() - marginPx * 2)
        val attributes = window.attributes
        attributes.width = width.coerceAtLeast(2)
        attributes.height = height.coerceAtLeast(2)
        attributes.gravity = Gravity.CENTER
        attributes.dimAmount = 0.18f
        window.attributes = attributes
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun toggleTeyesClimate() {
        if (climateOverlayVisibleState.value) {
            mainHandler.removeCallbacks(hideClimateOverlay)
            climateOverlayVisibleState.value = false
        } else {
            showTeyesClimateOverlay()
        }
    }

    private fun showTeyesClimateOverlay() {
        mainHandler.post {
            if (isDestroyed || isFinishing) return@post
            if (compactPanelMode) setCompactPanelMode(false)
            climateSummaryVisibleState.value = false
            climateOverlayVisibleState.value = true
            mainHandler.removeCallbacks(hideClimateOverlay)
            if (teyesClimateController?.state?.value?.health != com.cabin.platform.TeyesTelemetryHealth.LIVE) {
                teyesClimateController?.retryConnection()
            }
        }
    }

    private fun initializeTeyesClimateController() {
        if (!BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) return
        // Feedback updates widgets in place; climate navigation is always explicit.
        val controller = TeyesClimateController(this) {}
        if (controller.start()) {
            teyesClimateController = controller
            logInfo("[TEYES] Started asynchronous vehicle service connection", tag = "MAIN")
        } else {
            controller.close()
            logInfo("[TEYES] com.syu.ms climate service unavailable", tag = "MAIN")
        }
    }

    private fun supportsClusterNavigation(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
            packageManager.hasSystemFeature("android.software.car.templates_host")

    override fun onDestroy() {
        super.onDestroy()
        if (redirectedDuplicate) return
        activityOwner.release(this)
        systemBarRecovery?.close()
        systemBarRecovery = null

        mainHandler.removeCallbacks(hideClimateOverlay)
        mainHandler.removeCallbacks(pauseTeyesOverlay)
        teyesClimateController?.close()
        teyesClimateController = null

        // Unregister USB detachment receiver
        unregisterUsbDetachReceiver()

        // Release the CabinManager (it detaches its transport callback from the
        // MediaSession but does NOT release it — see CabinManager.release KDoc).
        // The MediaSession itself is owned by CabinMediaBrowserService; it releases
        // on its own onDestroy. Just drop the local reference here.
        val manager = cabinManager
        if (manager != null) {
            val returnedToService = CabinProjectionService.returnRunningManager(manager)
            CabinProjectionService.unregisterActivityManager(manager)
            if (!returnedToService) manager.release()
        }
        cabinManager = null
        mediaSessionManager = null
        fileLogManager?.release()

        logInfo("MainActivity destroyed", tag = "MAIN")
    }

    private fun initializeLogging() {
        // Initialize file logging
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val appVersion = "${packageInfo.versionName}+${PackageInfoCompat.getLongVersionCode(packageInfo)}"

        fileLogManager =
            FileLogManager(
                context = this,
                sessionPrefix = "cabin",
                appVersion = appVersion,
            )

        // Configure debug-only logging based on build type
        // In release builds, verbose pipeline logging is disabled for performance
        // Users can re-enable via Pipeline Debug preset in settings
        val isDebugBuild = BuildConfig.DEBUG
        Logger.setDebugLoggingEnabled(isDebugBuild)

        // Apply default log preset based on build type
        // Release: SILENT (errors only) - user can override via settings
        // Debug: NORMAL (standard logging)
        if (!isDebugBuild) {
            LogPreset.SILENT.apply()
        }

        // Auto-restore user's log preset and file logging from previous session.
        // Must restore preset BEFORE enabling file logging — otherwise SILENT
        // filters everything and the log file stays header-only.
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = LoggingPreferences.getInstance(this@MainActivity)
            val preset = prefs.logLevelFlow.first()
            preset.apply()
            val enabled = prefs.loggingEnabledFlow.first()
            if (enabled) {
                fileLogManager?.enable()
            }
        }

        logInfo("Cabin Native starting - version $appVersion", tag = "MAIN")
        logInfo("[LOGGING] Debug logging: ${if (isDebugBuild) "ENABLED" else "DISABLED (release build)"}", tag = "MAIN")
    }

    private fun initializeCabinManager() {
        // Get window metrics to determine USABLE area (excluding system UI).
        // WindowMetricsCompat falls back to Display.getRealMetrics + WindowInsetsCompat
        // on API 29 (AAOS 10); the API 30+ path is unchanged.
        val bounds = WindowMetricsCompat.displayBounds(windowManager)
        val windowInsets = WindowMetricsCompat.stableWindowInsets(windowManager, window.decorView)

        // Separate inset sources for per-mode SafeArea computation
        val systemBarInsets =
            windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        val cutoutInsets =
            windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())

        // Compute video resolution and SafeArea insets per display mode
        val videoWidth: Int
        val videoHeight: Int
        val safeInsetTop: Int
        val safeInsetBottom: Int
        val safeInsetLeft: Int
        val safeInsetRight: Int

        when (com.cabin.launcher.launcherDisplayMode(currentDisplayMode, homeNavigationRequest.value > 0, compactPanelMode)) {
            DisplayMode.SYSTEM_UI_VISIBLE -> {
                // System bars + cutouts both reduce video area. No SafeArea needed.
                videoWidth = bounds.width() - systemBarInsets.left - systemBarInsets.right -
                    cutoutInsets.left - cutoutInsets.right
                videoHeight = bounds.height() - systemBarInsets.top - systemBarInsets.bottom -
                    cutoutInsets.top - cutoutInsets.bottom
                safeInsetTop = 0
                safeInsetBottom = 0
                safeInsetLeft = 0
                safeInsetRight = 0
            }

            DisplayMode.STATUS_BAR_HIDDEN -> {
                // Nav bar visible (subtract from video), status bar hidden (cutout exposed top/sides)
                videoWidth = bounds.width() - systemBarInsets.left - systemBarInsets.right
                videoHeight = bounds.height() - systemBarInsets.bottom
                safeInsetTop = cutoutInsets.top
                safeInsetBottom = 0 // nav bar covers bottom
                safeInsetLeft = cutoutInsets.left
                safeInsetRight = cutoutInsets.right
            }

            DisplayMode.NAV_BAR_HIDDEN -> {
                // Status bar visible (subtract from video), nav bar hidden (cutout exposed bottom/sides)
                videoWidth = bounds.width()
                videoHeight = bounds.height() - systemBarInsets.top
                safeInsetTop = 0 // status bar covers top
                safeInsetBottom = cutoutInsets.bottom
                safeInsetLeft = cutoutInsets.left
                safeInsetRight = cutoutInsets.right
            }

            DisplayMode.FULLSCREEN_IMMERSIVE -> {
                // Full screen, all cutout areas exposed
                videoWidth = bounds.width()
                videoHeight = bounds.height()
                safeInsetTop = cutoutInsets.top
                safeInsetBottom = cutoutInsets.bottom
                safeInsetLeft = cutoutInsets.left
                safeInsetRight = cutoutInsets.right
            }
        }

        // Get DPI from display metrics
        val displayMetrics = resources.displayMetrics
        val dpi = displayMetrics.densityDpi

        // Round to even numbers for H.264 compatibility
        val evenWidth = videoWidth and 1.inv()
        val evenHeight = videoHeight and 1.inv()

        // Load icons from assets for adapter initialization
        val (icon120, icon180, icon256) = IconAssets.loadIcons(this)
        val iconsLoaded = icon120 != null && icon180 != null && icon256 != null

        // Load bundled aa_gps_fix.sh — pushed to adapter /tmp on every init (full + minimal).
        // Inline single-asset load; no dedicated util warranted for one file.
        val gpsFixScriptBytes =
            try {
                this.assets.open("aa_gps_fix.sh").use { it.readBytes() }
            } catch (e: java.io.IOException) {
                logWarn("[GPS_FIX] Failed to load aa_gps_fix.sh asset: ${e.message}")
                null
            }

        // Load bundled patched ARMiPhoneIAP2 — pushed to adapter /tmp/bin on every init to
        // preempt phone_link_deamon's first-spawn factory copy. 233 KiB, single SendFile.
        val patchedIap2BinaryBytes =
            try {
                this.assets.open("ARMiPhoneIAP2.patched").use { it.readBytes() }
            } catch (e: java.io.IOException) {
                logWarn("[IAP2_PATCH] Failed to load ARMiPhoneIAP2.patched asset: ${e.message}")
                null
            }

        // Platform-specific overrides. PlatformDetector.detect is cheap (Build props + one
        // MediaCodecList scan); CabinManager.initialize re-detects but that's idempotent.
        // gminfo37 (and the AAOS emulator for development parity): hide the CarPlay OEM
        // "Exit" icon — GM AAOS has its own back-nav; the OEM tile collides visually.
        // Other platforms keep the OEM icon visible (existing factory behavior). Wired into
        // /etc/airplay.conf via AdapterConfig.oemIconVisible which generateAirplayConfig now
        // honors.
        val platformInfo = com.cabin.platform.PlatformDetector.detect(this)
        val oemIconVisibleForPlatform = !platformInfo.requiresImmersiveDefaults()

        // Load user-configured adapter settings from sync cache (instant, no I/O blocking)
        // These are optional - only configured settings are sent to the adapter
        val userConfig = AdapterConfigPreference.getInstance(this).getUserConfigSync()

        // Apply video resolution preference (must be before ViewArea/SafeArea construction)
        // AUTO = use detected usable dimensions, otherwise use user-selected resolution
        val userSelectedResolution = !userConfig.videoResolution.isAuto
        val (configWidth, configHeight) =
            if (userConfig.videoResolution.isAuto) {
                Pair(evenWidth, evenHeight)
            } else {
                // User selected a specific resolution - use it for adapter config
                // Note: Surface size remains the actual display size for touch normalization
                Pair(userConfig.videoResolution.width, userConfig.videoResolution.height)
            }

        // Build binary ViewArea/SafeArea data using the configured resolution.
        // ViewArea/SafeArea must match OPEN message dimensions (safeArea ⊆ viewArea ⊆ display).
        val viewAreaData = buildViewAreaData(configWidth, configHeight)
        val safeAreaData =
            if (userSelectedResolution) {
                // Scale cutout insets from display coordinates to custom resolution coordinates.
                // Linear scaling assumes cutouts are rectangular window-edge margins (true on
                // AAOS displays we've seen). Non-uniform X/Y scaling is OK here because cutout
                // insets are axis-aligned.
                val scaleX = configWidth.toFloat() / evenWidth.toFloat()
                val scaleY = configHeight.toFloat() / evenHeight.toFloat()
                buildSafeAreaData(
                    configWidth,
                    configHeight,
                    (safeInsetTop * scaleY).toInt(),
                    (safeInsetBottom * scaleY).toInt(),
                    (safeInsetLeft * scaleX).toInt(),
                    (safeInsetRight * scaleX).toInt(),
                )
            } else {
                buildSafeAreaData(configWidth, configHeight, safeInsetTop, safeInsetBottom, safeInsetLeft, safeInsetRight)
            }

        // Map user config enums to AdapterConfig values
        val micType =
            when (userConfig.micSource) {
                com.cabin.ui.settings.MicSourceConfig.APP -> "os"
                com.cabin.ui.settings.MicSourceConfig.PHONE -> "box"
            }
        val wifiType =
            when (userConfig.wifiBand) {
                com.cabin.ui.settings.WiFiBandConfig.BAND_5GHZ -> "5ghz"
                com.cabin.ui.settings.WiFiBandConfig.BAND_24GHZ -> "24ghz"
            }

        val config =
            AdapterConfig(
                width = configWidth,
                height = configHeight,
                fps = userConfig.fps.fps,
                dpi = dpi,
                // Mark if user explicitly selected a resolution (non-AUTO)
                userSelectedResolution = userSelectedResolution,
                icon120Data = icon120,
                icon180Data = icon180,
                icon256Data = icon256,
                gpsFixScriptData = gpsFixScriptBytes,
                patchedIap2BinaryData = patchedIap2BinaryBytes,
                oemIconVisible = oemIconVisibleForPlatform,
                // User-configured audio transfer mode (false=adapter, true=bluetooth)
                audioTransferMode = userConfig.audioTransferMode,
                // Hardcoded to 48kHz - professional quality audio for GM AAOS
                sampleRate = 48000,
                // User-configured mic, wifi, call quality, and media delay
                micType = micType,
                wifiType = wifiType,
                callQuality = userConfig.callQuality.value,
                mediaDelay = userConfig.mediaDelay.delayMs,
                viewAreaData = viewAreaData,
                safeAreaData = safeAreaData,
                gpsForwarding = userConfig.gpsForwarding,
            )
        CabinProjectionService.rememberDisplayConfig(applicationContext, config)

        logInfo(
            "[WINDOW] Bounds: ${bounds.width()}x${bounds.height()}, " +
                "Video: ${evenWidth}x$evenHeight, " +
                "Cutout: T:${cutoutInsets.top} B:${cutoutInsets.bottom} " +
                "L:${cutoutInsets.left} R:${cutoutInsets.right}, " +
                "DisplayMode: ${currentDisplayMode.name}",
            tag = "MAIN",
        )
        logInfo("Display config: ${config.width}x${config.height}@${config.fps}fps, ${config.dpi}dpi", tag = "MAIN")
        logInfo(
            "Icons loaded: $iconsLoaded " +
                "(120: ${icon120?.size ?: 0}B, 180: ${icon180?.size ?: 0}B, " +
                "256: ${icon256?.size ?: 0}B)",
            tag = "MAIN",
        )
        logInfo(
            "[ADAPTER_CONFIG] User config: " +
                "audioTransferMode=${if (userConfig.audioTransferMode) "bluetooth" else "adapter"}, " +
                "sampleRate=48000Hz (hardcoded), mic=$micType, wifi=$wifiType, " +
                "callQuality=${userConfig.callQuality.name}, " +
                "mediaDelay=${userConfig.mediaDelay.name}(${userConfig.mediaDelay.delayMs}ms), " +
                "resolution=${userConfig.videoResolution.toStorageString()} (adapter: ${configWidth}x$configHeight)",
            tag = "MAIN",
        )

        applyAudioTransferModeToMediaSession(config.audioTransferMode)

        // A widget-started foreground service already owns the USB device. Share that
        // exact manager and attach the real SurfaceView when Compose renders, avoiding
        // a disconnect/reconnect cycle and two managers racing for the adapter. The
        // service remains owner and reclaims headless rendering in onDestroy().
        val serviceManager = CabinProjectionService.takeRunningManager()
        cabinManager =
            serviceManager
                ?.also {
                    logInfo("[BACKGROUND] Attached to service-owned projection session", tag = "MAIN")
                }
                ?: CabinManager(applicationContext, config, mediaSessionManager)
        cabinManager?.let(CabinProjectionService::registerActivityManager)
        // Publish the fully configured Activity manager before starting the durable
        // owner. This prevents the service from racing ahead with guessed display or
        // permission settings on a cold app launch. Its onStartCommand adopts this
        // exact manager, so Activity destruction can return it to headless mode.
        // Merely becoming the default Home must not revive an explicitly stopped session.
        if (homeNavigationRequest.value <= 0) CabinProjectionService.start(applicationContext)
        cabinManagerState.value = cabinManager
        displayModeState.value = currentDisplayMode
    }

    /**
     * Map the new config's `audioTransferMode` onto the app-scope [MediaSessionManager]
     * singleton (owned by [CabinMediaBrowserService]).
     *
     * - ADAPTER mode: take a reference to the singleton so [CabinManager] can publish
     *   metadata. The MBS already initialized it at boot; we never call `initialize()`
     *   from the Activity anymore.
     * - BLUETOOTH mode: drop our local reference and flip the session to placeholder
     *   via [MediaSessionManager.setInactive]. Crucially we do NOT call `release()` —
     *   destroying the session token here would re-trigger the boot-race-equivalent
     *   stale-card bug if the user toggles back to ADAPTER.
     *
     * Defensive bootstrap: if the singleton somehow isn't present yet (MBS not started
     * for any reason), we call [MediaSessionManager.getOrCreate] + `initialize()` to
     * cover the gap. In normal flow this branch is never taken.
     *
     * @param audioTransferMode `false` = ADAPTER (audio routed via USB, media session
     *   actively published), `true` = BLUETOOTH (audio via phone BT, session stays
     *   registered but inactive).
     */
    private fun applyAudioTransferModeToMediaSession(audioTransferMode: Boolean) {
        if (!audioTransferMode) {
            if (mediaSessionManager == null) {
                mediaSessionManager = MediaSessionManager.instance()
                    ?: try {
                        // Defensive — should not be reached in normal flow because MBS.onCreate
                        // already bootstrapped the singleton. Pass applicationContext for the
                        // same StaticFieldLeak-free lifetime guarantees as the MBS path.
                        MediaSessionManager.getOrCreate(applicationContext, mediaSessionLogCallback).apply {
                            initialize()
                        }.also {
                            logWarn(
                                "[MEDIA_SESSION] singleton missing — bootstrapped from Activity (unexpected)",
                                tag = "MAIN",
                            )
                        }
                    } catch (e: Exception) {
                        logWarn(
                            "[MEDIA_SESSION] bootstrap failed — running without AAOS media integration: ${e.message}",
                            tag = "MAIN",
                        )
                        null
                    }
                if (mediaSessionManager != null) {
                    logInfo("[MEDIA_SESSION] Singleton acquired (ADAPTER mode)", tag = "MAIN")
                }
            }
        } else {
            mediaSessionManager?.let {
                logInfo("[MEDIA_SESSION] Audio mode switched to BLUETOOTH — flipping session inactive", tag = "MAIN")
                it.setInactive()
            }
            mediaSessionManager = null
        }
    }

    /**
     * Reinitialize the adapter session for a new display mode WITHOUT killing the app.
     *
     * Replaces the old Process.killProcess() approach. Performs a Tier-2 session restart:
     * 1. Save new display mode preference
     * 2. Stop adapter session (graceful teardown)
     * 3. Release old CabinManager
     * 4. Apply new system bar visibility
     * 5. Recalculate resolution from fresh WindowMetrics
     * 6. Create new CabinManager with new AdapterConfig
     * 7. Compose recomposes via cabinManagerState → new surface → initialize() → start()
     *
     * The adapter sees a clean disconnect + reconnect with correct new resolution.
     * End state is identical to kill+relaunch.
     */
    fun reinitializeForDisplayMode(newMode: DisplayMode) {
        // Cancel any pending reinit from a previous rapid display mode change
        pendingReinitJob?.cancel()
        pendingReinitJob = null

        val displayModeChanged = newMode != currentDisplayMode
        logInfo(
            "[DISPLAY_REINIT] Begin — switching from ${currentDisplayMode.name} to ${newMode.name}" +
                " (displayModeChanged=$displayModeChanged)",
            tag = "MAIN",
        )

        // When display mode changes, reset video resolution to Auto.
        // The old custom resolution was calculated for the old display bounds —
        // keeping it would cause stretch/shrink with the new bounds.
        // Write sync cache directly (not DataStore) to guarantee getUserConfigSync()
        // reads the updated value when initializeCabinManager() runs after 200ms.
        if (displayModeChanged) {
            val adapterConfigPref = AdapterConfigPreference.getInstance(this)
            val currentRes = adapterConfigPref.getUserConfigSync().videoResolution
            if (!currentRes.isAuto) {
                logInfo(
                    "[DISPLAY_REINIT] Resetting video resolution from ${currentRes.toStorageString()} to AUTO" +
                        " (display mode changed, old resolution invalid for new bounds)",
                    tag = "MAIN",
                )
                // Sync cache write is immediate — guarantees initializeCabinManager reads AUTO
                adapterConfigPref.setVideoResolutionSync(
                    com.cabin.ui.settings.VideoResolutionConfig.AUTO,
                )
            }
        }

        val oldManager = cabinManager
        if (oldManager != null) {
            logInfo("[DISPLAY_REINIT] Stopping current adapter session", tag = "MAIN")
            // Clear Compose reference FIRST — stops surface callbacks into old manager
            cabinManagerState.value = null
            CabinProjectionService.unregisterActivityManager(oldManager)
            cabinManager = null
        }

        pendingReinitJob = lifecycleScope.launch {
            // Release in-order without blocking the main thread or racing the new manager
            // for the same USB interface.
            if (oldManager != null) {
                val releasedByService =
                    CabinProjectionService.releaseRunningManagerForReconfiguration(oldManager)
                if (!releasedByService) oldManager.releaseAndWait()
                logInfo("[DISPLAY_REINIT] Old CabinManager released", tag = "MAIN")
            }

            currentDisplayMode = newMode
            applyDisplayMode(newMode)
            logInfo("[DISPLAY_REINIT] Applied display mode: ${newMode.name}", tag = "MAIN")

            // Allow the system-bar animation and WindowMetrics propagation to settle.
            delay(200)
            if (!isDestroyed && !isFinishing) {
                logInfo("[DISPLAY_REINIT] Rebuilding CabinManager with new metrics", tag = "MAIN")
                // Build from the newly settled Activity metrics before publishing the
                // manager to the service; headless creation would reuse old metrics.
                initializeCabinManager()
                logInfo("[DISPLAY_REINIT] Complete — CabinManager recreated", tag = "MAIN")
            }
            pendingReinitJob = null
        }
    }

    /** Build HU_VIEWAREA_INFO (24 bytes): [screen_w, screen_h, view_w, view_h, originX, originY] */
    private fun buildViewAreaData(
        width: Int,
        height: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(width)
            .putInt(height) // screen dims
            .putInt(width)
            .putInt(height) // viewarea dims (same)
            .putInt(0)
            .putInt(0) // origin
            .array()

    /** Build HU_SAFEAREA_INFO (20 bytes): [safe_w, safe_h, originX, originY, drawOutside] */
    private fun buildSafeAreaData(
        videoW: Int,
        videoH: Int,
        insetTop: Int,
        insetBottom: Int,
        insetLeft: Int,
        insetRight: Int,
    ): ByteArray {
        val safeW = videoW - insetLeft - insetRight
        val safeH = videoH - insetTop - insetBottom
        val hasInsets = (insetTop or insetBottom or insetLeft or insetRight) != 0
        return ByteBuffer
            .allocate(20)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(safeW)
            .putInt(safeH)
            .putInt(insetLeft)
            .putInt(insetTop)
            .putInt(if (hasInsets) 1 else 0) // wallpaper outside safe area only when cutouts exist
            .array()
    }

    private fun requestMicrophonePermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED -> {
                logInfo("Microphone permission already granted", tag = "MAIN")
                // Already granted — chain to location request directly
                requestLocationPermission()
            }

            else -> {
                logInfo("Requesting microphone permission", tag = "MAIN")
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                // Location will be requested in mic launcher callback
            }
        }
    }

    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED -> {
                logInfo("Location permission already granted", tag = "MAIN")
            }

            else -> {
                logInfo("Requesting location permission", tag = "MAIN")
                locationPermissionLauncher.launch(Unit)
            }
        }
    }

    /**
     * Loads display mode preference and applies it.
     * Uses synchronous SharedPreferences cache to avoid ANR.
     *
     * No try/catch: a SharedPreferences read exception here would propagate through
     * onCreate and crash the Activity. Low probability (the file is tiny and local),
     * but a failure path exists. The read's default is `DisplayMode.SYSTEM_UI_VISIBLE`
     * (see DisplayModePreference.getDisplayModeSync), matching the field initializer.
     */
    private fun loadAndApplyDisplayMode() {
        // Read preference from sync cache (instant, no I/O blocking)
        // This ensures the viewport is correctly sized before the first build
        currentDisplayMode = com.cabin.launcher.launcherDisplayMode(
            DisplayModePreference.getInstance(this).getDisplayModeSync(), homeNavigationRequest.value > 0, compactPanelMode)
        displayModeState.value = currentDisplayMode

        applyDisplayMode(currentDisplayMode)
        logInfo("[DISPLAY_MODE] Applied mode: ${currentDisplayMode.name}", tag = "MAIN")
    }

    /**
     * Applies the specified display mode by showing/hiding system bars.
     *
     * @param mode The display mode to apply
     */
    private fun applyDisplayMode(mode: DisplayMode) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        val lp = window.attributes
        // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS is API 30; SHORT_EDGES (API 28) is the
        // pre-30 equivalent. gminfo3.7 has no display cutout, so the two are identical
        // on the target hardware.
        val effectiveMode = com.cabin.launcher.launcherDisplayMode(mode, homeNavigationRequest.value > 0, compactPanelMode)
        systemBarRecovery?.update(effectiveMode)
        when (effectiveMode) {
            DisplayMode.SYSTEM_UI_VISIBLE -> {
                // Edge-to-edge (window stays full-display); Compose windowInsetsPadding in
                // MainScreen subtracts the system bars exactly once. Using decorFits=true here
                // double-counts the bars on warm re-dispatch (788 -> 616). [FIX UNDER TEST]
                WindowCompat.setDecorFitsSystemWindows(window, false)
                setCutoutMode(lp, edgeToEdge = false)
                window.attributes = lp
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            }

            DisplayMode.STATUS_BAR_HIDDEN -> {
                // Edge-to-edge: video extends into hidden-bar + cutout regions.
                // SafeArea metadata tells the phone to keep clickable UI out of those zones.
                WindowCompat.setDecorFitsSystemWindows(window, false)
                setCutoutMode(lp, edgeToEdge = true)
                window.attributes = lp
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
                windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            DisplayMode.NAV_BAR_HIDDEN -> {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                setCutoutMode(lp, edgeToEdge = true)
                window.attributes = lp
                windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
                windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            DisplayMode.FULLSCREEN_IMMERSIVE -> {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                setCutoutMode(lp, edgeToEdge = true)
                window.attributes = lp
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun setCutoutMode(
        attributes: WindowManager.LayoutParams,
        edgeToEdge: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        attributes.layoutInDisplayCutoutMode =
            if (!edgeToEdge) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
    }

    /**
     * Registers the USB detachment BroadcastReceiver.
     *
     * This enables immediate detection of physical adapter removal,
     * providing faster recovery than waiting for USB transfer errors.
     */
    private fun registerUsbDetachReceiver() {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbDetachReceiver, filter)
        }
        logInfo("[USB_DETACH] Registered USB detachment receiver", tag = "MAIN")
    }

    /**
     * Launches CarAppActivity in a separate task to trigger Templates Host binding.
     * Combined with taskAffinity="zeno.carlink.templates" and singleTask launch mode,
     * this opens in its own task stack without disturbing MainActivity.
     *
     * Guarded by [ClusterBindingState.sessionAlive] — only launches if no live session
     * exists. The cluster session can be torn down by the Templates Host / Car App Host
     * around USB re-enumeration (the specific "RendererServiceBinder.terminate()"
     * mechanism previously cited in comments is not present in this repo — treat the
     * teardown as black-box Host behavior). Must therefore be callable from both
     * onCreate() and onNewIntent().
     *
     * Post-launch, re-elevates MainActivity with FLAG_ACTIVITY_REORDER_TO_FRONT after
     * 1s. That 1s is the upper-bound pinned by [ClusterMainSession.RelayScreen]'s KDoc
     * ("visible for ~1s before MainActivity returns to front") — change here and the
     * RelayScreen duration changes too.
     */
    private fun launchCarAppActivity() {
        if (ClusterBindingState.sessionAlive) {
            logInfo("[CLUSTER] Cluster session still alive — will retry after teardown", tag = "MAIN")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isDestroyed && !isFinishing && !ClusterBindingState.sessionAlive) {
                    logInfo("[CLUSTER] Old session torn down — retrying launch", tag = "MAIN")
                    launchCarAppActivity()
                }
            }, 4000)
            return
        }

        try {
            val intent =
                Intent().apply {
                    setClassName(this@MainActivity, "androidx.car.app.activity.CarAppActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                }
            startActivity(intent)
            logInfo("[CLUSTER] Launched CarAppActivity for Templates Host binding", tag = "MAIN")

            // Bring MainActivity back quickly — binding is IPC-based and doesn't
            // need CarAppActivity in the foreground. 1s is enough for the handshake.
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isDestroyed && !isFinishing) {
                    val bringBack =
                        Intent(this@MainActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION
                        }
                    startActivity(bringBack)
                    logInfo("[CLUSTER] Brought MainActivity back to foreground", tag = "MAIN")
                }
            }, 1000)
        } catch (e: Exception) {
            logWarn("[CLUSTER] Failed to launch CarAppActivity: ${e.message}", tag = "MAIN")
        }
    }

    /**
     * Tears down the cluster binding chain and re-establishes it.
     * Clears nav state → kills CarAppActivity task → waits for teardown → relaunches.
     *
     * [ActivityManager.getAppTasks] returns only the calling package's tasks, so the
     * CarAppActivity match is guaranteed to be ours. `singleTask` on the manifest
     * declaration guarantees at most one matching task — the `break` after first match
     * is intentional.
     */
    fun restartClusterBinding() {
        if (!supportsClusterNavigation()) {
            logInfo("[CLUSTER] Reset ignored: AAOS Templates Host is unavailable", tag = "MAIN")
            return
        }
        logWarn("[CLUSTER] restartClusterBinding() — tearing down and re-establishing binding chain", tag = "MAIN")
        NavigationStateManager.clear()
        val am = getSystemService(ActivityManager::class.java)
        for (appTask in am.appTasks) {
            if (appTask.taskInfo.baseActivity?.className == "androidx.car.app.activity.CarAppActivity") {
                @Suppress("DEPRECATION")
                val taskId = appTask.taskInfo.id
                logInfo("[CLUSTER] Finishing CarAppActivity task (taskId=$taskId)", tag = "MAIN")
                appTask.finishAndRemoveTask()
                break
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isDestroyed && !isFinishing) {
                logInfo("[CLUSTER] Re-launching CarAppActivity after teardown", tag = "MAIN")
                launchCarAppActivity()
            }
        }, 2000)
    }

    /**
     * Unregisters the USB detachment BroadcastReceiver.
     */
    private fun unregisterUsbDetachReceiver() {
        try {
            unregisterReceiver(usbDetachReceiver)
            logInfo("[USB_DETACH] Unregistered USB detachment receiver", tag = "MAIN")
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered or already unregistered
            logWarn("[USB_DETACH] Receiver already unregistered: ${e.message}", tag = "MAIN")
        }
    }
}

/**
 * Main Composable App with Overlay Navigation
 *
 * ARCHITECTURE: Uses overlay/stack pattern instead of screen replacement.
 * MainScreen stays composed when SettingsScreen is pushed on top.
 *
 * WHY: The VideoSurface in MainScreen uses a SurfaceView.
 * When MainScreen is replaced (disposed), the Surface is destroyed,
 * causing "BufferQueue has been abandoned" errors if the MediaCodec is still
 * running. By keeping MainScreen always in composition and overlaying
 * SettingsScreen on top, the video continues playing uninterrupted.
 *
 * BEHAVIOR:
 * - MainScreen is ALWAYS rendered (video keeps playing)
 * - SettingsScreen slides in ON TOP when showSettings is true
 * - Back button closes SettingsScreen overlay
 * - Video is never stopped during navigation
 */
@Composable
fun CabinApp(
    cabinManager: CabinManager,
    fileLogManager: FileLogManager?,
    displayMode: DisplayMode,
    compactPanel: Boolean = false,
    homeRequest: Long = 0L,
    onOpenLauncherWindow: () -> Unit = {},
    onExpandPanel: () -> Unit = {},
    onClosePanel: () -> Unit = {},
    onOpenClimate: (() -> Unit)? = null,
    climateOverlayVisible: Boolean = false,
    climateSummaryVisible: Boolean = false,
    windowFocused: Boolean = true,
    climateState: TeyesClimateState = TeyesClimateState(),
    onSetClimateAc: ((Boolean) -> Unit)? = null,
    onSetClimateFan: ((Int) -> Unit)? = null,
    onAdjustClimateTemperature: ((com.cabin.platform.TeyesTemperatureZone, Boolean) -> Unit)? = null,
    onToggleClimateSwitch: ((com.cabin.platform.TeyesClimateSwitch) -> Unit)? = null,
    onAirAction: ((String) -> Unit)? = null,
    onVehicleLighting: ((com.cabin.platform.SyuLightingSetting, Int) -> Unit)? = null,
    onFactoryAmplifier: ((com.cabin.platform.SyuAmplifierSetting, Int) -> Unit)? = null,
    onFactoryControl: ((com.cabin.platform.SyuFactoryControl, Int) -> Unit)? = null,
    onSetClimateAirflow: ((TeyesAirflowMode) -> Unit)? = null,
    onResetCluster: () -> Unit,
    onRetryVehicle: () -> Unit = {},
    onRefreshClimate: (() -> Unit)? = null,
    drivingGuard: com.cabin.platform.TeyesDrivingGuard? = null,
    onReinitForDisplayMode: (DisplayMode) -> Unit = {},
    onResetConnection: () -> Unit = {},
) {
    // Survives normal recomposition via remember{}, but discarded on manager rebuild
    // because CabinApp itself is gated by `if (manager != null)` at the Activity
    // setContent — tier-2 reinit disposes the whole composable, so every reinit
    // lands back in MainScreen regardless of prior overlay state. Intentional UX.
    var launcherShell by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(homeRequest >= 0 && !compactPanel && BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) }
    var launcherPage by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(1) }
    var projectionFullscreen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var projectionPlacement by remember { mutableStateOf<com.cabin.launcher.ProjectionModulePlacement?>(null) }
    var projectionRootOrigin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    var showHome by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(homeRequest >= 0 && !compactPanel && BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) }
    var showSettings by remember { mutableStateOf(false) }
    var showHub by remember { mutableStateOf(false) }
    LaunchedEffect(homeRequest) {
        if (homeRequest != 0L) {
            launcherShell = homeRequest > 0 && BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE
            projectionFullscreen = false
            launcherPage = 1
            showHome = launcherShell
            showSettings = false
            showHub = false
        }
    }
    var initialSettingsTab by remember { mutableStateOf(com.cabin.ui.settings.SettingsTab.PHONES) }
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val projectionPreferences by ProjectionPreferences.getInstance(context).state.collectAsState()
    val connectionFlow = remember(cabinManager) { cabinManager.dashboardState.map { it.connection }.distinctUntilChanged() }
    val projectionConnection by connectionFlow.collectAsState(initial = cabinManager.state)
    val canFullscreenProjection = projectionConnection == CabinManager.State.STREAMING
    LaunchedEffect(canFullscreenProjection, compactPanel, homeRequest, showHome, projectionFullscreen) {
        if (!canFullscreenProjection && !compactPanel && BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) {
            projectionFullscreen = false
            if (!showHome) {
                launcherShell = true
                launcherPage = 1
                showHome = true
            }
        }
    }
    var pendingHubConnectAt by remember(cabinManager) { mutableStateOf<Long?>(null) }
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val guard = remember(drivingGuard) { drivingGuard ?: com.cabin.platform.TeyesDrivingGuard() }
    val driving by guard.state.collectAsState()
    // TEYES controller already expires each field; no external OBD source or UI polling.
    val speed = com.cabin.platform.teyesVehicleReadings(climateState).speed?.value
    LaunchedEffect(speed) { guard.observe(speed) }
    var pendingParkedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(pendingHubConnectAt, projectionConnection, showHub, showSettings, pendingParkedAction,
        compactPanel, foreground, windowFocused, projectionPreferences.returnWhenReady) {
        when (projectionReturnDecision(
            requestedAtMs = pendingHubConnectAt,
            nowMs = android.os.SystemClock.elapsedRealtime(),
            state = projectionConnection,
            eligible = projectionPreferences.returnWhenReady && showHub && !showSettings && pendingParkedAction == null &&
                !compactPanel && foreground && windowFocused,
        )) {
            ProjectionReturnDecision.RETURN -> { pendingHubConnectAt = null; showHub = false }
            ProjectionReturnDecision.CANCEL -> pendingHubConnectAt = null
            ProjectionReturnDecision.WAIT -> Unit
        }
    }
    val parkedAction: (() -> Unit) -> Unit = { action ->
        guard.observe(speed)
        if (!BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE || guard.state.value.parkedConfirmed) action()
        else if (guard.state.value.canRequestParkedAction) pendingParkedAction = action
    }
    fun selectLauncherPage(page: Int) {
        if (page in 2..3 && driving.moving) return
        if (page == 3) {
            parkedAction {
                projectionFullscreen = false
                launcherPage = 1
                showHome = true
                showHub = false
                initialSettingsTab = com.cabin.ui.settings.SettingsTab.CONTROL
                showSettings = true
            }
            return
        }
        val action = {
            projectionFullscreen = false
            launcherPage = if (page == 0 && !canFullscreenProjection) 1 else page.coerceIn(0, 4)
            showHome = launcherPage != 0
            showHub = false
            showSettings = false
        }
        if (page in 2..3) parkedAction(action) else action()
    }
    LaunchedEffect(driving.moving) {
        if (driving.moving) { showSettings = false; pendingParkedAction = null; if (launcherPage in 2..3) selectLauncherPage(1) }
    }

    LaunchedEffect(compactPanel) {
        if (compactPanel) { projectionFullscreen = false; showSettings = false; showHub = false; showHome = false }
    }

    val dashboardVisible = launcherShell && showHome && launcherPage == 1 && !compactPanel && !showHub && !projectionFullscreen
    val liveModuleVisible = dashboardVisible && projectionPlacement != null
    // Keep transport/audio alive but don't decode into an occluded Surface on TEYES.
    LaunchedEffect(cabinManager, showSettings, showHub, showHome, liveModuleVisible, projectionFullscreen, climateOverlayVisible) {
        val screenName = when { showSettings -> "SettingsScreen"; showHub -> "TeyesDashboard"; showHome -> "CabinHome"; else -> "Projection" }
        logInfo("[UI_NAV] Active screen: $screenName", tag = "UI")
        if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) {
            cabinManager.setVideoOverlayCovered(climateOverlayVisible || showSettings || showHub || (showHome && !liveModuleVisible && !projectionFullscreen), lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        } else if (!showSettings && !showHub && !showHome) {
            cabinManager.recoverVideoFromOverlay()
        }
    }
    DisposableEffect(cabinManager) {
        onDispose {
            if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) cabinManager.setVideoOverlayCovered(false, resumeWhenUncovered = false)
        }
    }

    BackHandler(enabled = projectionFullscreen && !showSettings && !showHub) { projectionFullscreen = false }
    BackHandler(enabled = launcherShell && !showHome && !showSettings && !showHub && !compactPanel) { selectLauncherPage(1) }

    // Handle back button to close settings overlay
    BackHandler(enabled = showSettings) {
        logInfo("[UI_NAV] Back pressed: Closing SettingsScreen overlay", tag = "UI")
        showSettings = false
    }
    BackHandler(enabled = showHub && !showSettings) { showHub = false }

    BackHandler(enabled = compactPanel && !showSettings) {
        logInfo("[UI_NAV] Back pressed: Closing compact projection panel", tag = "UI")
        onClosePanel()
    }

    Column(modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.weight(1f).fillMaxWidth().background(androidx.compose.material3.MaterialTheme.colorScheme.background)
          .onGloballyPositioned { projectionRootOrigin = it.positionInRoot() }) {
        // MainScreen is ALWAYS in composition - VideoSurface never gets disposed
        // This keeps the Surface alive and video playing uninterrupted
        val frame = projectionPlacement?.bounds
        val videoFrameModifier = if (dashboardVisible && frame != null) with(density) {
            Modifier.offset((frame.left - projectionRootOrigin.x).toDp(), (frame.top - projectionRootOrigin.y).toDp())
                .size(frame.width.toDp(), frame.height.toDp())
        } else Modifier.fillMaxSize()
        Box(videoFrameModifier.then(if (dashboardVisible) Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) else Modifier).clipToBounds().testTag("persistent-projection-frame")
            .alpha(if (dashboardVisible && frame == null) 0f else 1f)
            .then(if (climateOverlayVisible || (showHome && !liveModuleVisible && !projectionFullscreen) || showHub || showSettings) Modifier.clearAndSetSemantics { } else Modifier)) {
        MainScreen(
            cabinManager = cabinManager,
            displayMode = displayMode,
            isCompactPanel = compactPanel,
            embeddedModule = dashboardVisible,
            autoConnectOnLaunch = !launcherShell,
            onNavigateToSettings = {
                if (compactPanel) {
                    logInfo("[UI_NAV] Expanding compact projection panel", tag = "UI")
                    if (canFullscreenProjection) onExpandPanel()
                } else {
                    logInfo("[UI_NAV] Opening SettingsScreen overlay (video continues)", tag = "UI")
                    parkedAction {
                        initialSettingsTab = com.cabin.ui.settings.SettingsTab.PHONES
                        showSettings = true
                    }
                }
            },
            onChangeDevice = { action -> parkedAction(action) },
            onClosePanel = if (compactPanel) onClosePanel else null,
            onOpenDashboard = if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) ({ showHub = true; showHome = false }) else null,
            onOpenLauncher = if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) ({
                onOpenLauncherWindow()
                launcherShell = true
                selectLauncherPage(1)
            }) else null,
            onOpenClimate = onOpenClimate,
            climateOverlayVisible = false,
            climateSummaryVisible = false,
            projectionUiVisible = !climateOverlayVisible && !showHub && (!showHome || projectionFullscreen || (liveModuleVisible && projectionPlacement?.editing != true)) && !showSettings && pendingParkedAction == null,
            windowFocused = windowFocused,
            climateState = climateState,
            onSetClimateAc = onSetClimateAc,
            onSetClimateFan = onSetClimateFan,
            onSetClimateAirflow = onSetClimateAirflow,
            onAdjustClimateTemperature = onAdjustClimateTemperature,
            onToggleClimateSwitch = onToggleClimateSwitch,
            onAirAction = onAirAction,
            onRefreshClimate = onRefreshClimate,
            onResetConnection = onResetConnection,
        )
        }

        if (showHub && !compactPanel && BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) {
            com.cabin.ui.TeyesDashboard(
                manager = cabinManager,
                vehicle = climateState,
                onProjection = { showHub = false },
                onSettings = {
                    parkedAction { initialSettingsTab = com.cabin.ui.settings.SettingsTab.TEYES; showSettings = true }
                },
                onClimate = onOpenClimate,
                onRetryVehicle = onRetryVehicle,
                onConnectPhone = {
                    // Only this explicit action arms one return. Automatic reconnects never navigate.
                    pendingHubConnectAt = if (projectionPreferences.returnWhenReady) android.os.SystemClock.elapsedRealtime() else null
                    try {
                        CabinProjectionService.startPhoneConnection(context)
                    } catch (_: RuntimeException) {
                        pendingHubConnectAt = null
                        android.widget.Toast.makeText(context, resources.getString(R.string.app_status_connect_failed), android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                onParkedAction = parkedAction,
                moving = driving.moving,
                speedKnown = driving.speedKnown,
            )
        }

        if (showHome && !compactPanel && !showHub && BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) {
            com.cabin.launcher.CabinLauncher(
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        if (!projectionFullscreen && !climateOverlayVisible) placeable.place(0, 0)
                    }
                }.then(if (projectionFullscreen || climateOverlayVisible) Modifier.clearAndSetSemantics { } else Modifier),
                manager = cabinManager,
                vehicle = climateState,
                moving = driving.moving,
                onProjection = { selectLauncherPage(0) },
                onVehicle = { showHome = false; showHub = true },
                onClimate = onOpenClimate,
                onSettings = { selectLauncherPage(3) },
                onParkedAction = parkedAction,
                page = launcherPage,
                onPageChange = ::selectLauncherPage,
                onProjectionPlacement = { projectionPlacement = it },
                climateActions = com.cabin.launcher.ClimateWidgetActions(onSetClimateAc, onSetClimateFan, onRefreshClimate, onSetClimateAirflow, onAdjustClimateTemperature, onToggleClimateSwitch, onAirAction, onVehicleLighting, onFactoryAmplifier, onFactoryControl),
            )
        }

        // SettingsScreen slides in ON TOP of MainScreen
        // MainScreen remains visible underneath (just covered)
        androidx.compose.animation.AnimatedVisibility(
            visible = showSettings && !compactPanel,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                    .testTag("dashboard-settings-dismiss").clickable { showSettings = false })
                Surface(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(if (maxWidth >= 720.dp) 0.84f else 1f)
                        .fillMaxHeight().padding(8.dp).testTag("dashboard-settings-panel"),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                ) {
            SettingsScreen(
                cabinManager = cabinManager,
                fileLogManager = fileLogManager,
                onNavigateBack = {
                    logInfo("[UI_NAV] Closing SettingsScreen overlay", tag = "UI")
                    showSettings = false
                },
                onResetCluster = onResetCluster,
                onReinitForDisplayMode = onReinitForDisplayMode,
                initialTab = initialSettingsTab,
                embedded = true,
                vehicleState = climateState,
                moving = driving.moving,
                carActions = com.cabin.launcher.ClimateWidgetActions(onSetClimateAc, onSetClimateFan, onRefreshClimate, onSetClimateAirflow, onAdjustClimateTemperature, onToggleClimateSwitch, onAirAction, onVehicleLighting, onFactoryAmplifier, onFactoryControl),
                onParkedAction = parkedAction,
                onOpenClimate = onOpenClimate?.let { open -> { showSettings = false; open() } },
            )
                }
            }
        }
        if (pendingParkedAction != null && driving.canRequestParkedAction) {
            AlertDialog(
                onDismissRequest = { pendingParkedAction = null },
                title = { Text(androidx.compose.ui.res.stringResource(R.string.app_status_park_title)) },
                text = { Text(androidx.compose.ui.res.stringResource(R.string.app_status_park_message)) },
                confirmButton = { TextButton(onClick = {
                    guard.observe(speed)
                    if (guard.confirmParked()) { val action = pendingParkedAction; pendingParkedAction = null; action?.invoke() }
                }) { Text(androidx.compose.ui.res.stringResource(R.string.app_status_park_confirm)) } },
                dismissButton = { TextButton(onClick = { pendingParkedAction = null }) { Text(androidx.compose.ui.res.stringResource(R.string.app_status_cancel)) } },
            )
        }
        if (launcherShell && !compactPanel && !showHub && !showSettings && pendingParkedAction == null &&
            (projectionFullscreen || (liveModuleVisible && projectionPlacement?.editing != true) || !showHome)) {
            BoxWithConstraints(videoFrameModifier) {
                if (maxWidth >= 144.dp) com.cabin.launcher.ProjectionSettingsButton(
                    onClick = { parkedAction { initialSettingsTab = com.cabin.ui.settings.SettingsTab.PHONES; showSettings = true } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 68.dp, bottom = 8.dp),
                )
                com.cabin.launcher.ProjectionFullscreenButton(
                    fullscreen = projectionFullscreen || !showHome,
                    enabled = canFullscreenProjection || projectionFullscreen || !showHome,
                    onClick = {
                        if (!showHome) selectLauncherPage(1)
                        else if (projectionFullscreen || canFullscreenProjection) projectionFullscreen = !projectionFullscreen
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                )
            }
        }
        if (launcherShell && !compactPanel && !projectionFullscreen && !showSettings && !climateOverlayVisible) {
            com.cabin.launcher.LauncherPageSwitcher(if (showHome) launcherPage else 0, driving.moving, ::selectLauncherPage,
                Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        if (climateOverlayVisible) {
            // A full screen destination. Dashboard state stays composed underneath
            // so closing Climate returns to the same page and layout.
            BackHandler { onOpenClimate?.invoke() }
            com.cabin.ui.ClimatePanel(
                state = climateState,
                onToggleAc = onSetClimateAc,
                onSetFan = onSetClimateFan,
                onSetAirflow = onSetClimateAirflow,
                onAdjustTemperature = onAdjustClimateTemperature,
                onSwitch = onToggleClimateSwitch,
                onAirAction = onAirAction,
                onClose = onOpenClimate,
                onRefresh = onRefreshClimate,
                modifier = Modifier.fillMaxSize().testTag("climate-page"),
            )
        }
      }
    }
}
