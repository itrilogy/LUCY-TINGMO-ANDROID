package lab.luxi.tingmo.domain

/**
 * AI / dictionary correction rule (对齐桌面听默「标识物 / 易错词」概念).
 */
data class CorrectionRule(
    val id: Long = 0,
    val source: String,
    val target: String,
    val enabled: Boolean = true,
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

object CorrectionApplier {
    fun apply(text: String, rules: List<CorrectionRule>): String {
        var out = text
        rules.filter { it.enabled && it.source.isNotBlank() }
            .sortedByDescending { it.source.length }
            .forEach { rule ->
                out = out.replace(rule.source, rule.target)
            }
        return out
    }
}
