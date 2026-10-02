package com.cabin.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cabin.R

/** Uses inexact alarms deliberately: users see that Android power policies may delay delivery. */
class ParkingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) { ParkingReminder.restore(context); return }
        val profile = intent.getIntExtra("profile", 0)
        val prefs = automationPreferences(context)
        val due = prefs.all["parkingExpires.$profile"] as? Long ?: return
        if(profile <= 0 || due > System.currentTimeMillis()) return
        if(!ParkingReminder.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("parking", context.getString(R.string.gv_parking_reminder), NotificationManager.IMPORTANCE_DEFAULT))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = launch?.let { PendingIntent.getActivity(context, profile, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        manager.notify(profile, NotificationCompat.Builder(context, "parking").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.gv_parking_reminder)).setContentText(context.getString(R.string.gv_parking_expired))
            .setContentIntent(pending).setAutoCancel(true).build())
        prefs.edit().putBoolean("parkingReminderDelivered.$profile", true).apply()
    }
}

object ParkingReminder {
    fun canNotify(context: Context): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    private fun pending(context: Context, profile: Int) = PendingIntent.getBroadcast(context, profile, Intent(context, ParkingReminderReceiver::class.java).setAction("com.cabin.PARKING_EXPIRED").putExtra("profile", profile), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(context: Context, profile: Int, time: Long) {
        require(profile > 0 && time > System.currentTimeMillis())
        automationPreferences(context).edit().putLong("parkingExpires.$profile", time).remove("parkingReminderDelivered.$profile").apply()
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(context, profile))
    }
    fun cancel(context: Context, profile: Int) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, profile))
        context.getSystemService(NotificationManager::class.java).cancel(profile)
        automationPreferences(context).edit().remove("parkingExpires.$profile").remove("parkingReminderDelivered.$profile").apply()
    }
    fun restore(context: Context) {
        val values = automationPreferences(context).all
        values.filterKeys { it.startsWith("parkingExpires.") }.forEach { (key, value) ->
            val profile = key.substringAfter('.').toIntOrNull() ?: return@forEach
            val due = value as? Long ?: return@forEach
            if(values["parkingReminderDelivered.$profile"] != true) {
                context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(System.currentTimeMillis() + 1_000, due), pending(context, profile))
            }
        }
    }
}

internal fun expireParking(prefs: android.content.SharedPreferences, now: Long = System.currentTimeMillis()): Set<Int> {
    val expired = prefs.all.filterKeys { it.startsWith("parkingTime.") }.mapNotNull { (key, value) ->
        val profile = key.substringAfter('.').toIntOrNull() ?: return@mapNotNull null
        val time = value as? Long ?: return@mapNotNull null
        val days = prefs.all["parkingRetention.$profile"] as? Int ?: 0
        profile.takeIf { days in listOf(1, 7, 30) && now >= time && now - time >= days * 86_400_000L }
    }.toSet()
    if(expired.isNotEmpty()) prefs.edit().apply { expired.forEach { profile ->
        listOf("parking.", "parkingTime.", "parkingNote.", "parkingAccuracy.", "parkingCapture.").forEach { remove(it + profile) }
    } }.apply()
    return expired
}
