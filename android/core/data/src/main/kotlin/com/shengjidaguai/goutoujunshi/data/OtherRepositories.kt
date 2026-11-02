package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*

class PlanRepository(private val session: DataSession) {
    suspend fun list(contactId: String): List<ActionPlan> = session.transaction { records(contactId, OwnedRecordKind.PLAN.name).map { decode(it.payload) } }
    suspend fun save(plan: ActionPlan) = session.transaction {
        putOwned(requireNotNull(plan.contactId), plan.id, OwnedRecordKind.PLAN, encode(plan))
    }
    /** Feedback is user-authored provenance, never implicit permission to learn. */
    suspend fun saveFeedback(plan: ActionPlan, privacyRevision: Long) = session.transaction {
        val contactId = requireNotNull(plan.contactId)
        check(requireContact(contactId).revision == plan.sourceRevision)
        val current = decode<Conversation>(requireNotNull(conversation(contactId, plan.conversationId)).payload)
        check(current.revision == plan.conversationRevision)
        val privacy = records(contactId, OwnedRecordKind.REDACTION.name).singleOrNull()?.let { decode<RedactionProfile>(it.payload) } ?: RedactionProfile()
        check(privacy.revision == privacyRevision)
        val answer = decode<Message>(requireNotNull(message(contactId, requireNotNull(plan.sourceAnswerId))).payload)
        check(answer.role == MessageRole.ASSISTANT && answer.requestId == plan.sourceRequestId && answer.conversationId == plan.conversationId)
        require(plan.chosenReply.isNotBlank() && !plan.learningAuthorized)
        record(contactId, OwnedRecordKind.PLAN.name, plan.id)?.let { row ->
            val previous = decode<ActionPlan>(row.payload)
            require(previous.copy(consentVersion = 0) == plan.copy(consentVersion = 0)) { "Feedback versions are immutable" }
            return@transaction
        }
        putOwned(contactId, plan.id, OwnedRecordKind.PLAN, encode(plan.copy(consentVersion = currentConsent().consentVersion)))
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
    suspend fun deleteRun(contactId: String, requestId: String) = session.transaction { deleteRecord(contactId, OwnedRecordKind.ANALYSIS_RUN.name, requestId);pruneDerivedMemories() }
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

/** Encrypted contact-owned cache; writes cannot recreate a removed owner or obsolete input. */
class SummaryRepository(private val session: DataSession) {
    suspend fun discard(summary: TimelineSummary) = session.transaction {
        val contactId = requireNotNull(summary.request.contactId)
        val stored = record(contactId, OwnedRecordKind.SUMMARY.name, summary.key)?.let { decode<TimelineSummary>(it.payload) }
        if (stored?.request?.requestId == summary.request.requestId) deleteRecord(contactId, OwnedRecordKind.SUMMARY.name, summary.key)
    }
    suspend fun list(contactId: String): List<TimelineSummary> = session.transaction {
        val eligible=effectiveCaptureSelection(contactId)
        if(eligible.blocked) emptyList() else records(contactId, OwnedRecordKind.SUMMARY.name).map {decode<TimelineSummary>(it.payload)}
            .filter {summary->summary.request.evidence.all {b->eligible.imports.any {it.id==b.id}}}
    }
    suspend fun save(summary: TimelineSummary) = session.transaction {
        val request = summary.request
        val contactId = requireNotNull(request.contactId)
        val eligible=effectiveCaptureSelection(contactId)
        check(!eligible.blocked && request.evidence.all {b->eligible.imports.any {it.id==b.id}}) {"Summary sources are not eligible"}
        check(requireContact(contactId).revision == request.sourceRevision)
        val row = requireNotNull(conversation(contactId, request.conversationId))
        check(decode<Conversation>(row.payload).revision == request.conversationRevision && row.draft == request.userText)
        val privacy = records(contactId, OwnedRecordKind.REDACTION.name).singleOrNull()?.let { decode<RedactionProfile>(it.payload) } ?: RedactionProfile()
        check(privacy.revision == request.redactionProfile.revision)
        check(request.evidence.all { block ->
            imported(contactId, block.id)?.let { decode<ChatImport>(it.payload).sourceType.name == block.source } == true &&
                lines(contactId, block.id).map { decode<ChatLine>(it.payload) } == block.lines
        }) { "Summary sources no longer match stored imports" }
        val selected = request.evidence.flatMap { it.lines }.filter { it.id in request.networkSelection.orEmpty() }
        check(ContinuousAnalysis.validSummary(summary.text, selected))
        putOwned(contactId, summary.key, OwnedRecordKind.SUMMARY, encode(summary.copy(request = request.copy(redactionProfile = RedactionProfile(revision = privacy.revision)))))
    }
}
