package com.oak404.redcast.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oak404.redcast.RedcastApp
import com.oak404.redcast.data.Creator
import com.oak404.redcast.data.Feed
import com.oak404.redcast.data.FeedSource
import com.oak404.redcast.data.Gif
import com.oak404.redcast.data.Profile
import com.oak404.redcast.data.Settings
import com.oak404.redcast.data.Signal
import com.oak404.redcast.data.Suggestion
import com.oak404.redcast.data.TrendingTag
import com.oak404.redcast.data.videoUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface Route {
    data class Listing(val feed: Feed) : Route
    data class Creator(val userName: String, val order: String, val feed: Feed) : Route
    data class Player(val feed: Feed, val startIndex: Int) : Route
    data object Settings : Route
}

enum class HomeTab(val label: String) {
    Mix("My Mix"),
    Trending("Trending"),
    Explore("Explore"),
    ForYou("For You"),
    Liked("Liked"),
    Following("Following"),
    Saved("Saved"),
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = RedcastApp.get(application)
    private val api = app.api
    private val feeds = app.feeds
    val library = app.library
    val session = app.session
    val cast = app.cast

    /** Screens opened on top of Home; the last entry is the one showing. */
    val backStack = mutableStateListOf<Route>()

    val defaultTab: HomeTab
        get() = HomeTab.entries.firstOrNull { it.name == library.settings.value.defaultTab } ?: HomeTab.Mix

    var tab by mutableStateOf(defaultTab)
    var ageConfirmed by mutableStateOf(library.ageConfirmed)
        private set
    var autoscroll by mutableStateOf(true)

    /** Set when the sleep timer runs out; the phone player stays paused until it clears. */
    var sleeping by mutableStateOf(false)
        private set

    /** Where the phone player is, so a new Cast session can pick up from the same clip. */
    private var playerPosition: Pair<Feed, Int>? = null
    private var sleepJob: Job? = null

    private val trending = feeds.create(FeedSource.Trending)
    var mix by mutableStateOf(feeds.create(FeedSource.Mix))
        private set
    var forYou by mutableStateOf(feeds.create(FeedSource.ForYou))
        private set
    var liked by mutableStateOf(feeds.create(FeedSource.Liked))
        private set

    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    private val _following = MutableStateFlow<List<Creator>>(emptyList())
    val following: StateFlow<List<Creator>> = _following.asStateFlow()

    private val _trendingTags = MutableStateFlow<List<TrendingTag>>(emptyList())
    val trendingTags: StateFlow<List<TrendingTag>> = _trendingTags.asStateFlow()

    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    private val _userName = MutableStateFlow<String?>(null)
    val userName: StateFlow<String?> = _userName.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var suggestJob: Job? = null

    init {
        viewModelScope.launch {
            if (session.signedIn.value) refreshAccount()
            session.signedIn.drop(1).collect { signedIn ->
                forYou = feeds.create(FeedSource.ForYou)
                liked = feeds.create(FeedSource.Liked)
                _likedIds.value = emptySet()
                _following.value = emptyList()
                _userName.value = null
                if (signedIn) refreshAccount()
            }
        }
        viewModelScope.launch {
            cast.sessionStarted.collect {
                val (feed, index) = playerPosition ?: (feedFor(tab) to 0)
                feed.ensureLoaded(index + 1)
                cast.play(feed, index)
            }
        }
        viewModelScope.launch {
            try {
                _trendingTags.value = api.trendingTags()
            } catch (_: IOException) {
                // The tag row is a shortcut; My Mix works without it.
            }
        }
    }

    fun feedFor(tab: HomeTab): Feed = when (tab) {
        HomeTab.Mix -> mix
        HomeTab.ForYou -> forYou
        HomeTab.Liked -> liked
        else -> trending
    }

    fun openGif(feed: Feed, index: Int) {
        backStack += Route.Player(feed, index)
        if (cast.deviceName.value != null) cast.play(feed, index)
    }

    /** Opens [feed] in random order, starting from whatever comes up first. */
    fun shufflePlay(feed: Feed) {
        val shuffled = feed.shuffled()
        viewModelScope.launch {
            shuffled.ensureLoaded(1)
            if (shuffled.items.value.isNotEmpty()) openGif(shuffled, 0)
        }
    }

    fun search(query: String) {
        val text = query.trim()
        _suggestions.value = emptyList()
        if (text.isNotEmpty()) {
            library.addSearch(text)
            openListing(FeedSource.Search(text))
        }
    }

    fun openSuggestion(suggestion: Suggestion) {
        _suggestions.value = emptyList()
        library.addSearch(suggestion.text)
        openListing(if (suggestion.isTag) FeedSource.Tag(suggestion.text) else FeedSource.Search(suggestion.text))
    }

    fun onQueryChanged(query: String) {
        suggestJob?.cancel()
        val text = query.trim()
        if (text.length < MIN_SUGGEST_LENGTH) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            try {
                _suggestions.value = api.suggestions(text)
            } catch (_: IOException) {
                _suggestions.value = emptyList()
            }
        }
    }

    fun openTag(tag: String) = openListing(FeedSource.Tag(tag))

    fun openSimilar(gif: Gif) = openListing(FeedSource.Similar(gif))

    fun openSavedList(name: String) = openListing(FeedSource.SavedList(name))

    fun openHistory() = openListing(FeedSource.History)

    fun openSettings() {
        backStack += Route.Settings
    }

    fun confirmAge() {
        library.ageConfirmed = true
        ageConfirmed = true
    }

    fun updateSettings(change: (Settings) -> Settings) = library.updateSettings(change)

    /** The file to play on the phone. Chromecast always gets HD. */
    fun playbackUrl(gif: Gif): String {
        val network = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return gif.videoUrl(library.settings.value.quality, network.isActiveNetworkMetered)
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val json = library.exportJson().toString(2)
                withContext(Dispatchers.IO) {
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: throw IOException("Couldn't open that file")
                    output.use { it.write(json.toByteArray()) }
                }
                "Exported"
            } catch (e: IOException) {
                e.message
            }
        }
    }

    fun openCreator(userName: String) {
        backStack += Route.Creator(userName, CREATOR_ORDER, feeds.create(FeedSource.CreatorGifs(userName, CREATOR_ORDER)))
    }

    fun sortCreator(route: Route.Creator, order: String) {
        val top = backStack.lastIndex
        if (backStack.getOrNull(top) == route) {
            backStack[top] = route.copy(order = order, feed = feeds.create(FeedSource.CreatorGifs(route.userName, order)))
        }
    }

    suspend fun profile(userName: String): Profile? = try {
        api.profile(userName)
    } catch (_: IOException) {
        null
    }

    private fun openListing(source: FeedSource) {
        backStack += Route.Listing(feeds.create(source))
    }

    fun back() {
        backStack.removeLastOrNull()
    }

    fun onPlayerPosition(feed: Feed, index: Int) {
        playerPosition = feed to index
        // Coming back from a profile should land on this clip, not the one first opened.
        val top = backStack.lastIndex
        val route = backStack.getOrNull(top) as? Route.Player ?: return
        if (route.feed === feed && route.startIndex != index) {
            backStack[top] = route.copy(startIndex = index)
            feed.rememberScroll(index, 0)
        }
    }

    fun onPlayerClosed() {
        playerPosition = null
    }

    fun onClipSeen(gif: Gif) {
        library.updateTaste { it.markSeen(gif.id) }
        library.addToHistory(gif)
    }

    fun onClipFinished(gif: Gif) = library.updateTaste { it.record(gif, Signal.Finished) }

    fun onClipSkipped(gif: Gif) = library.updateTaste { it.record(gif, Signal.Skipped) }

    /** Swipe right: a like when signed in, otherwise a local save. Either way, it teaches My Mix. */
    fun keep(gif: Gif, from: Feed, index: Int) {
        when {
            session.signedIn.value -> if (gif.id !in _likedIds.value) toggleLike(gif, from, index)
            else -> {
                library.updateTaste { it.record(gif, Signal.Liked) }
                library.addToList(KEPT_LIST, gif)
            }
        }
    }

    /** Into a collection, which also counts toward My Mix. */
    fun saveTo(list: String, gif: Gif) {
        val already = library.lists.value[list].orEmpty().any { it.id == gif.id }
        library.addToList(list, gif)
        if (!already) library.updateTaste { it.record(gif, Signal.Saved) }
        _message.value = if (already) "Already in $list" else "Saved to $list"
    }

    fun setListDiscovery(on: Boolean) {
        library.updateSettings { it.copy(listDiscovery = on) }
        val top = backStack.lastIndex
        val route = backStack.getOrNull(top) as? Route.Listing ?: return
        if (route.feed.source is FeedSource.SavedList) backStack[top] = Route.Listing(feeds.create(route.feed.source))
    }

    /** Swipe left: drops the clip and steers My Mix away from its tags and creator. */
    fun pass(gif: Gif) {
        library.updateTaste { it.record(gif, Signal.Passed).copy(hiddenGifs = it.hiddenGifs + gif.id) }
    }

    fun togglePinned(tag: String) {
        library.updateTaste { it.togglePinned(tag) }
    }

    /** Rebuilds My Mix from the latest taste, which also drops clips seen since it loaded. */
    fun refreshMix() {
        mix = feeds.create(FeedSource.Mix)
    }

    fun hideGif(gif: Gif) = library.updateTaste { it.copy(hiddenGifs = it.hiddenGifs + gif.id) }

    fun hideCreator(userName: String) =
        library.updateTaste { it.copy(hiddenCreators = it.hiddenCreators + userName) }

    fun hideTag(tag: String) {
        library.updateTaste {
            it.copy(hiddenTags = it.hiddenTags + tag, pinnedTags = it.pinnedTags - tag)
        }
    }

    fun unhideTag(tag: String) = library.updateTaste { it.copy(hiddenTags = it.hiddenTags - tag) }

    fun unhideCreator(userName: String) =
        library.updateTaste { it.copy(hiddenCreators = it.hiddenCreators - userName) }

    fun unhideGifs() = library.updateTaste { it.copy(hiddenGifs = emptySet()) }

    /** [index] is the clip's place in [from], which the site wants with every like. */
    fun toggleLike(gif: Gif, from: Feed?, index: Int) {
        val wasLiked = gif.id in _likedIds.value
        _likedIds.value = if (wasLiked) _likedIds.value - gif.id else _likedIds.value + gif.id
        if (!wasLiked) library.updateTaste { it.record(gif, Signal.Liked) }
        viewModelScope.launch {
            try {
                api.setLiked(gif.id, !wasLiked, from?.source, index + 1)
            } catch (e: IOException) {
                _likedIds.value = if (wasLiked) _likedIds.value + gif.id else _likedIds.value - gif.id
                _message.value = e.message
            }
        }
    }

    fun toggleFollow(userName: String) {
        val current = _following.value
        val wasFollowing = current.any { it.userName == userName }
        _following.value =
            if (wasFollowing) current.filterNot { it.userName == userName } else current + Creator(userName, null)
        viewModelScope.launch {
            try {
                api.setFollowing(userName, !wasFollowing)
            } catch (e: IOException) {
                _following.value = current
                _message.value = e.message
            }
        }
    }

    /** Stops playback after [minutes], or cancels the timer when null. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleeping = false
        if (minutes == null) return
        sleepJob = viewModelScope.launch {
            delay(minutes * MS_PER_MINUTE)
            sleeping = true
            cast.endSession()
        }
    }

    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }

    fun clearMessage() {
        _message.value = null
    }

    private suspend fun refreshAccount() {
        try {
            _userName.value = api.myUserName()
            liked.ensureLoaded(SEED_LIKES)
            val likes = liked.items.value
            _likedIds.value += likes.map { it.id }
            seedTaste(likes)
            var page = 1
            while (page <= MAX_FOLLOWING_PAGES) {
                val creators = api.following(page)
                if (creators.isEmpty()) break
                _following.value += creators
                page++
            }
        } catch (_: IOException) {
            // Account extras are best-effort; each tab shows its own error if it can't load.
        }
    }

    /** Gives My Mix a head start from existing likes, once per install. */
    private fun seedTaste(likes: List<Gif>) {
        if (library.seededFromLikes || likes.isEmpty()) return
        library.updateTaste { taste -> likes.fold(taste) { acc, gif -> acc.record(gif, Signal.Liked) } }
        library.seededFromLikes = true
        refreshMix()
    }

    private companion object {
        const val KEPT_LIST = "Kept"
        const val CREATOR_ORDER = "latest"
        const val MAX_FOLLOWING_PAGES = 10
        const val SEED_LIKES = 80
        const val MIN_SUGGEST_LENGTH = 2
        const val SUGGEST_DEBOUNCE_MS = 250L
        const val MS_PER_MINUTE = 60_000L
    }
}
