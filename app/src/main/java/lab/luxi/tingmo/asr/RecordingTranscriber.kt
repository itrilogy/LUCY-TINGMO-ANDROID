package lab.luxi.tingmo.asr

import lab.luxi.tingmo.domain.AsrModel
import lab.luxi.tingmo.domain.TimedSegment
import lab.luxi.tingmo.domain.segmentWindowMs
import kotlinx.coroutines.delay

/**
 * 对录音 PCM 按时长分片并转写。
 * 本地 ONNX 未接入前：按窗切段，并用轻量启发式生成可读分片稿
 * （保留真实时长/分片结构；后续替换 [inferChunk] 即可接真模型）。
 *
 * 若调用方已在录音期收集到系统识别字幕，应优先用 [mergeLiveCaptions]。
 */
class RecordingTranscriber {
    data class Outcome(
        val segments: List<TimedSegment>,
        val joined: String,
        val engineNote: String,
    )

    suspend fun transcribePcm(
        model: AsrModel,
        pcm16: ShortArray,
        sampleRate: Int = WavRecorder.SAMPLE_RATE,
        liveCaptions: List<TimedSegment> = emptyList(),
    ): Outcome {
        if (liveCaptions.isNotEmpty()) {
            val joined = liveCaptions.joinToString("\n") { it.text }.trim()
            return Outcome(
                segments = liveCaptions,
                joined = joined,
                engineNote = "后置转写：采用录音期捕获的分片字幕（${liveCaptions.size} 段），模型策略窗 ${model.segmentWindowMs() / 1000}s",
            )
        }

        val windowMs = model.segmentWindowMs()
        val windowSamples = ((windowMs / 1000.0) * sampleRate).toInt().coerceAtLeast(sampleRate)
        val segments = mutableListOf<TimedSegment>()
        var offset = 0
        var index = 0
        while (offset < pcm16.size) {
            val end = (offset + windowSamples).coerceAtMost(pcm16.size)
            val chunk = pcm16.copyOfRange(offset, end)
            val startMs = offset * 1000L / sampleRate
            val endMs = end * 1000L / sampleRate
            val text = inferChunk(model, chunk, sampleRate, index, startMs, endMs)
            if (text.isNotBlank()) {
                segments += TimedSegment(index, startMs, endMs, text)
                index++
            }
            offset = end
            delay(40)
        }
        val joined = segments.joinToString("\n") { it.text }.trim()
        return Outcome(
            segments = segments,
            joined = joined,
            engineNote = "后置转写：已对录音文件按 ${windowMs / 1000}s 分片（${segments.size} 段）。本地权重接入后将替换分片推理。",
        )
    }

    fun mergeLiveCaptions(captions: List<TimedSegment>): Outcome {
        val joined = captions.joinToString("\n") { it.text }.trim()
        return Outcome(captions, joined, "已汇总录音期字幕 ${captions.size} 段")
    }

    private suspend fun inferChunk(
        model: AsrModel,
        chunk: ShortArray,
        sampleRate: Int,
        index: Int,
        startMs: Long,
        endMs: Long,
    ): String {
        // Energy gate: skip near-silence windows
        var energy = 0L
        for (s in chunk) energy += kotlin.math.abs(s.toInt())
        val avg = if (chunk.isEmpty()) 0 else energy / chunk.size
        if (avg < 180) return ""

        delay(80)
        val sec = chunk.size.toDouble() / sampleRate
        // Keep structure honest; avoid the old long "占位说明" essay.
        return "（第${index + 1}片 ${"%.1f".format(sec)}s · ${model.displayName}）有语音活动，待本地模型推理。"
    }
}
