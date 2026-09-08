package com.cabin.widget

import com.cabin.localization.localizedString
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.cabin.CabinManager
import com.cabin.MainActivity
import com.cabin.R
import com.cabin.background.CabinProjectionService
import com.cabin.protocol.PhoneType

/** Launcher widget showing state and opening projection in a compact Activity panel. */
class CabinWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateWidgets(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    companion object {
        internal fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
        ) {
            val persisted = CabinWidgetState.read(context)
            // SharedPreferences survives process death; an old STREAMING value does not.
            // A registered manager is enough for progress; OPEN/live additionally
            // requires an actual stream, not a persisted old snapshot.
            val snapshot =
                if ((persisted.connectionState == CabinManager.State.STREAMING && !CabinProjectionService.hasActiveProcessSession()) ||
                    (persisted.connectionState != CabinManager.State.DISCONNECTED && !CabinProjectionService.hasRegisteredProcessManager())
                ) {
                    persisted.copy(
                        connectionState = CabinManager.State.DISCONNECTED,
                        status = context.localizedString(R.string.widget_default_status),
                    )
                } else {
                    persisted
                }
            val model = snapshot.toRenderModel(com.cabin.localization.AppLanguage.wrap(context).resources)
            appWidgetIds.forEach { widgetId ->
                val options = appWidgetManager.getAppWidgetOptions(widgetId)
                val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320)
                val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 128)
                val views = RemoteViews(context.packageName, if (width < 320) R.layout.cabin_widget_compact else R.layout.cabin_widget)
                views.setTextViewText(R.id.widget_title, model.title)
                views.setTextViewText(R.id.widget_status, model.status)
                views.setTextViewText(R.id.widget_action, model.action)
                views.setContentDescription(R.id.widget_root, context.localizedString(R.string.widget_root_description, model.title, model.status))
                views.setContentDescription(R.id.widget_action, model.actionDescription)
                views.setImageViewResource(R.id.widget_projection_icon, model.icon)
                views.setInt(R.id.widget_status, "setMaxLines", if (height >= 160) 2 else 1)
                views.setViewVisibility(R.id.widget_projection_icon, if (width >= 320) View.VISIBLE else View.GONE)
                views.setViewVisibility(
                    R.id.widget_live_indicator,
                    if (model.isLive) View.VISIBLE else View.INVISIBLE,
                )

                val openProjection =
                    Intent(context, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_SHOW_COMPACT_PROJECTION
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                val openPendingIntent =
                    PendingIntent.getActivity(
                        context,
                        widgetId,
                        openProjection,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent)
                val actionPendingIntent =
                    if (!model.connectInBackground) {
                        openPendingIntent
                    } else {
                        PendingIntent.getForegroundService(
                            context,
                            widgetId + BACKGROUND_REQUEST_OFFSET,
                            Intent(context, CabinProjectionService::class.java)
                                .setAction(CabinProjectionService.ACTION_CONNECT_PHONE),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    }
                views.setOnClickPendingIntent(R.id.widget_action, actionPendingIntent)
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        private const val BACKGROUND_REQUEST_OFFSET = 10_000
    }
}

internal data class WidgetRenderModel(
    val title: String,
    val status: String,
    val action: String,
    val isLive: Boolean,
    val connectInBackground: Boolean,
    val actionDescription: String,
    val icon: Int,
)

internal fun CabinWidgetState.Snapshot.toRenderModel(resources: android.content.res.Resources): WidgetRenderModel {
    val title =
        when (phoneType) {
            PhoneType.CARPLAY_WIRELESS -> resources.getString(R.string.widget_wireless_carplay)
            PhoneType.CARPLAY -> if (wifi == 1) resources.getString(R.string.widget_wireless_carplay) else "CarPlay"
            PhoneType.ANDROID_AUTO -> "Android Auto"
            PhoneType.ANDROID_MIRROR -> resources.getString(R.string.widget_android_mirror)
            PhoneType.IPHONE_MIRROR -> resources.getString(R.string.widget_iphone_mirror)
            PhoneType.CARLIFE -> "CarLife"
            PhoneType.HI_CAR -> "HiCar"
            PhoneType.ICCOA -> "ICCOA"
            PhoneType.UNKNOWN, null -> resources.getString(R.string.app_name)
        }
    val disconnected = connectionState == CabinManager.State.DISCONNECTED
    val live = connectionState == CabinManager.State.STREAMING
    return WidgetRenderModel(
        title = title,
        status =
            status.replace(Regex("[\\p{Cntrl}\\p{Cf}]+"), " ").trim().take(160).ifEmpty {
                when (connectionState) {
                    CabinManager.State.DISCONNECTED -> resources.getString(R.string.widget_default_status)
                    CabinManager.State.CONNECTING -> resources.getString(R.string.widget_connecting)
                    CabinManager.State.DEVICE_CONNECTED -> resources.getString(R.string.widget_starting)
                    CabinManager.State.STREAMING -> resources.getString(R.string.widget_active)
                }
            },
        action =
            if (live) {
                resources.getString(R.string.widget_action_open)
            } else if (disconnected) {
                resources.getString(R.string.widget_connect)
            } else {
                resources.getString(R.string.widget_action_view)
            },
        isLive = live,
        connectInBackground = disconnected,
        actionDescription =
            if (disconnected) {
                resources.getString(R.string.widget_connect_background)
            } else if (live) {
                resources.getString(R.string.action_open_projection)
            } else {
                resources.getString(R.string.widget_view_progress)
            },
        icon =
            when (phoneType) {
                PhoneType.ANDROID_AUTO, PhoneType.ANDROID_MIRROR -> R.drawable.ic_android_auto
                PhoneType.CARPLAY, PhoneType.CARPLAY_WIRELESS, PhoneType.IPHONE_MIRROR -> R.drawable.ic_carplay
                else -> R.drawable.ic_phone_projection
            },
    )
}
