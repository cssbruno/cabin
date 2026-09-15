package com.cabin.telemetry

import android.app.Application
import android.content.Context
import android.os.Build
import com.cabin.BuildConfig
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import com.cabin.logging.Logger
import io.sentry.SentryLogEvent
import io.sentry.SentryLogLevel
import io.sentry.protocol.SentryThread
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
    FYT_BIND_REJECTED, FYT_BIND_DENIED, FYT_BIND_FAILED, FYT_MODULE_UNAVAILABLE, FYT_MODULE_FAILED,
    JOYING_START, JOYING_RETRY, JOYING_EXHAUSTED, JOYING_STOP,
    PROJECTION_DISCONNECTED, PROJECTION_CONNECTING, PROJECTION_STREAMING, TEST_REPORT,
}

class CabinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.cabin.reports.DebugJournal.setObserver { CabinTelemetry.log(Logger.Level.DEBUG, null) }
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
                configureStackCapture(options)
                options.isSendDefaultPii = false
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
                options.isEnableNdk = true
                options.isEnableScopeSync = true
                options.isDebug = BuildConfig.DEBUG
                options.setDiagnosticLevel(SentryLevel.DEBUG)
                options.maxAttachmentSize = 0
                options.isAttachAnrThreadDump = false
                options.isAttachRawTombstone = false
                options.isReportHistoricalAnrs = false
                options.isReportHistoricalTombstones = false
                options.isCollectAdditionalContext = false
                options.isEnableAutoSessionTracking = false
                options.logs.setEnabled(true)
                options.logs.setBeforeSend { log -> if (enabled(context)) cleanLog(log) else null }
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
                options.setBeforeSend { event, hint ->
                    hint.clearAttachments()
                    if (enabled(context)) sanitize(event) else null
                }
            }
            active = true
        } catch (_: RuntimeException) {
            active = false
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
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

    internal fun configureStackCapture(options: io.sentry.SentryOptions) {
        // Message reports describe state, not a failure at the reporting call site.
        // Throwable stacks and the SDK's forced ANR thread capture are unaffected.
        options.isAttachStacktrace = false
        options.isAttachThreads = false
    }

    fun record(event: DiagnosticEvent, report: Boolean = false): Boolean {
        if (!active) return false
        Sentry.addBreadcrumb(Breadcrumb().apply { category = CATEGORY; message = event.name })
        Sentry.logger().info("Cabin event ${event.name}")
        return !report || Sentry.captureMessage(event.name, if (event == DiagnosticEvent.TEST_REPORT) SentryLevel.INFO else SentryLevel.WARNING) != io.sentry.protocol.SentryId.EMPTY_ID
    }

    // Rate-limit before inspecting the stack. No raw message or vendor tag crosses this boundary.
    private val logBudget = TelemetryBudget(120)
    private val errorBudget = TelemetryBudget(10)
    fun log(logLevel: Logger.Level, throwable: Throwable?) {
        if (!active) return
        try {
            val sendLog = logBudget.take(android.os.SystemClock.elapsedRealtime())
            val sendError = throwable != null && throwable !is java.util.concurrent.CancellationException &&
                errorBudget.take(android.os.SystemClock.elapsedRealtime())
            if (!sendLog && !sendError) return
            if (sendLog) {
                val frame = Throwable().stackTrace.firstOrNull {
                    it.className.startsWith("com.cabin.") &&
                        !it.className.startsWith("com.cabin.logging.") &&
                        it.className != "com.cabin.reports.DebugJournal" &&
                        !it.className.startsWith("com.cabin.telemetry.")
                }
                val site = frame?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
                val body = "Cabin log $site"
                val severity = when (logLevel) {
                    Logger.Level.VERBOSE -> SentryLogLevel.TRACE
                    Logger.Level.DEBUG -> SentryLogLevel.DEBUG
                    Logger.Level.INFO -> SentryLogLevel.INFO
                    Logger.Level.WARN -> SentryLogLevel.WARN
                    Logger.Level.ERROR -> SentryLogLevel.ERROR
                }
                Sentry.logger().log(severity, body)
                Sentry.addBreadcrumb(Breadcrumb().apply {
                    category = "cabin.log"; message = body
                    this.level = when (logLevel) {
                        Logger.Level.VERBOSE, Logger.Level.DEBUG -> SentryLevel.DEBUG
                        Logger.Level.INFO -> SentryLevel.INFO
                        Logger.Level.WARN -> SentryLevel.WARNING
                        Logger.Level.ERROR -> SentryLevel.ERROR
                    }
                })
            }
            if (sendError) Sentry.captureException(throwable) { scope ->
                (throwable as? com.cabin.joying.JoyingVideoConnection.ConnectionException)?.let {
                    scope.setTag("joying_video_failure", it.reason.name)
                }
            }
        } catch (_: RuntimeException) {
            // Never recurse into Logger or break application work when reporting fails.
        }
    }

    private val safeLog = Regex("Cabin log (unknown|com\\.cabin\\.[A-Za-z0-9_.$<>:-]+)")
    internal fun cleanLog(log: SentryLogEvent): SentryLogEvent? {
        if (!safeLog.matches(log.body) && DiagnosticEvent.entries.none { log.body == "Cabin event ${it.name}" }) return null
        return SentryLogEvent(log.traceId, log.timestamp, log.body, log.level).apply {
            setAttribute("sentry.release", io.sentry.SentryLogEventAttributeValue("string", "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"))
            setAttribute("sentry.environment", io.sentry.SentryLogEventAttributeValue("string", if (BuildConfig.DEBUG) "development" else "production"))
        }
    }

    internal fun cleanBreadcrumb(crumb: Breadcrumb): Breadcrumb? {
        if (crumb.category == "cabin.log" && safeLog.matches(crumb.message.orEmpty())) {
            return Breadcrumb(crumb.timestamp).apply { category = "cabin.log"; message = crumb.message; level = crumb.level }
        }
        if (crumb.category != CATEGORY || DiagnosticEvent.entries.none { it.name == crumb.message }) return null
        return Breadcrumb(crumb.timestamp).apply { category = CATEGORY; message = crumb.message }
    }

    /** Rebuild an allowlisted event; dropping fields is safer than matching sensitive strings. */
    internal fun sanitize(source: SentryEvent): SentryEvent = SentryEvent(source.timestamp).apply {
        eventId = source.eventId
        level = source.level
        // Cached crashes can belong to the previous installed version.
        release = source.release?.takeIf { it.matches(Regex("zeno[.]carlink@[0-9A-Za-z.+-]+")) }
            ?: "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
        environment = source.environment?.takeIf { it in listOf("development", "production") }
            ?: if (BuildConfig.DEBUG) "development" else "production"
        platform = if (source.platform == "native") "native" else "java"
        debugMeta = DebugMeta().apply {
            images = source.debugMeta?.images.orEmpty().filter { it.type in listOf("elf", "macho", "pe") }.map { original ->
                DebugImage().apply {
                    type = original.type; debugId = original.debugId; codeId = original.codeId
                    imageAddr = original.imageAddr; imageSize = original.imageSize; arch = original.arch
                    codeFile = original.codeFile?.substringAfterLast('/')
                    debugFile = original.debugFile?.substringAfterLast('/')
                }
            }
        }
        val mappingId = source.debugMeta?.images?.firstOrNull {
            it.type == "proguard" && it.uuid?.matches(Regex("[a-fA-F0-9-]{36}")) == true
        }?.uuid ?: BuildConfig.SENTRY_MAPPING_UUID.takeIf { source.release == null || source.release == "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}" }
        if (!mappingId.isNullOrBlank()) debugMeta!!.apply {
            images = images.orEmpty() + listOf(DebugImage().apply { type = "proguard"; uuid = mappingId })
        }
        setTag("android_api", Build.VERSION.SDK_INT.toString())
        source.getTag("joying_video_failure")?.takeIf { value ->
            com.cabin.joying.JoyingVideoConnection.Failure.entries.any { it.name == value }
        }?.let { setTag("joying_video_failure", it) }
        message = Message().apply {
            formatted = source.message?.formatted?.takeIf { name -> DiagnosticEvent.entries.any { it.name == name } }
                ?: "Cabin application error"
        }
        exceptions = source.exceptions?.map { original ->
            SentryException().apply {
                type = original.type
                module = original.module
                original.mechanism?.let { originalMechanism ->
                    mechanism = Mechanism().apply { type = originalMechanism.type?.takeIf { it in listOf("ANR", "UncaughtExceptionHandler", "signal", "native", "generic") } ?: "generic"; isHandled = originalMechanism.isHandled }
                }
                threadId = original.threadId
                stacktrace = original.stacktrace?.let(::cleanStack)

            }
        }
        threads = source.threads?.map { original ->
            SentryThread().apply {
                id = original.id; isCrashed = original.isCrashed; isCurrent = original.isCurrent
                stacktrace = original.stacktrace?.let(::cleanStack)
            }
        }
        breadcrumbs = source.breadcrumbs?.mapNotNull(::cleanBreadcrumb)
    }
}

internal class TelemetryBudget(private val limit: Int) {
    private var window = -1L
    private var count = 0
    @Synchronized fun take(now: Long): Boolean {
        if (window < 0 || now - window >= 60_000 || now < window) { window = now; count = 0 }
        if (count >= limit) return false
        count++
        return true
    }
}

private fun cleanStack(stack: SentryStackTrace) = SentryStackTrace(stack.frames?.map { frame ->
    SentryStackFrame().apply {
        module = frame.module?.substringAfterLast('/'); function = frame.function
        filename = frame.filename?.substringAfterLast('/')?.substringAfterLast('\\')
        lineno = frame.lineno; isInApp = frame.isInApp
        platform = frame.platform; instructionAddr = frame.instructionAddr
        imageAddr = frame.imageAddr; symbolAddr = frame.symbolAddr
        isNative = frame.isNative
    }
})
