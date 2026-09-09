package lab.luxi.tingmo.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import lab.luxi.tingmo.domain.PickupProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("tingmo_settings")

class SettingsRepository(private val context: Context) {
    private val selectedModelKey = stringPreferencesKey("selected_model_id")
    private val defaultModeKey = stringPreferencesKey("default_mode")
    private val pickupSensitivityKey = intPreferencesKey("pickup_sensitivity")
    private val semiMaxSpeechSecKey = intPreferencesKey("semi_max_speech_sec")

    val selectedModelId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[selectedModelKey] ?: "sensevoice_small"
    }

    val defaultMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[defaultModeKey] ?: "SEMI_REALTIME"
    }

    /** 0–100，默认课堂档 */
    val pickupSensitivity: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[pickupSensitivityKey] ?: PickupProfile.DEFAULT_SENSITIVITY
    }

    /** 半实时最长切片（秒），VAD maxSpeechDuration，默认 5 */
    val semiMaxSpeechSec: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[semiMaxSpeechSecKey] ?: 5
    }

    suspend fun setSelectedModel(id: String) {
        context.dataStore.edit { it[selectedModelKey] = id }
    }

    suspend fun setDefaultMode(mode: String) {
        context.dataStore.edit { it[defaultModeKey] = mode }
    }

    suspend fun setPickupSensitivity(value: Int) {
        context.dataStore.edit {
            it[pickupSensitivityKey] = value.coerceIn(0, 100)
        }
    }

    suspend fun setSemiMaxSpeechSec(sec: Int) {
        context.dataStore.edit {
            it[semiMaxSpeechSecKey] = sec.coerceIn(2, 15)
        }
    }
}
