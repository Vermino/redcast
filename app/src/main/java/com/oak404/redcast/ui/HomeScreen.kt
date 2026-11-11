package com.oak404.redcast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Surface
import com.oak404.redcast.data.ApiChangedException
import com.oak404.redcast.data.SavedSearch
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.oak404.redcast.data.SearchOrder
import com.oak404.redcast.data.SearchQuery
import com.oak404.redcast.data.searchOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.oak404.redcast.data.Creator
import com.oak404.redcast.data.Feed
import com.oak404.redcast.data.FeedLayout
import com.oak404.redcast.data.FeedSource
import com.oak404.redcast.data.Gif
import com.oak404.redcast.data.SignInRequiredException
import com.oak404.redcast.data.TrendingTag
import com.oak404.redcast.data.aspectRatio
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel, appLock: AppLock) {
    val signedIn by viewModel.session.signedIn.collectAsStateWithLifecycle()
    val searching = viewModel.searching
    var query by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = searching) {
        query = ""
        viewModel.closeSearch()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        TextField(
                            value = query,
                            onValueChange = {
                                query = it
                                viewModel.onQueryChanged(it.trim().removePrefix("-"))
                            },
                            placeholder = { Text("Search, or add tags") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                val text = query.trim()
                                if (text.startsWith("-")) {
                                    val word = text.removePrefix("-").trim()
                                    if (word.isNotEmpty()) viewModel.draft = viewModel.draft.withExcluded(word)
                                } else {
                                    viewModel.search(viewModel.draft.copy(text = text))
                                }
                                query = ""
                                viewModel.onQueryChanged("")
                            }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    } else {
                        Text("Redcast")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        query = ""
                        if (searching) viewModel.closeSearch() else viewModel.openSearch()
                    }) {
                        Icon(
                            if (searching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searching) "Close search" else "Search",
                        )
                    }
                    if (!searching) {
                        IconButton(onClick = { viewModel.shufflePlay(viewModel.feedFor(viewModel.tab)) }) {
                            Icon(Icons.Default.Shuffle, contentDescription = "Shuffle this feed")
                        }
                        CastButton(viewModel)
                        AccountMenu(viewModel, signedIn, appLock)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                SearchBuilder(viewModel, query) { query = "" }
                return@Column
            }
            ApiChangedBanner(viewModel)
            val newClips by viewModel.newClips.collectAsStateWithLifecycle()
            PrimaryScrollableTabRow(selectedTabIndex = viewModel.tab.ordinal) {
                HomeTab.entries.forEach { tab ->
                    val fresh = if (tab == HomeTab.Searches) newClips.values.sumOf { it.count } else 0
                    Tab(
                        selected = tab == viewModel.tab,
                        onClick = { viewModel.tab = tab },
                        text = { Text(if (fresh > 0) "${tab.label} · $fresh" else tab.label, maxLines = 1) },
                    )
                }
            }
            when (viewModel.tab) {
                HomeTab.Searches -> SavedSearches(viewModel)
                HomeTab.Mix -> {
                    MixHeader(viewModel)
                    FeedGrid(viewModel, viewModel.mix)
                }
                HomeTab.Trending -> FeedGrid(viewModel, viewModel.feedFor(HomeTab.Trending))
                HomeTab.Explore -> ExploreTags(viewModel)
                HomeTab.Saved -> SavedLists(viewModel)
                HomeTab.Following -> if (signedIn) FollowingList(viewModel) else SignInPrompt()
                else -> if (signedIn) FeedGrid(viewModel, viewModel.feedFor(viewModel.tab)) else SignInPrompt()
            }
        }
    }
}

/**
 * Everything under the open search bar: the tags picked so far, the sort order, suggestions
 * for what's typed, and recent searches and popular tags when nothing is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchBuilder(viewModel: MainViewModel, typed: String, clearTyped: () -> Unit) {
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val trending by viewModel.trendingTags.collectAsStateWithLifecycle()
    val taste by viewModel.library.taste.collectAsStateWithLifecycle()
    val draft = viewModel.draft
    val excluding = typed.trim().startsWith("-")
    val withTyped = draft.copy(text = if (excluding) "" else typed.trim())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (draft.tags.isNotEmpty() || draft.exclude.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                draft.tags.forEach { tag ->
                    InputChip(
                        selected = true,
                        onClick = { viewModel.draft = draft.withExcluded(tag) },
                        label = { Text(tag, maxLines = 1) },
                        trailingIcon = { RemoveChip(tag) { viewModel.draft = draft.without(tag) } },
                    )
                }
                draft.exclude.forEach { word ->
                    InputChip(
                        selected = false,
                        onClick = { viewModel.draft = draft.withTag(word) },
                        label = { Text("−$word", maxLines = 1, textDecoration = TextDecoration.LineThrough) },
                        trailingIcon = { RemoveChip(word) { viewModel.draft = draft.without(word) } },
                        colors = InputChipDefaults.inputChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    )
                }
            }
            Text(
                "Clips need every tag. Tap a tag to leave it out instead.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            items(SearchOrder.entries) { order ->
                FilterChip(
                    selected = order == draft.order,
                    onClick = { viewModel.draft = draft.copy(order = order) },
                    label = { Text(order.label, maxLines = 1) },
                )
            }
        }

        Button(
            onClick = {
                clearTyped()
                viewModel.search(withTyped)
            },
            enabled = !withTyped.isEmpty,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(
                if (withTyped.isEmpty) "Search" else "Search ${withTyped.title}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))

        if (suggestions.isNotEmpty() && typed.isNotBlank()) {
            suggestions.forEach { suggestion ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clearTyped()
                            viewModel.pickSuggestion(suggestion, exclude = excluding)
                        }
                        .padding(start = 16.dp),
                ) {
                    Icon(
                        when {
                            excluding -> Icons.Default.Block
                            suggestion.isTag -> Icons.Default.Add
                            else -> Icons.Default.Search
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (excluding) "Leave out ${suggestion.text}" else suggestion.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp),
                    )
                    if (suggestion.isTag && !excluding) {
                        IconButton(onClick = {
                            clearTyped()
                            viewModel.pickSuggestion(suggestion, exclude = true)
                        }) {
                            Icon(Icons.Default.Block, contentDescription = "Leave out ${suggestion.text}")
                        }
                    }
                }
            }
            HorizontalDivider()
        }

        if (typed.isBlank()) {
            if (draft.isEmpty && draft.exclude.isEmpty()) {
                RecentSearches(viewModel) { picked -> viewModel.search(picked) }
            }
            val inDraft = (draft.tags + draft.exclude).map { it.lowercase() }.toSet()
            val popular = (taste.pinnedTags + trending.map { it.name }).distinct()
                .filterNot { it.lowercase() in inDraft || it in taste.hiddenTags || taste.mutes(it) }
            if (popular.isNotEmpty()) {
                Text(
                    if (draft.tags.isEmpty()) "Popular tags" else "Add another tag",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    popular.take(POPULAR_TAGS).forEach { tag ->
                        SuggestionChip(
                            onClick = { viewModel.draft = draft.withTag(tag) },
                            label = { Text(tag, maxLines = 1) },
                        )
                    }
                }
            }
            Text(
                "Type -word and press search to leave a word out. Mute words everywhere in Settings.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun RemoveChip(label: String, onRemove: () -> Unit) {
    Icon(
        Icons.Default.Close,
        contentDescription = "Remove $label",
        modifier = Modifier.size(18.dp).clickable(onClick = onRemove),
    )
}

/** Tag chips that steer My Mix, with the pinned ones first. */
@Composable
private fun MixHeader(viewModel: MainViewModel) {
    val taste by viewModel.library.taste.collectAsStateWithLifecycle()
    val trending by viewModel.trendingTags.collectAsStateWithLifecycle()
    val tags = (taste.pinnedTags + trending.map { it.name }).distinct().filterNot { it in taste.hiddenTags }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = viewModel::refreshMix) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh My Mix")
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(end = 8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(tags, key = { it }) { tag ->
                FilterChip(
                    selected = tag in taste.pinnedTags,
                    onClick = { viewModel.togglePinned(tag) },
                    label = { Text(tag, maxLines = 1) },
                )
            }
        }
    }
    if (taste.isEmpty) {
        Text(
            "Pin a few tags to steer this feed. It also learns from what you like, finish and skip.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListingScreen(viewModel: MainViewModel, feed: Feed) {
    val taste by viewModel.library.taste.collectAsStateWithLifecycle()
    val tag = (feed.source as? FeedSource.Tag)?.name
    val search = searchOf(feed.source)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(feed.source.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (tag != null) {
                        val pinned = tag in taste.pinnedTags
                        IconButton(onClick = { viewModel.togglePinned(tag) }) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = if (pinned) "Unpin from My Mix" else "Pin to My Mix",
                                tint = if (pinned) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = {
                            viewModel.hideTag(tag)
                            viewModel.back()
                        }) {
                            Icon(Icons.Default.Block, contentDescription = "Hide this tag")
                        }
                    }
                    if (search != null) {
                        val savedSearches by viewModel.library.savedSearches.collectAsStateWithLifecycle()
                        val saved = savedSearches.any { it.query == search }
                        IconButton(onClick = { viewModel.toggleSaved(search) }) {
                            Icon(
                                if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (saved) "Remove from Searches" else "Save to Searches",
                                tint = if (saved) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = { viewModel.editSearch(feed) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Change this search")
                        }
                    }
                    IconButton(onClick = { viewModel.shufflePlay(feed) }) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle this feed")
                    }
                    CastButton(viewModel)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (search != null) NarrowRow(viewModel, feed, search)
            if (feed.source is FeedSource.SavedList) {
                val settings by viewModel.library.settings.collectAsStateWithLifecycle()
                FilterChip(
                    selected = settings.listDiscovery,
                    onClick = { viewModel.setListDiscovery(!settings.listDiscovery) },
                    label = { Text("Then more like these") },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                FeedGrid(viewModel, feed)
            }
        }
    }
}

/** Tags that go with a search's results, each one a tap away from narrowing it further. */
@Composable
private fun NarrowRow(viewModel: MainViewModel, feed: Feed, search: SearchQuery) {
    val related by feed.relatedTags.collectAsStateWithLifecycle()
    val inSearch = (search.tags + search.exclude).map { it.lowercase() }.toSet()
    val tags = related.filterNot { it.lowercase() in inSearch }.take(NARROW_TAGS)
    if (tags.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(tags, key = { it }) { tag ->
            SuggestionChip(
                onClick = { viewModel.narrow(feed, tag) },
                label = { Text("+ $tag", maxLines = 1) },
            )
        }
    }
}

@Composable
private fun AccountMenu(viewModel: MainViewModel, signedIn: Boolean, appLock: AppLock) {
    val context = LocalContext.current
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = "Account",
                tint = if (signedIn) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (signedIn) {
                DropdownMenuItem(
                    text = { Text(userName?.let { "My profile ($it)" } ?: "Signed in") },
                    enabled = userName != null,
                    onClick = {
                        open = false
                        userName?.let(viewModel::openCreator)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("History") },
                onClick = {
                    open = false
                    viewModel.openHistory()
                },
            )
            DropdownMenuItem(
                text = { Text("Settings") },
                onClick = {
                    open = false
                    viewModel.openSettings()
                },
            )
            DropdownMenuItem(
                text = { Text(if (signedIn) "Sign out" else "Sign in") },
                onClick = {
                    open = false
                    if (signedIn) viewModel.signOut() else startLogin(context)
                },
            )
        }
    }
}

@Composable
fun FeedGrid(viewModel: MainViewModel, feed: Feed) {
    val gifs by feed.items.collectAsStateWithLifecycle()
    val loading by feed.loading.collectAsStateWithLifecycle()
    val error by feed.error.collectAsStateWithLifecycle()
    val exhausted by feed.exhausted.collectAsStateWithLifecycle()
    val settings by viewModel.library.settings.collectAsStateWithLifecycle()
    val taste by viewModel.library.taste.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Muting or hiding something applies to listings already loaded too. Each clip keeps its
    // place in the feed, which is what the player opens at.
    val shown = remember(gifs, taste) { gifs.withIndex().filterNot { taste.hidesByRule(it.value) } }
    // Per feed: the state of a grid that has left composition keeps reporting its old size.
    // The feed holds the position instead, because these go whenever another screen opens.
    val grid = remember(feed) { LazyGridState(feed.scrollIndex, feed.scrollOffset) }
    val staggered = remember(feed) {
        LazyStaggeredGridState(feed.scrollIndex, feed.scrollOffset)
    }
    val list = remember(feed) { LazyListState(feed.scrollIndex, feed.scrollOffset) }
    val layout = settings.layout

    DisposableEffect(feed, layout) {
        onDispose {
            when (layout) {
                FeedLayout.Grid -> feed.rememberScroll(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
                FeedLayout.Staggered -> feed.rememberScroll(
                    staggered.firstVisibleItemIndex,
                    staggered.firstVisibleItemScrollOffset,
                )
                FeedLayout.List -> feed.rememberScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
            }
        }
    }

    LaunchedEffect(feed, layout) {
        if (feed.items.value.isEmpty()) feed.loadMore()
        snapshotFlow {
            val (last, total) = when (layout) {
                FeedLayout.Grid -> grid.layoutInfo.run { visibleItemsInfo.lastOrNull()?.index to totalItemsCount }
                FeedLayout.Staggered -> staggered.layoutInfo.run { visibleItemsInfo.lastOrNull()?.index to totalItemsCount }
                FeedLayout.List -> list.layoutInfo.run { visibleItemsInfo.lastOrNull()?.index to totalItemsCount }
            }
            (last ?: 0) >= total - LOAD_AHEAD
        }.collect { nearEnd -> if (nearEnd) feed.loadMore() }
    }
    // With clips already showing there's no room for the full-screen error, so a next page
    // that fails says so in passing; scrolling to the end again retries it.
    LaunchedEffect(error) {
        val failed = error
        if (failed != null && failed !is SignInRequiredException && feed.items.value.isNotEmpty()) {
            viewModel.onLoadMoreFailed(failed)
        }
    }


    when {
        gifs.isEmpty() && error is SignInRequiredException -> SignInPrompt()
        gifs.isEmpty() && error != null -> Message(error?.message ?: "Couldn't load this feed") {
            Button(onClick = { scope.launch { feed.loadMore() } }) { Text("Retry") }
            if (error is ApiChangedException) {
                TextButton(onClick = { viewModel.checkForUpdates(manual = true) }) { Text("Check for updates") }
            }
        }
        gifs.isEmpty() && exhausted -> Message("Nothing here yet")
        gifs.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            if (loading) CircularProgressIndicator()
        }
        layout == FeedLayout.List -> InlineList(viewModel, feed, shown, list)

        layout == FeedLayout.Staggered -> LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(settings.columns),
            state = staggered,
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalItemSpacing = 4.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            staggeredItemsIndexed(shown, key = { _, item -> item.value.id }) { _, (index, gif) ->
                GifCard(gif, gif.aspectRatio) { viewModel.openGif(feed, index) }
            }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(settings.columns),
            state = grid,
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(shown, key = { _, item -> item.value.id }) { _, (index, gif) ->
                GifCard(gif, 9f / 16f) { viewModel.openGif(feed, index) }
            }
        }
    }
}

@Composable
private fun GifCard(gif: Gif, aspectRatio: Float, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1B1B1F))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = gif.thumbnailUrl,
            contentDescription = "Video by ${gif.userName}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            "${gif.durationSec.toInt()}s",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(Color(0x99000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun RecentSearches(viewModel: MainViewModel, onPick: (SearchQuery) -> Unit) {
    val searches by viewModel.library.searches.collectAsStateWithLifecycle()
    if (searches.isEmpty()) return
    Text(
        "Recent",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    searches.take(VISIBLE_SEARCHES).map(SearchQuery::fromRecent).filterNot { it.isEmpty }.forEach { search ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(search) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                search.title + if (search.order != SearchOrder.Trending) " · ${search.order.label}" else "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
    TextButton(onClick = viewModel.library::clearSearches) { Text("Clear recent searches") }
    HorizontalDivider()
}

/** Searches kept with the bookmark on their results, each with how many clips are new. */
@Composable
private fun SavedSearches(viewModel: MainViewModel) {
    val saved by viewModel.library.savedSearches.collectAsStateWithLifecycle()
    val newClips by viewModel.newClips.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshNewClips() }
    if (saved.isEmpty()) {
        Message("Save a search with the bookmark on its results. New clips for it show up here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(saved, key = { it.query.toJson().toString() }) { search ->
            SavedSearchRow(viewModel, search, newClips[search.query])
            HorizontalDivider()
        }
    }
}

@Composable
private fun SavedSearchRow(viewModel: MainViewModel, search: SavedSearch, fresh: com.oak404.redcast.data.NewClips?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.openSavedSearch(search, newestFirst = false) }
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(search.query.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(search.query.order.label, style = MaterialTheme.typography.bodySmall)
        }
        if (fresh != null) {
            AssistChip(
                onClick = { viewModel.openSavedSearch(search, newestFirst = true) },
                label = { Text(fresh.label) },
            )
        }
        IconButton(onClick = { viewModel.unsave(search.query) }) {
            Icon(Icons.Default.Close, contentDescription = "Remove ${search.query.title} from Searches")
        }
    }
}

/** Says plainly when RedGIFs has changed something, instead of leaving feeds looking empty. */
@Composable
private fun ApiChangedBanner(viewModel: MainViewModel) {
    val changed by viewModel.apiChanged.collectAsStateWithLifecycle()
    val problem = changed ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Text(
                problem.message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            )
            TextButton(onClick = { viewModel.checkForUpdates(manual = true) }) { Text("Update") }
            IconButton(onClick = viewModel::dismissApiChanged) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun ExploreTags(viewModel: MainViewModel) {
    val tags by viewModel.trendingTags.collectAsStateWithLifecycle()
    if (tags.isEmpty()) {
        Message("Couldn't load tags")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tags, key = TrendingTag::name) { tag ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openTag(tag.name) }
                    .padding(16.dp),
            ) {
                Text(tag.name, modifier = Modifier.weight(1f))
                Text("${tag.count}", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun FollowingList(viewModel: MainViewModel) {
    val creators by viewModel.following.collectAsStateWithLifecycle()
    if (creators.isEmpty()) {
        Message("You're not following anyone yet")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(creators, key = Creator::userName) { creator ->
            Text(
                creator.userName,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openCreator(creator.userName) }
                    .padding(16.dp),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SavedLists(viewModel: MainViewModel) {
    val lists by viewModel.library.lists.collectAsStateWithLifecycle()
    if (lists.isEmpty()) {
        Message("Use the menu on any video to save it to a list")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(lists.keys.toList(), key = { it }) { name ->
            Text(
                "$name (${lists[name].orEmpty().size})",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openSavedList(name) }
                    .padding(16.dp),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SignInPrompt() {
    val context = LocalContext.current
    Message("Sign in to see your feeds") {
        Button(onClick = { startLogin(context) }) { Text("Sign in") }
    }
}

@Composable
fun Message(text: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text)
        Spacer(Modifier.height(16.dp))
        action()
    }
}

private const val LOAD_AHEAD = 12
private const val VISIBLE_SEARCHES = 6
private const val POPULAR_TAGS = 30
private const val NARROW_TAGS = 25
