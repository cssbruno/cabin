package com.cabin.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.cabin.BuildConfig

/**
 * Tabs shown in the Settings screen's navigation rail.
 *
 * @property title Android string resource for the localized tab label.
 * @property icon Material icon rendered alongside [title] in the rail.
 *
 * Filtering contract: [visible] is the authoritative list of entries the UI
 * should render; callers must iterate [visible] (not [entries]) so build-flavor
 * gating is honored.
 *
 * SettingsScreen dispatches each visible tab. Declaration order defines navigation order.
 */
enum class SettingsTab(
    @androidx.annotation.StringRes val title: Int,
    val icon: ImageVector,
) {
    // Order determines tab display order in the navigation rail.
    PHONES(com.cabin.R.string.launcher_page_carplay, Icons.Default.PhoneAndroid),
    CCPA(com.cabin.R.string.carplay_ccpa_settings, Icons.Default.Settings),
    CONTROL(com.cabin.R.string.settings_tab_launcher, Icons.Default.Settings),
    CAR(com.cabin.R.string.car_settings_title, Icons.Default.DirectionsCar),
    TEYES(com.cabin.R.string.settings_tab_teyes, Icons.Default.Settings),
    LOGS(com.cabin.R.string.settings_tab_logs, Icons.AutoMirrored.Filled.Article),
    ;

    companion object {
        /** Vehicle settings share the FYT feature gate; preserve the existing log visibility. */
        val visible: List<SettingsTab>
            get() =
                entries.filter { tab ->
                    when (tab) {
                        // SUSPICIOUS: inverted predicate — hides LOGS in DEBUG,
                        // shows it in RELEASE. Reads backwards for a developer-aid
                        // tab; load-bearing, preserve as-is but re-verify product
                        // intent before touching.
                        LOGS -> !BuildConfig.DEBUG
                        CAR, TEYES -> BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE
                        else -> true
                    }
                }
    }
}
