package com.example.core.github

import android.util.Base64
import com.example.core.ai.LlmClient
import com.example.data.db.GitHubPrDao
import com.example.data.db.GitHubPrEntity
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class GitHubFileEdit(
    val path: String,
    val content: String,
    val message: String,
)

class GitHubSelfUpdater(
    private val httpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val llmClient: LlmClient,
    private val gitHubPrDao: GitHubPrDao,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val prsFlow: Flow<List<GitHubPrEntity>> = gitHubPrDao.observeAll()

    suspend fun proposeAndMonitorSelfImprovement(
        taskDescription: String,
        onLog: suspend (String, String) -> Unit,
    ): GitHubPrEntity = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        val token = settings.githubToken.trim()
        val repoSlug = settings.githubRepo.trim()
        val baseBranch = settings.githubBaseBranch.trim().ifBlank { "main" }

        require(token.isNotBlank()) { "GitHub Personal Access Token is required in Settings." }
        require(repoSlug.contains("/") && repoSlug.split("/").size == 2) {
            "GitHub Repo in Settings must be formatted as owner/repo."
        }
        val (owner, repo) = repoSlug.split("/")

        onLog("GITHUB", "Inspecting repository $owner/$repo ($baseBranch)...")
        val baseSha = getBranchHeadSha(owner, repo, baseBranch, token)
        val treePaths = listRepoSourceFiles(owner, repo, baseBranch, token)
        onLog("GITHUB", "Indexed ${treePaths.size} source files on $baseBranch.")

        // Ask LLM which files to inspect (max 4)
        val pickPrompt = """
            Task to implement in the Android app repository ($owner/$repo):
            "$taskDescription"

            Available repository files:
            ${treePaths.take(120).joinToString("\n")}

            Reply ONLY with a JSON array of up to 4 relative file paths to read and modify, e.g.:
            ["app/src/main/java/com/example/MainActivity.kt"]
        """.trimIndent()
        val pickedRaw = llmClient.complete(pickPrompt, "Respond only with a valid JSON array of file paths.").text
        val targetPaths = extractJsonStringList(pickedRaw)
            .filter { it in treePaths || it.startsWith("app/src/") || it == "README.md" }
            .distinct()
            .take(4)
            .ifEmpty { treePaths.filter { it.endsWith(".kt") }.take(2) }

        val existingFiles = mutableMapOf<String, Pair<String, String?>>() // path -> (content, sha)
        for (path in targetPaths) {
            val fetched = getFileContentAndSha(owner, repo, path, baseBranch, token)
            if (fetched != null) {
                existingFiles[path] = fetched
            }
        }

        val branchSlug = taskDescription.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(28)
            .ifBlank { "update" }
        val newBranch = "agent-lab/${System.currentTimeMillis() / 1000}-$branchSlug"
        require(newBranch != baseBranch) { "Safety violation: never push directly to $baseBranch." }

        onLog("GITHUB", "Creating feature branch $newBranch from $baseBranch (${baseSha.take(7)})...")
        createBranch(owner, repo, newBranch, baseSha, token)

        val edits = generateCodeEdits(taskDescription, existingFiles, buildErrorLog = null)
        require(edits.isNotEmpty()) { "LLM did not produce any file modifications." }

        var lastCommitSha = baseSha
        for (edit in edits) {
            val currentSha = getFileContentAndSha(owner, repo, edit.path, newBranch, token)?.second
            lastCommitSha = commitFile(
                owner = owner,
                repo = repo,
                path = edit.path,
                branch = newBranch,
                content = edit.content,
                message = edit.message,
                existingSha = currentSha,
                token = token,
            )
            onLog("GITHUB", "Committed ${edit.path} to $newBranch (${lastCommitSha.take(7)}).")
        }

        val prTitle = "Agent Lab: ${taskDescription.take(64)}"
        val prBody = buildString {
            appendLine("## Autonomous Self-Improvement PR")
            appendLine("**Task:** $taskDescription")
            appendLine()
            appendLine("### Modified Files")
            edits.forEach { appendLine("- `${it.path}` — ${it.message}") }
            appendLine()
            appendLine("_Opened automatically by Agent Lab. Never pushed to `$baseBranch`._")
        }
        val (prNumber, prUrl) = createPullRequest(owner, repo, prTitle, prBody, newBranch, baseBranch, token)
        onLog("GITHUB", "Opened Pull Request #$prNumber: $prUrl")

        var prEntity = GitHubPrEntity(
            taskDescription = taskDescription,
            branchName = newBranch,
            prNumber = prNumber,
            prUrl = prUrl,
            commitSha = lastCommitSha,
            buildStatus = "queued",
            attempts = 1,
        )
        val rowId = gitHubPrDao.insert(prEntity)
        prEntity = prEntity.copy(id = rowId)

        // Monitor GitHub Actions workflow & auto-fix build failures up to 3 attempts total
        prEntity = monitorAndAutoFixLoop(owner, repo, prEntity, existingFiles, token, onLog)
        prEntity
    }

    suspend fun refreshPrStatus(pr: GitHubPrEntity): GitHubPrEntity = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        val token = settings.githubToken.trim()
        val repoSlug = settings.githubRepo.trim()
        if (token.isBlank() || !repoSlug.contains("/")) return@withContext pr
        val (owner, repo) = repoSlug.split("/")

        val run = getLatestWorkflowRun(owner, repo, pr.branchName, token) ?: return@withContext pr
        val status = run["status"]?.jsonPrimitive?.contentOrNull ?: pr.buildStatus
        val conclusion = run["conclusion"]?.jsonPrimitive?.contentOrNull
        val runId = run["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val runHtml = run["html_url"]?.jsonPrimitive?.contentOrNull ?: pr.runHtmlUrl
        val artifactUrl = if (conclusion == "success" && runId != null) {
            getArtifactDownloadPage(owner, repo, runId, token) ?: runHtml
        } else {
            pr.artifactUrl
        }
        val updated = pr.copy(
            buildStatus = status,
            buildConclusion = conclusion,
            runHtmlUrl = runHtml,
            artifactUrl = artifactUrl,
            updatedAt = System.currentTimeMillis(),
        )
        gitHubPrDao.update(updated)
        updated
    }

    private suspend fun monitorAndAutoFixLoop(
        owner: String,
        repo: String,
        initialPr: GitHubPrEntity,
        contextFiles: MutableMap<String, Pair<String, String?>>,
        token: String,
        onLog: suspend (String, String) -> Unit,
    ): GitHubPrEntity {
        var pr = initialPr
        val maxAttempts = 3

        while (pr.attempts <= maxAttempts) {
            onLog("GITHUB", "Waiting for GitHub Actions build (attempt ${pr.attempts}/$maxAttempts)...")
            var latestRun: JsonObject? = null
            for (poll in 1..24) {
                delay(10_000L)
                latestRun = getLatestWorkflowRun(owner, repo, pr.branchName, token)
                if (latestRun != null) {
                    val status = latestRun["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val conclusion = latestRun["conclusion"]?.jsonPrimitive?.contentOrNull
                    val htmlUrl = latestRun["html_url"]?.jsonPrimitive?.contentOrNull
                    pr = pr.copy(
                        buildStatus = status,
                        buildConclusion = conclusion,
                        runHtmlUrl = htmlUrl,
                        updatedAt = System.currentTimeMillis(),
                    )
                    gitHubPrDao.update(pr)
                    if (status == "completed") break
                }
            }

            if (latestRun == null) {
                onLog("GITHUB", "Build queued/pending on GitHub Actions. Check status in PR list.")
                return pr
            }

            val conclusion = latestRun["conclusion"]?.jsonPrimitive?.contentOrNull
            val runId = latestRun["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val htmlUrl = latestRun["html_url"]?.jsonPrimitive?.contentOrNull

            if (conclusion == "success") {
                val artifactUrl = if (runId != null) {
                    getArtifactDownloadPage(owner, repo, runId, token) ?: htmlUrl
                } else {
                    htmlUrl
                }
                pr = pr.copy(
                    buildStatus = "completed",
                    buildConclusion = "success",
                    artifactUrl = artifactUrl,
                    runHtmlUrl = htmlUrl,
                    lastError = null,
                    updatedAt = System.currentTimeMillis(),
                )
                gitHubPrDao.update(pr)
                onLog("GITHUB", "Build SUCCEEDED! APK artifact ready on GitHub Actions.")
                return pr
            }

            // Build failed -> fetch logs and attempt fix if attempts < maxAttempts
            val errorLog = if (runId != null) {
                fetchFailedJobLogs(owner, repo, runId, token)
            } else {
                "GitHub Actions build concluded with: $conclusion"
            }

            if (pr.attempts >= maxAttempts) {
                pr = pr.copy(
                    buildStatus = "completed",
                    buildConclusion = conclusion ?: "failure",
                    lastError = errorLog.take(1500),
                    updatedAt = System.currentTimeMillis(),
                )
                gitHubPrDao.update(pr)
                onLog("GITHUB", "Build failed after $maxAttempts attempts. Error saved on PR #${pr.prNumber}.")
                return pr
            }

            onLog("GITHUB", "Build failed ($conclusion). Reading Actions logs and generating fix (attempt ${pr.attempts + 1}/$maxAttempts)...")
            // Refresh current branch file contents
            for (path in contextFiles.keys.toList()) {
                getFileContentAndSha(owner, repo, path, pr.branchName, token)?.let {
                    contextFiles[path] = it
                }
            }
            val fixEdits = generateCodeEdits(pr.taskDescription, contextFiles, buildErrorLog = errorLog)
            if (fixEdits.isEmpty()) {
                pr = pr.copy(lastError = "Unable to generate automated fix from build log.", updatedAt = System.currentTimeMillis())
                gitHubPrDao.update(pr)
                return pr
            }

            var fixSha = pr.commitSha
            for (edit in fixEdits) {
                val existingSha = getFileContentAndSha(owner, repo, edit.path, pr.branchName, token)?.second
                fixSha = commitFile(
                    owner = owner,
                    repo = repo,
                    path = edit.path,
                    branch = pr.branchName,
                    content = edit.content,
                    message = "fix(ci): ${edit.message}",
                    existingSha = existingSha,
                    token = token,
                )
                contextFiles[edit.path] = edit.content to existingSha
                onLog("GITHUB", "Pushed CI fix commit for ${edit.path} (${fixSha.take(7)}).")
            }

            pr = pr.copy(
                commitSha = fixSha,
                buildStatus = "queued",
                buildConclusion = null,
                attempts = pr.attempts + 1,
                lastError = errorLog.take(800),
                updatedAt = System.currentTimeMillis(),
            )
            gitHubPrDao.update(pr)
        }
        return pr
    }

    private suspend fun generateCodeEdits(
        taskDescription: String,
        files: Map<String, Pair<String, String?>>,
        buildErrorLog: String?,
    ): List<GitHubFileEdit> {
        val filesSection = files.entries.joinToString("\n\n") { (path, pair) ->
            "=== FILE: $path ===\n${pair.first.take(12_000)}"
        }
        val errorSection = if (!buildErrorLog.isNullOrBlank()) {
            "\n\nPREVIOUS GITHUB ACTIONS BUILD FAILED WITH:\n${buildErrorLog.take(4000)}\nFix every compiler/Gradle error."
        } else {
            ""
        }
        val prompt = """
            You are editing the Agent Lab Android Kotlin + Jetpack Compose project.
            Task: $taskDescription
            $errorSection

            Current source files:
            $filesSection

            Return ONLY valid JSON (no markdown fences if possible) matching this schema:
            {
              "edits": [
                {
                  "path": "relative/path/to/File.kt",
                  "message": "Short commit message",
                  "content": "COMPLETE updated file content"
                }
              ]
            }
            Ensure complete, compilable code with zero placeholders or TODOs.
        """.trimIndent()

        val raw = llmClient.complete(prompt, "You are a senior Android Kotlin engineer.").text
        val cleanedJson = extractJsonObjectString(raw) ?: return emptyList()
        return runCatching {
            val obj = json.parseToJsonElement(cleanedJson).jsonObject
            obj["edits"]?.jsonArray?.mapNotNull { item ->
                val e = item.jsonObject
                val path = e["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val content = e["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val message = e["message"]?.jsonPrimitive?.contentOrNull?.ifBlank { "Update $path" } ?: "Update $path"
                if (path.isNotBlank() && content.isNotBlank()) GitHubFileEdit(path, content, message) else null
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun gitHubRequest(url: String, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "AgentLab-Android")

    private fun getBranchHeadSha(owner: String, repo: String, branch: String, token: String): String {
        val req = gitHubRequest("https://api.github.com/repos/$owner/$repo/git/ref/heads/$branch", token).get().build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("GitHub branch lookup failed (${resp.code}): ${body.take(300)}")
            val obj = json.parseToJsonElement(body).jsonObject
            return obj["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.contentOrNull
                ?: throw IOException("Missing SHA for branch $branch")
        }
    }

    private fun listRepoSourceFiles(owner: String, repo: String, branch: String, token: String): List<String> {
        val req = gitHubRequest("https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1", token).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
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

    private fun getFileContentAndSha(
        owner: String,
        repo: String,
        path: String,
        branch: String,
        token: String,
    ): Pair<String, String?>? {
        val req = gitHubRequest("https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch", token).get().build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            val sha = obj["sha"]?.jsonPrimitive?.contentOrNull
            val encoded = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty().replace("\n", "")
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            decoded to sha
        }
    }

    private fun createBranch(owner: String, repo: String, newBranch: String, fromSha: String, token: String) {
        val payload = buildJsonObject {
            put("ref", "refs/heads/$newBranch")
            put("sha", fromSha)
        }
        val req = gitHubRequest("https://api.github.com/repos/$owner/$repo/git/refs", token)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Failed to create branch $newBranch (${resp.code}): ${body.take(300)}")
        }
    }

    private fun commitFile(
        owner: String,
        repo: String,
        path: String,
        branch: String,
        content: String,
        message: String,
        existingSha: String?,
        token: String,
    ): String {
        val base64Content = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val payload = buildJsonObject {
            put("message", message)
            put("content", base64Content)
            put("branch", branch)
            if (existingSha != null) put("sha", existingSha)
        }
        val req = gitHubRequest("https://api.github.com/repos/$owner/$repo/contents/$path", token)
            .put(payload.toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Commit failed for $path (${resp.code}): ${body.take(300)}")
            val obj = json.parseToJsonElement(body).jsonObject
            return obj["commit"]?.jsonObject?.get("sha")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    private fun createPullRequest(
        owner: String,
        repo: String,
        title: String,
        bodyText: String,
        headBranch: String,
        baseBranch: String,
        token: String,
    ): Pair<Int, String> {
        val payload = buildJsonObject {
            put("title", title)
            put("body", bodyText)
            put("head", headBranch)
            put("base", baseBranch)
        }
        val req = gitHubRequest("https://api.github.com/repos/$owner/$repo/pulls", token)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("PR creation failed (${resp.code}): ${body.take(300)}")
            val obj = json.parseToJsonElement(body).jsonObject
            val number = obj["number"]?.jsonPrimitive?.intOrNull ?: 0
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return number to htmlUrl
        }
    }

    private fun getLatestWorkflowRun(owner: String, repo: String, branch: String, token: String): JsonObject? {
        val req = gitHubRequest("https://api.github.com/repos/$owner/$repo/actions/runs?branch=$branch&per_page=1", token).get().build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            obj["workflow_runs"]?.jsonArray?.firstOrNull()?.jsonObject
        }
    }

    private fun getArtifactDownloadPage(owner: String, repo: String, runId: Long, token: String): String? {
        val req = gitHubRequest("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts", token).get().build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            val artifact = obj["artifacts"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@use null
            val artifactId = artifact["id"]?.jsonPrimitive?.contentOrNull
            if (artifactId != null) {
                "https://github.com/$owner/$repo/actions/runs/$runId/artifacts/$artifactId"
            } else {
                "https://github.com/$owner/$repo/actions/runs/$runId"
            }
        }
    }

    private fun fetchFailedJobLogs(owner: String, repo: String, runId: Long, token: String): String {
        val jobsReq = gitHubRequest("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/jobs", token).get().build()
        val failedJobId = httpClient.newCall(jobsReq).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            obj["jobs"]?.jsonArray
                ?.firstOrNull { it.jsonObject["conclusion"]?.jsonPrimitive?.contentOrNull == "failure" }
                ?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        } ?: return "No failed job details returned by GitHub."

        val logReq = gitHubRequest("https://api.github.com/repos/$owner/$repo/actions/jobs/$failedJobId/logs", token).get().build()
        return httpClient.newCall(logReq).execute().use { resp ->
            if (!resp.isSuccessful) return@use "Unable to fetch logs (HTTP ${resp.code})"
            val raw = resp.body?.string().orEmpty()
            // Keep tail lines where Gradle/Kotlin compiler errors appear
            raw.lines().takeLast(120).joinToString("\n")
        }
    }

    private fun extractJsonStringList(raw: String): List<String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList())
    }

    private fun extractJsonObjectString(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }
}
