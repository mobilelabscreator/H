package com.example.ui.screens.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AgentLabContainer
import com.example.core.agent.AgentRunState
import com.example.core.agent.AutonomousAgentEngine
import com.example.core.ai.AgentLabBrain
import com.example.core.ai.NeuralModelStats
import com.example.core.github.GitHubSelfUpdater
import com.example.core.github.RepoWorkspaceManager
import com.example.core.github.WorkspaceSummary
import com.example.data.db.AgentLogDao
import com.example.data.db.AgentLogEntity
import com.example.data.db.BrainDao
import com.example.data.db.EvalDao
import com.example.data.db.EvalQuestionEntity
import com.example.data.db.GitHubPrEntity
import com.example.data.db.ImprovementProposalDao
import com.example.data.db.ImprovementProposalEntity
import com.example.data.db.TrainingCheckpointEntity
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AgentViewModel(
    private val engine: AutonomousAgentEngine,
    private val agentLogDao: AgentLogDao,
    private val proposalDao: ImprovementProposalDao,
    private val evalDao: EvalDao,
    private val brainDao: BrainDao,
    private val nativeBrain: AgentLabBrain,
    private val workspaceManager: RepoWorkspaceManager,
    private val settingsRepository: SettingsRepository,
    private val gitHubSelfUpdater: GitHubSelfUpdater,
) : ViewModel() {

    val runState: StateFlow<AgentRunState> = engine.runState
    val brainTransitions: StateFlow<Int> = brainDao.observeTransitionCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val checkpoints: StateFlow<List<TrainingCheckpointEntity>> = nativeBrain.checkpointsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val workspaceSummary: StateFlow<WorkspaceSummary> = workspaceManager.summary

    private val _neuralStats = MutableStateFlow<NeuralModelStats?>(null)
    val neuralStats: StateFlow<NeuralModelStats?> = _neuralStats.asStateFlow()

    private val _isTraining = MutableStateFlow(false)
    val isTraining: StateFlow<Boolean> = _isTraining.asStateFlow()

    val logs: StateFlow<List<AgentLogEntity>> = agentLogDao.observeRecentLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val proposals: StateFlow<List<ImprovementProposalEntity>> = proposalDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val evalQuestions: StateFlow<List<EvalQuestionEntity>> = evalDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val githubPrs: StateFlow<List<GitHubPrEntity>> = gitHubSelfUpdater.prsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val autoApprove: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.autoApproveImprovements }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isGitHubRunning = MutableStateFlow(false)
    val isGitHubRunning: StateFlow<Boolean> = _isGitHubRunning.asStateFlow()

    private val _githubError = MutableStateFlow<String?>(null)
    val githubError: StateFlow<String?> = _githubError.asStateFlow()

    init {
        viewModelScope.launch {
            workspaceManager.refreshLocalSummary()
            _neuralStats.value = nativeBrain.getNeuralStats()
            nativeBrain.checkpointsFlow.collect {
                _neuralStats.value = nativeBrain.getNeuralStats()
            }
        }
    }

    fun startAgent(goal: String? = null) = engine.startRun(goal)
    fun stopAgent() = engine.stopRun()

    fun trainNow() {
        if (_isTraining.value) return
        viewModelScope.launch {
            _isTraining.value = true
            try {
                engine.trainNow(epochs = 2)
                _neuralStats.value = nativeBrain.getNeuralStats()
            } finally {
                _isTraining.value = false
            }
        }
    }

    fun syncRepoNow() {
        if (_isGitHubRunning.value) return
        viewModelScope.launch {
            _isGitHubRunning.value = true
            _githubError.value = null
            try {
                val summary = workspaceManager.syncRepositoryAndTrain { msg ->
                    agentLogDao.insert(AgentLogEntity(runId = "SYNC", phase = "SYNC", message = msg))
                }
                agentLogDao.insert(
                    AgentLogEntity(
                        runId = "SYNC",
                        phase = "SYNC",
                        message = "Synced ${summary.syncedFiles} files (${summary.totalLines} lines) from ${summary.repoSlug} & trained Neural Core.",
                        level = "SUCCESS",
                    ),
                )
                _neuralStats.value = nativeBrain.getNeuralStats()
            } catch (e: Exception) {
                _githubError.value = e.message ?: "Repo sync failed"
            } finally {
                _isGitHubRunning.value = false
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch { agentLogDao.clearAll() }
    }

    fun toggleAutoApprove(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoApproveImprovements(enabled) }
    }

    fun approveProposal(proposal: ImprovementProposalEntity) {
        viewModelScope.launch { engine.approveProposal(proposal) }
    }

    fun rejectProposal(proposal: ImprovementProposalEntity) {
        viewModelScope.launch { engine.rejectProposal(proposal) }
    }

    fun addEvalQuestion(question: String, expectedAnswer: String) {
        if (question.isBlank() || expectedAnswer.isBlank()) return
        viewModelScope.launch {
            evalDao.insert(
                EvalQuestionEntity(
                    question = question.trim(),
                    expectedAnswer = expectedAnswer.trim(),
                    isAutoGenerated = false,
                ),
            )
        }
    }

    fun updateEvalQuestion(item: EvalQuestionEntity, question: String, expectedAnswer: String) {
        viewModelScope.launch {
            evalDao.update(
                item.copy(
                    question = question.trim(),
                    expectedAnswer = expectedAnswer.trim(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteEvalQuestion(item: EvalQuestionEntity) {
        viewModelScope.launch { evalDao.delete(item) }
    }

    fun triggerSelfRewritePr(taskDescription: String) {
        if (taskDescription.isBlank() || _isGitHubRunning.value) return
        viewModelScope.launch {
            _isGitHubRunning.value = true
            _githubError.value = null
            try {
                gitHubSelfUpdater.proposeAndMonitorSelfImprovement(taskDescription.trim()) { phase, msg ->
                    agentLogDao.insert(
                        AgentLogEntity(
                            runId = "GITHUB",
                            phase = phase,
                            message = msg,
                            level = if (msg.contains("SUCCEEDED")) "SUCCESS" else "INFO",
                        ),
                    )
                }
            } catch (e: Exception) {
                _githubError.value = e.message ?: "GitHub PR task failed"
                agentLogDao.insert(
                    AgentLogEntity(
                        runId = "GITHUB",
                        phase = "GITHUB",
                        message = "Self-rewrite PR error: ${e.message}",
                        level = "ERROR",
                    ),
                )
            } finally {
                _isGitHubRunning.value = false
            }
        }
    }

    fun refreshPr(pr: GitHubPrEntity) {
        viewModelScope.launch {
            runCatching { gitHubSelfUpdater.refreshPrStatus(pr) }
        }
    }

    fun dismissGitHubError() {
        _githubError.value = null
    }

    companion object {
        fun factory(container: AgentLabContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AgentViewModel(
                        engine = container.autonomousAgentEngine,
                        agentLogDao = container.database.agentLogDao(),
                        proposalDao = container.database.improvementProposalDao(),
                        evalDao = container.database.evalDao(),
                        brainDao = container.database.brainDao(),
                        nativeBrain = container.agentLabBrain,
                        workspaceManager = container.repoWorkspaceManager,
                        settingsRepository = container.settingsRepository,
                        gitHubSelfUpdater = container.gitHubSelfUpdater,
                    ) as T
            }
    }
}
