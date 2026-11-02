package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*
import java.util.UUID

class ConsentRepository(private val session: DataSession) {
    suspend fun recordDecline(owner: String, stamp: WriteStamp, declinedUntil: Long, onAuthorized: () -> Unit = {}): ConsentState = session.transaction {
        checkManagementStamp(session,stamp,owner)
        require(memoryRevision(owner)==stamp.memoryRevision)
        val current=currentConsent()
        require(!current.enabled && !current.paused)
        onAuthorized()
        current.copy(declinedUntil=declinedUntil,consentVersion=current.consentVersion+1).also { put(ConsentRow(payload=encode(it))) }
    }
    suspend fun get(): ConsentState = session.transaction { currentConsent() }
    /** Increment on every authorization change, including pause/resume, to reject late responses. */
    suspend fun update(enabled: Boolean, paused: Boolean = false, declinedUntil: Long = 0, expected: WriteStamp? = null, expectedClear: MemoryClearAuthority? = null, onAuthorized: () -> Unit = {}): ConsentState = session.transaction {
        if(expected!=null) checkConsentDisplayStamp(session,expected)
        if(!enabled && expectedClear!=null) require(clearMemoryAuthority(session)==expectedClear) { "Withdrawal display is stale" }
        onAuthorized()
        val previous = currentConsent()
        val next = previous.copy(enabled = enabled, paused = paused, consentVersion = previous.consentVersion + 1,
            updatedAt = System.currentTimeMillis(), declinedUntil = declinedUntil)
        put(ConsentRow(payload = encode(next)))
        if (!enabled) { clearSamples(); clearAllMemories(); clearAllOperations(); clearMemorySources(); clearMemoryAnchors() }
        invalidateAndRecompute()
        next
    }
}

data class MemoryClearAuthority(val generation: Long, val consentVersion: Long, val fingerprint: String,
    val memoryCount: Int, val contactCount: Int, val importCount: Int, val scope: String = "all_contact_memories")

data class MemorySnapshot(val consent: ConsentState = ConsentState(), val stamp: WriteStamp? = null,
    val entries: List<MemoryEntry> = emptyList(), val operationId: String? = null, val rejected: Set<String> = emptySet(), val anchors: Map<String,String> = emptyMap(), val clearAuthority: MemoryClearAuthority? = null, val verifiedInferenceIds: Set<String> = emptySet()) {
    fun outboundEntries(): List<MemoryEntry> = entries.filter { it.sourceType!=MemorySource.ASSISTANT_INFERENCE || it.id in verifiedInferenceIds }
}

class MemoryRepository(private val session: DataSession) {
    /** Management-only compatibility API; destructive display authority is not model context. */
    suspend fun snapshot(contactId:String?):MemorySnapshot = readSnapshot(contactId,management=true)
    /** Null reads consent/version metadata only; non-null reads just this owner's retained sources. */
    suspend fun contextSnapshot(contactId:String?):MemorySnapshot = readSnapshot(contactId,management=false)
    private suspend fun readSnapshot(contactId:String?,management:Boolean):MemorySnapshot = session.transaction {
        if(contactId!=null) {
            if(!management) check(!effectiveCaptureSelection(contactId).blocked) {"CAPTURE_SOURCE_RECONFIRM"}
            pruneDerivedMemories(ownerId=contactId)
        }
        val consent = currentConsent()
        val contact = contactId?.let { requireContact(it) }
        MemorySnapshot(consent, WriteStamp(consent.consentVersion, metadata()?.styleRevision ?: 0, session.generation, contact?.revision, contactId?.let { memoryRevision(it) }),
            if(contactId==null) emptyList() else memories(contactId).map { decode(it.payload) },
            if(contactId==null) null else operations(contactId).lastOrNull()?.id,
            if(contactId==null) emptySet() else records(contactId, OwnedRecordKind.MEMORY_REJECTION.name).map { decode<String>(it.payload) }.toSet(),
            if(contactId==null) emptyMap() else memoryAnchors(contactId).associate { it.id to it.fingerprint },if(management) clearMemoryAuthority(session) else null,
            if(contactId==null) emptySet() else records(contactId,OwnedRecordKind.MEMORY_SOURCE.name).map { it.id.removePrefix("MEMORY_SOURCE:") }.toSet())
    }

    suspend fun list(contactId: String): List<MemoryEntry> = session.transaction { pruneDerivedMemories(ownerId=contactId);memories(contactId).map { decode(it.payload) } }
    suspend fun operations(contactId: String): List<MemoryOperation> = session.transaction { pruneDerivedMemories(ownerId=contactId);operations(contactId).map { decode(it.payload) } }
    /** Untrusted model outputs must use applyProposal, which always forces inference provenance. */
    private fun proposalEntry(contactId: String,proposal: MemoryProposal,id: String=UUID.randomUUID().toString()): MemoryEntry {
        val namespace=java.security.MessageDigest.getInstance("SHA-256").digest(proposal.sourceRef.ifBlank { id }.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        return MemoryEntry(id=id,contactId=contactId,scope=proposal.scope,subjectId=contactId,field=proposal.field,
            value=ContinuousAnalysis.scopeHistoricalTokens(proposal.value,"记忆$namespace"),sourceType=MemorySource.ASSISTANT_INFERENCE,
            sourceRef=proposal.sourceRef,occurredAt=proposal.occurredAt,confidence=Confidence.LOW,createdAt=System.currentTimeMillis(),updatedAt=System.currentTimeMillis())
    }
    suspend fun applyProposal(contactId: String, proposal: MemoryProposal, stamp: WriteStamp): MemoryOperation =
        applyPlanned(contactId,proposalEntry(contactId,proposal),stamp,fromModel=true,sourceRequestId=proposal.sourceRef)

    /** Authorization and the proposal have one commit point; every suspended step precedes a final UI guard. */
    suspend fun approveProposal(contactId: String,proposal: MemoryProposal,stamp: WriteStamp,operationId: String,onAuthorized: () -> Unit): MemoryOperation = session.transaction {
        session.checkGeneration(stamp)
        require(operationId.length<=128 && ownerOfMemoryOperation(operationId).let { it==null || it==contactId })
        val entry=proposalEntry(contactId,proposal,"approval:$operationId")
        val proof=MemoryApprovalProof(stamp.generation,stamp.consentVersion,stamp.sourceRevision,stamp.contactRevision,stamp.memoryRevision,sourceHash(encode(MemoryPolicy.validateEntry(entry).copy(createdAt=0,updatedAt=0))))
        val latest=operations(contactId).lastOrNull()?.let { decode<MemoryOperation>(it.payload) }
        if(latest!=null && latest.id==operationId) {
            val consent=currentConsent()
            require(latest.approval==proof && consent.enabled && !consent.paused && consent.policyVersion=="1" && consent.consentVersion==stamp.consentVersion+1)
            require(requireContact(contactId).revision==stamp.contactRevision && (metadata()?.styleRevision ?: 0)==stamp.sourceRevision+1)
            require(memories(contactId).map { decode<MemoryEntry>(it.payload) }==latest.after && memoryAnchors(contactId).toSet()==latest.afterAnchors.toSet())
            verifiedMemoryReceipt(contactId,proposal.sourceRef)
            onAuthorized()
            return@transaction latest
        }
        checkManagementStamp(session,stamp,contactId)
        require(memoryRevision(contactId)==stamp.memoryRevision)
        val consent=currentConsent()
        require(!consent.enabled && !consent.paused && consent.policyVersion=="1")
        verifiedMemoryReceipt(contactId,proposal.sourceRef)
        onAuthorized()
        val enabled=consent.copy(enabled=true,paused=false,consentVersion=consent.consentVersion+1,declinedUntil=0,updatedAt=System.currentTimeMillis())
        put(ConsentRow(payload=encode(enabled)))
        invalidateAndRecompute()
        val approved=WriteStamp(enabled.consentVersion,metadata()?.styleRevision ?: 0,session.generation,requireContact(contactId).revision,memoryRevision(contactId))
        val operation=applyLocked(contactId,entry,approved,fromModel=true,sourceRequestId=proposal.sourceRef,operationId=operationId,approval=proof)
        onAuthorized()
        operation
    }
    suspend fun apply(contactId: String, entry: MemoryEntry, stamp: WriteStamp, operationId: String? = null): MemoryOperation = applyPlanned(contactId,entry,stamp,operationId=operationId)
    private suspend fun applyPlanned(contactId: String, entry: MemoryEntry, stamp: WriteStamp, fromModel: Boolean = false, sourceRequestId: String? = null, operationId: String? = null): MemoryOperation = session.transaction { applyLocked(contactId,entry,stamp,fromModel,sourceRequestId,operationId) }
    private suspend fun JunshiDao.applyLocked(contactId: String, entry: MemoryEntry, stamp: WriteStamp, fromModel: Boolean = false, sourceRequestId: String? = null, operationId: String? = null, approval: MemoryApprovalProof? = null): MemoryOperation {
        checkStamp(session, stamp, contactId)
        if(operationId!=null) {
            require(operationId.length<=128 && (!fromModel || approval!=null))
            require(ownerOfMemoryOperation(operationId).let { it==null || it==contactId })
            val latest=operations(contactId).lastOrNull()?.let { decode<MemoryOperation>(it.payload) }
            if(!fromModel && latest!=null && latest.id==operationId) {
                val normalized=MemoryPolicy.validateEntry(entry)
                val committed=latest.after.firstOrNull { it.id==entry.id }
                require(committed!=null && committed.copy(createdAt=0,updatedAt=0)==normalized.copy(createdAt=0,updatedAt=0) && memories(contactId).map { decode<MemoryEntry>(it.payload) }==latest.after) { "Operation retry changed" }
                return latest
            }
        }
        if(stamp.memoryRevision != memoryRevision(contactId)) throw MemoryWriteRejected("STALE_MEMORY", "记忆已变更")
        val receipt=sourceRequestId?.let { verifiedMemoryReceipt(contactId,it) }
        require(entry.id.isNotBlank())
        require(ownerOfMemory(entry.id).let { it == null || it == contactId })
        val trusted = if (fromModel) entry.copy(sourceType = MemorySource.ASSISTANT_INFERENCE) else entry
        val before = memories(contactId).map { decode<MemoryEntry>(it.payload) }
        val beforeAnchors=memoryAnchors(contactId)
        val key = LongTermMemoryPolicy.contentFingerprint(contactId,trusted.value)
        if (records(contactId, OwnedRecordKind.MEMORY_REJECTION.name).any { decode<String>(it.payload) == key })
            throw MemoryWriteRejected("REJECTED_CONTENT", "这条内容已被标错")
        if(fromModel && before.any { LongTermMemoryPolicy.contentKey(it.value)==LongTermMemoryPolicy.contentKey(trusted.value) })
            throw MemoryWriteRejected("DUPLICATE_MEMORY","相同内容已经记住")
        val after = if (fromModel) LongTermMemoryPolicy.planInference(trusted, before)
            else MemoryPolicy.planWrite(trusted, currentConsent(), stamp.consentVersion, contactId, before)
        if (after == before || (fromModel && after.none { it.id == trusted.id }))
            throw MemoryWriteRejected("MEMORY_LIMIT_REACHED", "未替换更高优先级的记忆")
        // Only automatic capacity eviction keeps a value-free provenance anchor.
        if(fromModel) before.filter { old -> after.none { it.id==old.id } }.forEach { old ->
            if(record(contactId,OwnedRecordKind.MEMORY_SOURCE.name,old.id)!=null) putMemoryAnchor(contactId,MemoryAnchor(old.id,MemoryContextPolicy.entryFingerprint(old),LongTermMemoryPolicy.contentFingerprint(contactId,old.value)))
        } else deleteMemoryAnchor(contactId,entry.id)
        replaceMemories(contactId, after)
        if(receipt!=null) {
            require(ownerOfRecord("MEMORY_SOURCE:${entry.id}").let { it==null || it==contactId }) { "Source receipt belongs to another owner" }
            put(OwnedRecordRow("MEMORY_SOURCE:${entry.id}",contactId,OwnedRecordKind.MEMORY_SOURCE,encode(receipt)))
        }
        advanceMemoryRevision(contactId)
        pruneDerivedMemories(collect=false,ownerId=contactId)
        val finalAfter=memories(contactId).map { decode<MemoryEntry>(it.payload) }
        if(fromModel && finalAfter.none { it.id==trusted.id }) throw MemoryWriteRejected("STALE_MEMORY","提案依赖的记忆已失效")
        val previousOps=operations(contactId)
        val order=(previousOps.lastOrNull()?.commitOrder ?: 0)+1
        val operation=MemoryOperation(operationId ?: UUID.randomUUID().toString(),contactId,before,finalAfter,System.currentTimeMillis(),beforeAnchors=beforeAnchors,afterAnchors=memoryAnchors(contactId),action=if(approval!=null) "approve" else "apply",approval=approval)
        put(MemoryOperationRow(operation.id, contactId, order, encode(operation)))
        val retained = MemoryPolicy.retainOperations(previousOps.map { decode<MemoryOperation>(it.payload) } + operation)
        clearOperations(contactId)
        retained.forEachIndexed { index, item -> put(MemoryOperationRow(item.id, contactId, index.toLong(), encode(item))) }
        collectMemoryAuthority(contactId)
        return operation
    }
    suspend fun delete(contactId: String, id: String, stamp: WriteStamp): MemoryOperation = session.transaction {
        checkManagementStamp(session,stamp,contactId)
        if(stamp.memoryRevision != memoryRevision(contactId)) throw MemoryWriteRejected("STALE_MEMORY", "记忆已变更")
        val before = memories(contactId).map { decode<MemoryEntry>(it.payload) }
        val beforeAnchors=memoryAnchors(contactId)
        require(before.any { it.id == id } || beforeAnchors.any { it.id==id })
        deleteMemoryAnchor(contactId,id)
        val after = before.filterNot { it.id == id }
        replaceMemories(contactId, after)
        advanceMemoryRevision(contactId)
        pruneDerivedMemories(collect=false,ownerId=contactId)
        val op=MemoryOperation(UUID.randomUUID().toString(),contactId,before,memories(contactId).map { decode(it.payload) },System.currentTimeMillis(),"delete",beforeAnchors,memoryAnchors(contactId))
        val retained = MemoryPolicy.retainOperations(operations(contactId).map { decode<MemoryOperation>(it.payload) } + op)
        clearOperations(contactId)
        retained.forEachIndexed { index, item -> put(MemoryOperationRow(item.id, contactId, index.toLong(), encode(item))) }
        collectMemoryAuthority(contactId)
        op
    }
    suspend fun undoLatest(contactId: String, stamp: WriteStamp, operationId: String? = null) = session.transaction {
        checkStamp(session, stamp, contactId)
        if(stamp.memoryRevision != memoryRevision(contactId)) throw MemoryWriteRejected("STALE_MEMORY", "记忆已变更")
        val result = MemoryPolicy.undoLatest(memories(contactId).map { decode(it.payload) },
            operations(contactId).map { decode(it.payload) }, operationId)
        // Verify the restored state too; no partial write if a quota is violated.
        require(MemoryPolicy.enforceQuotas(result.entries) == result.entries)
        val rejected = records(contactId, OwnedRecordKind.MEMORY_REJECTION.name).map { decode<String>(it.payload) }.toSet()
        require(result.entries.none { LongTermMemoryPolicy.contentFingerprint(contactId,it.value) in rejected }) { "Wrong content cannot be restored" }
        val latest=operations(contactId).last().let { decode<MemoryOperation>(it.payload) }
        require(memoryAnchors(contactId).toSet()==latest.afterAnchors.toSet()) { "Undo anchor conflict" }
        replaceMemoryAnchors(contactId,latest.beforeAnchors)
        replaceMemories(contactId, result.entries)
        pruneDerivedMemories(collect=false,ownerId=contactId)
        require(memories(contactId).map { decode<MemoryEntry>(it.payload) }==result.entries) { "Undo source changed" }
        advanceMemoryRevision(contactId)
        clearOperations(contactId)
        result.operations.forEachIndexed { index, op -> put(MemoryOperationRow(op.id, contactId, index.toLong(), encode(op))) }
        collectMemoryAuthority(contactId)
    }
    suspend fun markWrong(contactId: String, id: String, stamp: WriteStamp) = session.transaction {
        checkManagementStamp(session,stamp,contactId)
        if(stamp.memoryRevision != memoryRevision(contactId)) throw MemoryWriteRejected("STALE_MEMORY", "记忆已变更")
        val entry = memories(contactId).map { decode<MemoryEntry>(it.payload) }.firstOrNull { it.id == id }
        val key = entry?.let { LongTermMemoryPolicy.contentFingerprint(contactId,it.value) } ?: memoryAnchors(contactId).first { it.id==id }.contentKey
        memoryAnchors(contactId).filter { it.contentKey==key }.forEach { deleteMemoryAnchor(contactId,it.id) }
        put(OwnedRecordRow("MEMORY_REJECTION:$contactId:$key", contactId, OwnedRecordKind.MEMORY_REJECTION, encode(key)))
        replaceMemories(contactId, memories(contactId).map { decode<MemoryEntry>(it.payload) }.filterNot { LongTermMemoryPolicy.contentFingerprint(contactId,it.value)==key })
        clearOperations(contactId)
        advanceMemoryRevision(contactId)
        pruneDerivedMemories(collect=false,ownerId=contactId)
        collectMemoryAuthority(contactId)
    }
    /** Clearing memory is distinct from revoking learning consent and from deleting all data. */
    suspend fun clear(expected: MemoryClearAuthority, onAuthorized: () -> Unit = {}) = session.transaction {
        require(expected.scope=="all_contact_memories" && clearMemoryAuthority(session)==expected) { "Clear display is stale" }
        onAuthorized()
        clearAllMemories(); clearAllOperations(); clearMemorySources(); clearMemoryAnchors()
        contacts().forEach { advanceMemoryRevision(it.id) }
    }
}

internal suspend fun JunshiDao.replaceMemories(contactId: String, entries: List<MemoryEntry>) {
    // Snapshot IDs use a global primary key. Validate the entire restoration before deleting
    // anything: an ID freed by this contact may since have been claimed by another contact.
    entries.forEach { entry ->
        require(entry.contactId == contactId) { "Memory snapshot belongs to another contact" }
        require(ownerOfMemory(entry.id).let { it == null || it == contactId }) { "Memory ID belongs to another contact" }
    }
    clearMemories(contactId)
    entries.forEach { put(MemoryRow(it.id, contactId, encode(it))) }
}

/** Owner revision fences memory snapshots without changing learning authorization or style data. */
internal suspend fun JunshiDao.advanceMemoryRevision(contactId: String) {
    val revision=memoryRevision(contactId)+1
    put(OwnedRecordRow("MEMORY_STATE:$contactId",contactId,OwnedRecordKind.MEMORY_STATE,encode(revision)))
    clearSummaries(contactId)
}

private suspend fun JunshiDao.checkManagementStamp(session: DataSession, stamp: WriteStamp, owner: String) {
    session.checkGeneration(stamp)
    require(currentConsent().consentVersion==stamp.consentVersion) { "Stale consent" }
    require(requireContact(owner).revision==stamp.contactRevision) { "Stale owner" }
    require((metadata()?.styleRevision ?: 0)==stamp.sourceRevision) { "Stale source" }
}

@kotlinx.serialization.Serializable
internal data class MemorySourceReceipt(val requestId: String, val conversationId: String,
    val userHash: String, val lines: Map<String,String>, val memoryDependencies: Map<String,String> = emptyMap(), val runHash: String = "", val assistantHash: String = "")
private fun sourceHash(value: String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
private suspend fun JunshiDao.verifiedMemoryReceipt(owner: String,requestId: String): MemorySourceReceipt {
    val run=decode<AnalysisRun>(requireNotNull(record(owner,OwnedRecordKind.ANALYSIS_RUN.name,requestId)).payload)
    val request=requireNotNull(run.requestSnapshot)
    require(!effectiveCaptureSelection(owner).blocked) {"Capture source needs confirmation"}
    require(run.status==RunStatus.COMPLETED && request.contactId==owner && request.mode==RequestMode.FULL_ANALYSIS && request.purpose==OutboundPurpose.ANALYSIS)
    require(request.sourceRevision==requireContact(owner).revision) { "Memory source revision changed" }
    require(request.evidence.flatMap { it.lines }.all { it.speakerConfirmed })
    val messages=messages(owner,request.conversationId).map { decode<Message>(it.payload) }
    val assistant=requireNotNull(messages.singleOrNull { it.requestId==requestId && it.role==MessageRole.ASSISTANT && it.status==MessageStatus.COMPLETED && it.providerKind==ProviderKind.BYOK })
    val receipt=MemorySourceReceipt(requestId,request.conversationId,sourceHash(request.userText),request.evidence.flatMap { it.lines }.associate { it.id to sourceHash(encode(it)) },MemoryContextPolicy.dependencies(request.actualDataContext),sourceHash(encode(request)),sourceHash(encode(assistant)))
    require(request.requestId.length<=128 && request.conversationId.length<=128 && receipt.lines.size<=512 && receipt.lines.keys.all { it.length<=128 } && receipt.memoryDependencies.size<=200 && receipt.memoryDependencies.keys.all { it.length<=128 }) { "Memory source metadata limit" }
    require(memorySourceRetained(owner,receipt)) { "Memory evidence changed" }
    return receipt
}
internal suspend fun JunshiDao.memorySourceRetained(owner: String,receipt: MemorySourceReceipt): Boolean {
    val run=record(owner,OwnedRecordKind.ANALYSIS_RUN.name,receipt.requestId)?.let { decode<AnalysisRun>(it.payload) } ?: return false
    if(run.status!=RunStatus.COMPLETED || run.requestSnapshot?.let { sourceHash(encode(it)) }!=receipt.runHash) return false
    val sourceMessages=messages(owner,receipt.conversationId).map { decode<Message>(it.payload) }
    if(sourceMessages.none { it.requestId==receipt.requestId && it.role==MessageRole.USER && sourceHash(it.text)==receipt.userHash }) return false
    if(sourceMessages.none { it.requestId==receipt.requestId && it.role==MessageRole.ASSISTANT && it.status==MessageStatus.COMPLETED && it.providerKind==ProviderKind.BYOK && sourceHash(encode(it))==receipt.assistantHash }) return false
    val entries=memories(owner).map { decode<MemoryEntry>(it.payload) }.associateBy { it.id }
    val anchors=memoryAnchors(owner).associateBy { it.id }
    if(!receipt.memoryDependencies.all { (id,hash) -> entries[id]?.let { it.active && MemoryContextPolicy.entryFingerprint(it)==hash }==true || anchors[id]?.fingerprint==hash }) return false
    val eligible=effectiveCaptureSelection(owner).imports.map {it.id}.toSet()
    return receipt.lines.all { (id,hash) -> line(owner,id)?.let { it.importId in eligible && sourceHash(encode(decode<ChatLine>(it.payload)))==hash }==true }
}
/** Called in the same source mutation transaction; no stale derived value remains in undo payloads. */
internal suspend fun JunshiDao.pruneDerivedMemories(collect: Boolean = true, ownerId: String? = null) {
    (if(ownerId==null) contacts() else listOfNotNull(contact(ownerId))).forEach { contact ->
        do {
            val existingIds=memories(contact.id).map { it.id }.toSet()+memoryAnchors(contact.id).map { it.id }
            val sources=records(contact.id,OwnedRecordKind.MEMORY_SOURCE.name).associateBy { it.id.removePrefix("MEMORY_SOURCE:") }
            val stale=existingIds.filter { id ->
                val source=sources[id]
                (source!=null && !memorySourceRetained(contact.id,decode(source.payload))) || (memoryAnchors(contact.id).any { it.id==id } && source==null)
            }.toSet()
            if(stale.isNotEmpty()) {
                replaceMemories(contact.id,memories(contact.id).map { decode<MemoryEntry>(it.payload) }.filterNot { it.id in stale })
                stale.forEach { deleteMemoryAnchor(contact.id,it) }
                clearOperations(contact.id);advanceMemoryRevision(contact.id)
            }
        } while(stale.isNotEmpty())
        if(collect) collectMemoryAuthority(contact.id)
    }
}

private suspend fun JunshiDao.checkConsentDisplayStamp(session: DataSession, stamp: WriteStamp) {
    session.checkGeneration(stamp)
    require(currentConsent().consentVersion==stamp.consentVersion) { "Stale consent display" }
}
private suspend fun JunshiDao.clearMemoryAuthority(session: DataSession): MemoryClearAuthority {
    var count=0;var importCount=0
    val parts=mutableListOf<String>()
    contacts().forEach { contact ->
        parts+=contact.id;parts+=contact.revision.toString()
        imports(contact.id).forEach { parts+=it.id;parts+=it.payload;importCount++ }
        memories(contact.id).forEach { parts+=it.id;parts+=it.payload;count++ }
        operations(contact.id).forEach { parts+=it.id;parts+=it.payload }
        listOf(OwnedRecordKind.MEMORY_STATE,OwnedRecordKind.MEMORY_ANCHOR,OwnedRecordKind.MEMORY_SOURCE,OwnedRecordKind.MEMORY_REJECTION).forEach { kind ->
            records(contact.id,kind.name).forEach { parts+=it.id;parts+=it.payload }
        }
    }
    return MemoryClearAuthority(session.generation,currentConsent().consentVersion,sourceHash(encode(parts)),count,contacts().size,importCount)
}
