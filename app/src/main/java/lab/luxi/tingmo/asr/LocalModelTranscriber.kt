package lab.luxi.tingmo.asr

import lab.luxi.tingmo.domain.AsrModel
import lab.luxi.tingmo.domain.EngineKind
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Placeholder local engine.
 *
 * Swap [runLocalInference] with ONNX Runtime / whisper.cpp JNI later.
 * Until real weights are wired, batch mode returns a structured demo transcript
 * so UI / 纠偏 / 模型切换流程可完整验证。
 */
class LocalModelTranscriber : BatchTranscriber, RealtimeTranscriber {
    @Volatile private var realtimeActive = false

    override fun supports(model: AsrModel): Boolean =
        model.engine == EngineKind.LOCAL_ONNX || model.engine == EngineKind.LOCAL_WHISPER

    override suspend fun transcribeFile(model: AsrModel, pcm16kMono: ShortArray): String {
        val seconds = pcm16kMono.size / 16000.0
        return runLocalInference(model, seconds, sampleHint = "file")
    }

    override suspend fun simulateFromRecording(
        model: AsrModel,
        durationMs: Long,
        hint: String,
    ): String {
        val seconds = durationMs / 1000.0
        return runLocalInference(model, seconds, sampleHint = hint.ifBlank { "mic" })
    }

    override fun start(model: AsrModel): kotlinx.coroutines.flow.Flow<TranscribeEvent> =
        kotlinx.coroutines.flow.flow {
            if (!model.supportsRealtime) {
                emit(TranscribeEvent.Error("${model.displayName} 暂不支持实时转写，请改用后置转写"))
                emit(TranscribeEvent.Ended)
                return@flow
            }
            realtimeActive = true
            // Simulated semi-realtime chunks — replace with streaming local ASR.
            val chunks = listOf(
                "正在使用 ${model.displayName} 进行半实时转写",
                "本地引擎适配层已就绪",
                "可在模型页下载权重后接入真实推理",
            )
            for (i in chunks.indices) {
                if (!realtimeActive) break
                delay(900)
                val partial = chunks.subList(0, i + 1).joinToString("，")
                emit(TranscribeEvent.Partial(partial))
            }
            if (realtimeActive) {
                emit(TranscribeEvent.Final(chunks.joinToString("，") + "。"))
            }
            emit(TranscribeEvent.Ended)
            realtimeActive = false
        }

    override fun stop() {
        realtimeActive = false
    }

    private suspend fun runLocalInference(
        model: AsrModel,
        seconds: Double,
        sampleHint: String,
    ): String {
        // Pretend to run model; keep delay proportional but capped.
        delay(min(1800L, 400L + (seconds * 120).toLong()))
        val windowNote = when (model.id) {
            "qwen3_asr" -> "（已按约 20s 短窗分片策略模拟）"
            else -> ""
        }
        return buildString {
            append("【${model.displayName} · 后置转写】")
            append(windowNote)
            append('\n')
            append("音频约 ${"%.1f".format(seconds)} 秒（来源：$sampleHint）。")
            append("此处为本地推理占位结果：接入 ONNX/Whisper 后将替换为真实转写文本。")
            append("可先用「AI 纠偏」验证易错词替换，例如把「占位」改成业务用语。")
        }
    }
}
