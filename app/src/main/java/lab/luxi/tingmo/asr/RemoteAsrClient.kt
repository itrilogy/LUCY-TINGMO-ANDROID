package lab.luxi.tingmo.asr

import lab.luxi.tingmo.data.RemoteAsrSettings
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * OpenAI 兼容：POST /audio/transcriptions（multipart）。
 */
class RemoteAsrClient {
    suspend fun transcribeFile(
        settings: RemoteAsrSettings,
        wavFile: File,
        language: String = "zh",
    ): String = withContext(Dispatchers.IO) {
        require(settings.enabled) { "请先启用远端语音 API" }
        require(settings.apiKey.isNotBlank()) { "请填写远端 ASR API Key" }
        require(wavFile.exists()) { "录音文件不存在" }

        val boundary = "----TingmoBoundary${System.currentTimeMillis()}"
        val url = URL("${settings.baseUrl.trimEnd('/')}/audio/transcriptions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(conn.outputStream).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                out.writeBytes("$value\r\n")
            }
            field("model", settings.model)
            field("language", language)
            out.writeBytes("--$boundary\r\n")
            out.writeBytes(
                "Content-Disposition: form-data; name=\"file\"; filename=\"${wavFile.name}\"\r\n",
            )
            out.writeBytes("Content-Type: audio/wav\r\n\r\n")
            wavFile.inputStream().use { it.copyTo(out) }
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()
        conn.disconnect()
        require(code in 200..299) { "远端 ASR 失败 HTTP $code：$resp" }
        val json = JSONObject(resp)
        when {
            json.has("text") -> json.getString("text").trim()
            else -> resp.trim()
        }
    }
}
