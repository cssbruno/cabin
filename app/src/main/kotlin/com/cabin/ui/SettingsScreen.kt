package com.cabin.ui

import com.cabin.R
import androidx.compose.ui.res.stringResource
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.CabinManager
import com.cabin.logging.FileLogManager
import com.cabin.logging.logInfo
import com.cabin.logging.logWarn
import com.cabin.ui.components.LoadingSpinner
import com.cabin.ui.settings.AdapterConfigPreference
import com.cabin.ui.settings.AdapterConfigurationDialog
import com.cabin.ui.settings.DisplayMode
import com.cabin.ui.settings.DisplayModeDialog
import com.cabin.ui.settings.DisplayModePreference
import com.cabin.ui.settings.LogsTabContent
import com.cabin.ui.settings.PhonesTabContent
import com.cabin.ui.settings.ProjectionPreferencesSection
import com.cabin.ui.settings.SettingsNotice
import com.cabin.ui.settings.SettingsTab
import com.cabin.ui.settings.canResetAndroidClusterHost
import com.cabin.ui.settings.launchSettingsRestart
import com.cabin.ui.settings.settingsControlColumns
import com.cabin.ui.settings.useHorizontalSettingsNavigation
import com.cabin.ui.theme.AutomotiveDimens
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adaptive settings: controls remain reachable on short head units and narrow split screens. */
@Composable
fun SettingsScreen(
    cabinManager: CabinManager,
    fileLogManager: FileLogManager?,
    onNavigateBack: () -> Unit,
    onResetCluster: () -> Unit,
    onReinitForDisplayMode: (DisplayMode) -> Unit = {},
    initialTab: SettingsTab = SettingsTab.PHONES,
    vehicleState: com.cabin.platform.TeyesClimateState = com.cabin.platform.TeyesClimateState(),
    embedded: Boolean = false,
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab.takeIf { it in SettingsTab.visible } ?: SettingsTab.PHONES) }
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var showCloseConfirm by rememberSaveable { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    // Log settings screen entry and tab changes
    LaunchedEffect(Unit) {
        logInfo(
            "[UI_STATE] SettingsScreen opened" +
                " - user is in app settings (NOT viewing CarPlay projection)",
            tag = "UI",
        )
    }

    LaunchedEffect(selectedTab) {
        logInfo("[UI_STATE] Settings tab changed: $selectedTab", tag = "UI")
    }

    // Get app version
    val appVersion =
        remember {
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                "${packageInfo.versionName}+${PackageInfoCompat.getLongVersionCode(packageInfo)}"
            } catch (e: PackageManager.NameNotFoundException) {
                // NOTE: inconsistent log tag — all other logWarn/logInfo sites in this file pass
                // tag = "UI". This call omits it and falls back to the default tag. Flagged for
                // a future fix; left as-is to keep this pass comment-only.
                logWarn("[SettingsScreen] Failed to get package info: ${e.message}")
                resources.getString(R.string.state_unknown)
            }
        }

    val view = LocalView.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorScheme.surfaceContainerLowest,
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (embedded) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),
        ) {
            val horizontalNavigation = useHorizontalSettingsNavigation(maxWidth.value)
            val navigateBack = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onNavigateBack()
            }
            val selectTab: (SettingsTab) -> Unit = { tab ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                selectedTab = tab
            }
            val content: @Composable () -> Unit = {
                when (selectedTab) {
                    SettingsTab.CONTROL -> ControlTabContent(cabinManager, onResetCluster, onReinitForDisplayMode)
                    SettingsTab.PHONES -> com.cabin.ui.settings.CarPlaySettingsContent(cabinManager)
                    SettingsTab.LOGS -> LogsTabContent(context, fileLogManager)
                    SettingsTab.TEYES -> com.cabin.ui.settings.TeyesFeaturesScreen(cabinManager, vehicleState)
                }
            }
            if (horizontalNavigation) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = navigateBack, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back_cabin))
                        }
                        Text(stringResource(R.string.action_settings), Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { showCloseConfirm = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.settings_exit_app), color = colorScheme.error) }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsTab.visible.forEach { tab ->
                            FilterChip(
                                selected = selectedTab == tab,
                                onClick = { selectTab(tab) },
                                label = { Text(stringResource(tab.title)) },
                                leadingIcon = { Icon(tab.icon, null, Modifier.size(20.dp)) },
                                modifier = Modifier.heightIn(min = 56.dp),
                            )
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    Surface(color = colorScheme.surfaceContainerLow, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), modifier = Modifier.width(152.dp).fillMaxHeight().padding(8.dp)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = navigateBack, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                                Text(stringResource(R.string.action_back), Modifier.padding(start = 10.dp))
                            }
                            Column(
                                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.settings_heading),
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colorScheme.onSurfaceVariant,
                                )
                                SettingsTab.visible.forEach { tab ->
                                    val selected = selectedTab == tab
                                    Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                                        color = if (selected) colorScheme.primaryContainer else colorScheme.surfaceContainerLow,
                                        contentColor = if (selected) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
                                    ) {
                                        Column(
                                            Modifier.fillMaxWidth().selectable(selected, role = Role.Tab, onClick = { selectTab(tab) }).heightIn(min = 72.dp).padding(8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                                        ) {
                                            Icon(tab.icon, null, Modifier.size(24.dp))
                                            Text(stringResource(tab.title), style = MaterialTheme.typography.labelLarge,
                                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = { showCloseConfirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Icon(Icons.Default.Close, null, tint = colorScheme.error)
                                Text(stringResource(R.string.settings_exit_app), Modifier.padding(start = 10.dp), color = colorScheme.error)
                            }
                            Text("v$appVersion", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant)
                        }
                    }
                    Box(Modifier.fillMaxHeight().weight(1f)) { content() }
                }
            }
        }
    }
    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { if (!closing) showCloseConfirm = false },
            title = { Text(stringResource(R.string.settings_exit_confirm)) },
            text = { Text(if (closing) stringResource(R.string.settings_stopping) else stringResource(R.string.settings_exit_detail)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        closing = true
                        logWarn("[UI_ACTION] Close App confirmed", tag = "UI")
                        scope.launch {
                            withContext(NonCancellable) {
                                com.cabin.background.CabinProjectionService.stopForAppExit(context, cabinManager)
                                (context as? android.app.Activity)?.finishAffinity()
                            }
                        }
                    },
                    enabled = !closing,
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.settings_stop_exit), color = colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }, enabled = !closing, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.settings_keep_running)) }
            },
        )
    }
}

/**
 * Visual severity class for [ControlButton]. Drives container/content colors only — does not
 * affect behavior or confirmation flow (callers still own any "are you sure?" dialogs).
 *
 * NOTE: the `when(severity)` blocks in [ControlButton] (L631+) are intentionally non-exhaustive
 * on a semantic level but exhaustive on this enum (only two values). Adding a new severity
 * requires extending both `when` arms.
 */
private enum class ButtonSeverity {
    WARNING, // Warning action (tertiary/amber)
    DESTRUCTIVE, // Destructive action (error/red)
}

/** Adapter settings and recovery actions, arranged for the actual available content width. */
@Composable
private fun ControlTabContent(
    cabinManager: CabinManager,
    onResetCluster: () -> Unit,
    onReinitForDisplayMode: (DisplayMode) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val colorScheme = MaterialTheme.colorScheme
    var processingAction by remember { mutableStateOf<String?>(null) }
    val isProcessing = processingAction != null
    var actionStatus by remember { mutableStateOf("") }
    var showResetClusterDialog by remember { mutableStateOf(false) }
    var showClusterNavOffDialog by remember { mutableStateOf(false) }
    val projection by cabinManager.dashboardState.collectAsStateWithLifecycle()
    val isDeviceConnected = projection.connection != CabinManager.State.DISCONNECTED
    val clusterHostAvailable =
        remember(context) {
            canResetAndroidClusterHost(
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
                context.packageManager.hasSystemFeature("android.software.car.templates_host"),
            )
        }

    val displayModePreference = remember { DisplayModePreference.getInstance(context) }
    // Initial value is the platform-aware default (gminfo37 + AAOS emulator →
    // FULLSCREEN_IMMERSIVE; else SYSTEM_UI_VISIBLE). Flow will overwrite with any
    // persisted user choice on the first emission.
    val displayModeInitial = remember { DisplayMode.platformDefault(context) }
    val currentDisplayMode by displayModePreference.displayModeFlow.collectAsStateWithLifecycle(
        initialValue = displayModeInitial,
    )
    var showDisplayModeDialog by remember { mutableStateOf(false) }

    val adapterConfigPreference = remember { AdapterConfigPreference.getInstance(context) }
    var showAdapterConfigDialog by remember { mutableStateOf(false) }
    val clusterNavigationEnabled by adapterConfigPreference.clusterNavigationFlow.collectAsStateWithLifecycle(
        initialValue = adapterConfigPreference.getClusterNavigationSync(),
    )

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val cardColumns = settingsControlColumns(maxWidth.value, LocalDensity.current.fontScale)
        Column(
            modifier =
                Modifier
                    .widthIn(max = 1200.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (actionStatus.isNotEmpty()) SettingsNotice(actionStatus)
            com.cabin.updates.UpdateSettingsSection()
            com.cabin.ui.settings.LanguageSettingsSection()
            com.cabin.ui.settings.MeasurementSettingsSection()
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                maxItemsInEachRow = cardColumns,
            ) {
                // Adapter Configuration Card (includes device control actions)
                ControlCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.label_connection),
                    icon = Icons.Default.SettingsInputComponent,
                ) {
                    // Configure button
                    FilledTonalButton(
                        onClick = { showAdapterConfigDialog = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = AutomotiveDimens.ButtonMinHeight),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_configure_adapter),
                            modifier = Modifier.size(AutomotiveDimens.IconSize),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_configure),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ControlButton(
                        label = stringResource(R.string.settings_disconnect_phone),
                        icon = Icons.Default.PhoneDisabled,
                        severity = ButtonSeverity.WARNING,
                        enabled = isDeviceConnected && !isProcessing,
                        isProcessing = false,
                        onClick = {
                            logWarn("[UI_ACTION] Disconnect Phone button clicked", tag = "UI")
                            cabinManager.disconnectPhone()
                            actionStatus = resources.getString(R.string.settings_phone_disconnect_requested)
                        },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ControlButton(
                        label = stringResource(R.string.settings_disconnect_adapter),
                        icon = Icons.Default.PowerOff,
                        severity = ButtonSeverity.DESTRUCTIVE,
                        enabled = isDeviceConnected && !isProcessing,
                        isProcessing = processingAction == "disconnect",
                        onClick = {
                            logWarn("[UI_ACTION] Disconnect Adapter button clicked", tag = "UI")
                            processingAction = "disconnect"
                            scope.launch {
                                try {
                                    cabinManager.stopAndWait()
                                    actionStatus = resources.getString(R.string.settings_adapter_stopped)
                                } finally {
                                    processingAction = null
                                }
                            }
                        },
                    )
                }

                // App Control Card
                ControlCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.settings_display_recovery),
                    icon = Icons.Default.DisplaySettings,
                ) {
                    FilledTonalButton(
                        onClick = { showDisplayModeDialog = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = AutomotiveDimens.ButtonMinHeight),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector =
                                when (currentDisplayMode) {
                                    DisplayMode.SYSTEM_UI_VISIBLE -> Icons.Default.FullscreenExit
                                    DisplayMode.STATUS_BAR_HIDDEN -> Icons.Default.Layers
                                    DisplayMode.NAV_BAR_HIDDEN -> Icons.Default.WebAsset
                                    DisplayMode.FULLSCREEN_IMMERSIVE -> Icons.Default.Fullscreen
                                },
                            contentDescription = stringResource(R.string.settings_configure_display),
                            modifier = Modifier.size(AutomotiveDimens.IconSize),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.label_display_mode),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ControlButton(
                            label = stringResource(R.string.settings_reset_decoder),
                            icon = Icons.Default.VideoSettings,
                            severity = ButtonSeverity.WARNING,
                            enabled = !isProcessing,
                            isProcessing = false,
                            onClick = {
                                logWarn("[UI_ACTION] Reset Decoder button clicked", tag = "UI")
                                actionStatus =
                                    when (cabinManager.resetVideoDecoder()) {
                                        CabinManager.VideoResetResult.REQUESTED -> resources.getString(R.string.settings_video_requested)
                                        CabinManager.VideoResetResult.QUEUED -> resources.getString(R.string.settings_video_queued)
                                        CabinManager.VideoResetResult.UNAVAILABLE -> resources.getString(R.string.settings_video_unavailable)
                                    }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        ControlButton(
                            label = stringResource(R.string.settings_reset_cluster),
                            icon = Icons.Default.Speed,
                            severity = ButtonSeverity.WARNING,
                            enabled = clusterHostAvailable && !isProcessing,
                            isProcessing = false,
                            onClick = {
                                if (clusterNavigationEnabled) {
                                    showResetClusterDialog = true
                                } else {
                                    showClusterNavOffDialog = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (!clusterHostAvailable) {
                        Text(
                            stringResource(R.string.settings_cluster_unavailable),
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ControlButton(
                        label = stringResource(R.string.settings_reset_connection),
                        icon = Icons.Default.Usb,
                        severity = ButtonSeverity.DESTRUCTIVE,
                        enabled = isDeviceConnected && !isProcessing,
                        isProcessing = processingAction == "restart",
                        onClick = {
                            logWarn("[UI_ACTION] Reset Connection button clicked", tag = "UI")
                            processingAction = "restart"
                            launchSettingsRestart(
                                scope = scope,
                                restart = {
                                    cabinManager.restart()
                                    actionStatus = resources.getString(R.string.settings_connection_requested)
                                },
                                onFinished = { processingAction = null },
                            )
                        },
                    )
                }
            }
        }
    }

    // Reset Cluster Confirmation Dialog
    if (showResetClusterDialog) {
        AlertDialog(
            onDismissRequest = { showResetClusterDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = colorScheme.tertiary,
                )
            },
            title = { Text(stringResource(R.string.settings_cluster_confirm)) },
            text = {
                Text(stringResource(R.string.settings_cluster_detail))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        logWarn("[UI_ACTION] Reset Cluster confirmed", tag = "UI")
                        showResetClusterDialog = false
                        onResetCluster()
                    },
                ) {
                    Text(stringResource(R.string.action_reset), color = colorScheme.tertiary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetClusterDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Cluster Navigation Off Info Dialog
    if (showClusterNavOffDialog) {
        AlertDialog(
            onDismissRequest = { showClusterNavOffDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                )
            },
            title = { Text(stringResource(R.string.settings_cluster_nav_off)) },
            text = {
                Text(stringResource(R.string.settings_cluster_nav_off_detail))
            },
            confirmButton = {
                TextButton(onClick = { showClusterNavOffDialog = false }) {
                    Text("OK")
                }
            },
        )
    }

    // Adapter Configuration Dialog
    if (showAdapterConfigDialog) {
        AdapterConfigurationDialog(
            adapterConfigPreference = adapterConfigPreference,
            cabinManager = cabinManager,
            currentDisplayMode = currentDisplayMode,
            onDismiss = { showAdapterConfigDialog = false },
            onReinitAdapter = {
                showAdapterConfigDialog = false
                onReinitForDisplayMode(currentDisplayMode)
            },
        )
    }

    // Display Mode Dialog with live preview
    if (showDisplayModeDialog) {
        DisplayModeDialog(
            displayModePreference = displayModePreference,
            onDismiss = { showDisplayModeDialog = false },
            onApplyAndRestart = { newMode ->
                showDisplayModeDialog = false
                scope.launch {
                    // Save the new display mode preference
                    displayModePreference.setDisplayMode(newMode)
                    logInfo(
                        "[DISPLAY_REINIT] User applied display mode: ${newMode.name} — " +
                            "reinitializing in-place (no app kill)",
                        tag = "UI",
                    )
                    // Tier-2 session restart: tear down adapter, apply new mode,
                    // recalculate resolution, rebuild CabinManager.
                    // Replaces the old Process.killProcess() approach.
                    onReinitForDisplayMode(newMode)
                }
            },
        )
    }
}

/**
 * Material 3 elevated card used as a titled container for groups of control actions
 * (e.g. stringResource(R.string.adapter_configuration), "App Control").
 *
 * Renders a leading [icon] + [title] header row, a fixed-height spacer, and then the caller's
 * [content] in a [ColumnScope]. Callers are responsible for laying out their own buttons and
 * inter-button spacing inside [content]. Does not manage any state of its own.
 */
@Composable
private fun ControlCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            content()
        }
    }
}

/**
 * Material 3 action button used inside a [ControlCard]. Swaps its leading icon for a
 * [LoadingSpinner] while [isProcessing] is true, with a fade+scale transition.
 *
 * Colors are picked from [severity]: DESTRUCTIVE uses error container (solid [Button]);
 * WARNING uses tertiary container ([FilledTonalButton]). The button fills the available
 * width and uses [AutomotiveDimens.ButtonMinHeight] to stay within the automotive
 * touch-target spec.
 *
 * Caller contract:
 *   - [enabled] should already factor in any app-level gating (connection state, etc.);
 *     see the redundancy note at the `enabled = enabled && !isProcessing` call sites.
 *   - [isProcessing] controls only the visual swap — this composable does not perform
 *     the work or manage the flag's lifecycle.
 */
@Composable
private fun ControlButton(
    label: String,
    icon: ImageVector,
    severity: ButtonSeverity,
    enabled: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    when (severity) {
        ButtonSeverity.DESTRUCTIVE -> {
            Button(
                onClick = onClick,
                // Redundant-but-intentional: every call site already passes
                // `enabled = X && !isProcessing`. Kept as belt-and-suspenders so the
                // composable is safe against future callers that forget the gate.
                enabled = enabled && !isProcessing,
                modifier =
                    modifier
                        .fillMaxWidth()
                        .heightIn(min = AutomotiveDimens.ButtonMinHeight),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError,
                    ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                AnimatedContent(
                    targetState = isProcessing,
                    transitionSpec = {
                        (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                    },
                    label = "iconTransition",
                ) { processing ->
                    if (processing) {
                        LoadingSpinner(
                            size = 24.dp,
                            color = colorScheme.onError,
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ButtonSeverity.WARNING -> {
            FilledTonalButton(
                onClick = onClick,
                // Redundant-but-intentional: see note on the DESTRUCTIVE branch above.
                enabled = enabled && !isProcessing,
                modifier =
                    modifier
                        .fillMaxWidth()
                        .heightIn(min = AutomotiveDimens.ButtonMinHeight),
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.tertiaryContainer,
                        contentColor = colorScheme.onTertiaryContainer,
                    ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                AnimatedContent(
                    targetState = isProcessing,
                    transitionSpec = {
                        (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                    },
                    label = "iconTransition",
                ) { processing ->
                    if (processing) {
                        LoadingSpinner(
                            size = 24.dp,
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
