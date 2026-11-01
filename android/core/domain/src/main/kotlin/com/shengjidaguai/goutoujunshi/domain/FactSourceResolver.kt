package com.shengjidaguai.goutoujunshi.domain

/** Resolves only the current evidence snapshot. No repository lookup or nickname fallback is allowed. */
data class FactSource(val userStatement: Boolean = false, val lines: List<ChatLine> = emptyList()) {
    val available: Boolean get() = userStatement || lines.isNotEmpty()
}
object FactSourceResolver {
    fun resolve(refs: List<String>, contactId: String?, currentSourceRevision: Long, answerSourceRevision: Long?, evidence: List<EvidenceBlock>): FactSource {
        if (refs.isEmpty()) return FactSource(userStatement = true)
        if (answerSourceRevision != currentSourceRevision) return FactSource()
        val currentLines = evidence.flatMap { block -> block.lines.filter {
            it.contactId == contactId && it.importId == block.id && it.speakerConfirmed
        } }
        val selected = refs.distinct().map { ref ->
            currentLines.filter { it.id == ref.removeSurrounding("[", "]") }.singleOrNull() ?: return FactSource()
        }
        return FactSource(lines = selected)
    }
}
