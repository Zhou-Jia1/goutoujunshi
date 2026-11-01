package com.shengjidaguai.goutoujunshi.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

@Serializable data class AnalysisLedger(val runs:List<AnalysisRun> = emptyList(),val messages:List<Message> = emptyList()) {
    /** Retry reuses the existing run and user message; a new operation must have a new request id. */
    fun begin(request:AnalysisRequest,userMessageId:String):AnalysisLedger {
        require(request.requestId.isNotBlank() && userMessageId.isNotBlank())
        val previous=runs.firstOrNull { it.requestId==request.requestId }
        if(previous!=null) {
            require(matchesOperation(previous, request)) { "requestId belongs to another operation" }
            if (request.purpose != OutboundPurpose.LONG_RECORD_SUMMARY) require(messages.firstOrNull { it.requestId==request.requestId && it.role==MessageRole.USER }?.text==request.userText) { "Retry input changed" }
            return copy(runs=runs.map { if(it.requestId==request.requestId && it.status!=RunStatus.COMPLETED) it.copy(status=RunStatus.RUNNING) else it })
        }
        require(messages.none { it.id==userMessageId }) { "Duplicate message id" }
        return copy(runs=runs+AnalysisRun(request.requestId,request.conversationId,request.mode,request.parentAnswerId,request.inputRevision,RunStatus.RUNNING,request.conversationRevision,request.contactId,operationFingerprint(request)),
            messages=if (request.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) messages else messages+Message(id=userMessageId,conversationId=request.conversationId,role=MessageRole.USER,text=request.userText,requestId=request.requestId,status=MessageStatus.COMPLETED,contactId=request.contactId))
    }
    /** At most one assistant message per operation. Stale callbacks leave state unchanged. */
    fun complete(request:AnalysisRequest,incoming:StructuredAnswer,assistantMessageId:String,currentInputRevision:Long,currentConversationRevision:Long):AnalysisLedger {
        val run=runs.firstOrNull { it.requestId==request.requestId } ?: return this
        if(run.status !in setOf(RunStatus.PENDING, RunStatus.RUNNING) || !matchesOperation(run, request)) return this
        val merged=AnswerPolicy.apply(request,incoming,currentInputRevision,currentConversationRevision) ?: return this
        val existing=messages.firstOrNull { it.requestId==request.requestId && it.role==MessageRole.ASSISTANT }
        require(assistantMessageId.isNotBlank())
        require(messages.none { it.id==assistantMessageId && it.id!=existing?.id }) { "Duplicate message id" }
        val message=Message(id=existing?.id ?: assistantMessageId,conversationId=request.conversationId,role=MessageRole.ASSISTANT,
            text=merged.reply.joinToString("\n"),answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),merged),
            requestId=request.requestId,status=MessageStatus.COMPLETED,contactId=request.contactId)
        return copy(runs=runs.map { if(it.requestId==request.requestId) it.copy(status=RunStatus.COMPLETED) else it },
            messages=messages.filterNot { it.requestId==request.requestId && it.role==MessageRole.ASSISTANT }+message)
    }

    private fun matchesOperation(run: AnalysisRun, request: AnalysisRequest): Boolean =
        run.requestId == request.requestId &&
            run.conversationId == request.conversationId &&
            run.inputRevision == request.inputRevision &&
            run.conversationRevision == request.conversationRevision &&
            run.mode == request.mode &&
            run.contactId == request.contactId &&
            run.parentAnswerId == request.parentAnswerId &&
            (run.operationFingerprint == operationFingerprint(request) || legacyOperationMatches(run, request))

    /** T27 snapshots predate scene controls. Accept only their exact canonical hash, never a changed operation. */
    private fun legacyOperationMatches(run: AnalysisRun, request: AnalysisRequest): Boolean {
        if (request.networkSelection != null || request.timelineSummary.isNotEmpty()) return false
        val fields = Json { encodeDefaults = true }.encodeToJsonElement(AnalysisRequest.serializer(), request).jsonObject
        val prior = JsonObject(fields.filterKeys { it !in setOf("networkSelection", "timelineSummary") }).toString()
        if (run.operationFingerprint == hash(prior)) return true
        if (request.formality != "medium" || request.manualRelation != null || request.manualGoal != null || request.manualFormality != null) return false
        val legacy = JsonObject(fields.filterKeys { it !in setOf("formality", "manualRelation", "manualGoal", "manualFormality", "networkSelection", "timelineSummary") }).toString()
        val transitional = JsonObject(fields.filterKeys { it !in setOf("formality", "manualRelation", "manualGoal", "manualFormality") }).toString()
        return run.operationFingerprint in setOf(hash(legacy), hash(transitional))
    }

    /** Hash an immutable serialized snapshot, including every nested request field and default. */
    private fun operationFingerprint(request: AnalysisRequest): String {
        val snapshot = Json { encodeDefaults = true }.encodeToString(AnalysisRequest.serializer(), request)
        return hash(snapshot)
    }
    private fun hash(snapshot: String): String = MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
