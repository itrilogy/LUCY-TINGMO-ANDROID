package lab.luxi.tingmo.ui.viewmodel

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import lab.luxi.tingmo.AppContainer
import lab.luxi.tingmo.asr.RemoteAsrClient
import lab.luxi.tingmo.data.db.RecordingEntity
import lab.luxi.tingmo.domain.CorrectionApplier
import lab.luxi.tingmo.domain.ModelCatalog
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecordingsViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val recordings: StateFlow<List<RecordingEntity>> =
        container.recordingRepository.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId = _playingId.asStateFlow()

    private var player: MediaPlayer? = null

    fun play(item: RecordingEntity) {
        stopPlay()
        if (!File(item.filePath).exists()) return
        player = MediaPlayer().apply {
            setDataSource(item.filePath)
            setOnCompletionListener { _playingId.value = null }
            prepare()
            start()
        }
        _playingId.value = item.id
    }

    fun stopPlay() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        _playingId.value = null
    }

    fun rename(id: Long, title: String) = viewModelScope.launch {
        container.recordingRepository.rename(id, title)
    }

    fun delete(id: Long) = viewModelScope.launch {
        if (_playingId.value == id) stopPlay()
        container.recordingRepository.delete(id)
    }

    fun retranscribe(item: RecordingEntity, onDone: (String) -> Unit) = viewModelScope.launch {
        try {
            val file = File(item.filePath)
            require(file.exists()) { "文件不存在" }
            val modelId = container.settings.selectedModelId.first()
            val model = ModelCatalog.require(modelId)
            val rasr = container.aiSettings.remoteAsr.first()
            val text = when {
                rasr.enabled && rasr.apiKey.isNotBlank() ->
                    RemoteAsrClient().transcribeFile(rasr, file)
                container.transcriptionService.usesOnnx(model) -> {
                    // 按文件分片推理，避免课堂长录音整文件进内存
                    container.onnxEngine.transcribeWavFileSegmented(model, file).second
                }
                else -> throw IllegalStateException("请启用远端 ASR 或下载本地 ONNX")
            }
            val rules = container.correctionRepository.observe().first()
            val corrected = CorrectionApplier.apply(text, rules)
            container.recordingRepository.updateTexts(item.id, text, corrected)
            onDone("重转写完成")
        } catch (e: Exception) {
            onDone(e.message ?: "重转写失败")
        }
    }

    override fun onCleared() {
        stopPlay()
        super.onCleared()
    }
}

class RecordingsViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RecordingsViewModel(container) as T
}
