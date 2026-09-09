package lab.luxi.tingmo.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import lab.luxi.tingmo.domain.AsrModel
import lab.luxi.tingmo.domain.TimedSegment
import lab.luxi.tingmo.domain.segmentWindowMs
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 本地 ONNX 真推理引擎（sherpa-onnx + SenseVoice / Whisper）。
 */
class OnnxAsrEngine(private val context: Context) {
    private val mutex = Mutex()
    private val loaded = AtomicReference<Loaded?>(null)

    data class Loaded(
        val modelId: String,
        val recognizer: OfflineRecognizer,
        val pack: OnnxModelPack,
    )

    fun modelsRoot(): File = File(context.filesDir, "onnx-models").also { it.mkdirs() }

    fun isModelReady(model: AsrModel): Boolean {
        val pack = OnnxModelPack.forModel(model) ?: return false
        return pack.isReady(modelsRoot())
    }

    fun modelIntegrityIssue(model: AsrModel): String? {
        val pack = OnnxModelPack.forModel(model) ?: return null
        return pack.integrityIssue(modelsRoot())
    }

    suspend fun ensureLoaded(model: AsrModel): Loaded = mutex.withLock {
        val pack = OnnxModelPack.forModel(model)
            ?: throw IllegalStateException("${model.displayName} 暂无本地 ONNX 包")
        val issue = pack.integrityIssue(modelsRoot())
        if (issue != null) {
            // 半残文件会导致 native Ort::Exception 直接 abort，绝不进入 OfflineRecognizer
            throw IllegalStateException(
                "本地模型不可用：$issue。请打开「模型」页移除后重新下载（完整约 230MB）。",
            )
        }
        val cur = loaded.get()
        // SenseVoice 多别名共享同一权重：按 extractDir 复用
        if (cur != null && cur.pack.extractDirName == pack.extractDirName) {
            return@withLock cur.copy(modelId = model.id, pack = pack)
        }
        cur?.recognizer?.release()
        loaded.set(null)
        val recognizer = withContext(Dispatchers.Default) { createRecognizer(pack) }
        val next = Loaded(model.id, recognizer, pack)
        loaded.set(next)
        next
    }

    fun unload() {
        loaded.getAndSet(null)?.recognizer?.release()
    }

    /**
     * 对整段 PCM16 按时长窗分片推理，返回真实转写文本。
     * 长课请优先用 [transcribeWavFileSegmented]，避免整文件进 RAM。
     */
    suspend fun transcribePcmSegmented(
        model: AsrModel,
        pcm16: ShortArray,
        sampleRate: Int = WavRecorder.SAMPLE_RATE,
    ): Pair<List<TimedSegment>, String> = withContext(Dispatchers.Default) {
        val eng = ensureLoaded(model)
        decodeSegmented(eng, pcm16, sampleRate, model.segmentWindowMs())
    }

    /**
     * 按窗从 WAV/PCM 文件读片推理，峰值内存约等于一个策略窗。
     */
    suspend fun transcribeWavFileSegmented(
        model: AsrModel,
        wavFile: File,
    ): Pair<List<TimedSegment>, String> = withContext(Dispatchers.Default) {
        val info = PcmWavIO.probe(wavFile)
        val eng = ensureLoaded(model)
        val windowMs = model.segmentWindowMs()
        val windowSamples =
            ((windowMs / 1000.0) * info.sampleRate).toInt().coerceAtLeast(info.sampleRate / 2)
        val segments = mutableListOf<TimedSegment>()
        var offset = 0
        var index = 0
        Log.i(
            TAG,
            "transcribeWavFileSegmented file=${wavFile.name} samples=${info.sampleCount} windowMs=$windowMs",
        )
        while (offset < info.sampleCount) {
            val end = (offset + windowSamples).coerceAtMost(info.sampleCount)
            val chunk = PcmWavIO.readSamples(wavFile, offset, end)
            val startMs = offset * 1000L / info.sampleRate
            val endMs = end * 1000L / info.sampleRate
            if (hasVoiceEnergy(chunk) || chunk.size >= info.sampleRate) {
                val text = decodeChunk(eng.recognizer, chunk, info.sampleRate)
                Log.i(TAG, "chunk[$index] ${startMs}-${endMs}ms energy=${averageAbs(chunk)} textLen=${text.length}")
                if (text.isNotBlank()) {
                    segments += TimedSegment(index, startMs, endMs, text)
                    index++
                }
            }
            offset = end
        }
        // 全空时仅对开头一小段再试，避免再次整文件加载
        if (segments.isEmpty() && info.sampleCount > 0) {
            val probeEnd = minOf(info.sampleCount, info.sampleRate * 30)
            val probe = PcmWavIO.readSamples(wavFile, 0, probeEnd)
            if (averageAbs(probe) >= 15) {
                val text = decodeChunk(eng.recognizer, probe, info.sampleRate)
                if (text.isNotBlank()) {
                    segments += TimedSegment(0, 0, probeEnd * 1000L / info.sampleRate, text)
                }
            }
        }
        val joined = segments.joinToString("\n") { it.text }.trim()
        segments to joined
    }

    private fun decodeSegmented(
        eng: Loaded,
        pcm16: ShortArray,
        sampleRate: Int,
        windowMs: Long,
    ): Pair<List<TimedSegment>, String> {
        val windowSamples = ((windowMs / 1000.0) * sampleRate).toInt().coerceAtLeast(sampleRate / 2)
        val segments = mutableListOf<TimedSegment>()
        var offset = 0
        var index = 0
        val avgEnergy = averageAbs(pcm16)
        Log.i(TAG, "transcribePcmSegmented samples=${pcm16.size} avgEnergy=$avgEnergy windowMs=$windowMs")
        while (offset < pcm16.size) {
            val end = (offset + windowSamples).coerceAtMost(pcm16.size)
            val chunk = pcm16.copyOfRange(offset, end)
            val startMs = offset * 1000L / sampleRate
            val endMs = end * 1000L / sampleRate
            if (hasVoiceEnergy(chunk) || chunk.size >= sampleRate) {
                val text = decodeChunk(eng.recognizer, chunk, sampleRate)
                Log.i(TAG, "chunk[$index] ${startMs}-${endMs}ms energy=${averageAbs(chunk)} textLen=${text.length}")
                if (text.isNotBlank()) {
                    segments += TimedSegment(index, startMs, endMs, text)
                    index++
                }
            }
            offset = end
        }
        if (segments.isEmpty() && pcm16.isNotEmpty() && averageAbs(pcm16) >= 15) {
            val text = decodeChunk(eng.recognizer, pcm16, sampleRate)
            if (text.isNotBlank()) {
                segments += TimedSegment(0, 0, pcm16.size * 1000L / sampleRate, text)
            }
        }
        val joined = segments.joinToString("\n") { it.text }.trim()
        return segments to joined
    }

    suspend fun transcribePcmOnce(
        model: AsrModel,
        pcm16: ShortArray,
        sampleRate: Int = WavRecorder.SAMPLE_RATE,
    ): String = withContext(Dispatchers.Default) {
        if (pcm16.isEmpty() || !hasVoiceEnergy(pcm16)) return@withContext ""
        val eng = ensureLoaded(model)
        decodeChunk(eng.recognizer, pcm16, sampleRate)
    }

    private fun createRecognizer(pack: OnnxModelPack): OfflineRecognizer {
        val root = modelsRoot()
        val issue = pack.integrityIssue(root)
        require(issue == null) { issue ?: "模型损坏" }
        val dir = root.resolve(pack.extractDirName)
        val config = when (pack.kind) {
            OnnxModelPack.Kind.SENSE_VOICE -> {
                val onnx = dir.resolve("model.int8.onnx")
                val tokens = dir.resolve("tokens.txt")
                Log.i(
                    TAG,
                    "SenseVoice onnx=${onnx.length()}B tokens=${tokens.length()}B path=${onnx.absolutePath}",
                )
                OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = onnx.absolutePath,
                            language = "zh",
                            useInverseTextNormalization = true,
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "sense_voice",
                        debug = false,
                    ),
                )
            }
            OnnxModelPack.Kind.WHISPER -> {
                val enc = dir.resolve("tiny-encoder.int8.onnx").takeIf { it.exists() }
                    ?: dir.resolve("tiny.en-encoder.int8.onnx")
                val dec = dir.resolve("tiny-decoder.int8.onnx").takeIf { it.exists() }
                    ?: dir.resolve("tiny.en-decoder.int8.onnx")
                val tokens = dir.resolve("tiny-tokens.txt").takeIf { it.exists() }
                    ?: dir.resolve("tiny.en-tokens.txt")
                require(enc.exists() && dec.exists() && tokens.exists()) { "Whisper 模型文件缺失" }
                val isEn = enc.name.contains(".en")
                OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = enc.absolutePath,
                            decoder = dec.absolutePath,
                            language = if (isEn) "en" else "zh",
                            task = "transcribe",
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "whisper",
                    ),
                )
            }
        }
        Log.i(TAG, "Loading ONNX recognizer for ${pack.modelId} from ${dir.absolutePath}")
        // 若仍进到损坏文件，native 可能 abort；上层必须先 integrity 校验
        return OfflineRecognizer(assetManager = null, config = config)
    }

    private fun decodeChunk(
        recognizer: OfflineRecognizer,
        pcm16: ShortArray,
        sampleRate: Int,
    ): String {
        // 增益在采集端完成；此处直接送推理，避免三重 AGC
        val floats = FloatArray(pcm16.size) { i -> pcm16[i] / 32768.0f }
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(floats, sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            result.text.trim()
        } finally {
            stream.release()
        }
    }

    companion object {
        private const val TAG = "OnnxAsrEngine"

        fun hasVoiceEnergy(chunk: ShortArray, threshold: Int = -1): Boolean {
            if (chunk.isEmpty()) return false
            val th = if (threshold >= 0) threshold else AudioGain.energyThreshold()
            val boosted = AudioGain.boost(chunk)
            return averageAbs(boosted) >= th
        }

        fun averageAbs(chunk: ShortArray): Int {
            if (chunk.isEmpty()) return 0
            var energy = 0L
            for (s in chunk) energy += kotlin.math.abs(s.toInt())
            return (energy / chunk.size).toInt()
        }
    }
}
