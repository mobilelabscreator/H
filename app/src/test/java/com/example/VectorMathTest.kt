package com.example

import com.example.core.rag.VectorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMathTest {

    @Test
    fun `identical texts have cosine similarity close to 1`() {
        val a = VectorMath.localSemanticEmbedding("Jetpack Compose adaptive NavigationRail for Android tablets")
        val b = VectorMath.localSemanticEmbedding("Jetpack Compose adaptive NavigationRail for Android tablets")
        val sim = VectorMath.cosineSimilarity(a, b)
        assertEquals(1.0f, sim, 0.001f)
    }

    @Test
    fun `related texts score higher than unrelated texts`() {
        val query = VectorMath.localSemanticEmbedding("QuickJS JavaScript sandbox skills")
        val related = VectorMath.localSemanticEmbedding("Sandboxed QuickJS runtime executes JavaScript skills")
        val unrelated = VectorMath.localSemanticEmbedding("Bananas and tropical smoothies recipe")

        val simRelated = VectorMath.cosineSimilarity(query, related)
        val simUnrelated = VectorMath.cosineSimilarity(query, unrelated)
        assertTrue("Expected related ($simRelated) > unrelated ($simUnrelated)", simRelated > simUnrelated)
    }

    @Test
    fun `embedding serialization round trips accurately`() {
        val vec = floatArrayOf(0.25f, -0.5f, 0.75f, 0.125f)
        val encoded = VectorMath.encodeEmbedding(vec)
        val decoded = VectorMath.decodeEmbedding(encoded)
        assertEquals(vec.size, decoded.size)
        for (i in vec.indices) {
            assertEquals(vec[i], decoded[i], 0.0001f)
        }
    }
}
