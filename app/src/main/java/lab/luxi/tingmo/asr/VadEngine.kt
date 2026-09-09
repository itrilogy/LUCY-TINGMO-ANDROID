package lab.luxi.tingmo.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Silero VAD（**必须用 sherpa-onnx 发行包** `silero_vad.onnx`，约 630KB）。
 * 不要用上游 Silero 官方 ~2MB / v5 权重，接口与量化不同，会导致乱切段、乱转写。
 *
 * 半实时：按停顿出片；[maxSpeechSec] 为强制最长一片。
 */
class VadEngine(private val context: Context) {
    fun vadFile(): File = File(context.filesDir, "onnx-models/silero_vad.onnx")

    fun isReady(): Boolean {
        val f = vadFile()
        // 官方 sherpa 包约 643854；过小/过大都可疑（过大可能是误下了 v5）
        return f.exists() &&
            f.length() in MIN_VAD_BYTES..MAX_VAD_BYTES &&
            !OnnxModelPack.looksLikeHtmlOrText(f)
    }

    fun integrityIssue(): String? {
        val f = vadFile()
        if (!f.exists()) return "未安装 Silero VAD"
        if (OnnxModelPack.looksLikeHtmlOrText(f)) return "VAD 文件无效（可能是网页错误页）"
        if (f.length() < MIN_VAD_BYTES) return "VAD 文件过小（${f.length()}），请重新下载"
        if (f.length() > MAX_VAD_BYTES) {
            return "VAD 文件过大（${f.length() / 1000}KB）。请移除后重新下载 sherpa 版 silero_vad.onnx（约 630KB），勿使用 Silero v5。"
        }
        return null
    }

    suspend fun ensureDownloaded(onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (isReady()) {
            onProgress(1f)
            return@withContext
        }
        val dest = vadFile()
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val url = DOWNLOAD_URL
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "tingmo-android")
        }
        conn.connect()
        require(conn.responseCode in 200..299) { "VAD 下载失败 HTTP ${conn.responseCode}" }
        val total = conn.contentLengthLong.coerceAtLeast(1L)
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var sum = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    sum += n
                    onProgress((sum.toDouble() / total).toFloat().coerceIn(0f, 1f))
                }
            }
        }
        conn.disconnect()
        val issue = integrityIssue()
        if (issue != null) {
            dest.delete()
            throw IllegalStateException(issue)
        }
        Log.i(TAG, "silero_vad saved ${dest.length()} bytes (sherpa-onnx)")
    }

    /**
     * 每次半实时会话创建新实例**，避免跨会话状态污染导致乱切。
     * 调用方负责 [release]。
     */
    fun createSession(maxSpeechSec: Float): Vad? {
        val issue = integrityIssue()
        if (issue != null) {
            Log.w(TAG, issue)
            return null
        }
        val capped = maxSpeechSec.coerceIn(1.5f, 20f)
        // 课堂远场 + 软件增益：略提高门槛，减少噪声碎段 → SenseVoice 幻觉乱字
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadFile().absolutePath,
                threshold = 0.50f,
                minSilenceDuration = 0.50f,
                minSpeechDuration = 0.40f,
                windowSize = WINDOW_SIZE,
                maxSpeechDuration = capped,
            ),
            sampleRate = 16_000,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        return try {
            Vad(assetManager = null, config = config).also {
                Log.i(TAG, "VAD session created maxSpeech=${capped}s fileBytes=${vadFile().length()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD create failed", e)
            null
        }
    }

    fun release(vad: Vad?) {
        runCatching { vad?.release() }
    }

    /** 喂入 float PCM（须为 windowSize 的整数倍更佳），弹出已完成语音段 */
    fun acceptAndPop(vad: Vad, samples: FloatArray): List<SpeechSegment> {
        if (samples.isEmpty()) return emptyList()
        vad.acceptWaveform(samples)
        return drain(vad)
    }

    fun flush(vad: Vad): List<SpeechSegment> {
        vad.flush()
        return drain(vad)
    }

    private fun drain(vad: Vad): List<SpeechSegment> {
        val out = mutableListOf<SpeechSegment>()
        while (!vad.empty()) {
            out += vad.front()
            vad.pop()
        }
        return out
    }

    companion object {
        private const val TAG = "VadEngine"
        const val WINDOW_SIZE = 512
        const val DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        /** sherpa 发行包约 643854 */
        private const val MIN_VAD_BYTES = 500_000L
        /** 超过则可能是误下的官方 v5（~2.3MB） */
        private const val MAX_VAD_BYTES = 1_200_000L
    }
}
