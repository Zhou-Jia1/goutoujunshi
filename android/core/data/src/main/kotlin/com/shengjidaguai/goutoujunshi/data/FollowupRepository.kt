package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.serialization.Serializable
import java.util.UUID

/** Only this payload in SQLCipher contains owner, source and scheduling choices. */
@Serializable data class FollowupRecord(val id:String, val generation:String, val owner:String, val planId:String,
    val privacyRevision:Long, val due:Long, val active:Boolean=true, val delivered:Boolean=false)
interface FollowupEffects {
    fun clear() {retain(emptySet())}
    fun cancel(id:String)
    fun retain(ids:Set<String>)
}
class FollowupRepository(private val session:DataSession) {
    suspend fun bind(effects:FollowupEffects) { session.transaction { session.reminderEffects=effects } }
    suspend fun schedule(owner:String,planId:String,due:Long,now:Long,optIn:Boolean,permission:Boolean):FollowupRecord? = session.transaction {
        if(!optIn || !permission) return@transaction null
        require(due>now)
        val privacy=records(owner,OwnedRecordKind.REDACTION.name).singleOrNull()?.let {decode<RedactionProfile>(it.payload)} ?: RedactionProfile()
        val next=FollowupRecord(UUID.randomUUID().toString(),UUID.randomUUID().toString(),owner,planId,privacy.revision,due)
        check(validFollowup(next))
        followups().map {decode<FollowupRecord>(it.payload)}.filter {it.owner==owner && it.planId==planId && it.active}.forEach { old ->
            putOwned(owner,old.id,OwnedRecordKind.FOLLOWUP,encode(old.copy(active=false)))
        }
        putOwned(owner,next.id,OwnedRecordKind.FOLLOWUP,encode(next)); next
    }
    suspend fun cancelAnswer(owner:String,answerId:String) = session.transaction {
        followups().map {decode<FollowupRecord>(it.payload)}.filter {r->r.owner==owner && record(owner,OwnedRecordKind.PLAN.name,r.planId)?.let {decode<ActionPlan>(it.payload).sourceAnswerId==answerId}==true}.forEach {r->
            putOwned(owner,r.id,OwnedRecordKind.FOLLOWUP,encode(r.copy(active=false)));session.reminderEffects?.cancel(r.id)
        }
    }
    suspend fun cancel(id:String) = session.transaction {
        followup(id)?.let { row -> val old=decode<FollowupRecord>(row.payload);putOwned(old.owner,id,OwnedRecordKind.FOLLOWUP,encode(old.copy(active=false))) }
        session.reminderEffects?.cancel(id)
    }
    suspend fun cancelAll() = session.transaction {
        followups().map {decode<FollowupRecord>(it.payload)}.forEach { old ->
            putOwned(old.owner,old.id,OwnedRecordKind.FOLLOWUP,encode(old.copy(active=false)));session.reminderEffects?.cancel(old.id)
        }
    }
    /** Platform enqueue/notify runs while holding the same gate as deletion and full clear. */
    suspend fun <T> current(id:String,generation:String,block:(FollowupRecord,Contact,ActionPlan)->T):T? = session.transaction {
        val r=followup(id)?.let {decode<FollowupRecord>(it.payload)} ?: return@transaction null
        if(r.generation!=generation || !validFollowup(r)) return@transaction null
        block(r,decode<Contact>(requireContact(r.owner).payload),decode<ActionPlan>(requireNotNull(record(r.owner,OwnedRecordKind.PLAN.name,r.planId)).payload))
    }
    suspend fun deliver(id:String,generation:String,permission:()->Boolean,now:()->Long=System::currentTimeMillis,ready:(FollowupRecord)->Boolean={true},notify:(FollowupRecord)->Unit):Boolean = session.transaction {
        val r=followup(id)?.let {decode<FollowupRecord>(it.payload)} ?: return@transaction false
        if(r.generation!=generation || r.delivered || !permission() || !validFollowup(r) || !ready(r) || now()<r.due) return@transaction false
        notify(r)
        putOwned(r.owner,r.id,OwnedRecordKind.FOLLOWUP,encode(r.copy(delivered=true)));true
    }
}
internal suspend fun JunshiDao.validFollowup(r:FollowupRecord):Boolean {
    if(!r.active || contact(r.owner)==null || effectiveCaptureSelection(r.owner).blocked) return false
    val p=record(r.owner,OwnedRecordKind.PLAN.name,r.planId)?.let {decode<ActionPlan>(it.payload)} ?: return false
    if(p.status!=PlanStatus.SENT || p.contactId!=r.owner || requireContact(r.owner).revision!=p.sourceRevision) return false
    val c=conversation(r.owner,p.conversationId)?.let {decode<Conversation>(it.payload)} ?: return false
    if(c.revision!=p.conversationRevision) return false
    val privacy=records(r.owner,OwnedRecordKind.REDACTION.name).singleOrNull()?.let {decode<RedactionProfile>(it.payload)} ?: RedactionProfile()
    if(privacy.revision!=r.privacyRevision) return false
    val a=p.sourceAnswerId?.let {message(r.owner,it)}?.let {decode<Message>(it.payload)} ?: return false
    if(a.role!=MessageRole.ASSISTANT || a.requestId!=p.sourceRequestId || a.conversationId!=p.conversationId) return false
    val run=records(r.owner,OwnedRecordKind.ANALYSIS_RUN.name).map {decode<AnalysisRun>(it.payload)}.firstOrNull {it.requestId==p.sourceRequestId} ?: return false
    val request=run.requestSnapshot ?: return false
    if(request.contactId!=r.owner || request.conversationId!=p.conversationId || request.sourceRevision!=p.sourceRevision || request.conversationRevision!=p.conversationRevision || request.inputRevision!=p.inputRevision || request.redactionProfile.revision!=r.privacyRevision) return false
    return request.evidence.all { b -> captureImportEligible(r.owner,b.id) &&
        imported(r.owner,b.id)?.let {decode<ChatImport>(it.payload).sourceType.name==b.source}==true &&
        lines(r.owner,b.id).map {decode<ChatLine>(it.payload)}==b.lines }
}
internal suspend fun JunshiDao.reconcileFollowups(effects:FollowupEffects) {
    val valid=mutableSetOf<String>()
    followups().map {decode<FollowupRecord>(it.payload)}.forEach { r ->
        if(validFollowup(r)) valid+=r.id else { if(r.active) effects.cancel(r.id)
            if(r.active) putOwned(r.owner,r.id,OwnedRecordKind.FOLLOWUP,encode(r.copy(active=false)))
        }
    }
    effects.retain(valid)
}
