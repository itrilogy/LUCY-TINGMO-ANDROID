package lab.luxi.tingmo.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import lab.luxi.tingmo.domain.AsrModel
import lab.luxi.tingmo.domain.TimedSegment
import lab.luxi.tingmo.domain.segmentWindowMs
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Post-hoc (batch) transcription with **duration-based segmentation**.
 *
 * While the user speaks, we run Android SpeechRecognizer in successive windows
 * sized by [AsrModel.segmentWindowMs] (e.g. Qwen3 ≈ 20s). Each window becomes
 * one [TimedSegment]. Local ONNX weights can later replace the recognizer per
 * window without changing the session/UI contract.
 */
class SegmentedBatchSession(
    private val context: Context,
) {
    sealed class Event {
        data class Elapsed(val elapsedMs: Long, val windowMs: Long, val segmentIndex: Int) : Event()
        data class Partial(val text: String, val elapsedMs: Long) : Event()
        data class SegmentCommitted(val segment: TimedSegment, val joined: String) : Event()
        data class Finished(
            val segments: List<TimedSegment>,
            val joined: String,
            val durationMs: Long,
            val engineNote: String,
        ) : Event()
        data class Error(val message: String) : Event()
    }

    interface Listener {
        fun onEvent(event: Event)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var model: AsrModel? = null
    private var windowMs: Long = 20_000L
    private var sessionStartElapsedRealtime = 0L
    private var segmentStartMs = 0L
    private var segmentIndex = 0
    private val segments = mutableListOf<TimedSegment>()
    private var currentPartial = ""
    private var finishing = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!active.get()) return
            val elapsed = elapsedMs()
            listener?.onEvent(
                Event.Elapsed(elapsed, windowMs, segmentIndex),
            )
            // Force end of window → commit whatever we have and start next slice
            if (!finishing && elapsed - segmentStartMs >= windowMs) {
                forceCommitAndContinue("window")
            } else {
                mainHandler.postDelayed(this, 250L)
            }
        }
    }

    fun start(model: AsrModel, listener: Listener) {
        stopInternal(emitFinished = false)
        val probe = SpeechCompat.probe(context)
        if (!probe.available && probe.component == null) {
            listener.onEvent(Event.Error(SpeechCompat.unsupportedMessage(probe)))
            return
        }
        this.listener = listener
        this.model = model
        this.windowMs = model.segmentWindowMs()
        this.sessionStartElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        this.segmentStartMs = 0L
        this.segmentIndex = 0
        this.segments.clear()
        this.currentPartial = ""
        this.finishing = false
        active.set(true)
        mainHandler.post {
            startListeningWindow()
            mainHandler.post(tickRunnable)
        }
    }

    fun stop() {
        if (!active.getAndSet(false)) return
        finishing = true
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post {
            // Prefer final results from stopListening; then finish.
            val speech = recognizer
            if (speech != null) {
                runCatching { speech.stopListening() }
                // Give recognizer a brief moment; then force commit + finish
                mainHandler.postDelayed({
                    commitCurrentIfNeeded(elapsedMs())
                    destroyRecognizer()
                    emitFinished()
                }, 600L)
            } else {
                commitCurrentIfNeeded(elapsedMs())
                emitFinished()
            }
        }
    }

    private fun forceCommitAndContinue(reason: String) {
        if (!active.get() || finishing) return
        val end = elapsedMs()
        commitCurrentIfNeeded(end)
        segmentStartMs = end
        segmentIndex = segments.size
        currentPartial = ""
        // Restart recognizer for next window
        destroyRecognizer()
        if (active.get() && !finishing) {
            startListeningWindow()
            mainHandler.removeCallbacks(tickRunnable)
            mainHandler.post(tickRunnable)
        }
    }

    private fun startListeningWindow() {
        destroyRecognizer()
        val speech = SpeechCompat.createRecognizer(context)
        if (speech == null) {
            listener?.onEvent(Event.Error(SpeechCompat.unsupportedMessage(SpeechCompat.probe(context))))
            active.set(false)
            return
        }
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (!active.get()) return
                // No-match / timeout: still advance if window ended; otherwise restart quietly
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_CLIENT,
                    -> {
                        if (finishing) {
                            commitCurrentIfNeeded(elapsedMs())
                            destroyRecognizer()
                            emitFinished()
                        } else if (elapsedMs() - segmentStartMs >= windowMs * 0.85) {
                            forceCommitAndContinue("error-window")
                        } else {
                            // brief pause then listen again in same window
                            mainHandler.postDelayed({
                                if (active.get() && !finishing) startListeningWindow()
                            }, 200L)
                        }
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        listener?.onEvent(Event.Error("缺少麦克风权限"))
                        active.set(false)
                        destroyRecognizer()
                    }
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    -> {
                        listener?.onEvent(
                            Event.Error("系统识别需要网络（部分机型）。请联网后重试。"),
                        )
                        // keep session; try again
                        mainHandler.postDelayed({
                            if (active.get() && !finishing) startListeningWindow()
                        }, 800L)
                    }
                    else -> {
                        mainHandler.postDelayed({
                            if (active.get() && !finishing) startListeningWindow()
                        }, 300L)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                if (text.isNotBlank()) {
                    currentPartial = text
                    listener?.onEvent(Event.Partial(joinedPreview(text), elapsedMs()))
                }
                if (finishing) {
                    commitCurrentIfNeeded(elapsedMs())
                    destroyRecognizer()
                    emitFinished()
                } else {
                    // End of an utterance: commit slice and continue (also respects window)
                    val end = elapsedMs()
                    commitCurrentIfNeeded(end)
                    if (end - segmentStartMs >= windowMs) {
                        segmentStartMs = end
                    }
                    segmentIndex = segments.size
                    currentPartial = ""
                    destroyRecognizer()
                    if (active.get()) {
                        mainHandler.postDelayed({
                            if (active.get() && !finishing) startListeningWindow()
                        }, 150L)
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                if (text.isBlank()) return
                currentPartial = text
                listener?.onEvent(Event.Partial(joinedPreview(text), elapsedMs()))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Encourage longer capture within the window when supported
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        }
        runCatching { speech.startListening(intent) }
            .onFailure {
                listener?.onEvent(Event.Error(it.message ?: "无法启动识别"))
            }
    }

    private fun joinedPreview(ongoing: String): String {
        val base = segments.joinToString("\n") { it.text }.trim()
        return when {
            base.isBlank() -> ongoing
            ongoing.isBlank() -> base
            else -> "$base\n$ongoing"
        }
    }

    private fun commitCurrentIfNeeded(endMs: Long) {
        val text = currentPartial.trim()
        if (text.isBlank()) return
        // Avoid duplicating identical consecutive commits
        if (segments.lastOrNull()?.text == text && segments.lastOrNull()?.startMs == segmentStartMs) {
            return
        }
        val end = max(endMs, segmentStartMs + 1)
        val seg = TimedSegment(
            index = segments.size,
            startMs = segmentStartMs,
            endMs = end,
            text = text,
        )
        segments += seg
        currentPartial = ""
        segmentStartMs = end
        listener?.onEvent(
            Event.SegmentCommitted(
                segment = seg,
                joined = segments.joinToString("\n") { it.text },
            ),
        )
    }

    private fun emitFinished() {
        val duration = elapsedMs()
        val joined = segments.joinToString("\n") { it.text }.trim()
        val note = buildString {
            append("按时长分片转写完成：共 ${segments.size} 段")
            append("，窗长约 ${windowMs / 1000}s")
            append("（模型 ${model?.displayName ?: "?"}）")
            append("。识别后端：系统 SpeechRecognizer")
            if (model?.engine != lab.luxi.tingmo.domain.EngineKind.SYSTEM_SPEECH) {
                append("；本地权重接入后将替换为 ${model?.displayName} 推理")
            }
        }
        listener?.onEvent(
            Event.Finished(
                segments = segments.toList(),
                joined = joined,
                durationMs = duration,
                engineNote = note,
            ),
        )
        listener = null
        model = null
    }

    private fun elapsedMs(): Long =
        android.os.SystemClock.elapsedRealtime() - sessionStartElapsedRealtime

    private fun destroyRecognizer() {
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
    }

    private fun stopInternal(emitFinished: Boolean) {
        active.set(false)
        finishing = false
        mainHandler.removeCallbacks(tickRunnable)
        destroyRecognizer()
        if (emitFinished) emitFinished()
        segments.clear()
        currentPartial = ""
        listener = null
    }
}
