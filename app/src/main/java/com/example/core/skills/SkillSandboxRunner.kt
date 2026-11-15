package com.example.core.skills

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import com.example.core.ai.LlmClient
import com.example.core.web.WebContentReader
import com.example.core.web.WebSearchClient
import com.example.data.db.SkillDao
import com.example.data.db.SkillEntity
import com.example.data.repository.KnowledgeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SkillExecutionResult(
    val success: Boolean,
    val output: String,
    val logs: List<String>,
    val error: String? = null,
    val rolledBackToVersion: Int? = null,
)

class SkillSandboxRunner(
    private val skillDao: SkillDao,
    private val skillFileManager: SkillFileManager,
    private val webContentReader: WebContentReader,
    private val webSearchClient: WebSearchClient,
    private val knowledgeRepository: KnowledgeRepository,
    private val llmClient: LlmClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun runSkill(
        skill: SkillEntity,
        inputJson: String = "{}",
        timeoutMs: Long = 25_000L,
        recursionDepth: Int = 0,
        rollbackOnCrash: Boolean = true,
    ): SkillExecutionResult {
        val code = skillFileManager.readCode(skill)
        val result = executeCode(
            code = code,
            inputJson = inputJson,
            timeoutMs = timeoutMs,
            recursionDepth = recursionDepth,
        )

        val now = System.currentTimeMillis()
        if (result.success) {
            skillDao.updateSkill(
                skill.copy(
                    lastRunAt = now,
                    lastRunOutput = result.output.take(4000),
                    lastError = null,
                    updatedAt = now,
                ),
            )
            return result
        }

        // Crash / failure path -> mark current version non-working & auto-rollback to last working version
        val latestVer = skillDao.getVersions(skill.id).firstOrNull()
        if (latestVer != null && latestVer.version == skill.currentVersion) {
            skillDao.setVersionWorking(latestVer.id, isWorking = false)
        }

        var rolledBackVersion: Int? = null
        if (rollbackOnCrash) {
            val restored = skillFileManager.rollbackToLastWorking(skill)
            if (restored != null) {
                rolledBackVersion = restored.version
            }
        }

        skillDao.updateSkill(
            skill.copy(
                lastRunAt = now,
                lastError = result.error,
                updatedAt = now,
            ),
        )
        return result.copy(rolledBackToVersion = rolledBackVersion)
    }

    /**
     * Executes arbitrary JS skill code inside a strict QuickJS sandbox.
     * Exposed globals are strictly limited to:
     * fetch_url, web_search, read_knowledge, save_knowledge, call_llm, run_skill, console_log.
     * No filesystem, contacts, Android Context, or external app APIs are bound.
     */
    suspend fun executeCode(
        code: String,
        inputJson: String = "{}",
        timeoutMs: Long = 25_000L,
        recursionDepth: Int = 0,
    ): SkillExecutionResult = withContext(Dispatchers.Default) {
        if (recursionDepth > MAX_RECURSION_DEPTH) {
            return@withContext SkillExecutionResult(
                success = false,
                output = "",
                logs = emptyList(),
                error = "Skill recursion depth limit ($MAX_RECURSION_DEPTH) exceeded.",
            )
        }
        val logs = mutableListOf<String>()
        try {
            val output = withTimeout(timeoutMs) {
                quickJs {
                    memoryLimit = 16L * 1024L * 1024L // 16 MB JS heap cap
                    maxStackSize = 512L * 1024L // 512 KB stack cap

                    function("console_log") { args ->
                        val line = args.joinToString(" ") { it?.toString() ?: "null" }.take(1000)
                        if (logs.size < 100) logs.add(line)
                    }

                    asyncFunction("fetch_url") { args ->
                        val url = args.firstOrNull()?.toString().orEmpty()
                        if (url.isBlank()) {
                            return@asyncFunction """{"error":"Empty URL"}"""
                        }
                        runCatching {
                            val doc = webContentReader.readUrl(url)
                            buildJsonObject {
                                put("url", doc.url)
                                put("title", doc.title)
                                put("contentType", doc.contentType)
                                put("text", doc.textContent.take(18_000))
                                put("links", buildJsonArray {
                                    doc.outboundLinks.take(10).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                                })
                            }.toString()
                        }.getOrElse { err ->
                            buildJsonObject { put("error", err.message ?: "fetch_url failed") }.toString()
                        }
                    }

                    asyncFunction("web_search") { args ->
                        val query = args.firstOrNull()?.toString().orEmpty()
                        if (query.isBlank()) return@asyncFunction "[]"
                        runCatching {
                            val items = webSearchClient.search(query, maxResults = 6)
                            json.encodeToString(items)
                        }.getOrElse { "[]" }
                    }

                    asyncFunction("read_knowledge") { args ->
                        val query = args.firstOrNull()?.toString().orEmpty()
                        val limit = args.getOrNull(1)?.toString()?.toIntOrNull()?.coerceIn(1, 15) ?: 5
                        val matches = knowledgeRepository.retrieveRelevant(query, topK = limit, minScore = 0.05f)
                        buildJsonArray {
                            matches.forEach { m ->
                                add(buildJsonObject {
                                    put("id", m.entry.id)
                                    put("title", m.entry.title)
                                    put("summary", m.entry.summary)
                                    put("keyFacts", m.entry.keyFacts)
                                    put("sourceUrl", m.entry.sourceUrl)
                                    put("confidence", m.entry.confidence)
                                    put("score", m.score)
                                })
                            }
                        }.toString()
                    }

                    asyncFunction("save_knowledge") { args ->
                        val title = args.getOrNull(0)?.toString().orEmpty()
                        val summary = args.getOrNull(1)?.toString().orEmpty()
                        val keyFacts = args.getOrNull(2)?.toString().orEmpty()
                        val sourceUrl = args.getOrNull(3)?.toString().orEmpty()
                        val confidence = args.getOrNull(4)?.toString()?.toFloatOrNull() ?: 0.8f
                        val id = knowledgeRepository.saveWithDeduplication(
                            title = title,
                            summary = summary,
                            keyFacts = keyFacts,
                            sourceUrl = sourceUrl,
                            sourceTitle = title,
                            confidence = confidence,
                        )
                        id.toString()
                    }

                    asyncFunction("call_llm") { args ->
                        val prompt = args.getOrNull(0)?.toString().orEmpty()
                        val sysPrompt = args.getOrNull(1)?.toString()?.takeIf { it.isNotBlank() }
                        val res = llmClient.complete(prompt = prompt, systemPrompt = sysPrompt)
                        res.text
                    }

                    asyncFunction("run_skill") { args ->
                        val targetSlug = args.getOrNull(0)?.toString().orEmpty()
                        val subInput = args.getOrNull(1)?.toString() ?: "{}"
                        val targetSkill = skillDao.getBySlug(SkillFileManager.sanitizeSlug(targetSlug))
                            ?: return@asyncFunction """{"ok":false,"error":"Skill not found: $targetSlug"}"""
                        val subResult = runSkill(
                            skill = targetSkill,
                            inputJson = subInput,
                            timeoutMs = timeoutMs,
                            recursionDepth = recursionDepth + 1,
                            rollbackOnCrash = false,
                        )
                        if (subResult.success) subResult.output else """{"ok":false,"error":${json.encodeToString(subResult.error ?: "Failed")}}"""
                    }

                    val safeInputLiteral = json.encodeToString(inputJson)
                    val wrapperScript = """
                        $code

                        (async () => {
                          let parsedInput = {};
                          const rawIn = $safeInputLiteral;
                          try {
                            parsedInput = rawIn ? JSON.parse(rawIn) : {};
                          } catch (_) {
                            parsedInput = rawIn;
                          }
                          if (typeof main !== "function") {
                            throw new Error("Skill must define an async function main(input)");
                          }
                          const out = await main(parsedInput);
                          return typeof out === "string" ? out : JSON.stringify(out, null, 2);
                        })();
                    """.trimIndent()

                    evaluate<Any?>(wrapperScript)?.toString() ?: ""
                }
            }
            SkillExecutionResult(
                success = true,
                output = output.take(32_000),
                logs = logs,
            )
        } catch (e: Throwable) {
            SkillExecutionResult(
                success = false,
                output = "",
                logs = logs,
                error = e.message ?: e::class.java.simpleName,
            )
        }
    }

    companion object {
        private const val MAX_RECURSION_DEPTH = 3
    }
}
