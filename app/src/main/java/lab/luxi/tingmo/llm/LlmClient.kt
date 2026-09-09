package lab.luxi.tingmo.llm

import lab.luxi.tingmo.data.LlmSettings
import lab.luxi.tingmo.domain.CorrectionRule
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlmClient {
    suspend fun polish(
        settings: LlmSettings,
        promptTemplate: String,
        rawText: String,
        corrections: List<CorrectionRule>,
    ): String = withContext(Dispatchers.IO) {
        require(settings.enabled) { "请先在「智能」页启用大模型" }
        require(settings.apiKey.isNotBlank()) { "请填写大模型 API Key" }
        require(rawText.isNotBlank()) { "没有可加工的原文" }

        val dictBlock = corrections.filter { it.enabled && it.source.isNotBlank() }
            .joinToString("\n") { "- 「${it.source}」→「${it.target}」" }
            .ifBlank { "（无额外纠偏词）" }

        val system = promptTemplate.ifBlank { DEFAULT_PROMPT }
        val user = buildString {
            appendLine("【纠偏词库，必须优先遵守】")
            appendLine(dictBlock)
            appendLine()
            appendLine("【待加工原文】")
            append(rawText)
        }

        val body = JSONObject().apply {
            put("model", settings.model)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            put("temperature", 0.2)
        }

        val url = URL("${settings.baseUrl.trimEnd('/')}/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()
        conn.disconnect()
        require(code in 200..299) { "大模型请求失败 HTTP $code：$resp" }
        val json = JSONObject(resp)
        json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    /**
     * 让模型从「原文 vs 纠偏/润色结果」中提炼增量纠偏词候选，供人工审核入库。
     * 返回 JSON 数组：[{"source":"...","target":"...","reason":"..."}]
     */
    suspend fun suggestCorrections(
        settings: LlmSettings,
        rawText: String,
        correctedText: String,
        existing: List<CorrectionRule>,
    ): List<Triple<String, String, String>> = withContext(Dispatchers.IO) {
        if (!settings.enabled || settings.apiKey.isBlank()) return@withContext emptyList()
        if (rawText.isBlank() || correctedText.isBlank() || rawText == correctedText) {
            return@withContext emptyList()
        }
        val existingBlock = existing.filter { it.enabled }
            .joinToString("\n") { "- ${it.source} → ${it.target}" }
            .ifBlank { "（空）" }
        val system = SUGGEST_PROMPT
        val user = buildString {
            appendLine("【已有纠偏词库，勿重复】")
            appendLine(existingBlock)
            appendLine()
            appendLine("【原文】")
            appendLine(rawText)
            appendLine()
            appendLine("【纠偏/润色后】")
            appendLine(correctedText)
            appendLine()
            appendLine("请只输出 JSON 数组，不要其它文字。")
        }
        val content = chat(settings, system, user, temperature = 0.1)
        parseSuggestions(content)
    }

    private fun chat(
        settings: LlmSettings,
        system: String,
        user: String,
        temperature: Double,
    ): String {
        val body = JSONObject().apply {
            put("model", settings.model)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            put("temperature", temperature)
        }
        val url = URL("${settings.baseUrl.trimEnd('/')}/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()
        conn.disconnect()
        require(code in 200..299) { "大模型请求失败 HTTP $code：$resp" }
        return JSONObject(resp)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    private fun parseSuggestions(content: String): List<Triple<String, String, String>> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = JSONArray(content.substring(start, end + 1))
        val out = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optString("source").trim()
            val t = o.optString("target").trim()
            val r = o.optString("reason").trim()
            if (s.isNotBlank() && t.isNotBlank() && s != t) out += Triple(s, t, r)
        }
        return out.distinctBy { it.first to it.second }.take(20)
    }

    companion object {
        const val DEFAULT_PROMPT =
            """你是课堂语音转写润色助手。请在保持原意的前提下：
1. 优先应用用户提供的纠偏词库替换；
2. 理顺口语重复、语气词，补全合理标点；
3. 不要编造原文没有的事实；
4. 只输出加工后的正文，不要解释。"""

        const val SUGGEST_PROMPT =
            """你是 ASR 纠偏词挖掘助手。对比「原文」与「纠偏/润色后」文本，找出适合固化进词库的专有名词/易错词映射。
规则：
- 只提增量词（不要已有词库中的）；
- source 为识别易错写法，target 为正确写法；
- 课堂场景优先（人名、校名、术语）；
- 输出严格 JSON 数组：[{"source":"...","target":"...","reason":"..."}]，不要其它说明。"""
    }
}
