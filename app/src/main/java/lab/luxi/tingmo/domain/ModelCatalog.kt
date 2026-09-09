package lab.luxi.tingmo.domain

/**
 * Built-in model catalog for 听默 Android.
 * Local ONNX / Whisper backends are pluggable; system engine works out of the box.
 */
enum class EngineKind {
    SYSTEM_SPEECH,
    LOCAL_ONNX,
    LOCAL_WHISPER,
}

enum class ModelStatus {
    READY,
    NOT_DOWNLOADED,
    DOWNLOADING,
    ERROR,
}

data class AsrModel(
    val id: String,
    val displayName: String,
    val description: String,
    val engine: EngineKind,
    val supportsRealtime: Boolean,
    val supportsBatch: Boolean,
    val sizeLabel: String,
    val languages: String,
)

object ModelCatalog {
    val models: List<AsrModel> = listOf(
        AsrModel(
            id = "system_zh",
            displayName = "系统识别引擎",
            description = "系统 RecognitionService。小米等机型常不可用（录音机有识别但第三方调不到），请改用 SenseVoice ONNX。",
            engine = EngineKind.SYSTEM_SPEECH,
            supportsRealtime = true,
            supportsBatch = true,
            sizeLabel = "系统自带",
            languages = "中文 / 系统语言",
        ),
        AsrModel(
            id = "sensevoice_small",
            displayName = "SenseVoice Small (ONNX)",
            description = "本地 sherpa-onnx 真推理。半实时建议另下独立组件 Silero VAD；后置可按时长分片。",
            engine = EngineKind.LOCAL_ONNX,
            supportsRealtime = true,
            supportsBatch = true,
            sizeLabel = "~230 MB",
            languages = "中英日韩粤",
        ),
        AsrModel(
            id = "parakeet_v3",
            displayName = "分片预设·25s（SenseVoice）",
            description = "策略预设：后置/半实时最长相关窗约 25s。推理包与 SenseVoice 相同，非独立权重。",
            engine = EngineKind.LOCAL_ONNX,
            supportsRealtime = true,
            supportsBatch = true,
            sizeLabel = "~230 MB",
            languages = "中英日韩粤",
        ),
        AsrModel(
            id = "qwen3_asr",
            displayName = "分片预设·20s（SenseVoice）",
            description = "策略预设：短窗约 20s（对齐桌面听默）。推理包与 SenseVoice 相同，非独立权重。",
            engine = EngineKind.LOCAL_ONNX,
            supportsRealtime = true,
            supportsBatch = true,
            sizeLabel = "~230 MB",
            languages = "中英日韩粤",
        ),
        AsrModel(
            id = "whisper_tiny",
            displayName = "Whisper Tiny (ONNX)",
            description = "本地 Whisper tiny 多语 ONNX。半实时/后置约 30s/片。需先下载。",
            engine = EngineKind.LOCAL_WHISPER,
            supportsRealtime = true,
            supportsBatch = true,
            sizeLabel = "~75 MB",
            languages = "多语",
        ),
    )

    fun require(id: String): AsrModel =
        models.firstOrNull { it.id == id }
            ?: models.first()
}
