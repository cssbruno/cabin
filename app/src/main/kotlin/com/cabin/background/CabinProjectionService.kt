package com.cabin.background

import com.cabin.localization.localizedString
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.cabin.CabinManager
import com.cabin.MainActivity
import com.cabin.R
import com.cabin.logging.logInfo
import com.cabin.logging.logWarn
import com.cabin.media.CabinMediaBrowserService
import com.cabin.media.MediaSessionManager
import com.cabin.platform.PlatformDetector
import com.cabin.protocol.AdapterConfig
import com.cabin.protocol.KnownDevices
import com.cabin.protocol.PhoneType
import com.cabin.ui.settings.AdapterConfigPreference
import com.cabin.ui.settings.DisplayMode
import com.cabin.ui.settings.DisplayModePreference
import com.cabin.ui.settings.MicSourceConfig
import com.cabin.ui.settings.WiFiBandConfig
import com.cabin.util.IconAssets
import com.cabin.util.LogCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Owns a projection session started from the launcher widget without opening an Activity.
 *
 * The service intentionally runs without a decoder Surface. CabinManager continues the
 * USB/audio/media pipeline and drops projection video frames until MainActivity attaches its
 * SurfaceView to the same service-owned manager. The service remains the owner so destroying
 * the Activity can return to headless operation without disconnecting USB.
 */
class CabinProjectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var manager: CabinManager? = null
    private var connectJob: Job? = null
    private var statusText = ""
    private var receiverRegistered = false
    private var sensitiveForegroundTypesAvailable = true
    private var stopping = false

    private val logCallback =
        object : LogCallback {
            override fun log(message: String) = logInfo(message, tag = "BACKGROUND")

            override fun log(tag: String, message: String) = logInfo(message, tag = tag)
        }

    private val managerCallback =
        object : CabinManager.Callback {
            override fun onStateChanged(state: CabinManager.State) {
                if (manager != null) refreshNotification()
            }

            override fun onStatusTextChanged(text: String) {
                statusText = text
                if (manager != null) refreshNotification()
            }

            override fun onHostUIPressed() {
                // Android background-launch restrictions prohibit silently opening the
                // Activity here. The ongoing notification remains the user-visible route.
            }

            override fun onPhoneTypeChanged(phoneType: PhoneType) {
                if (manager != null) refreshNotification()
            }
        }

    private val usbReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    if (com.cabin.BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE && shouldRunInBackground() &&
                        com.cabin.platform.TeyesFeaturePreferences.get(context).profile.value.resumeOnWake) {
                        val target = manager ?: return
                        serviceScope.launch { target.resumeRequestedSession() }
                    }
                    return
                }
                val device =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                if (device == null || !KnownDevices.isKnownDevice(device.vendorId, device.productId)) return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        manager?.takeIf { it.ownsUsbDevice(device) }?.onUsbDeviceDetached()
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (shouldRunInBackground()) connectIfNeeded()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        statusText = localizedString(R.string.background_preparing)
        synchronized(ownershipLock) { instance = this }
        createNotificationChannel()
        registerUsbReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopping) return START_NOT_STICKY
        // Always request the sensitive types when their runtime permissions exist. On a
        // sticky restart Android may reject while-in-use types; promoteToForeground then
        // degrades safely to connectedDevice instead of crashing.
        val foregroundResult = promoteToForeground(includeSensitiveTypes = true)
        if (foregroundResult == null) {
            setShouldRunInBackground(false)
            // Keep ownership visible until onDestroy. Its independent cleanup scope
            // survives serviceScope cancellation and preserves Activity-owned managers.
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        sensitiveForegroundTypesAvailable = foregroundResult
        manager?.setSensitiveBackgroundCapabilitiesAvailable(sensitiveForegroundTypesAvailable)
        when (intent?.action) {
            ACTION_STOP -> stopBackgroundSession()
            ACTION_CONNECT_PHONE -> {
                setShouldRunInBackground(true)
                connectIfNeeded(explicitPhoneConnect = true)
            }
            ACTION_CONNECT, null -> {
                if (intent?.action == ACTION_CONNECT || shouldRunInBackground()) {
                    setShouldRunInBackground(true)
                    connectIfNeeded()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver)
            receiverRegistered = false
        }
        serviceScope.cancel()
        val ownedManager = manager
        val activityOwnsManager = synchronized(ownershipLock) { activeActivityManager === ownedManager }
        manager = null
        synchronized(ownershipLock) {
            if (instance === this) instance = null
        }
        if (ownedManager != null && !activityOwnsManager) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                ownedManager.releaseAndWait()
                CabinMediaBrowserService.releaseRetainedSessionIfServiceStopped()
            }
        } else {
            CabinMediaBrowserService.releaseRetainedSessionIfServiceStopped()
        }
        super.onDestroy()
    }

    private fun connectIfNeeded(explicitPhoneConnect: Boolean = false) {
        if (stopping) return
        if (!explicitPhoneConnect && connectJob?.isActive == true) return
        val current = manager
        if (!explicitPhoneConnect && current != null && current.state != CabinManager.State.DISCONNECTED) return
        if (current == null) {
            val activityManager = synchronized(ownershipLock) { activeActivityManager }
            if (activityManager != null) {
                manager = activityManager
                logInfo("[BACKGROUND] Adopted Activity-owned projection manager", tag = "BACKGROUND")
            } else {
                val config = buildHeadlessConfig()
                val mediaSession =
                    if (config.audioTransferMode) {
                        MediaSessionManager.instance()?.setInactive()
                        null
                    } else {
                        MediaSessionManager.getOrCreate(applicationContext, logCallback).apply { initialize() }
                    }
                manager = CabinManager(applicationContext, config, mediaSession).also {
                    it.initializeHeadless(managerCallback)
                }
            }
        }
        val target = manager ?: return
        target.setSensitiveBackgroundCapabilitiesAvailable(sensitiveForegroundTypesAvailable)
        if (explicitPhoneConnect) {
            connectJob?.cancel()
            connectJob = target.connectPhone()
            return
        }
        connectJob = serviceScope.launch(Dispatchers.IO) {
            try {
                target.start()
            } catch (error: Exception) {
                logWarn("[BACKGROUND] Connection attempt failed: ${error.message}", tag = "BACKGROUND")
                withContext(Dispatchers.Main.immediate) { refreshNotification() }
            }
        }
    }

    private fun stopBackgroundSession() {
        serviceScope.launch { stopBackgroundSessionAndWait() }
    }

    private suspend fun stopBackgroundSessionAndWait(): CabinManager? = withContext(NonCancellable + Dispatchers.Main.immediate) {
        stopping = true
        setShouldRunInBackground(false)
        val ownedManager = manager
        manager = null
        connectJob?.cancelAndJoin()
        connectJob = null
        if (ownedManager != null) {
            val activityOwnsManager = synchronized(ownershipLock) { activeActivityManager === ownedManager }
            if (activityOwnsManager) {
                ownedManager.stopAndWait()
            } else {
                ownedManager.releaseAndWait()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ownedManager
    }

    private fun attachManagerToActivity(): CabinManager? {
        val result = manager ?: return null
        refreshNotification()
        return result
    }

    private fun returnManagerToBackground(candidate: CabinManager): Boolean {
        if (manager !== candidate) return false
        candidate.setSensitiveBackgroundCapabilitiesAvailable(sensitiveForegroundTypesAvailable)
        candidate.enterHeadlessMode(managerCallback)
        refreshNotification()
        return true
    }

    private suspend fun releaseManagerForReconfiguration(candidate: CabinManager): Boolean {
        if (manager !== candidate) return false
        connectJob?.cancelAndJoin()
        connectJob = null
        manager = null
        candidate.releaseAndWait()
        return true
    }

    private fun buildHeadlessConfig(): AdapterConfig {
        val userConfig = AdapterConfigPreference.getInstance(this).getUserConfigSync()
        val metrics = resources.displayMetrics
        val windowManager = getSystemService(WindowManager::class.java)
        val bounds =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds
            } else {
                @Suppress("DEPRECATION")
                android.graphics.Rect().also { windowManager.defaultDisplay.getRectSize(it) }
            }
        val displayMode = DisplayModePreference.getInstance(this).getDisplayModeSync()
        var safeTop = 0
        var safeBottom = 0
        var safeLeft = 0
        var safeRight = 0
        var detectedWidth = bounds.width()
        var detectedHeight = bounds.height()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowInsets = windowManager.currentWindowMetrics.windowInsets
            val systemBars = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            val cutout = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            when (displayMode) {
                DisplayMode.SYSTEM_UI_VISIBLE -> {
                    detectedWidth -= systemBars.left + systemBars.right + cutout.left + cutout.right
                    detectedHeight -= systemBars.top + systemBars.bottom + cutout.top + cutout.bottom
                }
                DisplayMode.STATUS_BAR_HIDDEN -> {
                    detectedWidth -= systemBars.left + systemBars.right
                    detectedHeight -= systemBars.bottom
                    safeTop = cutout.top
                    safeLeft = cutout.left
                    safeRight = cutout.right
                }
                DisplayMode.NAV_BAR_HIDDEN -> {
                    detectedHeight -= systemBars.top
                    safeBottom = cutout.bottom
                    safeLeft = cutout.left
                    safeRight = cutout.right
                }
                DisplayMode.FULLSCREEN_IMMERSIVE -> {
                    safeTop = cutout.top
                    safeBottom = cutout.bottom
                    safeLeft = cutout.left
                    safeRight = cutout.right
                }
            }
        }
        detectedWidth = detectedWidth.coerceAtLeast(2) and 1.inv()
        detectedHeight = detectedHeight.coerceAtLeast(2) and 1.inv()
        val userSelectedResolution = !userConfig.videoResolution.isAuto
        val displayPrefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val rememberedWidth = displayPrefs.getInt(KEY_DISPLAY_WIDTH, 0)
        val rememberedHeight = displayPrefs.getInt(KEY_DISPLAY_HEIGHT, 0)
        val useRememberedDisplay =
            !userSelectedResolution && rememberedWidth >= 2 && rememberedHeight >= 2
        val width =
            when {
                userSelectedResolution -> userConfig.videoResolution.width
                useRememberedDisplay -> rememberedWidth
                else -> detectedWidth
            }
        val height =
            when {
                userSelectedResolution -> userConfig.videoResolution.height
                useRememberedDisplay -> rememberedHeight
                else -> detectedHeight
            }
        if (userSelectedResolution) {
            val scaleX = width.toFloat() / detectedWidth.toFloat()
            val scaleY = height.toFloat() / detectedHeight.toFloat()
            safeTop = (safeTop * scaleY).toInt()
            safeBottom = (safeBottom * scaleY).toInt()
            safeLeft = (safeLeft * scaleX).toInt()
            safeRight = (safeRight * scaleX).toInt()
        }
        val (icon120, icon180, icon256) = IconAssets.loadIcons(this)
        val platformInfo = PlatformDetector.detect(this)
        val rememberedViewArea = decodeArea(displayPrefs.getString(KEY_VIEW_AREA, null), 24)
        val rememberedSafeArea = decodeArea(displayPrefs.getString(KEY_SAFE_AREA, null), 20)
        return AdapterConfig(
            width = width,
            height = height,
            fps = userConfig.fps.fps,
            dpi =
                if (useRememberedDisplay) {
                    displayPrefs.getInt(KEY_DISPLAY_DPI, metrics.densityDpi)
                } else {
                    metrics.densityDpi
                },
            userSelectedResolution = userSelectedResolution,
            icon120Data = icon120,
            icon180Data = icon180,
            icon256Data = icon256,
            gpsFixScriptData = loadAsset("aa_gps_fix.sh"),
            patchedIap2BinaryData = loadAsset("ARMiPhoneIAP2.patched"),
            oemIconVisible = !platformInfo.requiresImmersiveDefaults(),
            audioTransferMode = userConfig.audioTransferMode,
            sampleRate = 48000,
            micType =
                if (sensitiveForegroundTypesAvailable &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                    userConfig.micSource == MicSourceConfig.APP
                ) {
                    "os"
                } else {
                    "box"
                },
            wifiType = if (userConfig.wifiBand == WiFiBandConfig.BAND_5GHZ) "5ghz" else "24ghz",
            callQuality = userConfig.callQuality.value,
            mediaDelay = userConfig.mediaDelay.delayMs,
            handDriveMode = userConfig.handDrive.value,
            viewAreaData =
                if (useRememberedDisplay && rememberedViewArea != null) rememberedViewArea else buildViewArea(width, height),
            safeAreaData =
                if (useRememberedDisplay && rememberedSafeArea != null) {
                    rememberedSafeArea
                } else {
                    buildSafeArea(width, height, safeTop, safeBottom, safeLeft, safeRight)
                },
            gpsForwarding =
                userConfig.gpsForwarding && sensitiveForegroundTypesAvailable &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }

    private fun loadAsset(name: String): ByteArray? =
        try {
            assets.open(name).use { it.readBytes() }
        } catch (e: java.io.IOException) {
            logWarn("[BACKGROUND] Failed to load $name: ${e.message}", tag = "BACKGROUND")
            null
        }

    private fun decodeArea(encoded: String?, expectedSize: Int): ByteArray? =
        try {
            encoded?.let { Base64.decode(it, Base64.NO_WRAP) }?.takeIf { it.size == expectedSize }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun buildViewArea(width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(width).putInt(height)
            .putInt(width).putInt(height)
            .putInt(0).putInt(0)
            .array()

    private fun buildSafeArea(
        width: Int,
        height: Int,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
    ): ByteArray {
        val hasInsets = (top or bottom or left or right) != 0
        return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(width - left - right).putInt(height - top - bottom)
            .putInt(left).putInt(top).putInt(if (hasInsets) 1 else 0)
            .array()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            localizedString(R.string.background_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = localizedString(R.string.background_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cabin_attribution)
            .setContentTitle(localizedString(R.string.background_notification_title))
            .setContentText(statusText)
            .setContentIntent(openActivityPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, localizedString(R.string.background_stop), stopPendingIntent())
            .build()

    /** @return true for full types, false for connected-device fallback, null on total failure. */
    private fun promoteToForeground(includeSensitiveTypes: Boolean): Boolean? {
        var foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            includeSensitiveTypes &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            foregroundTypes = foregroundTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (includeSensitiveTypes &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            foregroundTypes = foregroundTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                foregroundTypes,
            )
            return true
        } catch (error: RuntimeException) {
            // A sticky restart is not a fresh user interaction, so Android may reject
            // while-in-use microphone/location types. Keep USB alive with the always-
            // permitted connected-device type instead of crashing the whole service.
            logWarn(
                "[BACKGROUND] Sensitive foreground types unavailable; using connectedDevice only: ${error.message}",
                tag = "BACKGROUND",
            )
            return try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                false
            } catch (fallbackError: RuntimeException) {
                logWarn(
                    "[BACKGROUND] Foreground promotion failed completely: ${fallbackError.message}",
                    tag = "BACKGROUND",
                )
                null
            }
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun refreshForegroundCapabilities() {
        val available = promoteToForeground(includeSensitiveTypes = true) ?: return
        sensitiveForegroundTypesAvailable = available
        manager?.setSensitiveBackgroundCapabilitiesAvailable(available)
    }

    private fun openActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stopPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, CabinProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun registerUsbReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun shouldRunInBackground(): Boolean =
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(KEY_SHOULD_RUN, false)

    private fun setShouldRunInBackground(value: Boolean) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit {
            putBoolean(KEY_SHOULD_RUN, value)
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.carlink.action.CONNECT_BACKGROUND"
        const val ACTION_CONNECT_PHONE = "com.carlink.action.CONNECT_PHONE"
        const val ACTION_STOP = "com.carlink.action.STOP_BACKGROUND"

        private const val NOTIFICATION_CHANNEL_ID = "carlink_background_connection"
        private const val NOTIFICATION_ID = 1002
        private const val PREFERENCES = "carlink_background_service"
        private const val KEY_SHOULD_RUN = "should_run"
        private const val KEY_DISPLAY_WIDTH = "last_display_width"
        private const val KEY_DISPLAY_HEIGHT = "last_display_height"
        private const val KEY_DISPLAY_DPI = "last_display_dpi"
        private const val KEY_VIEW_AREA = "last_view_area"
        private const val KEY_SAFE_AREA = "last_safe_area"
        private val ownershipLock = Any()

        @Volatile
        private var instance: CabinProjectionService? = null

        /** The single Activity-side manager eligible for service adoption. */
        @SuppressLint("StaticFieldLeak") // CabinManager canonicalizes its Context to applicationContext.
        @Volatile
        private var activeActivityManager: CabinManager? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CabinProjectionService::class.java).setAction(ACTION_CONNECT),
            )
        }

        /** Only user-facing Connect actions may resume an explicitly disconnected phone. */
        fun startPhoneConnection(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CabinProjectionService::class.java).setAction(ACTION_CONNECT_PHONE),
            )
        }

        /** Clear durable reconnect intent and finish teardown before the Activity closes. */
        suspend fun stopForAppExit(context: Context, activityManager: CabinManager) {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY_SHOULD_RUN, false)
                }
                val service = synchronized(ownershipLock) { instance }
                val stoppedManager = service?.stopBackgroundSessionAndWait()
                if (stoppedManager !== activityManager) activityManager.stopAndWait()
            }
        }

        /**
         * Upgrade an existing service after a runtime permission grant. Call on the
         * main thread while an Activity is visible; never creates or reconnects a session.
         */
        fun refreshForegroundCapabilitiesFromVisibleActivity() {
            synchronized(ownershipLock) { instance }?.refreshForegroundCapabilities()
        }

        /** Persist the Activity's actual display so a later cold widget start uses it. */
        fun rememberDisplayConfig(context: Context, config: AdapterConfig) {
            if (config.userSelectedResolution) return
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
                putInt(KEY_DISPLAY_WIDTH, config.width)
                putInt(KEY_DISPLAY_HEIGHT, config.height)
                putInt(KEY_DISPLAY_DPI, config.dpi)
                config.viewAreaData?.let { putString(KEY_VIEW_AREA, Base64.encodeToString(it, Base64.NO_WRAP)) }
                config.safeAreaData?.let { putString(KEY_SAFE_AREA, Base64.encodeToString(it, Base64.NO_WRAP)) }
            }
        }

        /** Share the service-owned live session with MainActivity without reconnecting USB. */
        fun takeRunningManager(): CabinManager? {
            return synchronized(ownershipLock) {
                instance?.attachManagerToActivity()
            }
        }

        /** Publish Activity ownership before a widget/service start can create another manager. */
        fun registerActivityManager(manager: CabinManager) {
            synchronized(ownershipLock) {
                activeActivityManager = manager
                val service = instance ?: return
                if (service.manager == null && !service.stopping) {
                    service.manager = manager
                    service.refreshNotification()
                }
            }
        }

        fun unregisterActivityManager(manager: CabinManager) {
            synchronized(ownershipLock) {
                if (activeActivityManager === manager) activeActivityManager = null
            }
        }

        fun hasActiveProcessSession(): Boolean = synchronized(ownershipLock) {
            activeActivityManager?.state == CabinManager.State.STREAMING ||
                instance?.manager?.state == CabinManager.State.STREAMING
        }

        /** True while any process manager is registered, including reconnect gaps. */
        fun hasRegisteredProcessManager(): Boolean = synchronized(ownershipLock) {
            activeActivityManager != null || instance?.manager != null
        }

        /** Return a shared manager to headless operation when its Activity is destroyed. */
        fun returnRunningManager(manager: CabinManager): Boolean {
            return synchronized(ownershipLock) {
                instance?.returnManagerToBackground(manager) == true
            }
        }

        /** Drop the old service-owned manager before a settings-driven full rebuild. */
        suspend fun releaseRunningManagerForReconfiguration(manager: CabinManager): Boolean {
            val service = synchronized(ownershipLock) { instance } ?: return false
            return service.releaseManagerForReconfiguration(manager)
        }

        /** True while this foreground service owns a USB projection manager. */
        fun hasRunningSession(): Boolean = synchronized(ownershipLock) { instance?.manager != null }
    }
}
