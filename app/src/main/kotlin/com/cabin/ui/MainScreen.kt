package com.cabin.ui

import android.widget.Toast
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.AirlineSeatLegroomExtra
import androidx.compose.material.icons.filled.VerticalAlignTop
import com.cabin.platform.TeyesTelemetryHealth
import com.cabin.platform.labelRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.filled.Phonelink
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cabin.BuildConfig
import com.cabin.CabinManager
import com.cabin.R
import com.cabin.background.CabinProjectionService
import com.cabin.logging.logDebug
import com.cabin.logging.logInfo
import com.cabin.platform.ClimateNoticeMode
import com.cabin.platform.ProjectionControlSide
import com.cabin.platform.ProjectionPreferences
import com.cabin.platform.ProjectionReadinessSnapshot
import com.cabin.platform.ProjectionSetupPreferences
import com.cabin.platform.TeyesAirflowMode
import com.cabin.platform.TeyesClimateState
import com.cabin.protocol.PhoneType
import com.cabin.ui.components.LoadingSpinner
import com.cabin.ui.components.VideoSurface
import com.cabin.ui.components.rememberVideoSurfaceState
import com.cabin.ui.settings.DisplayMode
import com.cabin.ui.theme.AutomotiveDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main projection screen displaying H.264 video via SurfaceView (HWC overlay) with touch forwarding. */
@Composable
fun MainScreen(
    cabinManager: CabinManager,
    displayMode: DisplayMode,
    isCompactPanel: Boolean = false,
    embeddedModule: Boolean = false,
    autoConnectOnLaunch: Boolean = true,
    onNavigateToSettings: () -> Unit,
    onOpenDashboard: (() -> Unit)? = null,
    onChangeDevice: ((() -> Unit) -> Unit)? = null,
    onOpenLauncher: (() -> Unit)? = null,
    onClosePanel: (() -> Unit)? = null,
    onOpenClimate: (() -> Unit)? = null,
    climateOverlayVisible: Boolean = false,
    climateSummaryVisible: Boolean = false,
    projectionUiVisible: Boolean = true,
    windowFocused: Boolean = true,
    climateState: TeyesClimateState = TeyesClimateState(),
    onSetClimateAc: ((Boolean) -> Unit)? = null,
    onSetClimateFan: ((Int) -> Unit)? = null,
    onSetClimateAirflow: ((TeyesAirflowMode) -> Unit)? = null,
    onAdjustClimateTemperature: ((com.cabin.platform.TeyesTemperatureZone, Boolean) -> Unit)? = null,
    onToggleClimateSwitch: ((com.cabin.platform.TeyesClimateSwitch) -> Unit)? = null,
    onAirAction: ((String) -> Unit)? = null,
    onRefreshClimate: (() -> Unit)? = null,
    onResetConnection: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preferences by ProjectionPreferences.getInstance(context).state.collectAsStateWithLifecycle()
    val setupProgress by ProjectionSetupPreferences.get(context).progress.collectAsStateWithLifecycle()
    val sessionHealth by cabinManager.dashboardState.collectAsStateWithLifecycle()
    val projectionUiVisibleNow by rememberUpdatedState(projectionUiVisible)
    val windowFocusedNow by rememberUpdatedState(windowFocused)
    val navigateToSettingsNow by rememberUpdatedState(onNavigateToSettings)
    // Key state on cabinManager identity — when manager is replaced (display mode reinit),
    // all session-scoped state resets automatically. This prevents stale callbacks, flags,
    // or touch state from the old manager leaking into the new session.
    var connectionState by remember(cabinManager) { mutableStateOf(CabinManager.State.DISCONNECTED) }
    var statusText by remember(cabinManager) { mutableStateOf(resources.getString(R.string.main_connect_adapter)) }
    var isResetting by remember(cabinManager) { mutableStateOf(false) }
    val surfaceState = rememberVideoSurfaceState()
    var isAndroidAuto by remember(cabinManager) { mutableStateOf(false) }
    var aaCropParams by remember(cabinManager) { mutableStateOf<CabinManager.AaCropParams?>(null) }

    LaunchedEffect(connectionState) {
        logInfo("[UI_STATE] MainScreen connection state: $connectionState", tag = "UI")
    }

    var lastTouchTime by remember(cabinManager) { mutableLongStateOf(0L) }
    val touchState = remember(cabinManager) { ProjectionTouchState(cabinManager::sendMultiTouch) }
    var toolsVisible by remember(cabinManager) { mutableStateOf(false) }
    var screenBlanked by remember(cabinManager) { mutableStateOf(false) }
    var helpVisible by remember(cabinManager) { mutableStateOf(false) }
    var setupVisible by remember(cabinManager) { mutableStateOf(false) }
    var readiness by remember(cabinManager) { mutableStateOf<ProjectionReadinessSnapshot?>(null) }
    var readinessRefresh by remember(cabinManager) { mutableIntStateOf(0) }
    val openTools: () -> Unit = {
        touchState.cancel()
        toolsVisible = true
        helpVisible = false
    }
    val openHelp: () -> Unit = {
        touchState.cancel()
        helpVisible = true
        toolsVisible = false
    }
    val openSetup: () -> Unit = {
        touchState.cancel()
        setupVisible = true
        helpVisible = false
        toolsVisible = false
    }
    val connectPhone: () -> Unit = {
        touchState.cancel()
        try {
            CabinProjectionService.startPhoneConnection(context)
        } catch (_: RuntimeException) {
            Toast.makeText(context, resources.getString(R.string.hub_connect_failed), Toast.LENGTH_LONG).show()
        }
    }
    // No diagnostic polling while driving: read once when help becomes visible, when
    // the session stage changes, or when the user presses Refresh.
    LaunchedEffect(cabinManager, helpVisible, projectionUiVisible, connectionState, lifecycle, readinessRefresh) {
        if (helpVisible && projectionUiVisible) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                readiness = withContext(Dispatchers.IO) { cabinManager.projectionReadinessSnapshot() }
            }
        }
    }
    LaunchedEffect(connectionState, projectionUiVisible, windowFocused, climateOverlayVisible) {
        if (connectionState != CabinManager.State.STREAMING) {
            toolsVisible = false
            screenBlanked = false
        }
        if (!projectionUiVisible || !windowFocused || climateOverlayVisible) {
            toolsVisible = false
            helpVisible = false
            screenBlanked = false
        }
        if (!projectionUiVisible || climateOverlayVisible) setupVisible = false
        if (!projectionUiVisible || !windowFocused || climateOverlayVisible) touchState.cancel()
    }
    BackHandler(enabled = projectionUiVisible && (toolsVisible || helpVisible || setupVisible || screenBlanked)) {
        toolsVisible = false
        helpVisible = false
        screenBlanked = false
        setupVisible = false
    }
    var hasStartedConnection by remember(cabinManager) { mutableStateOf(false) }

    // Container dimensions (display-mode-padded area) — used for BoxSettings AR calculation.
    // Tracked separately from surfaceState because AA oversizes the SurfaceView beyond the container.
    var containerSize by remember(cabinManager) { mutableStateOf(IntSize.Zero) }

    // Handle surface initialization for adapter — uses CONTAINER dimensions for config/BoxSettings,
    // not the SurfaceView dimensions (oversized for AA bar cropping — confirmed in AutoKit; not
    // exercised in 2026-04-20 POTATO gminfo37 captures since all sessions ran CarPlay isAA=false).
    // Re-invoked on every Surface change (SurfaceView destroy→create, AA oversize resize, rotation);
    // CabinManager.initialize MUST be idempotent. hasStartedConnection (L96) is cleared only on
    // manager swap, so a new Surface with the same manager (AA resize) will re-initialize but will
    // NOT re-invoke start() — preserving the session.
    LaunchedEffect(
        surfaceState.surface,
        surfaceState.width,
        surfaceState.height,
        containerSize,
    ) {
        surfaceState.surface?.let { surface ->
            if (containerSize.width <= 0 || containerSize.height <= 0) return@let

            // Force even dimensions for H.264 macroblock alignment
            val adapterWidth = containerSize.width and 1.inv()
            val adapterHeight = containerSize.height and 1.inv()

            logInfo(
                "[CABIN_RESOLUTION] Container size: ${adapterWidth}x$adapterHeight " +
                    "(surface: ${surfaceState.width}x${surfaceState.height}, mode=$displayMode)",
                tag = "UI",
            )

            cabinManager.initialize(
                surface = surface,
                surfaceWidth = adapterWidth,
                surfaceHeight = adapterHeight,
                callback =
                    object : CabinManager.Callback {
                        override fun onStateChanged(state: CabinManager.State) {
                            if (state != CabinManager.State.STREAMING) touchState.clear()
                            connectionState = state
                        }

                        override fun onStatusTextChanged(text: String) {
                            statusText = text
                        }

                        override fun onHostUIPressed() {
                            if (!projectionUiVisibleNow || !windowFocusedNow || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                            if (connectionState == CabinManager.State.STREAMING && projectionUiVisibleNow) {
                                openTools()
                            } else {
                                navigateToSettingsNow()
                            }
                        }

                        override fun onPhoneTypeChanged(phoneType: PhoneType) {
                            val isAA = phoneType == PhoneType.ANDROID_AUTO
                            // The manager also emits the SAME phone type after a container resize.
                            // Always refresh crop geometry so a climate strip or changed insets
                            // cannot leave Android Auto using the previous content area's crop.
                            val crop = if (isAA) cabinManager.getAaCropParams() else null
                            if (isAA != isAndroidAuto || crop != aaCropParams) {
                                logInfo(
                                    "[UI_SURFACE] Phone layout updated: $phoneType, isAA=$isAA, crop=$crop",
                                    tag = "UI",
                                )
                            }
                            isAndroidAuto = isAA
                            aaCropParams = crop
                        }
                    },
            )
            if (!hasStartedConnection) {
                hasStartedConnection = true
                // A manager adopted from CabinProjectionService may already be
                // CONNECTING or STREAMING. Starting it again would tear down the live
                // USB session, so only cold managers enter start() here.
                if (autoConnectOnLaunch && cabinManager.state == CabinManager.State.DISCONNECTED) {
                    cabinManager.start()
                }
            }
        }
    }

    val isLoading = connectionState != CabinManager.State.STREAMING
    val colorScheme = MaterialTheme.colorScheme
    val configuredVideoAspectRatio = remember(cabinManager) { cabinManager.configuredVideoAspectRatio() }
    val restartConnection: () -> Unit = {
        if (!isResetting) {
            touchState.cancel()
            isResetting = true
            // Keep the existing Activity-level repair path: it rebuilds the manager and
            // surface with current window dimensions. Other callers use in-manager restart.
            val reset = onResetConnection
            if (reset != null) {
                reset()
                isResetting = false
            } else {
                scope.launch {
                    try {
                        cabinManager.restart()
                    } finally {
                        isResetting = false
                    }
                }
            }
        }
    }

    val baseModifier = Modifier.fillMaxSize().background(Color.Black)
    val boxModifier =
        if (embeddedModule) baseModifier else when (displayMode) {
            DisplayMode.FULLSCREEN_IMMERSIVE -> {
                baseModifier
            }

            DisplayMode.SYSTEM_UI_VISIBLE -> {
                baseModifier
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .windowInsetsPadding(WindowInsets.displayCutout)
            }

            DisplayMode.STATUS_BAR_HIDDEN,
            DisplayMode.NAV_BAR_HIDDEN,
            -> {
                baseModifier
                    .windowInsetsPadding(WindowInsets.systemBars)
            }
            // No displayCutout padding — video extends behind cutout.
            // (SafeArea, which tells CarPlay where to avoid placing UI, is NOT sent from this file —
            // it is emitted by CabinManager/MessageSerializer. This comment documents the visual
            // intent of the hidden-bars branch above, not an action performed here.)
        }

    BoxWithConstraints(modifier = boxModifier) {
        val density = LocalDensity.current
        // Use this actual viewport (including compact panels), not the whole display.
        // Reserve usable control height and a non-zero proportional projection area.
        val climateStripHeight = climatePanelHeightDp(maxHeight.value, climateOverlayVisible).dp
        val viewportHeight = maxHeight

        ProjectionVisibilityLayers(
            blanked = screenBlanked && projectionUiVisible && !isLoading,
            onWake = { screenBlanked = false },
        ) {
            // For AA: SurfaceView oversized to tier AR (16:9), centered + clipped.
            // Black bars in the codec frame fall outside the clip → cropped visually.
            // Codec start is deferred until phone type is known, so surface resize
            // (destroyed→created at oversized dims) happens before decoder is active.
            // (Cross-file: the deferral is enforced in CabinManager.kt — see initialize()
            // and the phone-type gate around the codec start path, approx. :247 / :1153 / :1477.)
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = climateStripHeight)
                        .clipToBounds(),
            ) {
                // Track container dimensions for BoxSettings AR calculation
                val containerPx =
                    with(density) {
                        IntSize(maxWidth.roundToPx(), maxHeight.roundToPx())
                    }
                LaunchedEffect(containerPx) {
                    if (containerPx.width > 0 && containerPx.height > 0) {
                        if (containerSize != containerPx) touchState.cancel()
                        containerSize = containerPx
                    }
                }

                // AA: oversize SurfaceView to tier AR so the phone's baked black bars overflow the
                // clip region. Two-way fit puts bars on exactly ONE axis: cropTop>0 → bars top/bottom
                // (oversize height); cropLeft>0 → bars left/right (oversize width). CarPlay fills.
                val surfaceModifier =
                    if (aaCropParams != null) {
                        val cp = aaCropParams!!
                        val tierAR = cp.tierWidth.toFloat() / cp.tierHeight.toFloat()
                        if (cp.cropLeft > 0) {
                            val oversizedWidthDp = with(density) { (maxHeight.toPx() * tierAR).toInt().toDp() }
                            Modifier
                                .fillMaxHeight()
                                .requiredWidth(oversizedWidthDp)
                                .align(Alignment.Center)
                        } else {
                            val oversizedHeightDp = with(density) { (maxWidth.toPx() / tierAR).toInt().toDp() }
                            Modifier
                                .fillMaxWidth()
                                .requiredHeight(oversizedHeightDp)
                                .align(Alignment.Center)
                        }
                    } else {
                        val containerAspectRatio =
                            if (containerPx.height > 0) {
                                containerPx.width.toFloat() / containerPx.height.toFloat()
                            } else {
                                configuredVideoAspectRatio
                            }
                        // SurfaceView stretches decoded frames when both dimensions are
                        // forced. Constrain only one axis and center the other so the
                        // phone-rendered frame keeps its negotiated aspect ratio while
                        // the temporary TEYES climate strip reduces available height.
                        if (containerAspectRatio > configuredVideoAspectRatio) {
                            Modifier
                                .fillMaxHeight()
                                .aspectRatio(configuredVideoAspectRatio)
                                .align(Alignment.Center)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(configuredVideoAspectRatio)
                                .align(Alignment.Center)
                        }
                    }

                // Key the VideoSurface on (manager identity, displayMode) so any reinit
                // path (display-mode change, resolution change, Reset Connection via the
                // manager-rebuild path) drops the AndroidView composition slot, disposing
                // the old VideoSurfaceView and releasing its HWC overlay plane. A fresh
                // SurfaceView is then inflated against the current window rect — the only
                // reliable way to migrate the HWC plane when system insets change post-boot.
                // Parent-size deltas alone are NOT included here: they would rebuild the
                // SurfaceView on every transient resize (e.g. bar animation frames) and
                // cause video flicker. Only identity-level changes force recreation.
                key(cabinManager, displayMode) {
                    VideoSurface(
                        modifier = surfaceModifier,
                        onSurfaceAvailable = { surface, width, height ->
                            logInfo("[UI_SURFACE] Surface available: ${width}x$height (isAA=$isAndroidAuto)", tag = "UI")
                            surfaceState.onSurfaceAvailable(surface, width, height)
                        },
                        onSurfaceDestroyed = {
                            logInfo("[UI_SURFACE] Surface destroyed", tag = "UI")
                            // Also called on composable disposal; duplicate teardown is harmless.
                            if (cabinManager.state == CabinManager.State.STREAMING) touchState.cancel() else touchState.clear()
                            surfaceState.onSurfaceDestroyed()
                            cabinManager.onSurfaceDestroyed()
                        },
                        onSurfaceSizeChanged = { width, height ->
                            logInfo("[UI_SURFACE] Surface size changed: ${width}x$height", tag = "UI")
                            surfaceState.onSurfaceSizeChanged(width, height)
                        },
                        onTouchEvent = { event ->
                            // Read state directly through the Compose delegate — NOT via
                            // the pre-computed val `isUserInteractingWithProjection`.
                            // This lambda is captured once in AndroidView's factory block;
                            // a pre-computed val would snapshot DISCONNECTED permanently.
                            if (connectionState == CabinManager.State.STREAMING && projectionUiVisibleNow &&
                                windowFocusedNow && !toolsVisible && !helpVisible && !setupVisible && !screenBlanked
                            ) {
                                if (BuildConfig.DEBUG) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTouchTime > 1000) {
                                        logDebug(
                                            "[UI_TOUCH] touch: action=${event.actionMasked}" +
                                                ", pointers=${event.pointerCount}" +
                                                ", surface=${surfaceState.width}x${surfaceState.height}" +
                                                ", container=${containerSize.width}x${containerSize.height}",
                                            tag = "UI",
                                        )
                                        lastTouchTime = now
                                    }
                                }
                                touchState.handle(
                                    event,
                                    surfaceState.width,
                                    surfaceState.height,
                                    containerSize.width,
                                    containerSize.height,
                                )
                            }
                            true
                        },
                    )
                } // end key(cabinManager, displayMode)
            }

            // Keep the live SurfaceView mounted behind this connection UI. Changing the
            // presentation must not restart USB or replace the video composition slot.
            if (isLoading && embeddedModule) {
                val colors = MaterialTheme.colorScheme
                BoxWithConstraints(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(colors.surfaceContainerHigh, colors.surfaceContainerLowest))).padding(16.dp)) {
                    if (maxHeight < 260.dp) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Phonelink, null, Modifier.size(32.dp), tint = colors.primary)
                            Text(stringResource(R.string.launcher_page_carplay), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                            androidx.compose.material3.FilledIconButton(connectPhone, Modifier.size(56.dp)) {
                                Icon(Icons.Default.ArrowForward, stringResource(R.string.launcher_connect))
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(color = colors.primary.copy(alpha = 0.10f), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
                                Icon(Icons.Default.Phonelink, null, Modifier.padding(20.dp).size(40.dp), tint = colors.primary)
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(stringResource(R.string.launcher_page_carplay), style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                            Spacer(Modifier.height(24.dp))
                            Button(connectPhone, modifier = Modifier.heightIn(min = 56.dp),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
                                Text(stringResource(R.string.launcher_connect))
                                Spacer(Modifier.width(12.dp))
                                Icon(Icons.Default.ArrowForward, null, Modifier.size(20.dp))
                            }
                        }
                    }
                }
            } else if (isLoading) {
                ProjectionConnectionScreen(
                    state = connectionState,
                    statusText = statusText,
                    isResetting = isResetting,
                    isCompactPanel = isCompactPanel,
                    onReconnect = connectPhone,
                    onSettings = onNavigateToSettings,
                    onDashboard = onOpenDashboard,
                    onClimate = if (climateOverlayVisible) null else onOpenClimate,
                    onClosePanel = onClosePanel,
                    onHelp = openHelp,
                    onHome = onOpenLauncher?.let { open -> { toolsVisible = false; open() } },
                    onRestart = restartConnection,
                    onSetup = openSetup,
                    firstTimeSetup = !setupProgress.completed,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = climateStripHeight),
                )
            }

            // Fullscreen keeps the controls out of the way once video is live. The
            // compact panel always keeps Expand/Close reachable so it cannot trap the
            // user in a borderless SurfaceView after the loading overlay disappears.
            if (!isLoading && isCompactPanel) {
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .windowInsetsPadding(WindowInsets.displayCutout)
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onNavigateToSettings() },
                        modifier = Modifier.heightIn(min = 56.dp),
                        contentPadding =
                            PaddingValues(
                                horizontal = AutomotiveDimens.ButtonPaddingHorizontal,
                                vertical = AutomotiveDimens.ButtonPaddingVertical,
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = null,
                            modifier = Modifier.size(AutomotiveDimens.IconSize),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.main_fullscreen),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }

                    if (onClosePanel != null) {
                        FilledTonalButton(
                            onClick = onClosePanel,
                            modifier = Modifier.heightIn(min = 56.dp),
                            contentPadding =
                                PaddingValues(
                                    horizontal = AutomotiveDimens.ButtonPaddingHorizontal,
                                    vertical = AutomotiveDimens.ButtonPaddingVertical,
                                ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(AutomotiveDimens.IconSize),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_close),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }

                    FilledTonalIconButton(onClick = openTools, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                }
            }

            // Keep settings and return to launcher directly accessible over CarPlay.
            if (!isLoading && !isCompactPanel) {
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .windowInsetsPadding(WindowInsets.displayCutout)
                            .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (preferences.controlSide == ProjectionControlSide.RIGHT) {
                        Box(Modifier.weight(1f)) {
                            if (preferences.vehicleHud && BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) VehicleHud(state = climateState)
                        }
                    }
                    if (!preferences.focusControls) {
                        onOpenDashboard?.let { open ->
                            FilledTonalButton(onClick = {
                                touchState.cancel()
                                open()
                            }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.main_hub)) }
                        }
                        if (!climateOverlayVisible) {
                            onOpenClimate?.let {
                                ClimateButton(onClick = {
                                    touchState.cancel()
                                    it()
                                })
                            }
                        }
                    }
                    FilledTonalIconButton(onClick = openTools, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                    onOpenLauncher?.let { open ->
                        FilledTonalIconButton(
                            onClick = {
                                touchState.cancel()
                                toolsVisible = false
                                open()
                            },
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(Icons.Default.Minimize, contentDescription = stringResource(R.string.projection_minimize))
                        }
                    }
                    if (preferences.controlSide == ProjectionControlSide.LEFT) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            if (preferences.vehicleHud && BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) VehicleHud(state = climateState)
                        }
                    }
                }
            }

            if (climateOverlayVisible) {
                ClimatePanel(
                    state = climateState,
                    onToggleAc = onSetClimateAc,
                    onSetFan = onSetClimateFan,
                    onSetAirflow = onSetClimateAirflow,
                    onAdjustTemperature = onAdjustClimateTemperature,
                    onSwitch = onToggleClimateSwitch,
                    onAirAction = onAirAction,
                    onClose = onOpenClimate,
                    onRefresh = onRefreshClimate,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(climateStripHeight),
                )
            }

            val doorWarning = vehicleDoorWarning(androidx.compose.ui.platform.LocalResources.current, climateState)
            if (climateSummaryVisible && preferences.climateNoticeMode == ClimateNoticeMode.SUMMARY &&
                !climateOverlayVisible && !toolsVisible && !helpVisible && !setupVisible && doorWarning == null
            ) {
                ProjectionClimateSummary(
                    state = climateState,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 680.dp),
                )
            }

            // These small, ordinary Compose panels never alter the video slot or decoder
            // overlay-coverage state. Touch forwarding is suspended until they close.
            if (toolsVisible && projectionUiVisible && !isLoading) {
                ProjectionQuickMenu(
                    manager = cabinManager,
                    onRequestDeviceChange = onChangeDevice ?: { action -> action() },
                    onSettings = {
                        toolsVisible = false
                        onNavigateToSettings()
                    },
                    onClose = { toolsVisible = false },
                    onScreenOff = {
                        touchState.cancel()
                        toolsVisible = false
                        helpVisible = false
                        screenBlanked = true
                    },
                    modifier =
                        Modifier.align(if (preferences.controlSide == ProjectionControlSide.LEFT) Alignment.TopStart else Alignment.TopEnd).padding(12.dp)
                            .heightIn(max = (viewportHeight - if (doorWarning != null) 112.dp else 24.dp).coerceAtLeast(100.dp)),
                )
            }
            if (helpVisible && projectionUiVisible) {
                readiness?.let { snapshot ->
                    ProjectionConnectionHelp(
                        snapshot = snapshot,
                        onConnect = connectPhone,
                        onRefresh = { readinessRefresh++ },
                        onClose = { helpVisible = false },
                        onOpenPermissions = { openProjectionAppPermissions(context) },
                        onOpenSetup = openSetup,
                        onStopSession = {
                            scope.launch {
                                CabinProjectionService.stopForAppExit(context, cabinManager)
                                readinessRefresh++
                            }
                        },
                        sessionIdle = sessionHealth.connection == CabinManager.State.DISCONNECTED &&
                            !cabinManager.projectionSessionRequested,
                        modifier =
                            Modifier.align(if (preferences.controlSide == ProjectionControlSide.LEFT) Alignment.TopStart else Alignment.TopEnd).padding(12.dp).widthIn(max = 560.dp)
                                .heightIn(max = (viewportHeight - if (doorWarning != null) 112.dp else 24.dp).coerceAtLeast(100.dp)),
                    )
                }
            }
            if (setupVisible && projectionUiVisible) {
                ProjectionSetupFlow(
                    manager = cabinManager,
                    onClose = { setupVisible = false },
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
            doorWarning?.let { warning ->
                Text(
                    text = warning,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 12.dp, end = 12.dp, bottom = climateStripHeight + 12.dp)
                            .background(colorScheme.errorContainer, MaterialTheme.shapes.medium)
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    color = colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        } // Stable content stays mounted when the blackout changes.
    }
}

@Composable
internal fun ProjectionConnectionScreen(
    state: CabinManager.State,
    statusText: String,
    isResetting: Boolean,
    isCompactPanel: Boolean,
    onReconnect: () -> Unit,
    onSettings: () -> Unit,
    onDashboard: (() -> Unit)?,
    onClimate: (() -> Unit)?,
    onClosePanel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onHelp: (() -> Unit)? = null,
    onRestart: (() -> Unit)? = null,
    onSetup: (() -> Unit)? = null,
    firstTimeSetup: Boolean = false,
    onHome: (() -> Unit)? = null,
) {
    val presentation = projectionConnectionPresentation(androidx.compose.ui.platform.LocalResources.current, state)
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier, color = colors.surface) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val viewportHeight = maxHeight
            val shortViewport = viewportHeight < 400.dp || maxWidth < 600.dp || LocalDensity.current.fontScale >= 1.3f
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = viewportHeight)
                        .padding(if (shortViewport) 16.dp else 28.dp),
                verticalArrangement = Arrangement.spacedBy(if (shortViewport) 12.dp else 20.dp, Alignment.CenterVertically),
            ) {
                if (isCompactPanel) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(onClick = onSettings, modifier = Modifier.heightIn(min = 56.dp)) {
                            Icon(Icons.Default.Fullscreen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.main_fullscreen))
                        }
                        onClosePanel?.let { close ->
                            OutlinedButton(onClick = close, modifier = Modifier.heightIn(min = 56.dp)) {
                                Icon(Icons.Default.Close, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.main_close_panel))
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!shortViewport) {
                        Surface(color = colors.primaryContainer, shape = MaterialTheme.shapes.large) {
                            Icon(
                                Icons.Default.Phonelink,
                                contentDescription = null,
                                tint = colors.onPrimaryContainer,
                                modifier = Modifier.padding(20.dp).size(40.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!shortViewport) Text("CABIN", color = colors.primary, style = MaterialTheme.typography.labelLarge)
                        Text(
                            presentation.title,
                            style = if (shortViewport) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                            color = colors.onSurface,
                        )
                        Text(presentation.nextStep, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                }

                if (viewportHeight >= 360.dp) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(stringResource(R.string.help_usb_adapter), stringResource(R.string.label_phone), stringResource(R.string.readiness_projection)).forEachIndexed { index, label ->
                            val reached = index <= presentation.stage
                            Surface(
                                color = if (reached) colors.secondaryContainer else colors.surfaceContainerHigh,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    "${index + 1}  $label",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (reached) colors.onSecondaryContainer else colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (presentation.busy || isResetting) LoadingSpinner(size = 24.dp, color = colors.primary)
                        Text(
                            text = if (isResetting) stringResource(R.string.main_restarting) else statusText.ifBlank { presentation.title },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state != CabinManager.State.DEVICE_CONNECTED) {
                        Button(onClick = onReconnect, enabled = !isResetting, modifier = Modifier.heightIn(min = 56.dp)) {
                            Icon(Icons.Default.Phonelink, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_connect_phone))
                        }
                    }
                    onHome?.let { open ->
                        FilledTonalButton(open, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.launcher_home)) }
                    }
                    onHelp?.let { help ->
                        FilledTonalButton(onClick = help, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.help_title)) }
                    }
                    onSetup?.let { setup ->
                        OutlinedButton(onClick = setup, modifier = Modifier.heightIn(min = 56.dp)) {
                            Text(stringResource(if (firstTimeSetup) R.string.setup_first_time else R.string.setup_guide))
                        }
                    }
                    if (!isCompactPanel) {
                        onDashboard?.let { open ->
                            FilledTonalButton(onClick = open, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.main_open_hub)) }
                        }
                        OutlinedButton(onClick = onSettings, modifier = Modifier.heightIn(min = 56.dp)) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_settings))
                        }
                    }
                    onClimate?.let { ClimateButton(onClick = it) }
                    onRestart?.let { restart ->
                        OutlinedButton(onClick = restart, enabled = !isResetting, modifier = Modifier.heightIn(min = 56.dp)) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.main_restart_connection))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleHud(
    state: TeyesClimateState,
    modifier: Modifier = Modifier,
) {
    val units by com.cabin.platform.MeasurementPreferences.get(LocalContext.current).unit.collectAsStateWithLifecycle()
    if (state.speedKph == null && state.engineRpm == null && state.oilLifePercent == null) return
    Row(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), MaterialTheme.shapes.medium)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.speedKph?.let {
            HudValue(
                value = kotlin.math.round(com.cabin.platform.MeasurementFormatter.speedValue(it.toDouble(), units)).toInt().toString(),
                unit = com.cabin.platform.MeasurementFormatter.speedLabel(units),
            )
        }
        state.engineRpm?.let {
            HudValue(value = it.toString(), unit = "RPM")
        }
        state.oilLifePercent?.let {
            HudValue(
                value = "$it%",
                unit = stringResource(R.string.main_oil),
                warning = it <= 15,
            )
        }
    }
}

@Composable
private fun HudValue(
    value: String,
    unit: String,
    warning: Boolean = false,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = " $unit",
            color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun ClimatePanel(
    state: TeyesClimateState,
    onToggleAc: ((Boolean) -> Unit)?,
    onSetFan: ((Int) -> Unit)?,
    onSetAirflow: ((TeyesAirflowMode) -> Unit)?,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
    onAdjustTemperature: ((com.cabin.platform.TeyesTemperatureZone, Boolean) -> Unit)? = null,
    onSwitch: ((com.cabin.platform.TeyesClimateSwitch) -> Unit)? = null,
    onAirAction: ((String) -> Unit)? = null,
) {
    state.syuAir?.let { air ->
        SyuAirPanel(air, onAirAction, modifier, onClose)
        return
    }
    val closeTimer = rememberClimateCloseTimer(onClose)
    val civicControls = com.cabin.platform.TeyesClimateControlPolicy.supportsTemperature(state.profileId)
    val resources = androidx.compose.ui.platform.LocalResources.current
    val controlsEnabled = state.controlsAvailable
    val colors = MaterialTheme.colorScheme
    val acKnown = (if (state.profileId == 262465) 30 else 24) in state.availableCodes
    val selectedAirflow = selectedClimateAirflow(state)
    BoxWithConstraints(modifier = modifier.then(closeTimer.touchModifier).background(colors.background)) {
        val wideClimate = maxWidth >= 720.dp
        val compactHeader = maxHeight < 220.dp
        val scrollWholePanel = maxHeight < 144.dp
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .then(if (scrollWholePanel) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = if (wideClimate) 24.dp else 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.climate_title), color = colors.onSurface, style = MaterialTheme.typography.headlineSmall)
                    if (state.health != TeyesTelemetryHealth.LIVE) {
                        Text(stringResource(state.health.labelRes), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                    if (!compactHeader && !civicControls) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            ClimateTemperature(stringResource(R.string.label_left), state.leftTemperature, state.fahrenheit)
                            ClimateTemperature(stringResource(R.string.label_right), state.rightTemperature, state.fahrenheit)
                        }
                    }
                }
                onRefresh?.let { refresh ->
                    IconButton(refresh, Modifier.size(56.dp)) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.widget_refresh_climate))
                    }
                }
                onClose?.let { close ->
                    ClimateCloseButton(close, closeTimer.remaining.value)
                }
            }

            // Normally keep header/close reachable. On exceptionally short panels, scroll
            // the entire sheet so a full control can still enter the viewport.
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .then(if (scrollWholePanel) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState())),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (compactHeader && !civicControls) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        ClimateTemperature(stringResource(R.string.label_left), state.leftTemperature, state.fahrenheit)
                        ClimateTemperature(stringResource(R.string.label_right), state.rightTemperature, state.fahrenheit)
                    }
                }
                if (civicControls) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        com.cabin.platform.TeyesTemperatureZone.entries.forEach { zone ->
                            Surface(Modifier.weight(1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                                color = colors.surfaceContainerLow) {
                                ClimateTemperatureControl(state, zone, onAdjustTemperature,
                                    Modifier.padding(if (wideClimate) 12.dp else 8.dp),
                                    fontSize = if (wideClimate) 48.sp else 34.sp, horizontal = wideClimate)
                            }
                        }
                    }
                    ClimateSwitchControls(state, onSwitch)
                }
                val extraControlsAvailable = com.cabin.platform.TeyesClimateSwitch.entries.any {
                    com.cabin.platform.TeyesClimateControlPolicy.canToggle(state, it)
                } || com.cabin.platform.TeyesTemperatureZone.entries.any {
                    com.cabin.platform.TeyesClimateControlPolicy.canAdjustTemperature(state, it, true) ||
                        com.cabin.platform.TeyesClimateControlPolicy.canAdjustTemperature(state, it, false)
                }
                if (!controlsEnabled && !fanSpeedEnabled(state) && !extraControlsAvailable) {
                    Text(
                        text = state.controlUnavailableReason ?: stringResource(R.string.climate_readonly),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onToggleAc?.invoke(!state.ac) },
                        enabled = controlsEnabled && acKnown && onToggleAc != null,
                        modifier = Modifier.heightIn(min = 56.dp).semantics { selected = acKnown && state.ac },
                        colors =
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (acKnown && state.ac) colors.primaryContainer else colors.surfaceContainerHighest,
                                contentColor = if (acKnown && state.ac) colors.onPrimaryContainer else colors.onSurface,
                            ),
                    ) {
                        Icon(Icons.Default.AcUnit, null, Modifier.size(32.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (!acKnown) {
                                stringResource(R.string.climate_ac_unknown)
                            } else if (state.ac) {
                                stringResource(R.string.climate_ac_on)
                            } else {
                                stringResource(R.string.climate_ac_off)
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    FanSpeedControls(state, onSetFan, Modifier.weight(1f).padding(horizontal = 8.dp))
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        stringResource(R.string.climate_face) to TeyesAirflowMode.BODY,
                        stringResource(R.string.climate_face_feet) to TeyesAirflowMode.BODY_FOOT,
                        stringResource(R.string.climate_feet) to TeyesAirflowMode.FOOT,
                        stringResource(R.string.climate_screen_feet) to TeyesAirflowMode.UP_FOOT,
                    ).forEach { (label, mode) ->
                        ClimateModeButton(label, mode, selectedAirflow == mode, controlsEnabled && onSetAirflow != null) {
                            onSetAirflow?.invoke(mode)
                        }
                    }
                }
                val status = climateStatusText(androidx.compose.ui.platform.LocalResources.current, state)
                if (status.isNotEmpty()) {
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ClimateTemperature(
    label: String,
    raw: Int?,
    fahrenheit: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(
            formatClimateTemperature(raw, fahrenheit),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ClimateModeButton(
    label: String,
    mode: TeyesAirflowMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 88.dp).semantics { this.selected = selected },
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(when (mode) {
                TeyesAirflowMode.BODY -> Icons.Default.Face
                TeyesAirflowMode.BODY_FOOT -> Icons.Default.AirlineSeatReclineNormal
                TeyesAirflowMode.FOOT -> Icons.Default.AirlineSeatLegroomExtra
                TeyesAirflowMode.UP_FOOT -> Icons.Default.VerticalAlignTop
            }, null, Modifier.size(32.dp))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun formatClimateTemperature(
    raw: Int?,
    fahrenheit: Boolean,
): String =
    when (raw) {
        null, -1 -> "—"
        -2 -> "LOW"
        -3 -> "HIGH"
        else -> if (fahrenheit) "$raw°F" else "${raw / 2f}°C"
    }

private fun climateStatusText(resources: android.content.res.Resources, state: TeyesClimateState): String =
    buildList {
        if (state.power) add(resources.getString(R.string.climate_state_on))
        if (state.auto) add(resources.getString(R.string.climate_state_auto))
        if (state.dual) add(resources.getString(R.string.climate_state_dual))
        if (state.recirculating) add(resources.getString(R.string.climate_state_recirc))
        if (state.frontDefrost) add(resources.getString(R.string.climate_state_front_defrost))
        if (state.rearDefrost) add(resources.getString(R.string.climate_state_rear_defrost))
        if (state.rearAuto) add(resources.getString(R.string.climate_state_rear_auto))
        if (state.driverSeatHeating > 0) add(resources.getString(R.string.climate_driver_heat, state.driverSeatHeating))
        if (state.driverSeatCooling > 0) add(resources.getString(R.string.climate_driver_cool, state.driverSeatCooling))
        if (state.passengerSeatHeating > 0) add(resources.getString(R.string.climate_passenger_heat, state.passengerSeatHeating))
        if (state.passengerSeatCooling > 0) add(resources.getString(R.string.climate_passenger_cool, state.passengerSeatCooling))
    }.joinToString("  •  ")

internal fun vehicleDoorWarning(resources: android.content.res.Resources, state: TeyesClimateState): String? {
    val open =
        buildList {
            if (state.hoodOpen) add(resources.getString(R.string.door_hood))
            if (state.frontLeftDoorOpen) add(resources.getString(R.string.door_driver))
            if (state.frontRightDoorOpen) add(resources.getString(R.string.door_passenger))
            if (state.rearLeftDoorOpen) add(resources.getString(R.string.door_rear_left))
            if (state.rearRightDoorOpen) add(resources.getString(R.string.door_rear_right))
            if (state.bootOpen) add(resources.getString(R.string.door_boot))
        }
    return open.takeIf { it.isNotEmpty() }?.let { resources.getString(R.string.door_open_warning, it.joinToString(" • ")) }
}

@Composable
private fun ClimateButton(onClick: () -> Unit) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = resources.getString(R.string.climate_open_controls) },
        contentPadding =
            PaddingValues(
                horizontal = AutomotiveDimens.ButtonPaddingHorizontal,
                vertical = AutomotiveDimens.ButtonPaddingVertical,
            ),
    ) {
        Text(
            text = "A/C",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}
