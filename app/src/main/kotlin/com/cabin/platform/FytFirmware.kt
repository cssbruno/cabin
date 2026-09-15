package com.cabin.platform

import android.content.Context
import java.io.File
import java.security.MessageDigest

enum class TeyesVehicleDataLayout { UNKNOWN, LEGACY, CIVIC_0298, JOYING_2023 }

internal data class FytFirmware(val version: String, val layout: TeyesVehicleDataLayout, val sha256: String = "", val profiles: Map<Int, FytDetectedProfile> = emptyMap(),
    val resolveProfile: ((Int) -> FytDetectedProfile)? = null)

/** Read vendor bytecode on a worker, without loading vendor classes into Cabin. */
internal fun detectFytFirmware(context: Context): FytFirmware = FytFirmwareCache.detect(context)

private object FytFirmwareCache {
    private var cachedKey: String? = null
    private var cached: FytFirmware? = null

    @Synchronized fun detect(context: Context): FytFirmware {
        var version = ""
        return try {
            val info = context.packageManager.getPackageInfo("com.syu.ms", 0)
            version = info.versionName.orEmpty()
            val app = requireNotNull(info.applicationInfo)
            val apks = (listOf(requireNotNull(app.sourceDir)) + app.splitSourceDirs.orEmpty()).map(::File)
            val clientInfo = try { context.packageManager.getPackageInfo("com.syu.canbus", 0) } catch (_: Exception) { null }
            val clientApks = clientInfo?.applicationInfo?.let { client ->
                (listOfNotNull(client.sourceDir) + client.splitSourceDirs.orEmpty()).map(::File)
            }.orEmpty()
            val key = "${info.lastUpdateTime}:${clientInfo?.lastUpdateTime}:${context.resources.configuration.locales.toLanguageTags()}:" +
                (apks + clientApks).joinToString { "${it.path}:${it.length()}:${it.lastModified()}" }
            if (key == cachedKey) cached?.let { return it }
            val digest = MessageDigest.getInstance("SHA-256")
            apks.first().inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val resources = try { context.packageManager.getResourcesForApplication("com.syu.canbus") } catch (_: Exception) { null }
            val clientResolver = FytSyuClientCatalog.resolver(clientApks) { id ->
                try { resources?.getString(id) } catch (_: Exception) { null }
            }
            val serviceResolver = FytCodeDetector.resolver(apks, clientResolver)
            val result = FytFirmware(version, TeyesVehicleDataLayout.UNKNOWN, hash,
                resolveProfile = { profile ->
                    val service = serviceResolver(profile)
                    val client = clientResolver(profile)
                    service.copy(syuClient = client.copy(names = client.names.filterKeys { it in service.publishedFields }))
                })
            cachedKey = key
            cached = result
            result
        } catch (_: Exception) {
            FytFirmware(version, TeyesVehicleDataLayout.UNKNOWN, profiles = FytFieldSemantics.profiles.associateWith {
                FytDetectedProfile(it, emptyMap(), "apk_unavailable")
            })
        }
    }
}
