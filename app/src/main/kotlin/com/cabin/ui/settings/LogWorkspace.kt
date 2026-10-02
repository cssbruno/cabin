package com.cabin.ui.settings

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class WorkspaceQuery(
    val text: String = "", val severity: LogSeverity = LogSeverity.ALL,
    val from: Long? = null, val until: Long? = null, val zone: String = ZoneId.systemDefault().id,
) {
    init { require(text.length <= 200); require(from == null || until == null || from <= until); ZoneId.of(zone) }
}
/** Identity and boundary samples distinguish rotation/replacement from safe appends. */
internal data class LogSource(val path: String, val size: Long, val modified: Long, val appendOnly: Boolean = false,
    val identity: String? = null, val boundaryHash: String? = null) {
    fun unchanged(): Boolean = runCatching {
        val file = File(path)
        file.isFile && size >= 0 && (if (appendOnly) file.length() >= size else file.length() == size && file.lastModified() == modified) &&
            (identity == null || identity == fileIdentity(file)) && (boundaryHash == null || boundaryHash == hashBoundary(file, size))
    }.getOrDefault(false)
    companion object {
        fun capture(file: File, size: Long = file.length(), appendOnly: Boolean = false): LogSource {
            require(size >= 0 && size <= file.length())
            return LogSource(file.canonicalPath, size, file.lastModified(), appendOnly, fileIdentity(file), hashBoundary(file, size))
        }
        private fun fileIdentity(file: File): String? = runCatching {
            java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()?.toString()
        }.getOrNull()
        private fun hashBoundary(file: File, size: Long): String = RandomAccessFile(file, "r").use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val count = minOf(4096L, size).toInt()
            val buffer = ByteArray(count)
            input.readFully(buffer); digest.update(buffer)
            if (size > count) { input.seek(size - count); input.readFully(buffer); digest.update(buffer) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal data class WorkspaceRow(val source: LogSource, val line: Int, val offset: Long, val end: Long, val text: String, val shortened: Boolean) {
    val entry get() = ExplorerLine(line, text)
    val key get() = "${source.path}:$offset"
}
internal data class WorkspaceCursor(val sources: List<LogSource>, val fileIndex: Int = 0, val offset: Long = 0, val line: Int = 0)
internal data class WorkspacePage(
    val rows: List<WorkspaceRow>, val next: WorkspaceCursor?, val scanned: Int,
    val severities: Map<String, Int>, val unknownTime: Int,
)
internal class LogSourceChanged : IllegalStateException("Log source changed")
internal fun logTime(text: String, zone: String): Long? = runCatching {
    val local = LocalDateTime.parse(text.take(23), DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS"))
    val zoneId = ZoneId.of(zone)
    val offsets = zoneId.rules.getValidOffsets(local)
    // Never guess which occurrence of an ambiguous local timestamp was meant.
    if (offsets.size != 1) null else local.toInstant(offsets.single()).toEpochMilli()
}.getOrNull()

/** Reads whole records. Preview storage is bounded; matching also examines the undisplayed tail. */
internal suspend fun scanLogWorkspace(
    cursor: WorkspaceCursor, query: WorkspaceQuery, maxRows: Int = 250, maxBytes: Long = 2 * 1024 * 1024,
): WorkspacePage {
    require(maxRows in 1..1000 && maxBytes > 0)
    require(cursor.sources.size <= 40 && cursor.sources.map { it.path }.distinct().size == cursor.sources.size)
    require(cursor.fileIndex in 0..cursor.sources.size && cursor.offset >= 0 && cursor.line >= 0)
    require(if (cursor.fileIndex == cursor.sources.size) cursor.offset == 0L else cursor.offset <= cursor.sources[cursor.fileIndex].size)
    if (cursor.sources.any { !it.unchanged() }) throw LogSourceChanged()
    val rows = ArrayList<WorkspaceRow>()
    val counts = mutableMapOf<String, Int>()
    var unknown = 0; var scanned = 0; var consumed = 0L
    var index = cursor.fileIndex; var offset = cursor.offset; var lineNumber = cursor.line
    val terms = query.text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    val coroutine = currentCoroutineContext()
    while (index < cursor.sources.size) {
        val source = cursor.sources[index]
        File(source.path).inputStream().use { raw ->
            raw.channel.position(offset)
            raw.buffered().use { input ->
                while (offset < source.size && rows.size < maxRows && (consumed < maxBytes || scanned == 0)) {
                    coroutine.ensureActive()
                    val start = offset
                    val preview = java.io.ByteArrayOutputStream()
                    val chunk = java.io.ByteArrayOutputStream()
                    var overlap = ByteArray(0)
                    val found = BooleanArray(terms.size)
                    fun matchChunk() {
                        val bytes = chunk.toByteArray()
                        val part = String(overlap + bytes, Charsets.UTF_8)
                        terms.forEachIndexed { n, term -> if (!found[n] && part.contains(term, ignoreCase = true)) found[n] = true }
                        val combined = overlap + bytes
                        overlap = combined.takeLast(1024).toByteArray()
                        chunk.reset()
                    }
                    var ended = false
                    while (offset < source.size) {
                        if ((offset - start) % 8192 == 0L) coroutine.ensureActive()
                        val byte = input.read()
                        if (byte < 0) throw LogSourceChanged()
                        offset++; consumed++
                        if (byte == 10) { ended = true; break }
                        if (preview.size() < 8192) preview.write(byte)
                        chunk.write(byte)
                        if (chunk.size() == 4096) matchChunk()
                    }
                    matchChunk()
                    lineNumber++; scanned++
                    val text = preview.toString("UTF-8").trimEnd('\r')
                    val entry = ExplorerLine(lineNumber, text)
                    val severity = entry.severity ?: "?"
                    counts[severity] = (counts[severity] ?: 0) + 1
                    val time = logTime(text, query.zone)
                    if (time == null) unknown++
                    val inRange = (query.from == null && query.until == null) ||
                        (time != null && (query.from == null || time >= query.from) && (query.until == null || time <= query.until))
                    if (found.all { it } && inRange && (query.severity == LogSeverity.ALL || severity == query.severity.name.take(1))) {
                        rows += WorkspaceRow(source, lineNumber, start, offset, text, offset - start - (if (ended) 1 else 0) > preview.size())
                    }
                }
            }
        }
        if (offset >= source.size) { index++; offset = 0; lineNumber = 0 }
        if (rows.size >= maxRows || consumed >= maxBytes) break
    }
    if (cursor.sources.any { !it.unchanged() }) throw LogSourceChanged()
    val next = if (index < cursor.sources.size) WorkspaceCursor(cursor.sources, index, offset, lineNumber) else null
    return WorkspacePage(rows, next, scanned, counts, unknown)
}

internal suspend fun logContext(row: WorkspaceRow, radius: Int = 3): List<WorkspaceRow> {
    require(radius in 0..10)
    if (!row.source.unchanged()) throw LogSourceChanged()
    var cursor: WorkspaceCursor? = WorkspaceCursor(listOf(row.source))
    val result = mutableListOf<WorkspaceRow>()
    while (cursor != null) {
        val page = scanLogWorkspace(cursor, WorkspaceQuery(), maxRows = 250)
        result += page.rows.filter { it.line in (row.line - radius)..(row.line + radius) }
        if (page.rows.lastOrNull()?.line?.let { it > row.line + radius } == true) break
        cursor = page.next
    }
    return result
}

/** Streams original matching records, even when a preview was shortened. No context is included. */
internal suspend fun exportWorkspace(sources: List<LogSource>, query: WorkspaceQuery, metadataOnly: Boolean, output: OutputStream): Int {
    var cursor: WorkspaceCursor? = WorkspaceCursor(sources)
    var matches = 0
    while (cursor != null) {
        val page = scanLogWorkspace(cursor, query)
        for (row in page.rows) {
            currentCoroutineContext().ensureActive()
            if (metadataOnly) {
                row.entry.metadata()?.let { output.write((it + "\n").toByteArray()) }
            } else {
                output.write(("[${File(row.source.path).name}:${row.line}]\n").toByteArray())
                RandomAccessFile(row.source.path, "r").use { input ->
                    input.seek(row.offset)
                    var remaining = row.end - row.offset
                    val buffer = ByteArray(8192)
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw LogSourceChanged()
                        output.write(buffer, 0, read); remaining -= read
                    }
                }
                output.write('\n'.code)
            }
            matches++
        }
        cursor = page.next
    }
    if (sources.any { !it.unchanged() }) throw LogSourceChanged()
    return matches
}

internal suspend fun captureCompleteLog(file: File): LogSource {
    val source = LogSource.capture(file)
    val end = RandomAccessFile(file, "r").use { input ->
        var position = source.size
        val buffer = ByteArray(8192)
        var complete = 0L
        while (position > 0 && complete == 0L) {
            currentCoroutineContext().ensureActive()
            val count = minOf(position, buffer.size.toLong()).toInt()
            position -= count; input.seek(position); input.readFully(buffer, 0, count)
            val newline = (count - 1 downTo 0).firstOrNull { buffer[it] == 10.toByte() }
            if (newline != null) complete = position + newline + 1
        }
        complete
    }
    return LogSource.capture(file, size = end, appendOnly = true)
}
