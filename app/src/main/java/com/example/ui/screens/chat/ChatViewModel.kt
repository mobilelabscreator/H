package com.example.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AgentLabContainer
import com.example.core.ai.ChatTurn
import com.example.core.ai.LlmClient
import com.example.core.ai.StreamChunk
import com.example.data.db.ChatDao
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ChatSessionEntity
import com.example.data.db.SourceCitation
import com.example.data.repository.KnowledgeRepository
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ChatUiState(
    val activeSessionId: Long? = null,
    val isStreaming: Boolean = false,
    val streamingText: String = "",
    val streamingCitations: List<SourceCitation> = emptyList(),
    val errorMessage: String? = null,
)

class ChatViewModel(
    private val chatDao: ChatDao,
    private val knowledgeRepository: KnowledgeRepository,
    private val llmClient: LlmClient,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val sessions: StateFlow<List<ChatSessionEntity>> = chatDao.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<ChatMessageEntity>> = _uiState
        .flatMapLatest { state ->
            val sid = state.activeSessionId
            if (sid == null) flowOf(emptyList()) else chatDao.observeMessages(sid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            val latest = chatDao.getLatestSession()
            if (latest != null) {
                _uiState.value = _uiState.value.copy(activeSessionId = latest.id)
            } else {
                createNewSession()
            }
        }
    }

    fun selectSession(sessionId: Long) {
        if (_uiState.value.isStreaming) stopStreaming()
        _uiState.value = _uiState.value.copy(activeSessionId = sessionId, errorMessage = null)
    }

    fun createNewSession() {
        viewModelScope.launch {
            if (_uiState.value.isStreaming) stopStreaming()
            val id = chatDao.insertSession(ChatSessionEntity(title = "New Session"))
            _uiState.value = _uiState.value.copy(activeSessionId = id, errorMessage = null)
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            chatDao.deleteSession(sessionId)
            if (_uiState.value.activeSessionId == sessionId) {
                val next = chatDao.getLatestSession()
                if (next != null) {
                    _uiState.value = _uiState.value.copy(activeSessionId = next.id)
                } else {
                    val fresh = chatDao.insertSession(ChatSessionEntity(title = "New Session"))
                    _uiState.value = _uiState.value.copy(activeSessionId = fresh)
                }
            }
        }
    }

    fun clearCurrentChat() {
        val sid = _uiState.value.activeSessionId ?: return
        viewModelScope.launch {
            chatDao.clearSessionMessages(sid)
            chatDao.updateSessionTitle(sid, "New Session")
        }
    }

    fun sendMessage(rawPrompt: String) {
        val prompt = rawPrompt.trim()
        if (prompt.isBlank() || _uiState.value.isStreaming) return

        streamJob = viewModelScope.launch {
            val sessionId = _uiState.value.activeSessionId ?: run {
                val id = chatDao.insertSession(ChatSessionEntity(title = prompt.take(36)))
                _uiState.value = _uiState.value.copy(activeSessionId = id)
                id
            }

            val existing = chatDao.getMessages(sessionId)
            if (existing.isEmpty()) {
                chatDao.updateSessionTitle(sessionId, prompt.take(40))
            }

            chatDao.insertMessage(
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = "user",
                    content = prompt,
                ),
            )
            chatDao.touchSession(sessionId)

            // Retrieve relevant knowledge via RAG embeddings
            val retrieved = knowledgeRepository.retrieveRelevant(prompt, topK = 5, minScore = 0.08f)
            val citations = retrieved.map {
                SourceCitation(
                    knowledgeId = it.entry.id,
                    title = it.entry.title,
                    sourceUrl = it.entry.sourceUrl,
                    similarity = it.score,
                    confidence = it.entry.confidence,
                )
            }

            val settings = settingsRepository.getSnapshot()
            val augmentedSystem = buildString {
                appendLine(settings.systemPrompt)
                if (retrieved.isNotEmpty()) {
                    appendLine()
                    appendLine("### Retrieved Knowledge Base Context")
                    appendLine("Use these verified local knowledge entries when relevant and cite their source URLs inline:")
                    retrieved.forEachIndexed { idx, item ->
                        appendLine("[${idx + 1}] ${item.entry.title} (Source: ${item.entry.sourceUrl}, Confidence: ${"%.0f".format(item.entry.confidence * 100)}%)")
                        appendLine("Summary: ${item.entry.summary}")
                        if (item.entry.keyFacts.isNotBlank()) {
                            appendLine("Facts: ${item.entry.keyFacts}")
                        }
                        appendLine()
                    }
                }
            }

            val turns = (existing.takeLast(12) + ChatMessageEntity(sessionId = sessionId, role = "user", content = prompt))
                .map { ChatTurn(role = it.role, content = it.content) }

            _uiState.value = _uiState.value.copy(
                isStreaming = true,
                streamingText = "",
                streamingCitations = citations,
                errorMessage = null,
            )

            val totalKbCount = knowledgeRepository.getAll().size
            val citationsJson = json.encodeToString(citations)
            val buffer = StringBuilder()

            llmClient.streamChat(
                history = turns,
                systemPrompt = augmentedSystem,
                retrievedKnowledge = retrieved.map { it.entry },
                allKnowledgeCount = totalKbCount,
                overrideSettings = settings,
            ).collect { chunk ->
                when (chunk) {
                    is StreamChunk.Delta -> {
                        buffer.append(chunk.text)
                        _uiState.value = _uiState.value.copy(streamingText = buffer.toString())
                    }
                    is StreamChunk.Completed -> {
                        val finalContent = chunk.fullText.ifBlank { buffer.toString() }
                        if (finalContent.isNotBlank()) {
                            chatDao.insertMessage(
                                ChatMessageEntity(
                                    sessionId = sessionId,
                                    role = "assistant",
                                    content = finalContent,
                                    citationsJson = citationsJson,
                                ),
                            )
                            chatDao.touchSession(sessionId)
                        }
                        _uiState.value = _uiState.value.copy(
                            isStreaming = false,
                            streamingText = "",
                            streamingCitations = emptyList(),
                        )
                    }
                    is StreamChunk.Error -> {
                        if (buffer.isNotEmpty()) {
                            chatDao.insertMessage(
                                ChatMessageEntity(
                                    sessionId = sessionId,
                                    role = "assistant",
                                    content = buffer.toString(),
                                    citationsJson = citationsJson,
                                ),
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            isStreaming = false,
                            streamingText = "",
                            streamingCitations = emptyList(),
                            errorMessage = chunk.message,
                        )
                    }
                }
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        val partial = _uiState.value.streamingText
        val sid = _uiState.value.activeSessionId
        val citationsJson = json.encodeToString(_uiState.value.streamingCitations)
        if (partial.isNotBlank() && sid != null) {
            viewModelScope.launch {
                chatDao.insertMessage(
                    ChatMessageEntity(
                        sessionId = sid,
                        role = "assistant",
                        content = "$partial\n\n_(Generation stopped)_",
                        citationsJson = citationsJson,
                    ),
                )
            }
        }
        _uiState.value = _uiState.value.copy(
            isStreaming = false,
            streamingText = "",
            streamingCitations = emptyList(),
        )
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun parseCitations(citationsJson: String): List<SourceCitation> =
        runCatching { json.decodeFromString<List<SourceCitation>>(citationsJson) }.getOrDefault(emptyList())

    companion object {
        fun factory(container: AgentLabContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(
                        chatDao = container.database.chatDao(),
                        knowledgeRepository = container.knowledgeRepository,
                        llmClient = container.llmClient,
                        settingsRepository = container.settingsRepository,
                    ) as T
            }
    }
}
