package lab.luxi.tingmo.asr

import android.content.Context
import lab.luxi.tingmo.data.db.CorrectionDao
import lab.luxi.tingmo.data.db.HistoryDao
import lab.luxi.tingmo.data.db.TranscriptHistoryEntity
import lab.luxi.tingmo.domain.AsrModel
import lab.luxi.tingmo.domain.CorrectionApplier
import lab.luxi.tingmo.domain.CorrectionRule
import lab.luxi.tingmo.domain.EngineKind
import lab.luxi.tingmo.domain.ModelCatalog
import lab.luxi.tingmo.domain.TimedSegment
import lab.luxi.tingmo.domain.TranscribeMode
import lab.luxi.tingmo.domain.TranscriptResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class TranscriptionService(
    private val context: Context,
    private val correctionDao: CorrectionDao,
    private val historyDao: HistoryDao,
    private val pendingCorrectionDao: lab.luxi.tingmo.data.db.PendingCorrectionDao,
    private val recordingRepository: lab.luxi.tingmo.data.RecordingRepository,
    val onnxEngine: OnnxAsrEngine,
    val vadEngine: VadEngine,
) {
    private val system = SystemSpeechTranscriber(context)
    private val remoteAsr = RemoteAsrClient()
    private val llmClient = lab.luxi.tingmo.llm.LlmClient()
    private var systemBatch: SegmentedBatchSession? = null
    private var onnxSession: LocalOnnxSession? = null
    private var parallelRecorder: StreamingPcmRecorder? = null
    private var lastRecording: File? = null

    fun recordingsDir(): File = File(context.filesDir, "recordings").also { it.mkdirs() }

    fun lastRecordingPath(): String? = lastRecording?.absolutePath
    fun lastRecordingFile(): File? = lastRecording

    /** 当前采集电平 0..1，供 UI 电平条。 */
    fun currentPeakLevel(): Float =
        onnxSession?.peakLevel ?: parallelRecorder?.peakLevel ?: 0f

    fun usesOnnx(model: AsrModel): Boolean =
        OnnxModelPack.requiresOnnx(model) && onnxEngine.isModelReady(model)

    fun startRealtime(model: AsrModel): Flow<TranscribeEvent> {
        if (model.engine == EngineKind.SYSTEM_SPEECH) {
            return system.start(model)
        }
        return kotlinx.coroutines.flow.flow {
            emit(TranscribeEvent.Error("本地 ONNX 请使用半实时或后置模式"))
            emit(TranscribeEvent.Ended)
        }
    }

    fun stopRealtime(model: AsrModel) {
        if (model.engine == EngineKind.SYSTEM_SPEECH) system.stop()
    }

    fun startOnnxSession(
        model: AsrModel,
        liveCaptions: Boolean,
        maxSpeechSec: Float,
        listener: LocalOnnxSession.Listener,
    ) {
        require(usesOnnx(model)) { "请先下载本地 ONNX 模型" }
        onnxSession?.stop()
        systemBatch?.stop()
        stopParallelRecorder(keepFile = false)
        val session = LocalOnnxSession(onnxEngine, vadEngine, recordingsDir())
        onnxSession = session
        session.start(model, liveCaptions, maxSpeechSec, listener)
    }

    fun stopOnnxSession() {
        onnxSession?.stop()
    }

    fun startSystemSegmented(model: AsrModel, listener: SegmentedBatchSession.Listener) {
        systemBatch?.stop()
        onnxSession?.stop()
        stopParallelRecorder(keepFile = false)
        // 系统 ASR 路径同步磁盘录音，便于归档 / 远端重转写
        val wav = File(recordingsDir(), "tingmo_${System.currentTimeMillis()}.wav")
        val rec = StreamingPcmRecorder()
        runCatching { rec.start(wav) }
            .onSuccess { parallelRecorder = rec }
            .onFailure { parallelRecorder = null }
        val session = SegmentedBatchSession(context)
        systemBatch = session
        session.start(model, listener)
    }

    fun stopSystemSegmented() {
        systemBatch?.stop()
        systemBatch = null
        // Finished 回调前由 takeParallelRecording 收口
    }

    /** 系统分片结束时取出并行录音文件。 */
    fun takeParallelRecording(): File? {
        val rec = parallelRecorder ?: return null
        parallelRecorder = null
        val file = rec.stopToWav()
        if (file != null) lastRecording = file
        return file
    }

    fun rememberRecording(file: File?) {
        if (file != null && file.exists()) {
            lastRecording = file
        }
    }

    @Deprecated("改用磁盘流式 rememberRecording", ReplaceWith("rememberRecording(file)"))
    fun rememberPcm(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val file = File(recordingsDir(), "tingmo_${System.currentTimeMillis()}.wav")
        runCatching {
            writeWav(file, pcm, WavRecorder.SAMPLE_RATE)
            lastRecording = file
        }
    }

    fun clearSessionArtifacts() {
        lastRecording = null
    }

    private fun stopParallelRecorder(keepFile: Boolean) {
        val rec = parallelRecorder ?: return
        parallelRecorder = null
        if (keepFile) {
            val f = rec.stopToWav()
            if (f != null) lastRecording = f
        } else {
            rec.cancel()
        }
    }

    suspend fun aiPolish(
        model: AsrModel,
        rawText: String,
        segments: List<TimedSegment>,
        mode: TranscribeMode,
        llmSettings: lab.luxi.tingmo.data.LlmSettings,
        prompt: String,
        remoteAsr: lab.luxi.tingmo.data.RemoteAsrSettings? = null,
    ): TranscriptResult {
        var workingRaw = rawText
        var workingSegs = segments
        val note = StringBuilder()
        val recording = lastRecording
        if (remoteAsr?.enabled == true && recording?.exists() == true) {
            val remoteText = remoteAsrClientSafe(remoteAsr, recording)
            if (remoteText.isNotBlank()) {
                workingRaw = remoteText
                note.append("远端 ASR 重转写 · ")
            }
        } else if (
            recording?.exists() == true &&
            usesOnnx(model) &&
            workingRaw.isBlank()
        ) {
            val (segs, joined) = onnxEngine.transcribeWavFileSegmented(model, recording)
            workingRaw = joined
            workingSegs = segs.ifEmpty { segments }
            note.append("本地 ONNX 重跑 · ")
        }

        val rules = correctionDao.enabledRules()
            .map {
                lab.luxi.tingmo.domain.CorrectionRule(
                    it.id, it.source, it.target, it.enabled, it.note, it.updatedAt,
                )
            }
            .sortedByDescending { it.source.length }
        var corrected = CorrectionApplier.apply(workingRaw, rules)
        note.append("纠偏词已应用")

        if (llmSettings.enabled) {
            val polished = llmClient.polish(llmSettings, prompt, corrected, rules)
            corrected = CorrectionApplier.apply(polished, rules)
            note.append(" · LLM 润色")
            runCatching {
                val suggestions = llmClient.suggestCorrections(
                    llmSettings, workingRaw, corrected, rules,
                )
                if (suggestions.isNotEmpty()) {
                    pendingCorrectionDao.insertAll(
                        suggestions.map { (s, t, r) ->
                            lab.luxi.tingmo.data.db.PendingCorrectionEntity(
                                source = s,
                                target = t,
                                reason = r,
                                status = "pending",
                            )
                        },
                    )
                    note.append(" · 提议${suggestions.size}条待审纠偏词")
                }
            }
        }

        historyDao.insert(
            TranscriptHistoryEntity(
                rawText = workingRaw,
                correctedText = corrected,
                modelId = model.id,
                mode = mode.name,
            ),
        )
        val durationMs = recording?.let { runCatching { PcmWavIO.durationMs(it) }.getOrDefault(0L) } ?: 0L
        recording?.absolutePath?.let { path ->
            recordingRepository.saveOrUpdate(
                title = "录音 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}",
                filePath = path,
                durationMs = durationMs,
                modelId = model.id,
                mode = mode.name,
                rawText = workingRaw,
                correctedText = corrected,
            )
        }
        return TranscriptResult(
            rawText = workingRaw,
            correctedText = corrected,
            modelId = model.id,
            mode = mode,
            durationMs = durationMs,
            segments = workingSegs,
            engineNote = "AI 加工：$note",
            recordingPath = recording?.absolutePath,
        )
    }

    private suspend fun remoteAsrClientSafe(
        settings: lab.luxi.tingmo.data.RemoteAsrSettings,
        file: File,
    ): String = try {
        remoteAsr.transcribeFile(settings, file)
    } catch (e: Exception) {
        throw IllegalStateException("远端语音失败：${e.message}")
    }

    suspend fun finalizeAndStore(
        raw: String,
        model: AsrModel,
        mode: TranscribeMode,
        durationMs: Long = 0,
        segments: List<TimedSegment> = emptyList(),
        engineNote: String = "",
        recordingPath: String? = null,
    ): TranscriptResult = withContext(Dispatchers.IO) {
        val rules = correctionDao.enabledRules()
            .map { CorrectionRule(it.id, it.source, it.target, it.enabled, it.note, it.updatedAt) }
            .sortedByDescending { it.source.length }
        val corrected = CorrectionApplier.apply(raw, rules)
        historyDao.insert(
            TranscriptHistoryEntity(
                rawText = raw,
                correctedText = corrected,
                modelId = model.id,
                mode = mode.name,
            ),
        )
        val path = recordingPath ?: lastRecording?.absolutePath
        if (!path.isNullOrBlank() && File(path).exists()) {
            recordingRepository.saveOrUpdate(
                title = "录音 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}",
                filePath = path,
                durationMs = durationMs,
                modelId = model.id,
                mode = mode.name,
                rawText = raw,
                correctedText = corrected,
            )
        }
        TranscriptResult(
            rawText = raw,
            correctedText = corrected,
            modelId = model.id,
            mode = mode,
            durationMs = durationMs,
            segments = segments,
            engineNote = engineNote,
            recordingPath = path,
        )
    }

    fun resolveModel(id: String): AsrModel = ModelCatalog.require(id)

    private fun writeWav(file: File, pcm16: ShortArray, sampleRate: Int) {
        val pcm = ByteArray(pcm16.size * 2)
        for (i in pcm16.indices) {
            val v = pcm16[i].toInt()
            pcm[i * 2] = (v and 0xff).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        file.outputStream().use { out ->
            fun intLE(v: Int) = byteArrayOf(
                (v and 0xff).toByte(),
                ((v shr 8) and 0xff).toByte(),
                ((v shr 16) and 0xff).toByte(),
                ((v shr 24) and 0xff).toByte(),
            )
            fun shortLE(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
            out.write("RIFF".toByteArray())
            out.write(intLE(36 + pcm.size))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intLE(16))
            out.write(shortLE(1))
            out.write(shortLE(1))
            out.write(intLE(sampleRate))
            out.write(intLE(sampleRate * 2))
            out.write(shortLE(2))
            out.write(shortLE(16))
            out.write("data".toByteArray())
            out.write(intLE(pcm.size))
            out.write(pcm)
        }
    }
}
