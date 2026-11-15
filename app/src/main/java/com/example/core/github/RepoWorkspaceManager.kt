package com.example.core.github

import android.content.Context
import android.util.Base64
import com.example.core.ai.AgentLabBrain
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

data class WorkspaceSummary(
    val repoSlug: String = "",
    val branch: String = "main",
    val syncedFiles: Int = 0,
    val totalLines: Int = 0,
    val lastSyncedAt: Long? = null,
)

data class GrepMatch(
    val relativePath: String,
    val lineNumber: Int,
    val lineText: String,
)

/**
 * Sandboxed local GitHub repository workspace ("Termux home directory" for Agent Lab).
 * Syncs repository source files into `filesDir/workspace/<owner>_<repo>/` so the AI can
 * inspect, grep, train its neural weights on its own source code, and prepare self-upgrades.
 */
class RepoWorkspaceManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val nativeBrain: AgentLabBrain,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val workspaceRoot: File = File(context.filesDir, "workspace").apply { mkdirs() }
    private val skillsRoot: File = File(context.filesDir, "skills").apply { mkdirs() }

    private val _summary = MutableStateFlow(WorkspaceSummary())
    val summary: StateFlow<WorkspaceSummary> = _summary.asStateFlow()

    companion object {
        private val CODE_EXTENSIONS = setOf(
            "kt", "kts", "java", "xml", "md", "js", "yml", "yaml", "json", "pro", "properties", "txt",
        )

        fun normalizeRepoSlug(raw: String): String? {
            val cleaned = raw.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removeSuffix(".git")
                .trim('/')
            val parts = cleaned.split("/").filter { it.isNotBlank() }
            return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else null
        }
    }

    suspend fun refreshLocalSummary() = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        val slug = normalizeRepoSlug(settings.githubRepo).orEmpty()
        val repoDir = if (slug.isNotBlank()) repoDirFor(slug) else null
        if (repoDir == null || !repoDir.exists()) {
            _summary.value = WorkspaceSummary(repoSlug = slug, branch = settings.githubBaseBranch)
            return@withContext
        }
        val files = repoDir.walkTopDown().filter { it.isFile }.toList()
        val lines = files.sumOf { runCatching { it.readLines().size }.getOrDefault(0) }
        _summary.value = WorkspaceSummary(
            repoSlug = slug,
            branch = settings.githubBaseBranch,
            syncedFiles = files.size,
            totalLines = lines,
            lastSyncedAt = _summary.value.lastSyncedAt ?: repoDir.lastModified(),
        )
    }

    /**
     * Syncs source code files from GitHub into local workspace and trains the neural core on them.
     * Works with public repos even without a token; uses encrypted PAT when configured.
     */
    suspend fun syncRepositoryAndTrain(
        explicitRepoSlug: String? = null,
        maxFiles: Int = 36,
        onLog: (suspend (String) -> Unit)? = null,
    ): WorkspaceSummary = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        val slug = normalizeRepoSlug(explicitRepoSlug ?: settings.githubRepo)
            ?: throw IllegalArgumentException("Invalid or missing GitHub repository. Use format owner/repo or https://github.com/owner/repo")

        if (explicitRepoSlug != null && slug != settings.githubRepo) {
            settingsRepository.updateGitHubRepo(slug)
        }

        val (owner, repo) = slug.split("/")
        val branch = settings.githubBaseBranch.trim().ifBlank { "main" }
        val token = settings.githubToken.trim()

        onLog?.invoke("Syncing source code from github.com/$slug ($branch)...")
        val allPaths = fetchTreePaths(owner, repo, branch, token)
        val sourcePaths = allPaths
            .filter { path ->
                val ext = path.substringAfterLast('.', "").lowercase()
                ext in CODE_EXTENSIONS &&
                    !path.startsWith("gradle/wrapper/") &&
                    !path.contains("/build/")
            }
            .sortedWith(compareBy<String> { priorityRank(it) }.thenBy { it.length })
            .take(maxFiles)

        val targetDir = repoDirFor(slug).apply { mkdirs() }
        var totalLines = 0
        var syncedCount = 0
        val trainingCorpus = StringBuilder()

        for (relPath in sourcePaths) {
            val content = fetchFileContent(owner, repo, relPath, branch, token) ?: continue
            val localFile = File(targetDir, relPath)
            localFile.parentFile?.mkdirs()
            localFile.writeText(content)
            totalLines += content.lines().size
            syncedCount++
            if (trainingCorpus.length < 28_000) {
                trainingCorpus.appendLine("// FILE: $relPath")
                trainingCorpus.appendLine(content.take(2500))
                trainingCorpus.appendLine()
            }
        }

        onLog?.invoke("Synced $syncedCount files ($totalLines lines). Training Neural Core on codebase...")
        if (trainingCorpus.isNotBlank()) {
            nativeBrain.trainNeuralEpochs(
                corpus = trainingCorpus.toString(),
                epochs = 2,
                sourceLabel = "repo:$slug",
            )
            nativeBrain.trainOnText(trainingCorpus.toString().take(8000), weightBoost = 1.2f, sourceLabel = "repo:$slug", neuralEpochs = 0)
        }

        val result = WorkspaceSummary(
            repoSlug = slug,
            branch = branch,
            syncedFiles = syncedCount,
            totalLines = totalLines,
            lastSyncedAt = System.currentTimeMillis(),
        )
        _summary.value = result
        result
    }

    suspend fun listFiles(relPath: String = ""): List<String> = withContext(Dispatchers.IO) {
        val activeDir = activeWorkspaceDir()
        val clean = relPath.trim().trimStart('/')
        if (clean == "skills" || clean.startsWith("skills/")) {
            return@withContext skillsRoot.listFiles()
                ?.sortedBy { it.name }
                ?.map { "skills/${it.name} (${it.length()}B)" }
                .orEmpty()
        }
        val base = if (clean.isBlank()) activeDir else File(activeDir, clean)
        if (!base.exists() || !isInsideSafeRoot(base)) return@withContext emptyList()

        if (base.isFile) {
            return@withContext listOf("${base.relativeTo(activeDir).path} (${base.length()}B)")
        }

        val direct = base.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })).orEmpty()
        val entries = direct.map { f ->
            if (f.isDirectory) "${f.name}/" else "${f.name} (${f.length()}B)"
        }.toMutableList()
        if (clean.isBlank()) {
            val skillCount = skillsRoot.listFiles()?.size ?: 0
            entries.add(0, "skills/ ($skillCount QuickJS files)")
        }
        entries
    }

    suspend fun readFile(relPath: String, maxLines: Int = 120): String? = withContext(Dispatchers.IO) {
        val clean = relPath.trim().trimStart('/')
        if (clean.startsWith("skills/")) {
            val target = File(skillsRoot, clean.removePrefix("skills/"))
            if (!target.exists() || !target.isFile || !target.canonicalPath.startsWith(skillsRoot.canonicalPath)) return@withContext null
            return@withContext target.readLines().take(maxLines).joinToString("\n")
        }
        val activeDir = activeWorkspaceDir()
        val target = File(activeDir, clean)
        if (!target.exists() || !target.isFile || !target.canonicalPath.startsWith(activeDir.canonicalPath)) return@withContext null
        target.readLines().take(maxLines).joinToString("\n")
    }

    suspend fun grep(pattern: String, maxMatches: Int = 25): List<GrepMatch> = withContext(Dispatchers.IO) {
        val needle = pattern.trim()
        if (needle.isBlank()) return@withContext emptyList()
        val matches = mutableListOf<GrepMatch>()
        val activeDir = activeWorkspaceDir()
        val roots = listOf(activeDir, skillsRoot)
        for (root in roots) {
            if (!root.exists()) continue
            val prefix = if (root == skillsRoot) "skills/" else ""
            for (file in root.walkTopDown().filter { it.isFile }) {
                val rel = prefix + file.relativeTo(root).path
                val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
                lines.forEachIndexed { idx, line ->
                    if (line.contains(needle, ignoreCase = true)) {
                        matches.add(GrepMatch(rel, idx + 1, line.trim().take(120)))
                        if (matches.size >= maxMatches) return@withContext matches
                    }
                }
            }
        }
        matches
    }

    suspend fun getAllWorkspaceFiles(): Map<String, String> = withContext(Dispatchers.IO) {
        val activeDir = activeWorkspaceDir()
        if (!activeDir.exists()) return@withContext emptyMap()
        val map = LinkedHashMap<String, String>()
        activeDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(activeDir).path
            map[rel] = runCatching { f.readText() }.getOrDefault("")
        }
        map
    }

    private suspend fun activeWorkspaceDir(): File {
        val slug = _summary.value.repoSlug.ifBlank {
            normalizeRepoSlug(settingsRepository.getSnapshot().githubRepo).orEmpty()
        }
        return if (slug.isNotBlank()) repoDirFor(slug).apply { mkdirs() } else workspaceRoot
    }

    private fun repoDirFor(slug: String): File =
        File(workspaceRoot, slug.replace("/", "_"))

    private fun isInsideSafeRoot(file: File): Boolean {
        val cp = file.canonicalPath
        return cp.startsWith(workspaceRoot.canonicalPath) || cp.startsWith(skillsRoot.canonicalPath)
    }

    private fun priorityRank(path: String): Int = when {
        path.endsWith("AgentLabBrain.kt") || path.endsWith("TrainableNeuralNet.kt") -> 0
        path.endsWith("AutonomousAgentEngine.kt") || path.endsWith("GitHubSelfUpdater.kt") -> 1
        path.endsWith("MainActivity.kt") || path.endsWith("ChatViewModel.kt") -> 2
        path.endsWith(".kt") -> 3
        path == "README.md" || path.endsWith(".kts") -> 4
        else -> 5
    }

    private fun fetchTreePaths(owner: String, repo: String, branch: String, token: String): List<String> {
        val req = buildGitHubGet("https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1", token)
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("GitHub tree fetch failed (${resp.code}): ${body.take(240)}")
            }
            val obj = json.parseToJsonElement(body).jsonObject
            return obj["tree"]?.jsonArray?.mapNotNull { item ->
                val o = item.jsonObject
                if (o["type"]?.jsonPrimitive?.contentOrNull == "blob") {
                    o["path"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }.orEmpty()
        }
    }

    private fun fetchFileContent(owner: String, repo: String, path: String, branch: String, token: String): String? {
        val req = buildGitHubGet("https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch", token)
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            val encoded = obj["content"]?.jsonPrimitive?.contentOrNull?.replace("\n", "") ?: return@use null
            runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        }
    }

    private fun buildGitHubGet(url: String, token: String): Request {
        return Request.Builder()
            .url(url)
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "AgentLab-Android")
            .get()
            .build()
    }
}
