package com.oak404.redcast.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** The phone's own record of taste and saved lists. Nothing here leaves the device. */
class Library(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)

    // Every clip seen changes taste and history, and each save rewrites the whole prefs file, so
    // saves run off the main thread and a burst of changes to one key becomes a single write.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val pendingWrites = HashMap<String, Job>()

    private val _taste = MutableStateFlow(readTaste())
    val taste: StateFlow<Taste> = _taste.asStateFlow()

    private val _lists = MutableStateFlow(readLists())
    val lists: StateFlow<Map<String, List<Gif>>> = _lists.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _history = MutableStateFlow(readGifs(KEY_HISTORY))
    val history: StateFlow<List<Gif>> = _history.asStateFlow()

    private val _searches = MutableStateFlow(readStrings(KEY_SEARCHES))
    val searches: StateFlow<List<String>> = _searches.asStateFlow()

    private val _savedSearches = MutableStateFlow(readSavedSearches())

    /** Searches kept on the Searches tab, newest saved first. */
    val savedSearches: StateFlow<List<SavedSearch>> = _savedSearches.asStateFlow()

    var ageConfirmed: Boolean
        get() = prefs.getBoolean(KEY_AGE, false)
        set(value) = prefs.edit().putBoolean(KEY_AGE, value).apply()

    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_CHECK, value).apply()

    var skippedVersion: String?
        get() = prefs.getString(KEY_SKIPPED_VERSION, null)
        set(value) = prefs.edit().putString(KEY_SKIPPED_VERSION, value).apply()

    var swipeHintSeen: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_SWIPE_HINT, value).apply()

    var seededFromLikes: Boolean
        get() = prefs.getBoolean(KEY_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_SEEDED, value).apply()

    fun updateTaste(change: (Taste) -> Taste) {
        _taste.value = change(_taste.value)
        persist(KEY_TASTE) { _taste.value.toJson().toString() }
    }

    fun updateSettings(change: (Settings) -> Settings) {
        _settings.value = change(_settings.value)
        persist(KEY_SETTINGS) { _settings.value.toJson().toString() }
    }

    fun addToHistory(gif: Gif) = saveHistory(pushRecent(_history.value, gif, MAX_HISTORY) { a, b -> a.id == b.id })

    fun clearHistory() = saveHistory(emptyList())

    fun addSearch(text: String) = saveSearches(pushRecent(_searches.value, text, MAX_SEARCHES))

    fun clearSearches() = saveSearches(emptyList())

    /** Keeps [query] on the Searches tab; clips posted before [nowSec] don't count as new. */
    fun saveSearch(query: SearchQuery, nowSec: Long) {
        if (_savedSearches.value.none { it.query == query }) {
            saveSavedSearches(listOf(SavedSearch(query, nowSec)) + _savedSearches.value)
        }
    }

    fun unsaveSearch(query: SearchQuery) = saveSavedSearches(_savedSearches.value.filterNot { it.query == query })

    /** Opening a saved search catches it up, so only clips posted after now count as new. */
    fun markSearchSeen(query: SearchQuery, nowSec: Long) {
        if (_savedSearches.value.none { it.query == query }) return
        saveSavedSearches(_savedSearches.value.map { if (it.query == query) it.copy(seenUpTo = nowSec) else it })
    }

    private fun saveSavedSearches(searches: List<SavedSearch>) {
        _savedSearches.value = searches
        persist(KEY_SAVED_SEARCHES) { savedSearchesJson(_savedSearches.value).toString() }
    }

    /** Everything kept here. Sign-in tokens live in a separate store and are never included. */
    fun exportJson(): JSONObject = JSONObject()
        .put("app", EXPORT_APP)
        .put("settings", _settings.value.toJson())
        .put("taste", _taste.value.toJson())
        .put("lists", listsJson(_lists.value))
        .put("history", gifsJson(_history.value))
        .put("searches", JSONArray(_searches.value))
        .put("savedSearches", savedSearchesJson(_savedSearches.value))

    /**
     * Folds an [exportJson] file into what's here. Lists, history, searches and taste are merged,
     * so nothing on this phone is lost; settings are taken from the file.
     */
    fun importJson(json: JSONObject): ImportResult {
        if (EXPORT_KEYS.none(json::has)) throw IOException("That file isn't a Redcast export")
        val imported = readExport(json)

        imported.settings?.let { settings -> updateSettings { settings } }
        imported.taste?.let { taste -> updateTaste { it.mergedWith(taste) } }
        saveLists(mergeLists(_lists.value, imported.lists))
        saveHistory(mergeRecent(_history.value, imported.history, MAX_HISTORY) { it.id })
        saveSearches(mergeRecent(_searches.value, imported.searches, MAX_SEARCHES) { it })
        saveSavedSearches(mergeRecent(_savedSearches.value, imported.savedSearches, Int.MAX_VALUE) { it.query })

        return ImportResult(
            lists = imported.lists.size,
            clips = imported.lists.values.sumOf { it.size },
            history = imported.history.size,
            savedSearches = imported.savedSearches.size,
            mutedWords = imported.taste?.mutedWords?.size ?: 0,
        )
    }

    private fun saveHistory(gifs: List<Gif>) {
        _history.value = gifs
        persist(KEY_HISTORY) { gifsJson(_history.value).toString() }
    }

    private fun saveSearches(searches: List<String>) {
        _searches.value = searches
        persist(KEY_SEARCHES) { JSONArray(_searches.value).toString() }
    }

    fun addToList(name: String, gif: Gif) {
        val existing = _lists.value[name].orEmpty()
        if (existing.none { it.id == gif.id }) saveLists(_lists.value + (name to existing + gif))
    }

    fun removeFromList(name: String, gifId: String) {
        val remaining = _lists.value[name].orEmpty().filterNot { it.id == gifId }
        saveLists(_lists.value + (name to remaining))
    }

    private fun saveLists(lists: Map<String, List<Gif>>) {
        _lists.value = lists
        persist(KEY_LISTS) { listsJson(_lists.value).toString() }
    }

    /** Saves the latest value of [key] shortly after the last change to it. */
    private fun persist(key: String, value: () -> String) {
        synchronized(pendingWrites) {
            pendingWrites[key]?.cancel()
            pendingWrites[key] = writer.launch {
                delay(WRITE_DELAY_MS)
                prefs.edit().putString(key, value()).apply()
            }
        }
    }

    private fun listsJson(lists: Map<String, List<Gif>>) = JSONObject().also { json ->
        lists.forEach { (name, gifs) -> json.put(name, gifsJson(gifs)) }
    }

    private fun gifsJson(gifs: List<Gif>) = JSONArray().also { array -> gifs.forEach { array.put(it.toJson()) } }

    private fun savedSearchesJson(searches: List<SavedSearch>) =
        JSONArray().also { array -> searches.forEach { array.put(it.toJson()) } }

    private fun readSavedSearches(): List<SavedSearch> {
        val array = prefs.getString(KEY_SAVED_SEARCHES, null)
            ?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return List(array.length()) { array.optJSONObject(it) }.mapNotNull { it?.let(SavedSearch::fromJson) }
    }

    private fun readSettings(): Settings =
        prefs.getString(KEY_SETTINGS, null)?.let { runCatching { settingsFrom(JSONObject(it)) }.getOrNull() } ?: Settings()

    private fun readGifs(key: String): List<Gif> {
        val array = prefs.getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return List(array.length()) { array.getJSONObject(it) }.mapNotNull(::parseGif)
    }

    private fun readStrings(key: String): List<String> {
        val array = prefs.getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return List(array.length()) { array.getString(it) }
    }

    private fun readTaste(): Taste =
        prefs.getString(KEY_TASTE, null)?.let { runCatching { tasteFrom(JSONObject(it)) }.getOrNull() } ?: Taste()

    private fun readLists(): Map<String, List<Gif>> {
        val json = prefs.getString(KEY_LISTS, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return json.keys().asSequence().associateWith { name ->
            val array = json.getJSONArray(name)
            List(array.length()) { array.getJSONObject(it) }.mapNotNull(::parseGif)
        }
    }

    private companion object {
        const val KEY_TASTE = "taste"
        const val KEY_LISTS = "lists"
        const val KEY_SEEDED = "seeded_from_likes"
        const val KEY_SETTINGS = "settings"
        const val KEY_HISTORY = "history"
        const val KEY_SEARCHES = "searches"
        const val KEY_SAVED_SEARCHES = "saved_searches"
        const val EXPORT_APP = "redcast"
        val EXPORT_KEYS = listOf("settings", "taste", "lists", "history", "searches", "savedSearches")
        const val KEY_AGE = "age_confirmed"
        const val KEY_SWIPE_HINT = "swipe_hint_seen"
        const val KEY_UPDATE_CHECK = "update_check_ms"
        const val KEY_SKIPPED_VERSION = "skipped_version"
        const val MAX_HISTORY = 300
        const val MAX_SEARCHES = 20
        const val WRITE_DELAY_MS = 400L
    }
}

/** What an import brought in, for telling the person. */
data class ImportResult(
    val lists: Int,
    val clips: Int,
    val history: Int,
    val savedSearches: Int,
    val mutedWords: Int,
) {
    val summary: String
        get() = buildList {
            if (lists > 0) add("${plural(lists, "list")} (${plural(clips, "clip")})")
            if (savedSearches > 0) add(plural(savedSearches, "saved search", "saved searches"))
            if (history > 0) add("${plural(history, "clip")} of history")
            if (mutedWords > 0) add(plural(mutedWords, "muted word"))
        }.let { if (it.isEmpty()) "Imported settings and taste" else "Imported " + it.joinToString(", ") }

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
}

/** An export file read back. Anything missing or unreadable is left out. */
internal class Export(
    val settings: Settings?,
    val taste: Taste?,
    val lists: Map<String, List<Gif>>,
    val history: List<Gif>,
    val searches: List<String>,
    val savedSearches: List<SavedSearch>,
)

internal fun readExport(json: JSONObject): Export {
    fun gifs(array: JSONArray?): List<Gif> =
        if (array == null) emptyList() else List(array.length()) { array.optJSONObject(it) }.mapNotNull { it?.let(::parseGif) }
    fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else List(array.length()) { array.optString(it) }.filter { it.isNotEmpty() }
    val lists = json.optJSONObject("lists")
    val saved = json.optJSONArray("savedSearches")
    return Export(
        settings = json.optJSONObject("settings")?.let { runCatching { settingsFrom(it) }.getOrNull() },
        taste = json.optJSONObject("taste")?.let { runCatching { tasteFrom(it) }.getOrNull() },
        lists = lists?.keys()?.asSequence()?.associateWith { gifs(lists.optJSONArray(it)) }.orEmpty(),
        history = gifs(json.optJSONArray("history")),
        searches = strings(json.optJSONArray("searches")),
        savedSearches = if (saved == null) emptyList() else
            List(saved.length()) { saved.optJSONObject(it) }.mapNotNull { it?.let(SavedSearch::fromJson) },
    )
}

/** Each list keeps its own clips in order, with the imported ones it lacked added after them. */
internal fun mergeLists(local: Map<String, List<Gif>>, imported: Map<String, List<Gif>>): Map<String, List<Gif>> =
    (local.keys + imported.keys).associateWith { name ->
        val mine = local[name].orEmpty()
        val ids = mine.mapTo(HashSet()) { it.id }
        mine + imported[name].orEmpty().filter { ids.add(it.id) }
    }

/** What's here first, then imported items not already present, up to [limit]. */
internal fun <T, K> mergeRecent(local: List<T>, imported: List<T>, limit: Int, key: (T) -> K): List<T> =
    (local + imported).distinctBy(key).take(limit)

internal fun Taste.toJson(): JSONObject = JSONObject()
    .put("tags", JSONObject(tagScores))
    .put("creators", JSONObject(creatorScores))
    .put("pinned", JSONArray(pinnedTags))
    .put("hiddenGifs", JSONArray(hiddenGifs))
    .put("hiddenCreators", JSONArray(hiddenCreators))
    .put("hiddenTags", JSONArray(hiddenTags))
    .put("mutedWords", JSONArray(mutedWords))
    .put("seen", JSONArray(seen))

internal fun tasteFrom(json: JSONObject) = Taste(
    tagScores = json.scores("tags"),
    creatorScores = json.scores("creators"),
    pinnedTags = json.strings("pinned"),
    hiddenGifs = json.strings("hiddenGifs").toSet(),
    hiddenCreators = json.strings("hiddenCreators").toSet(),
    hiddenTags = json.strings("hiddenTags").toSet(),
    mutedWords = json.strings("mutedWords").toSet(),
    seen = json.strings("seen"),
)

private fun JSONObject.scores(key: String): Map<String, Double> {
    val scores = optJSONObject(key) ?: return emptyMap()
    return scores.keys().asSequence().associateWith { scores.getDouble(it) }
}

private fun JSONObject.strings(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return List(array.length()) { array.getString(it) }
}
