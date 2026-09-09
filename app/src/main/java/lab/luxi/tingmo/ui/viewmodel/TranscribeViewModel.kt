package lab.luxi.tingmo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import lab.luxi.tingmo.AppContainer
import lab.luxi.tingmo.asr.LocalOnnxSession
import lab.luxi.tingmo.asr.SegmentedBatchSession
import lab.luxi.tingmo.asr.SpeechCompat
import lab.luxi.tingmo.asr.TranscribeEvent
import lab.luxi.tingmo.asr.AudioGain
import lab.luxi.tingmo.domain.AsrModel
import lab.luxi.tingmo.domain.EngineKind
import lab.luxi.tingmo.domain.PickupProfile
import lab.luxi.tingmo.domain.ResultTab
import lab.luxi.tingmo.domain.TimedSegment
import lab.luxi.tingmo.domain.TranscribeMode
import lab.luxi.tingmo.domain.segmentWindowMs
import lab.luxi.tingmo.domain.supportsMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranscribeUiState(
    val model: AsrModel? = null,
    val mode: TranscribeMode = TranscribeMode.SEMI_REALTIME,
    val isBusy: Boolean = false,
    val isPolishing: Boolean = false,
    val liveText: String = "",
    val rawText: String = "",
    val correctedText: String = "",
    val resultTab: ResultTab = ResultTab.RAW,
    val error: String? = null,
    val elapsedMs: Long = 0L,
    val windowMs: Long = 20_000L,
    val segmentIndex: Int = 0,
    val segments: List<TimedSegment> = emptyList(),
    val engineNote: String = "",
    val recordingPath: String? = null,
    val usingOnnx: Boolean = false,
    val pickupSensitivity: Int = PickupProfile.DEFAULT_SENSITIVITY,
    val pickupLabel: String = "课堂",
    val pickupHint: String = "教室远讲 / 走动授课，推荐默认",
    /** 半实时最长切片（秒），VAD maxSpeechDuration */
    val semiMaxSpeechSec: Int = 5,
    val vadReady: Boolean = false,
    /** 半实时本会话是否走 VAD（否则时间窗） */
    val usingVad: Boolean = false,
    /** 实时采集电平 0..1 */
    val peakLevel: Float = 0f,
)

class TranscribeViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TranscribeUiState())
    val uiState: StateFlow<TranscribeUiState> = _uiState.asStateFlow()

    private var sessionJob: Job? = null
    private val capturedSegments = mutableListOf<TimedSegment>()
    private var usingOnnxSession = false

    init {
        viewModelScope.launch {
            container.settings.pickupSensitivity.collect { sens ->
                val profile = PickupProfile.fromSensitivity(sens)
                AudioGain.applyProfile(profile)
                _uiState.update {
                    it.copy(
                        pickupSensitivity = sens,
                        pickupLabel = profile.label,
                        pickupHint = profile.hint,
                    )
                }
            }
        }
        viewModelScope.launch {
            container.settings.semiMaxSpeechSec.collect { sec ->
                _uiState.update { it.copy(semiMaxSpeechSec = sec) }
            }
        }
        viewModelScope.launch {
            container.modelRepository.observeVadAddon().collect { vad ->
                _uiState.update { it.copy(vadReady = vad.ready) }
            }
        }
        viewModelScope.launch {
            container.modelRepository.selectedModelFlow().collect { model ->
                val onnx = container.transcriptionService.usesOnnx(model)
                val systemProbe = SpeechCompat.probe(container.appContext)
                _uiState.update {
                    val mode = when {
                        model.supportsMode(it.mode) -> it.mode
                        onnx -> TranscribeMode.SEMI_REALTIME
                        model.supportsRealtime -> TranscribeMode.REALTIME
                        else -> TranscribeMode.BATCH
                    }
                    val warn = buildString {
                        if (
                            model.engine == EngineKind.SYSTEM_SPEECH &&
                            !systemProbe.available &&
                            systemProbe.component == null
                        ) {
                            append(SpeechCompat.unsupportedMessage(systemProbe))
                        }
                        if (onnx && mode == TranscribeMode.SEMI_REALTIME && !it.vadReady) {
                            if (isNotEmpty()) append('\n')
                            append("半实时建议先在「模型」页下载独立组件 Silero VAD，否则只能按最长切片出字幕。")
                        }
                    }.ifBlank { null }
                    it.copy(
                        model = model,
                        error = warn,
                        windowMs = model.segmentWindowMs(),
                        mode = mode,
                        usingOnnx = onnx,
                    )
                }
            }
        }
    }

    fun setPickupSensitivity(value: Int) {
        val profile = PickupProfile.fromSensitivity(value)
        AudioGain.applyProfile(profile)
        _uiState.update {
            it.copy(
                pickupSensitivity = profile.sensitivity,
                pickupLabel = profile.label,
                pickupHint = profile.hint,
            )
        }
        viewModelScope.launch {
            container.settings.setPickupSensitivity(profile.sensitivity)
        }
    }

    fun setSemiMaxSpeechSec(sec: Int) {
        val v = sec.coerceIn(2, 15)
        _uiState.update { it.copy(semiMaxSpeechSec = v) }
        viewModelScope.launch { container.settings.setSemiMaxSpeechSec(v) }
    }

    fun setMode(mode: TranscribeMode) {
        if (_uiState.value.isBusy) return
        val model = _uiState.value.model
        if (model != null && !model.supportsMode(mode)) {
            _uiState.update { it.copy(error = "${model.displayName} 不支持该模式") }
            return
        }
        // 本地 ONNX 的「实时」改为半实时
        val adjusted =
            if (mode == TranscribeMode.REALTIME && model != null &&
                container.transcriptionService.usesOnnx(model)
            ) {
                TranscribeMode.SEMI_REALTIME
            } else {
                mode
            }
        _uiState.update { it.copy(mode = adjusted, error = null) }
    }

    fun setResultTab(tab: ResultTab) {
        _uiState.update { it.copy(resultTab = tab) }
    }

    fun clearText() {
        _uiState.update {
            it.copy(
                liveText = "",
                rawText = "",
                correctedText = "",
                error = null,
                segments = emptyList(),
                elapsedMs = 0L,
                engineNote = "",
                recordingPath = null,
                resultTab = ResultTab.RAW,
            )
        }
        capturedSegments.clear()
        container.transcriptionService.clearSessionArtifacts()
    }

    fun start() {
        val model = _uiState.value.model ?: return
        var mode = _uiState.value.mode
        val needsOnnx = lab.luxi.tingmo.asr.OnnxModelPack.requiresOnnx(model)
        val onnxReady = container.transcriptionService.usesOnnx(model)

        // P0：本地引擎未下载时绝不静默回退到系统识别
        if (needsOnnx && !onnxReady) {
            _uiState.update {
                it.copy(error = "请先在「模型」页下载并选用 ${model.displayName}（本地 ONNX），勿使用未就绪模型。")
            }
            return
        }

        if (mode == TranscribeMode.REALTIME && onnxReady) {
            mode = TranscribeMode.SEMI_REALTIME
        }
        if (!model.supportsMode(mode) && !(onnxReady && mode == TranscribeMode.SEMI_REALTIME)) {
            _uiState.update { it.copy(error = "请选择支持该模式的模型") }
            return
        }

        capturedSegments.clear()
        usingOnnxSession = onnxReady && mode != TranscribeMode.REALTIME
        _uiState.update {
            it.copy(
                isBusy = true,
                isPolishing = false,
                error = null,
                liveText = "",
                rawText = "",
                correctedText = "",
                segments = emptyList(),
                elapsedMs = 0L,
                segmentIndex = 0,
                windowMs = model.segmentWindowMs(),
                engineNote = "",
                recordingPath = null,
                resultTab = ResultTab.RAW,
                mode = mode,
                usingOnnx = onnxReady,
            )
        }

        // 课堂长录：拉起前台服务，降低息屏被杀概率
        lab.luxi.tingmo.asr.RecordingForegroundService.start(container.appContext)

        try {
            when {
                mode == TranscribeMode.REALTIME -> startRealtime(model)
                usingOnnxSession -> startOnnx(model, live = mode == TranscribeMode.SEMI_REALTIME)
                else -> startSystemSegmented(model, live = mode == TranscribeMode.SEMI_REALTIME)
            }
        } catch (e: Exception) {
            lab.luxi.tingmo.asr.RecordingForegroundService.stop(container.appContext)
            _uiState.update {
                it.copy(isBusy = false, error = e.message ?: "无法开始转写")
            }
        }
    }

    private fun startRealtime(model: AsrModel) {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            try {
                container.transcriptionService.startRealtime(model).collect { event ->
                    when (event) {
                        is TranscribeEvent.Partial ->
                            _uiState.update { it.copy(liveText = event.text, rawText = event.text) }
                        is TranscribeEvent.Final -> {
                            container.transcriptionService.stopRealtime(model)
                            val result = container.transcriptionService.finalizeAndStore(
                                raw = event.text,
                                model = model,
                                mode = TranscribeMode.REALTIME,
                                engineNote = "系统实时转写 · 已应用纠偏词",
                            )
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    liveText = event.text,
                                    rawText = result.rawText,
                                    correctedText = result.correctedText,
                                    resultTab = ResultTab.CORRECTED,
                                    engineNote = result.engineNote,
                                )
                            }
                        }
                        is TranscribeEvent.Error ->
                            _uiState.update { it.copy(error = event.message, isBusy = false) }
                        TranscribeEvent.Ended ->
                            _uiState.update { s -> if (s.isBusy) s.copy(isBusy = false) else s }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, error = e.message ?: "实时失败") }
            }
        }
    }

    private fun startOnnx(model: AsrModel, live: Boolean) {
        // 开录前预加载：损坏权重会在 Kotlin 层失败，避免录音中 native abort 闪退
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            try {
                val issue = container.onnxEngine.modelIntegrityIssue(model)
                if (issue != null) {
                    markIdle {
                        it.copy(
                            error = "模型损坏：$issue。请到「模型」页点「重新下载」（完整约 230MB）。",
                        )
                    }
                    return@launch
                }
                _uiState.update { it.copy(liveText = "正在加载本地模型…") }
                container.onnxEngine.ensureLoaded(model)
            } catch (e: Exception) {
                markIdle {
                    it.copy(error = e.message ?: "模型加载失败，请到「模型」页重新下载")
                }
                return@launch
            }

            container.transcriptionService.startOnnxSession(
                model = model,
                liveCaptions = live,
                maxSpeechSec = _uiState.value.semiMaxSpeechSec.toFloat(),
                listener = object : LocalOnnxSession.Listener {
                    override fun onEvent(event: LocalOnnxSession.Event) {
                        when (event) {
                            is LocalOnnxSession.Event.Elapsed ->
                                _uiState.update {
                                    it.copy(
                                        elapsedMs = event.elapsedMs,
                                        windowMs = event.windowMs,
                                        segmentIndex = event.segmentIndex,
                                        usingVad = event.usingVad,
                                        peakLevel = container.transcriptionService.currentPeakLevel(),
                                    )
                                }
                            is LocalOnnxSession.Event.Partial ->
                                if (live) {
                                    _uiState.update {
                                        it.copy(
                                            liveText = event.text,
                                            rawText = event.text,
                                            elapsedMs = event.elapsedMs,
                                        )
                                    }
                                }
                            is LocalOnnxSession.Event.SegmentCommitted -> {
                                capturedSegments += event.segment
                                if (live) {
                                    _uiState.update {
                                        it.copy(
                                            segments = capturedSegments.toList(),
                                            liveText = event.joined,
                                            rawText = event.joined,
                                        )
                                    }
                                } else {
                                    _uiState.update { it.copy(segments = capturedSegments.toList()) }
                                }
                            }
                            is LocalOnnxSession.Event.Finished -> {
                                container.transcriptionService.rememberRecording(event.recordingFile)
                                viewModelScope.launch {
                                    val result = container.transcriptionService.finalizeAndStore(
                                        raw = event.joined,
                                        model = model,
                                        mode = if (live) {
                                            TranscribeMode.SEMI_REALTIME
                                        } else {
                                            TranscribeMode.BATCH
                                        },
                                        durationMs = event.durationMs,
                                        segments = event.segments,
                                        engineNote = event.engineNote + " · 已应用纠偏词",
                                        recordingPath = event.recordingFile?.absolutePath,
                                    )
                                    markIdle {
                                        it.copy(
                                            liveText = result.rawText,
                                            rawText = result.rawText,
                                            correctedText = result.correctedText,
                                            segments = result.segments,
                                            elapsedMs = result.durationMs,
                                            engineNote = result.engineNote,
                                            recordingPath = result.recordingPath,
                                            resultTab = ResultTab.CORRECTED,
                                            error = if (result.rawText.isBlank()) {
                                                "未识别到有效语音，请检查拾音场景或靠近一些说话"
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                }
                            }
                            is LocalOnnxSession.Event.Error ->
                                markIdle { it.copy(error = event.message) }
                        }
                    }
                },
            )
            _uiState.update {
                it.copy(
                    liveText = if (live) "" else "后置录音中（停止后转写）",
                )
            }
        }
    }

    private fun startSystemSegmented(model: AsrModel, live: Boolean) {
        container.transcriptionService.startSystemSegmented(
            model = model,
            listener = object : SegmentedBatchSession.Listener {
                override fun onEvent(event: SegmentedBatchSession.Event) {
                    when (event) {
                        is SegmentedBatchSession.Event.Elapsed ->
                            _uiState.update {
                                it.copy(
                                    elapsedMs = event.elapsedMs,
                                    windowMs = event.windowMs,
                                    segmentIndex = event.segmentIndex,
                                    peakLevel = container.transcriptionService.currentPeakLevel(),
                                )
                            }
                        is SegmentedBatchSession.Event.Partial ->
                            if (live) {
                                _uiState.update {
                                    it.copy(liveText = event.text, rawText = event.text, elapsedMs = event.elapsedMs)
                                }
                            } else {
                                _uiState.update { it.copy(elapsedMs = event.elapsedMs) }
                            }
                        is SegmentedBatchSession.Event.SegmentCommitted -> {
                            capturedSegments += event.segment
                            _uiState.update {
                                it.copy(
                                    segments = capturedSegments.toList(),
                                    liveText = if (live) event.joined else it.liveText,
                                    rawText = if (live) event.joined else it.rawText,
                                )
                            }
                        }
                        is SegmentedBatchSession.Event.Finished -> {
                            val wav = container.transcriptionService.takeParallelRecording()
                            viewModelScope.launch {
                                val result = container.transcriptionService.finalizeAndStore(
                                    raw = event.joined,
                                    model = model,
                                    mode = if (live) TranscribeMode.SEMI_REALTIME else TranscribeMode.BATCH,
                                    durationMs = event.durationMs,
                                    segments = event.segments,
                                    engineNote = event.engineNote + " · 已应用纠偏词",
                                    recordingPath = wav?.absolutePath,
                                )
                                markIdle {
                                    it.copy(
                                        liveText = result.rawText,
                                        rawText = result.rawText,
                                        correctedText = result.correctedText,
                                        segments = result.segments,
                                        elapsedMs = event.durationMs,
                                        engineNote = result.engineNote,
                                        recordingPath = result.recordingPath,
                                        resultTab = ResultTab.CORRECTED,
                                        error = if (result.rawText.isBlank()) {
                                            "未识别到有效语音"
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                        is SegmentedBatchSession.Event.Error -> {
                            container.transcriptionService.takeParallelRecording()
                            markIdle { it.copy(error = event.message) }
                        }
                    }
                }
            },
        )
    }

    fun stop() {
        val state = _uiState.value
        val model = state.model ?: return
        when {
            state.mode == TranscribeMode.REALTIME && !usingOnnxSession -> {
                container.transcriptionService.stopRealtime(model)
                sessionJob?.cancel()
                val raw = state.liveText.ifBlank { state.rawText }
                if (raw.isNotBlank()) {
                    viewModelScope.launch {
                        val result = container.transcriptionService.finalizeAndStore(
                            raw = raw,
                            model = model,
                            mode = TranscribeMode.REALTIME,
                            engineNote = "实时已停止 · 已应用纠偏词",
                        )
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                rawText = result.rawText,
                                correctedText = result.correctedText,
                                liveText = result.rawText,
                                resultTab = ResultTab.CORRECTED,
                                engineNote = result.engineNote,
                            )
                        }
                    }
                } else {
                    _uiState.update { it.copy(isBusy = false) }
                }
            }
            usingOnnxSession -> {
                _uiState.update {
                    it.copy(
                        liveText = if (state.mode == TranscribeMode.BATCH) {
                            "正在对录音做本地 ONNX 分片转写…"
                        } else {
                            it.liveText.ifBlank { "正在结束半实时…" }
                        },
                    )
                }
                container.transcriptionService.stopOnnxSession()
            }
            else -> {
                _uiState.update {
                    it.copy(
                        liveText = if (state.mode == TranscribeMode.BATCH) {
                            "正在结束并汇总…"
                        } else {
                            it.liveText.ifBlank { "正在结束…" }
                        },
                    )
                }
                container.transcriptionService.stopSystemSegmented()
            }
        }
    }

    private fun markIdle(transform: (TranscribeUiState) -> TranscribeUiState = { it }) {
        lab.luxi.tingmo.asr.RecordingForegroundService.stop(container.appContext)
        _uiState.update { transform(it.copy(isBusy = false, peakLevel = 0f, usingVad = false)) }
    }

    fun aiPolish() {
        val state = _uiState.value
        val model = state.model ?: return
        val raw = state.rawText.ifBlank { state.liveText }
        if (raw.isBlank() && state.recordingPath == null) {
            _uiState.update { it.copy(error = "没有可加工的文本或录音") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isPolishing = true, error = null) }
            try {
                val llm = container.aiSettings.llm.first()
                val rasr = container.aiSettings.remoteAsr.first()
                val prompt = container.promptRepository.getDefaultContent()
                val result = container.transcriptionService.aiPolish(
                    model = model,
                    rawText = raw,
                    segments = state.segments,
                    mode = state.mode,
                    llmSettings = llm,
                    prompt = prompt,
                    remoteAsr = rasr,
                )
                _uiState.update {
                    it.copy(
                        isPolishing = false,
                        rawText = result.rawText,
                        correctedText = result.correctedText,
                        liveText = result.correctedText,
                        segments = result.segments.ifEmpty { it.segments },
                        resultTab = ResultTab.CORRECTED,
                        engineNote = result.engineNote,
                        recordingPath = result.recordingPath ?: it.recordingPath,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isPolishing = false, error = e.message ?: "AI 加工失败") }
            }
        }
    }
}

class TranscribeViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return TranscribeViewModel(container) as T
    }
}
