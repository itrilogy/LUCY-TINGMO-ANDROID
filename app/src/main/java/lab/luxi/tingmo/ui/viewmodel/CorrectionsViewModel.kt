package lab.luxi.tingmo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import lab.luxi.tingmo.AppContainer
import lab.luxi.tingmo.data.db.PendingCorrectionEntity
import lab.luxi.tingmo.domain.CorrectionApplier
import lab.luxi.tingmo.domain.CorrectionRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CorrectionsViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val rules: StateFlow<List<CorrectionRule>> =
        container.correctionRepository.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pending: StateFlow<List<PendingCorrectionEntity>> =
        container.pendingCorrectionDao.observePending()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun preview(input: String): Flow<String> =
        rules.map { CorrectionApplier.apply(input, it) }

    suspend fun add(source: String, target: String, note: String) {
        container.correctionRepository.add(source, target, note)
    }

    suspend fun setEnabled(rule: CorrectionRule, enabled: Boolean) {
        container.correctionRepository.update(rule.copy(enabled = enabled))
    }

    suspend fun delete(id: Long) {
        container.correctionRepository.delete(id)
    }

    fun approvePending(item: PendingCorrectionEntity) = viewModelScope.launch {
        container.correctionRepository.add(
            source = item.source,
            target = item.target,
            note = "AI 建议入库：${item.reason}".trimEnd('：'),
        )
        container.pendingCorrectionDao.setStatus(item.id, "approved")
        container.pendingCorrectionDao.delete(item.id)
    }

    fun rejectPending(id: Long) = viewModelScope.launch {
        container.pendingCorrectionDao.setStatus(id, "rejected")
        container.pendingCorrectionDao.delete(id)
    }
}

class CorrectionsViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CorrectionsViewModel(container) as T
}
