package com.example.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AgentLabContainer
import com.example.data.settings.AgentSettings
import com.example.data.settings.AiProvider
import com.example.data.settings.SearchProvider
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AgentSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgentSettings())

    private val _savedMessage = MutableStateFlow<String?>(null)
    val savedMessage: StateFlow<String?> = _savedMessage.asStateFlow()

    fun saveAll(updated: AgentSettings) {
        viewModelScope.launch {
            settingsRepository.updateSettings { updated }
            _savedMessage.value = "Settings encrypted & saved."
        }
    }

    fun selectProvider(provider: AiProvider) {
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(
                    aiProvider = provider,
                    baseUrl = provider.defaultBaseUrl,
                    modelName = provider.defaultModel,
                )
            }
        }
    }

    fun selectSearchProvider(provider: SearchProvider) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(searchProvider = provider) }
        }
    }

    fun resetDailyTokenCounter() {
        viewModelScope.launch {
            settingsRepository.resetDailyTokenUsage()
            _savedMessage.value = "Daily token counter reset."
        }
    }

    fun dismissSavedMessage() {
        _savedMessage.value = null
    }

    companion object {
        fun factory(container: AgentLabContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(container.settingsRepository) as T
            }
    }
}
