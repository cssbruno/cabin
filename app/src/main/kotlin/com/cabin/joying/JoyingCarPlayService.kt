package com.cabin.joying

import android.app.*
import com.cabin.telemetry.CabinTelemetry
import com.cabin.telemetry.DiagnosticEvent
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.MotionEvent
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.cabin.MainActivity
import com.cabin.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The started service owns CarPlay; Activity bindings only own a video surface. */
internal open class JoyingCarPlayService : Service() {
    data class State(val status: String = "Connecting to Joying’s native CarPlay service…", val ratio: Float = 1280f / 720f)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var session: JoyingSessionRuntime? = null
    private var surface: Surface? = null
    private val recovery = Handler(Looper.getMainLooper())
    private var retries = 0
    private var recoveryPending = false
    @Volatile private var generation = 0
    inner class Connection : Binder() { val service get() = this@JoyingCarPlayService }
    private val connection = Connection()

    override fun onBind(intent: Intent?): IBinder = connection
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Joying CarPlay", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopProjection()
            return START_NOT_STICKY
        }
        promote()
        if (intent?.action == RETRY || session == null) restart()
        return START_STICKY
    }
    private fun promote() {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_FULLSCREEN_PROJECTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Cabin CarPlay").setContentText(getString(R.string.joying_background))
            .setContentIntent(open).setOngoing(true).addAction(0, getString(R.string.joying_disconnect), stop).build())
    }
    fun restart() {
        retries = 0
        startSession()
    }
    private fun startSession() {
        CabinTelemetry.record(DiagnosticEvent.JOYING_START)
        disconnect()
        val current = generation
        mutable.value = State()
        session = createSession(
            { message -> mutable.update { if (generation == current) it.copy(status = message) else it } },
            { width, height -> mutable.update { if (generation == current && height > 0) it.copy(ratio = width.toFloat() / height) else it } },
            { recovery.post { scheduleRecovery(current) } },
        ).also { next -> surface?.let(next::attach); next.start() }
    }
    private fun scheduleRecovery(failedGeneration: Int) {
        if (generation != failedGeneration || recoveryPending) return
        if (retries >= 3) {
            CabinTelemetry.record(DiagnosticEvent.JOYING_EXHAUSTED, report = true)
            mutable.update { it.copy(status = getString(R.string.joying_recovery_exhausted)) }
            return
        }
        CabinTelemetry.record(DiagnosticEvent.JOYING_RETRY)
        recoveryPending = true
        val delay = 2_000L shl retries
        retries++
        mutable.update { it.copy(status = getString(R.string.joying_recovering, retries, 3)) }
        recovery.postDelayed({
            if (generation == failedGeneration) startSession()
        }, delay)
    }
    protected open fun createSession(status: (String) -> Unit, size: (Int, Int) -> Unit, failed: () -> Unit): JoyingSessionRuntime =
        JoyingEmbeddedSession(applicationContext, resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels, status, size, failed)

    fun stopProjection() {
        CabinTelemetry.record(DiagnosticEvent.JOYING_STOP)
        disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    fun disconnect() {
        recovery.removeCallbacksAndMessages(null)
        recoveryPending = false
        generation++
        session?.close()
        session = null
        mutable.value = mutable.value.copy(status = "CarPlay disconnected")
    }
    fun attach(next: Surface) { surface = next; session?.attach(next) }
    fun detach(old: Surface) {
        if (surface !== old) return
        session?.detach(old)
        surface = null
    }
    fun touch(event: MotionEvent, width: Int, height: Int) = session?.touch(event, width, height) ?: false
    fun connectPhone(address: String) { session?.connectPhone(address) }
    fun enableWireless() { session?.enableWireless() }
    fun siri() { session?.siri() }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    companion object {
        const val RETRY = "com.cabin.joying.RETRY"
        const val STOP = "com.cabin.joying.STOP"
        private const val CHANNEL = "joying_carplay"
        private const val NOTIFICATION = 7462
    }
}
