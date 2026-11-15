package com.example.ui.screens.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.db.SkillEntity
import com.example.data.db.SkillVersionEntity
import com.example.ui.theme.CodeFontFamily
import com.example.ui.theme.CodeSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    viewModel: SkillsViewModel,
    isWideScreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val versions by viewModel.selectedVersions.collectAsStateWithLifecycle()

    var inputJson by remember { mutableStateOf("""{"url":"https://example.com","topic":"Kotlin Coroutines","task":"Hello"}""") }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showNewSkillDialog by remember { mutableStateOf(false) }
    var showAiSkillDialog by remember { mutableStateOf(false) }

    val selectedSkill = skills.firstOrNull { it.id == uiState.selectedSkillId }

    Row(modifier = modifier.fillMaxSize()) {
        // Wide Tablet Sidebar
        if (isWideScreen) {
            Surface(
                modifier = Modifier.width(260.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("QuickJS Skills", style = MaterialTheme.typography.titleMedium)
                        Row {
                            IconButton(
                                onClick = { showAiSkillDialog = true },
                                modifier = Modifier.testTag("ai_create_skill_button"),
                            ) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = "AI generate skill")
                            }
                            IconButton(
                                onClick = { showNewSkillDialog = true },
                                modifier = Modifier.testTag("new_skill_button"),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = "Create blank skill")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(skills, key = { it.id }) { skill ->
                            SkillSidebarRow(
                                skill = skill,
                                selected = skill.id == uiState.selectedSkillId,
                                onSelect = { viewModel.selectSkill(skill) },
                                onDelete = { viewModel.deleteSkill(skill) },
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

        // Main Editor + Sandbox Pane
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Compact skill switcher
            if (!isWideScreen) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("QuickJS Skills", style = MaterialTheme.typography.titleLarge)
                    Row {
                        IconButton(onClick = { showAiSkillDialog = true }) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = "AI generate skill")
                        }
                        IconButton(onClick = { showNewSkillDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "New skill")
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(skills, key = { it.id }) { skill ->
                        FilterChip(
                            selected = skill.id == uiState.selectedSkillId,
                            onClick = { viewModel.selectSkill(skill) },
                            label = { Text("${skill.slug}.js (v${skill.currentVersion})") },
                        )
                    }
                }
            }

            if (selectedSkill == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select or create a JavaScript skill to edit and run.")
                }
            } else {
                // Header & Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${selectedSkill.name}  •  v${selectedSkill.currentVersion}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${selectedSkill.slug}.js — ${selectedSkill.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { showHistorySheet = true },
                            modifier = Modifier.testTag("skill_history_button"),
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Versions")
                        }
                        OutlinedButton(
                            onClick = { viewModel.saveCurrentSkill() },
                            modifier = Modifier.testTag("save_skill_button"),
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save")
                        }
                        Button(
                            onClick = { viewModel.runCurrentSkill(inputJson) },
                            enabled = !uiState.isRunning,
                            modifier = Modifier.testTag("run_skill_button"),
                        ) {
                            if (uiState.isRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(if (uiState.isRunning) "Running" else "Run")
                        }
                    }
                }

                uiState.statusMessage?.let { msg ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                // Code Editor with Line Numbers & Monospace Font
                LineNumberedCodeEditor(
                    code = uiState.editorCode,
                    onCodeChange = { viewModel.onEditorCodeChanged(it) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )

                // Input JSON + Sandbox Execution Console
                OutlinedTextField(
                    value = inputJson,
                    onValueChange = { inputJson = it },
                    label = { Text("Skill Input JSON (passed to main(input))") },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = CodeFontFamily, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().testTag("skill_input_json"),
                    shape = RoundedCornerShape(10.dp),
                )

                uiState.lastExecution?.let { exec ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (exec.success) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = if (exec.success) "Sandbox Output" else "Sandbox Error: ${exec.error}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (exec.success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onErrorContainer,
                            )
                            if (exec.logs.isNotEmpty()) {
                                Text(
                                    text = exec.logs.joinToString("\n") { "[log] $it" },
                                    fontFamily = CodeFontFamily,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (exec.output.isNotBlank()) {
                                Text(
                                    text = exec.output,
                                    fontFamily = CodeFontFamily,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHistorySheet && selectedSkill != null) {
        ModalBottomSheet(onDismissRequest = { showHistorySheet = false }) {
            VersionHistorySheetContent(
                skill = selectedSkill,
                versions = versions,
                onRollback = { version ->
                    viewModel.rollbackToVersion(version)
                    showHistorySheet = false
                },
            )
        }
    }

    if (showNewSkillDialog) {
        CreateSkillDialog(
            onDismiss = { showNewSkillDialog = false },
            onCreate = { name, desc ->
                viewModel.createNewSkill(name, desc)
                showNewSkillDialog = false
            },
        )
    }

    if (showAiSkillDialog) {
        AiGenerateSkillDialog(
            isGenerating = uiState.isGenerating,
            onDismiss = { showAiSkillDialog = false },
            onGenerate = { goal ->
                viewModel.generateSkillWithAi(goal)
                showAiSkillDialog = false
            },
        )
    }
}

@Composable
private fun LineNumberedCodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val lineCount = remember(code) { maxOf(1, code.count { it == '\n' } + 1) }
    val lineNumbersText = remember(lineCount) { (1..lineCount).joinToString("\n") }

    val editorStyle = TextStyle(
        fontFamily = CodeFontFamily,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CodeSurface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .verticalScroll(verticalScroll)
            .padding(vertical = 10.dp),
    ) {
        // Line numbers gutter
        Text(
            text = lineNumbersText,
            fontFamily = CodeFontFamily,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier
                .width(44.dp)
                .padding(horizontal = 8.dp),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            textStyle = editorStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScroll)
                .padding(horizontal = 12.dp)
                .testTag("skill_code_editor"),
        )
    }
}

@Composable
private fun SkillSidebarRow(
    skill: SkillEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${skill.slug}.js • v${skill.currentVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete skill", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun VersionHistorySheetContent(
    skill: SkillEntity,
    versions: List<SkillVersionEntity>,
    onRollback: (SkillVersionEntity) -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Version History — ${skill.slug}.js",
            style = MaterialTheme.typography.titleLarge,
        )
        LazyColumn(
            modifier = Modifier.heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(versions, key = { it.id }) { ver ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "v${ver.version} • ${if (ver.isWorking) "Working" else "Crashed"}",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (ver.isWorking) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = "${ver.changeReason} (${fmt.format(Date(ver.createdAt))})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { onRollback(ver) }) {
                            Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Rollback")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CreateSkillDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New QuickJS Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Skill Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, description) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AiGenerateSkillDialog(
    isGenerating: Boolean,
    onDismiss: () -> Unit,
    onGenerate: (String) -> Unit,
) {
    var goal by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI Write New Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Describe what the skill should do. The AI will generate sandboxed QuickJS code using the 6 built-in tools.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("e.g., Compare two GitHub repos and save pros/cons") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onGenerate(goal) },
                enabled = goal.isNotBlank() && !isGenerating,
            ) {
                Text(if (isGenerating) "Writing..." else "Generate Skill")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
