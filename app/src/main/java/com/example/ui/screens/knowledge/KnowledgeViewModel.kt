package com.example.ui.screens.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AgentLabContainer
import com.example.data.db.KnowledgeEntryEntity
import com.example.data.repository.KnowledgeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class KnowledgeListItem(
    val entry: KnowledgeEntryEntity,
    val matchScore: Float? = null,
)

class KnowledgeViewModel(
    private val knowledgeRepository: KnowledgeRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _semanticResults = MutableStateFlow<Map<Long, Float>>(emptyMap())

    val filteredEntries: StateFlow<List<KnowledgeListItem>> = combine(
        knowledgeRepository.allEntries,
        _searchQuery,
        _semanticResults,
    ) { entries, query, semanticScores ->
        if (query.isBlank()) {
            entries.map { KnowledgeListItem(it, null) }
        } else {
            val q = query.trim().lowercase()
            entries.mapNotNull { entry ->
                val textHit = entry.title.lowercase().contains(q) ||
                    entry.summary.lowercase().contains(q) ||
                    entry.keyFacts.lowercase().contains(q) ||
                    entry.sourceUrl.lowercase().contains(q)
                val semScore = semanticScores[entry.id]
                if (textHit || (semScore != null && semScore > 0.14f)) {
                    KnowledgeListItem(entry, semScore ?: 0.5f)
                } else {
                    null
                }
            }.sortedByDescending { it.matchScore ?: 0f }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _semanticResults.value = emptyMap()
            return
        }
        viewModelScope.launch {
            val matches = knowledgeRepository.retrieveRelevant(query, topK = 20, minScore = 0.05f)
            _semanticResults.value = matches.associate { it.entry.id to it.score }
        }
    }

    fun updateEntry(entry: KnowledgeEntryEntity, title: String, summary: String, keyFacts: String, confidence: Float) {
        viewModelScope.launch {
            knowledgeRepository.updateEntry(
                entry.copy(
                    title = title.trim(),
                    summary = summary.trim(),
                    keyFacts = keyFacts.trim(),
                    confidence = confidence.coerceIn(0f, 1f),
                ),
            )
        }
    }

    fun deleteEntry(entry: KnowledgeEntryEntity) {
        viewModelScope.launch {
            knowledgeRepository.deleteEntry(entry)
        }
    }

    companion object {
        fun factory(container: AgentLabContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    KnowledgeViewModel(container.knowledgeRepository) as T
            }
    }
}
