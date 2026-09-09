package lab.luxi.tingmo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "correction_rules")
data class CorrectionRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val target: String,
    val enabled: Boolean = true,
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "transcript_history")
data class TranscriptHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawText: String,
    val correctedText: String,
    val modelId: String,
    val mode: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "model_install_state")
data class ModelInstallEntity(
    @PrimaryKey val modelId: String,
    val installed: Boolean = false,
    val localPath: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "ai_prompts")
data class PromptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val content: String,
    val isDefault: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** AI 建议的纠偏词，待人工审核后入正式词库 */
@Entity(tableName = "pending_corrections")
data class PendingCorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val target: String,
    val reason: String = "",
    val status: String = "pending", // pending | approved | rejected
    val createdAt: Long = System.currentTimeMillis(),
)

/** 录音档案 */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val durationMs: Long = 0,
    val modelId: String = "",
    val mode: String = "",
    val rawText: String = "",
    val correctedText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
