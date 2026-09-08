package com.cabin.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.coroutines.CoroutineContext

class ProjectionDiagnosticsTest {
    @Test
    fun `microphone measurement handles negative full scale and ignores unread samples`() {
        assertEquals(100 to true, microphoneMeasurement(shortArrayOf(Short.MIN_VALUE), 1))
        assertEquals(0 to false, microphoneMeasurement(shortArrayOf(0, Short.MAX_VALUE), 1))
        assertEquals(0 to false, microphoneMeasurement(shortArrayOf(Short.MAX_VALUE), 0))
        assertEquals(50 to false, microphoneMeasurement(shortArrayOf(16384), 1))
    }

    @Test
    fun `speaker tones are bounded and channels are separated by silence`() {
        val samples = speakerTestSamples()
        assertEquals(48000, samples.size)
        assertTrue(samples.all { abs(it.toInt()) <= 1966 })
        assertTrue((0 until 8000).all { samples[it * 2 + 1] == 0.toShort() })
        assertTrue((8000 until 12800).all { samples[it * 2] == 0.toShort() && samples[it * 2 + 1] == 0.toShort() })
        assertTrue((12800 until 20800).all { samples[it * 2] == 0.toShort() })
        assertTrue(samples.any { it != 0.toShort() })
    }

    @Test
    fun `GPS accepts only fresh post-start accuracy without needing coordinates`() {
        assertEquals(3f, freshGpsAccuracy(200L, 100L, 300L, 3f))
        assertNull(freshGpsAccuracy(99L, 100L, 300L, 3f))
        assertNull(freshGpsAccuracy(301L, 100L, 300L, 3f))
        assertNull(freshGpsAccuracy(200L, 100L, 6_000_000_000L, 3f))
        assertNull(freshGpsAccuracy(200L, 100L, 300L, Float.NaN))
        assertNull(freshGpsAccuracy(200L, 100L, 300L, -1f))
    }

    @Test
    fun `disallowed diagnostic never acquires hardware`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var called = false
            val session = ProjectionDiagnostics(scope, DiagnosticRunner { _, _ -> called = true; DiagnosticState() }, { false })
            session.start(DiagnosticKind.MICROPHONE)
            assertFalse(called)
            assertEquals(DiagnosticPhase.IDLE, session.state.value.phase)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stop rejects late callbacks and prevents concurrent tests`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            lateinit var feedback: (DiagnosticState) -> Unit
            val session = ProjectionDiagnostics(scope, DiagnosticRunner { _, update ->
                calls++
                feedback = update
                awaitCancellation()
            }, { true })
            session.start(DiagnosticKind.MICROPHONE)
            session.start(DiagnosticKind.SPEAKERS)
            assertEquals(1, calls)
            session.stop()
            feedback(DiagnosticState(microphonePercent = 99))
            assertEquals(DiagnosticPhase.STOPPED, session.state.value.phase)
            assertEquals(0, session.state.value.microphonePercent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `permission failures are actionable not reported as successful tests`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = ProjectionDiagnostics(scope, DiagnosticRunner { _, _ -> throw SecurityException() }, { true })
            session.start(DiagnosticKind.MICROPHONE)
            assertEquals(DiagnosticPhase.PERMISSION_NEEDED, session.state.value.phase)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelled cleanup keeps ownership until hardware release completes`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            var calls = 0
            val session = ProjectionDiagnostics(scope, DiagnosticRunner { kind, _ ->
                calls++
                if (calls == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanup.complete(Unit)
                            release.await()
                        }
                    }
                }
                DiagnosticState(kind)
            }, { true })
            session.start(DiagnosticKind.MICROPHONE)
            session.stop()
            assertTrue(cleanup.isCompleted)
            session.start(DiagnosticKind.SPEAKERS)
            assertEquals(1, calls)
            assertEquals(DiagnosticPhase.STOPPED, session.state.value.phase)
            release.complete(Unit)
            session.start(DiagnosticKind.SPEAKERS)
            assertEquals(2, calls)
            assertEquals(DiagnosticPhase.COMPLETE, session.state.value.phase)
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `lost eligibility before dispatch never acquires hardware`() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            var allowed = true
            var called = false
            val session = ProjectionDiagnostics(scope, DiagnosticRunner { _, _ -> called = true; DiagnosticState() }, { allowed })
            session.start(DiagnosticKind.GPS)
            allowed = false
            dispatcher.drain()
            assertFalse(called)
            assertEquals(DiagnosticPhase.STOPPED, session.state.value.phase)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scope cancellation before dispatch cannot leave a running check`() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var called = false
        val session = ProjectionDiagnostics(scope, DiagnosticRunner { _, _ -> called = true; DiagnosticState() }, { true })
        session.start(DiagnosticKind.MICROPHONE)
        scope.cancel()
        dispatcher.drain()
        assertFalse(called)
        assertEquals(DiagnosticPhase.STOPPED, session.state.value.phase)
        session.start(DiagnosticKind.SPEAKERS)
        assertEquals(DiagnosticPhase.STOPPED, session.state.value.phase)
    }

    @Test
    fun `callbacks after completion and from a previous run cannot revive a test`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val callbacks = mutableListOf<(DiagnosticState) -> Unit>()
            val session = ProjectionDiagnostics(scope, DiagnosticRunner { kind, update ->
                callbacks.add(update)
                DiagnosticState(kind)
            }, { true })
            session.start(DiagnosticKind.MICROPHONE)
            callbacks.first()(DiagnosticState(microphonePercent = 99))
            assertEquals(DiagnosticPhase.COMPLETE, session.state.value.phase)
            assertEquals(0, session.state.value.microphonePercent)
            session.start(DiagnosticKind.SPEAKERS)
            callbacks.first()(DiagnosticState(microphonePercent = 99))
            assertEquals(DiagnosticKind.SPEAKERS, session.state.value.kind)
            assertEquals(DiagnosticPhase.COMPLETE, session.state.value.phase)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `absent and stalled audio are distinct from successful silence`() {
        val silentStream = DiagnosticProgressDeadline(0L)
        repeat(6) { index -> silentStream.observe((index + 1) * 1600L, index * 1000L) }
        val absentStream = DiagnosticProgressDeadline(0L)
        absentStream.observe(0L, 1999L)
        assertNoProgress { absentStream.observe(0L, 2000L) }
        val stalledStream = DiagnosticProgressDeadline(0L)
        stalledStream.observe(1600L, 100L)
        stalledStream.observe(1600L, 2099L)
        assertNoProgress { stalledStream.observe(1600L, 2100L) }
        assertEquals(0 to false, microphoneMeasurement(ShortArray(1600), 1600))
    }

    @Test
    fun `audio without progress is not reported as completed`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = ProjectionDiagnostics(scope, DiagnosticRunner { _, _ -> throw DiagnosticNoProgressException() }, { true })
            session.start(DiagnosticKind.MICROPHONE)
            assertEquals(DiagnosticPhase.NO_PROGRESS, session.state.value.phase)
        } finally {
            scope.cancel()
        }
    }

    private fun assertNoProgress(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: DiagnosticNoProgressException) { rejected = true }
        assertTrue(rejected)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

}
