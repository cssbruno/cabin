package com.cabin.platform

import android.content.Context
import android.content.Intent

/** Cabin's embedded Carlink client. Loads the bundled engine in Cabin’s private process. */
object JoyingCarPlay {
    fun isAvailable(context: Context): Boolean = bundledRuntimeAvailable(
        android.os.Build.VERSION.SDK_INT,
        android.os.Build.SUPPORTED_ABIS.firstOrNull(),
        context.applicationInfo.nativeLibraryDir,
    )

    internal fun bundledRuntimeAvailable(api: Int, abi: String?, directory: String?): Boolean =
        api == 29 && abi == "arm64-v8a" && directory != null &&
            listOf("libcabin_carlink.so", "libcps_7862.so", "libcarplay_plugin_r14g.so").all {
                java.io.File(directory, it).isFile
            }

    /** Open Cabin's own embedded projection page; never launch the vendor activity. */
    fun open(context: Context): Boolean = try {
        context.startActivity(Intent(context, com.cabin.MainActivity::class.java)
            .setAction(com.cabin.MainActivity.ACTION_SHOW_FULLSCREEN_PROJECTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
