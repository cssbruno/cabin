package com.cabin.ui.settings

import com.cabin.logging.FileLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal data class LogFileSnapshot(val file: File, val size: Long, val modifiedAt: Long)

internal data class LogFilesSnapshot(
    val files: List<LogFileSnapshot> = emptyList(),
    val currentFileSize: Long = 0,
) {
    val totalSize: Long = files.sumOf { it.size }
}

/** Serializes the Logs tab's disk work and returns metadata safe to render on Main. */
internal class LogFilesStore(private val manager: FileLogManager) {
    private val mutex = Mutex()

    suspend fun snapshot(): LogFilesSnapshot = onIo { readSnapshot() }

    suspend fun setEnabled(enabled: Boolean): LogFilesSnapshot = onIo {
        if (enabled) manager.enable() else manager.disable()
        readSnapshot()
    }

    suspend fun delete(file: File): LogFilesSnapshot = onIo {
        manager.deleteLogFile(file)
        readSnapshot()
    }

    suspend fun page(file: File, offset: Long): LogPage = onIo {
        manager.flush()
        readLogPage(file, offset)
    }

    suspend fun prepareExport(file: File): Boolean = onIo {
        manager.flush()
        file.isFile
    }

    private fun readSnapshot(): LogFilesSnapshot =
        LogFilesSnapshot(
            files = manager.getLogFiles().map { LogFileSnapshot(it, it.length(), it.lastModified()) },
            currentFileSize = manager.getCurrentLogFileSize(),
        )

    private suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }
}

internal data class LogPage(val text: String, val nextOffset: Long, val hasMore: Boolean)

/** Read a bounded page; keep UTF-8 characters intact even for unusually long lines. */
internal fun readLogPage(file: File, offset: Long, pageBytes: Int = 64 * 1024): LogPage {
    require(offset >= 0 && pageBytes >= 4)
    return java.io.RandomAccessFile(file, "r").use { input ->
        input.seek(offset.coerceAtMost(input.length()))
        val start = input.filePointer
        val bytes = ByteArray(pageBytes + 1)
        val count = input.read(bytes)
        if (count <= 0) return@use LogPage("", start, false)
        var end = minOf(count, pageBytes)
        if (count > pageBytes) {
            val newline = (0 until end).lastOrNull { bytes[it] == '\n'.code.toByte() }
            if (newline != null) end = newline + 1
            else while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
        }
        val next = start + end
        LogPage(String(bytes, 0, end, Charsets.UTF_8), next, next < input.length())
    }
}
