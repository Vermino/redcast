package com.oak404.redcast.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import java.io.IOException

/** One paged listing, shared by the grid, the phone player and the Cast queue. */
class Feed(val source: FeedSource, private val loader: suspend (Int) -> GifPage) {

    private val _items = MutableStateFlow<List<Gif>>(emptyList())
    val items: StateFlow<List<Gif>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _exhausted = MutableStateFlow(false)
    val exhausted: StateFlow<Boolean> = _exhausted.asStateFlow()

    private val mutex = Mutex()
    private var nextPage = 1

    /** Where the gallery was left, kept here because its scroll state goes with the screen. */
    var scrollIndex = 0
        private set
    var scrollOffset = 0
        private set

    fun rememberScroll(index: Int, offset: Int) {
        scrollIndex = index
        scrollOffset = offset
    }

    /** Fetches the next page, unless a load is already running or the feed has ended. */
    suspend fun loadMore() {
        if (_exhausted.value) return
        if (!mutex.tryLock()) return
        try {
            fetchNext()
        } finally {
            mutex.unlock()
        }
    }

    /** Suspends until [count] items exist or the feed can't supply more. */
    suspend fun ensureLoaded(count: Int) {
        mutex.withLock {
            while (_items.value.size < count && !_exhausted.value) {
                if (!fetchNext()) return
            }
        }
    }

    /** Puts [gif] first, for a change made in the app that the listing would only show after a reload. */
    fun prepend(gif: Gif) {
        if (_items.value.none { it.id == gif.id }) _items.value = listOf(gif) + _items.value
    }

    fun remove(gifIds: Set<String>) {
        if (gifIds.isNotEmpty()) _items.value = _items.value.filterNot { it.id in gifIds }
    }

    /** A new feed over the same source, shuffled a page at a time. */
    fun shuffled(): Feed = Feed(source) { page ->
        loader(page).let { it.copy(gifs = it.gifs.shuffled()) }
    }

    private suspend fun fetchNext(): Boolean {
        _loading.value = true
        _error.value = null
        return try {
            val page = loader(nextPage)
            nextPage++
            val known = _items.value.mapTo(HashSet()) { it.id }
            _items.value += page.gifs.filter { known.add(it.id) }
            if (page.gifs.isEmpty() || nextPage > page.pages) _exhausted.value = true
            true
        } catch (t: IOException) {
            _error.value = t
            false
        } catch (t: JSONException) {
            _error.value = t
            false
        } finally {
            _loading.value = false
        }
    }
}
