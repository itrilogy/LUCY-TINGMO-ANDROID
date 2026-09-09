package lab.luxi.tingmo.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aiDataStore by preferencesDataStore("tingmo_ai_settings")

data class LlmSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
)

data class RemoteAsrSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    /** e.g. whisper-1；POST {base}/audio/transcriptions */
    val model: String = "whisper-1",
)

class AiSettingsRepository(private val context: Context) {
    private val llmEnabled = booleanPreferencesKey("llm_enabled")
    private val llmBase = stringPreferencesKey("llm_base")
    private val llmKey = stringPreferencesKey("llm_key")
    private val llmModel = stringPreferencesKey("llm_model")

    private val asrEnabled = booleanPreferencesKey("rasr_enabled")
    private val asrBase = stringPreferencesKey("rasr_base")
    private val asrKey = stringPreferencesKey("rasr_key")
    private val asrModel = stringPreferencesKey("rasr_model")

    val llm: Flow<LlmSettings> = context.aiDataStore.data.map { p ->
        LlmSettings(
            enabled = p[llmEnabled] ?: false,
            baseUrl = p[llmBase] ?: "https://api.openai.com/v1",
            apiKey = p[llmKey] ?: "",
            model = p[llmModel] ?: "gpt-4o-mini",
        )
    }

    val remoteAsr: Flow<RemoteAsrSettings> = context.aiDataStore.data.map { p ->
        RemoteAsrSettings(
            enabled = p[asrEnabled] ?: false,
            baseUrl = p[asrBase] ?: "https://api.openai.com/v1",
            apiKey = p[asrKey] ?: "",
            model = p[asrModel] ?: "whisper-1",
        )
    }

    suspend fun saveLlm(s: LlmSettings) {
        context.aiDataStore.edit {
            it[llmEnabled] = s.enabled
            it[llmBase] = s.baseUrl.trim().trimEnd('/')
            it[llmKey] = s.apiKey.trim()
            it[llmModel] = s.model.trim()
        }
    }

    suspend fun saveRemoteAsr(s: RemoteAsrSettings) {
        context.aiDataStore.edit {
            it[asrEnabled] = s.enabled
            it[asrBase] = s.baseUrl.trim().trimEnd('/')
            it[asrKey] = s.apiKey.trim()
            it[asrModel] = s.model.trim()
        }
    }
}
