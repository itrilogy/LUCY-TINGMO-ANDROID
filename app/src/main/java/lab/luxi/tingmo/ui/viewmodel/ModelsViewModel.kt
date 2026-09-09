package lab.luxi.tingmo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import lab.luxi.tingmo.AppContainer
import lab.luxi.tingmo.data.AddonUiState
import lab.luxi.tingmo.data.ModelUiState
import lab.luxi.tingmo.domain.AddonCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class ModelsViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val models: StateFlow<List<ModelUiState>> =
        container.modelRepository.observeModels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vadAddon: StateFlow<AddonUiState> =
        container.modelRepository.observeVadAddon()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                AddonUiState(
                    AddonCatalog.SILERO_VAD_ID,
                    AddonCatalog.sileroVad.displayName,
                    AddonCatalog.sileroVad.description,
                    AddonCatalog.sileroVad.sizeLabel,
                    ready = false,
                    errorHint = null,
                ),
            )

    private val _downloadingId = MutableStateFlow<String?>(null)
    val downloadingId = _downloadingId.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    suspend fun select(id: String) = container.modelRepository.select(id)

    suspend fun download(id: String) {
        _downloadingId.value = id
        _progress.value = 0f
        try {
            container.modelRepository.download(id) { _progress.value = it }
        } finally {
            _downloadingId.value = null
            _progress.value = 0f
        }
    }

    suspend fun downloadVad() {
        _downloadingId.value = AddonCatalog.SILERO_VAD_ID
        _progress.value = 0f
        try {
            container.modelRepository.downloadVad { _progress.value = it }
        } finally {
            _downloadingId.value = null
            _progress.value = 0f
        }
    }

    suspend fun uninstallVad() = container.modelRepository.uninstallVad()

    suspend fun uninstall(id: String) = container.modelRepository.uninstall(id)
}

class ModelsViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ModelsViewModel(container) as T
}
