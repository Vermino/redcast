package com.oak404.redcast.data

import kotlin.math.log10
import kotlin.math.sqrt

enum class Signal(val weight: Double) {
    Liked(3.0),
    Saved(2.0),
    Finished(1.0),
    Skipped(-1.0),
    Passed(-3.0),
}

/** What this person has shown they like. Stored on the phone only. */
data class Taste(
    val tagScores: Map<String, Double> = emptyMap(),
    val creatorScores: Map<String, Double> = emptyMap(),
    val pinnedTags: List<String> = emptyList(),
    val hiddenGifs: Set<String> = emptySet(),
    val hiddenCreators: Set<String> = emptySet(),
    val hiddenTags: Set<String> = emptySet(),
    val seen: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = tagScores.isEmpty() && pinnedTags.isEmpty()

    fun hides(gif: Gif): Boolean =
        gif.id in hiddenGifs || gif.userName in hiddenCreators || gif.tags.any { it in hiddenTags }

    fun record(gif: Gif, signal: Signal): Taste = copy(
        tagScores = gif.tags.fold(tagScores) { scores, tag -> scores.bump(tag, signal.weight) },
        creatorScores = creatorScores.bump(gif.userName, signal.weight),
    )

    fun markSeen(gifId: String): Taste =
        if (gifId in seen) this else copy(seen = (seen + gifId).takeLast(MAX_SEEN))

    fun togglePinned(tag: String): Taste =
        copy(pinnedTags = if (tag in pinnedTags) pinnedTags - tag else pinnedTags + tag)

    /** Pinned tags first, then the strongest learned ones. */
    fun topTags(count: Int): List<String> =
        (pinnedTags + strongest(tagScores)).distinct().filterNot { it in hiddenTags }.take(count)

    fun topCreators(count: Int): List<String> =
        strongest(creatorScores).filterNot { it in hiddenCreators }.take(count)

    private fun strongest(scores: Map<String, Double>) =
        scores.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key }

    private fun Map<String, Double>.bump(key: String, by: Double) =
        this + (key to ((this[key] ?: 0.0) + by).coerceIn(MIN_SCORE, MAX_SCORE))

    private companion object {
        const val MAX_SEEN = 3000
        const val MIN_SCORE = -10.0
        const val MAX_SCORE = 50.0
    }
}

/** Orders candidates by personal fit, with overall popularity as a tie-breaker. */
internal object MixRanker {

    private const val PINNED_BONUS = 4.0
    private const val CREATOR_WEIGHT = 1.5
    private const val POPULARITY_WEIGHT = 0.5
    private const val MAX_PER_CREATOR = 3

    fun score(gif: Gif, taste: Taste): Double {
        val tags = gif.tags.sumOf { tag ->
            (taste.tagScores[tag] ?: 0.0) + if (tag in taste.pinnedTags) PINNED_BONUS else 0.0
        }
        // A long tag list shouldn't beat a short one that fits better.
        val tagFit = if (gif.tags.isEmpty()) 0.0 else tags / sqrt(gif.tags.size.toDouble())
        val creatorFit = (taste.creatorScores[gif.userName] ?: 0.0) * CREATOR_WEIGHT
        return tagFit + creatorFit + log10(gif.likes + 1.0) * POPULARITY_WEIGHT
    }

    fun rank(candidates: List<Gif>, taste: Taste): List<Gif> {
        val seen = taste.seen.toHashSet()
        val perCreator = HashMap<String, Int>()
        return candidates.distinctBy { it.id }
            .filterNot { taste.hides(it) || it.id in seen }
            .sortedByDescending { score(it, taste) }
            .filter { gif ->
                val count = perCreator.getOrDefault(gif.userName, 0)
                perCreator[gif.userName] = count + 1
                count < MAX_PER_CREATOR
            }
    }
}
