package com.cabin.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SupportExportQueueTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() { File(context.noBackupFilesDir, "support-exports").deleteRecursively() }
    @Test fun `prepared payload survives queue reconstruction until explicit discard`() = runBlocking {
        val saved = SupportExportQueue(context).enqueueText("support.json", "{\"result\":1}")
        val restored = SupportExportQueue(context).pending().single()
        assertEquals(saved.id, restored.id)
        assertEquals("{\"result\":1}", restored.file.readText())
        SupportExportQueue(context).discard(restored.id)
        assertTrue(SupportExportQueue(context).pending().isEmpty())
    }
    @Test fun `concurrent queue instances cannot exceed admission limit`() = runBlocking {
        val outcomes = (0..10).map { index -> async(Dispatchers.Default) {
            runCatching { SupportExportQueue(context).enqueueText("support-$index.json", "{}") }.isSuccess
        } }.awaitAll()
        assertEquals(10, outcomes.count { it })
        assertEquals(10, SupportExportQueue(context).pending().size)
    }
    @Test fun `process death during preparation leaves a discardable entry without exposing partial data`() = runBlocking {
        val directory = File(context.noBackupFilesDir, "support-exports").apply { mkdirs() }
        val id = java.util.UUID.randomUUID().toString()
        File(directory, "$id.json").writeText("""{"name":"interrupted.txt","mime":"text/plain"}""")
        File(directory, "$id.tmp").writeText("partial private log")
        val recovered = SupportExportQueue(context).pending().single()
        assertFalse(recovered.ready)
        assertFalse(recovered.file.exists())
        SupportExportQueue(context).discard(id)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun `complete atomic payload is resumable immediately after preparation process death`() = runBlocking {
        val directory = File(context.noBackupFilesDir, "support-exports").apply { mkdirs() }
        val id = java.util.UUID.randomUUID().toString()
        File(directory, "$id.json").writeText("""{"name":"complete.txt","mime":"text/plain"}""")
        File(directory, "$id.payload").writeText("complete snapshot")
        val recovered = SupportExportQueue(context).pending().single()
        assertTrue(recovered.ready)
        assertEquals("complete snapshot", recovered.file.readText())
    }
    @Test fun `cancelled preparation never publishes a partial payload`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = launch {
            SupportExportQueue(context).enqueue("support.txt", "text/plain") { output ->
                output.write("partial".toByteArray()); started.complete(Unit); awaitCancellation()
            }
        }
        started.await(); job.cancelAndJoin()
        assertTrue(SupportExportQueue(context).pending().isEmpty())
        assertTrue(File(context.noBackupFilesDir, "support-exports").listFiles().orEmpty().isEmpty())
    }
}
