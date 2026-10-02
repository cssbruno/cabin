package com.cabin.logging

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class PendingSupportExport(val id: String, val name: String, val mime: String, val file: File, val ready: Boolean = true)

/** Private, durable frozen payloads. A failed/cancelled document write remains explicitly resumable. */
internal class SupportExportQueue(context: Context) {
    private val directory = File(context.noBackupFilesDir, "support-exports")
    suspend fun pending(): List<PendingSupportExport> = withContext(Dispatchers.IO) { queueMutex.withLock { readPending() } }
    private fun readPending(): List<PendingSupportExport> =
        directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { metadata ->
            runCatching {
                val id = metadata.nameWithoutExtension
                require(validId(id) && metadata.length() <= 4096)
                val json = JSONObject(metadata.readText())
                val file = File(directory, "$id.payload")
                PendingSupportExport(id, json.getString("name").take(160), json.getString("mime"), file, ready = file.isFile)
            }.getOrNull()
        }.sortedBy { it.file.lastModified() }
    suspend fun enqueue(name: String, mime: String, writer: suspend (OutputStream) -> Unit): PendingSupportExport = withContext(Dispatchers.IO) { queueMutex.withLock {
        require(name.length in 1..160 && mime in setOf("text/plain", "application/json"))
        check(directory.isDirectory || directory.mkdirs())
        check(readPending().size < 10) { "Discard or finish older exports first" }
        val id = UUID.randomUUID().toString()
        val temp = File(directory, "$id.tmp")
        val payload = File(directory, "$id.payload")
        val metadata = android.util.AtomicFile(File(directory, "$id.json"))
        try {
            // Journal before freezing. Process death during preparation leaves an explicit
            // discardable entry; only the final atomic rename makes a payload resumable.
            val json = JSONObject().put("name", name).put("mime", mime).toString()
            var stream: FileOutputStream? = null
            try { stream = metadata.startWrite(); stream.write(json.toByteArray()); metadata.finishWrite(stream) }
            catch (e: Exception) { metadata.failWrite(stream); throw e }
            FileOutputStream(temp).use { output -> writer(output); output.fd.sync() }
            currentCoroutineContext().ensureActive()
            check(temp.renameTo(payload))
            PendingSupportExport(id, name, mime, payload)
        } catch (e: Exception) { temp.delete(); payload.delete(); metadata.delete(); throw e }
    } }
    suspend fun enqueueText(name: String, text: String, mime: String = "application/json") = enqueue(name, mime) { it.write(text.toByteArray()) }
    suspend fun discard(id: String) = withContext(Dispatchers.IO) { queueMutex.withLock {
        require(validId(id))
        android.util.AtomicFile(File(directory, "$id.json")).delete()
        File(directory, "$id.payload").delete()
        File(directory, "$id.tmp").delete()
    } }
    companion object { private val queueMutex = Mutex() }
    private fun validId(id: String) = Regex("[a-f0-9-]{36}").matches(id)
}
