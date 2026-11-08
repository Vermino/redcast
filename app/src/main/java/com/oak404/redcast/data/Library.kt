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

    /** Everything kept here. Sign-in tokens live in a separate store and are never included. */
    fun exportJson(): JSONObject = JSONObject()
        .put("settings", _settings.value.toJson())
        .put("taste", _taste.value.toJson())
        .put("lists", listsJson(_lists.value))
        .put("history", gifsJson(_history.value))
        .put("searches", JSONArray(_searches.value))

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
        const val KEY_AGE = "age_confirmed"
        const val KEY_SWIPE_HINT = "swipe_hint_seen"
        const val KEY_UPDATE_CHECK = "update_check_ms"
        const val KEY_SKIPPED_VERSION = "skipped_version"
        const val MAX_HISTORY = 300
        const val MAX_SEARCHES = 20
        const val WRITE_DELAY_MS = 400L
    }
}

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
