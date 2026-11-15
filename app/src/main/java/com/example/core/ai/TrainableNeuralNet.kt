package com.example.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

data class NeuralModelStats(
    val parameterCount: Int,
    val vocabSize: Int,
    val totalEpochs: Int,
    val totalSteps: Long,
    val lastLoss: Float,
    val bestLoss: Float,
    val lastPerplexity: Float,
    val trainedTokens: Long,
)

data class TrainingEpochMetrics(
    val epoch: Int,
    val loss: Float,
    val perplexity: Float,
    val vocabSize: Int,
    val parameterCount: Int,
    val tokensProcessed: Int,
)

/**
 * Real, trainable on-device neural language model written in pure Kotlin.
 *
 * Architecture:
 * - Dynamic token & code-symbol vocabulary (expands up to MAX_VOCAB as the AI reads sources & repos)
 * - Token Embedding Matrix E [vocabSize x D_MODEL]
 * - Positional Context Mixing P [CONTEXT_WINDOW x D_MODEL] (learned attention-like positional decay)
 * - Hidden Feed-Forward Layer W1 [D_MODEL x HIDDEN_DIM] + b1 [HIDDEN_DIM] with LeakyReLU + residual
 * - Output Language Head W2 [HIDDEN_DIM x vocabSize] + b2 [vocabSize] + Softmax
 *
 * Training:
 * - Analytical backpropagation through Softmax Cross-Entropy loss -> W2/b2 -> LeakyReLU -> W1/b1 -> P -> E
 * - Adam optimizer updates with gradient clipping
 * - Binary weight persistence to app storage (`agent_neural_weights.bin`)
 */
class TrainableNeuralNet(
    private val weightsFile: File,
) {
    companion object {
        private const val MAGIC = 0x41474E31 // "AGN1"
        private const val D_MODEL = 48
        private const val HIDDEN_DIM = 64
        private const val CONTEXT_WINDOW = 6
        private const val MAX_VOCAB = 2048
        private const val GRAD_CLIP = 2.0f
        private const val ADAM_BETA1 = 0.9f
        private const val ADAM_BETA2 = 0.999f
        private const val ADAM_EPS = 1e-6f

        private val SPECIAL_TOKENS = listOf("<pad>", "<unk>", "<bos>", "<eos>", "<nl>")
    }

    private val mutex = Mutex()
    private val rng = Random(42)

    private val tokenToId = LinkedHashMap<String, Int>()
    private val idToToken = ArrayList<String>()

    // Dynamic capacity buffers up to MAX_VOCAB
    private var wEmb = Array(MAX_VOCAB) { FloatArray(D_MODEL) }
    private var posWeights = FloatArray(CONTEXT_WINDOW) { idx ->
        (idx + 1).toFloat() / CONTEXT_WINDOW.toFloat()
    }
    private var w1 = Array(D_MODEL) { FloatArray(HIDDEN_DIM) }
    private var b1 = FloatArray(HIDDEN_DIM)
    private var w2 = Array(HIDDEN_DIM) { FloatArray(MAX_VOCAB) }
    private var b2 = FloatArray(MAX_VOCAB)

    // Adam optimizer 1st & 2nd moment buffers for dense core + sparse embeddings/output head
    private var mEmb = Array(MAX_VOCAB) { FloatArray(D_MODEL) }
    private var vEmb = Array(MAX_VOCAB) { FloatArray(D_MODEL) }
    private var mW1 = Array(D_MODEL) { FloatArray(HIDDEN_DIM) }
    private var vW1 = Array(D_MODEL) { FloatArray(HIDDEN_DIM) }
    private var mb1 = FloatArray(HIDDEN_DIM)
    private var vb1 = FloatArray(HIDDEN_DIM)
    private var mW2 = Array(HIDDEN_DIM) { FloatArray(MAX_VOCAB) }
    private var vW2 = Array(HIDDEN_DIM) { FloatArray(MAX_VOCAB) }
    private var mb2 = FloatArray(MAX_VOCAB)
    private var vb2 = FloatArray(MAX_VOCAB)
    private var mPos = FloatArray(CONTEXT_WINDOW)
    private var vPos = FloatArray(CONTEXT_WINDOW)

    private var totalEpochs: Int = 0
    private var totalSteps: Long = 0L
    private var trainedTokens: Long = 0L
    private var lastLoss: Float = 0f
    private var bestLoss: Float = Float.MAX_VALUE
    private var initialized: Boolean = false

    suspend fun ensureInitialized() {
        mutex.withLock {
            if (initialized) return
            if (!loadFromDiskLocked()) {
                initFreshLocked()
            }
            initialized = true
        }
    }

    suspend fun getStats(): NeuralModelStats {
        ensureInitialized()
        return mutex.withLock {
            statsLocked()
        }
    }

    /**
     * Tokenizes [corpus] into subword/word/code tokens, expands vocabulary if space remains,
     * and runs [epochs] full backpropagation passes with Adam optimization.
     */
    suspend fun trainOnCorpus(
        corpus: String,
        epochs: Int = 2,
        learningRate: Float = 0.015f,
        maxTokensPerEpoch: Int = 900,
        onEpochDone: (suspend (TrainingEpochMetrics) -> Unit)? = null,
    ): List<TrainingEpochMetrics> = withContext(Dispatchers.Default) {
        ensureInitialized()
        val rawTokens = tokenize(corpus)
        if (rawTokens.size < 4) return@withContext emptyList()

        val results = mutableListOf<TrainingEpochMetrics>()
        val epochCount = epochs.coerceIn(1, 10)

        for (ep in 1..epochCount) {
            currentCoroutineContext().ensureActive()
            val metrics = mutex.withLock {
                val ids = encodeAndGrowVocabLocked(rawTokens).take(maxTokensPerEpoch)
                if (ids.size < 4) return@withLock null

                var epochLossSum = 0.0
                var samples = 0

                val logits = FloatArray(idToToken.size)
                val probs = FloatArray(idToToken.size)
                val ctxVec = FloatArray(D_MODEL)
                val hPre = FloatArray(HIDDEN_DIM)
                val hAct = FloatArray(HIDDEN_DIM)
                val dHidden = FloatArray(HIDDEN_DIM)
                val dCtx = FloatArray(D_MODEL)

                for (pos in 1 until ids.size) {
                    val targetId = ids[pos]
                    val start = max(0, pos - CONTEXT_WINDOW)
                    val ctxLen = pos - start
                    val vocabNow = idToToken.size

                    // 1. Forward: weighted positional context sum over embeddings
                    ctxVec.fill(0f)
                    var weightNorm = 0f
                    for (k in 0 until ctxLen) {
                        val tokId = ids[start + k]
                        val slot = CONTEXT_WINDOW - ctxLen + k
                        val pW = max(0.05f, posWeights[slot])
                        weightNorm += pW
                        val embRow = wEmb[tokId]
                        for (d in 0 until D_MODEL) {
                            ctxVec[d] += embRow[d] * pW
                        }
                    }
                    val invNorm = 1f / max(1e-4f, weightNorm)
                    for (d in 0 until D_MODEL) ctxVec[d] *= invNorm

                    // Hidden layer: hPre = ctxVec * W1 + b1, LeakyReLU + residual on first D_MODEL dims
                    for (j in 0 until HIDDEN_DIM) {
                        var sum = b1[j]
                        for (d in 0 until D_MODEL) {
                            sum += ctxVec[d] * w1[d][j]
                        }
                        if (j < D_MODEL) sum += ctxVec[j] * 0.25f
                        hPre[j] = sum
                        hAct[j] = if (sum >= 0f) sum else 0.05f * sum
                    }

                    // Output logits: hAct * W2 + b2
                    var maxLogit = Float.NEGATIVE_INFINITY
                    for (v in 0 until vocabNow) {
                        var sum = b2[v]
                        for (j in 0 until HIDDEN_DIM) {
                            sum += hAct[j] * w2[j][v]
                        }
                        logits[v] = sum
                        if (sum > maxLogit) maxLogit = sum
                    }

                    // Softmax + Cross-Entropy loss
                    var expSum = 0.0
                    for (v in 0 until vocabNow) {
                        val e = exp((logits[v] - maxLogit).coerceIn(-20f, 0f).toDouble())
                        probs[v] = e.toFloat()
                        expSum += e
                    }
                    val invExpSum = (1.0 / max(1e-12, expSum)).toFloat()
                    for (v in 0 until vocabNow) probs[v] *= invExpSum

                    val targetProb = max(1e-7f, probs[targetId])
                    epochLossSum += -ln(targetProb.toDouble())
                    samples++
                    totalSteps++
                    trainedTokens++

                    val b1Corr = 1f - pow(ADAM_BETA1, totalSteps)
                    val b2Corr = 1f - pow(ADAM_BETA2, totalSteps)
                    val stepLr = learningRate * sqrt(b2Corr) / max(1e-5f, b1Corr)

                    // 2. Backpropagation
                    dHidden.fill(0f)
                    for (v in 0 until vocabNow) {
                        val dLogit = (probs[v] - if (v == targetId) 1f else 0f).coerceIn(-GRAD_CLIP, GRAD_CLIP)
                        // Skip tiny zero-signal updates for non-target inactive vocab entries to keep speed high
                        if (v != targetId && kotlin.math.abs(dLogit) < 0.0005f) continue

                        // Accumulate dHidden from W2
                        for (j in 0 until HIDDEN_DIM) {
                            dHidden[j] += dLogit * w2[j][v]
                        }

                        // Adam update for b2[v]
                        val mb2New = ADAM_BETA1 * mb2[v] + (1f - ADAM_BETA1) * dLogit
                        val vb2New = ADAM_BETA2 * vb2[v] + (1f - ADAM_BETA2) * dLogit * dLogit
                        mb2[v] = mb2New
                        vb2[v] = vb2New
                        b2[v] -= stepLr * mb2New / (sqrt(vb2New) + ADAM_EPS)

                        // Adam update for W2[*][v]
                        for (j in 0 until HIDDEN_DIM) {
                            val g = (dLogit * hAct[j]).coerceIn(-GRAD_CLIP, GRAD_CLIP)
                            val mNew = ADAM_BETA1 * mW2[j][v] + (1f - ADAM_BETA1) * g
                            val vNew = ADAM_BETA2 * vW2[j][v] + (1f - ADAM_BETA2) * g * g
                            mW2[j][v] = mNew
                            vW2[j][v] = vNew
                            w2[j][v] -= stepLr * mNew / (sqrt(vNew) + ADAM_EPS)
                        }
                    }

                    // Backprop through LeakyReLU + W1 + b1 -> dCtx
                    dCtx.fill(0f)
                    for (j in 0 until HIDDEN_DIM) {
                        val actDeriv = if (hPre[j] >= 0f) 1f else 0.05f
                        val dPre = (dHidden[j] * actDeriv).coerceIn(-GRAD_CLIP, GRAD_CLIP)
                        if (j < D_MODEL) dCtx[j] += dPre * 0.25f

                        val mb1New = ADAM_BETA1 * mb1[j] + (1f - ADAM_BETA1) * dPre
                        val vb1New = ADAM_BETA2 * vb1[j] + (1f - ADAM_BETA2) * dPre * dPre
                        mb1[j] = mb1New
                        vb1[j] = vb1New
                        b1[j] -= stepLr * mb1New / (sqrt(vb1New) + ADAM_EPS)

                        for (d in 0 until D_MODEL) {
                            dCtx[d] += dPre * w1[d][j]
                            val g = (dPre * ctxVec[d]).coerceIn(-GRAD_CLIP, GRAD_CLIP)
                            val mNew = ADAM_BETA1 * mW1[d][j] + (1f - ADAM_BETA1) * g
                            val vNew = ADAM_BETA2 * vW1[d][j] + (1f - ADAM_BETA2) * g * g
                            mW1[d][j] = mNew
                            vW1[d][j] = vNew
                            w1[d][j] -= stepLr * mNew / (sqrt(vNew) + ADAM_EPS)
                        }
                    }

                    // Backprop into active context embeddings & positional weights
                    for (k in 0 until ctxLen) {
                        val tokId = ids[start + k]
                        val slot = CONTEXT_WINDOW - ctxLen + k
                        val pW = max(0.05f, posWeights[slot])
                        val scale = pW * invNorm
                        var posGrad = 0f

                        val embRow = wEmb[tokId]
                        val mRow = mEmb[tokId]
                        val vRow = vEmb[tokId]
                        for (d in 0 until D_MODEL) {
                            posGrad += dCtx[d] * embRow[d] * invNorm
                            val g = (dCtx[d] * scale).coerceIn(-GRAD_CLIP, GRAD_CLIP)
                            val mNew = ADAM_BETA1 * mRow[d] + (1f - ADAM_BETA1) * g
                            val vNew = ADAM_BETA2 * vRow[d] + (1f - ADAM_BETA2) * g * g
                            mRow[d] = mNew
                            vRow[d] = vNew
                            embRow[d] -= stepLr * mNew / (sqrt(vNew) + ADAM_EPS)
                        }

                        val gPos = posGrad.coerceIn(-GRAD_CLIP, GRAD_CLIP)
                        mPos[slot] = ADAM_BETA1 * mPos[slot] + (1f - ADAM_BETA1) * gPos
                        vPos[slot] = ADAM_BETA2 * vPos[slot] + (1f - ADAM_BETA2) * gPos * gPos
                        posWeights[slot] = (posWeights[slot] - stepLr * mPos[slot] / (sqrt(vPos[slot]) + ADAM_EPS))
                            .coerceIn(0.05f, 3.0f)
                    }
                }

                totalEpochs++
                val avgLoss = (epochLossSum / max(1, samples)).toFloat()
                val ppl = exp(avgLoss.coerceAtMost(12f).toDouble()).toFloat()
                lastLoss = avgLoss
                if (avgLoss < bestLoss) bestLoss = avgLoss

                TrainingEpochMetrics(
                    epoch = totalEpochs,
                    loss = avgLoss,
                    perplexity = ppl,
                    vocabSize = idToToken.size,
                    parameterCount = computeParamCountLocked(),
                    tokensProcessed = ids.size,
                )
            }
            if (metrics != null) {
                results.add(metrics)
                onEpochDone?.invoke(metrics)
            }
        }

        mutex.withLock { saveToDiskLocked() }
        results
    }

    /**
     * Generates a neural continuation from [prompt] using trained forward-pass logits + temperature sampling.
     */
    suspend fun generateContinuation(
        prompt: String,
        maxTokens: Int = 24,
        temperature: Float = 0.45f,
    ): String = withContext(Dispatchers.Default) {
        ensureInitialized()
        mutex.withLock {
            if (totalSteps < 8L || idToToken.size <= SPECIAL_TOKENS.size) return@withLock ""
            val seedTokens = tokenize(prompt)
            val contextIds = seedTokens.mapNotNull { tokenToId[it] }.toMutableList()
            if (contextIds.isEmpty()) return@withLock ""

            val generated = mutableListOf<String>()
            val recentSet = mutableSetOf<Int>()
            val vocabNow = idToToken.size
            val logits = FloatArray(vocabNow)
            val ctxVec = FloatArray(D_MODEL)
            val hAct = FloatArray(HIDDEN_DIM)

            repeat(maxTokens) {
                val start = max(0, contextIds.size - CONTEXT_WINDOW)
                val ctxLen = contextIds.size - start
                if (ctxLen == 0) return@repeat

                ctxVec.fill(0f)
                var weightNorm = 0f
                for (k in 0 until ctxLen) {
                    val tokId = contextIds[start + k]
                    val slot = CONTEXT_WINDOW - ctxLen + k
                    val pW = max(0.05f, posWeights[slot])
                    weightNorm += pW
                    val embRow = wEmb[tokId]
                    for (d in 0 until D_MODEL) {
                        ctxVec[d] += embRow[d] * pW
                    }
                }
                val invNorm = 1f / max(1e-4f, weightNorm)
                for (d in 0 until D_MODEL) ctxVec[d] *= invNorm

                for (j in 0 until HIDDEN_DIM) {
                    var sum = b1[j]
                    for (d in 0 until D_MODEL) sum += ctxVec[d] * w1[d][j]
                    if (j < D_MODEL) sum += ctxVec[j] * 0.25f
                    hAct[j] = if (sum >= 0f) sum else 0.05f * sum
                }

                for (v in 0 until vocabNow) {
                    var sum = b2[v]
                    for (j in 0 until HIDDEN_DIM) sum += hAct[j] * w2[j][v]
                    logits[v] = sum
                }

                val chosenId = sampleTopK(logits, recentSet, temperature)
                if (chosenId < SPECIAL_TOKENS.size) return@repeat

                val tok = idToToken[chosenId]
                generated.add(tok)
                recentSet.add(chosenId)
                if (recentSet.size > 6) recentSet.remove(recentSet.first())
                contextIds.add(chosenId)
            }

            if (generated.size < 4) "" else detokenize(generated)
        }
    }

    /**
     * Computes a learned dense representation of [text] using trained embeddings.
     */
    suspend fun encodeTextEmbedding(text: String): FloatArray {
        ensureInitialized()
        return mutex.withLock {
            val tokens = tokenize(text)
            val vec = FloatArray(D_MODEL)
            var count = 0
            for (t in tokens) {
                val id = tokenToId[t] ?: continue
                val row = wEmb[id]
                for (d in 0 until D_MODEL) vec[d] += row[d]
                count++
            }
            if (count > 0) {
                var normSq = 0f
                for (d in 0 until D_MODEL) normSq += vec[d] * vec[d]
                val norm = sqrt(normSq)
                if (norm > 1e-6f) {
                    for (d in 0 until D_MODEL) vec[d] /= norm
                }
            }
            vec
        }
    }

    private fun sampleTopK(logits: FloatArray, recentIds: Set<Int>, temperature: Float): Int {
        val temp = temperature.coerceIn(0.1f, 1.5f)
        val candidates = (SPECIAL_TOKENS.size until logits.size)
            .map { id ->
                val penalty = if (id in recentIds) 1.4f else 0f
                id to ((logits[id] - penalty) / temp)
            }
            .sortedByDescending { it.second }
            .take(5)
        if (candidates.isEmpty()) return 0
        if (temp <= 0.25f) return candidates.first().first

        val maxVal = candidates.first().second
        val probs = candidates.map { exp((it.second - maxVal).coerceIn(-15f, 0f).toDouble()) }
        val total = probs.sum()
        var r = rng.nextDouble() * total
        for (i in candidates.indices) {
            r -= probs[i]
            if (r <= 0.0) return candidates[i].first
        }
        return candidates.first().first
    }

    private fun encodeAndGrowVocabLocked(tokens: List<String>): List<Int> {
        val ids = ArrayList<Int>(tokens.size)
        for (tok in tokens) {
            val existing = tokenToId[tok]
            if (existing != null) {
                ids.add(existing)
            } else if (idToToken.size < MAX_VOCAB) {
                val newId = idToToken.size
                tokenToId[tok] = newId
                idToToken.add(tok)
                initTokenSlotLocked(newId)
                ids.add(newId)
            } else {
                ids.add(1) // <unk>
            }
        }
        return ids
    }

    private fun initFreshLocked() {
        tokenToId.clear()
        idToToken.clear()
        SPECIAL_TOKENS.forEachIndexed { idx, tok ->
            tokenToId[tok] = idx
            idToToken.add(tok)
            initTokenSlotLocked(idx)
        }
        val scale1 = sqrt(2.0 / D_MODEL).toFloat()
        for (d in 0 until D_MODEL) {
            for (j in 0 until HIDDEN_DIM) {
                w1[d][j] = ((rng.nextFloat() * 2f - 1f) * scale1)
            }
        }
        b1.fill(0f)
        for (k in 0 until CONTEXT_WINDOW) {
            posWeights[k] = (k + 1).toFloat() / CONTEXT_WINDOW.toFloat()
        }
        totalEpochs = 0
        totalSteps = 0L
        trainedTokens = 0L
        lastLoss = 0f
        bestLoss = Float.MAX_VALUE
    }

    private fun initTokenSlotLocked(id: Int) {
        val embScale = 0.25f
        for (d in 0 until D_MODEL) {
            wEmb[id][d] = (rng.nextFloat() * 2f - 1f) * embScale
            mEmb[id][d] = 0f
            vEmb[id][d] = 0f
        }
        val outScale = sqrt(1.0 / HIDDEN_DIM).toFloat()
        for (j in 0 until HIDDEN_DIM) {
            w2[j][id] = (rng.nextFloat() * 2f - 1f) * outScale
            mW2[j][id] = 0f
            vW2[j][id] = 0f
        }
        b2[id] = 0f
        mb2[id] = 0f
        vb2[id] = 0f
    }

    private fun statsLocked(): NeuralModelStats {
        val ppl = if (lastLoss > 0f) exp(lastLoss.coerceAtMost(12f).toDouble()).toFloat() else 0f
        return NeuralModelStats(
            parameterCount = computeParamCountLocked(),
            vocabSize = idToToken.size,
            totalEpochs = totalEpochs,
            totalSteps = totalSteps,
            lastLoss = lastLoss,
            bestLoss = if (bestLoss == Float.MAX_VALUE) 0f else bestLoss,
            lastPerplexity = ppl,
            trainedTokens = trainedTokens,
        )
    }

    private fun computeParamCountLocked(): Int {
        val v = idToToken.size
        return (v * D_MODEL) + CONTEXT_WINDOW + (D_MODEL * HIDDEN_DIM) + HIDDEN_DIM + (HIDDEN_DIM * v) + v
    }

    private fun saveToDiskLocked() {
        runCatching {
            weightsFile.parentFile?.mkdirs()
            val tmp = File(weightsFile.parentFile, "${weightsFile.name}.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(totalEpochs)
                out.writeLong(totalSteps)
                out.writeLong(trainedTokens)
                out.writeFloat(lastLoss)
                out.writeFloat(bestLoss)

                val v = idToToken.size
                out.writeInt(v)
                for (tok in idToToken) out.writeUTF(tok)

                for (k in 0 until CONTEXT_WINDOW) out.writeFloat(posWeights[k])
                for (d in 0 until D_MODEL) {
                    for (j in 0 until HIDDEN_DIM) out.writeFloat(w1[d][j])
                }
                for (j in 0 until HIDDEN_DIM) out.writeFloat(b1[j])
                for (i in 0 until v) {
                    for (d in 0 until D_MODEL) out.writeFloat(wEmb[i][d])
                    for (j in 0 until HIDDEN_DIM) out.writeFloat(w2[j][i])
                    out.writeFloat(b2[i])
                }
            }
            if (weightsFile.exists()) weightsFile.delete()
            tmp.renameTo(weightsFile)
        }
    }

    private fun loadFromDiskLocked(): Boolean {
        if (!weightsFile.exists()) return false
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(weightsFile))).use { inp ->
                if (inp.readInt() != MAGIC) return false
                totalEpochs = inp.readInt()
                totalSteps = inp.readLong()
                trainedTokens = inp.readLong()
                lastLoss = inp.readFloat()
                bestLoss = inp.readFloat()

                val v = inp.readInt().coerceIn(SPECIAL_TOKENS.size, MAX_VOCAB)
                tokenToId.clear()
                idToToken.clear()
                for (i in 0 until v) {
                    val tok = inp.readUTF()
                    tokenToId[tok] = i
                    idToToken.add(tok)
                }

                for (k in 0 until CONTEXT_WINDOW) posWeights[k] = inp.readFloat()
                for (d in 0 until D_MODEL) {
                    for (j in 0 until HIDDEN_DIM) w1[d][j] = inp.readFloat()
                }
                for (j in 0 until HIDDEN_DIM) b1[j] = inp.readFloat()
                for (i in 0 until v) {
                    for (d in 0 until D_MODEL) wEmb[i][d] = inp.readFloat()
                    for (j in 0 until HIDDEN_DIM) w2[j][i] = inp.readFloat()
                    b2[i] = inp.readFloat()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val matcher = Regex("""[A-Za-z_][A-Za-z0-9_]*|[0-9]+(?:\.[0-9]+)?|\n|[{}()\[\].:,;=+\-*/<>!]""")
        for (m in matcher.findAll(text)) {
            val raw = m.value
            out.add(if (raw == "\n") "<nl>" else raw.lowercase())
        }
        return out
    }

    private fun detokenize(tokens: List<String>): String {
        val sb = StringBuilder()
        val noSpaceBefore = setOf(".", ",", ":", ";", ")", "]", "}", "<nl>")
        val noSpaceAfter = setOf("(", "[", "{", ".", "<nl>")
        var prev = ""
        for (t in tokens) {
            if (t == "<nl>") {
                sb.append(' ')
                prev = t
                continue
            }
            if (sb.isNotEmpty() && t !in noSpaceBefore && prev !in noSpaceAfter) {
                sb.append(' ')
            }
            sb.append(t)
            prev = t
        }
        return sb.toString().trim()
    }

    private fun pow(base: Float, exp: Long): Float {
        if (exp > 1000L) return 0f
        var res = 1.0
        val b = base.toDouble()
        for (i in 0 until exp.toInt()) {
            res *= b
            if (res < 1e-7) return 0f
        }
        return res.toFloat()
    }
}
