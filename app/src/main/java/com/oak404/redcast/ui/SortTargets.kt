package com.oak404.redcast.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** One collection bubble in hold-to-sort, centred at ([x], [y]) in screen pixels. */
data class SortTarget(val label: String, val isNew: Boolean, val x: Float, val y: Float)

/**
 * The bubbles shown while a clip is held: up to [MAX_SORT_LISTS] collections and a "New list",
 * evenly spaced on a ring around the middle of a [width] by [height] screen.
 */
internal fun sortTargets(lists: List<String>, width: Int, height: Int): List<SortTarget> {
    val labels = lists.take(MAX_SORT_LISTS).map { it to false } + (NEW_LIST to true)
    val cx = width / 2f
    val cy = height / 2f
    val radius = min(width, height) * RING_FRACTION
    return labels.mapIndexed { i, (label, isNew) ->
        val angle = -PI / 2 + 2 * PI * i / labels.size
        SortTarget(label, isNew, cx + radius * cos(angle).toFloat(), cy + radius * sin(angle).toFloat())
    }
}

/** The bubble under ([x], [y]), if the finger is within [reach] pixels of one. */
internal fun targetAt(targets: List<SortTarget>, x: Float, y: Float, reach: Float): SortTarget? =
    targets.filter { hypot(it.x - x, it.y - y) <= reach }.minByOrNull { hypot(it.x - x, it.y - y) }

internal const val MAX_SORT_LISTS = 7
internal const val NEW_LIST = "New list"
private const val RING_FRACTION = 0.36f
