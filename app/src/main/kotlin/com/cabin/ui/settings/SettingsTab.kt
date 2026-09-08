package com.cabin.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.PhoneAndroid
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
 * Cross-file contract: adding/removing an entry requires updates at
 * SettingsScreen.kt:263-267 (exhaustive `when`), :102 (default `selectedTab`),
 * and :172 (tab rendering loop). Declaration order below is load-bearing.
 */
enum class SettingsTab(
    @androidx.annotation.StringRes val title: Int,
    val icon: ImageVector,
) {
    // Order determines tab display order in the navigation rail.
    PHONES(com.cabin.R.string.settings_tab_phones, Icons.Default.PhoneAndroid),
    CONTROL(com.cabin.R.string.settings_tab_control, Icons.Default.Settings),
    TEYES(com.cabin.R.string.settings_tab_teyes, Icons.Default.Settings),
    LOGS(com.cabin.R.string.settings_tab_logs, Icons.AutoMirrored.Filled.Article),
    ;

    companion object {
        /**
         * Entries filtered by current build flavor.
         *
         * Recomputed on every access (called per recomposition at
         * SettingsScreen.kt:172); intentional and cheap — three-entry filter
         * over a compile-time-constant condition, results are not cached.
         *
         * Caveat: no invariant pins `selectedTab in visible`. If a caller ends
         * up with a `selectedTab` absent from `visible` (e.g. LOGS while hidden),
         * the rail will show no selection while the content pane still renders
         * that tab.
         */
        val visible: List<SettingsTab>
            get() =
                entries.filter { tab ->
                    when (tab) {
                        // SUSPICIOUS: inverted predicate — hides LOGS in DEBUG,
                        // shows it in RELEASE. Reads backwards for a developer-aid
                        // tab; load-bearing, preserve as-is but re-verify product
                        // intent before touching.
                        LOGS -> !BuildConfig.DEBUG
                        TEYES -> BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE
                        else -> true
                    }
                }
    }
}
