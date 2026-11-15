package com.example.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.security.EncryptedSecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_lab_settings")

enum class AiProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean,
) {
    AGENT_LAB_NATIVE(
        displayName = "Agent Lab Core (Built-in, No Key)",
        defaultBaseUrl = "local://agent-lab-core",
        defaultModel = "agent-lab-neural-v1",
        requiresApiKey = false,
    ),
    OPENAI_COMPATIBLE(
        displayName = "Local / OpenAI-Compatible",
        defaultBaseUrl = "http://localhost:11434/v1",
        defaultModel = "llama3.2",
        requiresApiKey = false,
    ),
    CUSTOM_API(
        displayName = "Custom Cloud API",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        requiresApiKey = true,
    ),
}

enum class SearchProvider(val displayName: String, val requiresKey: Boolean) {
    DUCKDUCKGO("DuckDuckGo (No Key)", requiresKey = false),
    TAVILY("Tavily Search API", requiresKey = true),
    SERPER("Serper Google Search", requiresKey = true),
    BRAVE("Brave Search API", requiresKey = true),
}

data class AgentSettings(
    val aiProvider: AiProvider = AiProvider.AGENT_LAB_NATIVE,
    val modelName: String = AiProvider.AGENT_LAB_NATIVE.defaultModel,
    val baseUrl: String = AiProvider.AGENT_LAB_NATIVE.defaultBaseUrl,
    val apiKey: String = "",
    val temperature: Float = 0.4f,
    val maxTokens: Int = 4096,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val searchProvider: SearchProvider = SearchProvider.DUCKDUCKGO,
    val searchApiKey: String = "",
    val githubToken: String = "",
    val githubRepo: String = "",
    val githubBaseBranch: String = "main",
    val maxPagesPerRun: Int = 8,
    val crawlDepth: Int = 1,
    val dailyTokenBudget: Int = 250_000,
    val tokensUsedToday: Int = 0,
    val tokenBudgetDate: String = LocalDate.now().toString(),
    val autoApproveImprovements: Boolean = false,
    val autoCreateUpgradePrOnRun: Boolean = true,
) {
    val effectiveBaseUrl: String
        get() = baseUrl.trim().ifBlank { aiProvider.defaultBaseUrl }.trimEnd('/')

    val effectiveModel: String
        get() = modelName.trim().ifBlank { aiProvider.defaultModel }

    val remainingDailyTokens: Int
        get() = (dailyTokenBudget - tokensUsedToday).coerceAtLeast(0)

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are Agent Lab, an autonomous research and engineering AI. " +
                "Always ground your answers in the provided Knowledge Base context when available, " +
                "cite source URLs clearly, write clean production-ready code, and admit uncertainty rather than fabricating facts."
    }
}

class SettingsRepository(
    private val context: Context,
    private val secretStore: EncryptedSecretStore,
) {
    private object Keys {
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val BASE_URL = stringPreferencesKey("base_url")
        val ENCRYPTED_API_KEY = stringPreferencesKey("enc_api_key")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val SEARCH_PROVIDER = stringPreferencesKey("search_provider")
        val ENCRYPTED_SEARCH_KEY = stringPreferencesKey("enc_search_key")
        val ENCRYPTED_GITHUB_TOKEN = stringPreferencesKey("enc_github_token")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val GITHUB_BASE_BRANCH = stringPreferencesKey("github_base_branch")
        val MAX_PAGES_PER_RUN = intPreferencesKey("max_pages_per_run")
        val CRAWL_DEPTH = intPreferencesKey("crawl_depth")
        val DAILY_TOKEN_BUDGET = intPreferencesKey("daily_token_budget")
        val TOKENS_USED_TODAY = intPreferencesKey("tokens_used_today")
        val TOKEN_BUDGET_DATE = stringPreferencesKey("token_budget_date")
        val AUTO_APPROVE_IMPROVEMENTS = booleanPreferencesKey("auto_approve_improvements")
        val AUTO_PR_ON_RUN = booleanPreferencesKey("auto_pr_on_run")
    }

    val settingsFlow: Flow<AgentSettings> = context.settingsDataStore.data.map { prefs ->
        val provider = prefs[Keys.AI_PROVIDER]
            ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
            ?: AiProvider.AGENT_LAB_NATIVE
        val searchProvider = prefs[Keys.SEARCH_PROVIDER]
            ?.let { runCatching { SearchProvider.valueOf(it) }.getOrNull() }
            ?: SearchProvider.DUCKDUCKGO

        val today = LocalDate.now().toString()
        val storedDate = prefs[Keys.TOKEN_BUDGET_DATE] ?: today
        val usedToday = if (storedDate == today) (prefs[Keys.TOKENS_USED_TODAY] ?: 0) else 0

        AgentSettings(
            aiProvider = provider,
            modelName = prefs[Keys.MODEL_NAME] ?: provider.defaultModel,
            baseUrl = prefs[Keys.BASE_URL] ?: provider.defaultBaseUrl,
            apiKey = secretStore.decrypt(prefs[Keys.ENCRYPTED_API_KEY].orEmpty()),
            temperature = prefs[Keys.TEMPERATURE] ?: 0.4f,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 4096,
            systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: AgentSettings.DEFAULT_SYSTEM_PROMPT,
            searchProvider = searchProvider,
            searchApiKey = secretStore.decrypt(prefs[Keys.ENCRYPTED_SEARCH_KEY].orEmpty()),
            githubToken = secretStore.decrypt(prefs[Keys.ENCRYPTED_GITHUB_TOKEN].orEmpty()),
            githubRepo = prefs[Keys.GITHUB_REPO].orEmpty(),
            githubBaseBranch = prefs[Keys.GITHUB_BASE_BRANCH] ?: "main",
            maxPagesPerRun = prefs[Keys.MAX_PAGES_PER_RUN] ?: 8,
            crawlDepth = prefs[Keys.CRAWL_DEPTH] ?: 1,
            dailyTokenBudget = prefs[Keys.DAILY_TOKEN_BUDGET] ?: 250_000,
            tokensUsedToday = usedToday,
            tokenBudgetDate = today,
            autoApproveImprovements = prefs[Keys.AUTO_APPROVE_IMPROVEMENTS] ?: false,
            autoCreateUpgradePrOnRun = prefs[Keys.AUTO_PR_ON_RUN] ?: true,
        )
    }

    suspend fun getSnapshot(): AgentSettings = settingsFlow.first()

    suspend fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        val current = getSnapshot()
        val updated = transform(current)
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.AI_PROVIDER] = updated.aiProvider.name
            prefs[Keys.MODEL_NAME] = updated.modelName
            prefs[Keys.BASE_URL] = updated.baseUrl
            prefs[Keys.ENCRYPTED_API_KEY] = secretStore.encrypt(updated.apiKey)
            prefs[Keys.TEMPERATURE] = updated.temperature.coerceIn(0f, 2f)
            prefs[Keys.MAX_TOKENS] = updated.maxTokens.coerceIn(128, 32768)
            prefs[Keys.SYSTEM_PROMPT] = updated.systemPrompt
            prefs[Keys.SEARCH_PROVIDER] = updated.searchProvider.name
            prefs[Keys.ENCRYPTED_SEARCH_KEY] = secretStore.encrypt(updated.searchApiKey)
            prefs[Keys.ENCRYPTED_GITHUB_TOKEN] = secretStore.encrypt(updated.githubToken)
            prefs[Keys.GITHUB_REPO] = updated.githubRepo.trim()
            prefs[Keys.GITHUB_BASE_BRANCH] = updated.githubBaseBranch.trim().ifBlank { "main" }
            prefs[Keys.MAX_PAGES_PER_RUN] = updated.maxPagesPerRun.coerceIn(1, 50)
            prefs[Keys.CRAWL_DEPTH] = updated.crawlDepth.coerceIn(0, 3)
            prefs[Keys.DAILY_TOKEN_BUDGET] = updated.dailyTokenBudget.coerceIn(1000, 10_000_000)
            prefs[Keys.TOKENS_USED_TODAY] = updated.tokensUsedToday.coerceAtLeast(0)
            prefs[Keys.TOKEN_BUDGET_DATE] = LocalDate.now().toString()
            prefs[Keys.AUTO_APPROVE_IMPROVEMENTS] = updated.autoApproveImprovements
            prefs[Keys.AUTO_PR_ON_RUN] = updated.autoCreateUpgradePrOnRun
        }
    }

    suspend fun recordTokenUsage(tokens: Int) {
        if (tokens <= 0) return
        val today = LocalDate.now().toString()
        context.settingsDataStore.edit { prefs ->
            val storedDate = prefs[Keys.TOKEN_BUDGET_DATE] ?: today
            val base = if (storedDate == today) (prefs[Keys.TOKENS_USED_TODAY] ?: 0) else 0
            prefs[Keys.TOKEN_BUDGET_DATE] = today
            prefs[Keys.TOKENS_USED_TODAY] = base + tokens
        }
    }

    suspend fun resetDailyTokenUsage() {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TOKEN_BUDGET_DATE] = LocalDate.now().toString()
            prefs[Keys.TOKENS_USED_TODAY] = 0
        }
    }

    suspend fun updateSystemPrompt(newPrompt: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SYSTEM_PROMPT] = newPrompt
        }
    }

    suspend fun setAutoApproveImprovements(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.AUTO_APPROVE_IMPROVEMENTS] = enabled
        }
    }

    suspend fun setAutoCreateUpgradePrOnRun(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.AUTO_PR_ON_RUN] = enabled
        }
    }

    suspend fun updateGitHubRepo(repoSlug: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.GITHUB_REPO] = repoSlug.trim()
        }
    }
}
