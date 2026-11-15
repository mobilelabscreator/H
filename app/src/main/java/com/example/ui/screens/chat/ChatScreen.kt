package com.example.ui.screens.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ChatSessionEntity
import com.example.data.db.SourceCitation
import com.example.ui.components.MarkdownText
import com.example.ui.theme.CodeFontFamily

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    isWideScreen: Boolean,
    needsApiKey: Boolean,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val neuralStats by viewModel.neuralStats.collectAsStateWithLifecycle()
    val workspaceSummary by viewModel.workspaceSummary.collectAsStateWithLifecycle()
    val agentRunState by viewModel.agentRunState.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    var showHistoryMenu by remember { mutableStateOf(false) }

    Row(modifier = modifier.fillMaxSize()) {
        // Persistent Session Sidebar on Tablet / Wide Landscape
        if (isWideScreen) {
            Surface(
                modifier = Modifier
                    .width(240.dp)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Sessions",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(
                            onClick = { viewModel.createNewSession() },
                            modifier = Modifier.testTag("new_chat_button"),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "New chat session")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(sessions, key = { it.id }) { session ->
                            SessionSidebarItem(
                                session = session,
                                selected = session.id == uiState.activeSessionId,
                                onSelect = { viewModel.selectSession(session.id) },
                                onDelete = { viewModel.deleteSession(session.id) },
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }

        // Main Chat Pane
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .imePadding(),
        ) {
            // Header
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                text = "Agent Lab Shell",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val statsLabel = neuralStats?.let {
                                "${it.parameterCount} params • ep ${it.totalEpochs} • loss ${"%.3f".format(it.lastLoss)}"
                            } ?: "Neural Core ready"
                            val repoLabel = workspaceSummary.repoSlug.ifBlank { "no repo" }
                            val phaseLabel = if (agentRunState.isRunning) " • ${agentRunState.currentPhase}" else ""
                            Text(
                                text = "$statsLabel • $repoLabel$phaseLabel",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = CodeFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isWideScreen) {
                            Box {
                                IconButton(
                                    onClick = { showHistoryMenu = true },
                                    modifier = Modifier.testTag("chat_history_button"),
                                ) {
                                    Icon(Icons.Filled.History, contentDescription = "Chat history")
                                }
                                DropdownMenu(
                                    expanded = showHistoryMenu,
                                    onDismissRequest = { showHistoryMenu = false },
                                ) {
                                    sessions.forEach { session ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = session.title,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            onClick = {
                                                viewModel.selectSession(session.id)
                                                showHistoryMenu = false
                                            },
                                            trailingIcon = {
                                                IconButton(onClick = { viewModel.deleteSession(session.id) }) {
                                                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete session")
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = { viewModel.createNewSession() },
                                modifier = Modifier.testTag("new_chat_compact_button"),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = "New chat")
                            }
                        }
                        if (messages.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.clearCurrentChat() },
                                modifier = Modifier.testTag("clear_chat_button"),
                            ) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "Clear messages",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (needsApiKey) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Selected cloud API requires a key in Settings, or switch to Agent Lab Core (No Key).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.testTag("chat_go_settings_button"),
                        ) {
                            Text("Settings")
                        }
                    }
                }
            }

            uiState.errorMessage?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            // Messages List
            val listState = rememberLazyListState()
            LaunchedEffect(messages.size, uiState.streamingText.length) {
                val total = messages.size + if (uiState.isStreaming) 1 else 0
                if (total > 0) {
                    listState.animateScrollToItem(total - 1)
                }
            }

            if (messages.isEmpty() && !uiState.isStreaming) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ChatEmptyHero(
                        onQuickPrompt = { prompt ->
                            inputText = prompt
                            viewModel.sendMessage(prompt)
                            inputText = ""
                        },
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatMessageBubble(
                            message = msg,
                            citations = viewModel.parseCitations(msg.citationsJson),
                        )
                    }
                    if (uiState.isStreaming) {
                        item(key = "streaming_bubble") {
                            StreamingBubble(
                                text = uiState.streamingText,
                                citations = uiState.streamingCitations,
                            )
                        }
                    }
                }
            }

            // Termux-style quick slash command bar + Composer Bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val quickCmds = listOf(
                            "/run" to true,
                            "/train" to true,
                            "/sync" to true,
                            "/status" to true,
                            "/ls" to true,
                            "/repo " to false,
                            "/upgrade " to false,
                            "/grep " to false,
                            "/help" to true,
                        )
                        quickCmds.forEach { (cmd, executeImmediately) ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .clickable {
                                        if (executeImmediately && !uiState.isStreaming) {
                                            viewModel.sendMessage(cmd)
                                        } else {
                                            inputText = cmd
                                        }
                                    }
                                    .testTag("cmd_chip_${cmd.trim().removePrefix("/")}"),
                            ) {
                                Text(
                                    text = cmd.trim(),
                                    fontFamily = CodeFontFamily,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (cmd == "/run") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Prompt AI or run /run, /train, /repo owner/repo, /ls...") },
                            maxLines = 5,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_input_field"),
                            shape = RoundedCornerShape(16.dp),
                        )
                        if (uiState.isStreaming) {
                            FilledIconButton(
                                onClick = { viewModel.stopStreaming() },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("chat_stop_button"),
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = "Stop generating")
                            }
                        } else {
                            FilledIconButton(
                                onClick = {
                                    val text = inputText
                                    inputText = ""
                                    viewModel.sendMessage(text)
                                },
                                enabled = inputText.isNotBlank(),
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("chat_send_button"),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSidebarItem(
    session: ChatSessionEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Delete session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatEmptyHero(onQuickPrompt: (String) -> Unit) {
    Card(
        modifier = Modifier.widthIn(max = 560.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column {
            Image(
                painter = painterResource(id = R.drawable.img_agent_core),
                contentDescription = "Agent Lab neural core artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Termux AI Environment & Neural Core",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Train on web knowledge & your GitHub repo locally, run sandboxed QuickJS tools, and type /run to trigger autonomous research, training & self-upgrade PRs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "/run",
                        "/train",
                        "/status",
                        "/help",
                    ).forEach { prompt ->
                        AssistChip(
                            onClick = { onQuickPrompt(prompt) },
                            label = { Text(prompt, fontFamily = CodeFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageBubble(
    message: ChatMessageEntity,
    citations: List<SourceCitation>,
) {
    val isUser = message.role == "user"
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .border(
                    width = 1.dp,
                    color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    ),
                ),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isUser) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    MarkdownText(markdown = message.content)
                    if (citations.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = "Cited Knowledge Sources (${citations.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            citations.forEach { cite ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.clickable(enabled = cite.sourceUrl.startsWith("http")) {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cite.sourceUrl)))
                                        }
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Link,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = "${cite.title.take(28)} (${"%.0f".format(cite.similarity * 100)}%)",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (cite.sourceUrl.startsWith("http")) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = "Open source URL",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(12.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(
    text: String,
    citations: List<SourceCitation>,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .widthIn(max = 720.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                )
                Text(
                    text = if (citations.isNotEmpty()) "Streaming with ${citations.size} RAG sources..." else "Streaming reply...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (text.isNotBlank()) {
                MarkdownText(markdown = text)
            }
        }
    }
}
