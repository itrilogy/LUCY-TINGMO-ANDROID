package lab.luxi.tingmo.asr

import android.media.AudioRecord
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 流式 PCM16 采集：宽拾音 + 块级 AGC，**边录边写 WAV**（不在 RAM 堆积整堂课）。
 */
class StreamingPcmRecorder {
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val queue = LinkedBlockingQueue<ShortArray>(64)
    private var raf: RandomAccessFile? = null
    private var outFile: File? = null
    private var bytesWritten = 0L
    private var recentPeak = 500

    @Volatile
    var peakLevel: Float = 0f
        private set

    fun start(targetWav: File) {
        if (!running.compareAndSet(false, true)) return
        queue.clear()
        recentPeak = 500
        peakLevel = 0f
        bytesWritten = 0L
        targetWav.parentFile?.mkdirs()
        if (targetWav.exists()) targetWav.delete()
        outFile = targetWav
        val file = RandomAccessFile(targetWav, "rw")
        // 预留 WAV 头，停止时回写正确长度
        file.write(ByteArray(PcmWavIO.HEADER_SIZE))
        raf = file

        val (recorder, bufSize) = AudioRecordFactory.create(WavRecorder.SAMPLE_RATE)
        val shortBufLen = max(bufSize / 2, WavRecorder.SAMPLE_RATE / 10)
        audioRecord = recorder
        recorder.startRecording()
        Thread.sleep(120)
        thread = thread(name = "tingmo-pcm-stream", isDaemon = true) {
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
                    synchronized(this@StreamingPcmRecorder) {
                        raf?.write(bytes)
                        bytesWritten += bytes.size
                    }
                    peakLevel = min(1f, samplePeak / 32768f)
                    if (!queue.offer(boosted)) {
                        queue.poll()
                        queue.offer(boosted)
                    }
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error $n")
                }
            }
        }
    }

    fun read(maxWaitMs: Long = 50): ShortArray {
        return queue.poll() ?: ShortArray(0)
    }

    /**
     * 停止采集并回写 WAV 头。返回落盘文件；失败或空录返回 null。
     */
    fun stopToWav(): File? {
        if (!running.getAndSet(false)) {
            return outFile?.takeIf { it.exists() && it.length() > PcmWavIO.HEADER_SIZE }
        }
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        thread?.join(1500)
        thread = null
        queue.clear()
        peakLevel = 0f

        val file = outFile
        val access = raf
        raf = null
        outFile = null
        if (file == null || access == null) return null
        return try {
            val dataSize = (bytesWritten.toInt() and 1.inv())
            PcmWavIO.writeHeader(access, dataSize, WavRecorder.SAMPLE_RATE)
            access.close()
            if (dataSize <= 0) {
                file.delete()
                null
            } else {
                file
            }
        } catch (e: Exception) {
            Log.e(TAG, "finalize wav failed", e)
            runCatching { access.close() }
            null
        }
    }

    fun cancel() {
        running.set(false)
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        thread?.join(500)
        thread = null
        queue.clear()
        peakLevel = 0f
        runCatching { raf?.close() }
        raf = null
        outFile?.delete()
        outFile = null
        bytesWritten = 0L
    }

    companion object {
        private const val TAG = "StreamingPcmRecorder"
    }
}
