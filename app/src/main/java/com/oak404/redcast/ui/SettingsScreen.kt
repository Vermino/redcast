package com.oak404.redcast.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oak404.redcast.data.AppTheme
import com.oak404.redcast.data.FeedLayout
import com.oak404.redcast.data.VideoQuality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.library.settings.collectAsStateWithLifecycle()
    val taste by viewModel.library.taste.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Heading("Theme")
            Choices(AppTheme.entries, settings.theme, AppTheme::label) { choice ->
                viewModel.updateSettings { it.copy(theme = choice) }
            }
            Heading("Feed layout")
            Choices(FeedLayout.entries, settings.layout, FeedLayout::label) { choice ->
                viewModel.updateSettings { it.copy(layout = choice) }
            }
            if (settings.layout != FeedLayout.List) {
                Heading("Columns")
                Choices(COLUMNS, settings.columns, { it.toString() }) { choice ->
                    viewModel.updateSettings { it.copy(columns = choice) }
                }
            }
            Heading("Open on")
            Choices(HomeTab.entries, viewModel.defaultTab, HomeTab::label) { choice ->
                viewModel.updateSettings { it.copy(defaultTab = choice.name) }
            }
            Heading("Video quality")
            Choices(VideoQuality.entries, settings.quality, VideoQuality::label) { choice ->
                viewModel.updateSettings { it.copy(quality = choice) }
            }
            Note("Auto uses HD on Wi-Fi and SD on mobile data. Chromecast always gets HD.")

            HorizontalDivider()
            Heading("Hidden")
            if (taste.hiddenTags.isEmpty() && taste.hiddenCreators.isEmpty() && taste.hiddenGifs.isEmpty()) {
                Note("Nothing hidden")
            }
            taste.hiddenTags.sorted().forEach { tag ->
                HiddenRow("Tag: $tag") { viewModel.unhideTag(tag) }
            }
            taste.hiddenCreators.sorted().forEach { name ->
                HiddenRow(name) { viewModel.unhideCreator(name) }
            }
            if (taste.hiddenGifs.isNotEmpty()) {
                HiddenRow("${taste.hiddenGifs.size} hidden videos", viewModel::unhideGifs)
            }

            HorizontalDivider()
            Heading("Your data")
            Note("History, lists and settings stay on this phone unless you export them.")
            OutlinedButton(onClick = { export.launch("redcast-data.json") }) { Text("Export my data") }
            OutlinedButton(onClick = viewModel.library::clearHistory) { Text("Clear watch history") }
            OutlinedButton(onClick = viewModel.library::clearSearches) { Text("Clear search history") }

            HorizontalDivider()
            About()
        }
    }
}

@Composable
private fun About() {
    val context = LocalContext.current
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
    val open: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Heading("About")
    Note("Redcast $version. An unofficial, open-source client, not affiliated with RedGIFs.")
    if (Links.SUPPORT.isNotEmpty()) {
        OutlinedButton(onClick = { open(Links.SUPPORT) }) { Text("Support Redcast") }
    }
    if (Links.SOURCE.isNotEmpty()) {
        OutlinedButton(onClick = { open(Links.SOURCE) }) { Text("Source code") }
    }
    Heading("Also from 404 Oak")
    Note("Cedarbrook: an NSFW AI sim built on Chronicle. 18+.")
    OutlinedButton(onClick = { open(Links.CEDARBROOK) }) { Text("Visit Cedarbrook") }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HiddenRow(label: String, onRestore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onRestore) {
            Icon(Icons.Default.Close, contentDescription = "Show $label again")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onPick(option) },
                label = { Text(label(option), maxLines = 1) },
            )
        }
    }
}

private val COLUMNS = listOf(2, 3, 4)
