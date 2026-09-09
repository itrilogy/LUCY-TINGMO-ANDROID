package lab.luxi.tingmo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import lab.luxi.tingmo.AppContainer
import lab.luxi.tingmo.data.LlmSettings
import lab.luxi.tingmo.data.RemoteAsrSettings
import lab.luxi.tingmo.data.db.PromptEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AiSettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val llm: StateFlow<LlmSettings> = container.aiSettings.llm
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmSettings())

    val remoteAsr: StateFlow<RemoteAsrSettings> = container.aiSettings.remoteAsr
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteAsrSettings())

    val prompts: StateFlow<List<PromptEntity>> = container.promptRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveLlm(s: LlmSettings) = viewModelScope.launch {
        container.aiSettings.saveLlm(s)
    }

    fun saveRemoteAsr(s: RemoteAsrSettings) = viewModelScope.launch {
        container.aiSettings.saveRemoteAsr(s)
    }

    fun addPrompt(name: String, content: String, makeDefault: Boolean) = viewModelScope.launch {
        container.promptRepository.add(name, content, makeDefault)
    }

    fun setDefaultPrompt(id: Long) = viewModelScope.launch {
        container.promptRepository.setDefault(id)
    }

    fun deletePrompt(id: Long) = viewModelScope.launch {
        container.promptRepository.delete(id)
    }
}

class AiSettingsViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AiSettingsViewModel(container) as T
}
