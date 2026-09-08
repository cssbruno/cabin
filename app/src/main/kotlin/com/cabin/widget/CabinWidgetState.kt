package com.cabin.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import com.cabin.CabinManager
import com.cabin.protocol.PhoneType

/**
 * Small persisted projection snapshot used by [CabinWidgetProvider].
 *
 * A widget can be rendered after the Activity/process that produced the state has gone
 * away, so its source of truth cannot be an in-memory CabinManager reference. Every
 * manager transition is saved to this private preference file and then pushed to all
 * active widget instances.
 */
object CabinWidgetState {
    private const val PREFERENCES = "carlink_widget_state"
    private const val KEY_CONNECTION_STATE = "connection_state"
    private const val KEY_PHONE_TYPE = "phone_type"
    private const val KEY_WIFI = "wifi"
    private const val KEY_STATUS = "status"
    private const val KEY_STATUS_LOCALE = "status_locale"

    data class Snapshot(
        val connectionState: CabinManager.State,
        val phoneType: PhoneType?,
        val wifi: Int?,
        val status: String,
    )

    fun updateProjection(
        context: Context,
        connectionState: CabinManager.State,
        phoneType: PhoneType?,
        wifi: Int?,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(KEY_CONNECTION_STATE, connectionState.name)
            if (phoneType == null || phoneType == PhoneType.UNKNOWN) {
                remove(KEY_PHONE_TYPE)
            } else {
                putString(KEY_PHONE_TYPE, phoneType.name)
            }
            if (wifi == null) {
                remove(KEY_WIFI)
            } else {
                putInt(KEY_WIFI, wifi)
            }
        }
        refreshWidgets(context)
    }

    fun updateStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(KEY_STATUS, status)
            putString(KEY_STATUS_LOCALE, context.resources.configuration.locales[0].toLanguageTag())
        }
        refreshWidgets(context)
    }

    fun read(context: Context): Snapshot {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return Snapshot(
            connectionState = preferences.getString(KEY_CONNECTION_STATE, null)
                ?.let { runCatching { CabinManager.State.valueOf(it) }.getOrNull() }
                ?: CabinManager.State.DISCONNECTED,
            phoneType = preferences.getString(KEY_PHONE_TYPE, null)
                ?.let { runCatching { PhoneType.valueOf(it) }.getOrNull() },
            wifi = if (preferences.contains(KEY_WIFI)) preferences.getInt(KEY_WIFI, 0) else null,
            status = if (preferences.getString(KEY_STATUS_LOCALE, null) == context.resources.configuration.locales[0].toLanguageTag()) {
                preferences.getString(KEY_STATUS, null).orEmpty()
            } else {
                ""
            },
        )
    }

    private fun refreshWidgets(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val component = ComponentName(appContext, CabinWidgetProvider::class.java)
        val widgetIds = manager.getAppWidgetIds(component)
        if (widgetIds.isNotEmpty()) {
            CabinWidgetProvider.updateWidgets(appContext, manager, widgetIds)
        }
    }
}
