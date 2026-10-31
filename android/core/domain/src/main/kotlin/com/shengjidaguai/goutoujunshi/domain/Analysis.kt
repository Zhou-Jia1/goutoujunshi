package com.shengjidaguai.goutoujunshi.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable enum class RequestMode { FULL_ANALYSIS, REWRITE_REPLY }
@Serializable enum class SectionId { SAFETY, SCENE, EMPATHY, VERDICT, REPLY, ALT, WHY, COST, SITUATION, FACTS, GUESSES, UNKNOWNS, RISK, TIP, HABIT, NEXT, ASK, MEMORY, FREE_TEXT }
@Serializable data class EvidenceBlock(val id:String="", val source:String="", val lines:List<ChatLine> = emptyList(), val text:String="")
@Serializable data class AnalysisRequest(
    val requestId:String="", val mode:RequestMode=RequestMode.FULL_ANALYSIS,
    val parentAnswerId:String?=null, val originalAnswer:StructuredAnswer?=null,
    val inputRevision:Long=0, val contactId:String?=null, val relationType:RelationType?=null,
    val goal:Goal?=null, val userText:String="", val emotionLevel:Int?=null,
    val evidence:List<EvidenceBlock> = emptyList(), val styleBias:Float=0.5f, val tone:Tone?=null,
    val styleContext:String="", val styleSamples:List<StyleSample> = emptyList(),
    val contactContext:String="", val recentSummary:String="", val redactionTerms:List<String> = emptyList(),
    val sourceRevision:Long=0, val speakersConfirmed:Boolean=false,
    val conversationId:String="", val conversationRevision:Long=0,
    val purpose:OutboundPurpose=OutboundPurpose.ANALYSIS,
    val redactionProfile:RedactionProfile=RedactionProfile(),
    val actualDataContext:OutboundDataContext=OutboundDataContext(),
)
interface AnalysisProvider { val kind:ProviderKind; fun analyze(request:AnalysisRequest):Flow<AnalysisChunk> }
@Serializable sealed interface AnalysisChunk {
    @Serializable data class Section(val id:SectionId=SectionId.FREE_TEXT, val textDelta:String=""):AnalysisChunk
    @Serializable data class Completed(val answer:StructuredAnswer=StructuredAnswer()):AnalysisChunk
    @Serializable data class Failed(val error:AnalysisError=AnalysisError()):AnalysisChunk
}
@Serializable data class AnalysisError(val code:String="UNKNOWN", val message:String="", val retryable:Boolean=false)
@Serializable data class Alternative(val label:String="", val reply:String="")
@Serializable data class Fact(val text:String="", val messageRefs:List<String> = emptyList())
@Serializable data class Guess(val text:String="", val confidence:Confidence=Confidence.LOW)
@Serializable data class Scene(val relationType:RelationType?=null, val goal:Goal?=null, val formality:String="medium")
@Serializable data class StructuredAnswer(
    val id:String="", val safety:SafetyLevel=SafetyLevel.NONE, val scene:Scene?=null,
    val empathy:String="", val verdict:String="", val reply:List<String> = emptyList(),
    val alternatives:List<Alternative> = emptyList(), val why:List<String> = emptyList(),
    val cost:String="", val situation:String="", val facts:List<Fact> = emptyList(),
    val guesses:List<Guess> = emptyList(), val unknowns:List<String> = emptyList(),
    val risk:String="", val tips:List<Tip> = emptyList(), val habit:String="", val next:String="",
    val ask:List<String> = emptyList(), val memoryProposals:List<MemoryProposal> = emptyList(),
    val freeText:String="", val rawText:String="", val sections:Map<SectionId,String> = emptyMap(),
    val formatDisordered:Boolean=false,
)
interface PromptAssembler { fun prepare(request:AnalysisRequest):PreparedPrompt }
@Serializable data class KnowledgeReference(val path:String, val sha256:String, val characters:Int) {
    init { require(path.isNotBlank()); require(sha256.matches(Regex("[a-fA-F0-9]{64}"))); require(characters > 0) }
}
@Serializable data class PreparedPrompt(val system:String, val user:String, val references:List<KnowledgeReference>, val knowledgeVersion:String,
    val redactionCount:Int=0, val safeAliases:Map<String,String> = emptyMap()) {
    init { require(system.isNotBlank() && user.isNotBlank()); require(references.size in 1..3); require(knowledgeVersion.isNotBlank()) }
}

object AnswerPolicy {
    /** Returns null for callbacks made obsolete by input or conversation edits. */
    fun apply(request:AnalysisRequest, incoming:StructuredAnswer, currentInputRevision:Long, currentConversationRevision:Long):StructuredAnswer? {
        if(request.inputRevision != currentInputRevision || request.conversationRevision != currentConversationRevision) return null
        val original=request.originalAnswer
        val safety=(original?.safety ?: SafetyLevel.NONE).upgrade(incoming.safety)
        return if(request.mode == RequestMode.REWRITE_REPLY) {
            original?.copy(reply=incoming.reply, alternatives=incoming.alternatives, safety=safety)
        } else incoming.copy(safety=safety)
    }
}
