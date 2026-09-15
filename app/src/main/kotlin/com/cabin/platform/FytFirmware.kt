package com.cabin.platform

import android.content.Context
import java.io.File
import java.security.MessageDigest

enum class TeyesVehicleDataLayout { UNKNOWN, LEGACY, CIVIC_0298, JOYING_2023 }

internal data class FytFirmware(val version: String, val layout: TeyesVehicleDataLayout, val sha256: String = "")

/** Match the installed vendor APK, not the vehicle model or a saved user guess. Worker only. */
internal fun detectFytFirmware(context: Context): FytFirmware {
    var version = ""
    return try {
        val info = context.packageManager.getPackageInfo("com.syu.ms", 0)
        version = info.versionName.orEmpty()
        val path = requireNotNull(info.applicationInfo?.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        FytFirmware(version, fytFirmwareLayout(hash), hash)
    } catch (_: Exception) {
        FytFirmware(version, TeyesVehicleDataLayout.UNKNOWN)
    }
}

internal fun fytFirmwareLayout(sha256: String): TeyesVehicleDataLayout = when (sha256) {
    // Inspected Joying com.syu.ms 2.23.0711.1001. See JOYING-REPLACEMENT-RUNTIME.md and JOYING-FIRMWARE-FIELDS.md.
    "4b428302e29c9e2503ccf7844a127f5bed59450736317629aa42b5eb35a9b577" -> TeyesVehicleDataLayout.JOYING_2023
    else -> TeyesVehicleDataLayout.UNKNOWN
}
