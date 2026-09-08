package com.cabin.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

enum class DiagnosticKind { MICROPHONE, SPEAKERS, GPS }
enum class DiagnosticPhase { IDLE, RUNNING, COMPLETE, STOPPED, PERMISSION_NEEDED, UNAVAILABLE, TIMED_OUT, NO_PROGRESS }

/** Only measurements are retained. Never holds recordings, coordinates or device identifiers. */
data class DiagnosticState(
    val kind: DiagnosticKind? = null,
    val phase: DiagnosticPhase = DiagnosticPhase.IDLE,
    val microphonePercent: Int = 0,
    val clipped: Boolean = false,
    val accuracyMeters: Float? = null,
)

fun interface DiagnosticRunner {
    suspend fun run(kind: DiagnosticKind, update: (DiagnosticState) -> Unit): DiagnosticState
}

/** The UI owns this session. Cancellation invalidates callbacks before releasing hardware. */
class ProjectionDiagnostics(
    private val scope: CoroutineScope,
    private val runner: DiagnosticRunner,
    private val canRun: () -> Boolean,
    private val timeoutMs: Long = 22_000,
) {
    private var generation = 0L
    private val mutableState = MutableStateFlow(DiagnosticState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start(kind: DiagnosticKind) {
        // A cancelled job still owns its hardware until its finally blocks complete.
        if (!scope.isActive || !canRun() || job?.isCompleted == false) return
        val token = ++generation
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canRun()) {
                    publish(token) { it.copy(phase = DiagnosticPhase.STOPPED) }
                    return@launch
                }
                val result = withTimeout(timeoutMs) {
                    runner.run(kind) { progress ->
                        publish(token) {
                            if (it.phase == DiagnosticPhase.RUNNING) progress.copy(kind = kind, phase = DiagnosticPhase.RUNNING) else it
                        }
                    }
                }
                publish(token) { result.copy(kind = kind, phase = DiagnosticPhase.COMPLETE) }
            } catch (_: TimeoutCancellationException) {
                publish(token) { it.copy(phase = DiagnosticPhase.TIMED_OUT) }
            } catch (cancelled: CancellationException) {
                publish(token) { it.copy(phase = DiagnosticPhase.STOPPED) }
                throw cancelled
            } catch (_: SecurityException) {
                publish(token) { it.copy(phase = DiagnosticPhase.PERMISSION_NEEDED) }
            } catch (_: DiagnosticNoProgressException) {
                publish(token) { it.copy(phase = DiagnosticPhase.NO_PROGRESS) }
            } catch (_: Exception) {
                publish(token) { it.copy(phase = DiagnosticPhase.UNAVAILABLE) }
            }
        }
        job = next
        mutableState.value = DiagnosticState(kind, DiagnosticPhase.RUNNING)
        next.invokeOnCompletion {
            // Cancellation can happen before the coroutine body is dispatched.
            publish(token) { if (it.phase == DiagnosticPhase.RUNNING) it.copy(phase = DiagnosticPhase.STOPPED) else it }
        }
        next.start()
    }

    @Synchronized
    private fun publish(token: Long, transform: (DiagnosticState) -> DiagnosticState) {
        // The generation check and state write must be indivisible with Stop: audio
        // progress arrives from IO while lifecycle/focus callbacks run on the UI thread.
        if (generation == token) mutableState.value = transform(mutableState.value)
    }

    @Synchronized
    fun stop() {
        generation++
        job?.cancel()
        if (mutableState.value.phase == DiagnosticPhase.RUNNING) {
            mutableState.value = mutableState.value.copy(phase = DiagnosticPhase.STOPPED)
        }
    }

}

internal fun microphoneMeasurement(samples: ShortArray, count: Int): Pair<Int, Boolean> {
    require(count in 0..samples.size)
    var peak = 0
    for (index in 0 until count) peak = maxOf(peak, abs(samples[index].toInt()))
    return (peak * 100.0 / 32768.0).roundToInt().coerceIn(0, 100) to (peak >= 32_440)
}

/** 1.5 s stereo: left tone, silence, right tone. Quiet amplitude and ramps avoid clicks. */
internal fun speakerTestSamples(sampleRate: Int = 16_000): ShortArray {
    val frames = sampleRate * 3 / 2
    return ShortArray(frames * 2).also { samples ->
        for (frame in 0 until frames) {
            val seconds = frame.toDouble() / sampleRate
            val channel = when {
                seconds < 0.5 -> 0
                seconds >= 0.8 && seconds < 1.3 -> 1
                else -> continue
            }
            val localTime = seconds - if (channel == 0) 0.0 else 0.8
            val envelope = minOf(1.0, localTime / 0.02, (0.5 - localTime) / 0.02).coerceAtLeast(0.0)
            samples[frame * 2 + channel] = (sin(2 * Math.PI * 440 * localTime) * 1_966 * envelope).roundToInt().toShort()
        }
    }
}

internal fun freshGpsAccuracy(fixElapsedNanos: Long, startedNanos: Long, nowNanos: Long, accuracy: Float?): Float? {
    if (fixElapsedNanos < startedNanos || fixElapsedNanos > nowNanos || nowNanos - fixElapsedNanos > 5_000_000_000L) return null
    return accuracy?.takeIf { it.isFinite() && it >= 0f }
}


internal class DiagnosticNoProgressException : IllegalStateException("Audio stream made no progress")

/** Zero PCM is still progress; only an absent/stalled stream times out. */
internal class DiagnosticProgressDeadline(startedMs: Long, private val limitMs: Long = 2_000L) {
    private var lastProgressMs = startedMs
    private var maximumProgress = 0L

    fun observe(progress: Long, nowMs: Long) {
        if (progress > maximumProgress) {
            maximumProgress = progress
            lastProgressMs = nowMs
        }
        if (nowMs - lastProgressMs >= limitMs) throw DiagnosticNoProgressException()
    }
}
