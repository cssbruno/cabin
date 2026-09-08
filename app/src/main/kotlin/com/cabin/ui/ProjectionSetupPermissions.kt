package com.cabin.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.cabin.R

/** Android owns permission grants. This opens the actual app page without changing USB access. */
internal fun openProjectionAppPermissions(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.setup_permissions_unavailable), Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, context.getString(R.string.setup_permissions_unavailable), Toast.LENGTH_LONG).show()
    }
}
