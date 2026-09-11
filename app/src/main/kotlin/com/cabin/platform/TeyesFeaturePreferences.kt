package com.cabin.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TeyesAppearance(val label: String) { SYSTEM("Follow head unit"), DAY("Day"), NIGHT("Night") }

data class TeyesDriverProfile(
    val slot: Int = 0,
    val name: String = "Driver 1",
    val preferredPhone: String = "",
    val appearance: TeyesAppearance = TeyesAppearance.SYSTEM,
    val nightBrightness: Float = 0.35f,
    val mediaGain: Float = 1f,
    val navigationGain: Float = 1f,
    val resumeOnWake: Boolean = false,
    val recoverOverlays: Boolean = true,
    val compactOnLaunch: Boolean = false,
)

/** App-private profiles. No vehicle settings, CAN writes, or system brightness changes. */
class TeyesFeaturePreferences internal constructor(context: Context) {
    internal val resources: android.content.res.Resources = context.applicationContext.resources
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("teyes_features_v1", Context.MODE_PRIVATE)
    private val mutableProfile = MutableStateFlow(readProfile((prefs.all["active"] as? Int ?: 0).coerceIn(0, 2)))
    val profile = mutableProfile.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    /** Changes to profiles, shortcuts and learned keys, including imported configuration. */
    val revision = mutableRevision.asStateFlow()

    @Synchronized
    fun select(slot: Int) {
        require(slot in 0..2)
        prefs.edit { putInt("active", slot) }
        mutableProfile.value = readProfile(slot)
        mutableRevision.value++
    }

    @Synchronized
    fun update(transform: (TeyesDriverProfile) -> TeyesDriverProfile) {
        val current = mutableProfile.value
        val next = transform(current).copy(slot = current.slot).normalized()
        prefs.edit { putProfile(next) }
        mutableProfile.value = next
        mutableRevision.value++
    }

    private fun readProfile(slot: Int): TeyesDriverProfile {
        val prefix = "driver.$slot."
        // SharedPreferences typed getters throw if older/corrupt data used another type.
        val values = prefs.all
        return TeyesDriverProfile(
            slot = slot,
            name = values[prefix + "name"] as? String ?: resources.getString(com.cabin.R.string.teyes_driver_slot, slot + 1),
            preferredPhone = values[prefix + "phone"] as? String ?: "",
            appearance =
                TeyesAppearance.entries.firstOrNull { it.name == values[prefix + "appearance"] }
                    ?: TeyesAppearance.SYSTEM,
            nightBrightness = values[prefix + "brightness"] as? Float ?: 0.35f,
            mediaGain = values[prefix + "media"] as? Float ?: 1f,
            navigationGain = values[prefix + "nav"] as? Float ?: 1f,
            resumeOnWake = values[prefix + "wake"] as? Boolean ?: false,
            recoverOverlays = values[prefix + "overlays"] as? Boolean ?: true,
            compactOnLaunch = values[prefix + "compact"] as? Boolean ?: false,
        ).normalized()
    }

    fun keyAction(keyCode: Int, longPress: Boolean = false): TeyesKeyAction? =
        if (TeyesKeyRouter.isMappable(keyCode)) TeyesKeyAction.entries.firstOrNull { it.name == prefs.all["${if (longPress) "longKey" else "key"}.$keyCode"] } else null

    @Synchronized
    fun forgetPhone(btMac: String) {
        prefs.edit {
            for (slot in 0..2) {
                val key = "driver.$slot.phone"
                if ((prefs.all[key] as? String).equals(btMac, ignoreCase = true)) remove(key)
            }
        }
        mutableProfile.value = readProfile(mutableProfile.value.slot)
        mutableRevision.value++
    }

    fun mappedKeys(longPress: Boolean = false): Map<Int, TeyesKeyAction> =
        prefs.all.keys.mapNotNull { key ->
            val prefix = if (longPress) "longKey." else "key."
            val code = key.removePrefix(prefix).toIntOrNull()
            if (key.startsWith(prefix) && code != null) keyAction(code, longPress)?.let { code to it } else null
        }.toMap()

    @Synchronized
    fun mapKey(
        keyCode: Int,
        action: TeyesKeyAction?,
        longPress: Boolean = false,
    ) {
        require(TeyesKeyRouter.isMappable(keyCode))
        val key = "${if (longPress) "longKey" else "key"}.$keyCode"
        prefs.edit { if (action == null) remove(key) else putString(key, action.name) }
        mutableRevision.value++
    }

    fun shortcut(kind: TeyesShortcut): String? =
        (prefs.all["shortcut.${kind.name}"] as? String)?.takeIf(TeyesConfigurationBackup::validComponent)

    @Synchronized
    fun setShortcut(
        kind: TeyesShortcut,
        component: String?,
    ) {
        require(component == null || TeyesConfigurationBackup.validComponent(component))
        prefs.edit { if (component == null) remove("shortcut.${kind.name}") else putString("shortcut.${kind.name}", component) }
        mutableRevision.value++
    }

    @Synchronized
    fun configurationSnapshot(): TeyesConfigurationSnapshot =
        TeyesConfigurationSnapshot(
            profiles = (0..2).map(::readProfile),
            keys = mappedKeys(),
            longKeys = mappedKeys(longPress = true),
            shortcuts = TeyesShortcut.entries.mapNotNull { kind -> shortcut(kind)?.let { kind to it } }.toMap(),
            projection = ProjectionPreferences.getInstance(appContext).state.value,
            measurementUnit = MeasurementPreferences.get(appContext).unit.value,
        )

    /** Validates every section before writes. Keeps the current driver selected; legacy imports preserve presentation. */
    @Synchronized
    fun replaceConfiguration(snapshot: TeyesConfigurationSnapshot): Boolean {
        TeyesConfigurationBackup.validate(snapshot)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("driver.") || it.startsWith("key.") || it.startsWith("longKey.") || it.startsWith("shortcut.") }.forEach(editor::remove)
        snapshot.profiles.forEach { editor.putProfile(it) }
        snapshot.keys.forEach { (key, action) -> editor.putString("key.$key", action.name) }
        snapshot.longKeys.forEach { (key, action) -> editor.putString("longKey.$key", action.name) }
        snapshot.shortcuts.forEach { (kind, component) -> editor.putString("shortcut.${kind.name}", component) }
        // commit is used by the IO caller so success means persistence completed.
        val profilesPersisted = editor.commit()
        val projectionPersisted = snapshot.projection?.let { ProjectionPreferences.getInstance(appContext).replace(it) } ?: true
        val measurementPersisted = snapshot.measurementUnit?.let { MeasurementPreferences.get(appContext).replace(it) } ?: true
        mutableProfile.value = readProfile(mutableProfile.value.slot)
        mutableRevision.value++
        return profilesPersisted && projectionPersisted && measurementPersisted
    }

    private fun SharedPreferences.Editor.putProfile(profile: TeyesDriverProfile) {
        val prefix = "driver.${profile.slot}."
        putString(prefix + "name", profile.name)
        putString(prefix + "phone", profile.preferredPhone)
        putString(prefix + "appearance", profile.appearance.name)
        putFloat(prefix + "brightness", profile.nightBrightness)
        putFloat(prefix + "media", profile.mediaGain)
        putFloat(prefix + "nav", profile.navigationGain)
        putBoolean(prefix + "wake", profile.resumeOnWake)
        putBoolean(prefix + "overlays", profile.recoverOverlays)
        putBoolean(prefix + "compact", profile.compactOnLaunch)
    }

    companion object {
        @Volatile private var instance: TeyesFeaturePreferences? = null

        fun get(context: Context): TeyesFeaturePreferences =
            instance ?: synchronized(this) {
                instance ?: TeyesFeaturePreferences(context).also { instance = it }
            }

        fun validPhone(value: String): Boolean = Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(value)

        fun TeyesDriverProfile.normalized(): TeyesDriverProfile =
            copy(
                name = name.filterNot { it.isISOControl() }.trim().take(32).ifEmpty { "Driver ${slot + 1}" },
                preferredPhone = preferredPhone.takeIf(::validPhone).orEmpty(),
                nightBrightness = nightBrightness.takeIf { it.isFinite() }?.coerceIn(0.1f, 1f) ?: 0.35f,
                mediaGain = mediaGain.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
                navigationGain = navigationGain.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
            )
    }
}

/** Localized presentation; enum names remain stable for saved configuration. */
@get:androidx.annotation.StringRes
val TeyesAppearance.labelRes: Int
    get() = when (this) {
        TeyesAppearance.SYSTEM -> com.cabin.R.string.teyes_appearance_system
        TeyesAppearance.DAY -> com.cabin.R.string.teyes_appearance_day
        TeyesAppearance.NIGHT -> com.cabin.R.string.teyes_appearance_night
    }
