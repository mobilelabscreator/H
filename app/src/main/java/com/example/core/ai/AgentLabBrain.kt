package com.example.core.ai

import com.example.core.rag.VectorMath
import com.example.data.db.BrainDao
import com.example.data.db.BrainTransitionEntity
import com.example.data.db.KnowledgeEntryEntity
import com.example.data.db.TrainingCheckpointEntity
import com.example.data.db.TrainingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.math.ln

data class NativeSummaryResult(
    val title: String,
    val summary: String,
    val keyFacts: String,
    val confidence: Float,
    val evalQuestion: String,
    val evalAnswer: String,
)

/**
 * Self-contained, trainable on-device AI engine ("Agent Lab Core").
 * Built from scratch in Kotlin with zero external API keys and zero dependency on Gemini:
 * - Includes [TrainableNeuralNet]: pure-Kotlin backpropagation neural network with Adam optimizer,
 *   dynamic token/code vocabulary, cross-entropy loss tracking, and persisted binary weight checkpoints
 * - Maintains associative context weights in Room (`brain_transitions`)
 * - Summarizes web pages, GitHub source repos, PDFs, and transcripts using TextRank graph centrality
 * - Synthesizes grounded, streaming Markdown replies with explicit source citations
 * - Evaluates answer accuracy using semantic cosine + F1 concept overlap
 */
class AgentLabBrain(
    private val brainDao: BrainDao,
    private val trainingDao: TrainingDao,
    val neuralNet: TrainableNeuralNet,
) {
    val transitionCountFlow: Flow<Int> = brainDao.observeTransitionCount()
    val checkpointsFlow: Flow<List<TrainingCheckpointEntity>> = trainingDao.observeRecent()

    suspend fun getNeuralStats(): NeuralModelStats = neuralNet.getStats()

    suspend fun trainOnText(
        text: String,
        weightBoost: Float = 1.0f,
        sourceLabel: String = "document",
        neuralEpochs: Int = 1,
    ) = withContext(Dispatchers.IO) {
        val tokens = tokenizeWords(text).take(1200)
        if (tokens.size >= 3) {
            val counts = mutableMapOf<Pair<String, String>, Float>()
            for (i in 0 until tokens.size - 1) {
                val w1 = tokens[i]
                val w2 = tokens[i + 1]
                counts[w1 to w2] = (counts[w1 to w2] ?: 0f) + weightBoost

                if (i + 2 < tokens.size) {
                    val w3 = tokens[i + 2]
                    val key2 = "$w1 $w2"
                    counts[key2 to w3] = (counts[key2 to w3] ?: 0f) + (weightBoost * 1.5f)
                }
            }

            val now = System.currentTimeMillis()
            val batch = mutableListOf<BrainTransitionEntity>()
            for ((pair, addWeight) in counts.entries.sortedByDescending { it.value }.take(250)) {
                val existing = brainDao.findTransition(pair.first, pair.second)
                if (existing != null) {
                    batch.add(existing.copy(weight = existing.weight + addWeight, updatedAt = now))
                } else {
                    batch.add(
                        BrainTransitionEntity(
                            contextKey = pair.first,
                            nextToken = pair.second,
                            weight = addWeight,
                            updatedAt = now,
                        ),
                    )
                }
            }
            if (batch.isNotEmpty()) {
                brainDao.upsertAll(batch)
            }
        }

        if (neuralEpochs > 0 && text.trim().length >= 24) {
            trainNeuralEpochs(
                corpus = text,
                epochs = neuralEpochs,
                sourceLabel = sourceLabel,
            )
        }
    }

    suspend fun trainNeuralEpochs(
        corpus: String,
        epochs: Int = 2,
        learningRate: Float = 0.015f,
        sourceLabel: String = "corpus",
        onEpoch: (suspend (TrainingEpochMetrics) -> Unit)? = null,
    ): List<TrainingEpochMetrics> {
        val epochResults = neuralNet.trainOnCorpus(
            corpus = corpus,
            epochs = epochs,
            learningRate = learningRate,
            onEpochDone = { metrics ->
                trainingDao.insert(
                    TrainingCheckpointEntity(
                        epoch = metrics.epoch,
                        loss = metrics.loss,
                        perplexity = metrics.perplexity,
                        vocabSize = metrics.vocabSize,
                        parameterCount = metrics.parameterCount,
                        tokensProcessed = metrics.tokensProcessed,
                        sourceLabel = sourceLabel.take(64),
                    ),
                )
                onEpoch?.invoke(metrics)
            },
        )
        return epochResults
    }

    suspend fun summarizeAndExtract(
        rawTitle: String,
        url: String,
        content: String,
        maxSummarySentences: Int = 3,
    ): NativeSummaryResult = withContext(Dispatchers.Default) {
        trainOnText("$rawTitle\n$content", weightBoost = 1.0f, sourceLabel = rawTitle.ifBlank { url })

        val cleanLines = content.lines()
            .map { it.trim() }
            .filter { it.length >= 25 }
            .distinct()
        val sentences = splitSentences(cleanLines.joinToString(" "))
            .filter { it.length in 35..340 }
            .take(80)

        val ranked = rankSentencesTextRank(sentences)
        val summarySentences = ranked.take(maxSummarySentences.coerceIn(2, 5))
        val summary = if (summarySentences.isNotEmpty()) {
            summarySentences.joinToString(" ")
        } else {
            content.take(450).trim()
        }

        val keyFactsList = ranked
            .drop(1)
            .ifEmpty { ranked }
            .take(4)
            .map { "- ${it.trim()}" }
        val keyFacts = keyFactsList.joinToString("\n")

        val title = rawTitle.trim().ifBlank { url.substringAfter("://").take(48) }
        val confidence = when {
            sentences.size >= 12 -> 0.88f
            sentences.size >= 5 -> 0.80f
            content.length >= 200 -> 0.72f
            else -> 0.58f
        }

        val firstFact = ranked.firstOrNull() ?: summary
        val topicKeywords = extractKeywords(title, 3).joinToString(" ").ifBlank { title }
        val evalQuestion = "What are the key details about $topicKeywords?"
        val evalAnswer = firstFact

        NativeSummaryResult(
            title = title,
            summary = summary,
            keyFacts = keyFacts,
            confidence = confidence,
            evalQuestion = evalQuestion,
            evalAnswer = evalAnswer,
        )
    }

    fun streamResponse(
        userPrompt: String,
        systemPrompt: String,
        retrievedKnowledge: List<KnowledgeEntryEntity>,
        allKnowledgeCount: Int,
        temperature: Float,
        maxTokens: Int,
    ): Flow<StreamChunk> = flow {
        trainOnText(userPrompt, weightBoost = 0.6f, sourceLabel = "chat_prompt", neuralEpochs = 0)

        val fullReply = generateReply(
            userPrompt = userPrompt,
            systemPrompt = systemPrompt,
            retrievedKnowledge = retrievedKnowledge,
            allKnowledgeCount = allKnowledgeCount,
            temperature = temperature,
            maxTokens = maxTokens,
        )

        val words = fullReply.split(Regex("(?<=\\s)"))
        var step = 0
        for (token in words) {
            emit(StreamChunk.Delta(token))
            step++
            if (step % 3 == 0) delay(14L)
        }
        val approxTokens = ((userPrompt.length + fullReply.length) / 4).coerceAtLeast(1)
        emit(StreamChunk.Completed(fullReply, approxTokens))
    }.flowOn(Dispatchers.Default)

    suspend fun generateReply(
        userPrompt: String,
        systemPrompt: String,
        retrievedKnowledge: List<KnowledgeEntryEntity>,
        allKnowledgeCount: Int,
        temperature: Float = 0.4f,
        maxTokens: Int = 2048,
    ): String = withContext(Dispatchers.Default) {
        val cleanPrompt = userPrompt.trim()
        val intent = detectIntent(cleanPrompt)
        val transitionCount = brainDao.getTransitionCount()
        val stats = neuralNet.getStats()

        if (retrievedKnowledge.isEmpty()) {
            val neuralOut = neuralNet.generateContinuation(cleanPrompt, maxTokens = 26, temperature = temperature)
            val transitionOut = generateTransitionContinuation(cleanPrompt, temperature, maxWords = 24)
            val continuation = neuralOut.ifBlank { transitionOut }

            return@withContext buildString {
                appendLine("### Agent Lab Core (${stats.parameterCount} params • epoch ${stats.totalEpochs})")
                if (allKnowledgeCount == 0) {
                    appendLine("Knowledge Base is empty; trained neural weights: **${stats.vocabSize}** vocab tokens, **$transitionCount** associative transitions.")
                    appendLine()
                    appendLine("Quick Termux commands you can run right here:")
                    appendLine("- `/repo owner/repo` — Connect your GitHub source code so I can train on it and self-upgrade")
                    appendLine("- `/train` — Run backpropagation epochs on learned sources & repo files")
                    appendLine("- `/run` — Launch the full autonomous Research -> Train -> Eval -> Upgrade loop")
                    appendLine("- `/help` — View all shell & workspace commands")
                } else {
                    appendLine("Searched **$allKnowledgeCount** Knowledge Base entries; none closely matched **\"${cleanPrompt.take(80)}\"** yet.")
                    if (continuation.isNotBlank()) {
                        appendLine()
                        appendLine("**Neural Core Output** _(loss=${"%.3f".format(stats.lastLoss)}):_")
                        appendLine("> $continuation")
                    }
                    appendLine()
                    appendLine("- Run `/run` or add a Source URL / GitHub repo to expand grounded knowledge on this topic.")
                }
            }.trim()
        }

        val queryVec = VectorMath.localSemanticEmbedding(cleanPrompt)
        val queryNeuralVec = neuralNet.encodeTextEmbedding(cleanPrompt)
        val rankedEvidence = mutableListOf<Triple<String, KnowledgeEntryEntity, Float>>()
        for (entry in retrievedKnowledge) {
            val sentences = splitSentences("${entry.summary} ${entry.keyFacts}")
            for (s in sentences) {
                if (s.length < 18) continue
                val sem = VectorMath.cosineSimilarity(queryVec, VectorMath.localSemanticEmbedding(s))
                val neuralSim = VectorMath.cosineSimilarity(queryNeuralVec, neuralNet.encodeTextEmbedding(s))
                val lex = VectorMath.keywordOverlapScore(cleanPrompt, s)
                val score = sem * 0.50f + neuralSim * 0.20f + lex * 0.30f
                rankedEvidence.add(Triple(s.trim(), entry, score))
            }
        }
        val topEvidence = rankedEvidence.sortedByDescending { it.third }.take(6)

        val sb = StringBuilder()
        when (intent) {
            IntentKind.CODE -> {
                val primary = retrievedKnowledge.first()
                sb.appendLine("### Implementation Synthesis (${primary.title})")
                sb.appendLine("Grounded in **${retrievedKnowledge.size}** knowledge entries & Neural Core (epoch ${stats.totalEpochs}, loss ${"%.3f".format(stats.lastLoss)}):")
                sb.appendLine()
                topEvidence.take(3).forEachIndexed { idx, (sent, entry, _) ->
                    sb.appendLine("- $sent _([${idx + 1}] ${entry.title})_")
                }
                sb.appendLine()
                sb.appendLine("```kotlin")
                sb.appendLine("// Generated by Agent Lab Core (Neural Epoch ${stats.totalEpochs})")
                sb.appendLine("// Task: ${cleanPrompt.take(72)}")
                sb.appendLine("data class AgentSynthesisResult(")
                sb.appendLine("    val topic: String = \"${primary.title.replace("\"", "'")}\",")
                sb.appendLine("    val keyInsights: List<String> = listOf(")
                topEvidence.take(3).forEach { (s, _, _) ->
                    sb.appendLine("        \"${s.take(90).replace("\"", "'")}\",")
                }
                sb.appendLine("    ),")
                sb.appendLine(")")
                sb.appendLine("```")
            }
            IntentKind.LIST_OR_COMPARE -> {
                sb.appendLine("### Key Findings for \"${cleanPrompt.take(64)}\"")
                sb.appendLine()
                retrievedKnowledge.take(4).forEachIndexed { idx, entry ->
                    sb.appendLine("${idx + 1}. **${entry.title}** — ${entry.summary.trim()}")
                    if (entry.keyFacts.isNotBlank()) {
                        val topFact = entry.keyFacts.lines().firstOrNull { it.isNotBlank() }.orEmpty()
                        if (topFact.isNotBlank()) sb.appendLine("   $topFact")
                    }
                }
            }
            IntentKind.EXPLAIN_OR_GENERAL -> {
                val lead = topEvidence.firstOrNull()?.first ?: retrievedKnowledge.first().summary
                sb.appendLine(lead)
                sb.appendLine()
                if (topEvidence.size > 1) {
                    sb.appendLine("**Supporting Facts from Knowledge Base:**")
                    topEvidence.drop(1).take(4).forEach { (sent, entry, _) ->
                        val refIndex = retrievedKnowledge.indexOfFirst { it.id == entry.id } + 1
                        sb.appendLine("- $sent [${refIndex.coerceAtLeast(1)}]")
                    }
                }
                val neuralContinuation = neuralNet.generateContinuation(cleanPrompt, maxTokens = 20, temperature = temperature)
                if (neuralContinuation.isNotBlank()) {
                    sb.appendLine()
                    sb.appendLine("**Neural Synthesis** _(epoch ${stats.totalEpochs}, loss=${"%.3f".format(stats.lastLoss)}):_ $neuralContinuation")
                } else {
                    val transContinuation = generateTransitionContinuation(cleanPrompt, temperature, maxWords = 18)
                    if (transContinuation.isNotBlank()) {
                        sb.appendLine()
                        sb.appendLine("**Trained Association:** $transContinuation")
                    }
                }
            }
        }

        sb.appendLine()
        sb.appendLine("**Sources Referenced:**")
        retrievedKnowledge.forEachIndexed { idx, entry ->
            val src = entry.sourceUrl.ifBlank { entry.sourceTitle }
            sb.appendLine("[${idx + 1}] ${entry.title} — $src")
        }

        val maxChars = (maxTokens * 4).coerceAtLeast(400)
        return@withContext sb.toString().trim().take(maxChars)
    }

    fun evaluateAccuracy(
        expectedAnswer: String,
        candidateAnswer: String,
    ): Float {
        if (candidateAnswer.isBlank()) return 0f
        val sem = VectorMath.cosineSimilarity(
            VectorMath.localSemanticEmbedding(expectedAnswer),
            VectorMath.localSemanticEmbedding(candidateAnswer),
        ).coerceIn(0f, 1f)

        val expTokens = tokenizeWords(expectedAnswer).filter { it.length >= 3 }.toSet()
        val candTokens = tokenizeWords(candidateAnswer).filter { it.length >= 3 }.toSet()
        val f1 = if (expTokens.isEmpty() || candTokens.isEmpty()) {
            0f
        } else {
            val overlap = expTokens.intersect(candTokens).size.toFloat()
            val precision = overlap / candTokens.size.toFloat()
            val recall = overlap / expTokens.size.toFloat()
            if (precision + recall > 0f) (2f * precision * recall) / (precision + recall) else 0f
        }
        return (sem * 0.6f + f1 * 0.4f).coerceIn(0f, 1f)
    }

    private suspend fun generateTransitionContinuation(
        seedText: String,
        temperature: Float,
        maxWords: Int,
    ): String {
        val seedWords = tokenizeWords(seedText).filter { it.length >= 3 }
        if (seedWords.isEmpty()) return ""
        var prev2 = seedWords.getOrNull(seedWords.size - 2)
        var prev1 = seedWords.last()
        val generated = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        for (step in 0 until maxWords) {
            val candidates = if (prev2 != null) {
                brainDao.getTopNextTokens("$prev2 $prev1", limit = 8)
                    .ifEmpty { brainDao.getTopNextTokens(prev1, limit = 8) }
            } else {
                brainDao.getTopNextTokens(prev1, limit = 8)
            }
            val next = candidates.firstOrNull { it.nextToken !in visited || temperature > 0.8f }
                ?: break
            generated.add(next.nextToken)
            visited.add(next.nextToken)
            prev2 = prev1
            prev1 = next.nextToken
        }
        return if (generated.size >= 4) {
            "${seedWords.takeLast(2).joinToString(" ")} ${generated.joinToString(" ")}."
        } else {
            ""
        }
    }

    private enum class IntentKind { CODE, LIST_OR_COMPARE, EXPLAIN_OR_GENERAL }

    private fun detectIntent(prompt: String): IntentKind {
        val lower = prompt.lowercase()
        return when {
            listOf("code", "kotlin", "compose", "javascript", "function", "script", "implement", "class", "build").any { lower.contains(it) } ->
                IntentKind.CODE
            listOf("list", "compare", "difference", "pros", "cons", "steps", "summarize", "all").any { lower.contains(it) } ->
                IntentKind.LIST_OR_COMPARE
            else -> IntentKind.EXPLAIN_OR_GENERAL
        }
    }

    private fun rankSentencesTextRank(sentences: List<String>): List<String> {
        if (sentences.size <= 2) return sentences
        val vectors = sentences.map { VectorMath.localSemanticEmbedding(it) }
        val n = sentences.size
        val scores = DoubleArray(n) { 1.0 }
        val simMatrix = Array(n) { DoubleArray(n) }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val sim = VectorMath.cosineSimilarity(vectors[i], vectors[j]).coerceAtLeast(0f).toDouble()
                simMatrix[i][j] = sim
                simMatrix[j][i] = sim
            }
        }

        val damping = 0.85
        repeat(8) {
            val next = DoubleArray(n) { 1.0 - damping }
            for (i in 0 until n) {
                var sum = 0.0
                for (j in 0 until n) {
                    if (i == j) continue
                    val outWeight = simMatrix[j].sum()
                    if (outWeight > 1e-6) {
                        sum += (simMatrix[j][i] / outWeight) * scores[j]
                    }
                }
                next[i] += damping * sum
            }
            for (i in 0 until n) scores[i] = next[i]
        }

        return sentences.indices
            .sortedByDescending { scores[it] + ln((sentences[it].length + 10).toDouble()) * 0.05 }
            .map { sentences[it] }
    }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun tokenizeWords(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9_+.-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    fun extractKeywords(text: String, limit: Int = 5): List<String> {
        val stop = setOf("the", "and", "for", "with", "that", "this", "from", "are", "was", "have", "into", "about", "what", "how", "why", "when", "your", "does")
        return tokenizeWords(text)
            .filter { it.length >= 4 && it !in stop }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }
}
