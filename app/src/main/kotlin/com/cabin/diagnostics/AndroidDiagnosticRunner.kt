package com.cabin.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Explicit foreground tests; does not modify system volume, projection routing or stored data. */
class AndroidDiagnosticRunner(context: Context) : DiagnosticRunner {
    private val context = context.applicationContext

    override suspend fun run(kind: DiagnosticKind, update: (DiagnosticState) -> Unit): DiagnosticState =
        hardwareOwner.withLock {
            // A newly opened panel waits for the previous panel's cancelled test to
            // finish releasing its devices. Ownership extends across runner instances.
            currentCoroutineContext().ensureActive()
            when (kind) {
                DiagnosticKind.MICROPHONE -> microphone(update)
                DiagnosticKind.SPEAKERS -> speakers()
                DiagnosticKind.GPS -> gps()
            }
        }

    private suspend fun microphone(update: (DiagnosticState) -> Unit): DiagnosticState = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException()
        }
        withAudioFocus { audio, focusRetained, _ ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException()
            }
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, SAMPLE_RATE / 5),
            )
            val samples = ShortArray(SAMPLE_RATE / 10)
            var result = DiagnosticState(DiagnosticKind.MICROPHONE)
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                currentCoroutineContext().ensureActive()
                check(focusRetained.get() && audio.mode == AudioManager.MODE_NORMAL)
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                val started = SystemClock.elapsedRealtime()
                val progress = DiagnosticProgressDeadline(started)
                var samplesReceived = 0L
                while (SystemClock.elapsedRealtime() - started < 5_000L) {
                    currentCoroutineContext().ensureActive()
                    check(focusRetained.get() && audio.mode == AudioManager.MODE_NORMAL)
                    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0)
                    if (count > 0) {
                        samplesReceived += count
                        val (percent, clipped) = microphoneMeasurement(samples, count)
                        result = result.copy(microphonePercent = maxOf(percent, result.microphonePercent), clipped = clipped || result.clipped)
                        update(result.copy(microphonePercent = percent))
                        samples.fill(0)
                    }
                    progress.observe(samplesReceived, SystemClock.elapsedRealtime())
                    delay(50)
                }
                result
            } finally {
                samples.fill(0)
                try { recorder.stop() } catch (_: RuntimeException) { }
                try { recorder.release() } catch (_: RuntimeException) { }
            }
        }
    }

    private suspend fun speakers(): DiagnosticState = withContext(Dispatchers.IO) {
        withAudioFocus { audio, focusRetained, attributes ->
            var track: AudioTrack? = null
            val samples = speakerTestSamples(SAMPLE_RATE)
            try {
                currentCoroutineContext().ensureActive()
                check(focusRetained.get() && audio.mode == AudioManager.MODE_NORMAL)
                val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                val output = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, SAMPLE_RATE / 2)).build()
                track = output
                check(output.state == AudioTrack.STATE_INITIALIZED)
                output.setVolume(1f) // PCM itself is capped at 6%; system media volume is left untouched.
                output.play()
                val started = SystemClock.elapsedRealtime()
                val writeProgress = DiagnosticProgressDeadline(started)
                val playbackProgress = DiagnosticProgressDeadline(started)
                var offset = 0
                while (offset < samples.size) {
                    currentCoroutineContext().ensureActive()
                    check(focusRetained.get() && audio.mode == AudioManager.MODE_NORMAL)
                    check(output.playState == AudioTrack.PLAYSTATE_PLAYING)
                    val written = output.write(samples, offset, minOf(1600, samples.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                    check(written >= 0)
                    offset += written
                    val now = SystemClock.elapsedRealtime()
                    writeProgress.observe(offset.toLong(), now)
                    playbackProgress.observe(output.playbackHeadPosition.toLong(), now)
                    delay(20)
                }
                while (output.playbackHeadPosition < samples.size / 2) {
                    currentCoroutineContext().ensureActive()
                    check(focusRetained.get() && audio.mode == AudioManager.MODE_NORMAL)
                    check(output.playState == AudioTrack.PLAYSTATE_PLAYING)
                    playbackProgress.observe(output.playbackHeadPosition.toLong(), SystemClock.elapsedRealtime())
                    delay(20)
                }
                DiagnosticState(DiagnosticKind.SPEAKERS)
            } finally {
                samples.fill(0)
                try { track?.stop() } catch (_: RuntimeException) { }
                try { track?.release() } catch (_: RuntimeException) { }
            }
        }
    }

    /** Both explicit audio checks own transient focus; ducking/loss also terminates them. */
    private suspend fun <T> withAudioFocus(block: suspend (AudioManager, AtomicBoolean, AudioAttributes) -> T): T {
        val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
        check(audio.mode == AudioManager.MODE_NORMAL)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val focusRetained = AtomicBoolean(true)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change -> if (change < 0) focusRetained.set(false) }, Handler(Looper.getMainLooper()))
            .build()
        try {
            currentCoroutineContext().ensureActive()
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            currentCoroutineContext().ensureActive()
            return block(audio, focusRetained, attributes)
        } finally {
            // Failed focus requests and failed device initialization take this path too.
            try { audio.abandonAudioFocusRequest(focus) } catch (_: RuntimeException) { }
        }
    }

    private suspend fun gps(): DiagnosticState = withContext(Dispatchers.Main.immediate) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException()
        }
        val locationManager = checkNotNull(context.getSystemService(LocationManager::class.java))
        check(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (finished.get()) return
                    val accuracy = freshGpsAccuracy(location.elapsedRealtimeNanos, startedNanos, SystemClock.elapsedRealtimeNanos(),
                        location.accuracy.takeIf { location.hasAccuracy() }) ?: return
                    if (!finished.compareAndSet(false, true)) return
                    locationManager.stopUpdatesSafely(this)
                    continuation.resume(DiagnosticState(DiagnosticKind.GPS, accuracyMeters = accuracy))
                }
                override fun onProviderEnabled(provider: String) { }
                override fun onProviderDisabled(provider: String) {
                    if (!finished.compareAndSet(false, true)) return
                    locationManager.stopUpdatesSafely(this)
                    continuation.resumeWith(Result.failure(IllegalStateException("GPS disabled")))
                }
                @Deprecated("Legacy callback")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
            }
            continuation.invokeOnCancellation {
                finished.set(true)
                locationManager.stopUpdatesSafely(listener)
            }
            try {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
                // Cancellation can race the registration, so removal is safe to repeat.
                if (finished.get() || !continuation.isActive) locationManager.stopUpdatesSafely(listener)
            } catch (error: SecurityException) {
                locationManager.stopUpdatesSafely(listener)
                if (finished.compareAndSet(false, true)) continuation.resumeWith(Result.failure(error))
            } catch (error: RuntimeException) {
                locationManager.stopUpdatesSafely(listener)
                if (finished.compareAndSet(false, true)) continuation.resumeWith(Result.failure(error))
            }
        }
    }

    private fun LocationManager.stopUpdatesSafely(listener: LocationListener) {
        // Permission revocation or a dying vendor service must not crash a lifecycle
        // callback/cancellation handler. Listener callbacks are already invalidated.
        try { removeUpdates(listener) } catch (_: RuntimeException) { }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private val hardwareOwner = Mutex()
    }
}
