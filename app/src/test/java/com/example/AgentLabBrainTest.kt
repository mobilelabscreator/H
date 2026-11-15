package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.ai.AgentLabBrain
import com.example.core.ai.LlmClient
import com.example.core.ai.TrainableNeuralNet
import com.example.core.github.RepoWorkspaceManager
import com.example.core.rag.VectorMath
import com.example.core.security.EncryptedSecretStore
import com.example.core.web.InternetExplorerEngine
import com.example.core.web.WebContentReader
import com.example.core.web.WebSearchClient
import com.example.data.db.AgentLabDatabase
import com.example.data.db.KnowledgeEntryEntity
import com.example.data.repository.KnowledgeRepository
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLabBrainTest {

    private lateinit var db: AgentLabDatabase
    private lateinit var weightsFile: File
    private lateinit var neuralNet: TrainableNeuralNet
    private lateinit var brain: AgentLabBrain

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AgentLabDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        weightsFile = File(context.cacheDir, "test_neural_weights_${System.nanoTime()}.bin")
        neuralNet = TrainableNeuralNet(weightsFile)
        brain = AgentLabBrain(db.brainDao(), db.trainingDao(), neuralNet)
    }

    @After
    fun tearDown() {
        db.close()
        weightsFile.delete()
    }

    @Test
    fun `trainNeuralNet decreases loss across backpropagation epochs and persists binary weights`() = runBlocking {
        val trainingText = buildString {
            repeat(6) {
                appendLine("fun trainAgentModel(epoch: Int) { val loss = computeCrossEntropy(logits, target); backpropagate(loss) }")
                appendLine("Agent Lab syncs GitHub repository code into local Termux workspace and optimizes neural weights.")
            }
        }

        val epochs = brain.trainNeuralEpochs(
            corpus = trainingText,
            epochs = 4,
            learningRate = 0.025f,
            sourceLabel = "unit_test",
        )

        assertEquals(4, epochs.size)
        val firstLoss = epochs.first().loss
        val finalLoss = epochs.last().loss
        assertTrue("Expected finalLoss ($finalLoss) < firstLoss ($firstLoss)", finalLoss < firstLoss)
        assertTrue("Expected persisted binary checkpoint file", weightsFile.exists() && weightsFile.length() > 100L)

        // Reload into a brand-new instance and verify weights + stats restored
        val reloaded = TrainableNeuralNet(weightsFile)
        val stats = reloaded.getStats()
        assertEquals(4, stats.totalEpochs)
        assertTrue(stats.parameterCount > 1000)
        assertTrue(stats.vocabSize > 10)
        assertEquals(finalLoss, stats.lastLoss, 0.0001f)

        // Verify TrainingDao recorded checkpoints
        val latestCp = db.trainingDao().getLatest()
        assertEquals(4, latestCp?.epoch)
    }

    @Test
    fun `trainOnText persists token transitions into Room`() = runBlocking {
        val sample = "Kotlin Jetpack Compose builds reactive Android interfaces cleanly. " +
            "Jetpack Compose manages state using StateFlow and ViewModels."
        brain.trainOnText(sample, weightBoost = 1.0f, sourceLabel = "transition_test")

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

    @Test
    fun `normalizeRepoSlug handles full github urls and owner repo slugs`() {
        assertEquals("octocat/hello-world", RepoWorkspaceManager.normalizeRepoSlug("https://github.com/octocat/hello-world.git"))
        assertEquals("octocat/hello-world", RepoWorkspaceManager.normalizeRepoSlug("octocat/hello-world/"))
        assertEquals(null, RepoWorkspaceManager.normalizeRepoSlug("invalid-slug"))
    }

    @Test
    fun `optimizeSelfModel strictly improves validation loss and persists optimized profile`() = runBlocking {
        val corpus = buildString {
            repeat(5) {
                appendLine("fun optimizeModel(lr: Float) { val loss = forward(tokens); backprop(lr, loss) }")
                appendLine("Agent Lab benchmarks candidate learning rates and retains strictly lower cross-entropy loss.")
            }
        }
        val opt = brain.optimizeSelfModel(corpus)
        assertTrue("Expected optimization to reduce loss: ${opt.summary}", opt.improved && opt.optimizedLoss < opt.baselineLoss)

        val reloaded = TrainableNeuralNet(weightsFile)
        val reloadedProfile = reloaded.getTrainingProfile()
        assertEquals(opt.winningProfile.learningRate, reloadedProfile.learningRate, 0.0001f)
        assertEquals(opt.winningProfile.residualScale, reloadedProfile.residualScale, 0.0001f)
    }

    @Test
    fun `webContentReader extracts urls from Chrome shared text and bare domains`() {
        assertEquals(
            "https://developer.android.com/compose",
            WebContentReader.extractFirstUrlOrNull("Jetpack Compose Docs - https://developer.android.com/compose."),
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Kotlin",
            WebContentReader.normalizeUrl("en.wikipedia.org/wiki/Kotlin"),
        )
        assertTrue(WebContentReader.looksLikeUrl("github.com/google/guava"))
        assertTrue(!WebContentReader.looksLikeUrl("how do neural networks work"))
    }

    @Test
    fun `internetExplorerEngine learns from rendered WebView DOM and saves KB and Source`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = SettingsRepository(context, EncryptedSecretStore())
        val llm = LlmClient(settingsRepo, brain, db.knowledgeDao())
        val kbRepo = KnowledgeRepository(db.knowledgeDao(), llm)
        val reader = WebContentReader(llm.httpClient, settingsRepo)
        val search = WebSearchClient(llm.httpClient, settingsRepo)
        val explorer = InternetExplorerEngine(
            settingsRepository = settingsRepo,
            webContentReader = reader,
            webSearchClient = search,
            knowledgeRepository = kbRepo,
            sourceDao = db.sourceDao(),
            evalDao = db.evalDao(),
            agentLogDao = db.agentLogDao(),
            nativeBrain = brain,
            llmClient = llm,
        )

        val domText = """
            Kotlin Multiplatform shares business logic across Android, iOS, web, and server targets.
            Jetpack Compose renders declarative user interfaces natively using state flows and coroutines.
            Developers inspect network requests and extract structured facts cleanly into vector knowledge bases.
            Autonomous agents crawl outbound documentation links to stay current with new frameworks.
        """.trimIndent()

        val learnedTitle = explorer.learnFromWebViewDom(
            url = "https://kotlinlang.org/docs/multiplatform.html",
            title = "Kotlin Multiplatform Documentation",
            domText = domText,
            outboundLinks = listOf("https://kotlinlang.org/docs/home.html"),
        )

        assertTrue(learnedTitle.contains("Kotlin"))
        assertTrue(db.knowledgeDao().getAll().any { it.sourceUrl.contains("kotlinlang.org") })
        assertTrue(db.sourceDao().getAll().any { it.value.contains("kotlinlang.org") })
        assertTrue(explorer.state.value.knowledgeLearned >= 1)
    }
}
