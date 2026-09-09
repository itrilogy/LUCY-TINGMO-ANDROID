package lab.luxi.tingmo.domain

/**
 * 对齐桌面听默的三种转写交互：
 * - REALTIME：实时模型连续听写
 * - SEMI_REALTIME：录音中出字幕（半实时）
 * - BATCH：只录音，停止后对录音文件分片转写
 */
enum class TranscribeMode {
    REALTIME,
    SEMI_REALTIME,
    BATCH,
}

/** 结果区标签：与桌面 overlay 原文/纠偏/对照一致 */
enum class ResultTab {
    RAW,
    CORRECTED,
    BOTH,
}

data class TranscriptSegment(
    val text: String,
    val isFinal: Boolean,
    val atMs: Long = System.currentTimeMillis(),
)

data class TimedSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    fun timeLabel(): String = "${formatClock(startMs)}–${formatClock(endMs)}"

    companion object {
        fun formatClock(ms: Long): String {
            val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
            val m = totalSec / 60
            val s = totalSec % 60
            return "%d:%02d".format(m, s)
        }
    }
}

data class TranscriptResult(
    val rawText: String,
    val correctedText: String,
    val modelId: String,
    val mode: TranscribeMode,
    val durationMs: Long = 0,
    val segments: List<TimedSegment> = emptyList(),
    val engineNote: String = "",
    val recordingPath: String? = null,
)

fun AsrModel.segmentWindowMs(): Long = when (id) {
    "qwen3_asr" -> 20_000L
    "sensevoice_small" -> 30_000L
    "whisper_tiny" -> 30_000L
    "parakeet_v3" -> 25_000L
    "system_zh" -> 15_000L
    else -> 20_000L
}

fun AsrModel.supportsMode(mode: TranscribeMode): Boolean = when (mode) {
    TranscribeMode.REALTIME -> supportsRealtime
    TranscribeMode.SEMI_REALTIME -> supportsRealtime || supportsBatch
    TranscribeMode.BATCH -> supportsBatch
}
