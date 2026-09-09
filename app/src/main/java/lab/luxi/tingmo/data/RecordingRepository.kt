package lab.luxi.tingmo.data

import lab.luxi.tingmo.data.db.RecordingDao
import lab.luxi.tingmo.data.db.RecordingEntity
import java.io.File
import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val dao: RecordingDao) {
    fun observe(): Flow<List<RecordingEntity>> = dao.observeAll()

    suspend fun get(id: Long) = dao.getById(id)

    /** 同一文件路径则更新文案，避免 AI 加工重复插行 */
    suspend fun saveOrUpdate(
        title: String,
        filePath: String,
        durationMs: Long,
        modelId: String,
        mode: String,
        rawText: String,
        correctedText: String,
    ): Long {
        val existing = dao.getByPath(filePath)
        return if (existing != null) {
            dao.update(
                existing.copy(
                    durationMs = durationMs.coerceAtLeast(existing.durationMs),
                    modelId = modelId.ifBlank { existing.modelId },
                    mode = mode.ifBlank { existing.mode },
                    rawText = rawText.ifBlank { existing.rawText },
                    correctedText = correctedText.ifBlank { existing.correctedText },
                ),
            )
            existing.id
        } else {
            dao.insert(
                RecordingEntity(
                    title = title.ifBlank { "录音 ${System.currentTimeMillis()}" },
                    filePath = filePath,
                    durationMs = durationMs,
                    modelId = modelId,
                    mode = mode,
                    rawText = rawText,
                    correctedText = correctedText,
                ),
            )
        }
    }

    suspend fun save(
        title: String,
        filePath: String,
        durationMs: Long,
        modelId: String,
        mode: String,
        rawText: String,
        correctedText: String,
    ): Long = saveOrUpdate(title, filePath, durationMs, modelId, mode, rawText, correctedText)

    suspend fun updateTexts(id: Long, raw: String, corrected: String) {
        val e = dao.getById(id) ?: return
        dao.update(e.copy(rawText = raw, correctedText = corrected))
    }

    suspend fun rename(id: Long, title: String) {
        val e = dao.getById(id) ?: return
        dao.update(e.copy(title = title.trim().ifBlank { e.title }))
    }

    suspend fun delete(id: Long) {
        val e = dao.getById(id) ?: return
        runCatching { File(e.filePath).delete() }
        dao.delete(id)
    }
}
