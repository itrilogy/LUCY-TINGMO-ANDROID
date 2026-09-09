package lab.luxi.tingmo.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import lab.luxi.tingmo.domain.AsrModel
import lab.luxi.tingmo.domain.EngineKind
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

/**
 * Realtime engine backed by Android SpeechRecognizer.
 */
class SystemSpeechTranscriber(
    private val context: Context,
) : RealtimeTranscriber {
    private var recognizer: SpeechRecognizer? = null

    override fun supports(model: AsrModel): Boolean =
        model.engine == EngineKind.SYSTEM_SPEECH && model.supportsRealtime

    override fun start(model: AsrModel): Flow<TranscribeEvent> = callbackFlow {
        val probe = SpeechCompat.probe(context)
        val speech = SpeechCompat.createRecognizer(context)
        if (speech == null || (!probe.available && probe.component == null)) {
            trySend(TranscribeEvent.Error(SpeechCompat.unsupportedMessage(probe)))
            trySend(TranscribeEvent.Ended)
            close()
            return@callbackFlow
        }
        recognizer = speech

        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到有效语音"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "听写超时，请重试"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "网络错误（部分机型系统识别需联网）"
                    else -> "识别错误 ($error)"
                }
                trySend(TranscribeEvent.Error(msg))
                trySend(TranscribeEvent.Ended)
                close()
            }

            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = texts?.firstOrNull().orEmpty()
                if (text.isNotBlank()) trySend(TranscribeEvent.Final(text))
                trySend(TranscribeEvent.Ended)
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val texts =
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = texts?.firstOrNull().orEmpty()
                if (text.isNotBlank()) trySend(TranscribeEvent.Partial(text))
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
        }
        speech.startListening(intent)

        awaitClose { stop() }
    }

    override fun stop() {
        runCatching {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
    }
}
