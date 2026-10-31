package com.shengjidaguai.goutoujunshi.domain

/** An old answer never acquires authority from the current contact's newer snapshot. */
object AnswerSourcePolicy {
    fun matches(request: AnalysisRequest?, answerConfigRevision: String?, conversation: Conversation?, contactId: String?, sourceRevision: Long,
        draft: String, evidence: List<EvidenceBlock>, privacyRevision: Long, configRevision: String): Boolean =
        request != null && answerConfigRevision == configRevision && conversation != null &&
            request.conversationId == conversation.id && request.conversationRevision == conversation.revision &&
            request.contactId == contactId && request.sourceRevision == sourceRevision &&
            request.userText == draft && request.evidence == evidence && request.redactionProfile.revision == privacyRevision
}
