package com.example.ui.screens.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AgentLabContainer
import com.example.core.ai.AgentLabBrain
import com.example.core.ai.LlmClient
import com.example.core.web.WebContentReader
import com.example.core.web.WebSearchClient
import com.example.data.db.SourceDao
import com.example.data.db.SourceEntity
import com.example.data.db.SourceStatus
import com.example.data.db.SourceType
import com.example.data.repository.KnowledgeRepository
import com.example.data.settings.AiProvider
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SourcesUiState(
    val activeReadingIds: Set<Long> = emptySet(),
    val statusBanner: String? = null,
)

class SourcesViewModel(
    private val sourceDao: SourceDao,
    private val webContentReader: WebContentReader,
    private val webSearchClient: WebSearchClient,
    private val knowledgeRepository: KnowledgeRepository,
    private val llmClient: LlmClient,
    private val nativeBrain: AgentLabBrain,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    val sources: StateFlow<List<SourceEntity>> = sourceDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(SourcesUiState())
    val uiState: StateFlow<SourcesUiState> = _uiState.asStateFlow()

    fun addSource(type: SourceType, title: String, value: String) {
        val raw = value.trim()
        if (raw.isBlank()) return
        val cleanVal = if (type == SourceType.URL) WebContentReader.normalizeUrl(raw) else raw
        val cleanTitle = title.trim().ifBlank {
            if (type == SourceType.URL) cleanVal.removePrefix("https://").removePrefix("http://").take(42) else cleanVal.take(42)
        }
        viewModelScope.launch {
            sourceDao.insert(
                SourceEntity(
                    type = type.name,
                    title = cleanTitle,
                    value = cleanVal,
                    enabled = true,
                ),
            )
        }
    }

    fun updateSource(source: SourceEntity, type: SourceType, title: String, value: String) {
        val raw = value.trim()
        val cleanVal = if (type == SourceType.URL && raw.isNotBlank()) WebContentReader.normalizeUrl(raw) else raw
        viewModelScope.launch {
            sourceDao.update(
                source.copy(
                    type = type.name,
                    title = title.trim().ifBlank { cleanVal.take(42) },
                    value = cleanVal,
                ),
            )
        }
    }

    fun toggleEnabled(source: SourceEntity, enabled: Boolean) {
        viewModelScope.launch {
            sourceDao.setEnabled(source.id, enabled)
        }
    }

    fun deleteSource(source: SourceEntity) {
        viewModelScope.launch {
            sourceDao.delete(source)
        }
    }

    fun readSourceNow(source: SourceEntity) {
        if (source.id in _uiState.value.activeReadingIds) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                activeReadingIds = _uiState.value.activeReadingIds + source.id,
                statusBanner = null,
            )
            sourceDao.updateStatus(source.id, SourceStatus.READING.name, null)

            try {
                val type = runCatching { SourceType.valueOf(source.type) }.getOrDefault(SourceType.URL)
                val urlsToRead = when (type) {
                    SourceType.URL -> listOf(source.value)
                    SourceType.TOPIC, SourceType.SEARCH_QUERY -> {
                        val query = if (type == SourceType.TOPIC) "${source.value} guide documentation" else source.value
                        webSearchClient.search(query, maxResults = 3).map { it.url }
                    }
                }
                if (urlsToRead.isEmpty()) {
                    throw IllegalStateException("No search results found for '${source.value}'.")
                }

                val settings = settingsRepository.getSnapshot()
                var savedCount = 0

                for (url in urlsToRead) {
                    val doc = webContentReader.readUrl(url)
                    if (doc.textContent.isBlank()) continue

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
                    } else {
                        nativeBrain.trainOnText("${doc.title}\n${doc.textContent}")
                        val extractPrompt = """
                            Summarize and extract key facts from this source.
                            Title: ${doc.title}
                            URL: ${doc.url}
                            Content:
                            ${doc.textContent.take(12_000)}

                            Return ONLY valid JSON:
                            {
                              "title": "Clear title",
                              "summary": "2-4 sentence summary",
                              "keyFacts": "- Fact 1\n- Fact 2\n- Fact 3",
                              "confidence": 0.85
                            }
                        """.trimIndent()
                        val raw = llmClient.complete(extractPrompt, "Return strict JSON only.").text
                        val parsed = extractJsonObj(raw)
                        val title = parsed?.get("title")?.jsonPrimitive?.contentOrNull ?: doc.title
                        val summary = parsed?.get("summary")?.jsonPrimitive?.contentOrNull ?: doc.textContent.take(600)
                        val facts = parsed?.get("keyFacts")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val conf = parsed?.get("confidence")?.jsonPrimitive?.floatOrNull ?: 0.85f
                        knowledgeRepository.saveWithDeduplication(title, summary, facts, doc.url, doc.title, conf)
                    }
                    savedCount++
                }

                sourceDao.markReadResult(
                    id = source.id,
                    status = SourceStatus.SUCCESS.name,
                    lastReadAt = System.currentTimeMillis(),
                    entryCount = source.entryCount + savedCount,
                    lastError = null,
                )
                _uiState.value = _uiState.value.copy(
                    statusBanner = "Read '${source.title}' -> trained brain & saved $savedCount knowledge ${if (savedCount == 1) "entry" else "entries"}.",
                )
            } catch (e: Exception) {
                sourceDao.markReadResult(
                    id = source.id,
                    status = SourceStatus.ERROR.name,
                    lastReadAt = System.currentTimeMillis(),
                    entryCount = source.entryCount,
                    lastError = e.message ?: "Failed to read source",
                )
            } finally {
                _uiState.value = _uiState.value.copy(
                    activeReadingIds = _uiState.value.activeReadingIds - source.id,
                )
            }
        }
    }

    fun dismissBanner() {
        _uiState.value = _uiState.value.copy(statusBanner = null)
    }

    private fun extractJsonObj(raw: String): kotlinx.serialization.json.JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    companion object {
        fun factory(container: AgentLabContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SourcesViewModel(
                        sourceDao = container.database.sourceDao(),
                        webContentReader = container.webContentReader,
                        webSearchClient = container.webSearchClient,
                        knowledgeRepository = container.knowledgeRepository,
                        llmClient = container.llmClient,
                        nativeBrain = container.agentLabBrain,
                        settingsRepository = container.settingsRepository,
                    ) as T
            }
    }
}
