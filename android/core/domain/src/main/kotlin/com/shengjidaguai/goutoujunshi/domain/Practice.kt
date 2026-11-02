package com.shengjidaguai.goutoujunshi.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

enum class ExecutionPolicy { ORDINARY, EPHEMERAL }
val AnalysisRequest.executionPolicy: ExecutionPolicy get() = if (purpose == OutboundPurpose.PRACTICE) ExecutionPolicy.EPHEMERAL else ExecutionPolicy.ORDINARY
@Serializable enum class PracticeDifficulty { COOPERATIVE, NORMAL, DIFFICULT }
@Serializable enum class PracticeStage { OPENING, ROUND, SUMMARY }
@Serializable data class PracticeRound(val counterpartLine:String, val userLine:String?=null, val feedback:String?=null, val betterVersion:String?=null)
@Serializable data class PracticeRequestData(val sessionId:String, val revision:Long, val stage:PracticeStage,
    val difficulty:PracticeDifficulty, val turns:List<PracticeRound> = emptyList())

/** RAM authority: the original evidence is retained to compare effective capture eligibility. */
data class PracticeSourceLease(val contactId:String?,val sourceRevision:Long,val conversationId:String,
    val conversationRevision:Long,val evidence:List<EvidenceBlock>,val privacyRevision:Long,val configRevision:String) {
    fun opponents():List<EvidenceBlock> = evidence.mapNotNull { block ->
        block.lines.filter { it.speaker==Speaker.THEM && it.speakerConfirmed && it.contactId==contactId && it.text.isNotBlank() }
            .takeLast(12).takeIf {it.isNotEmpty()}?.let {block.copy(lines=it,text="")}
    }
}
data class PracticeSummaryLease(val sessionId:String,val revision:Long,val source:PracticeSourceLease,val tip:Tip)
data class PracticeResult(val counterpartLine:String?=null,val feedback:String?=null,val betterVersion:String?=null,val summary:Tip?=null)
@Serializable data class PracticeSelection(val totalTurns:Int,val selectedTurns:Int,val totalOpponentMessages:Int,val opponentMessageIds:List<String>) {
    val omittedTurns:Int get()=totalTurns-selectedTurns
    val omittedOpponentMessages:Int get()=totalOpponentMessages-opponentMessageIds.size
    init {require(selectedTurns in 0..totalTurns && opponentMessageIds.isNotEmpty() && opponentMessageIds.distinct()==opponentMessageIds && opponentMessageIds.size<=totalOpponentMessages)}
}
class PracticeInputRangeException:IllegalArgumentException("Practice input exceeds outbound range")

/** A protocol failure is retryable; malformed JSON is never rendered as a practice turn. */
object PracticeProtocol {
    private val json=Json
    fun parse(text:String,stage:PracticeStage):PracticeResult {
        require(text.length<=4000)
        val obj=json.parseToJsonElement(text.trim()).jsonObject
        val expected=when(stage) {PracticeStage.OPENING->setOf("counterpartLine");PracticeStage.ROUND->setOf("counterpartLine","feedback","betterVersion");PracticeStage.SUMMARY->setOf("name","explanation","example")}
        require(obj.keys==expected)
        fun line(key:String,max:Int):String {
            val value=(obj[key] as? JsonPrimitive)?.takeIf {it.isString}?.content ?: error("Expected string")
            require(value.isNotBlank() && value.codePointCount(0,value.length)<=max && '\n' !in value && '\r' !in value && "[[" !in value)
            return value.trim()
        }
        return when(stage) {
            PracticeStage.OPENING->PracticeResult(counterpartLine=line("counterpartLine",200))
            PracticeStage.ROUND->PracticeResult(line("counterpartLine",200),line("feedback",120),line("betterVersion",200))
            PracticeStage.SUMMARY->PracticeResult(summary=Tip(name=line("name",40),explanation=line("explanation",160),example=line("example",200)))
        }
    }
}
