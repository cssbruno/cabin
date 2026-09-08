package com.cabin.gnss

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** Android 12 requires the pair in one request; GNSS still requires precise location. */
internal class GnssPermissionContract : ActivityResultContract<Unit, Boolean>() {
    private val permissions = ActivityResultContracts.RequestMultiplePermissions()

    override fun createIntent(context: Context, input: Unit): Intent =
        permissions.createIntent(context, requestedPermissions())

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        permissions.parseResult(resultCode, intent)[Manifest.permission.ACCESS_FINE_LOCATION] == true

    override fun getSynchronousResult(context: Context, input: Unit): SynchronousResult<Boolean>? =
        permissions.getSynchronousResult(context, requestedPermissions())?.let {
            SynchronousResult(it.value[Manifest.permission.ACCESS_FINE_LOCATION] == true)
        }

    private fun requestedPermissions() = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
}
