package com.example.ui.screens.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.db.SourceEntity
import com.example.data.db.SourceStatus
import com.example.data.db.SourceType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    isWideScreen: Boolean,
    onExploreInternet: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var quickUrl by remember { mutableStateOf("") }
    var editingSource by remember { mutableStateOf<SourceEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.testTag("add_source_fab"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add source")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Research Sources",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Paste any link, GitHub repo, docs, or topic — or browse & learn live sites via Chrome.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(
                    onClick = { onExploreInternet(quickUrl.trim().ifBlank { null }) },
                    modifier = Modifier.testTag("sources_explore_internet_button"),
                ) {
                    Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Explore Internet")
                }
            }

            // Quick paste link bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = quickUrl,
                    onValueChange = { quickUrl = it },
                    placeholder = { Text("Paste URL (web, GitHub, YouTube, PDF)...") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("quick_source_url_input"),
                    shape = RoundedCornerShape(12.dp),
                )
                Button(
                    onClick = {
                        viewModel.addSource(SourceType.URL, "", quickUrl)
                        quickUrl = ""
                    },
                    enabled = quickUrl.isNotBlank(),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("quick_add_url_button"),
                ) {
                    Text("Add Link")
                }
            }

            uiState.statusBanner?.let { banner ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.dismissBanner() }) {
                            Text("OK")
                        }
                    }
                }
            }

            if (sources.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "No Sources Added Yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Paste a URL above or tap + to add URLs, Topics, or Search Queries.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (isWideScreen) 2 else 1),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    items(sources, key = { it.id }) { source ->
                        SourceCard(
                            source = source,
                            isReading = source.id in uiState.activeReadingIds,
                            onToggle = { enabled -> viewModel.toggleEnabled(source, enabled) },
                            onReadNow = { viewModel.readSourceNow(source) },
                            onExplore = { onExploreInternet(source.value) },
                            onEdit = { editingSource = source },
                            onDelete = { viewModel.deleteSource(source) },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog || editingSource != null) {
        SourceEditDialog(
            initial = editingSource,
            onDismiss = {
                showAddDialog = false
                editingSource = null
            },
            onSave = { type, title, value ->
                val target = editingSource
                if (target == null) {
                    viewModel.addSource(type, title, value)
                } else {
                    viewModel.updateSource(target, type, title, value)
                }
                showAddDialog = false
                editingSource = null
            },
        )
    }
}

@Composable
private fun SourceCard(
    source: SourceEntity,
    isReading: Boolean,
    onToggle: (Boolean) -> Unit,
    onReadNow: () -> Unit,
    onExplore: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val type = runCatching { SourceType.valueOf(source.type) }.getOrDefault(SourceType.URL)
    val status = runCatching { SourceStatus.valueOf(source.status) }.getOrDefault(SourceStatus.IDLE)
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = when (type) {
                            SourceType.URL -> Icons.Filled.Language
                            SourceType.TOPIC -> Icons.Filled.Topic
                            SourceType.SEARCH_QUERY -> Icons.Filled.Search
                        },
                        contentDescription = type.name,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = source.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = source.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("source_toggle_${source.id}"),
                )
            }

            // Status metadata row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusColor = when (status) {
                    SourceStatus.SUCCESS -> MaterialTheme.colorScheme.secondary
                    SourceStatus.ERROR -> MaterialTheme.colorScheme.error
                    SourceStatus.READING -> MaterialTheme.colorScheme.primary
                    SourceStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = if (isReading) "READING..." else status.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
                Text(
                    text = "Entries: ${source.entryCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = source.lastReadAt?.let { "Last: ${dateFormat.format(Date(it))}" } ?: "Never read",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!source.lastError.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = source.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onReadNow,
                        enabled = !isReading,
                        modifier = Modifier.testTag("read_source_now_${source.id}"),
                    ) {
                        if (isReading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(6.dp))
                            Text("Reading...")
                        } else {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Read Now")
                        }
                    }
                    IconButton(onClick = onExplore, modifier = Modifier.testTag("explore_source_${source.id}")) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in Web Explorer")
                    }
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit source")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete source")
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceEditDialog(
    initial: SourceEntity?,
    onDismiss: () -> Unit,
    onSave: (SourceType, String, String) -> Unit,
) {
    var selectedType by remember {
        mutableStateOf(
            initial?.let { runCatching { SourceType.valueOf(it.type) }.getOrNull() } ?: SourceType.URL,
        )
    }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var value by remember { mutableStateOf(initial?.value.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Research Source" else "Edit Source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceType.entries.forEach { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = { selectedType = t },
                            label = {
                                Text(
                                    when (t) {
                                        SourceType.URL -> "URL"
                                        SourceType.TOPIC -> "Topic"
                                        SourceType.SEARCH_QUERY -> "Search"
                                    },
                                )
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = {
                        Text(
                            when (selectedType) {
                                SourceType.URL -> "URL (Web, GitHub, YouTube, PDF)"
                                SourceType.TOPIC -> "Topic Name"
                                SourceType.SEARCH_QUERY -> "Search Query"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("dialog_source_value_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedType, title, value) },
                enabled = value.isNotBlank(),
                modifier = Modifier.testTag("dialog_source_save_button"),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
