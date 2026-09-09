package lab.luxi.tingmo.asr

import android.media.AudioRecord
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 16 kHz mono PCM16 → 磁盘流式 WAV（与 [StreamingPcmRecorder] 同策略，避免长课 OOM）。
 */
class WavRecorder {
    companion object {
        const val SAMPLE_RATE = 16_000
    }

    private val running = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var raf: RandomAccessFile? = null
    private var outFile: File? = null
    private var bytesWritten = 0L

    @Volatile var peakLevel: Float = 0f
        private set

    @Volatile var elapsedMs: Long = 0L
        private set

    private var startedAt = 0L

    fun start(targetWav: File) {
        if (!running.compareAndSet(false, true)) return
        peakLevel = 0f
        elapsedMs = 0L
        bytesWritten = 0L
        startedAt = android.os.SystemClock.elapsedRealtime()
        targetWav.parentFile?.mkdirs()
        if (targetWav.exists()) targetWav.delete()
        outFile = targetWav
        val access = RandomAccessFile(targetWav, "rw")
        access.write(ByteArray(PcmWavIO.HEADER_SIZE))
        raf = access

        val (recorder, bufSize) = AudioRecordFactory.create(SAMPLE_RATE)
        val shortBufLen = max(bufSize / 2, SAMPLE_RATE / 10)
        audioRecord = recorder
        recorder.startRecording()
        Thread.sleep(120)
        var recentPeak = 500
        recordThread = thread(name = "tingmo-wav-recorder", isDaemon = true) {
            val buf = ShortArray(shortBufLen)
            val scratch = ByteBuffer.allocate(shortBufLen * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (running.get()) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) {
                    val raw = buf.copyOf(n)
                    val (boosted, peak) = AudioGain.boostChunk(raw, recentPeak)
                    recentPeak = peak
                    var samplePeak = 0
                    scratch.clear()
                    for (s in boosted) {
                        scratch.putShort(s)
                        val a = abs(s.toInt())
                        if (a > samplePeak) samplePeak = a
                    }
                    val bytes = scratch.array().copyOf(n * 2)
                    synchronized(this@WavRecorder) {
                        raf?.write(bytes)
                        bytesWritten += bytes.size
                    }
                    peakLevel = min(1f, samplePeak / 32768f)
                    elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
                }
            }
        }
    }

    fun stopToFile(outFile: File = this.outFile ?: error("未 start")): RecordingResult {
        if (!running.getAndSet(false)) {
            return RecordingResult(outFile, 0L, ShortArray(0))
        }
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        recordThread?.join(1000)
        recordThread = null
        val access = raf
        raf = null
        val dataSize = (bytesWritten.toInt() and 1.inv())
        if (access != null) {
            PcmWavIO.writeHeader(access, dataSize, SAMPLE_RATE)
            access.close()
        }
        val target = this.outFile ?: outFile
        this.outFile = null
        // 兼容旧调用方：仅读入短录音的 PCM；长课请直接用文件路径
        val pcm = if (dataSize > 0 && dataSize <= SAMPLE_RATE * 2 * 120) {
            PcmWavIO.readSamples(target, 0, dataSize / 2)
        } else {
            ShortArray(0)
        }
        val duration = if (dataSize > 0) dataSize / 2 * 1000L / SAMPLE_RATE else 0L
        elapsedMs = duration
        peakLevel = 0f
        return RecordingResult(target, duration, pcm)
    }

    fun cancel() {
        running.set(false)
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        recordThread = null
        runCatching { raf?.close() }
        raf = null
        outFile?.delete()
        outFile = null
        peakLevel = 0f
        bytesWritten = 0L
    }

    data class RecordingResult(
        val file: File,
        val durationMs: Long,
        val pcm16: ShortArray,
    )
}
