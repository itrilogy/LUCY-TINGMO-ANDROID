package lab.luxi.tingmo.asr

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

class ModelDownloader(
    private val modelsRoot: File,
) {
    /**
     * 下载并解压。若已损坏/不完整会先删再下。
     * [force] 为 true 时无视本地缓存强制重下。
     */
    suspend fun download(
        pack: OnnxModelPack,
        onProgress: (Float) -> Unit = {},
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        modelsRoot.mkdirs()
        if (!force && pack.isReady(modelsRoot)) {
            onProgress(1f)
            return@withContext
        }
        // 清除半残文件，避免「显示已就绪」却闪退
        pack.deleteExtracted(modelsRoot)

        val tmp = File(modelsRoot, "${pack.extractDirName}.download")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        val archive = File(tmp, "model.tar.bz2")
        try {
            downloadFile(pack.archiveUrl, archive, onProgress)
            require(archive.length() >= pack.minArchiveBytes) {
                "压缩包过小（${archive.length() / 1_000_000}MB），下载可能被截断或被墙成错误页，请换网络后重试"
            }
            extractArchive(archive, modelsRoot)
            val issue = pack.integrityIssue(modelsRoot)
            if (issue != null) {
                pack.deleteExtracted(modelsRoot)
                throw IllegalStateException("解压校验失败：$issue")
            }
            onProgress(1f)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 300_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "tingmo-android")
        }
        conn.connect()
        require(conn.responseCode in 200..299) { "下载失败 HTTP ${conn.responseCode}" }
        val total = conn.contentLengthLong.coerceAtLeast(1L)
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(256 * 1024)
                var readSum = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    readSum += n
                    // contentLength 在部分 CDN 可能不准，用已读进度上限 0.95
                    val p = if (total > 1) {
                        (readSum.toDouble() / total).toFloat()
                    } else {
                        0.5f
                    }
                    onProgress(p.coerceIn(0f, 0.95f))
                }
            }
        }
        conn.disconnect()
        Log.i(TAG, "downloaded ${dest.name} ${dest.length()} bytes from $url")
    }

    private fun extractArchive(archive: File, destDir: File) {
        destDir.mkdirs()
        val raw = BufferedInputStream(archive.inputStream(), 256 * 1024)
        val name = archive.name.lowercase()
        val tarStream = when {
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") ->
                TarArchiveInputStream(BZip2CompressorInputStream(raw))
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                TarArchiveInputStream(GzipCompressorInputStream(raw))
            name.endsWith(".gz") ->
                TarArchiveInputStream(GZIPInputStream(raw))
            else -> TarArchiveInputStream(BZip2CompressorInputStream(raw))
        }
        tarStream.use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                // 防止 zip-slip
                val out = File(destDir, entry.name).canonicalFile
                require(out.path.startsWith(destDir.canonicalPath)) {
                    "非法压缩路径：${entry.name}"
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos ->
                    tar.copyTo(fos, 256 * 1024)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ModelDownloader"
    }
}
