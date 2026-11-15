package com.example.ui.screens.agent

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ai.NeuralModelStats
import com.example.core.github.WorkspaceSummary
import com.example.data.db.AgentLogEntity
import com.example.data.db.EvalQuestionEntity
import com.example.data.db.GitHubPrEntity
import com.example.data.db.ImprovementProposalEntity
import com.example.data.db.ProposalStatus
import com.example.data.db.TrainingCheckpointEntity
import com.example.ui.components.DiffViewer
import com.example.ui.theme.CodeFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    isWideScreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val runState by viewModel.runState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val proposals by viewModel.proposals.collectAsStateWithLifecycle()
    val evalQuestions by viewModel.evalQuestions.collectAsStateWithLifecycle()
    val githubPrs by viewModel.githubPrs.collectAsStateWithLifecycle()
    val autoApprove by viewModel.autoApprove.collectAsStateWithLifecycle()
    val isGitHubRunning by viewModel.isGitHubRunning.collectAsStateWithLifecycle()
    val githubError by viewModel.githubError.collectAsStateWithLifecycle()
    val brainTransitions by viewModel.brainTransitions.collectAsStateWithLifecycle()
    val neuralStats by viewModel.neuralStats.collectAsStateWithLifecycle()
    val checkpoints by viewModel.checkpoints.collectAsStateWithLifecycle()
    val workspaceSummary by viewModel.workspaceSummary.collectAsStateWithLifecycle()
    val isTraining by viewModel.isTraining.collectAsStateWithLifecycle()

    var selectedSubTab by remember { mutableIntStateOf(0) }
    val subTabs = listOf(
        "Activity Log",
        "Neural Core (${checkpoints.size})",
        "Improvements (${proposals.size})",
        "Eval Set (${evalQuestions.size})",
        "GitHub PRs",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Control Header Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Autonomous Agent",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Phase: ${runState.currentPhase}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (runState.isRunning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Params: ${neuralStats?.parameterCount ?: 0} • Ep ${neuralStats?.totalEpochs ?: 0} • W:$brainTransitions",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = CodeFontFamily,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.testTag("agent_brain_weights"),
                            )
                            if (runState.lastScoreBefore != null) {
                                val before = "${"%.1f".format(runState.lastScoreBefore!! * 100)}%"
                                val after = runState.lastScoreAfter?.let { "${"%.1f".format(it * 100)}%" }
                                Text(
                                    text = if (after != null) "Eval: $before -> $after" else "Eval: $before",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.trainNow() },
                            enabled = !isTraining && !runState.isRunning,
                            modifier = Modifier.testTag("agent_train_button"),
                        ) {
                            if (isTraining) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.ModelTraining, contentDescription = null)
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("TRAIN")
                        }
                        Button(
                            onClick = { viewModel.startAgent() },
                            enabled = !runState.isRunning,
                            modifier = Modifier.testTag("agent_run_button"),
                        ) {
                            if (runState.isRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("RUN")
                        }
                        OutlinedButton(
                            onClick = { viewModel.stopAgent() },
                            enabled = runState.isRunning,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.testTag("agent_stop_button"),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("STOP")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Auto-approve changes that improved the score",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = autoApprove,
                        onCheckedChange = { viewModel.toggleAutoApprove(it) },
                        modifier = Modifier.testTag("auto_approve_switch"),
                    )
                }
            }
        }

        // Sub-tab selector (scrollable for smaller handheld widths)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            subTabs.forEachIndexed { index, title ->
                FilterChip(
                    selected = selectedSubTab == index,
                    onClick = { selectedSubTab = index },
                    label = { Text(title, maxLines = 1) },
                    modifier = Modifier.testTag("agent_subtab_$index"),
                )
            }
        }

        when (selectedSubTab) {
            0 -> ActivityLogPane(
                logs = logs,
                onClear = { viewModel.clearLogs() },
                modifier = Modifier.weight(1f),
            )
            1 -> NeuralTrainingPane(
                stats = neuralStats,
                checkpoints = checkpoints,
                workspace = workspaceSummary,
                isTraining = isTraining,
                onTrainNow = { viewModel.trainNow() },
                modifier = Modifier.weight(1f),
            )
            2 -> ImprovementsPane(
                proposals = proposals,
                onApprove = { viewModel.approveProposal(it) },
                onReject = { viewModel.rejectProposal(it) },
                modifier = Modifier.weight(1f),
            )
            3 -> EvalQuestionsPane(
                questions = evalQuestions,
                onAdd = { q, a -> viewModel.addEvalQuestion(q, a) },
                onUpdate = { item, q, a -> viewModel.updateEvalQuestion(item, q, a) },
                onDelete = { viewModel.deleteEvalQuestion(it) },
                modifier = Modifier.weight(1f),
            )
            4 -> GitHubSelfRewritePane(
                prs = githubPrs,
                workspace = workspaceSummary,
                isRunning = isGitHubRunning,
                errorMessage = githubError,
                onSyncRepo = { viewModel.syncRepoNow() },
                onTriggerPr = { viewModel.triggerSelfRewritePr(it) },
                onRefreshPr = { viewModel.refreshPr(it) },
                onDismissError = { viewModel.dismissGitHubError() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ActivityLogPane(
    logs: List<AgentLogEntity>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live Activity Stream (${logs.size})", style = MaterialTheme.typography.titleSmall)
            if (logs.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Clear Log") }
            }
        }
        if (logs.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Tap RUN to start the autonomous loop: Research -> Learn -> Evaluate -> Improve -> Test -> Approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(logs, key = { it.id }) { log ->
                    val levelColor = when (log.level) {
                        "SUCCESS" -> MaterialTheme.colorScheme.secondary
                        "ERROR" -> MaterialTheme.colorScheme.error
                        "WARN" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = fmt.format(Date(log.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "[${log.phase}]",
                                fontFamily = CodeFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = levelColor,
                            )
                            Text(
                                text = log.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImprovementsPane(
    proposals: List<ImprovementProposalEntity>,
    onApprove: (ImprovementProposalEntity) -> Unit,
    onReject: (ImprovementProposalEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (proposals.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = modifier.fillMaxWidth(),
        ) {
            Text(
                text = "No improvement proposals yet. Run the agent to evaluate weak spots and generate tested prompt, skill, and strategy diffs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        items(proposals, key = { it.id }) { prop ->
            val status = runCatching { ProposalStatus.valueOf(prop.status) }.getOrDefault(ProposalStatus.PENDING)
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(prop.targetName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Score: ${"%.1f".format(prop.scoreBefore * 100)}% -> ${"%.1f".format(prop.scoreAfter * 100)}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (prop.scoreAfter > prop.scoreBefore) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = status.name,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }

                    Text(
                        text = prop.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    DiffViewer(
                        beforeText = prop.beforeContent,
                        afterText = prop.afterContent,
                    )

                    if (status == ProposalStatus.PENDING) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { onReject(prop) },
                                modifier = Modifier.testTag("reject_proposal_${prop.id}"),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reject")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { onApprove(prop) },
                                modifier = Modifier.testTag("approve_proposal_${prop.id}"),
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Approve")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvalQuestionsPane(
    questions: List<EvalQuestionEntity>,
    onAdd: (String, String) -> Unit,
    onUpdate: (EvalQuestionEntity, String, String) -> Unit,
    onDelete: (EvalQuestionEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<EvalQuestionEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Evaluation Benchmark Set", style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = { showAdd = true },
                modifier = Modifier.testTag("add_eval_question_button"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Question")
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            items(questions, key = { it.id }) { q ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (q.isAutoGenerated) "AUTO-GENERATED" else "USER TEST",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            q.lastScore?.let { sc ->
                                Text(
                                    text = "Last Score: ${"%.0f".format(sc * 100)}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sc >= 0.7f) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        Text(q.question, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Expected: ${q.expectedAnswer}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = { editing = q }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit eval question")
                            }
                            IconButton(onClick = { onDelete(q) }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete eval question")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd || editing != null) {
        EvalEditDialog(
            initial = editing,
            onDismiss = {
                showAdd = false
                editing = null
            },
            onSave = { q, a ->
                val target = editing
                if (target == null) onAdd(q, a) else onUpdate(target, q, a)
                showAdd = false
                editing = null
            },
        )
    }
}

@Composable
private fun EvalEditDialog(
    initial: EvalQuestionEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var q by remember { mutableStateOf(initial?.question.orEmpty()) }
    var a by remember { mutableStateOf(initial?.expectedAnswer.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Eval Question" else "Edit Eval Question") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it },
                    label = { Text("Question") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = a,
                    onValueChange = { a = it },
                    label = { Text("Expected Reference Answer") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(q, a) }, enabled = q.isNotBlank() && a.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NeuralTrainingPane(
    stats: NeuralModelStats?,
    checkpoints: List<TrainingCheckpointEntity>,
    workspace: WorkspaceSummary,
    isTraining: Boolean,
    onTrainNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Trainable Neural Core (Backprop + Adam)", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = onTrainNow,
                        enabled = !isTraining,
                        modifier = Modifier.testTag("train_2_epochs_button"),
                    ) {
                        Text(if (isTraining) "Training..." else "Train 2 Epochs")
                    }
                }
                Text(
                    text = if (stats != null) {
                        "Params: ${stats.parameterCount}  •  Vocab: ${stats.vocabSize}  •  Epochs: ${stats.totalEpochs}  •  Steps: ${stats.totalSteps}\n" +
                            "Last Loss: ${"%.4f".format(stats.lastLoss)}  •  Best Loss: ${"%.4f".format(stats.bestLoss)}  •  PPL: ${"%.2f".format(stats.lastPerplexity)}\n" +
                            "Synced GitHub Repo: ${workspace.repoSlug.ifBlank { "none" }} (${workspace.syncedFiles} files, ${workspace.totalLines} lines)"
                    } else {
                        "Initializing neural network tensors..."
                    },
                    fontFamily = CodeFontFamily,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (checkpoints.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "No backpropagation checkpoints recorded yet. Tap TRAIN, run /train, or sync a GitHub repo to train weights.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(checkpoints, key = { it.id }) { cp ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Epoch #${cp.epoch} • ${cp.sourceLabel}",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = "loss=${"%.4f".format(cp.loss)}  ppl=${"%.2f".format(cp.perplexity)}  vocab=${cp.vocabSize}  tokens=${cp.tokensProcessed}",
                                    fontFamily = CodeFontFamily,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            Text(
                                text = fmt.format(Date(cp.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitHubSelfRewritePane(
    prs: List<GitHubPrEntity>,
    workspace: WorkspaceSummary,
    isRunning: Boolean,
    errorMessage: String?,
    onSyncRepo: () -> Unit,
    onTriggerPr: (String) -> Unit,
    onRefreshPr: (GitHubPrEntity) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var taskInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Self-Rewriting GitHub Loop",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (workspace.repoSlug.isNotBlank()) {
                        "Workspace: ${workspace.repoSlug} (${workspace.syncedFiles} files, ${workspace.totalLines} lines). Pulls code, trains Neural Core, opens feature-branch PRs, and monitors GitHub Actions APK builds."
                    } else {
                        "Set owner/repo in Settings or run `/repo owner/repo` in Chat so the AI can clone, train on its codebase, and open self-upgrade PRs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = taskInput,
                    onValueChange = { taskInput = it },
                    label = { Text("Describe app feature or code upgrade via PR") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("github_task_input"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSyncRepo,
                        enabled = !isRunning,
                        modifier = Modifier.testTag("sync_github_repo_button"),
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sync & Train")
                    }
                    Button(
                        onClick = {
                            onTriggerPr(taskInput)
                            taskInput = ""
                        },
                        enabled = taskInput.isNotBlank() && !isRunning,
                        modifier = Modifier.testTag("trigger_github_pr_button"),
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (isRunning) "Building PR..." else "Open Upgrade PR")
                    }
                }
            }
        }

        errorMessage?.let { err ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            items(prs, key = { it.id }) { pr ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "PR #${pr.prNumber} • ${pr.branchName}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onRefreshPr(pr) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh build status")
                            }
                        }
                        Text(pr.taskDescription, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "CI Status: ${pr.buildStatus} (${pr.buildConclusion ?: "running"}) • Attempt ${pr.attempts}/3",
                            style = MaterialTheme.typography.labelMedium,
                            color = when (pr.buildConclusion) {
                                "success" -> MaterialTheme.colorScheme.secondary
                                "failure" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                        if (!pr.lastError.isNullOrBlank()) {
                            Text(
                                text = pr.lastError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 3,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (pr.prUrl.startsWith("http")) {
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pr.prUrl)))
                                        }
                                    },
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Open PR")
                                }
                            }
                            val downloadUrl = pr.artifactUrl ?: pr.runHtmlUrl
                            if (!downloadUrl.isNullOrBlank() && downloadUrl.startsWith("http")) {
                                Button(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Get APK")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
