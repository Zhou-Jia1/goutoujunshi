package com.shengjidaguai.goutoujunshi.domain

import java.text.Normalizer
import java.util.Locale

/** Model suggestions always remain low confidence hypotheses. No model can grant provenance. */
object LongTermMemoryPolicy {
    fun contentKey(value: String): String = Normalizer.normalize(value.replace(Regex("〈记忆[0-9a-f]+·([^〉]+)〉")) { "[${it.groupValues[1]}]" }, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    fun contentFingerprint(owner: String, value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((owner+"\u0000"+contentKey(value)).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun worthRemembering(proposal: MemoryProposal): Boolean = runCatching {
        MemoryPolicy.validateEntry(MemoryEntry(scope=proposal.scope, subjectId=proposal.subjectId,
            field=proposal.field, value=proposal.value, sourceType=MemorySource.ASSISTANT_INFERENCE,
            sourceRef=proposal.sourceRef, occurredAt=proposal.occurredAt, confidence=Confidence.LOW))
    }.isSuccess

    /** T13 stable profile quotas stay unchanged; automatic hypotheses use source/confidence/time. */
    fun planInference(entry: MemoryEntry, existing: List<MemoryEntry>): List<MemoryEntry> {
        val normalized = MemoryPolicy.validateEntry(entry.copy(sourceType=MemorySource.ASSISTANT_INFERENCE, confidence=Confidence.LOW))
        val candidates = (existing.filterNot { it.id==entry.id } + normalized).toMutableList()
        val priority = compareBy<MemoryEntry> { if(it.sourceType==MemorySource.ASSISTANT_INFERENCE) 0 else 1 }
            .thenByDescending { it.confidence.ordinal }.thenBy { it.updatedAt }
        val group = candidates.filter { it.active && it.scope==normalized.scope && it.subjectId==normalized.subjectId }
        val excess = group.size - MemoryPolicy.scopeLimits.getValue(normalized.scope)
        if(excess>0) candidates.removeAll(group.sortedWith(priority).take(excess).toSet())
        val overflow = candidates.size - MemoryPolicy.MAX_ROWS
        if(overflow>0) candidates.removeAll(candidates.filter { it.scope in setOf(MemoryScope.EVENT,MemoryScope.HYPOTHESIS) }.sortedWith(priority).take(overflow).toSet())
        return MemoryPolicy.enforceQuotas(candidates)
    }
}
