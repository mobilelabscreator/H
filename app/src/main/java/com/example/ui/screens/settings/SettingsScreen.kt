package com.example.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.settings.AgentSettings
import com.example.data.settings.AiProvider
import com.example.data.settings.SearchProvider

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val persisted by viewModel.settings.collectAsStateWithLifecycle()
    val savedMessage by viewModel.savedMessage.collectAsStateWithLifecycle()

    var provider by remember(persisted.aiProvider) { mutableStateOf(persisted.aiProvider) }
    var modelName by remember(persisted.modelName) { mutableStateOf(persisted.modelName) }
    var baseUrl by remember(persisted.baseUrl) { mutableStateOf(persisted.baseUrl) }
    var apiKey by remember(persisted.apiKey) { mutableStateOf(persisted.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }

    var temperature by remember(persisted.temperature) { mutableFloatStateOf(persisted.temperature) }
    var maxTokensText by remember(persisted.maxTokens) { mutableStateOf(persisted.maxTokens.toString()) }
    var systemPrompt by remember(persisted.systemPrompt) { mutableStateOf(persisted.systemPrompt) }

    var searchProvider by remember(persisted.searchProvider) { mutableStateOf(persisted.searchProvider) }
    var searchApiKey by remember(persisted.searchApiKey) { mutableStateOf(persisted.searchApiKey) }
    var showSearchKey by remember { mutableStateOf(false) }

    var githubToken by remember(persisted.githubToken) { mutableStateOf(persisted.githubToken) }
    var showGithubToken by remember { mutableStateOf(false) }
    var githubRepo by remember(persisted.githubRepo) { mutableStateOf(persisted.githubRepo) }
    var githubBaseBranch by remember(persisted.githubBaseBranch) { mutableStateOf(persisted.githubBaseBranch) }
    var autoPrOnRun by remember(persisted.autoCreateUpgradePrOnRun) { mutableStateOf(persisted.autoCreateUpgradePrOnRun) }

    var maxPagesText by remember(persisted.maxPagesPerRun) { mutableStateOf(persisted.maxPagesPerRun.toString()) }
    var crawlDepthText by remember(persisted.crawlDepth) { mutableStateOf(persisted.crawlDepth.toString()) }
    var dailyBudgetText by remember(persisted.dailyTokenBudget) { mutableStateOf(persisted.dailyTokenBudget.toString()) }

    LaunchedEffect(persisted) {
        provider = persisted.aiProvider
        modelName = persisted.modelName
        baseUrl = persisted.baseUrl
        apiKey = persisted.apiKey
        temperature = persisted.temperature
        maxTokensText = persisted.maxTokens.toString()
        systemPrompt = persisted.systemPrompt
        searchProvider = persisted.searchProvider
        searchApiKey = persisted.searchApiKey
        githubToken = persisted.githubToken
        githubRepo = persisted.githubRepo
        githubBaseBranch = persisted.githubBaseBranch
        autoPrOnRun = persisted.autoCreateUpgradePrOnRun
        maxPagesText = persisted.maxPagesPerRun.toString()
        crawlDepthText = persisted.crawlDepth.toString()
        dailyBudgetText = persisted.dailyTokenBudget.toString()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(end = 2.dp),
                        )
                        Text(
                            text = "Optional keys & GitHub token encrypted with AndroidKeyStore AES-256-GCM",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }

            savedMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(msg, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        TextButton(onClick = { viewModel.dismissSavedMessage() }) { Text("OK") }
                    }
                }
            }

            // 1. AI Provider Card
            SettingsSectionCard(title = "AI Provider & Model") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiProvider.entries.forEach { item ->
                        FilterChip(
                            selected = provider == item,
                            onClick = {
                                provider = item
                                baseUrl = item.defaultBaseUrl
                                modelName = item.defaultModel
                            },
                            label = { Text(item.displayName) },
                            modifier = Modifier.testTag("provider_chip_${item.name}"),
                        )
                    }
                }

                if (provider == AiProvider.AGENT_LAB_NATIVE) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth().testTag("native_ai_status_card"),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Built-in On-Device AI Active — No API Key Required",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = "Agent Lab Core trains neural token transitions locally in Room from your Sources, Knowledge Base, and Chats—with zero external LLM dependency.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("Model Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_model_input"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = when (provider) {
                        AiProvider.AGENT_LAB_NATIVE -> listOf("agent-lab-neural-v1")
                        AiProvider.OPENAI_COMPATIBLE -> listOf("llama3.2", "qwen2.5-coder", "mistral")
                        AiProvider.CUSTOM_API -> listOf("gpt-4o-mini", "gpt-4o", "deepseek-chat")
                    }
                    presets.forEach { preset ->
                        FilterChip(
                            selected = modelName == preset,
                            onClick = { modelName = preset },
                            label = { Text(preset) },
                        )
                    }
                }

                if (provider != AiProvider.AGENT_LAB_NATIVE) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Provider Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = {
                            Text(
                                if (provider.requiresApiKey) "${provider.displayName} API Key (Encrypted)"
                                else "${provider.displayName} Key (Optional, Encrypted)",
                            )
                        },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Toggle API key visibility",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("settings_api_key_input"),
                    )
                }

                Text(
                    text = "Temperature: ${"%.2f".format(temperature)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                )

                OutlinedTextField(
                    value = maxTokensText,
                    onValueChange = { maxTokensText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max Output Tokens") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("Editable System Prompt") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("settings_system_prompt_input"),
                )
            }

            // 2. Search Provider Card
            SettingsSectionCard(title = "Web Search Provider") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchProvider.entries.forEach { sp ->
                        FilterChip(
                            selected = searchProvider == sp,
                            onClick = { searchProvider = sp },
                            label = { Text(sp.displayName) },
                        )
                    }
                }
                if (searchProvider.requiresKey) {
                    OutlinedTextField(
                        value = searchApiKey,
                        onValueChange = { searchApiKey = it },
                        label = { Text("${searchProvider.displayName} Key (Encrypted)") },
                        singleLine = true,
                        visualTransformation = if (showSearchKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showSearchKey = !showSearchKey }) {
                                Icon(
                                    if (showSearchKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Toggle search key visibility",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 3. GitHub Self-Update Card
            SettingsSectionCard(title = "GitHub Source Code & Self-Upgrade Loop") {
                OutlinedTextField(
                    value = githubRepo,
                    onValueChange = { githubRepo = it },
                    label = { Text("GitHub Repository (owner/repo or URL)") },
                    placeholder = { Text("e.g. yourname/agent-lab") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_github_repo_input"),
                )
                OutlinedTextField(
                    value = githubBaseBranch,
                    onValueChange = { githubBaseBranch = it },
                    label = { Text("Base Branch (Protected — never pushed directly)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = githubToken,
                    onValueChange = { githubToken = it },
                    label = { Text("GitHub Personal Access Token (for PRs & CI)") },
                    singleLine = true,
                    visualTransformation = if (showGithubToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showGithubToken = !showGithubToken }) {
                            Icon(
                                if (showGithubToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "Toggle GitHub token visibility",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("settings_github_token_input"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Auto-open self-upgrade PR during /run or RUN",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = autoPrOnRun,
                        onCheckedChange = { autoPrOnRun = it },
                        modifier = Modifier.testTag("settings_auto_pr_switch"),
                    )
                }
            }

            // 4. Autonomous Limits & Budget Card
            SettingsSectionCard(title = "Autonomous Limits & Daily Token Budget") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = maxPagesText,
                        onValueChange = { maxPagesText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Max Pages / Run") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = crawlDepthText,
                        onValueChange = { crawlDepthText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Link Depth (0-3)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = dailyBudgetText,
                    onValueChange = { dailyBudgetText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Daily Token Budget") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val budgetVal = dailyBudgetText.toIntOrNull()?.coerceAtLeast(1) ?: persisted.dailyTokenBudget
                val pct = (persisted.tokensUsedToday.toFloat() / budgetVal.toFloat()).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Used Today: ${persisted.tokensUsedToday} / $budgetVal tokens",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { viewModel.resetDailyTokenCounter() }) {
                            Text("Reset")
                        }
                    }
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Button(
                onClick = {
                    val updated = AgentSettings(
                        aiProvider = provider,
                        modelName = modelName.trim().ifBlank { provider.defaultModel },
                        baseUrl = baseUrl.trim().ifBlank { provider.defaultBaseUrl },
                        apiKey = apiKey.trim(),
                        temperature = temperature,
                        maxTokens = maxTokensText.toIntOrNull()?.coerceIn(128, 32768) ?: 4096,
                        systemPrompt = systemPrompt.trim().ifBlank { AgentSettings.DEFAULT_SYSTEM_PROMPT },
                        searchProvider = searchProvider,
                        searchApiKey = searchApiKey.trim(),
                        githubToken = githubToken.trim(),
                        githubRepo = githubRepo.trim(),
                        githubBaseBranch = githubBaseBranch.trim().ifBlank { "main" },
                        maxPagesPerRun = maxPagesText.toIntOrNull()?.coerceIn(1, 50) ?: 8,
                        crawlDepth = crawlDepthText.toIntOrNull()?.coerceIn(0, 3) ?: 1,
                        dailyTokenBudget = dailyBudgetText.toIntOrNull()?.coerceIn(1000, 10_000_000) ?: 250_000,
                        tokensUsedToday = persisted.tokensUsedToday,
                        tokenBudgetDate = persisted.tokenBudgetDate,
                        autoApproveImprovements = persisted.autoApproveImprovements,
                        autoCreateUpgradePrOnRun = autoPrOnRun,
                    )
                    viewModel.saveAll(updated)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_settings_button"),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save Encrypted Settings")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}
