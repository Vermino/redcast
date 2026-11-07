package com.oak404.redcast.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oak404.redcast.data.AppTheme
import com.oak404.redcast.data.FeedLayout
import com.oak404.redcast.data.VideoQuality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, appLock: AppLock) {
    val context = LocalContext.current
    val settings by viewModel.library.settings.collectAsStateWithLifecycle()
    val taste by viewModel.library.taste.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }
    val open: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Appearance") {
                Choices("Theme", AppTheme.entries, settings.theme, AppTheme::label) { choice ->
                    viewModel.updateSettings { it.copy(theme = choice) }
                }
                Choices("Feed layout", FeedLayout.entries, settings.layout, FeedLayout::label) { choice ->
                    viewModel.updateSettings { it.copy(layout = choice) }
                }
                if (settings.layout != FeedLayout.List) {
                    Choices("Columns", COLUMNS, settings.columns, { it.toString() }) { choice ->
                        viewModel.updateSettings { it.copy(columns = choice) }
                    }
                }
                Choices("Open on", HomeTab.entries, viewModel.defaultTab, HomeTab::label) { choice ->
                    viewModel.updateSettings { it.copy(defaultTab = choice.name) }
                }
            }

            Section("Playback") {
                Choices(
                    "Video quality",
                    VideoQuality.entries,
                    settings.quality,
                    VideoQuality::label,
                    note = "Auto uses HD on Wi-Fi and SD on mobile data. Chromecast always gets HD.",
                ) { choice ->
                    viewModel.updateSettings { it.copy(quality = choice) }
                }
                Toggle(
                    "Swipe to curate",
                    "Right keeps a clip and stays on it, left passes and moves on",
                    settings.swipeToCurate,
                ) { on -> viewModel.updateSettings { it.copy(swipeToCurate = on) } }
                Toggle(
                    "Show promoted clips",
                    "Promoted clips help support RedGIFs and are marked in the player",
                    settings.showPromoted,
                ) { on -> viewModel.updateSettings { it.copy(showPromoted = on) } }
            }

            Section("Privacy") {
                Toggle(
                    "App lock",
                    if (appLock.available) {
                        "Fingerprint or screen lock to open, and hidden from recent apps"
                    } else {
                        "Set up a fingerprint or screen lock on this phone first"
                    },
                    appLock.enabled,
                    enabled = appLock.available,
                ) { on -> appLock.enabled = on }
                Row("Watch history", "Clips you've opened, newest first") { viewModel.openHistory() }
                Row("Clear watch history", null, viewModel.library::clearHistory)
                Row("Clear search history", null, viewModel.library::clearSearches)
                Row("Export my data", "History, lists and settings as a JSON file") {
                    export.launch("redcast-data.json")
                }
            }

            Section("Hidden") {
                if (taste.hiddenTags.isEmpty() && taste.hiddenCreators.isEmpty() && taste.hiddenGifs.isEmpty()) {
                    Row("Nothing hidden", "Hide clips, creators or tags from their menus, or swipe left")
                }
                taste.hiddenTags.sorted().forEach { tag ->
                    Hidden("Tag: $tag") { viewModel.unhideTag(tag) }
                }
                taste.hiddenCreators.sorted().forEach { name ->
                    Hidden(name) { viewModel.unhideCreator(name) }
                }
                if (taste.hiddenGifs.isNotEmpty()) {
                    Hidden("${taste.hiddenGifs.size} hidden videos", viewModel::unhideGifs)
                }
            }

            Section("About") {
                val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                Row("Redcast $version", "Unofficial and open source. Not affiliated with RedGIFs.")
                Row("Check for updates", "From the releases on GitHub") { viewModel.checkForUpdates(manual = true) }
                Toggle(
                    "Check automatically",
                    "Look for a new version when Redcast opens. You choose whether to install it.",
                    settings.checkForUpdates,
                ) { on -> viewModel.updateSettings { it.copy(checkForUpdates = on) } }
                if (Links.SUPPORT.isNotEmpty()) {
                    Link("Support Redcast", "Help keep it going") { open(Links.SUPPORT) }
                }
                if (Links.SOURCE.isNotEmpty()) {
                    Link("Source code", "On GitHub") { open(Links.SOURCE) }
                }
                Link("Cedarbrook", "An NSFW AI sim from 404 Oak, built on Chronicle. 18+.") {
                    open(Links.CEDARBROOK)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

private val rowColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun Row(title: String, summary: String?, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        colors = rowColors,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun Link(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
        colors = rowColors,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Toggle(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = rowColors,
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

@Composable
private fun Hidden(label: String, onRestore: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            IconButton(onClick = onRestore) {
                Icon(Icons.Default.Close, contentDescription = "Show $label again")
            }
        },
        colors = rowColors,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    note: String? = null,
    onPick: (T) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

private val COLUMNS = listOf(2, 3, 4)
