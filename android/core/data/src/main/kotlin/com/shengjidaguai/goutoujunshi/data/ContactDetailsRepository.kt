package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*

/** A consistent, owner-scoped read; opening a profile never creates a conversation. */
data class ContactDetails(val contact: Contact, val imports: List<ChatImport> = emptyList(),
    val evidence: List<EvidenceBlock> = emptyList(), val messages: List<Message> = emptyList(),
    val runs: List<AnalysisRun> = emptyList(), val plans: List<ActionPlan> = emptyList(),
    val events: List<Event> = emptyList(), val memories: List<MemoryEntry> = emptyList())

class ContactDetailsRepository(private val session: DataSession) {
    suspend fun load(id: String, expectedSource: Long): ContactDetails = session.transaction {
        val owner = requireContact(id)
        check(owner.revision == expectedSource)
        val batches = imports(id).map { decode<ChatImport>(it.payload) }
        ContactDetails(decode(owner.payload), batches,
            batches.map { batch -> EvidenceBlock(batch.id, batch.sourceType.name, lines(id, batch.id).map { decode(it.payload) }) },
            conversations(id).flatMap { messages(id, it.id).map { row -> decode<Message>(row.payload) } },
            records(id, OwnedRecordKind.ANALYSIS_RUN.name).map { decode(it.payload) },
            records(id, OwnedRecordKind.PLAN.name).map { decode(it.payload) },
            records(id, OwnedRecordKind.EVENT.name).map { decode(it.payload) },
            memories(id).map { decode(it.payload) })
    }
}
