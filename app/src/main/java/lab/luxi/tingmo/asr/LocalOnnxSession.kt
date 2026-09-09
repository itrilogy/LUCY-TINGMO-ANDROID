package lab.luxi.tingmo.asr

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import lab.luxi.tingmo.domain.AsrModel
import lab.luxi.tingmo.domain.TimedSegment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 本地 ONNX 会话：
 * - 半实时：优先 Silero VAD 按停顿切段 + [maxSpeechSec] 最长切片；无 VAD 则固定时间窗
 * - 后置：只录音（磁盘流式 WAV），停止后按文件分片真推理
 */
class LocalOnnxSession(
    private val engine: OnnxAsrEngine,
    private val vadEngine: VadEngine,
    private val recordingsDir: File,
) {
    sealed class Event {
        data class Elapsed(
            val elapsedMs: Long,
            val windowMs: Long,
            val segmentIndex: Int,
            val usingVad: Boolean,
        ) : Event()
        data class Partial(val text: String, val elapsedMs: Long) : Event()
        data class SegmentCommitted(val segment: TimedSegment, val joined: String) : Event()
        data class Finished(
            val segments: List<TimedSegment>,
            val joined: String,
            val durationMs: Long,
            val engineNote: String,
            val recordingFile: File?,
        ) : Event()
        data class Error(val message: String) : Event()
    }

    fun interface Listener {
        fun onEvent(event: Event)
    }

    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private var streaming: StreamingPcmRecorder? = null
    private var model: AsrModel? = null
    private var liveCaptions = true
    private var maxSpeechSec = 5f
    private var listener: Listener? = null
    private val segments = mutableListOf<TimedSegment>()
    private var scope: CoroutineScope? = null
    private var worker: Thread? = null
    private var startedAt = 0L
    private var targetWav: File? = null
    private val sessionVad = AtomicReference<Vad?>(null)
    @Volatile private var usingVad = false
    /** 供 stop 时冲刷未满 512 的 float 缓冲 */
    private val pendingFloatsLock = Any()
    private var pendingFloats = FloatArray(0)
    private var pendingFloatSize = 0

    val peakLevel: Float get() = streaming?.peakLevel ?: 0f

    private val tick = object : Runnable {
        override fun run() {
            if (!active.get()) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            listener?.onEvent(
                Event.Elapsed(
                    elapsed,
                    (maxSpeechSec * 1000).toLong(),
                    segments.size,
                    usingVad,
                ),
            )
            main.postDelayed(this, 250L)
        }
    }

    fun start(
        model: AsrModel,
        liveCaptions: Boolean,
        maxSpeechSec: Float,
        listener: Listener,
    ) {
        stopInternal()
        this.model = model
        this.liveCaptions = liveCaptions
        this.maxSpeechSec = maxSpeechSec.coerceIn(1.5f, 20f)
        this.listener = listener
        segments.clear()
        startedAt = SystemClock.elapsedRealtime()
        active.set(true)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        recordingsDir.mkdirs()
        val wav = File(recordingsDir, "tingmo_${System.currentTimeMillis()}.wav")
        targetWav = wav
        val stream = StreamingPcmRecorder()
        try {
            stream.start(wav)
        } catch (e: Exception) {
            listener.onEvent(Event.Error(e.message ?: "无法打开麦克风"))
            active.set(false)
            return
        }
        streaming = stream

        val vad = if (liveCaptions) vadEngine.createSession(this.maxSpeechSec) else null
        sessionVad.set(vad)
        usingVad = vad != null
        if (liveCaptions && vad == null) {
            val why = vadEngine.integrityIssue() ?: "未安装或无法加载"
            Log.w(TAG, "半实时回退时间窗：$why")
            main.post {
                listener.onEvent(
                    Event.Partial("（未启用 VAD：$why；本会话按时间窗切段）", 0),
                )
            }
        }
        main.post(tick)

        worker = thread(name = "tingmo-onnx-asr", isDaemon = true) {
            val windowSamples = ((this.maxSpeechSec * WavRecorder.SAMPLE_RATE).toInt())
                .coerceAtLeast(WavRecorder.SAMPLE_RATE)
            var pending = ShortArray(windowSamples * 2)
            var pendingSize = 0
            val vadWindow = VadEngine.WINDOW_SIZE
            var floatBuf = FloatArray(vadWindow * 8)
            var floatSize = 0

            try {
                while (active.get()) {
                    val chunk = stream.read()
                    if (chunk.isEmpty()) {
                        Thread.sleep(15)
                        continue
                    }
                    if (!liveCaptions) continue

                    val v = sessionVad.get()
                    if (v != null) {
                        // 可增长缓冲，禁止丢样（旧实现满了就 break → 乱切/乱字）
                        val need = floatSize + chunk.size
                        if (need > floatBuf.size) {
                            floatBuf = floatBuf.copyOf(maxOf(need, floatBuf.size * 2))
                        }
                        for (s in chunk) {
                            floatBuf[floatSize++] = s / 32768f
                        }
                        while (floatSize >= vadWindow) {
                            val frame = FloatArray(vadWindow) { i -> floatBuf[i] }
                            val remain = floatSize - vadWindow
                            if (remain > 0) {
                                System.arraycopy(floatBuf, vadWindow, floatBuf, 0, remain)
                            }
                            floatSize = remain
                            val segs = vadEngine.acceptAndPop(v, frame)
                            for (seg in segs) {
                                commitFloatSegment(seg.samples)
                            }
                        }
                        synchronized(pendingFloatsLock) {
                            pendingFloats = floatBuf
                            pendingFloatSize = floatSize
                        }
                    } else {
                        ensureCapacity(pending, pendingSize, chunk.size, windowSamples).also {
                            pending = it
                        }
                        System.arraycopy(chunk, 0, pending, pendingSize, chunk.size)
                        pendingSize += chunk.size
                        while (pendingSize >= windowSamples) {
                            val slice = pending.copyOfRange(0, windowSamples)
                            val remain = pendingSize - windowSamples
                            if (remain > 0) {
                                System.arraycopy(pending, windowSamples, pending, 0, remain)
                            }
                            pendingSize = remain
                            decodeAndCommit(slice)
                        }
                    }
                }
            } catch (e: Exception) {
                if (active.get()) {
                    main.post { listener.onEvent(Event.Error(e.message ?: "录音中断")) }
                }
            }
        }
    }

    fun stop() {
        if (!active.getAndSet(false)) return
        main.removeCallbacks(tick)
        val m = model
        val stream = streaming
        streaming = null
        worker?.join(2500)
        worker = null

        val wav = stream?.stopToWav()
        if (m == null) {
            listener?.onEvent(Event.Error("模型丢失"))
            cleanup()
            return
        }

        // 冲刷 VAD：先喂完未满窗的 float，再 flush
        val vad = sessionVad.getAndSet(null)
        if (vad != null) {
            try {
                val rem: FloatArray
                synchronized(pendingFloatsLock) {
                    rem = if (pendingFloatSize > 0) {
                        pendingFloats.copyOfRange(0, pendingFloatSize)
                    } else {
                        FloatArray(0)
                    }
                    pendingFloatSize = 0
                }
                if (rem.isNotEmpty()) {
                    // 右侧补零到 512 倍数，避免尾巴丢失
                    val pad = (VadEngine.WINDOW_SIZE - rem.size % VadEngine.WINDOW_SIZE) %
                        VadEngine.WINDOW_SIZE
                    val fed = if (pad == 0) rem else rem.copyOf(rem.size + pad)
                    for (seg in vadEngine.acceptAndPop(vad, fed)) {
                        commitFloatSegment(seg.samples)
                    }
                }
                for (seg in vadEngine.flush(vad)) {
                    commitFloatSegment(seg.samples)
                }
            } catch (e: Exception) {
                Log.w(TAG, "vad flush: ${e.message}")
            } finally {
                vadEngine.release(vad)
            }
        }

        val usedVad = usingVad
        scope?.launch {
            try {
                if (wav == null || !wav.exists()) {
                    main.post {
                        listener?.onEvent(Event.Error("录音文件未生成"))
                        cleanup()
                    }
                    return@launch
                }
                val info = PcmWavIO.probe(wav)
                if (!liveCaptions || segments.isEmpty()) {
                    val (segs, joined) = engine.transcribeWavFileSegmented(m, wav)
                    val note =
                        "本地 ONNX 真推理（${m.displayName}）· ${segs.size} 段 · ${info.durationMs}ms · 磁盘流式"
                    main.post {
                        listener?.onEvent(
                            Event.Finished(segs, joined, info.durationMs, note, wav),
                        )
                        cleanup()
                    }
                } else {
                    val lastEnd = segments.lastOrNull()?.endMs ?: 0L
                    val totalMs = info.durationMs
                    if (totalMs > lastEnd + 400) {
                        val from = ((lastEnd * info.sampleRate) / 1000).toInt()
                            .coerceIn(0, info.sampleCount)
                        val tail = PcmWavIO.readSamples(wav, from, info.sampleCount)
                        if (tail.size >= MIN_ASR_SAMPLES && OnnxAsrEngine.hasVoiceEnergy(tail)) {
                            val text = engine.transcribePcmOnce(m, tail)
                            if (text.isNotBlank() && !looksLikeHallucination(text, tail.size)) {
                                val seg = TimedSegment(segments.size, lastEnd, totalMs, text)
                                segments += seg
                                main.post {
                                    listener?.onEvent(
                                        Event.SegmentCommitted(
                                            seg,
                                            segments.joinToString("\n") { it.text },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    val joined = segments.joinToString("\n") { it.text }.trim()
                    val modeNote = if (usedVad) {
                        "半实时·VAD停顿切段+最长${maxSpeechSec}s"
                    } else {
                        "半实时·时间窗${maxSpeechSec}s（无有效 VAD）"
                    }
                    val note =
                        "本地 ONNX（$modeNote）（${m.displayName}）· ${segments.size} 段 · 磁盘流式"
                    main.post {
                        listener?.onEvent(
                            Event.Finished(segments.toList(), joined, totalMs, note, wav),
                        )
                        cleanup()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ONNX finish failed", e)
                main.post {
                    listener?.onEvent(Event.Error(e.message ?: "ONNX 推理失败"))
                    cleanup()
                }
            }
        } ?: run {
            listener?.onEvent(Event.Error("会话已销毁"))
            cleanup()
        }
    }

    private fun ensureCapacity(
        buf: ShortArray,
        size: Int,
        add: Int,
        windowSamples: Int,
    ): ShortArray {
        val need = size + add
        if (need <= buf.size) return buf
        val next = ShortArray(maxOf(need, windowSamples * 2, buf.size * 2))
        if (size > 0) System.arraycopy(buf, 0, next, 0, size)
        return next
    }

    private fun commitFloatSegment(samples: FloatArray) {
        if (samples.size < MIN_ASR_SAMPLES) {
            Log.d(TAG, "skip short VAD seg samples=${samples.size}")
            return
        }
        // VAD 段能量（已是 float -1..1）；过弱多为噪声误触发
        var energy = 0.0
        for (s in samples) energy += abs(s.toDouble())
        val avg = energy / samples.size
        if (avg < 0.012) {
            Log.d(TAG, "skip low-energy VAD seg avg=$avg")
            return
        }
        val pcm = ShortArray(samples.size) { i ->
            val v = (samples[i] * 32767f).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            v.toShort()
        }
        decodeAndCommit(pcm)
    }

    private fun decodeAndCommit(slice: ShortArray) {
        val m = model ?: return
        if (slice.size < MIN_ASR_SAMPLES) return
        if (!OnnxAsrEngine.hasVoiceEnergy(slice)) return
        try {
            val text = runBlocking { engine.transcribePcmOnce(m, slice) }
            if (text.isBlank()) return
            if (looksLikeHallucination(text, slice.size)) {
                Log.w(TAG, "drop likely hallucination: ${text.take(40)}")
                return
            }
            val endMs = SystemClock.elapsedRealtime() - startedAt
            val durMs = slice.size * 1000L / WavRecorder.SAMPLE_RATE
            val startMs = (endMs - durMs).coerceAtLeast(0)
            val seg = TimedSegment(segments.size, startMs, endMs, text)
            synchronized(segments) { segments += seg }
            val joined = synchronized(segments) { segments.joinToString("\n") { it.text } }
            main.post {
                listener?.onEvent(Event.SegmentCommitted(seg, joined))
                listener?.onEvent(Event.Partial(joined, endMs))
            }
        } catch (e: Exception) {
            Log.w(TAG, "chunk decode failed: ${e.message}")
        }
    }

    /**
     * SenseVoice 对极短/噪声段常见幻觉：重复语气词、无意义叠字。
     * 粗滤，避免 VAD 碎段把课堂稿写成「嗯嗯嗯 / 的的的」。
     */
    private fun looksLikeHallucination(text: String, sampleCount: Int): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val sec = sampleCount.toDouble() / WavRecorder.SAMPLE_RATE
        if (sec < 0.6 && t.length <= 2) return true
        val compact = t.replace("\\s".toRegex(), "")
        if (compact.length >= 4) {
            val allSame = compact.all { it == compact[0] }
            if (allSame) return true
        }
        val fillers = listOf("嗯", "啊", "呃", "哦", "唔", "嘿")
        if (compact.length <= 6 && fillers.any { f -> compact.count { it.toString() == f } >= 3 }) {
            return true
        }
        return false
    }

    private fun cleanup() {
        vadEngine.release(sessionVad.getAndSet(null))
        scope?.cancel()
        scope = null
        listener = null
        model = null
        targetWav = null
        usingVad = false
        synchronized(pendingFloatsLock) {
            pendingFloats = FloatArray(0)
            pendingFloatSize = 0
        }
    }

    private fun stopInternal() {
        active.set(false)
        main.removeCallbacks(tick)
        streaming?.cancel()
        streaming = null
        worker = null
        vadEngine.release(sessionVad.getAndSet(null))
        scope?.cancel()
        scope = null
        listener = null
        model = null
        targetWav = null
        usingVad = false
        segments.clear()
        synchronized(pendingFloatsLock) {
            pendingFloats = FloatArray(0)
            pendingFloatSize = 0
        }
    }

    companion object {
        private const val TAG = "LocalOnnxSession"
        /** 短于约 0.45s 的 VAD 段不送 ASR，抑制幻觉乱字 */
        private val MIN_ASR_SAMPLES = (0.45f * WavRecorder.SAMPLE_RATE).toInt()
    }
}
