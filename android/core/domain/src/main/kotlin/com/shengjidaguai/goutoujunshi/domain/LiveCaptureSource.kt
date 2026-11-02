package com.shengjidaguai.goutoujunshi.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

@Serializable data class CaptureAliasWitness(val id:String,val contentHash:String,val source:ImportSource,val normalizedHash:String)
@Serializable data class CaptureFrameWitness(val normalizedHash:String,val semanticHash:String)
@Serializable data class LiveCaptureProvenance(val lineageId:String,val contactId:String,val winner:ChatImport,
    val normalizedHash:String,val semanticHash:String,val revision:Long=1,
    val aliases:Map<String,CaptureAliasWitness> = emptyMap(),val normalizedHistory:Set<String> = emptySet(),
    val requiresConfirmation:Boolean=false,val operationId:String="",val expectedSourceRevision:Long=0,
    val historyVersion:Int=0,val frames:Set<CaptureFrameWitness> = emptySet())
class CaptureHistoryReviewRequired:IllegalStateException("CAPTURE_HISTORY_REVIEW")
data class LiveCaptureReceipt(val imported:ChatImport,val provenance:LiveCaptureProvenance,val duplicate:Boolean)
data class EffectiveImportSelection(val imports:List<ChatImport>,val blocked:Boolean=false,val preferred:Boolean=false)
data class EffectiveCaptureEvidence(val evidence:List<EvidenceBlock>,val blocked:Boolean,val preferred:Boolean)
object LiveCaptureFingerprint {
    const val FRAME_HISTORY_VERSION=1
    const val PREFIX="live-capture:"
    fun id(operation:String)=if(operation.startsWith(PREFIX)) operation else PREFIX+operation
    fun normalized(lines:List<ChatLine>)=canonical(lines,true)
    fun semantic(lines:List<ChatLine>)=canonical(lines,false)
    fun stored(lineage:String,normalized:String)=digest("live-capture-v1|$lineage|$normalized")
    private fun canonical(lines:List<ChatLine>,speakers:Boolean)=digest(buildJsonArray {
        lines.sortedBy {it.seq}.forEach {line->add(buildJsonObject {
            put("name",line.speakerName.orEmpty());put("text",line.text);put("at",line.sentAt)
            if(speakers) put("speaker",line.speaker.name)
        })}
    }.toString())
    private fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
}
