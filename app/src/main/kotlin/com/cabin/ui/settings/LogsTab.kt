package com.cabin.ui.settings

import com.cabin.R
import androidx.compose.ui.res.stringResource
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cabin.logging.FileExportService
import com.cabin.logging.FileLogManager
import com.cabin.logging.LogPreset
import com.cabin.logging.LoggingPreferences
import com.cabin.logging.apply
import com.cabin.logging.logError
import com.cabin.logging.logInfo
import com.cabin.logging.logWarn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Content for the Logs tab in SettingsScreen.
 * Provides controls for file logging, log level selection, and log file management.
 *
 * Known hazards (documented for future cleanup — do not silently fix):
 *  - LogLevelSelectorDialog uses a hardcoded 2x5 chip grid (L616-631). Adding an
 *    11th LogPreset silently drops it (no exhaustiveness check). Fix: drive from
 *    LogPreset.entries.chunked(5) or similar.
 *  - formatBytes vs formatFileSize are inconsistent (L938 vs L945) — see their docs.
 */
@Composable
internal fun LogsTabContent(
    context: android.content.Context,
    fileLogManager: FileLogManager?,
) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val filesStore = remember(fileLogManager) { fileLogManager?.let(::LogFilesStore) }
    var filesSnapshot by remember(fileLogManager) { mutableStateOf(LogFilesSnapshot()) }
    val logFiles = filesSnapshot.files
    var showDeleteDialog by remember { mutableStateOf<LogFileSnapshot?>(null) }
    var showLogLevelDialog by remember { mutableStateOf(false) }
    var showDebugWarningDialog by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    val createDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            val fileToExport = pendingExportFile
            if (uri != null && fileToExport != null) {
                scope.launch {
                    val result = FileExportService.writeFileToUri(context, uri, fileToExport)
                    result
                        // TODO(cleanup): bytesWritten is unused — rename to `_` in a future cleanup.
                        .onSuccess { bytesWritten ->
                            Toast.makeText(context, resources.getString(R.string.logs_exported, fileToExport.name), Toast.LENGTH_SHORT).show()
                        }.onFailure { error ->
                            logError("[FILE_EXPORT] Export failed: ${error.message}", tag = "FILE_LOG")
                            Toast.makeText(context, resources.getString(R.string.logs_export_failed, error.message.orEmpty()), Toast.LENGTH_SHORT).show()
                        }
                    pendingExportFile = null
                    isExporting = false
                }
            } else {
                logInfo("[FILE_EXPORT] User cancelled document picker", tag = "FILE_LOG")
                pendingExportFile = null
                isExporting = false
            }
        }

    val loggingPreferences = remember { LoggingPreferences.getInstance(context) }
    val isLoggingEnabled by loggingPreferences.loggingEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val currentLogLevel by loggingPreferences.logLevelFlow.collectAsStateWithLifecycle(initialValue = LogPreset.SILENT)

    // remember{} with no key is correct: the debuggable flag is immutable for the process lifetime.
    val isDebugBuild =
        remember {
            try {
                val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            } catch (e: PackageManager.NameNotFoundException) {
                logWarn("[SettingsScreen] Failed to check debug build status: ${e.message}")
                false
            }
        }

    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Refresh rotations and sizes at a bounded rate while visible. Composition reads
    // only this immutable snapshot; directory scans and writer locks stay on IO.
    LaunchedEffect(filesStore, lifecycle) {
        val store = filesStore ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                filesSnapshot = store.snapshot()
                delay(5_000)
            }
        }
    }

    if (filesStore == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.logs_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                color = colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Responsive max width - 75% of container width, clamped between 400dp and 1200dp
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val maxContentWidth = (containerWidthDp * 0.75f).coerceIn(400.dp, 1200.dp)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            LoggingControlCard(
                title = stringResource(R.string.logs_logging),
                icon = Icons.AutoMirrored.Filled.Article,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.logs_save),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.logs_save_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isLoggingEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                if (enabled && isDebugBuild) {
                                    showDebugWarningDialog = true
                                } else {
                                    loggingPreferences.setLoggingEnabled(enabled)
                                    filesSnapshot = filesStore.setEnabled(enabled)
                                }
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column {
                    Text(
                        text = stringResource(R.string.logs_level),
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        onClick = { showLogLevelDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, colorScheme.outline),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentLogLevel.displayName,
                                    style =
                                        MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    color = currentLogLevel.color,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = currentLogLevel.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                val totalSize = filesSnapshot.totalSize
                val currentFileSize = filesSnapshot.currentFileSize

                if (isLoggingEnabled || logFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainerHighest,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.logs_status),
                                style =
                                    MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                color = colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FileLoggingStatusRow(
                                label = stringResource(R.string.logs_logging_label),
                                value = if (isLoggingEnabled) stringResource(R.string.state_enabled) else stringResource(R.string.state_disabled),
                            )
                            FileLoggingStatusRow(
                                label = stringResource(R.string.logs_files_label),
                                value = logFiles.size.toString(),
                            )
                            FileLoggingStatusRow(
                                label = stringResource(R.string.logs_size_label),
                                value = formatBytes(totalSize),
                            )
                            if (isLoggingEnabled && currentFileSize > 0) {
                                FileLoggingStatusRow(
                                    label = stringResource(R.string.logs_current_label),
                                    value = formatBytes(currentFileSize),
                                )
                            }
                        }
                    }
                }
            }

            // Log Files Card
            if (logFiles.isNotEmpty()) {
                LoggingControlCard(
                    title = stringResource(R.string.logs_files),
                    icon = Icons.Default.Folder,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        logFiles.forEach { file ->
                            LogFileItem(
                                file = file,
                                dateFormat = dateFormat,
                                isExportEnabled = !isExporting,
                                onDelete = { showDeleteDialog = file },
                                onExport = {
                                    if (isExporting) return@LogFileItem
                                    pendingExportFile = file.file
                                    isExporting = true
                                    scope.launch {
                                        try {
                                            if (filesStore.prepareExport(file.file)) {
                                                createDocumentLauncher.launch(file.file.name)
                                            } else {
                                                Toast.makeText(context, resources.getString(R.string.logs_missing), Toast.LENGTH_SHORT).show()
                                                filesSnapshot = filesStore.snapshot()
                                                pendingExportFile = null
                                                isExporting = false
                                            }
                                        } catch (e: Exception) {
                                            logError(
                                                "[FILE_EXPORT] Failed to launch document picker: ${e.message}",
                                                tag = "FILE_LOG",
                                            )
                                            Toast.makeText(context, resources.getString(R.string.logs_picker_unavailable), Toast.LENGTH_SHORT).show()
                                            pendingExportFile = null
                                            isExporting = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Log Level Selector Dialog
    if (showLogLevelDialog) {
        LogLevelSelectorDialog(
            currentLevel = currentLogLevel,
            onDismiss = { showLogLevelDialog = false },
            onSelectLevel = { preset ->
                scope.launch {
                    loggingPreferences.setLogLevel(preset)
                    preset.apply()
                    showLogLevelDialog = false
                }
            },
        )
    }

    // Debug Build Warning Dialog
    if (showDebugWarningDialog) {
        AlertDialog(
            onDismissRequest = { showDebugWarningDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.logs_debug_warning)) },
            text = {
                Text(
                    stringResource(R.string.logs_debug_detail),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            loggingPreferences.setLoggingEnabled(true)
                            filesSnapshot = filesStore.setEnabled(true)
                            showDebugWarningDialog = false
                        }
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = colorScheme.error,
                        ),
                ) {
                    Text(stringResource(R.string.logs_enable_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDebugWarningDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Delete Confirmation Dialog
    showDeleteDialog?.let { file ->
        val fileSizeStr = formatFileSize(file.size)

        Dialog(onDismissRequest = { showDeleteDialog = null }) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = colorScheme.error,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.logs_delete_title),
                            style =
                                MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainerHighest,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(R.string.logs_delete_count),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.logs_one_file),
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                        ),
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(R.string.logs_size_label),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = fileSizeStr,
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                        ),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // File name box
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = file.file.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.errorContainer.copy(alpha = 0.4f),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.logs_permanent),
                                    style =
                                        MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    color = colorScheme.onErrorContainer,
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text =
                                    stringResource(R.string.logs_delete_detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onErrorContainer,
                                lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.4,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            onClick = { showDeleteDialog = null },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Button(
                            onClick = {
                                showDeleteDialog = null
                                scope.launch { filesSnapshot = filesStore.delete(file.file) }
                            },
                            modifier = Modifier.weight(2f),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = colorScheme.error,
                                    contentColor = colorScheme.onError,
                                ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.logs_delete_file))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modal dialog presenting available [LogPreset]s as a 2-column chip grid.
 *
 * HAZARD: the column lists below are hand-maintained against the 10 current
 * LogPreset values. Adding an 11th preset will silently drop it from the UI —
 * there is no compile-time exhaustiveness check (a `when` over LogPreset would
 * fail; two hardcoded lists will not). Fix: drive the grid from
 * `LogPreset.entries.chunked(5)` (or chunked(ceil(n/2))) so new presets are
 * picked up automatically.
 */
@Composable
private fun LogLevelSelectorDialog(
    currentLevel: LogPreset,
    onDismiss: () -> Unit,
    onSelectLevel: (LogPreset) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    // WARNING: hand-enumerated — must be kept in sync with LogPreset. See KDoc above.
    val leftColumn =
        listOf(
            LogPreset.SILENT,
            LogPreset.MINIMAL,
            LogPreset.NORMAL,
            LogPreset.DEBUG,
            LogPreset.PERFORMANCE,
        )
    val rightColumn =
        listOf(
            LogPreset.RX_MESSAGES,
            LogPreset.VIDEO_ONLY,
            LogPreset.AUDIO_ONLY,
            LogPreset.PIPELINE_DEBUG,
            LogPreset.CLUSTER_MEDIA,
        )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(28.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.logs_select_level),
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Two columns with specific preset order
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Left column
                    Column(modifier = Modifier.weight(1f)) {
                        leftColumn.forEach { preset ->
                            LogPresetChip(
                                preset = preset,
                                isSelected = preset == currentLevel,
                                onClick = { onSelectLevel(preset) },
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    // Right column
                    Column(modifier = Modifier.weight(1f)) {
                        rightColumn.forEach { preset ->
                            LogPresetChip(
                                preset = preset,
                                isSelected = preset == currentLevel,
                                onClick = { onSelectLevel(preset) },
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Cancel button aligned right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

/**
 * Single selectable chip inside [LogLevelSelectorDialog].
 * Displays the preset's display name, description, and color-coded selection state.
 */
@Composable
private fun LogPresetChip(
    preset: LogPreset,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) preset.color.copy(alpha = 0.15f) else colorScheme.surfaceContainerHighest,
        border =
            BorderStroke(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) preset.color else colorScheme.outline,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .then(
                            if (isSelected) {
                                Modifier.background(preset.color, shape = CircleShape)
                            } else {
                                Modifier.background(Color.Transparent, shape = CircleShape)
                            },
                        ).border(
                            width = 2.dp,
                            color = preset.color,
                            shape = CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = colorScheme.surface,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.displayName,
                    style =
                        MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    color = preset.color,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Material 3 card container used as a section wrapper inside the Logs tab.
 * Renders an icon + title header followed by caller-provided [content] in a
 * padded column. Used for both the stringResource(R.string.logs_logging) and stringResource(R.string.logs_files) sections.
 */
@Composable
private fun LoggingControlCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
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
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            content()
        }
    }
}

/**
 * Single row in the Log Files list. Shows filename, size + last-modified timestamp,
 * and export/delete icon buttons. `isExportEnabled` is the soft gate driven by
 * the parent's `isExporting` flag — see race note at the call site.
 */
@Composable
private fun LogFileItem(
    file: LogFileSnapshot,
    dateFormat: SimpleDateFormat,
    isExportEnabled: Boolean = true,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.file.name,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatFileSize(file.size)} • ${dateFormat.format(Date(file.modifiedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = onExport,
                enabled = isExportEnabled,
            ) {
                Icon(
                    imageVector = Icons.Default.SaveAlt,
                    contentDescription = stringResource(R.string.action_export),
                    tint = if (isExportEnabled) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.size(24.dp),
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * Label/value row used in the Status panel (logging state, file count, sizes).
 * Spaces label and value to opposite ends of the available width.
 */
@Composable
private fun FileLoggingStatusRow(
    label: String,
    value: String,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                ),
        )
    }
}

// ==================== UTILITY FUNCTIONS ====================

/** Human-readable size with unit auto-selected (B / KB / MB). Preferred formatter. */
private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

/**
 * Legacy: always renders in KB regardless of magnitude (e.g. a 5 MB file reads as
 * "5120.0 KB"). Kept only to avoid a UX diff at call sites L428 and L875.
 *
 * TODO(cleanup): consolidate on [formatBytes] and delete this function.
 */
private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return "${String.format(Locale.US, "%.1f", kb)} KB"
}
