package com.cabin.updates

import org.json.JSONObject
import java.net.URI

internal const val MAX_APK_BYTES = 200L * 1024 * 1024
internal data class GitHubRelease(val versionCode: Long, val url: String, val size: Long, val sha256: String?)

internal fun validUpdateRepository(value: String): Boolean =
    Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}").matches(value) &&
        value.substringAfter('/') !in setOf(".", "..")

/** Only the configured repository's stable, uploaded TEYES APK asset is eligible. */
internal fun parseGitHubRelease(body: String, repository: String, installedVersion: Long): GitHubRelease? {
    require(validUpdateRepository(repository) && body.length <= 1_048_576)
    val root = JSONObject(body)
    if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
    val assets = root.optJSONArray("assets") ?: return null
    require(assets.length() <= 200)
    return (0 until assets.length()).mapNotNull { index ->
        val asset = assets.optJSONObject(index) ?: return@mapNotNull null
        val match = Regex("teyes-([1-9][0-9]{0,9})\\.apk").matchEntire(asset.optString("name")) ?: return@mapNotNull null
        val version = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val size = asset.optLong("size", -1)
        if (version <= installedVersion || version > 2_100_000_000 || size !in 1..MAX_APK_BYTES || asset.optString("state") != "uploaded") return@mapNotNull null
        val url = asset.optString("browser_download_url")
        val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
        if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null ||
            !uri.path.startsWith("/$repository/releases/download/") || uri.path.split('/').any { it == "." || it == ".." } ||
            !uri.path.endsWith("/${asset.optString("name")}")) return@mapNotNull null
        val digest = asset.optString("digest").takeUnless { it.isBlank() || it == "null" }
        if (digest != null && !Regex("sha256:[a-fA-F0-9]{64}").matches(digest)) return@mapNotNull null
        GitHubRelease(version, url, size, digest?.substringAfter(':')?.lowercase())
    }.maxByOrNull { it.versionCode }
}

internal fun trustedUpdateDownload(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
        uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
}
