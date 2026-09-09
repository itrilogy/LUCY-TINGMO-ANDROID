package lab.luxi.tingmo.asr

import lab.luxi.tingmo.domain.AsrModel
import lab.luxi.tingmo.domain.TranscriptSegment
import kotlinx.coroutines.flow.Flow

sealed class TranscribeEvent {
    data class Partial(val text: String) : TranscribeEvent()
    data class Final(val text: String) : TranscribeEvent()
    data class Error(val message: String) : TranscribeEvent()
    data object Ended : TranscribeEvent()
}

interface RealtimeTranscriber {
    fun supports(model: AsrModel): Boolean
    fun start(model: AsrModel): Flow<TranscribeEvent>
    fun stop()
}

interface BatchTranscriber {
    fun supports(model: AsrModel): Boolean
    suspend fun transcribeFile(model: AsrModel, pcm16kMono: ShortArray): String
    suspend fun simulateFromRecording(model: AsrModel, durationMs: Long, hint: String = ""): String
}
