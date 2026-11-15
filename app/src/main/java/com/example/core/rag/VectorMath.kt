package com.example.core.rag

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.sqrt

object VectorMath {
    private val json = Json { ignoreUnknownKeys = true }
    private const val LOCAL_DIM = 128

    fun encodeEmbedding(vector: FloatArray): String =
        json.encodeToString(vector.toList())

    fun decodeEmbedding(raw: String): FloatArray {
        if (raw.isBlank() || raw == "[]") return FloatArray(0)
        return runCatching {
            json.decodeFromString<List<Float>>(raw).toFloatArray()
        }.getOrElse { FloatArray(0) }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val va = a[i].toDouble()
            val vb = b[i].toDouble()
            dot += va * vb
            normA += va * va
            normB += vb * vb
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom <= 1e-12) 0f else (dot / denom).toFloat().coerceIn(-1f, 1f)
    }

    /**
     * Deterministic local hashed word + character-trigram semantic projection.
     * Used automatically when no remote embedding API key is configured or when
     * the selected provider does not expose an embeddings endpoint.
     */
    fun localSemanticEmbedding(text: String): FloatArray {
        val vec = FloatArray(LOCAL_DIM)
        val normalized = text.lowercase().replace(Regex("[^a-z0-9\\s]"), " ")
        val tokens = normalized.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return vec

        for (token in tokens) {
            val h1 = abs(token.hashCode()) % LOCAL_DIM
            val sign1 = if ((token.hashCode() and 1) == 0) 1f else -1f
            vec[h1] += 2.0f * sign1

            if (token.length >= 3) {
                for (i in 0..token.length - 3) {
                    val tri = token.substring(i, i + 3)
                    val h2 = abs(tri.hashCode()) % LOCAL_DIM
                    val sign2 = if ((tri.hashCode() and 2) == 0) 1f else -1f
                    vec[h2] += 1.0f * sign2
                }
            }
        }
        var norm = 0.0
        for (v in vec) norm += (v * v).toDouble()
        val mag = sqrt(norm).toFloat()
        if (mag > 1e-6f) {
            for (i in vec.indices) vec[i] /= mag
        }
        return vec
    }

    fun keywordOverlapScore(query: String, target: String): Float {
        val qTokens = query.lowercase().split(Regex("\\W+")).filter { it.length >= 3 }.toSet()
        if (qTokens.isEmpty()) return 0f
        val targetLower = target.lowercase()
        val matches = qTokens.count { targetLower.contains(it) }
        return matches.toFloat() / qTokens.size.toFloat()
    }
}
