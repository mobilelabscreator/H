package com.example.core.terminal

import com.example.core.agent.AutonomousAgentEngine
import com.example.core.ai.AgentLabBrain
import com.example.core.github.GitHubSelfUpdater
import com.example.core.github.RepoWorkspaceManager
import com.example.core.skills.SkillSandboxRunner
import com.example.data.db.AgentLogDao
import com.example.data.db.AgentLogEntity
import com.example.data.db.KnowledgeDao
import com.example.data.db.SkillDao
import com.example.data.settings.SettingsRepository

sealed interface TerminalCommandResult {
    data class Output(val markdown: String) : TerminalCommandResult
    data object ClearChat : TerminalCommandResult
    data object NotACommand : TerminalCommandResult
}

/**
 * Termux-style command shell inside Agent Lab.
 * Allows running `/run`, `/train`, `/repo`, `/sync`, `/upgrade`, `/ls`, `/cat`, `/grep`,
 * `/skill`, `/status`, and `/help` directly from the Chat/Terminal interface.
 */
class AgentTerminalProcessor(
    private val settingsRepository: SettingsRepository,
    private val nativeBrain: AgentLabBrain,
    private val workspaceManager: RepoWorkspaceManager,
    private val agentEngine: AutonomousAgentEngine,
    private val gitHubSelfUpdater: GitHubSelfUpdater,
    private val skillDao: SkillDao,
    private val skillSandboxRunner: SkillSandboxRunner,
    private val knowledgeDao: KnowledgeDao,
    private val agentLogDao: AgentLogDao,
) {
    suspend fun executeIfCommand(rawInput: String): TerminalCommandResult {
        val trimmed = rawInput.trim()
        if (!trimmed.startsWith("/")) return TerminalCommandResult.NotACommand

        val spaceIdx = trimmed.indexOfFirst { it.isWhitespace() }
        val cmd = (if (spaceIdx < 0) trimmed else trimmed.substring(0, spaceIdx)).lowercase()
        val arg = if (spaceIdx < 0) "" else trimmed.substring(spaceIdx + 1).trim()

        return when (cmd) {
            "/help" -> TerminalCommandResult.Output(helpText())
            "/clear" -> TerminalCommandResult.ClearChat
            "/status" -> TerminalCommandResult.Output(buildStatusText())
            "/run" -> {
                agentEngine.startRun(arg.ifBlank { null })
                val settings = settingsRepository.getSnapshot()
                val repoMsg = if (settings.githubRepo.isNotBlank()) {
                    "Linked GitHub repo: `${settings.githubRepo}`" +
                        if (settings.githubToken.isNotBlank()) " (self-upgrade PR enabled)" else " (read-only; add PAT in Settings to push PRs)"
                } else {
                    "No GitHub repo attached yet — attach one anytime with `/repo owner/repo`."
                }
                TerminalCommandResult.Output(
                    buildString {
                        appendLine("### Autonomous `/run` Started")
                        if (arg.isNotBlank()) appendLine("- **Upgrade Goal:** $arg")
                        appendLine("- **Pipeline:** `SYNC` -> `RESEARCH` -> `TRAIN` -> `EVALUATE` -> `IMPROVE` -> `UPGRADE_PR`")
                        appendLine("- **Repository:** $repoMsg")
                        appendLine()
                        appendLine("Open the **Agent** tab to watch live epoch loss, diffs, and GitHub Actions APK builds.")
                    }.trim(),
                )
            }
            "/train" -> {
                val epochs = arg.toIntOrNull()?.coerceIn(1, 8) ?: 2
                val summary = agentEngine.trainNow(epochs)
                val stats = nativeBrain.getNeuralStats()
                TerminalCommandResult.Output(
                    buildString {
                        appendLine("### Neural Core Training Complete")
                        appendLine("```")
                        appendLine(summary)
                        appendLine("epochs=${stats.totalEpochs}  steps=${stats.totalSteps}  vocab=${stats.vocabSize}  params=${stats.parameterCount}  bestLoss=${"%.4f".format(stats.bestLoss)}")
                        appendLine("```")
                    }.trim(),
                )
            }
            "/repo" -> {
                if (arg.isBlank()) {
                    return TerminalCommandResult.Output("Usage: `/repo owner/repo` or `/repo https://github.com/owner/repo`")
                }
                val slug = RepoWorkspaceManager.normalizeRepoSlug(arg)
                    ?: return TerminalCommandResult.Output("Invalid GitHub repository format. Example: `/repo octocat/hello-world`")
                val ws = runCatching { workspaceManager.syncRepositoryAndTrain(slug) }.getOrElse { err ->
                    return TerminalCommandResult.Output("Repo attached (`$slug`), but sync hit an error: `${err.message}`")
                }
                val stats = nativeBrain.getNeuralStats()
                TerminalCommandResult.Output(
                    buildString {
                        appendLine("### GitHub Source Code Synced & Trained")
                        appendLine("- **Repository:** `${ws.repoSlug}` (`${ws.branch}`)")
                        appendLine("- **Files Synced:** ${ws.syncedFiles} files (${ws.totalLines} lines) into local `workspace/`")
                        appendLine("- **Neural Core:** ${stats.parameterCount} params, ${stats.vocabSize} vocab tokens, epoch ${stats.totalEpochs}, loss `${"%.4f".format(stats.lastLoss)}`")
                        appendLine()
                        appendLine("Try `/ls`, `/grep TrainableNeuralNet`, or `/run`!")
                    }.trim(),
                )
            }
            "/sync" -> {
                val ws = runCatching { workspaceManager.syncRepositoryAndTrain() }.getOrElse { err ->
                    return TerminalCommandResult.Output("Sync failed: `${err.message}`. Set a repo first with `/repo owner/repo`.")
                }
                TerminalCommandResult.Output("Synced **${ws.syncedFiles}** files (${ws.totalLines} lines) from `${ws.repoSlug}` and ran 2 neural training epochs.")
            }
            "/upgrade" -> {
                if (arg.isBlank()) {
                    return TerminalCommandResult.Output("Usage: `/upgrade <describe code upgrade>` — e.g. `/upgrade add cosine warmup to trainer`")
                }
                val pr = runCatching {
                    gitHubSelfUpdater.proposeAndMonitorSelfImprovement(arg) { phase, msg ->
                        agentLogDao.insert(AgentLogEntity(runId = "SHELL", phase = phase, message = msg))
                    }
                }.getOrElse { err ->
                    return TerminalCommandResult.Output("Self-upgrade PR failed: `${err.message}`")
                }
                TerminalCommandResult.Output(
                    buildString {
                        appendLine("### Self-Upgrade Pull Request #${pr.prNumber}")
                        appendLine("- **Branch:** `${pr.branchName}` (safe — never pushed to main)")
                        appendLine("- **PR:** ${pr.prUrl}")
                        appendLine("- **CI Status:** `${pr.buildStatus}` (${pr.buildConclusion ?: "running"})")
                    }.trim(),
                )
            }
            "/ls" -> {
                val files = workspaceManager.listFiles(arg)
                if (files.isEmpty()) {
                    TerminalCommandResult.Output("Workspace is empty. Attach a GitHub repo with `/repo owner/repo`.")
                } else {
                    TerminalCommandResult.Output(
                        buildString {
                            appendLine("### `/workspace/${arg.trimStart('/')}`")
                            appendLine("```")
                            files.forEach { appendLine(it) }
                            appendLine("```")
                        }.trim(),
                    )
                }
            }
            "/cat" -> {
                if (arg.isBlank()) return TerminalCommandResult.Output("Usage: `/cat <relative/path>` (e.g. `/cat README.md`)")
                val content = workspaceManager.readFile(arg, maxLines = 100)
                    ?: return TerminalCommandResult.Output("File not found in workspace: `$arg`. Run `/ls` to inspect files.")
                val ext = arg.substringAfterLast('.', "txt")
                TerminalCommandResult.Output("```$ext\n$content\n```")
            }
            "/grep" -> {
                if (arg.isBlank()) return TerminalCommandResult.Output("Usage: `/grep <pattern>`")
                val matches = workspaceManager.grep(arg)
                if (matches.isEmpty()) {
                    TerminalCommandResult.Output("No matches found for `$arg` across workspace & skills.")
                } else {
                    TerminalCommandResult.Output(
                        buildString {
                            appendLine("### Matches for `$arg` (${matches.size})")
                            appendLine("```")
                            matches.forEach { m -> appendLine("${m.relativePath}:${m.lineNumber}: ${m.lineText}") }
                            appendLine("```")
                        }.trim(),
                    )
                }
            }
            "/skill" -> {
                if (arg.isBlank()) {
                    val all = skillDao.getAll()
                    val list = if (all.isEmpty()) "No skills found." else all.joinToString("\n") { "- `${it.slug}` — ${it.description}" }
                    return TerminalCommandResult.Output("Usage: `/skill <slug> [json_input]`\n\n$list")
                }
                val parts = arg.split(Regex("\\s+"), limit = 2)
                val slug = parts[0]
                val inputJson = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "{}"
                val skill = skillDao.getBySlug(slug)
                    ?: return TerminalCommandResult.Output("Skill `$slug` not found. Run `/skill` to list available skills.")
                val startMs = System.currentTimeMillis()
                val res = skillSandboxRunner.runSkill(skill, inputJson)
                val durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                TerminalCommandResult.Output(
                    buildString {
                        appendLine("### QuickJS Skill `${skill.slug}.js` (${durationMs}ms)")
                        if (res.logs.isNotEmpty()) {
                            appendLine("```")
                            res.logs.forEach { line -> appendLine(line) }
                            appendLine("```")
                        }
                        if (res.success) {
                            appendLine("```json\n${res.output}\n```")
                        } else {
                            appendLine("Error: `${res.error}`")
                        }
                    }.trim(),
                )
            }
            else -> TerminalCommandResult.Output("Unknown command `$cmd`. Type `/help` to list all terminal commands.")
        }
    }

    private suspend fun buildStatusText(): String {
        val stats = nativeBrain.getNeuralStats()
        val settings = settingsRepository.getSnapshot()
        workspaceManager.refreshLocalSummary()
        val ws = workspaceManager.summary.value
        val kbCount = knowledgeDao.getAll().size
        val skillCount = skillDao.getAll().size
        val runState = agentEngine.runState.value

        return buildString {
            appendLine("### Agent Lab Termux & Neural Status")
            appendLine("```")
            appendLine("AI Engine      : ${settings.aiProvider.displayName}")
            appendLine("Parameters     : ${stats.parameterCount} (vocab=${stats.vocabSize})")
            appendLine("Training       : epochs=${stats.totalEpochs}, steps=${stats.totalSteps}, tokens=${stats.trainedTokens}")
            appendLine("Loss / PPL     : last=${"%.4f".format(stats.lastLoss)} (best=${"%.4f".format(stats.bestLoss)}), ppl=${"%.2f".format(stats.lastPerplexity)}")
            appendLine("Knowledge Base : $kbCount entries")
            appendLine("QuickJS Skills : $skillCount live .js files")
            appendLine("GitHub Repo    : ${ws.repoSlug.ifBlank { "<not linked>" }} (${ws.syncedFiles} synced files, ${ws.totalLines} lines)")
            appendLine("Agent Loop     : ${runState.currentPhase} (running=${runState.isRunning})")
            appendLine("```")
        }.trim()
    }

    private fun helpText(): String = """
        ### Agent Lab Terminal Commands
        - `/run [goal]` — Run the full autonomous cycle (Sync Repo -> Research -> Train -> Eval -> Improve -> Self-Upgrade PR)
        - `/train [epochs]` — Run real backpropagation epochs on Knowledge Base & synced repo code
        - `/repo <owner/repo>` — Attach & clone a GitHub source repo into the AI's workspace and train on it
        - `/sync` — Pull latest source files from the attached GitHub repo and retrain
        - `/upgrade <task>` — Have the AI edit its source repo on a new branch, open a PR, and watch Actions build the APK
        - `/ls [path]` — List files inside the AI's local Termux `workspace/` & `skills/`
        - `/cat <path>` — Read a file from the AI's local workspace
        - `/grep <pattern>` — Search code across the synced workspace & skills
        - `/skill <slug> [json]` — Execute a sandboxed QuickJS `.js` skill
        - `/status` — Show live Neural Core, repo workspace, and agent metrics
        - `/clear` — Clear the current terminal/chat buffer
    """.trimIndent()
}
