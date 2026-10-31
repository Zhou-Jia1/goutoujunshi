package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*

class PlanRepository(private val session: DataSession) {
    suspend fun list(contactId: String): List<ActionPlan> = session.transaction { records(contactId, OwnedRecordKind.PLAN.name).map { decode(it.payload) } }
    suspend fun save(plan: ActionPlan) = session.transaction {
        putOwned(requireNotNull(plan.contactId), plan.id, OwnedRecordKind.PLAN, encode(plan))
    }
    suspend fun delete(contactId: String, id: String) = session.transaction { deleteRecord(contactId, OwnedRecordKind.PLAN.name, id) }
}

class TipRepository(private val session: DataSession) {
    suspend fun list(): List<Tip> = session.transaction { tips().map { decode(it.payload) } }
    suspend fun save(tip: Tip): String = session.transaction {
        require(tip.id.isNotBlank() && tip.name.isNotBlank())
        val id = tipNamed(tip.name)?.id ?: tip.id
        put(TipRow(id, tip.name, encode(tip.copy(id = id))))
        id
    }
    suspend fun delete(id: String) = session.transaction { deleteTip(id) }
}

/** The enum is the only public route into the shared table; raw arbitrary payloads are not exposed. */
class TimelineRepository(private val session: DataSession) {
    /** No upsert may recreate a deleted owner or accept an obsolete source/input. */
    suspend fun saveCurrentRun(run: AnalysisRun, expectedSource: Long) = session.transaction {
        val contactId = requireNotNull(run.contactId)
        check(requireContact(contactId).revision == expectedSource)
        val row = requireNotNull(conversation(contactId, run.conversationId))
        check(decode<Conversation>(row.payload).revision == run.conversationRevision)
        run.requestSnapshot?.let { check(it.sourceRevision == expectedSource && it.userText == row.draft) }
        putOwned(contactId, run.requestId, OwnedRecordKind.ANALYSIS_RUN, encode(run))
    }
    suspend fun events(contactId: String): List<Event> = session.transaction { records(contactId, OwnedRecordKind.EVENT.name).map { decode(it.payload) } }
    suspend fun save(event: Event) = session.transaction { putOwned(requireNotNull(event.contactId), event.id, OwnedRecordKind.EVENT, encode(event)) }
    suspend fun deleteEvent(contactId: String, id: String) = session.transaction { deleteRecord(contactId, OwnedRecordKind.EVENT.name, id) }
    suspend fun runs(contactId: String): List<AnalysisRun> = session.transaction { records(contactId, OwnedRecordKind.ANALYSIS_RUN.name).map { decode(it.payload) } }
    suspend fun save(run: AnalysisRun) = session.transaction {
        val contactId = requireNotNull(run.contactId)
        requireNotNull(conversation(contactId, run.conversationId))
        putOwned(contactId, run.requestId, OwnedRecordKind.ANALYSIS_RUN, encode(run))
    }
    suspend fun deleteRun(contactId: String, requestId: String) = session.transaction { deleteRecord(contactId, OwnedRecordKind.ANALYSIS_RUN.name, requestId) }
    suspend fun mappings(contactId: String): List<NicknameMapping> = session.transaction { records(contactId, OwnedRecordKind.NICKNAME.name).map { decode(it.payload) } }
    suspend fun save(mapping: NicknameMapping) = session.transaction { putOwned(requireNotNull(mapping.contactId), mapping.id, OwnedRecordKind.NICKNAME, encode(mapping)) }
    suspend fun deleteMapping(contactId: String, id: String) = session.transaction { deleteRecord(contactId, OwnedRecordKind.NICKNAME.name, id) }
}

private suspend fun JunshiDao.putOwned(contactId: String, id: String, kind: OwnedRecordKind, payload: String) {
    requireContact(contactId)
    require(id.isNotBlank())
    val storageId = "${kind.name}:$id"
    require(ownerOfRecord(storageId).let { it == null || it == contactId })
    put(OwnedRecordRow(storageId, contactId, kind, payload))
}
