package com.example.core.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.example.core.ai.AgentLabBrain
import com.example.core.ai.LlmClient
import com.example.data.db.AgentLogDao
import com.example.data.db.AgentLogEntity
import com.example.data.db.EvalDao
import com.example.data.db.EvalQuestionEntity
import com.example.data.db.SourceDao
import com.example.data.db.SourceEntity
import com.example.data.db.SourceStatus
import com.example.data.db.SourceType
import com.example.data.repository.KnowledgeRepository
import com.example.data.settings.AiProvider
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

data class ExploredVisit(
    val url: String,
    val title: String,
    val learnedEntryTitle: String,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis(),
)

data class InternetExploreState(
    val isExploring: Boolean = false,
    val isLearningCurrentPage: Boolean = false,
    val currentUrl: String = "https://en.wikipedia.org/wiki/Artificial_intelligence",
    val currentTitle: String = "Explore Internet",
    val pagesVisited: Int = 0,
    val knowledgeLearned: Int = 0,
    val statusMessage: String = "Enter any link, browse live, or tap Auto-Explore.",
    val lastError: String? = null,
    val recentVisits: List<ExploredVisit> = emptyList(),
)

/**
 * Autonomous & interactive web exploration engine backed by real HTTP fetching,
 * embedded Chromium WebView DOM extraction, and Google Chrome launch/share integration.
 */
class InternetExplorerEngine(
    private val settingsRepository: SettingsRepository,
    private val webContentReader: WebContentReader,
    private val webSearchClient: WebSearchClient,
    private val knowledgeRepository: KnowledgeRepository,
    private val sourceDao: SourceDao,
    private val evalDao: EvalDao,
    private val agentLogDao: AgentLogDao,
    private val nativeBrain: AgentLabBrain,
    private val llmClient: LlmClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var exploreJob: Job? = null

    private val _state = MutableStateFlow(InternetExploreState())
    val state: StateFlow<InternetExploreState> = _state.asStateFlow()

    fun updateCurrentBrowserLocation(url: String, title: String?) {
        if (url.isBlank()) return
        _state.value = _state.value.copy(
            currentUrl = url,
            currentTitle = title?.takeIf { it.isNotBlank() } ?: _state.value.currentTitle,
        )
    }

    fun resolveOmniboxInput(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return _state.value.currentUrl
        val extractedUrl = WebContentReader.extractFirstUrlOrNull(trimmed)
        if (extractedUrl != null && WebContentReader.looksLikeUrl(extractedUrl)) {
            return extractedUrl
        }
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return "https://html.duckduckgo.com/html/?q=$encoded"
    }

    fun openInChrome(context: Context, rawUrl: String) {
        val target = resolveOmniboxInput(rawUrl)
        val chromeIntent = Intent(Intent.ACTION_VIEW, target.toUri()).apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chromeIntent)
        } catch (_: ActivityNotFoundException) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, target.toUri()).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }

    fun stopExploring() {
        exploreJob?.cancel()
        exploreJob = null
        _state.value = _state.value.copy(
            isExploring = false,
            statusMessage = "Exploration paused.",
        )
    }

    fun startAutonomousExplore(seedInput: String? = null, maxPages: Int = 5) {
        if (exploreJob?.isActive == true) return
        exploreJob = scope.launch {
            _state.value = _state.value.copy(
                isExploring = true,
                lastError = null,
                statusMessage = if (seedInput.isNullOrBlank()) {
                    "Picking curiosity topics & exploring the web..."
                } else {
                    "Exploring $seedInput..."
                },
            )
            try {
                runAutonomousExploreInternal(seedInput, maxPages.coerceIn(1, 12))
            } catch (_: CancellationException) {
                log("EXPLORE", "Internet exploration stopped by user.", "WARN")
            } catch (e: Exception) {
                _state.value = _state.value.copy(lastError = e.message ?: "Exploration failed")
                log("EXPLORE", "Exploration error: ${e.message}", "ERROR")
            } finally {
                _state.value = _state.value.copy(isExploring = false)
            }
        }
    }

    suspend fun learnFromWebViewDom(
        url: String,
        title: String,
        domText: String,
        outboundLinks: List<String> = emptyList(),
        alsoExploreLinks: Boolean = false,
    ): String {
        val cleanUrl = WebContentReader.normalizeUrl(url)
        val cleanText = domText.trim().take(35_000)
        if (cleanText.length < 40) {
            // Fallback to direct OkHttp reader if DOM was sparse or PDF
            val doc = webContentReader.readUrl(cleanUrl)
            return ingestDocument(doc)
        }
        val cleanTitle = title.trim().ifBlank { cleanUrl.removePrefix("https://").removePrefix("http://").take(48) }
        val safeLinks = outboundLinks
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .take(15)

        val learnedTitle = ingestDocument(
            WebDocument(
                url = cleanUrl,
                title = cleanTitle,
                contentType = "webview/dom",
                textContent = cleanText,
                outboundLinks = safeLinks,
            ),
        )

        // Ensure visited URL is tracked in Sources for transparency
        recordVisitedUrlAsSource(cleanUrl, cleanTitle)

        if (alsoExploreLinks && safeLinks.isNotEmpty() && exploreJob?.isActive != true) {
            startAutonomousExplore(safeLinks.first(), maxPages = 4)
        }
        return learnedTitle
    }

    private suspend fun runAutonomousExploreInternal(seedInput: String?, maxPages: Int) {
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()

        val cleanSeed = seedInput?.trim()?.takeIf { it.isNotBlank() }
        if (cleanSeed != null) {
            val extracted = WebContentReader.extractFirstUrlOrNull(cleanSeed)
            if (extracted != null) {
                queue.add(extracted)
            } else {
                _state.value = _state.value.copy(statusMessage = "Searching web for \"$cleanSeed\"...")
                log("EXPLORE", "Searching web for topic: $cleanSeed")
                val hits = webSearchClient.search(cleanSeed, maxResults = 5)
                hits.forEach { queue.add(it.url) }
            }
        } else {
            // Derive fresh exploration seeds from Sources or Knowledge keywords or starter curiosity topics
            val sources = sourceDao.getEnabledSources()
            sources.filter { it.type == SourceType.URL.name }.take(2).forEach { queue.add(it.value) }

            if (queue.isEmpty()) {
                val kb = knowledgeRepository.getAll()
                val curiosityQuery = if (kb.isNotEmpty()) {
                    val sample = kb.random()
                    val kws = nativeBrain.extractKeywords("${sample.title} ${sample.summary}", 3)
                    kws.joinToString(" ").ifBlank { sample.title }
                } else {
                    CURIOSITY_TOPICS.random()
                }
                _state.value = _state.value.copy(statusMessage = "Autonomously researching \"$curiosityQuery\"...")
                log("EXPLORE", "Derived curiosity query: $curiosityQuery")
                webSearchClient.search(curiosityQuery, maxResults = 5).forEach { queue.add(it.url) }
            }
        }

        if (queue.isEmpty()) {
            _state.value = _state.value.copy(
                statusMessage = "No reachable links found for this query. Try entering a direct URL.",
            )
            return
        }

        var count = 0
        while (queue.isNotEmpty() && count < maxPages) {
            currentCoroutineContext().ensureActive()
            val nextUrl = WebContentReader.normalizeUrl(queue.removeFirst())
            if (!visited.add(nextUrl)) continue

            _state.value = _state.value.copy(
                currentUrl = nextUrl,
                statusMessage = "Reading (${count + 1}/$maxPages): $nextUrl",
            )
            try {
                val doc = webContentReader.readUrl(nextUrl)
                if (doc.textContent.length >= 80) {
                    ingestDocument(doc)
                    recordVisitedUrlAsSource(doc.url, doc.title)
                    count++
                    // Enqueue interesting outbound links that differ from current page
                    doc.outboundLinks
                        .filter { it !in visited && !isLowValueLink(it) }
                        .take(3)
                        .forEach { queue.add(it) }
                } else {
                    log("EXPLORE", "Skipped thin page ($nextUrl)", "WARN")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("EXPLORE", "Skipped $nextUrl: ${e.message}", "WARN")
            }
        }

        // Run a neural training epoch over newly explored content
        val kb = knowledgeRepository.getAll().take(6)
        if (kb.isNotEmpty()) {
            val corpus = kb.joinToString("\n") { "${it.title}\n${it.summary}\n${it.keyFacts}" }
            nativeBrain.trainNeuralEpochs(corpus = corpus, epochs = 1, sourceLabel = "web_explore")
        }

        _state.value = _state.value.copy(
            statusMessage = "Explored $count page(s) and trained Neural Core.",
        )
        log("EXPLORE", "Internet exploration finished: visited $count page(s), total learned=${_state.value.knowledgeLearned}.", "SUCCESS")
    }

    private suspend fun ingestDocument(doc: WebDocument): String {
        _state.value = _state.value.copy(isLearningCurrentPage = true)
        try {
            val settings = settingsRepository.getSnapshot()
            val extractedTitle: String
            val extractedConfidence: Float

            if (settings.aiProvider == AiProvider.AGENT_LAB_NATIVE || (settings.aiProvider.requiresApiKey && settings.apiKey.isBlank())) {
                val extracted = nativeBrain.summarizeAndExtract(doc.title, doc.url, doc.textContent)
                knowledgeRepository.saveWithDeduplication(
                    title = extracted.title,
                    summary = extracted.summary,
                    keyFacts = extracted.keyFacts,
                    sourceUrl = doc.url,
                    sourceTitle = doc.title,
                    confidence = extracted.confidence,
                )
                val existingQs = evalDao.getAll()
                if (existingQs.none { it.question.equals(extracted.evalQuestion, ignoreCase = true) }) {
                    evalDao.insert(
                        EvalQuestionEntity(
                            question = extracted.evalQuestion,
                            expectedAnswer = extracted.evalAnswer,
                            isAutoGenerated = true,
                        ),
                    )
                }
                extractedTitle = extracted.title
                extractedConfidence = extracted.confidence
            } else {
                nativeBrain.trainOnText("${doc.title}\n${doc.textContent}", sourceLabel = "web:${doc.title.take(28)}")
                val prompt = """
                    Summarize and extract key facts from this web page.
                    Title: ${doc.title}
                    URL: ${doc.url}
                    Content:
                    ${doc.textContent.take(10_000)}

                    Reply ONLY in valid JSON:
                    {
                      "title": "Concise title",
                      "summary": "2-4 sentence summary",
                      "keyFacts": "- Fact 1\n- Fact 2\n- Fact 3",
                      "confidence": 0.85
                    }
                """.trimIndent()
                val raw = llmClient.complete(prompt, "Return strict JSON only.").text
                val obj = parseJsonObj(raw)
                extractedTitle = obj?.get("title")?.jsonPrimitive?.contentOrNull ?: doc.title
                val summary = obj?.get("summary")?.jsonPrimitive?.contentOrNull ?: doc.textContent.take(500)
                val facts = obj?.get("keyFacts")?.jsonPrimitive?.contentOrNull.orEmpty()
                extractedConfidence = obj?.get("confidence")?.jsonPrimitive?.floatOrNull ?: 0.84f
                knowledgeRepository.saveWithDeduplication(extractedTitle, summary, facts, doc.url, doc.title, extractedConfidence)
            }

            val visit = ExploredVisit(
                url = doc.url,
                title = doc.title,
                learnedEntryTitle = extractedTitle,
                confidence = extractedConfidence,
            )
            _state.value = _state.value.copy(
                currentUrl = doc.url,
                currentTitle = doc.title,
                pagesVisited = _state.value.pagesVisited + 1,
                knowledgeLearned = _state.value.knowledgeLearned + 1,
                recentVisits = (listOf(visit) + _state.value.recentVisits).take(15),
                statusMessage = "Learned: $extractedTitle",
            )
            log("EXPLORE", "Learned from ${doc.url} -> $extractedTitle", "SUCCESS")
            return extractedTitle
        } finally {
            _state.value = _state.value.copy(isLearningCurrentPage = false)
        }
    }

    private suspend fun recordVisitedUrlAsSource(url: String, title: String) {
        val existing = sourceDao.getAll().firstOrNull { it.value.equals(url, ignoreCase = true) }
        val now = System.currentTimeMillis()
        if (existing != null) {
            sourceDao.markReadResult(
                id = existing.id,
                status = SourceStatus.SUCCESS.name,
                lastReadAt = now,
                entryCount = existing.entryCount + 1,
                lastError = null,
            )
        } else {
            sourceDao.insert(
                SourceEntity(
                    type = SourceType.URL.name,
                    title = title.ifBlank { url.removePrefix("https://").take(42) }.take(64),
                    value = url,
                    enabled = true,
                    lastReadAt = now,
                    entryCount = 1,
                    status = SourceStatus.SUCCESS.name,
                ),
            )
        }
    }

    private fun isLowValueLink(url: String): Boolean {
        val lower = url.lowercase()
        return listOf("login", "signup", "privacy", "terms", "cookie", ".png", ".jpg", ".svg", ".ico", "accounts.")
            .any { lower.contains(it) }
    }

    private suspend fun log(phase: String, message: String, level: String = "INFO") {
        agentLogDao.insert(
            AgentLogEntity(
                runId = "EXPLORE",
                phase = phase,
                message = message,
                level = level,
            ),
        )
    }

    private fun parseJsonObj(raw: String): kotlinx.serialization.json.JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    companion object {
        private val CURIOSITY_TOPICS = listOf(
            "neural network backpropagation optimization tutorial",
            "Kotlin coroutines structured concurrency guide",
            "Android Jetpack Compose architecture best practices",
            "Retrieval Augmented Generation embeddings cosine similarity",
        )
    }
}
