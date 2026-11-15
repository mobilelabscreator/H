package com.example.ui.screens.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AgentLabContainer
import com.example.core.ai.LlmClient
import com.example.core.skills.SkillExecutionResult
import com.example.core.skills.SkillFileManager
import com.example.core.skills.SkillSandboxRunner
import com.example.data.db.SkillDao
import com.example.data.db.SkillEntity
import com.example.data.db.SkillVersionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SkillsUiState(
    val selectedSkillId: Long? = null,
    val editorCode: String = "",
    val isRunning: Boolean = false,
    val isGenerating: Boolean = false,
    val lastExecution: SkillExecutionResult? = null,
    val statusMessage: String? = null,
)

class SkillsViewModel(
    private val skillDao: SkillDao,
    private val skillFileManager: SkillFileManager,
    private val skillSandboxRunner: SkillSandboxRunner,
    private val llmClient: LlmClient,
) : ViewModel() {

    val skills: StateFlow<List<SkillEntity>> = skillDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val selectedVersions: StateFlow<List<SkillVersionEntity>> = _uiState
        .flatMapLatest { state ->
            val id = state.selectedSkillId
            if (id == null) flowOf(emptyList()) else skillDao.observeVersions(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            skillFileManager.ensureStarterSkillsSeeded()
            val all = skillDao.getAll()
            if (all.isNotEmpty() && _uiState.value.selectedSkillId == null) {
                selectSkill(all.first())
            }
        }
    }

    fun selectSkill(skill: SkillEntity) {
        val code = skillFileManager.readCode(skill)
        _uiState.value = _uiState.value.copy(
            selectedSkillId = skill.id,
            editorCode = code,
            lastExecution = null,
            statusMessage = null,
        )
    }

    fun onEditorCodeChanged(newCode: String) {
        _uiState.value = _uiState.value.copy(editorCode = newCode)
    }

    fun saveCurrentSkill(reason: String = "Manual editor save") {
        val id = _uiState.value.selectedSkillId ?: return
        val code = _uiState.value.editorCode
        viewModelScope.launch {
            val skill = skillDao.getById(id) ?: return@launch
            val updated = skillFileManager.createOrUpdateSkill(
                name = skill.name,
                slug = skill.slug,
                description = skill.description,
                code = code,
                changeReason = reason,
            )
            _uiState.value = _uiState.value.copy(
                statusMessage = "Saved ${updated.slug}.js (v${updated.currentVersion}) and hot-reloaded.",
            )
        }
    }

    fun runCurrentSkill(inputJson: String) {
        val id = _uiState.value.selectedSkillId ?: return
        if (_uiState.value.isRunning) return
        viewModelScope.launch {
            val skill = skillDao.getById(id) ?: return@launch
            // Hot-save current editor text before executing if modified
            val diskCode = skillFileManager.readCode(skill)
            val activeSkill = if (_uiState.value.editorCode != diskCode) {
                skillFileManager.createOrUpdateSkill(
                    name = skill.name,
                    slug = skill.slug,
                    description = skill.description,
                    code = _uiState.value.editorCode,
                    changeReason = "Hot-loaded from editor before run",
                )
            } else {
                skill
            }

            _uiState.value = _uiState.value.copy(isRunning = true, lastExecution = null, statusMessage = null)
            val result = skillSandboxRunner.runSkill(activeSkill, inputJson = inputJson)
            val refreshed = skillDao.getById(activeSkill.id)
            val updatedCode = if (refreshed != null) skillFileManager.readCode(refreshed) else _uiState.value.editorCode
            val msg = when {
                result.rolledBackToVersion != null ->
                    "Skill crashed and automatically rolled back to working v${result.rolledBackToVersion}."
                result.success ->
                    "Executed ${activeSkill.slug}.js cleanly."
                else ->
                    "Skill failed: ${result.error}"
            }
            _uiState.value = _uiState.value.copy(
                isRunning = false,
                editorCode = updatedCode,
                lastExecution = result,
                statusMessage = msg,
            )
        }
    }

    fun rollbackToVersion(version: SkillVersionEntity) {
        val id = _uiState.value.selectedSkillId ?: return
        viewModelScope.launch {
            val skill = skillDao.getById(id) ?: return@launch
            val updated = skillFileManager.rollbackToVersion(skill, version)
            _uiState.value = _uiState.value.copy(
                editorCode = version.code,
                statusMessage = "Rolled back ${updated.slug}.js to v${version.version}.",
            )
        }
    }

    fun createNewSkill(name: String, description: String) {
        if (name.isBlank()) return
        val slug = SkillFileManager.sanitizeSlug(name)
        val template = """
// Skill: ${name.trim()}
// Built-in tools: fetch_url(url), web_search(q), read_knowledge(q, limit),
// save_knowledge(title, summary, facts, url, conf), call_llm(prompt, system), run_skill(slug, json)
async function main(input) {
  console_log("Running $slug...");
  return { ok: true, input };
}
""".trimIndent()
        viewModelScope.launch {
            val created = skillFileManager.createOrUpdateSkill(
                name = name.trim(),
                slug = slug,
                description = description.trim().ifBlank { "Custom QuickJS skill" },
                code = template,
                changeReason = "Created new skill",
            )
            selectSkill(created)
        }
    }

    fun generateSkillWithAi(goalDescription: String) {
        if (goalDescription.isBlank() || _uiState.value.isGenerating) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGenerating = true, statusMessage = null)
            runCatching {
                val prompt = """
                    Write a complete, production-quality QuickJS JavaScript skill for Agent Lab.
                    Goal: $goalDescription

                    Sandbox rules:
                    - Must define `async function main(input) { ... }` and return a JSON-serializable object or string.
                    - Available async built-in tools ONLY:
                      - `await fetch_url(url)` -> returns JSON string `{url, title, contentType, text, links, error}`
                      - `await web_search(query)` -> returns JSON string array of `{title, url, snippet}`
                      - `await read_knowledge(query, limit)` -> returns JSON string array of `{id, title, summary, keyFacts, sourceUrl, confidence, score}`
                      - `await save_knowledge(title, summary, keyFacts, sourceUrl, confidence)` -> returns saved entry id string
                      - `await call_llm(prompt, systemPrompt)` -> returns string response
                      - `await run_skill(slug, inputJson)` -> returns string output
                      - `console_log(msg)` -> logs message
                    - No Node/browser globals (no `fetch`, `require`, `window`, `fs`).
                    Return ONLY raw JavaScript code with no markdown code fences.
                """.trimIndent()
                val raw = llmClient.complete(prompt, "Output valid QuickJS JavaScript only.").text
                val extractedFence = Regex("```(?:javascript|js)?\\s*\\n([\\s\\S]*?)```")
                    .find(raw)?.groupValues?.getOrNull(1)?.trim()
                val candidate = (extractedFence ?: raw)
                    .removePrefix("```javascript")
                    .removePrefix("```js")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                val cleanCode = if (candidate.contains("function main")) {
                    candidate
                } else {
                    val safeGoal = goalDescription.trim().replace("\"", "'")
                    """
// Skill: $safeGoal
async function main(input) {
  const query = (typeof input === "string" ? input : input?.task || input?.topic || input?.query) || "$safeGoal";
  console_log("Executing skill for: " + query);
  const kb = JSON.parse(await read_knowledge(query, 4));
  const hits = JSON.parse(await web_search(query));
  let webNotes = "";
  if (hits.length > 0) {
    const page = JSON.parse(await fetch_url(hits[0].url));
    if (!page.error && page.text) {
      webNotes = page.text.slice(0, 5000);
      await save_knowledge(page.title || query, webNotes.slice(0, 500), webNotes.slice(0, 900), hits[0].url, 0.8);
    }
  }
  const synthesis = await call_llm(
    "Goal: $safeGoal\nInput: " + query + "\nKB matches: " + kb.length + "\nWeb excerpt: " + webNotes.slice(0, 2000),
    "Synthesize a clear, actionable result."
  );
  return { ok: true, goal: "$safeGoal", knowledgeHits: kb.length, webHits: hits.length, result: synthesis };
}
""".trimIndent()
                }
                val name = goalDescription.trim().take(36)
                val slug = SkillFileManager.sanitizeSlug(name)
                val saved = skillFileManager.createOrUpdateSkill(
                    name = name,
                    slug = slug,
                    description = goalDescription.trim(),
                    code = cleanCode,
                    changeReason = "Generated by AI from prompt",
                )
                selectSkill(saved)
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    statusMessage = "AI created skill '${saved.slug}.js'.",
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    statusMessage = "AI skill generation failed: ${err.message}",
                )
            }
        }
    }

    fun deleteSkill(skill: SkillEntity) {
        viewModelScope.launch {
            skillDao.deleteSkill(skill)
            val remaining = skillDao.getAll()
            if (remaining.isNotEmpty()) {
                selectSkill(remaining.first())
            } else {
                _uiState.value = _uiState.value.copy(selectedSkillId = null, editorCode = "")
            }
        }
    }

    companion object {
        fun factory(container: AgentLabContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SkillsViewModel(
                        skillDao = container.database.skillDao(),
                        skillFileManager = container.skillFileManager,
                        skillSandboxRunner = container.skillSandboxRunner,
                        llmClient = container.llmClient,
                    ) as T
            }
    }
}
