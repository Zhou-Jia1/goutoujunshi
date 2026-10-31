package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.data.network.ModelSettingsSnapshot

data class WaitingPhase(val stage: Int, val slow: Boolean) {
    companion object { fun at(elapsedMillis: Long) = WaitingPhase(if (elapsedMillis < 5_000) 0 else if (elapsedMillis < 12_000) 1 else 2, elapsedMillis > 20_000) }
}

data class CounselError(val code: String, val retryable: Boolean = false)
/** Process-local input acknowledgement, never serialized or restored from storage. */
data class DraftEditStamp(val editorId: String, val sequence: Long, val originConversationId: String)

/** Never put this state into SavedStateHandle: it can contain temporary private data. */
data class CounselState(
    val loading: Boolean = true,
    val contacts: List<Contact> = emptyList(),
    val currentContact: Contact? = null,
    val conversation: Conversation? = null,
    val importDuplicate: Boolean = false,
    val evidence: List<EvidenceBlock> = emptyList(),
    val draft: String = "",
    val draftEditAck: DraftEditStamp? = null,
    val relationType: RelationType = RelationType.ACQUAINTANCE,
    val goal: Goal = Goal.REPLY,
    val emotion: Int? = null,
    val styleBias: Float = 0.5f,
    val tone: Tone = Tone.NATURAL,
    val answer: StructuredAnswer? = null,
    val safety: SafetyLevel = SafetyLevel.NONE,
    val lockState: LockState = LockState(),
    val messages: List<Message> = emptyList(),
    val streaming: Boolean = false,
    val waiting: WaitingPhase = WaitingPhase.at(12_000),
    val draftSaved: Boolean = false,
    val partial: Map<SectionId, String> = emptyMap(),
    val error: CounselError? = null,
    val modelStatus: ProviderKind = ProviderKind.DEMO,
    val demo: Boolean = true,
    val answerDemo: Boolean = true,
    val preview: PreparedPrompt? = null,
    val modelSettings: ModelSettingsSnapshot = ModelSettingsSnapshot(),
    val testingConnection: Boolean = false,
    val connectionResult: String? = null,
    val outboundBody: String? = null,
    val currentRequest: AnalysisRequest? = null,
    val redactionProfile: RedactionProfile = RedactionProfile(),
    val redactionError: Boolean = false,
)
