package com.oak404.redcast.ui

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
import androidx.compose.material3.Checkbox
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
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = {
                                query = it
                                viewModel.onQueryChanged(it)
                            },
                            placeholder = { Text("Search") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                viewModel.search(query)
                                searching = false
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Redcast")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        viewModel.onQueryChanged("")
                    }) {
                        Icon(
                            if (searching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searching) "Close search" else "Search",
                        )
                    }
                    IconButton(onClick = { viewModel.shufflePlay(viewModel.feedFor(viewModel.tab)) }) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle this feed")
                    }
                    CastButton(viewModel)
                    AccountMenu(viewModel, signedIn, appLock)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching && query.isBlank()) {
                RecentSearches(viewModel) { text ->
                    viewModel.search(text)
                    searching = false
                }
            }
            if (searching && suggestions.isNotEmpty()) {
                suggestions.forEach { suggestion ->
                    Text(
                        suggestion.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.openSuggestion(suggestion)
                                searching = false
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                HorizontalDivider()
            }
            PrimaryScrollableTabRow(selectedTabIndex = viewModel.tab.ordinal) {
                HomeTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == viewModel.tab,
                        onClick = { viewModel.tab = tab },
                        text = { Text(tab.label, maxLines = 1) },
                    )
                }
            }
            when (viewModel.tab) {
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
                    IconButton(onClick = { viewModel.shufflePlay(feed) }) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle this feed")
                    }
                    CastButton(viewModel)
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            FeedGrid(viewModel, feed)
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
                text = { Text("App lock") },
                leadingIcon = { Checkbox(checked = appLock.enabled, onCheckedChange = null) },
                enabled = appLock.available,
                onClick = {
                    appLock.enabled = !appLock.enabled
                    open = false
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
    val scope = rememberCoroutineScope()
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

    when {
        gifs.isEmpty() && error is SignInRequiredException -> SignInPrompt()
        gifs.isEmpty() && error != null -> Message(error?.message ?: "Couldn't load this feed") {
            Button(onClick = { scope.launch { feed.loadMore() } }) { Text("Retry") }
        }
        gifs.isEmpty() && exhausted -> Message("Nothing here yet")
        gifs.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            if (loading) CircularProgressIndicator()
        }
        layout == FeedLayout.List -> InlineList(viewModel, feed, gifs, list)
        layout == FeedLayout.Staggered -> LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(settings.columns),
            state = staggered,
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalItemSpacing = 4.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            staggeredItemsIndexed(gifs, key = { _, gif -> gif.id }) { index, gif ->
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
            itemsIndexed(gifs, key = { _, gif -> gif.id }) { index, gif ->
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
private fun RecentSearches(viewModel: MainViewModel, onPick: (String) -> Unit) {
    val searches by viewModel.library.searches.collectAsStateWithLifecycle()
    if (searches.isEmpty()) return
    searches.take(VISIBLE_SEARCHES).forEach { text ->
        Text(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(text) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    TextButton(onClick = viewModel.library::clearSearches) { Text("Clear recent searches") }
    HorizontalDivider()
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
