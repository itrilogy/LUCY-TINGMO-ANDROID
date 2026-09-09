package lab.luxi.tingmo.asr

import lab.luxi.tingmo.domain.AsrModel
import lab.luxi.tingmo.domain.EngineKind
import java.io.File
import java.io.RandomAccessFile

/**
 * sherpa-onnx 可下载模型包。
 * 中文主力：SenseVoice INT8；Whisper tiny 为多语轻量包。
 *
 * 注意：仅「文件存在」不够。损坏/截断的 .onnx 会在 native 层
 * `Protobuf parsing failed` 直接 abort 进程，必须在加载前做体积与头校验。
 */
data class OnnxModelPack(
    val modelId: String,
    val archiveUrl: String,
    val extractDirName: String,
    val kind: Kind,
    /** 压缩包最小字节（防下到 HTML/截断包） */
    val minArchiveBytes: Long,
    /** 主权重文件最小字节 */
    val minPrimaryOnnxBytes: Long,
) {
    enum class Kind { SENSE_VOICE, WHISPER }

    fun modelDir(modelsRoot: File): File = modelsRoot.resolve(extractDirName)

    fun resolvePaths(modelsRoot: File): Pair<File, File>? {
        val dir = modelDir(modelsRoot)
        return when (kind) {
            Kind.SENSE_VOICE -> {
                val onnx = dir.resolve("model.int8.onnx")
                val tokens = dir.resolve("tokens.txt")
                if (onnx.exists() && tokens.exists()) onnx to tokens else null
            }
            Kind.WHISPER -> {
                val enc = dir.resolve("tiny-encoder.int8.onnx")
                    .takeIf { it.exists() }
                    ?: dir.resolve("tiny.en-encoder.int8.onnx")
                val tokens = dir.resolve("tiny-tokens.txt")
                    .takeIf { it.exists() }
                    ?: dir.resolve("tiny.en-tokens.txt")
                if (enc != null && enc.exists() && tokens != null && tokens.exists()) {
                    enc to tokens
                } else {
                    null
                }
            }
        }
    }

    /**
     * @return null 表示完整可用；否则为人类可读损坏原因
     */
    fun integrityIssue(modelsRoot: File): String? {
        val dir = modelDir(modelsRoot)
        if (!dir.isDirectory) return "模型目录不存在"
        return when (kind) {
            Kind.SENSE_VOICE -> {
                val onnx = dir.resolve("model.int8.onnx")
                val tokens = dir.resolve("tokens.txt")
                when {
                    !onnx.exists() -> "缺少 model.int8.onnx"
                    !tokens.exists() -> "缺少 tokens.txt"
                    tokens.length() < 1_000L -> "tokens.txt 异常过小"
                    onnx.length() < minPrimaryOnnxBytes ->
                        "模型不完整（${onnx.length() / 1_000_000}MB < ${minPrimaryOnnxBytes / 1_000_000}MB），请重新下载"
                    looksLikeHtmlOrText(onnx) -> "模型文件不是有效 ONNX（可能下到了网页错误页）"
                    else -> null
                }
            }
            Kind.WHISPER -> {
                val enc = dir.resolve("tiny-encoder.int8.onnx").takeIf { it.exists() }
                    ?: dir.resolve("tiny.en-encoder.int8.onnx")
                val dec = dir.resolve("tiny-decoder.int8.onnx").takeIf { it.exists() }
                    ?: dir.resolve("tiny.en-decoder.int8.onnx")
                val tokens = dir.resolve("tiny-tokens.txt").takeIf { it.exists() }
                    ?: dir.resolve("tiny.en-tokens.txt")
                when {
                    enc == null || !enc.exists() -> "缺少 Whisper encoder"
                    dec == null || !dec.exists() -> "缺少 Whisper decoder"
                    tokens == null || !tokens.exists() -> "缺少 Whisper tokens"
                    enc.length() < minPrimaryOnnxBytes / 2 -> "Whisper encoder 不完整，请重新下载"
                    dec.length() < minPrimaryOnnxBytes / 2 -> "Whisper decoder 不完整，请重新下载"
                    looksLikeHtmlOrText(enc) || looksLikeHtmlOrText(dec) ->
                        "Whisper 权重不是有效 ONNX，请重新下载"
                    else -> null
                }
            }
        }
    }

    fun isReady(modelsRoot: File): Boolean = integrityIssue(modelsRoot) == null

    /** 删除解压目录（保留下载临时目录由 Downloader 处理） */
    fun deleteExtracted(modelsRoot: File) {
        modelDir(modelsRoot).deleteRecursively()
    }

    companion object {
        private const val RELEASE =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

        // 官方包约 163MB 压缩 / model.int8.onnx 约 229MB
        val SENSE_VOICE_INT8 = OnnxModelPack(
            modelId = "sensevoice_small",
            archiveUrl = "$RELEASE/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            extractDirName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
            kind = Kind.SENSE_VOICE,
            minArchiveBytes = 140_000_000L,
            minPrimaryOnnxBytes = 200_000_000L,
        )

        val WHISPER_TINY = OnnxModelPack(
            modelId = "whisper_tiny",
            archiveUrl = "$RELEASE/sherpa-onnx-whisper-tiny.tar.bz2",
            extractDirName = "sherpa-onnx-whisper-tiny",
            kind = Kind.WHISPER,
            minArchiveBytes = 40_000_000L,
            minPrimaryOnnxBytes = 20_000_000L,
        )

        fun forModel(model: AsrModel): OnnxModelPack? = when (model.id) {
            "sensevoice_small", "parakeet_v3", "qwen3_asr" ->
                SENSE_VOICE_INT8.copy(modelId = model.id)
            "whisper_tiny" -> WHISPER_TINY
            else -> null
        }

        fun requiresOnnx(model: AsrModel): Boolean =
            model.engine == EngineKind.LOCAL_ONNX || model.engine == EngineKind.LOCAL_WHISPER

        /** 粗检：拒绝 HTML/纯文本伪文件，避免进 native 后 abort */
        fun looksLikeHtmlOrText(file: File): Boolean {
            if (!file.exists() || file.length() < 8) return true
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val buf = ByteArray(16)
                    raf.readFully(buf)
                    val head = String(buf, Charsets.ISO_8859_1)
                    head.startsWith("<!") ||
                        head.startsWith("<html", ignoreCase = true) ||
                        head.startsWith("<HTML") ||
                        head.startsWith("{") ||
                        head.startsWith("error", ignoreCase = true)
                }
            } catch (_: Exception) {
                true
            }
        }
    }
}
