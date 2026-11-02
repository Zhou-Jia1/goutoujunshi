package com.shengjidaguai.goutoujunshi.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable

class MemoryWriteRejected(val code:String, message:String):IllegalArgumentException(message)
@Serializable data class UndoResult(val entries:List<MemoryEntry> = emptyList(), val operations:List<MemoryOperation> = emptyList())

/** Pure rules; repositories must invoke inside the same transaction as the write. */
object MemoryPolicy {
    const val MAX_ROWS=200
    const val MAX_OPERATIONS=20
    const val MAX_VALUE_CODEPOINTS=200
    val scopeLimits:Map<MemoryScope,Int> = mapOf(MemoryScope.USER to 30,MemoryScope.OBJECT to 15,MemoryScope.RELATIONSHIP to 10,MemoryScope.EVENT to 20,MemoryScope.HYPOTHESIS to 5)
    private fun reject(code:String,message:String):Nothing=throw MemoryWriteRejected(code,message)
    fun requireConsent(consent:ConsentState, expectedVersion:Long) {
        if(!consent.enabled) reject("CONSENT_REQUIRED","用户尚未同意持久化")
        if(consent.paused) reject("MEMORY_PAUSED","持久化已暂停")
        if(consent.consentVersion!=expectedVersion) reject("STALE_CONSENT","同意版本已变更")
        if(consent.policyVersion!="1") reject("POLICY_VERSION","需要重新确认当前记忆政策")
    }
    /** Persist the returned normalized entry, never the caller's unnormalized input. */
    fun validateWrite(entry:MemoryEntry,consent:ConsentState,expectedConsentVersion:Long,contactId:String?,existing:List<MemoryEntry>):MemoryEntry {
        requireConsent(consent,expectedConsentVersion)
        if(contactId.isNullOrBlank()) reject("TEMPORARY_SESSION","临时会话不得持久化")
        if(entry.contactId!=contactId) reject("CONTACT_MISMATCH","记忆不属于当前联系人")
        val normalized = validateEntry(entry)
        enforceQuotas(existing.filterNot { it.id==normalized.id } + normalized)
        return normalized
    }
    fun planWrite(entry:MemoryEntry,consent:ConsentState,expectedConsentVersion:Long,contactId:String?,existing:List<MemoryEntry>):List<MemoryEntry> {
        val normalized = validateWrite(entry,consent,expectedConsentVersion,contactId,existing)
        return enforceQuotas(existing.filterNot { it.id==normalized.id } + normalized)
    }
    fun validateEntry(entry:MemoryEntry):MemoryEntry {
        val normalized = entry.copy(
            subjectId = validateText(entry.subjectId,64),
            field = validateText(entry.field,64),
            value = validateValue(entry.value),
            sourceRef = validateText(entry.sourceRef,200,false),
            occurredAt = entry.occurredAt?.let(::stripWhitespace)?.takeIf { it.isNotEmpty() },
        )
        if(entry.scope==MemoryScope.USER && entry.sourceType!=MemorySource.USER_EXPLICIT)
            reject("SOURCE_NOT_ELIGIBLE","本人稳定信息只接受明确陈述")
        if(entry.scope in setOf(MemoryScope.OBJECT,MemoryScope.RELATIONSHIP) && entry.sourceType !in setOf(MemorySource.USER_EXPLICIT,MemorySource.USER_REPORT))
            reject("SOURCE_NOT_ELIGIBLE","对象事实与关系只接受陈述或转述")
        if(entry.sourceType in setOf(MemorySource.CHATLAB,MemorySource.TOOL) && entry.scope !in setOf(MemoryScope.EVENT,MemoryScope.HYPOTHESIS))
            reject("SOURCE_NOT_ELIGIBLE","工具输出只接受事件或假设")
        if(entry.sourceType==MemorySource.ASSISTANT_INFERENCE && entry.scope!=MemoryScope.HYPOTHESIS)
            reject("SOURCE_NOT_ELIGIBLE","推断只能写入假设")
        validateDate(normalized.occurredAt)
        return normalized
    }
    /** Shared pre-submit and transaction rule; validation never freezes a UI operation. */
    fun validateValue(value: String): String = validateText(value,MAX_VALUE_CODEPOINTS)
    /** Match Python str.strip, including NEL. Removed boundary whitespace is never persisted. */
    private fun stripWhitespace(value: String): String = value.trim { it.isWhitespace() || it == '\u0085' }

    private fun validateText(value:String,max:Int,required:Boolean=true):String {
        val trimmed=stripWhitespace(value)
        if((required && trimmed.isEmpty()) || trimmed.codePointCount(0,trimmed.length)>max || trimmed.any { it.code<32 && it!='\t' && it!='\n' })
            reject("INVALID_DELTA","记忆字段不符合长度或文本要求")
        return trimmed
    }
    fun validateDate(value:String?) {
        if(value == null) return
        val text=stripWhitespace(value)
        if(text.isEmpty()) return
        if(!text.matches(Regex("\\d{4}-\\d{2}-\\d{2}(?:[Tt ]\\d{2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?)?"))) reject("INVALID_DATE","日期必须为ISO8601")
        try {
            if(text.length==10) LocalDate.parse(text) else {
                val normal=text.replace('t','T').replace(' ','T').replace(',','.')
                    .replace(Regex("([+-]\\d{2})(\\d{2})$"),"$1:$2")
                if(normal.endsWith('Z') || normal.drop(10).contains('+') || normal.drop(10).contains('-')) OffsetDateTime.parse(normal)
                else LocalDateTime.parse(normal)
            }
        } catch (_:java.time.DateTimeException) { reject("INVALID_DATE","无效日期") }
    }
    /** Stable profiles reject overflow; event/hypothesis rows evict oldest, as in Python. */
    fun enforceQuotas(entries:List<MemoryEntry>):List<MemoryEntry> {
        val result=entries.toMutableList()
        entries.filter { it.active }.groupBy { it.scope to it.subjectId }.forEach { (key,rows) ->
            val excess=rows.size-scopeLimits.getValue(key.first)
            if(excess>0) {
                if(key.first !in setOf(MemoryScope.EVENT,MemoryScope.HYPOTHESIS)) reject("MEMORY_LIMIT_REACHED","稳定档案已达配额")
                result.removeAll(rows.sortedBy { it.updatedAt }.take(excess).toSet())
            }
        }
        val overflow=result.size-MAX_ROWS
        if(overflow>0) result.removeAll(result.filter { it.scope in setOf(MemoryScope.EVENT,MemoryScope.HYPOTHESIS) }.sortedBy { it.updatedAt }.take(overflow).toSet())
        if(result.size>MAX_ROWS) reject("MEMORY_LIMIT_REACHED","记忆总量达到上限")
        return result
    }
    fun retainOperations(operations:List<MemoryOperation>):List<MemoryOperation> = operations.takeLast(MAX_OPERATIONS)
    /** Operations must be in commit order, oldest first. Unrelated rows remain untouched. */
    fun undoLatest(entries:List<MemoryEntry>,operations:List<MemoryOperation>,operationId:String?=null):UndoResult {
        val latest=operations.lastOrNull() ?: reject("NOTHING_TO_UNDO","没有可撤销的操作")
        if(operationId!=null && latest.id!=operationId) reject("UNDO_CONFLICT","只可撤销最新操作")
        val touched=(latest.before+latest.after).map { it.id }.toSet()
        val current=entries.filter { it.id in touched }.associateBy { it.id }
        if(current!=latest.after.associateBy { it.id }) reject("UNDO_CONFLICT","记录已发生变更")
        return UndoResult(entries.filterNot { it.id in touched } + latest.before,operations.dropLast(1))
    }
}

object StylePersistencePolicy {
    const val MAX_SAMPLES_PER_GROUP=30
    /** Prefer distinct, substantial examples, while reserving a few short expression signals.
     * Text diversity affects ranking only; IDs and provenance are never merged or rewritten. */
    fun retainSamples(samples:List<StyleSample>):List<StyleSample> = samples.groupBy { it.relationGroup }.toSortedMap(compareBy { it.ordinal }).values.flatMap { group ->
        val ranked=group.sortedWith(compareByDescending<StyleSample> { it.effectiveText.trim().codePointCount(0,it.effectiveText.trim().length) }
            .thenBy { it.effectiveText }.thenBy { it.id })
        val distinct=ranked.distinctBy { it.effectiveText.trim() }
        val short=distinct.filter { it.effectiveText.codePointCount(0,it.effectiveText.length)<=12 }.take(3)
        (short+distinct.filterNot { it in short }+ranked).distinctBy { it.id }.take(MAX_SAMPLES_PER_GROUP)
    }
    fun validate(sample:StyleSample,consent:ConsentState,currentSourceRevision:Long,isTemporary:Boolean) {
        MemoryPolicy.requireConsent(consent,sample.consentVersion)
        if(isTemporary) throw MemoryWriteRejected("TEMPORARY_SESSION","临时样本不得落库")
        if(sample.sourceRevision!=currentSourceRevision) throw MemoryWriteRejected("STALE_SOURCE","样本来源已变更")
        when(sample.origin) {
            StyleOrigin.IMPORT -> if(sample.sourceContactId.isNullOrBlank() || sample.sourceImportId.isNullOrBlank() || sample.sourceMessageId.isNullOrBlank())
                throw MemoryWriteRejected("SOURCE_REQUIRED","导入样本需要来源消息")
            StyleOrigin.EDITED_REPLY -> if(sample.sourceContactId.isNullOrBlank() || sample.sourceAnswerId.isNullOrBlank())
                throw MemoryWriteRejected("SOURCE_REQUIRED","编辑样本需要来源答案")
            StyleOrigin.QUIZ -> Unit
        }
    }
    fun validateProfile(profile:StyleProfile,consent:ConsentState,currentSourceRevision:Long,isTemporary:Boolean) {
        MemoryPolicy.requireConsent(consent,profile.consentVersion)
        if(isTemporary) throw MemoryWriteRejected("TEMPORARY_SESSION","临时画像不得落库")
        if(profile.sourceRevision!=currentSourceRevision) throw MemoryWriteRejected("STALE_SOURCE","画像来源已变更")
    }
}
