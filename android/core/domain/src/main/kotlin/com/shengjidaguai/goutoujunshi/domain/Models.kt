package com.shengjidaguai.goutoujunshi.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class RelationType { BOSS, COLLEAGUE, SUBORDINATE, CLIENT, CRUSH, PARTNER, EX, PARENT, IN_LAW, RELATIVE, CHILD, SIBLING, FRIEND, ACQUAINTANCE, GROUP, MEDIATION }
@Serializable enum class Goal { REPLY, REPORT, ASK_HELP, DECLINE, APOLOGIZE, CLARIFY, COMFORT, ADVANCE, INVITE, BOUNDARY, ICEBREAK, REPAIR, MEDIATE, CLOSE }
@Serializable enum class RelationGroup { WORK, INTIMATE, FAMILY, SOCIAL, MEDIATION, DEFAULT }
@Serializable enum class Tone { NATURAL, GENTLE, DIRECT, FORMAL, WARM, CALM, HUMOROUS }
@Serializable enum class ProviderKind { DEMO, BYOK, GATEWAY }
@Serializable enum class SafetyLevel { NONE, CAUTION, CRISIS;
    fun upgrade(other: SafetyLevel) = if (ordinal >= other.ordinal) this else other
}
@Serializable enum class Confidence { HIGH, MEDIUM, LOW }
@Serializable enum class Speaker { ME, THEM, OTHER }
@Serializable enum class MessageRole { USER, ASSISTANT, SYSTEM }
@Serializable enum class MessageStatus { PENDING, STREAMING, COMPLETED, FAILED, CANCELLED }
@Serializable enum class RunStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
@Serializable enum class ImportSource { SCREENSHOT, SHARE, PASTE, FILE }
@Serializable enum class StyleOrigin { IMPORT, QUIZ, EDITED_REPLY }
@Serializable enum class PlanStatus { PENDING, SENT, SKIPPED }
@Serializable enum class TimePrecision { UNKNOWN, DATE, DATETIME, APPROXIMATE }

fun RelationType.group(): RelationGroup = when(this) {
    RelationType.BOSS, RelationType.COLLEAGUE, RelationType.SUBORDINATE, RelationType.CLIENT -> RelationGroup.WORK
    RelationType.CRUSH, RelationType.PARTNER, RelationType.EX -> RelationGroup.INTIMATE
    RelationType.PARENT, RelationType.IN_LAW, RelationType.RELATIVE, RelationType.CHILD, RelationType.SIBLING -> RelationGroup.FAMILY
    RelationType.MEDIATION -> RelationGroup.MEDIATION
    else -> RelationGroup.SOCIAL
}

@Serializable data class Contact(val id:String="", val alias:String="", val relationType:RelationType=RelationType.ACQUAINTANCE, val notes:String?=null, val archivedAt:Long?=null, val createdAt:Long=0, val sourceRevision:Long=0)
/** A null contact is ephemeral: never serialize this conversation into persistent storage. */
@Serializable data class Conversation(val id:String="", val contactId:String?=null, val isTemporary:Boolean=contactId == null, val updatedAt:Long=0, val revision:Long=0) {
    val canPersist:Boolean get() = !isTemporary && !contactId.isNullOrBlank()
}
@Serializable data class AnalysisRun(val requestId:String="", val conversationId:String="", val mode:RequestMode=RequestMode.FULL_ANALYSIS, val parentAnswerId:String?=null, val inputRevision:Long=0, val status:RunStatus=RunStatus.PENDING, val conversationRevision:Long=0, val contactId:String?=null, val operationFingerprint:String="")
@Serializable data class Message(val id:String="", val conversationId:String="", val role:MessageRole=MessageRole.USER, val text:String="", val answerJson:String?=null, val requestId:String="", val status:MessageStatus=MessageStatus.PENDING, val contactId:String?=null, val createdAt:Long=0, val providerKind:ProviderKind=ProviderKind.DEMO)
@Serializable data class ChatImport(val id:String="", val contactId:String?=null, val sourceType:ImportSource=ImportSource.PASTE, val contentHash:String="", val importedAt:Long=0)
@Serializable data class ChatLine(val id:String="", val importId:String="", val seq:Int=0, val speaker:Speaker=Speaker.OTHER, val text:String="", val sentAt:String?=null, val ocrConfidence:Float?=null, val speakerConfirmed:Boolean=false, val contactId:String?=null, val speakerName:String?=null)
@Serializable data class StyleProfile(val id:String="", val relationGroup:RelationGroup=RelationGroup.DEFAULT, val features:StyleFeatures=StyleFeatures(), val description:String="", val sourceRevision:Long=0, val updatedAt:Long=0, val consentVersion:Long=0)
@Serializable data class StyleSample(val id:String="", val relationGroup:RelationGroup=RelationGroup.DEFAULT, val text:String="", val origin:StyleOrigin=StyleOrigin.QUIZ, val sourceContactId:String?=null, val sourceImportId:String?=null, val sourceMessageId:String?=null, val sourceAnswerId:String?=null, val consentVersion:Long=0, val sourceRevision:Long=0, val createdAt:Long=0)
@Serializable data class Tip(val id:String="", val name:String="", val explanation:String="", val example:String="", val savedAt:Long=0)
@Serializable data class Event(val id:String="", val contactId:String?=null, val occurredAt:String?=null, val timePrecision:TimePrecision=TimePrecision.UNKNOWN, val summary:String="", val lineRefs:List<String> = emptyList())
@Serializable data class ActionPlan(val id:String="", val contactId:String?=null, val chosenReply:String="", val wasEdited:Boolean=false, val status:PlanStatus=PlanStatus.PENDING, val followUp:String?=null)
@Serializable data class NicknameMapping(val id:String="", val contactId:String?=null, val original:String="", val replacement:String="", val sourceRevision:Long=0)
@Serializable data class ConsentState(val enabled:Boolean=false, val paused:Boolean=false, val consentVersion:Long=0, val policyVersion:String="1", val updatedAt:Long=0)

@Serializable enum class MemoryScope { @SerialName("user") USER, @SerialName("object") OBJECT, @SerialName("relationship") RELATIONSHIP, @SerialName("event") EVENT, @SerialName("hypothesis") HYPOTHESIS }
@Serializable enum class MemorySource { @SerialName("user_explicit") USER_EXPLICIT, @SerialName("user_report") USER_REPORT, @SerialName("chatlab") CHATLAB, @SerialName("tool") TOOL, @SerialName("assistant_inference") ASSISTANT_INFERENCE }
@Serializable data class MemoryEntry(val id:String="", val contactId:String?=null, val scope:MemoryScope=MemoryScope.HYPOTHESIS, val subjectId:String="", val field:String="", val value:String="", val sourceType:MemorySource=MemorySource.ASSISTANT_INFERENCE, val sourceRef:String="", val occurredAt:String?=null, val observedAt:String="", val confidence:Confidence=Confidence.MEDIUM, val active:Boolean=true, val createdAt:Long=0, val updatedAt:Long=0)
@Serializable data class MemoryOperation(val id:String="", val contactId:String?=null, val before:List<MemoryEntry> = emptyList(), val after:List<MemoryEntry> = emptyList(), val createdAt:Long=0, val action:String="apply")
/** Untrusted model proposal. Passing parsing is never permission to persist. */
@Serializable data class MemoryProposal(val scope:MemoryScope=MemoryScope.HYPOTHESIS, @SerialName("subject_id") val subjectId:String="", val field:String="", val value:String="", @SerialName("source_type") val sourceType:MemorySource=MemorySource.ASSISTANT_INFERENCE, @SerialName("source_ref") val sourceRef:String="", @SerialName("occurred_at") val occurredAt:String?=null, val confidence:String="medium")
