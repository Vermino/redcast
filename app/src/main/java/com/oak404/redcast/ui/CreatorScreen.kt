package com.oak404.redcast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.oak404.redcast.data.Profile

private val ORDERS = listOf("latest" to "Latest", "trending" to "Trending", "top" to "Top")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorScreen(viewModel: MainViewModel, route: Route.Creator) {
    val userName = route.userName
    val feed = route.feed
    val signedIn by viewModel.session.signedIn.collectAsStateWithLifecycle()
    val following by viewModel.following.collectAsStateWithLifecycle()
    val profile by produceState<Profile?>(null, userName) { value = viewModel.profile(userName) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(userName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.shufflePlay(feed) }) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle this creator")
                    }
                    IconButton(onClick = {
                        viewModel.hideCreator(userName)
                        viewModel.back()
                    }) {
                        Icon(Icons.Default.Block, contentDescription = "Hide this creator")
                    }
                    CastButton(viewModel)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(profile?.avatarUrl)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        profile?.displayName ?: userName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    profile?.let {
                        Text(
                            "${it.followers} followers · ${it.gifCount} videos",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (signedIn) {
                    if (following.any { it.userName == userName }) {
                        OutlinedButton(onClick = { viewModel.toggleFollow(userName) }) { Text("Following") }
                    } else {
                        Button(onClick = { viewModel.toggleFollow(userName) }) { Text("Follow") }
                    }
                }
            }
            profile?.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Row(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ORDERS.forEach { (key, label) ->
                    FilterChip(
                        selected = key == route.order,
                        onClick = { viewModel.sortCreator(route, key) },
                        label = { Text(label) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                FeedGrid(viewModel, feed)
            }
        }
    }
}

@Composable
private fun Avatar(url: String?) {
    val modifier = Modifier.size(56.dp).clip(CircleShape)
    if (url == null) {
        Icon(Icons.Default.Person, contentDescription = null, modifier = modifier)
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}
