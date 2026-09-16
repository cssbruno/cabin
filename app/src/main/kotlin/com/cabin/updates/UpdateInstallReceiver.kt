package com.cabin.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Explicit, non-exported PendingIntent target; survives the updating UI being destroyed. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || intent.data?.scheme != "cabin-update" || intent.data?.host != "session") return
        val id = intent.data?.lastPathSegment?.toIntOrNull() ?: return
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return
        GitHubUpdater.get(context).installResult(id,
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE))
    }
    companion object { const val ACTION = "com.cabin.UPDATE_INSTALL_RESULT" }
}
