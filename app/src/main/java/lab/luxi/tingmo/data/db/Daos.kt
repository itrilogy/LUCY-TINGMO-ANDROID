package lab.luxi.tingmo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CorrectionDao {
    @Query("SELECT * FROM correction_rules ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CorrectionRuleEntity>>

    @Query("SELECT * FROM correction_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<CorrectionRuleEntity>

    @Insert
    suspend fun insert(entity: CorrectionRuleEntity): Long

    @Update
    suspend fun update(entity: CorrectionRuleEntity)

    @Query("DELETE FROM correction_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM transcript_history ORDER BY createdAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<TranscriptHistoryEntity>>

    @Insert
    suspend fun insert(entity: TranscriptHistoryEntity): Long

    @Query("DELETE FROM transcript_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transcript_history")
    suspend fun clear()
}

@Dao
interface ModelInstallDao {
    @Query("SELECT * FROM model_install_state")
    fun observeAll(): Flow<List<ModelInstallEntity>>

    @Query("SELECT * FROM model_install_state WHERE modelId = :id LIMIT 1")
    suspend fun get(id: String): ModelInstallEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ModelInstallEntity)
}

@Dao
interface PromptDao {
    @Query("SELECT * FROM ai_prompts ORDER BY isDefault DESC, updatedAt DESC")
    fun observeAll(): Flow<List<PromptEntity>>

    @Query("SELECT * FROM ai_prompts WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): PromptEntity?

    @Query("SELECT * FROM ai_prompts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PromptEntity?

    @Insert
    suspend fun insert(entity: PromptEntity): Long

    @Update
    suspend fun update(entity: PromptEntity)

    @Query("DELETE FROM ai_prompts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE ai_prompts SET isDefault = 0")
    suspend fun clearDefaultFlags()
}

@Dao
interface PendingCorrectionDao {
    @Query("SELECT * FROM pending_corrections WHERE status = 'pending' ORDER BY createdAt DESC")
    fun observePending(): Flow<List<PendingCorrectionEntity>>

    @Insert
    suspend fun insert(entity: PendingCorrectionEntity): Long

    @Insert
    suspend fun insertAll(entities: List<PendingCorrectionEntity>)

    @Query("UPDATE pending_corrections SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("DELETE FROM pending_corrections WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE filePath = :path LIMIT 1")
    suspend fun getByPath(path: String): RecordingEntity?

    @Insert
    suspend fun insert(entity: RecordingEntity): Long

    @Update
    suspend fun update(entity: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)
}
