package lab.luxi.tingmo.domain

/**
 * 非 ASR 主模型的独立组件（如 VAD）。
 * Silero VAD 可配合任意本地 ONNX / 半实时流程，不绑定 SenseVoice。
 */
data class AddonComponent(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeLabel: String,
)

object AddonCatalog {
    const val SILERO_VAD_ID = "silero_vad"

    val sileroVad = AddonComponent(
        id = SILERO_VAD_ID,
        displayName = "Silero VAD（sherpa 版）",
        description = "语音活动检测，半实时按停顿出字幕。必须使用 sherpa-onnx 发行的 silero_vad.onnx（约 630KB），" +
            "不要用 Silero 官方 v5（约 2MB），否则会乱切段、乱转写。",
        sizeLabel = "~630 KB",
    )
}
