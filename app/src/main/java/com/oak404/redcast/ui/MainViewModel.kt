package com.oak404.redcast.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import android.net.ConnectivityManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oak404.redcast.RedcastApp
import com.oak404.redcast.data.ApiChangedException
import com.oak404.redcast.data.Creator
import com.oak404.redcast.data.NewClips
import com.oak404.redcast.data.SavedSearch
import com.oak404.redcast.data.SearchOrder
import com.oak404.redcast.data.Feed
import com.oak404.redcast.data.FeedSource
import com.oak404.redcast.data.Gif
import com.oak404.redcast.data.Profile
import com.oak404.redcast.data.Release
import com.oak404.redcast.data.isNewer
import com.oak404.redcast.data.Settings
import com.oak404.redcast.data.Signal
import com.oak404.redcast.data.SearchQuery
import com.oak404.redcast.data.Suggestion
import com.oak404.redcast.data.normalizeWord
import com.oak404.redcast.data.searchOf
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
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
    Searches("Searches"),
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

    /** A newer release to offer, and its download progress once the person says yes. */
    var update by mutableStateOf<Release?>(null)
        private set
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    var autoscroll by mutableStateOf(true)

    /** Set when the sleep timer runs out; the phone player stays paused until it clears. */
    var sleeping by mutableStateOf(false)
        private set

    /** Where the phone player is, so a new Cast session can pick up from the same clip. */
    private var playerPosition: Pair<Feed, Int>? = null
    // Unlikes made while the Liked feed is playing; taking them out then would shift the player.
    private val unlikedWhilePlaying = HashSet<String>()
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

    private val _newClips = MutableStateFlow<Map<SearchQuery, NewClips>>(emptyMap())

    /** New clips per saved search, filled in by [refreshNewClips]. */
    val newClips: StateFlow<Map<SearchQuery, NewClips>> = _newClips.asStateFlow()
    private var newClipsCheckedAtMs = 0L
    private var newClipsJob: Job? = null

    /** Set when a reply from RedGIFs no longer has the shape Redcast reads. */
    val apiChanged: StateFlow<ApiChangedException?> = api.changed

    /** While "more like this" works out which tags to follow. */
    var findingSimilar by mutableStateOf(false)
        private set

    init {
        checkForUpdates(manual = false)
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
        refreshNewClips()
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

    /** Whether the search bar is open, and the tags and words being put together in it. */
    var searching by mutableStateOf(false)
    var draft by mutableStateOf(SearchQuery())

    fun openSearch(start: SearchQuery = SearchQuery()) {
        draft = start
        searching = true
        _suggestions.value = emptyList()
    }

    fun closeSearch() {
        searching = false
        draft = SearchQuery()
        _suggestions.value = emptyList()
    }

    /** Runs [query], or the draft with whatever is still typed in the box as its text. */
    fun search(query: SearchQuery) {
        _suggestions.value = emptyList()
        val tidy = query.copy(text = query.text.trim())
        if (tidy.isEmpty) return
        library.addSearch(tidy.toRecent())
        closeSearch()
        openListing(tidy.toSource())
    }

    /** A tag suggestion joins the draft; anything else is searched as text. */
    fun pickSuggestion(suggestion: Suggestion, exclude: Boolean = false) {
        _suggestions.value = emptyList()
        draft = when {
            exclude -> draft.withExcluded(suggestion.text)
            suggestion.isTag -> draft.withTag(suggestion.text)
            else -> return search(draft.copy(text = suggestion.text))
        }
    }

    /** Opens the search bar on the search behind [feed], so it can be changed. */
    fun editSearch(feed: Feed) {
        val query = searchOf(feed.source) ?: SearchQuery()
        if (backStack.lastOrNull().let { it is Route.Listing && it.feed === feed }) backStack.removeAt(backStack.lastIndex)
        openSearch(query)
    }

    /** Replaces the listing on top with [feed]'s search narrowed by [tag], or with [tag] left out. */
    fun narrow(feed: Feed, tag: String, exclude: Boolean = false) {
        val query = searchOf(feed.source) ?: return
        val narrowed = if (exclude) query.withExcluded(tag) else query.withTag(tag)
        library.addSearch(narrowed.toRecent())
        val top = backStack.lastIndex
        val route = Route.Listing(feeds.create(narrowed.toSource()))
        if (backStack.getOrNull(top).let { it is Route.Listing && it.feed === feed }) backStack[top] = route
        else backStack += route
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

    fun muteWord(text: String) {
        val word = normalizeWord(text)
        if (word.isNotEmpty()) library.updateTaste { it.copy(mutedWords = it.mutedWords + word) }
    }

    fun unmuteWord(word: String) = library.updateTaste { it.copy(mutedWords = it.mutedWords - word) }

    fun muteTag(tag: String) = muteWord(tag)

    /** Follows [gif]'s least common tags, falling back to its first ones if that takes too long. */
    fun openSimilar(gif: Gif) {
        if (findingSimilar) return
        findingSimilar = true
        viewModelScope.launch {
            val tags = try {
                withTimeoutOrNull(SIMILAR_TIMEOUT_MS) { feeds.similarTags(gif) }
            } catch (_: IOException) {
                null
            }
            findingSimilar = false
            openListing(FeedSource.Similar(gif, tags ?: gif.tags.take(FALLBACK_SIMILAR_TAGS)))
        }
    }

    /** Whether [feed]'s search is on the Searches tab. */
    fun isSaved(query: SearchQuery?): Boolean =
        query != null && library.savedSearches.value.any { it.query == query }

    fun toggleSaved(query: SearchQuery) {
        if (isSaved(query)) {
            library.unsaveSearch(query)
            _message.value = "Removed from Searches"
        } else {
            library.saveSearch(query, nowSec())
            _message.value = "Saved to Searches. New clips will show up there."
        }
    }

    fun unsave(query: SearchQuery) = library.unsaveSearch(query)

    /** Opens a saved search, newest first when [newestFirst], and counts its clips as seen. */
    fun openSavedSearch(saved: SavedSearch, newestFirst: Boolean) {
        library.markSearchSeen(saved.query, nowSec())
        _newClips.value -= saved.query
        val query = if (newestFirst) saved.query.copy(order = SearchOrder.Latest) else saved.query
        openListing(query.toSource())
    }

    /** Checks each saved search for new clips, at most every few minutes unless [force]d. */
    fun refreshNewClips(force: Boolean = false) {
        val saved = library.savedSearches.value
        val now = System.currentTimeMillis()
        if (saved.isEmpty() || newClipsJob?.isActive == true) return
        if (!force && now - newClipsCheckedAtMs < NEW_CLIPS_EVERY_MS) return
        newClipsCheckedAtMs = now
        newClipsJob = viewModelScope.launch {
            saved.forEach { search ->
                try {
                    val found = feeds.newClips(search)
                    _newClips.value = if (found.count > 0) _newClips.value + (search.query to found)
                    else _newClips.value - search.query
                } catch (_: IOException) {
                    // A count is a nicety; the search itself still opens.
                }
            }
        }
    }

    fun dismissApiChanged() = api.dismissChanged()

    /** A listing that has clips on screen couldn't fetch its next page. */
    fun onLoadMoreFailed(error: Throwable) {
        _message.value = error.message ?: "Couldn't load more"
    }


    /** Fetches the clips after [index] ahead of time: a few whole ones on Wi-Fi, the starts of two on data. */
    fun preloadAfter(gifs: List<Gif>, index: Int) {
        val metered = connectivity().isActiveNetworkMetered
        val next = gifs.drop(index + 1).take(if (metered) PRELOAD_METERED else PRELOAD_UNMETERED)
        app.preloader.preload(next.map(::playbackUrl), if (metered) PRELOAD_METERED_BYTES else null)
    }

    fun stopPreloading() = app.preloader.cancel()

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val json = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    if (size > MAX_IMPORT_BYTES) throw IOException("That file is too big to be a Redcast export")
                    val input = resolver.openInputStream(uri) ?: throw IOException("Couldn't open that file")
                    JSONObject(input.use { it.readBytes().decodeToString() })
                }
                val result = library.importJson(json)
                refreshMix()
                refreshNewClips(force = true)
                result.summary
            } catch (_: JSONException) {
                "That file isn't a Redcast export"
            } catch (e: IOException) {
                e.message
            }
        }
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    fun openSavedList(name: String) = openListing(FeedSource.SavedList(name))

    fun openHistory() = openListing(FeedSource.History)

    /** On launch at most every few hours (if allowed), or whenever the person asks. */
    fun checkForUpdates(manual: Boolean) {
        val now = System.currentTimeMillis()
        if (!manual && (!library.settings.value.checkForUpdates || now - library.lastUpdateCheckMs < UPDATE_CHECK_EVERY_MS)) {
            return
        }
        viewModelScope.launch {
            try {
                val release = app.updates.latest()
                library.lastUpdateCheckMs = now
                val newer = release != null && isNewer(release.version, installedVersion())
                when {
                    newer && (manual || release.version != library.skippedVersion) -> update = release
                    manual -> _message.value = "Redcast is up to date"
                }
            } catch (e: IOException) {
                if (manual) _message.value = e.message
            }
        }
    }

    fun dismissUpdate() {
        update = null
    }

    fun skipUpdate() {
        library.skippedVersion = update?.version
        update = null
    }

    /** Downloads the release and hands it to Android's installer, which asks the person to confirm. */
    fun installUpdate() {
        val release = update ?: return
        val context = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _message.value = "Allow Redcast to install updates, then tap Update again"
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        updateProgress = 0f
        viewModelScope.launch {
            try {
                val file = app.updates.download(release, java.io.File(context.cacheDir, "updates")) {
                    viewModelScope.launch { updateProgress = it }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                update = null
            } catch (e: IOException) {
                _message.value = e.message
            } catch (e: RuntimeException) {
                // No installer to hand the file to, or the file can't be shared with it.
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = "Couldn't open the installer"
            } finally {
                updateProgress = null
            }
        }
    }

    private fun installedVersion(): String {
        val context = getApplication<Application>()
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

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
        return gif.videoUrl(library.settings.value.quality, connectivity().isActiveNetworkMetered)
    }

    private fun connectivity() =
        getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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
            // The grid leaves out hidden and muted clips, so its place for this clip is the
            // number of clips before it that it still shows.
            val taste = library.taste.value
            feed.rememberScroll(feed.items.value.take(index).count { !taste.hidesByRule(it) }, 0)
        }
    }


    fun onPlayerClosed() {
        playerPosition = null
        liked.remove(unlikedWhilePlaying.toSet())
        unlikedWhilePlaying.clear()
    }

    fun onClipSeen(gif: Gif) {
        library.updateTaste { it.markSeen(gif.id) }
        library.addToHistory(gif)
    }

    fun onClipFinished(gif: Gif) = library.updateTaste { it.record(gif, Signal.Finished) }

    fun onClipSkipped(gif: Gif) = library.updateTaste { it.record(gif, Signal.Skipped) }

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
        showInLiked(gif, liked = !wasLiked)
        viewModelScope.launch {
            try {
                api.setLiked(gif.id, !wasLiked, from?.source, index + 1)
            } catch (e: IOException) {
                _likedIds.value = if (wasLiked) _likedIds.value + gif.id else _likedIds.value - gif.id
                showInLiked(gif, liked = wasLiked)
                _message.value = e.message
            }
        }
    }

    /** The Liked feed is loaded once, so likes made here go into it directly. */
    private fun showInLiked(gif: Gif, liked: Boolean) {
        val playingLiked = playerPosition?.first === this.liked
        when {
            liked -> {
                unlikedWhilePlaying -= gif.id
                if (!playingLiked) this.liked.prepend(gif)
            }
            playingLiked -> unlikedWhilePlaying += gif.id
            else -> this.liked.remove(setOf(gif.id))
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
        const val UPDATE_CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
        /** Far more than any real export: a few thousand clips come to a couple of MB. */
        const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
        const val CREATOR_ORDER = "latest"
        const val MAX_FOLLOWING_PAGES = 10
        const val SEED_LIKES = 80
        const val MIN_SUGGEST_LENGTH = 2
        const val SUGGEST_DEBOUNCE_MS = 250L
        const val MS_PER_MINUTE = 60_000L
        const val SIMILAR_TIMEOUT_MS = 4_000L
        const val FALLBACK_SIMILAR_TAGS = 2
        const val NEW_CLIPS_EVERY_MS = 10 * 60 * 1000L
        const val PRELOAD_UNMETERED = 3
        const val PRELOAD_METERED = 2
        const val PRELOAD_METERED_BYTES = 1_500_000L
    }
}
