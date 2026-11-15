package com.example.core.ai

import com.example.core.rag.VectorMath
import com.example.data.db.KnowledgeDao
import com.example.data.db.KnowledgeEntryEntity
import com.example.data.settings.AgentSettings
import com.example.data.settings.AiProvider
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatTurn(
    val role: String, // "user" | "assistant"
    val content: String,
)

sealed interface StreamChunk {
    data class Delta(val text: String) : StreamChunk
    data class Completed(val fullText: String, val tokensUsed: Int) : StreamChunk
    data class Error(val message: String) : StreamChunk
}

data class LlmCompletionResult(
    val text: String,
    val tokensUsed: Int,
)

class LlmClient(
    private val settingsRepository: SettingsRepository,
    private val nativeBrain: AgentLabBrain,
    private val knowledgeDao: KnowledgeDao,
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun streamChat(
        history: List<ChatTurn>,
        systemPrompt: String,
        retrievedKnowledge: List<KnowledgeEntryEntity> = emptyList(),
        allKnowledgeCount: Int = 0,
        overrideSettings: AgentSettings? = null,
    ): Flow<StreamChunk> = flow {
        val settings = overrideSettings ?: settingsRepository.getSnapshot()
        if (settings.tokensUsedToday >= settings.dailyTokenBudget) {
            emit(StreamChunk.Error("Daily token budget reached (${settings.tokensUsedToday}/${settings.dailyTokenBudget}). Adjust or reset budget in Settings."))
            return@flow
        }

        // Default self-contained Agent Lab Native AI (Zero API Key, Zero Gemini)
        if (settings.aiProvider == AiProvider.AGENT_LAB_NATIVE) {
            val lastUserPrompt = history.lastOrNull { it.role == "user" }?.content.orEmpty()
            nativeBrain.streamResponse(
                userPrompt = lastUserPrompt,
                systemPrompt = systemPrompt,
                retrievedKnowledge = retrievedKnowledge,
                allKnowledgeCount = allKnowledgeCount,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
            ).collect { chunk ->
                if (chunk is StreamChunk.Completed) {
                    settingsRepository.recordTokenUsage(chunk.tokensUsed)
                }
                emit(chunk)
            }
            return@flow
        }

        if (settings.aiProvider.requiresApiKey && settings.apiKey.isBlank()) {
            emit(StreamChunk.Error("${settings.aiProvider.displayName} requires an API key in Settings, or switch back to Agent Lab Core (No Key)."))
            return@flow
        }

        val request = buildStreamingRequest(settings, history, systemPrompt)
        val fullBuilder = StringBuilder()
        var estimatedTokens = 0

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()?.take(500).orEmpty()
                    emit(StreamChunk.Error("HTTP ${response.code}: $errBody"))
                    return@flow
                }
                val source = response.body?.source() ?: run {
                    emit(StreamChunk.Error("Empty response body"))
                    return@flow
                }

                while (currentCoroutineContext().isActive && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue

                    val parsed = parseSseDelta(payload)
                    if (parsed.delta.isNotEmpty()) {
                        fullBuilder.append(parsed.delta)
                        emit(StreamChunk.Delta(parsed.delta))
                    }
                    if (parsed.totalTokens > 0) {
                        estimatedTokens = parsed.totalTokens
                    }
                }
            }
            val finalText = fullBuilder.toString()
            if (estimatedTokens <= 0) {
                estimatedTokens = ((systemPrompt.length + history.sumOf { it.content.length } + finalText.length) / 4).coerceAtLeast(1)
            }
            settingsRepository.recordTokenUsage(estimatedTokens)
            emit(StreamChunk.Completed(finalText, estimatedTokens))
        } catch (e: Exception) {
            emit(StreamChunk.Error(e.message ?: "Streaming request failed"))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun complete(
        prompt: String,
        systemPrompt: String? = null,
        temperatureOverride: Float? = null,
        maxTokensOverride: Int? = null,
        overrideSettings: AgentSettings? = null,
    ): LlmCompletionResult = withContext(Dispatchers.IO) {
        val settings = overrideSettings ?: settingsRepository.getSnapshot()
        if (settings.tokensUsedToday >= settings.dailyTokenBudget) {
            throw IllegalStateException("Daily token budget exceeded (${settings.tokensUsedToday}/${settings.dailyTokenBudget}).")
        }

        val effectiveSystem = systemPrompt ?: settings.systemPrompt
        if (settings.aiProvider == AiProvider.AGENT_LAB_NATIVE) {
            val allEntries = knowledgeDao.getAll()
            val queryVec = VectorMath.localSemanticEmbedding(prompt)
            val topMatches = allEntries
                .map { entry ->
                    val stored = VectorMath.decodeEmbedding(entry.embeddingJson)
                    val sim = if (stored.size == queryVec.size && stored.isNotEmpty()) {
                        VectorMath.cosineSimilarity(queryVec, stored)
                    } else {
                        VectorMath.cosineSimilarity(
                            queryVec,
                            VectorMath.localSemanticEmbedding("${entry.title}\n${entry.summary}\n${entry.keyFacts}"),
                        )
                    }
                    entry to sim
                }
                .filter { it.second > 0.08f }
                .sortedByDescending { it.second }
                .take(4)
                .map { it.first }

            val text = nativeBrain.generateReply(
                userPrompt = prompt,
                systemPrompt = effectiveSystem,
                retrievedKnowledge = topMatches,
                allKnowledgeCount = allEntries.size,
                temperature = temperatureOverride ?: settings.temperature,
                maxTokens = maxTokensOverride ?: settings.maxTokens,
            )
            val tokens = ((prompt.length + text.length) / 4).coerceAtLeast(1)
            settingsRepository.recordTokenUsage(tokens)
            return@withContext LlmCompletionResult(text, tokens)
        }

        if (settings.aiProvider.requiresApiKey && settings.apiKey.isBlank()) {
            throw IllegalStateException("API key required for ${settings.aiProvider.displayName}. Switch to Agent Lab Core for zero-key AI.")
        }

        val effectiveSettings = settings.copy(
            temperature = temperatureOverride ?: settings.temperature,
            maxTokens = maxTokensOverride ?: settings.maxTokens,
        )
        val request = buildNonStreamingRequest(
            effectiveSettings,
            listOf(ChatTurn("user", prompt)),
            effectiveSystem,
        )
        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("API error HTTP ${response.code}: ${bodyStr.take(600)}")
            }
            val result = parseNonStreamingResponse(bodyStr, prompt, effectiveSystem)
            settingsRepository.recordTokenUsage(result.tokensUsed)
            result
        }
    }

    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        if (settings.aiProvider != AiProvider.AGENT_LAB_NATIVE && settings.apiKey.isNotBlank()) {
            val remote = runCatching { embedWithOpenAiCompatible(settings, text) }.getOrNull()
            if (remote != null && remote.isNotEmpty()) return@withContext remote
        }
        VectorMath.localSemanticEmbedding(text)
    }

    private fun embedWithOpenAiCompatible(settings: AgentSettings, text: String): FloatArray? {
        val url = "${settings.effectiveBaseUrl}/embeddings"
        val payload = buildJsonObject {
            put("model", "text-embedding-3-small")
            put("input", text.take(4000))
        }
        val req = Request.Builder()
            .url(url)
            .apply {
                if (settings.apiKey.isNotBlank()) header("Authorization", "Bearer ${settings.apiKey}")
            }
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            val emb = root["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("embedding")?.jsonArray
                ?: return null
            return emb.mapNotNull { it.jsonPrimitive.floatOrNull }.toFloatArray()
        }
    }

    private fun buildStreamingRequest(
        settings: AgentSettings,
        history: List<ChatTurn>,
        systemPrompt: String,
    ): Request {
        val url = "${settings.effectiveBaseUrl}/chat/completions"
        val body = buildOpenAiBody(settings, history, systemPrompt, stream = true)
        return Request.Builder()
            .url(url)
            .apply {
                if (settings.apiKey.isNotBlank()) header("Authorization", "Bearer ${settings.apiKey}")
            }
            .post(body.toRequestBody(jsonMediaType))
            .build()
    }

    private fun buildNonStreamingRequest(
        settings: AgentSettings,
        history: List<ChatTurn>,
        systemPrompt: String,
    ): Request {
        val url = "${settings.effectiveBaseUrl}/chat/completions"
        val body = buildOpenAiBody(settings, history, systemPrompt, stream = false)
        return Request.Builder()
            .url(url)
            .apply {
                if (settings.apiKey.isNotBlank()) header("Authorization", "Bearer ${settings.apiKey}")
            }
            .post(body.toRequestBody(jsonMediaType))
            .build()
    }

    private fun buildOpenAiBody(
        settings: AgentSettings,
        history: List<ChatTurn>,
        systemPrompt: String,
        stream: Boolean,
    ): String = buildJsonObject {
        put("model", settings.effectiveModel)
        put("temperature", settings.temperature)
        put("max_tokens", settings.maxTokens)
        put("stream", stream)
        put("messages", buildJsonArray {
            if (systemPrompt.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            for (turn in history) {
                add(buildJsonObject {
                    put("role", turn.role)
                    put("content", turn.content)
                })
            }
        })
    }.toString()

    private data class ParsedSse(val delta: String, val totalTokens: Int = 0)

    private fun parseSseDelta(payload: String): ParsedSse {
        return runCatching {
            val obj = json.parseToJsonElement(payload).jsonObject
            val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("delta")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
                .orEmpty()
            val tokens = obj["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: 0
            ParsedSse(delta, tokens)
        }.getOrElse { ParsedSse("") }
    }

    private fun parseNonStreamingResponse(
        bodyStr: String,
        prompt: String,
        systemPrompt: String,
    ): LlmCompletionResult {
        val obj = json.parseToJsonElement(bodyStr).jsonObject
        val text = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
            .orEmpty()
        val tokens = (obj["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: 0)
            .let { if (it > 0) it else ((prompt.length + systemPrompt.length + text.length) / 4).coerceAtLeast(1) }

        return LlmCompletionResult(text = text, tokensUsed = tokens)
    }
}
