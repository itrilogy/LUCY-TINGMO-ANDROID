package lab.luxi.tingmo.asr

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16 kHz mono PCM16 WAV 读写工具：支持流式写头、按样本区间读，避免整段进内存。
 */
object PcmWavIO {
    const val HEADER_SIZE = 44
    const val SAMPLE_RATE = WavRecorder.SAMPLE_RATE

    data class WavInfo(
        val sampleRate: Int,
        val dataOffset: Int,
        val dataSize: Int,
        val sampleCount: Int,
        val durationMs: Long,
    )

    fun writeHeader(raf: RandomAccessFile, dataSize: Int, sampleRate: Int = SAMPLE_RATE) {
        val byteRate = sampleRate * 2
        raf.seek(0)
        raf.write("RIFF".toByteArray())
        raf.write(intLE(36 + dataSize))
        raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray())
        raf.write(intLE(16))
        raf.write(shortLE(1))
        raf.write(shortLE(1))
        raf.write(intLE(sampleRate))
        raf.write(intLE(byteRate))
        raf.write(shortLE(2))
        raf.write(shortLE(16))
        raf.write("data".toByteArray())
        raf.write(intLE(dataSize))
    }

    fun probe(file: File): WavInfo {
        require(file.exists() && file.length() >= 12) { "无效音频文件" }
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(4)
            raf.readFully(riff)
            if (String(riff) != "RIFF") {
                // 裸 PCM：整文件即数据
                val dataSize = (file.length().toInt() and 1.inv())
                return WavInfo(
                    sampleRate = SAMPLE_RATE,
                    dataOffset = 0,
                    dataSize = dataSize,
                    sampleCount = dataSize / 2,
                    durationMs = dataSize / 2 * 1000L / SAMPLE_RATE,
                )
            }
            raf.seek(8)
            raf.readFully(riff)
            require(String(riff) == "WAVE") { "非 WAVE 文件" }

            var dataOffset = -1
            var dataSize = 0
            var sampleRate = SAMPLE_RATE
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(riff)
                val sizeBytes = ByteArray(4)
                raf.readFully(sizeBytes)
                val chunkSize = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).int
                val chunkName = String(riff)
                val chunkStart = raf.filePointer
                when (chunkName) {
                    "fmt " -> {
                        if (chunkSize >= 16) {
                            val fmt = ByteArray(16)
                            raf.readFully(fmt)
                            val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                            bb.short // audio format
                            bb.short // channels
                            sampleRate = bb.int
                        }
                    }
                    "data" -> {
                        dataOffset = chunkStart.toInt()
                        dataSize = chunkSize.coerceAtMost((raf.length() - chunkStart).toInt())
                        break
                    }
                }
                raf.seek(chunkStart + chunkSize + (chunkSize and 1))
            }
            require(dataOffset >= 0) { "WAV 缺少 data 块" }
            dataSize = dataSize and 1.inv()
            val samples = dataSize / 2
            return WavInfo(
                sampleRate = sampleRate,
                dataOffset = dataOffset,
                dataSize = dataSize,
                sampleCount = samples,
                durationMs = if (sampleRate <= 0) 0L else samples * 1000L / sampleRate,
            )
        }
    }

    /** 读取 [fromSample, toSample) 区间（不含 to）。 */
    fun readSamples(file: File, fromSample: Int, toSample: Int): ShortArray {
        if (toSample <= fromSample) return ShortArray(0)
        val info = probe(file)
        val from = fromSample.coerceIn(0, info.sampleCount)
        val to = toSample.coerceIn(from, info.sampleCount)
        val count = to - from
        if (count <= 0) return ShortArray(0)
        val out = ShortArray(count)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset.toLong() + from * 2L)
            val bytes = ByteArray(count * 2)
            raf.readFully(bytes)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        }
        return out
    }

    fun sampleCount(file: File): Int = probe(file).sampleCount

    fun durationMs(file: File): Long = probe(file).durationMs

    private fun intLE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun shortLE(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
}
