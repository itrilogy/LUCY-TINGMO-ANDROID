package lab.luxi.tingmo.data

import lab.luxi.tingmo.data.db.PromptDao
import lab.luxi.tingmo.data.db.PromptEntity
import lab.luxi.tingmo.llm.LlmClient
import kotlinx.coroutines.flow.Flow

class PromptRepository(private val dao: PromptDao) {
    fun observe(): Flow<List<PromptEntity>> = dao.observeAll()

    suspend fun ensureDefault() {
        if (dao.getDefault() == null) {
            dao.insert(
                PromptEntity(
                    name = "课堂润色（默认）",
                    content = LlmClient.DEFAULT_PROMPT,
                    isDefault = true,
                ),
            )
        }
    }

    suspend fun add(name: String, content: String, makeDefault: Boolean = false) {
        if (makeDefault) dao.clearDefaultFlags()
        dao.insert(
            PromptEntity(
                name = name.trim().ifBlank { "未命名提示词" },
                content = content.trim(),
                isDefault = makeDefault,
            ),
        )
    }

    suspend fun update(entity: PromptEntity) = dao.update(entity.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun setDefault(id: Long) {
        dao.clearDefaultFlags()
        val e = dao.getById(id) ?: return
        dao.update(e.copy(isDefault = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun getDefaultContent(): String =
        dao.getDefault()?.content ?: LlmClient.DEFAULT_PROMPT
}
