package com.oak404.redcast.data

import org.json.JSONObject

enum class AppTheme(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
    Amoled("AMOLED"),
    Midnight("Midnight"),
    Sunset("Sunset"),
    Forest("Forest"),
}

enum class VideoQuality(val label: String) {
    Auto("Auto"),
    Hd("HD"),
    Sd("SD"),
}

enum class FeedLayout(val label: String) {
    Grid("Grid"),
    Staggered("Staggered"),
    List("List"),
}

data class Settings(
    val theme: AppTheme = AppTheme.System,
    val quality: VideoQuality = VideoQuality.Auto,
    val layout: FeedLayout = FeedLayout.Grid,
    val columns: Int = 3,
    val defaultTab: String = "Mix",
    val showPromoted: Boolean = true,
    val swipeToCurate: Boolean = true,
    val listDiscovery: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("theme", theme.name)
        .put("quality", quality.name)
        .put("layout", layout.name)
        .put("columns", columns)
        .put("defaultTab", defaultTab)
        .put("showPromoted", showPromoted)
        .put("swipeToCurate", swipeToCurate)
        .put("listDiscovery", listDiscovery)
}

internal fun settingsFrom(json: JSONObject): Settings {
    val defaults = Settings()
    return Settings(
        theme = enumOr(json.optString("theme"), defaults.theme),
        quality = enumOr(json.optString("quality"), defaults.quality),
        layout = enumOr(json.optString("layout"), defaults.layout),
        columns = json.optInt("columns", defaults.columns).coerceIn(2, 4),
        defaultTab = json.optString("defaultTab").ifEmpty { defaults.defaultTab },
        showPromoted = json.optBoolean("showPromoted", defaults.showPromoted),
        swipeToCurate = json.optBoolean("swipeToCurate", defaults.swipeToCurate),
        listDiscovery = json.optBoolean("listDiscovery", defaults.listDiscovery),
    )
}

private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

/** The file for this clip: Auto saves mobile data by taking SD on a metered connection. */
fun Gif.videoUrl(quality: VideoQuality, metered: Boolean): String = when (quality) {
    VideoQuality.Hd -> hdUrl
    VideoQuality.Sd -> sdUrl
    VideoQuality.Auto -> if (metered) sdUrl else hdUrl
}

/** Width over height, kept within limits so one odd clip can't swallow a whole column. */
val Gif.aspectRatio: Float
    get() = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.5f, 1.8f) else 9f / 16f

/** Newest first, with no repeats. */
internal fun <T> pushRecent(items: List<T>, item: T, limit: Int, same: (T, T) -> Boolean = { a, b -> a == b }): List<T> =
    (listOf(item) + items.filterNot { same(it, item) }).take(limit)
