package com.cabin.telemetry

import android.app.Application
import android.content.Context
import android.os.Build
import com.cabin.BuildConfig
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.DebugMeta
import io.sentry.protocol.DebugImage
import io.sentry.protocol.Mechanism
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import java.io.File

/** Fixed vocabulary only: never send raw vendor messages or media/phone metadata. */
enum class DiagnosticEvent {
    JOYING_START, JOYING_RETRY, JOYING_EXHAUSTED, JOYING_STOP,
    PROJECTION_DISCONNECTED, PROJECTION_CONNECTING, PROJECTION_STREAMING, TEST_REPORT,
}

class CabinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CabinTelemetry.initialize(this)
    }
}

object CabinTelemetry {
    const val PREFERENCES = "cabin_reporting"
    private const val CATEGORY = "cabin.connection"
    @Volatile private var active = false
    val configured get() = BuildConfig.SENTRY_DSN.isNotBlank()
    fun enabled(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("enabled", false)

    @Synchronized
    fun initialize(context: Context) {
        if (active || !configured || !enabled(context)) return
        val cache = File(context.noBackupFilesDir, "sentry")
        try {
            SentryAndroid.init(context.applicationContext) { options ->
                options.dsn = BuildConfig.SENTRY_DSN
                options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                options.environment = if (BuildConfig.DEBUG) "development" else "production"
                options.isSendDefaultPii = false
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
                options.isEnableNdk = false
                options.isAttachAnrThreadDump = false
                options.isAttachRawTombstone = false
                options.isReportHistoricalAnrs = false
                options.isReportHistoricalTombstones = false
                options.isCollectAdditionalContext = false
                options.isEnableAutoSessionTracking = false
                options.logs.setEnabled(false)
                options.metrics.setEnabled(false)
                options.isEnableScopePersistence = false
                options.isEnableActivityLifecycleBreadcrumbs = false
                options.isEnableAppLifecycleBreadcrumbs = false
                options.isEnableSystemEventBreadcrumbs = false
                options.isEnableAppComponentBreadcrumbs = false
                options.isEnableNetworkEventBreadcrumbs = false
                options.isEnableAutoActivityLifecycleTracing = false
                options.isEnableScreenTracking = false
                options.tracesSampleRate = 0.0
                options.maxBreadcrumbs = 40
                options.maxCacheItems = 20
                options.cacheDirPath = cache.absolutePath
                options.shutdownTimeoutMillis = 0
                options.setBeforeBreadcrumb { crumb, _ -> cleanBreadcrumb(crumb) }
                options.setBeforeSend { event, _ -> if (enabled(context)) sanitize(event) else null }
            }
            active = true
        } catch (_: RuntimeException) {
            // Reporting must never prevent the launcher from starting.
            Sentry.close()
        }
    }

    @Synchronized
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", value && configured).commit()
        if (value && configured) initialize(context) else {
            active = false
            Sentry.close()
            File(context.noBackupFilesDir, "sentry").deleteRecursively()
        }
    }

    fun record(event: DiagnosticEvent, report: Boolean = false): Boolean {
        if (!active) return false
        Sentry.addBreadcrumb(Breadcrumb().apply { category = CATEGORY; message = event.name })
        return !report || Sentry.captureMessage(event.name, if (event == DiagnosticEvent.TEST_REPORT) SentryLevel.INFO else SentryLevel.WARNING) != io.sentry.protocol.SentryId.EMPTY_ID
    }

    internal fun cleanBreadcrumb(crumb: Breadcrumb): Breadcrumb? {
        if (crumb.category != CATEGORY || DiagnosticEvent.entries.none { it.name == crumb.message }) return null
        return Breadcrumb(crumb.timestamp).apply { category = CATEGORY; message = crumb.message }
    }

    /** Rebuild an allowlisted event; dropping fields is safer than matching sensitive strings. */
    internal fun sanitize(source: SentryEvent): SentryEvent = SentryEvent(source.timestamp).apply {
        eventId = source.eventId
        level = source.level
        release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
        environment = if (BuildConfig.DEBUG) "development" else "production"
        platform = "java"
        if (BuildConfig.SENTRY_MAPPING_UUID.isNotBlank()) debugMeta = DebugMeta().apply {
            images = listOf(DebugImage().apply { type = "proguard"; uuid = BuildConfig.SENTRY_MAPPING_UUID })
        }
        setTag("android_api", Build.VERSION.SDK_INT.toString())
        message = Message().apply {
            formatted = source.message?.formatted?.takeIf { name -> DiagnosticEvent.entries.any { it.name == name } }
                ?: "Cabin application error"
        }
        exceptions = source.exceptions?.map { original ->
            SentryException().apply {
                type = original.type
                module = original.module
                original.mechanism?.let { originalMechanism ->
                    mechanism = Mechanism().apply { type = "generic"; isHandled = originalMechanism.isHandled }
                }
                stacktrace = original.stacktrace?.let { stack ->
                    SentryStackTrace(stack.frames?.map { frame ->
                        SentryStackFrame().apply {
                            module = frame.module
                            function = frame.function
                            filename = frame.filename?.substringAfterLast('/')?.substringAfterLast('\\')
                            lineno = frame.lineno
                            isInApp = frame.isInApp
                        }
                    })
                }
            }
        }
        breadcrumbs = source.breadcrumbs?.mapNotNull(::cleanBreadcrumb)
    }
}
