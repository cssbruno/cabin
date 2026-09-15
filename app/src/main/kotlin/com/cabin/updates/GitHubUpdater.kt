package com.cabin.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.cabin.R
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.cabin.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, FAILED }
internal data class UpdateStatus(val phase: UpdatePhase = UpdatePhase.IDLE, val release: UpdateRelease? = null, val progress: Int = 0, val errorRes: Int? = null)
internal class GitHubUpdater private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("github_updates_v2", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val readyFile get() = File(context.filesDir, "updates/update.apk")
    private val installed get() = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    private val cached = cachedRelease(prefs.getString("release", null))?.takeIf { it.versionCode > installed }
    private val mutable = MutableStateFlow(UpdateStatus(if (cached == null) UpdatePhase.IDLE else if (readyFile.exists()) UpdatePhase.READY else UpdatePhase.AVAILABLE, cached))
    val state = mutable.asStateFlow()
    val automatic get() = prefs.getBoolean("automatic", true)
    val lastCheck get() = prefs.getLong("last_check", 0)
    fun setAutomatic(enabled: Boolean) { prefs.edit().putBoolean("automatic", enabled).apply(); UpdateJobService.schedule(context) }

    suspend fun check() = withContext(Dispatchers.IO) { lock.withLock {
        val old = mutable.value
        mutable.value = old.copy(phase = UpdatePhase.CHECKING, errorRes = null)
        try {
            val assets = releaseAssets(readText("https://api.github.com/repos/$UPDATE_REPOSITORY/releases?per_page=20", 1_048_576), '-' in BuildConfig.VERSION_NAME)
            var malformed = false
            val candidates = assets.mapNotNull { asset ->
                try { parseUpdateManifest(readText(asset.manifestUrl, 16_384), asset, installed) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { malformed = true; null }
            }
            val release = candidates.maxByOrNull { it.versionCode }
            if (release == null && malformed) error("Invalid release metadata")
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            if (release == null) {
                readyFile.delete(); prefs.edit().remove("release").apply()
                mutable.value = UpdateStatus(UpdatePhase.CURRENT)
            } else {
                val same = release == old.release
                if (!same) readyFile.delete()
                prefs.edit().putString("release", release.json()).apply()
                mutable.value = UpdateStatus(if (same && readyFile.exists()) UpdatePhase.READY else UpdatePhase.AVAILABLE, release)
            }
        } catch (e: CancellationException) { mutable.value = old; throw e }
        catch (e: Exception) {
            Log.w("CabinUpdater", "Update check failed", e)
            mutable.value = old.copy(phase = UpdatePhase.FAILED, errorRes = updateErrorResource(e))
        }
    } }

    suspend fun download() = withContext(Dispatchers.IO) { lock.withLock {
        val release = mutable.value.release ?: return@withLock
        val partial = File(context.filesDir, "updates/download.tmp")
        try {
            require(release.versionCode > installed)
            mutable.value = UpdateStatus(UpdatePhase.DOWNLOADING, release)
            requireUpdate(partial.parentFile!!.isDirectory || partial.parentFile!!.mkdirs(), R.string.update_error_storage)
            val connection = open(release.url)
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                requireUpdate(connection.responseCode == 200, R.string.update_error_server)
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer); if (read < 0) break
                        total += read; requireUpdate(total <= release.size && total <= MAX_APK_BYTES, R.string.update_error_integrity)
                        digest.update(buffer, 0, read); output.write(buffer, 0, read)
                        mutable.value = UpdateStatus(UpdatePhase.DOWNLOADING, release, (total * 100 / release.size).toInt())
                    }
                    requireUpdate(total == release.size, R.string.update_error_integrity)
                } }
            } finally { connection.disconnect() }
            requireUpdate(digest.digest().hex() == release.sha256, R.string.update_error_integrity)
            validateApk(partial, release)
            readyFile.delete(); requireUpdate(partial.renameTo(readyFile), R.string.update_error_storage)
            mutable.value = UpdateStatus(UpdatePhase.READY, release, 100)
        } catch (e: CancellationException) { mutable.value = UpdateStatus(UpdatePhase.AVAILABLE, release); throw e }
        catch (e: Exception) {
            Log.w("CabinUpdater", "Update download or validation failed", e)
            mutable.value = UpdateStatus(UpdatePhase.FAILED, release, errorRes = updateErrorResource(e))
        }
        finally { partial.delete() }
    } }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, release: UpdateRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw UpdateException(R.string.update_error_apk)
        validateUpdatePackage(current, candidate, release, context.packageName, Build.VERSION.SDK_INT)
    }
    suspend fun installIntent(): Intent = withContext(Dispatchers.IO) { lock.withLock {
        val release = mutable.value.release ?: error("No update")
        requireUpdate(readyFile.length() == release.size, R.string.update_error_integrity)
        val digest = MessageDigest.getInstance("SHA-256")
        readyFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        requireUpdate(digest.digest().hex() == release.sha256, R.string.update_error_integrity)
        validateApk(readyFile, release)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", readyFile)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    } }
    companion object {
        @Volatile private var instance: GitHubUpdater? = null
        fun get(context: Context): GitHubUpdater = instance ?: synchronized(this) {
            instance ?: GitHubUpdater(context.applicationContext).also { instance = it }
        }
        private fun open(start: String): HttpURLConnection {
            var url = start
            repeat(5) {
                require(trustedUpdateDownload(url))
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "Cabin-Updater/${BuildConfig.VERSION_NAME}")
                try {
                    if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return connection
                    url = URL(URL(url), connection.getHeaderField("Location") ?: error("Missing redirect")).toString()
                } catch (e: Exception) { connection.disconnect(); throw e }
                connection.disconnect()
            }
            error("Too many redirects")
        }
        private suspend fun readText(url: String, limit: Int): String {
            val connection = open(url)
            try {
                requireUpdate(connection.responseCode == 200, R.string.update_error_server)
                return connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= limit); out.write(buffer, 0, n) }
                    out.toString("UTF-8")
                }
            } finally { connection.disconnect() }
        }
    }
}
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

@Suppress("DEPRECATION")
internal fun validateUpdatePackage(
    current: android.content.pm.PackageInfo,
    candidate: android.content.pm.PackageInfo,
    release: UpdateRelease,
    packageName: String,
    sdk: Int,
) {
    requireUpdate(candidate.packageName == packageName && candidate.splitNames.isNullOrEmpty(), R.string.update_error_apk)
    requireUpdate(PackageInfoCompat.getLongVersionCode(candidate) == release.versionCode && release.versionCode > PackageInfoCompat.getLongVersionCode(current), R.string.update_error_apk)
    requireUpdate(candidate.versionName == release.versionName, R.string.update_error_apk)
    requireUpdate(candidate.applicationInfo?.minSdkVersion?.let { it <= sdk } == true, R.string.update_error_android)
    fun certificates(info: android.content.pm.PackageInfo): Set<String> =
        (if (sdk >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty()
    val trusted = certificates(current)
    requireUpdate(trusted.isNotEmpty() && certificates(candidate) == trusted, R.string.update_error_signature)
}
