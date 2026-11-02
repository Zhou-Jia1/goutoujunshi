package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*

/** Explicit saves use the current session transaction, independently of learning consent. */
class TeachingRepository(private val session: DataSession) {
    private suspend fun JunshiDao.validate(request: AnalysisRequest, answerId: String, current: () -> Boolean): StructuredAnswer {
        check(current())
        val owner = requireNotNull(request.contactId)
        check(requireContact(owner).revision == request.sourceRevision)
        val row = requireNotNull(conversation(owner, request.conversationId))
        check(decode<Conversation>(row.payload).revision == request.conversationRevision && row.draft == request.userText)
        val privacy = records(owner, OwnedRecordKind.REDACTION.name).singleOrNull()?.let { decode<RedactionProfile>(it.payload) } ?: RedactionProfile()
        check(privacy.revision == request.redactionProfile.revision)
        val run = records(owner, OwnedRecordKind.ANALYSIS_RUN.name).map { decode<AnalysisRun>(it.payload) }.first { it.requestId == request.requestId }
        check(run.requestSnapshot == request.copy(redactionProfile=RedactionProfile(revision=request.redactionProfile.revision)) && run.conversationRevision == request.conversationRevision)
        check(run.status == RunStatus.COMPLETED && run.highestSafety != SafetyLevel.CRISIS)
        val source = messages(owner, request.conversationId).map { decode<Message>(it.payload) }
            .first { it.role == MessageRole.ASSISTANT && it.requestId == request.requestId && it.status == MessageStatus.COMPLETED }
        val answer = decode<StructuredAnswer>(requireNotNull(source.answerJson))
        check(answer.id == answerId && answer.safety != SafetyLevel.CRISIS)
        val selection = effectiveCaptureSelection(owner)
        check(!selection.blocked && request.evidence.all { captureImportEligible(owner, it.id) })
        check(request.evidence.all { block ->
            imported(owner,block.id)?.let {decode<ChatImport>(it.payload).sourceType.name==block.source}==true && lines(owner,block.id).map {decode<ChatLine>(it.payload)}==block.lines
        })
        check(current())
        return answer
    }

    suspend fun favorite(tip: Tip, request: AnalysisRequest?, answerId: String, saved: Boolean, current: () -> Boolean) = session.transaction {
        check(current())
        if (request != null) {
            val actual=validate(request,answerId,current)
            val expected = TeachingPolicy.tip(actual) ?: error("No tip")
            check(expected.name == tip.name && expected.explanation == tip.explanation && expected.example == tip.example)
            check(tip.scenes == setOf(TipScene((actual.scene?.relationType ?: request.relationType)?.group() ?: RelationGroup.DEFAULT, actual.scene?.goal ?: request.goal ?: Goal.REPLY)))
        }
        val previous = tipNamed(tip.name)
        check(current())
        if (!saved) previous?.let { deleteTip(it.id) }
        else {
            require(tip.name.isNotBlank() && tip.id.isNotBlank())
            val merged = TeachingPolicy.merge(previous?.let { decode<Tip>(it.payload) }, tip)
            put(TipRow(merged.id, merged.name, encode(merged)))
        }
        check(current())
    }

    suspend fun habit(request: AnalysisRequest, answerId: String, day: String, disabled: String? = null, current: () -> Boolean): Boolean = session.transaction {
        val answer = validate(request, answerId, current)
        require(disabled == null || disabled == answer.habit)
        val owner = requireNotNull(request.contactId)
        val id = "habit:$owner"
        val previous = record(owner, OwnedRecordKind.TEACHING_HABIT.name, id)?.let { decode<HabitState>(it.payload) } ?: HabitState()
        val show = answer.habit.isNotBlank() && previous.day != day && day !in previous.shownDays && answer.habit !in previous.disabled
        if (show || disabled != null) {
            check(current())
            put(OwnedRecordRow("${OwnedRecordKind.TEACHING_HABIT.name}:$id", owner, OwnedRecordKind.TEACHING_HABIT,
                encode(previous.copy(day = if (show) day else previous.day, disabled = previous.disabled + listOfNotNull(disabled), shownDays = previous.shownDays + if(show) setOf(day) else emptySet()))))
        }
        check(current())
        show
    }
}
