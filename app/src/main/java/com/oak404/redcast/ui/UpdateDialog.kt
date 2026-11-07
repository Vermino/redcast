package com.oak404.redcast.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Offers a new release; nothing downloads or installs unless the person taps Update. */
@Composable
fun UpdateDialog(viewModel: MainViewModel) {
    val release = viewModel.update ?: return
    val progress = viewModel.updateProgress
    AlertDialog(
        onDismissRequest = { if (progress == null) viewModel.dismissUpdate() },
        title = { Text("Redcast ${release.version} is available") },
        text = {
            Column {
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    )
                }
                if (progress != null) {
                    Spacer(Modifier.height(16.dp))
                    Text("Downloading…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null, onClick = viewModel::installUpdate) { Text("Update") }
        },
        dismissButton = {
            if (progress == null) {
                TextButton(onClick = viewModel::skipUpdate) { Text("Skip this version") }
                TextButton(onClick = viewModel::dismissUpdate) { Text("Later") }
            }
        },
    )
}
