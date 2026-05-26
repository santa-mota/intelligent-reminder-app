package com.santamota.reminder.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.santamota.reminder.data.models.DownloadState
import com.santamota.reminder.data.models.ModelRowState
import com.santamota.reminder.data.models.ModelSpec
import com.santamota.reminder.ui.theme.CornerRadius
import com.santamota.reminder.ui.theme.Size
import com.santamota.reminder.ui.theme.Spacing

@Composable
fun ModelsScreen(
    modifier: Modifier = Modifier,
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    var confirmFor by remember { mutableStateOf<ModelSpec?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = Spacing.l)) {
        Text(
            text = "On-device models",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.m),
        )
        Text(
            text = "Tap to download. Tap again to use. Active model is checked.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Spacing.l),
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
            contentPadding = PaddingValues(bottom = Spacing.xl),
        ) {
            items(rows, key = { it.spec.id }) { row ->
                ModelRow(
                    row = row,
                    onClick = {
                        if (row.isDownloaded) viewModel.onTap(row)
                        else if (row.download is DownloadState.Idle ||
                            row.download is DownloadState.Failed) {
                            confirmFor = row.spec
                        }
                    },
                )
            }
        }
    }

    confirmFor?.let { spec ->
        AlertDialog(
            onDismissRequest = { confirmFor = null },
            title = { Text("Download ${spec.displayName}?") },
            text = {
                Text(
                    "${spec.approxSizeMb} MB over your network. Once downloaded the " +
                        "model runs entirely on-device — no further network access needed."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.startDownload(spec)
                    confirmFor = null
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFor = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModelRow(row: ModelRowState, onClick: () -> Unit) {
    val downloading = row.download as? DownloadState.Downloading
    val failed = row.download as? DownloadState.Failed
    val isDimmed = !row.isDownloaded && downloading == null

    Surface(
        shape = RoundedCornerShape(CornerRadius.l),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = downloading == null, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.spec.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isDimmed)
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (row.isActive) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (row.isActive) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = Spacing.sm)
                                .size(Size.iconSm),
                        )
                    }
                }
                Text(
                    text = "${row.spec.params} · ${row.spec.approxSizeMb} MB",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = row.spec.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDimmed)
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                if (downloading != null) {
                    LinearProgressIndicator(
                        progress = { downloading.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.sm),
                    )
                    Text(
                        text = "${downloading.percent}% · " +
                            "${downloading.bytesDownloaded / 1_048_576} of " +
                            "${if (downloading.totalBytes > 0) downloading.totalBytes / 1_048_576 else "?"} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
                if (failed != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = Spacing.sm),
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Size.iconSm),
                        )
                        Text(
                            text = "Download failed: ${failed.message}. Tap to retry.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = Spacing.sm),
                        )
                    }
                }
            }
            if (downloading == null && !row.isDownloaded) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}
