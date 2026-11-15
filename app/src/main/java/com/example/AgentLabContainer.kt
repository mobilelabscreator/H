package com.example

import android.content.Context
import com.example.core.agent.AutonomousAgentEngine
import com.example.core.ai.AgentLabBrain
import com.example.core.ai.LlmClient
import com.example.core.github.GitHubSelfUpdater
import com.example.core.security.EncryptedSecretStore
import com.example.core.skills.SkillFileManager
import com.example.core.skills.SkillSandboxRunner
import com.example.core.web.WebContentReader
import com.example.core.web.WebSearchClient
import com.example.data.db.AgentLabDatabase
import com.example.data.repository.KnowledgeRepository
import com.example.data.settings.SettingsRepository

class AgentLabContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val database: AgentLabDatabase = AgentLabDatabase.build(appContext)
    val secretStore: EncryptedSecretStore = EncryptedSecretStore()
    val settingsRepository: SettingsRepository = SettingsRepository(appContext, secretStore)
    val agentLabBrain: AgentLabBrain = AgentLabBrain(database.brainDao())
    val llmClient: LlmClient = LlmClient(
        settingsRepository = settingsRepository,
        nativeBrain = agentLabBrain,
        knowledgeDao = database.knowledgeDao(),
    )
    val knowledgeRepository: KnowledgeRepository = KnowledgeRepository(database.knowledgeDao(), llmClient)
    val webSearchClient: WebSearchClient = WebSearchClient(llmClient.httpClient, settingsRepository)
    val webContentReader: WebContentReader = WebContentReader(llmClient.httpClient, settingsRepository)
    val skillFileManager: SkillFileManager = SkillFileManager(appContext, database.skillDao())
    val skillSandboxRunner: SkillSandboxRunner = SkillSandboxRunner(
        skillDao = database.skillDao(),
        skillFileManager = skillFileManager,
        webContentReader = webContentReader,
        webSearchClient = webSearchClient,
        knowledgeRepository = knowledgeRepository,
        llmClient = llmClient,
    )
    val gitHubSelfUpdater: GitHubSelfUpdater = GitHubSelfUpdater(
        httpClient = llmClient.httpClient,
        settingsRepository = settingsRepository,
        llmClient = llmClient,
        gitHubPrDao = database.gitHubPrDao(),
    )
    val autonomousAgentEngine: AutonomousAgentEngine = AutonomousAgentEngine(
        settingsRepository = settingsRepository,
        sourceDao = database.sourceDao(),
        knowledgeRepository = knowledgeRepository,
        skillDao = database.skillDao(),
        skillFileManager = skillFileManager,
        skillSandboxRunner = skillSandboxRunner,
        evalDao = database.evalDao(),
        agentLogDao = database.agentLogDao(),
        proposalDao = database.improvementProposalDao(),
        webContentReader = webContentReader,
        webSearchClient = webSearchClient,
        llmClient = llmClient,
        nativeBrain = agentLabBrain,
    )
}
