package com.cabin.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.cabin.BuildConfig
import com.cabin.navigation.NavigationState
import com.cabin.util.LogCallback
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Compatibility metadata bridge for original Android 8.1 TEYES/FYT firmware.
 *
 * Modern consumers read Cabin's Media3 session directly. Older TEYES launchers use
 * the legacy Android Music broadcasts to copy the active player's metadata into the
 * vendor main module; the Honda Civic CANBUS profile then performs the actual, bounded
 * cluster write. Broadcasts are package-scoped and are never sent by non-TEYES builds.
 * This class deliberately does not write raw CAN frames or undocumented CANBUS commands.
 */
class TeyesClusterMediaBridge(
    context: Context,
    private val logCallback: LogCallback,
) {
    private val appContext = context.applicationContext
    private val enabled = BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE
    private val targets: List<String> by lazy(::findInstalledTargets)
    private val broadcastExecutor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "TeyesMediaBridge").apply { isDaemon = true }
        }

    private var metadata = ClusterMediaMetadata()
    private var mediaMetadata = ClusterMediaMetadata()
    private var navigationActive = false
    private var playing = false
    private var positionMs = 0L
    private var metadataPublished = false

    @Synchronized
    fun publishMetadata(
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
    ) {
        if (!enabled) return
        val next =
            ClusterMediaMetadata(
                title = fitHondaClusterText(title),
                artist = fitHondaClusterText(artist),
                album = fitHondaClusterText(album),
                durationMs = durationMs.coerceAtLeast(0L),
            )
        if (metadataPublished && next == mediaMetadata) return
        mediaMetadata = next
        metadataPublished = true
        if (!navigationActive) {
            metadata = next
            send(ACTION_META_CHANGED)
        }
    }

    /**
     * Show text-only turn guidance through TEYES' verified legacy metadata bridge.
     * Honda profile 0298 performs the final bounded cluster write. Bitmap/CAN injection
     * is intentionally absent because the Civic G10 path exposes no verified image API.
     */
    @Synchronized
    fun publishNavigation(state: NavigationState) {
        if (!enabled) return
        if (!state.isActive) {
            if (!navigationActive) return
            navigationActive = false
            metadata = mediaMetadata
            send(ACTION_META_CHANGED)
            return
        }

        val text = formatTeyesNavigation(state, MeasurementPreferences.get(appContext).unit.value)
        val next =
            ClusterMediaMetadata(
                title = text.maneuver,
                artist = text.road,
                album = text.progress,
            )
        if (navigationActive && metadata == next) return
        navigationActive = true
        metadata = next
        send(ACTION_META_CHANGED)
    }

    @Synchronized
    fun publishPlayback(playing: Boolean, positionMs: Long) {
        if (!enabled) return
        val safePosition = positionMs.coerceAtLeast(0L)
        if (this.playing == playing && this.positionMs == safePosition) return
        this.playing = playing
        this.positionMs = safePosition
        send(ACTION_PLAY_STATE_CHANGED)
    }

    @Synchronized
    fun clear() {
        if (!enabled || (!metadataPublished && !playing)) return
        playing = false
        positionMs = 0L
        navigationActive = false
        send(ACTION_PLAY_STATE_CHANGED)
        metadata = ClusterMediaMetadata()
        mediaMetadata = ClusterMediaMetadata()
        metadataPublished = false
        send(ACTION_META_CHANGED)
    }

    @Synchronized
    fun release() {
        clear()
        broadcastExecutor.shutdown()
    }

    private fun send(action: String) {
        val metadataSnapshot = metadata
        val playingSnapshot = playing
        val positionSnapshot = positionMs
        try {
            broadcastExecutor.execute {
                sendNow(action, metadataSnapshot, playingSnapshot, positionSnapshot)
            }
        } catch (_: RejectedExecutionException) {
            log("Metadata broadcast skipped after bridge release")
        }
    }

    private fun sendNow(
        action: String,
        metadata: ClusterMediaMetadata,
        playing: Boolean,
        positionMs: Long,
    ) {
        if (targets.isEmpty()) {
            log("No compatible TEYES media receiver installed; MediaSession remains active")
            return
        }
        for (target in targets) {
            val intent =
                Intent(action)
                    .setPackage(target)
                    .putExtra("track", metadata.title)
                    .putExtra("artist", metadata.artist)
                    .putExtra("album", metadata.album)
                    .putExtra("playing", playing)
                    .putExtra("duration", metadata.durationMs)
                    .putExtra("position", positionMs)
                    .putExtra("id", 0L)
            try {
                appContext.sendBroadcast(intent)
            } catch (error: RuntimeException) {
                log("Metadata broadcast rejected by $target: ${error.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findInstalledTargets(): List<String> {
        val packageManager = appContext.packageManager
        val installedVendorPackages =
            packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
                .asSequence()
                .map { it.packageName }
                .filter(::isVendorPackage)
                .toSet()

        // A receiver can move between TEYES firmware revisions. Resolve declared
        // receivers instead of relying only on package names; package-scoped sends also
        // reach a dynamically registered receiver in the same resolved package.
        val declaredReceiverPackages =
            sequenceOf(ACTION_META_CHANGED, ACTION_PLAY_STATE_CHANGED)
                .flatMap { action ->
                    packageManager.queryBroadcastReceivers(Intent(action), PackageManager.MATCH_ALL)
                        .asSequence()
                }
                .mapNotNull { it.activityInfo?.packageName }
                .filter(::isVendorPackage)
                .toSet()

        val likelyDynamicReceivers =
            installedVendorPackages.filter { packageName ->
                VENDOR_PACKAGES.contains(packageName) ||
                    DYNAMIC_RECEIVER_HINTS.any { hint -> packageName.contains(hint, ignoreCase = true) }
            }

        return (declaredReceiverPackages + likelyDynamicReceivers).sorted().also { installed ->
            log("Legacy cluster metadata targets: ${installed.joinToString().ifEmpty { "none" }}")
        }
    }

    private fun isVendorPackage(packageName: String): Boolean =
        packageName == "com.syu" || packageName.startsWith("com.syu.") ||
            packageName == "com.teyes" || packageName.startsWith("com.teyes.")

    private fun log(message: String) {
        logCallback.log("[TEYES_CLUSTER_MEDIA] $message")
    }

    private data class ClusterMediaMetadata(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0L,
    )

    companion object {
        private const val ACTION_META_CHANGED = "com.android.music.metachanged"
        private const val ACTION_PLAY_STATE_CHANGED = "com.android.music.playstatechanged"

        private val VENDOR_PACKAGES =
            listOf(
                "com.syu.music",
                "com.syu.launcher",
                "com.syu.ms",
                "com.syu.canbus",
                "com.teyes.music",
                "com.teyes.launcher",
                "com.teyes",
            )

        private val DYNAMIC_RECEIVER_HINTS =
            listOf("music", "media", "launcher", "canbus", "bt", ".ms")
    }
}

/** Honda profile 0298 accepts at most 15 Unicode code points per cluster field. */
internal fun fitHondaClusterText(value: String?): String {
    val cleaned =
        value.orEmpty()
            .map { character -> if (character.isWhitespace()) ' ' else character }
            .filterNot { character ->
                Character.isISOControl(character) || character in '\u202A'..'\u202E' ||
                    character in '\u2066'..'\u2069'
            }
            .joinToString(separator = "")
            .replace(Regex("\\s+"), " ")
            .trim()
    if (cleaned.isEmpty()) return ""
    val end = cleaned.offsetByCodePoints(0, minOf(15, cleaned.codePointCount(0, cleaned.length)))
    return cleaned.substring(0, end)
}

internal data class TeyesNavigationText(
    val maneuver: String,
    val road: String,
    val progress: String,
)

/** Produce only short printable fields known to fit Honda profile 0298. */
internal fun formatTeyesNavigation(state: NavigationState, measurementUnit: MeasurementUnit = MeasurementUnit.METRIC): TeyesNavigationText {
    // NavigationStateManager normalizes Android Auto order types to the same
    // CPManeuverType used by CarPlay. CarPlay does not populate orderType.
    val maneuver =
        when (state.maneuverType) {
            1, 20 -> "TURN LEFT"
            2, 21 -> "TURN RIGHT"
            3 -> "STRAIGHT"
            4, 18, 26 -> "U-TURN"
            6, 19 -> "ROUNDABOUT"
            7 -> if (state.roundaboutExit > 0) "EXIT ${state.roundaboutExit.coerceIn(1, 19)}" else "ROUNDABOUT"
            8 -> "EXIT"
            9 -> "RAMP"
            10, 12, 24, 25, 27 -> "ARRIVED"
            11 -> "DEPART"
            13, 52 -> "KEEP LEFT"
            14, 51, 53 -> "KEEP RIGHT"
            15, 16, 17 -> "FERRY"
            22 -> "EXIT LEFT"
            23 -> "EXIT RIGHT"
            in 28..46 -> "EXIT ${state.maneuverType - 27}"
            47 -> "SHARP LEFT"
            48 -> "SHARP RIGHT"
            49 -> "SLIGHT LEFT"
            50 -> "SLIGHT RIGHT"
            else -> "CONTINUE"
        }

    val distance = if (state.remainDistance > 0) {
        MeasurementFormatter.distance(state.remainDistance.toDouble(), measurementUnit)
    } else ""
    val minutes = if (state.timeToDestination > 0) "${(state.timeToDestination + 59) / 60} min" else ""
    val progress = listOf(distance, minutes).filter { it.isNotEmpty() }.joinToString(" | ")

    return TeyesNavigationText(
        maneuver = fitHondaClusterText(maneuver),
        road = fitHondaClusterText(state.roadName ?: state.destinationName),
        progress = fitHondaClusterText(progress),
    )
}
