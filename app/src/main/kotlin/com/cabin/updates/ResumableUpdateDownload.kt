package com.cabin.updates

import com.cabin.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun UpdateRelease.assetIdentity() = "$versionCode:$size:$sha256:$url"
internal fun strongEtag(value: String?) = value?.takeIf { it.length in 2..512 && it.startsWith('"') && it.endsWith('"') && '\n' !in it && '\r' !in it }
internal fun validResumeResponse(connection: HttpURLConnection, offset: Long, size: Long, etag: String): Boolean {
    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(connection.getHeaderField("Content-Range") ?: "") ?: return false
    val (start, end, total) = range.destructured
    return connection.responseCode == 206 && start.toLongOrNull() == offset && total.toLongOrNull() == size &&
        end.toLongOrNull() == size - 1 && strongEtag(connection.getHeaderField("ETag")) == etag
}

/** Resume only a validated asset with a strong entity tag. Install verification remains mandatory. */
internal suspend fun downloadUpdateAsset(
    release: UpdateRelease, partial: File, metadata: File,
    open: (String, Map<String, String>) -> HttpURLConnection,
    availableBytes: () -> Long, networkAllowed: () -> Boolean,
    progress: (Int) -> Unit,
) {
    requireUpdate(partial.parentFile!!.isDirectory || partial.parentFile!!.mkdirs(), R.string.update_error_storage)
    val saved = runCatching { require(metadata.length() <= 4096); JSONObject(metadata.readText()) }.getOrNull()
    var etag = strongEtag(saved?.optString("etag"))
    var offset = if (saved?.optString("asset") == release.assetIdentity() && etag != null && partial.length() in 1..release.size) partial.length() else 0L
    if (offset == 0L) { partial.delete(); metadata.delete(); etag = null }
    requireUpdate(networkAllowed(), R.string.ux_metered_wait)
    requireUpdate(availableBytes() >= release.size - offset + release.size + 1024 * 1024, R.string.ux_low_space)
    if (offset == release.size) { progress(100); return }
    var connection = open(release.url, if (offset > 0) mapOf("Range" to "bytes=$offset-", "If-Range" to etag!!) else emptyMap())
    try {
        if (offset > 0 && !validResumeResponse(connection, offset, release.size, etag!!)) {
            connection.disconnect(); offset = 0
            partial.delete(); metadata.delete()
            requireUpdate(availableBytes() >= release.size * 2 + 1024 * 1024, R.string.ux_low_space)
            connection = open(release.url, emptyMap())
        }
        requireUpdate(connection.responseCode == (if (offset == 0L) 200 else 206), R.string.update_error_server)
        val currentTag = strongEtag(connection.getHeaderField("ETag"))
        if (currentTag != null) {
            val atomic = android.util.AtomicFile(metadata)
            val stream = atomic.startWrite()
            try { stream.write(JSONObject().put("asset", release.assetIdentity()).put("etag", currentTag).toString().toByteArray()); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        } else metadata.delete()
        connection.inputStream.use { input -> FileOutputStream(partial, offset > 0).use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = offset
            progress((total * 100 / release.size).toInt())
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    requireUpdate(networkAllowed(), R.string.ux_metered_wait)
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    requireUpdate(total <= release.size && total <= MAX_APK_BYTES, R.string.update_error_integrity)
                    output.write(buffer, 0, read)
                    progress((total * 100 / release.size).toInt())
                }
                if (total != release.size) throw java.io.EOFException("Interrupted APK transfer")
            } finally { output.fd.sync() }
        } }
    } finally { connection.disconnect() }
}
