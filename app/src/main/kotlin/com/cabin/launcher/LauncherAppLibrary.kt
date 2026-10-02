package com.cabin.launcher

import android.content.Context
import com.cabin.platform.TeyesLaunchableApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

enum class LauncherAppSort { PINNED, NAME, NAME_DESCENDING, RECENT, FREQUENT }
enum class LauncherAppFilter { ALL, PINNED, RECENT, INSTALLED, HIDDEN }
data class LauncherAppUsage(val lastOpened: Long, val launches: Int)
data class LauncherLibraryState(
    val sort: LauncherAppSort = LauncherAppSort.PINNED,
    val filter: LauncherAppFilter = LauncherAppFilter.ALL,
    val hidden: Set<String> = emptySet(),
    val names: Map<String, String> = emptyMap(),
    val usage: Map<String, LauncherAppUsage> = emptyMap(),
    val showPackages: Boolean = false,
    val recordHistory: Boolean = false,
    val folders: Map<String, String> = emptyMap(),
    val appFolders: Map<String, String> = emptyMap(),
    val selectedFolder: String? = null,
    val compact: Boolean = false,
    val recentDays: Int = 7,
) {
    fun label(app: TeyesLaunchableApp): String = names[app.component] ?: app.label
}

/** Only successful launches from Cabin are recorded, locally and separately for each driver. */
class LauncherAppLibrary(context: Context, private val driverSlot: Int) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val key = "driver.$driverSlot"
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()

    internal var beforeMutation: (() -> Unit)? = null
    internal fun restore(value: LauncherLibraryState) = save(value)
    fun selectFolder(id: String?) = save(read().copy(selectedFolder = id?.takeIf { it in read().folders }))
    fun setCompact(value: Boolean) = save(read().copy(compact = value))
    fun setRecentDays(value: Int) = save(read().copy(recentDays = value.coerceIn(1, 365)))
    fun createFolder(name: String): String? {
        val clean = cleanName(name)
        val current = read()
        if (clean.isEmpty() || current.folders.size >= MAX_FOLDERS) return null
        val id = java.util.UUID.randomUUID().toString()
        beforeMutation?.invoke()
        save(current.copy(folders = current.folders + (id to clean)))
        return id
    }
    fun renameFolder(id: String, name: String) {
        val current = read(); val clean = cleanName(name)
        if (id !in current.folders || clean.isEmpty()) return
        beforeMutation?.invoke()
        save(current.copy(folders = current.folders + (id to clean)))
    }
    fun removeFolder(id: String) {
        val current = read()
        if (id !in current.folders) return
        beforeMutation?.invoke()
        save(current.copy(folders = current.folders - id, appFolders = current.appFolders.filterValues { it != id }, selectedFolder = current.selectedFolder.takeUnless { it == id }))
    }
    fun moveToFolder(component: String, id: String?) {
        require(validLauncherComponent(component))
        val current = read()
        if (id != null && id !in current.folders) return
        beforeMutation?.invoke()
        save(current.copy(appFolders = if (id == null) current.appFolders - component else (current.appFolders + (component to id)).entries.take(MAX_APPS).associate { it.toPair() }))
    }
    private fun cleanName(value: String) = value.filterNot(Char::isISOControl).trim().take(MAX_NAME)
    fun refresh() { mutableState.value = read() }
    fun setSort(value: LauncherAppSort) = save(read().copy(sort = value))
    fun setFilter(value: LauncherAppFilter) = save(read().copy(filter = value))
    fun showPackages(value: Boolean) = save(read().copy(showPackages = value))
    fun recordHistory(value: Boolean) = save(read().let {
        it.copy(recordHistory = value, usage = if (value) it.usage else emptyMap())
    })
    fun clearHistory() = save(read().copy(usage = emptyMap()))
    fun hide(component: String, hidden: Boolean) {
        require(validLauncherComponent(component))
        val current = read()
        beforeMutation?.invoke()
        save(current.copy(hidden = if (hidden) (current.hidden + component).take(MAX_APPS).toSet() else current.hidden - component))
    }
    fun rename(component: String, name: String) {
        require(validLauncherComponent(component))
        val current = read()
        val cleaned = name.filterNot(Char::isISOControl).trim().take(MAX_NAME)
        val names = if (cleaned.isEmpty()) current.names - component else current.names + (component to cleaned)
        beforeMutation?.invoke()
        save(current.copy(names = names.entries.take(MAX_APPS).associate { it.toPair() }))
    }
    fun recordLaunch(component: String, now: Long = System.currentTimeMillis()) {
        require(validLauncherComponent(component))
        val current = read()
        if (!current.recordHistory || now <= 0) return
        val previous = current.usage[component]
        val usage = current.usage + (component to LauncherAppUsage(now, ((previous?.launches ?: 0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        save(current.copy(usage = usage.entries.sortedByDescending { it.value.lastOpened }.take(MAX_APPS).associate { it.toPair() }))
    }

    private fun save(value: LauncherLibraryState) {
        val json = JSONObject().put("sort", value.sort.name).put("filter", value.filter.name)
            .put("showPackages", value.showPackages).put("recordHistory", value.recordHistory)
            .put("hidden", JSONArray(value.hidden.toList())).put("names", JSONObject(value.names))
            .put("folders", JSONObject(value.folders)).put("appFolders", JSONObject(value.appFolders))
            .put("selectedFolder", value.selectedFolder).put("compact", value.compact).put("recentDays", value.recentDays)
        val usage = JSONObject()
        value.usage.forEach { (component, entry) -> usage.put(component, JSONObject().put("last", entry.lastOpened).put("count", entry.launches)) }
        json.put("usage", usage)
        preferences.edit().putString(key, json.toString()).apply()
        mutableState.value = value
    }

    private fun read(): LauncherLibraryState = try {
        val saved = preferences.all[key] as? String
        val json = if (saved != null && saved.length <= 512_000) JSONObject(saved) else JSONObject()
        val hidden = json.optJSONArray("hidden") ?: JSONArray()
        val names = json.optJSONObject("names") ?: JSONObject()
        val usage = json.optJSONObject("usage") ?: JSONObject()
        fun stringMap(field: String): Map<String, String> = json.optJSONObject(field)?.let { obj ->
            obj.keys().asSequence().take(MAX_APPS).mapNotNull { k -> (obj.opt(k) as? String)?.let { k to cleanName(it) } }.toMap()
        } ?: emptyMap()
        val folders = stringMap("folders").filter { (key, value) -> key.length <= 60 && value.isNotBlank() }.entries.take(MAX_FOLDERS).associate { it.toPair() }
        val recording = json.optBoolean("recordHistory", false)
        LauncherLibraryState(
            sort = LauncherAppSort.entries.firstOrNull { it.name == json.optString("sort") } ?: LauncherAppSort.PINNED,
            filter = LauncherAppFilter.entries.firstOrNull { it.name == json.optString("filter") } ?: LauncherAppFilter.ALL,
            hidden = (0 until minOf(hidden.length(), MAX_APPS)).mapNotNull { hidden.opt(it) as? String }.filter(::validLauncherComponent).toSet(),
            names = names.keys().asSequence().take(MAX_APPS).filter(::validLauncherComponent).mapNotNull { component ->
                (names.opt(component) as? String)?.filterNot(Char::isISOControl)?.trim()?.take(MAX_NAME)?.takeIf(String::isNotEmpty)?.let { component to it }
            }.toMap(),
            usage = if (!recording) emptyMap() else usage.keys().asSequence().take(MAX_APPS).filter(::validLauncherComponent).mapNotNull { component ->
                usage.optJSONObject(component)?.let { entry ->
                    val last = entry.optLong("last", 0)
                    val count = entry.optInt("count", 0)
                    if (last > 0 && count > 0) component to LauncherAppUsage(last, count) else null
                }
            }.toMap(),
            showPackages = json.optBoolean("showPackages", false),
            recordHistory = recording,
            folders = folders,
            appFolders = stringMap("appFolders").filter { (component, folder) -> validLauncherComponent(component) && folder in folders },
            selectedFolder = json.optString("selectedFolder").takeIf { it in folders },
            compact = json.optBoolean("compact", false),
            recentDays = json.optInt("recentDays", 7).coerceIn(1, 365),
        )
    } catch (_: Exception) { LauncherLibraryState() }

    companion object {
        const val FILE = "cabin_launcher_library_v1"
        const val MAX_APPS = 256
        const val MAX_FOLDERS = 32
        const val MAX_NAME = 60
    }
}

internal fun normalizedLauncherText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

internal fun filterLauncherApps(apps: List<TeyesLaunchableApp>, query: String): List<TeyesLaunchableApp> {
    val tokens = normalizedLauncherText(query.trim()).split(Regex("\\s+")).filter(String::isNotEmpty)
    return apps.filter { app ->
        val searchable = normalizedLauncherText("${app.label} ${app.component.substringBefore('/')}")
        tokens.all(searchable::contains)
    }
}

internal fun launcherLibraryApps(
    apps: List<TeyesLaunchableApp>, query: String, favorites: List<String>, state: LauncherLibraryState,
    installedAt: Map<String, Long> = emptyMap(), now: Long = System.currentTimeMillis(),
): List<TeyesLaunchableApp> {
    val filtered = apps.distinctBy { it.component }.filter { state.selectedFolder == null || state.appFolders[it.component] == state.selectedFolder }.filter { app ->
        if (state.filter == LauncherAppFilter.HIDDEN) app.component in state.hidden
        else app.component !in state.hidden && when (state.filter) {
            LauncherAppFilter.PINNED -> app.component in favorites
            LauncherAppFilter.RECENT -> app.component in state.usage
            LauncherAppFilter.INSTALLED -> installedAt[app.component]?.let { it > 0 && it <= now && now - it <= state.recentDays * 86_400_000L } == true
            else -> true
        }
    }
    // Keep the original installed label searchable even after the driver gives an app a nickname.
    val matches = filtered.filter { app ->
        filterLauncherApps(listOf(app.copy(label = "${state.label(app)} ${app.label}")), query).isNotEmpty()
    }.map { it.copy(label = state.label(it)) }
    val byName = compareBy<TeyesLaunchableApp> { normalizedLauncherText(it.label) }.thenBy { it.component }
    val comparator = when (state.sort) {
        LauncherAppSort.PINNED -> compareBy<TeyesLaunchableApp> { favorites.indexOf(it.component).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.then(byName)
        LauncherAppSort.NAME -> byName
        LauncherAppSort.NAME_DESCENDING -> byName.reversed()
        LauncherAppSort.RECENT -> compareByDescending<TeyesLaunchableApp> { state.usage[it.component]?.lastOpened ?: 0L }.then(byName)
        LauncherAppSort.FREQUENT -> compareByDescending<TeyesLaunchableApp> { state.usage[it.component]?.launches ?: 0 }.then(byName)
    }
    return matches.sortedWith(comparator)
}
