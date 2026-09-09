package lab.luxi.tingmo.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlin.math.max

/**
 * 优先选用「录音机式」宽拾音音源，避免 VOICE_COMMUNICATION / VOICE_RECOGNITION
 * 的近讲降噪把远处人声压掉。
 */
object AudioRecordFactory {
    private const val TAG = "AudioRecordFactory"

    fun create(sampleRate: Int = WavRecorder.SAMPLE_RATE): Pair<AudioRecord, Int> {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuf > 0) { "AudioRecord.getMinBufferSize 失败 ($minBuf)" }
        // 更大缓冲，降低丢包、利于远处弱信号累积
        val bufSize = max(minBuf * 2, sampleRate / 2 * 2)

        val sources = buildList {
            add(MediaRecorder.AudioSource.MIC)
            add(MediaRecorder.AudioSource.CAMCORDER)
            add(MediaRecorder.AudioSource.DEFAULT)
            if (Build.VERSION.SDK_INT >= 24) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            // 近讲音源放最后（容易「必须贴麦」）
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }

        var lastError: Exception? = null
        for (source in sources) {
            try {
                val recorder = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "AudioRecord OK source=$source buf=$bufSize")
                    return recorder to bufSize
                }
                recorder.release()
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "AudioRecord source=$source failed: ${e.message}")
            }
        }
        throw IllegalStateException("无法初始化麦克风", lastError)
    }
}
