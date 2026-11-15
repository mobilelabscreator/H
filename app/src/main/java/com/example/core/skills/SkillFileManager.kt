package com.example.core.skills

import android.content.Context
import com.example.data.db.SkillDao
import com.example.data.db.SkillEntity
import com.example.data.db.SkillVersionEntity
import java.io.File

class SkillFileManager(
    private val context: Context,
    private val skillDao: SkillDao,
) {
    private val skillsDir: File
        get() = File(context.filesDir, "skills").apply { if (!exists()) mkdirs() }

    fun fileForSlug(slug: String): File {
        val safeSlug = sanitizeSlug(slug)
        return File(skillsDir, "$safeSlug.js")
    }

    fun readCode(skill: SkillEntity): String {
        val file = File(skill.filePath)
        return if (file.exists()) file.readText() else ""
    }

    fun writeCodeToDisk(slug: String, code: String): File {
        val file = fileForSlug(slug)
        file.writeText(code)
        return file
    }

    suspend fun ensureStarterSkillsSeeded() {
        val existing = skillDao.getAll()
        if (existing.isNotEmpty()) {
            // Ensure disk files exist for every DB skill
            for (skill in existing) {
                val file = File(skill.filePath)
                if (!file.exists()) {
                    val latest = skillDao.getVersions(skill.id).firstOrNull()
                    if (latest != null) {
                        file.parentFile?.mkdirs()
                        file.writeText(latest.code)
                    }
                }
            }
            return
        }

        createOrUpdateSkill(
            name = "Web Page Deep Summarizer",
            slug = "web_summarizer",
            description = "Fetches a URL, extracts key concepts with the LLM, and stores a clean entry in Knowledge Base.",
            code = STARTER_WEB_SUMMARIZER,
            changeReason = "Initial built-in starter skill",
        )
        createOrUpdateSkill(
            name = "Topic Deep Dive Researcher",
            slug = "topic_deep_dive",
            description = "Searches the web for a topic, fetches top results, synthesizes findings, and saves to Knowledge.",
            code = STARTER_TOPIC_DEEP_DIVE,
            changeReason = "Initial built-in starter skill",
        )
        createOrUpdateSkill(
            name = "Code & Architecture Generator",
            slug = "code_architect",
            description = "Queries relevant Knowledge Base entries and generates modular production code or project blueprints.",
            code = STARTER_CODE_ARCHITECT,
            changeReason = "Initial built-in starter skill",
        )
    }

    suspend fun createOrUpdateSkill(
        name: String,
        slug: String,
        description: String,
        code: String,
        changeReason: String,
    ): SkillEntity {
        val safeSlug = sanitizeSlug(slug)
        val file = writeCodeToDisk(safeSlug, code)
        val now = System.currentTimeMillis()
        val existing = skillDao.getBySlug(safeSlug)

        return if (existing == null) {
            val entity = SkillEntity(
                name = name.trim(),
                slug = safeSlug,
                description = description.trim(),
                filePath = file.absolutePath,
                currentVersion = 1,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            )
            val id = skillDao.insertSkill(entity)
            skillDao.insertVersion(
                SkillVersionEntity(
                    skillId = id,
                    version = 1,
                    code = code,
                    changeReason = changeReason,
                    isWorking = true,
                    createdAt = now,
                ),
            )
            entity.copy(id = id)
        } else {
            val nextVersion = existing.currentVersion + 1
            val updated = existing.copy(
                name = name.trim().ifBlank { existing.name },
                description = description.trim().ifBlank { existing.description },
                filePath = file.absolutePath,
                currentVersion = nextVersion,
                updatedAt = now,
            )
            skillDao.updateSkill(updated)
            skillDao.insertVersion(
                SkillVersionEntity(
                    skillId = existing.id,
                    version = nextVersion,
                    code = code,
                    changeReason = changeReason,
                    isWorking = true,
                    createdAt = now,
                ),
            )
            updated
        }
    }

    suspend fun rollbackToVersion(skill: SkillEntity, version: SkillVersionEntity): SkillEntity {
        val file = writeCodeToDisk(skill.slug, version.code)
        val nextVer = skill.currentVersion + 1
        val now = System.currentTimeMillis()
        val updated = skill.copy(
            filePath = file.absolutePath,
            currentVersion = nextVer,
            lastError = null,
            updatedAt = now,
        )
        skillDao.updateSkill(updated)
        skillDao.insertVersion(
            SkillVersionEntity(
                skillId = skill.id,
                version = nextVer,
                code = version.code,
                changeReason = "Rolled back to v${version.version}",
                isWorking = true,
                createdAt = now,
            ),
        )
        return updated
    }

    suspend fun rollbackToLastWorking(skill: SkillEntity): SkillVersionEntity? {
        val lastWorking = skillDao.getLastWorkingVersion(skill.id) ?: return null
        rollbackToVersion(skill, lastWorking)
        return lastWorking
    }

    companion object {
        fun sanitizeSlug(raw: String): String =
            raw.lowercase()
                .replace(Regex("[^a-z0-9_]+"), "_")
                .trim('_')
                .ifBlank { "custom_skill" }

        private val STARTER_WEB_SUMMARIZER = """
// Skill: Web Page Deep Summarizer
// Input: { "url": "https://..." }
async function main(input) {
  const url = (typeof input === "string" ? input : input?.url) || "";
  if (!url) return { ok: false, error: "Provide a URL in input.url" };

  console_log("Fetching URL: " + url);
  const pageRaw = await fetch_url(url);
  const page = JSON.parse(pageRaw);
  if (page.error) return { ok: false, error: page.error };

  const prompt = "Summarize this document in 4-6 concise bullet points and extract key technical facts.\nTitle: "
    + page.title + "\nContent:\n" + page.text.slice(0, 12000);

  const summary = await call_llm(prompt, "You are a precise technical research analyst.");
  const savedId = await save_knowledge(
    page.title || url,
    summary,
    summary,
    url,
    0.85
  );

  return {
    ok: true,
    savedKnowledgeId: savedId,
    title: page.title,
    summaryPreview: summary.slice(0, 300)
  };
}
""".trimIndent()

        private val STARTER_TOPIC_DEEP_DIVE = """
// Skill: Topic Deep Dive Researcher
// Input: { "topic": "Jetpack Compose adaptive layouts" }
async function main(input) {
  const topic = (typeof input === "string" ? input : input?.topic) || "";
  if (!topic) return { ok: false, error: "Provide a topic string" };

  console_log("Searching web for: " + topic);
  const resultsRaw = await web_search(topic);
  const results = JSON.parse(resultsRaw);
  if (!results.length) return { ok: false, error: "No search results found." };

  const top = results.slice(0, 2);
  const notes = [];
  for (const item of top) {
    console_log("Reading: " + item.url);
    const doc = JSON.parse(await fetch_url(item.url));
    if (!doc.error && doc.text) {
      notes.push("SOURCE: " + item.url + "\n" + doc.text.slice(0, 4500));
    }
  }

  const synthesis = await call_llm(
    "Synthesize clear, accurate technical notes on '" + topic + "' from these sources:\n\n" + notes.join("\n\n---\n\n"),
    "Return concise, factual knowledge grounded strictly in the provided text."
  );

  const savedId = await save_knowledge(
    topic,
    synthesis,
    synthesis,
    top[0].url,
    0.82
  );
  return { ok: true, savedKnowledgeId: savedId, sourcesRead: top.map(x => x.url), synthesis };
}
""".trimIndent()

        private val STARTER_CODE_ARCHITECT = """
// Skill: Code & Architecture Generator
// Input: { "task": "Build a responsive HTML/CSS dashboard widget" }
async function main(input) {
  const task = (typeof input === "string" ? input : input?.task) || "";
  if (!task) return { ok: false, error: "Provide a coding task" };

  console_log("Querying Knowledge Base for context...");
  const kbRaw = await read_knowledge(task, 4);
  const kb = JSON.parse(kbRaw);
  const contextText = kb.map(k => "- " + k.title + ": " + k.summary).join("\n");

  const code = await call_llm(
    "Task: " + task + "\n\nRelevant Knowledge:\n" + contextText + "\n\nProvide complete, clean code with no placeholders.",
    "You are a senior software architect."
  );
  return { ok: true, knowledgeMatches: kb.length, output: code };
}
""".trimIndent()
    }
}
