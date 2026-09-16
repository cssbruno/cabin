package com.cabin.platform

import android.content.Context

enum class TeyesVehicleDataLayout { UNKNOWN, LEGACY, CIVIC_0298, JOYING_2023 }

internal data class FytFirmware(val version: String, val layout: TeyesVehicleDataLayout, val sha256: String = "", val profiles: Map<Int, FytDetectedProfile> = emptyMap(),
    val resolveProfile: ((Int) -> FytDetectedProfile)? = null)

/** Select Cabin's own protocol implementation using package metadata and the live CAN profile. */
internal fun detectFytFirmware(context: Context): FytFirmware {
    val version = try { context.packageManager.getPackageInfo("com.syu.ms", 0).versionName.orEmpty() }
        catch (_: Exception) { return FytFirmware("", TeyesVehicleDataLayout.UNKNOWN) }
    if (!FytProtocolRegistry.supports(version)) return FytFirmware(version, TeyesVehicleDataLayout.UNKNOWN)
    return try {
        val registry = FytProtocolRegistry.load(context)
        FytFirmware(version, TeyesVehicleDataLayout.UNKNOWN, resolveProfile = registry::profile)
    } catch (_: Exception) {
        FytFirmware(version, TeyesVehicleDataLayout.UNKNOWN)
    }
}
