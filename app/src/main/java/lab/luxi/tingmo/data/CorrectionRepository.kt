package lab.luxi.tingmo.data

import lab.luxi.tingmo.data.db.CorrectionDao
import lab.luxi.tingmo.data.db.CorrectionRuleEntity
import lab.luxi.tingmo.domain.CorrectionRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CorrectionRepository(private val dao: CorrectionDao) {
    fun observe(): Flow<List<CorrectionRule>> =
        dao.observeAll().map { list ->
            list.map {
                CorrectionRule(it.id, it.source, it.target, it.enabled, it.note, it.updatedAt)
            }
        }

    suspend fun add(source: String, target: String, note: String = "") {
        require(source.isNotBlank()) { "原词不能为空" }
        dao.insert(
            CorrectionRuleEntity(
                source = source.trim(),
                target = target.trim(),
                note = note.trim(),
            ),
        )
    }

    suspend fun update(rule: CorrectionRule) {
        dao.update(
            CorrectionRuleEntity(
                id = rule.id,
                source = rule.source.trim(),
                target = rule.target.trim(),
                enabled = rule.enabled,
                note = rule.note,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun seedDefaultsIfEmpty() {
        // no-op if already has data — checked by caller via first emission if needed
    }

    suspend fun ensureDemoRules() {
        // Insert a couple of examples only when table empty — done from App onCreate via count
    }
}
