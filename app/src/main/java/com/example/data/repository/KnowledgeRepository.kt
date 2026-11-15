package com.example.data.repository

import com.example.core.ai.LlmClient
import com.example.core.rag.VectorMath
import com.example.data.db.KnowledgeDao
import com.example.data.db.KnowledgeEntryEntity
import kotlinx.coroutines.flow.Flow

data class RetrievedKnowledge(
    val entry: KnowledgeEntryEntity,
    val score: Float,
)

class KnowledgeRepository(
    private val knowledgeDao: KnowledgeDao,
    private val llmClient: LlmClient,
) {
    val allEntries: Flow<List<KnowledgeEntryEntity>> = knowledgeDao.observeAll()
    val entryCount: Flow<Int> = knowledgeDao.observeCount()

    suspend fun getAll(): List<KnowledgeEntryEntity> = knowledgeDao.getAll()

    suspend fun saveWithDeduplication(
        title: String,
        summary: String,
        keyFacts: String,
        sourceUrl: String,
        sourceTitle: String,
        confidence: Float,
    ): Long {
        val cleanTitle = title.trim().ifBlank { "Untitled Note" }
        val cleanSummary = summary.trim()
        val combinedText = "$cleanTitle\n$cleanSummary\n$keyFacts"
        val newEmbedding = llmClient.embed(combinedText)

        // Check for near-duplicates (same URL + high cosine similarity, or >0.91 semantic match)
        val existing = knowledgeDao.getAll()
        val duplicate = existing.firstOrNull { item ->
            val vec = VectorMath.decodeEmbedding(item.embeddingJson)
            val sim = if (vec.size == newEmbedding.size && vec.isNotEmpty()) {
                VectorMath.cosineSimilarity(newEmbedding, vec)
            } else {
                VectorMath.cosineSimilarity(
                    VectorMath.localSemanticEmbedding(combinedText),
                    VectorMath.localSemanticEmbedding("${item.title}\n${item.summary}\n${item.keyFacts}"),
                )
            }
            (item.sourceUrl.equals(sourceUrl, ignoreCase = true) && sim > 0.78f) || sim > 0.91f
        }

        val now = System.currentTimeMillis()
        val encoded = VectorMath.encodeEmbedding(newEmbedding)
        return if (duplicate != null) {
            val mergedFacts = if (keyFacts.isBlank()) {
                duplicate.keyFacts
            } else if (duplicate.keyFacts.contains(keyFacts.take(40), ignoreCase = true)) {
                duplicate.keyFacts
            } else {
                (duplicate.keyFacts + "\n" + keyFacts).trim()
            }
            val updated = duplicate.copy(
                title = cleanTitle,
                summary = if (cleanSummary.length >= duplicate.summary.length) cleanSummary else duplicate.summary,
                keyFacts = mergedFacts,
                confidence = maxOf(duplicate.confidence, confidence.coerceIn(0f, 1f)),
                embeddingJson = encoded,
                updatedAt = now,
            )
            knowledgeDao.update(updated)
            duplicate.id
        } else {
            knowledgeDao.insert(
                KnowledgeEntryEntity(
                    title = cleanTitle,
                    summary = cleanSummary,
                    keyFacts = keyFacts.trim(),
                    sourceUrl = sourceUrl.trim(),
                    sourceTitle = sourceTitle.trim(),
                    confidence = confidence.coerceIn(0f, 1f),
                    embeddingJson = encoded,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun updateEntry(entry: KnowledgeEntryEntity) {
        val text = "${entry.title}\n${entry.summary}\n${entry.keyFacts}"
        val newEmb = llmClient.embed(text)
        knowledgeDao.update(
            entry.copy(
                embeddingJson = VectorMath.encodeEmbedding(newEmb),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteEntry(entry: KnowledgeEntryEntity) {
        knowledgeDao.delete(entry)
    }

    suspend fun retrieveRelevant(
        query: String,
        topK: Int = 5,
        minScore: Float = 0.12f,
    ): List<RetrievedKnowledge> {
        val entries = knowledgeDao.getAll()
        if (entries.isEmpty()) return emptyList()

        val queryApiEmb = llmClient.embed(query)
        val queryLocalEmb = VectorMath.localSemanticEmbedding(query)

        return entries
            .map { entry ->
                val storedEmb = VectorMath.decodeEmbedding(entry.embeddingJson)
                val semantic = if (storedEmb.isNotEmpty() && storedEmb.size == queryApiEmb.size) {
                    VectorMath.cosineSimilarity(queryApiEmb, storedEmb)
                } else {
                    val entryLocal = VectorMath.localSemanticEmbedding("${entry.title}\n${entry.summary}\n${entry.keyFacts}")
                    VectorMath.cosineSimilarity(queryLocalEmb, entryLocal)
                }
                val lexical = VectorMath.keywordOverlapScore(
                    query,
                    "${entry.title} ${entry.summary} ${entry.keyFacts}",
                )
                val combined = (semantic * 0.72f + lexical * 0.28f).coerceIn(0f, 1f)
                RetrievedKnowledge(entry = entry, score = combined)
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
    }
}
