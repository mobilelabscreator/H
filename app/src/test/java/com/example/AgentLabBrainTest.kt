package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.ai.AgentLabBrain
import com.example.core.rag.VectorMath
import com.example.data.db.AgentLabDatabase
import com.example.data.db.KnowledgeEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLabBrainTest {

    private lateinit var db: AgentLabDatabase
    private lateinit var brain: AgentLabBrain

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AgentLabDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        brain = AgentLabBrain(db.brainDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `trainOnText persists token transitions into Room`() = runBlocking {
        val sample = "Kotlin Jetpack Compose builds reactive Android interfaces cleanly. " +
            "Jetpack Compose manages state using StateFlow and ViewModels."
        brain.trainOnText(sample, weightBoost = 1.0f)

        val count = db.brainDao().getTransitionCount()
        assertTrue("Expected positive persisted neural transition count, got $count", count > 5)
    }

    @Test
    fun `summarizeAndExtract produces TextRank summary facts and eval pair`() = runBlocking {
        val article = """
            Room provides an abstraction layer over SQLite to allow fluent database access on Android.
            Developers define Entity classes, DAO interfaces, and a RoomDatabase subclass for persistence.
            Room validates SQL queries at compile time to catch errors before runtime execution.
            Kotlin Coroutines and Flow integrate natively with Room DAOs for reactive UI updates.
            Vector embeddings stored alongside entries enable semantic retrieval without cloud APIs.
        """.trimIndent()

        val result = brain.summarizeAndExtract(
            rawTitle = "Android Room Persistence Guide",
            url = "https://developer.android.com/training/data-storage/room",
            content = article,
        )

        assertEquals("Android Room Persistence Guide", result.title)
        assertTrue(result.summary.isNotBlank())
        assertTrue(result.keyFacts.contains("- "))
        assertTrue(result.confidence >= 0.7f)
        assertTrue(result.evalQuestion.isNotBlank())
        assertTrue(result.evalAnswer.isNotBlank())
    }

    @Test
    fun `evaluateAccuracy scores matching answer higher than unrelated answer`() {
        val expected = "Room validates SQL queries at compile time and integrates with Kotlin Flow."
        val goodCandidate = "Room checks SQL statements during compilation and works with Kotlin Flow."
        val poorCandidate = "Bananas grow in tropical climates with abundant sunshine and rain."

        val goodScore = brain.evaluateAccuracy(expected, goodCandidate)
        val poorScore = brain.evaluateAccuracy(expected, poorCandidate)

        assertTrue("Expected goodScore ($goodScore) > poorScore ($poorScore)", goodScore > poorScore + 0.25f)
    }

    @Test
    fun `generateReply cites retrieved knowledge entries`() = runBlocking {
        val text = "QuickJS executes sandboxed JavaScript inside Android apps with strict memory limits."
        val vec = VectorMath.localSemanticEmbedding(text)
        val entry = KnowledgeEntryEntity(
            id = 1L,
            title = "QuickJS Sandbox Overview",
            summary = text,
            keyFacts = "- Strict memory and stack size limits protect the host process.",
            sourceUrl = "https://bellard.org/quickjs/",
            sourceTitle = "QuickJS Engine",
            confidence = 0.9f,
            embeddingJson = VectorMath.encodeEmbedding(vec),
        )

        val reply = brain.generateReply(
            userPrompt = "How does QuickJS protect the host Android app?",
            systemPrompt = "Cite sources.",
            retrievedKnowledge = listOf(entry),
            allKnowledgeCount = 1,
        )

        assertTrue(reply.contains("Sources Referenced"))
        assertTrue(reply.contains("https://bellard.org/quickjs/"))
    }
}
