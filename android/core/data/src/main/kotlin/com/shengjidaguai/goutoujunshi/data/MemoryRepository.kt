package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*
import java.util.UUID

class ConsentRepository(private val session: DataSession) {
    suspend fun get(): ConsentState = session.transaction { currentConsent() }
    /** Increment on every authorization change, including pause/resume, to reject late responses. */
    suspend fun update(enabled: Boolean, paused: Boolean = false): ConsentState = session.transaction {
        val previous = currentConsent()
        val next = previous.copy(enabled = enabled, paused = paused, consentVersion = previous.consentVersion + 1,
            updatedAt = System.currentTimeMillis())
        put(ConsentRow(payload = encode(next)))
        if (!enabled) { clearSamples(); clearAllMemories(); clearAllOperations() }
        invalidateAndRecompute()
        next
    }
}

class MemoryRepository(private val session: DataSession) {
    suspend fun list(contactId: String): List<MemoryEntry> = session.transaction { memories(contactId).map { decode(it.payload) } }
    suspend fun operations(contactId: String): List<MemoryOperation> = session.transaction { operations(contactId).map { decode(it.payload) } }
    /** Untrusted model outputs must use applyProposal, which always forces inference provenance. */
    suspend fun applyProposal(contactId: String, proposal: MemoryProposal, stamp: WriteStamp): MemoryOperation {
        val entry = MemoryEntry(id = UUID.randomUUID().toString(), contactId = contactId, scope = proposal.scope,
            subjectId = proposal.subjectId, field = proposal.field, value = proposal.value,
            sourceType = MemorySource.ASSISTANT_INFERENCE, sourceRef = proposal.sourceRef,
            occurredAt = proposal.occurredAt, confidence = Confidence.LOW, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        return apply(contactId, entry, stamp, fromModel = true)
    }
    suspend fun apply(contactId: String, entry: MemoryEntry, stamp: WriteStamp, fromModel: Boolean = false): MemoryOperation = session.transaction {
        checkStamp(session, stamp, contactId)
        require(entry.id.isNotBlank())
        require(ownerOfMemory(entry.id).let { it == null || it == contactId })
        val trusted = if (fromModel) entry.copy(sourceType = MemorySource.ASSISTANT_INFERENCE) else entry
        val before = memories(contactId).map { decode<MemoryEntry>(it.payload) }
        val after = MemoryPolicy.planWrite(trusted, currentConsent(), stamp.consentVersion, contactId, before)
        val previousOps = operations(contactId)
        val order = (previousOps.lastOrNull()?.commitOrder ?: 0) + 1
        // Complete snapshots preserve automatic quota evictions during undo.
        val operation = MemoryOperation(UUID.randomUUID().toString(), contactId, before, after, System.currentTimeMillis())
        replaceMemories(contactId, after)
        put(MemoryOperationRow(operation.id, contactId, order, encode(operation)))
        val retained = MemoryPolicy.retainOperations(previousOps.map { decode<MemoryOperation>(it.payload) } + operation)
        clearOperations(contactId)
        retained.forEachIndexed { index, item -> put(MemoryOperationRow(item.id, contactId, index.toLong(), encode(item))) }
        operation
    }
    suspend fun delete(contactId: String, id: String, stamp: WriteStamp): MemoryOperation = session.transaction {
        checkStamp(session, stamp, contactId)
        val before = memories(contactId).map { decode<MemoryEntry>(it.payload) }
        require(before.any { it.id == id })
        val after = before.filterNot { it.id == id }
        val op = MemoryOperation(UUID.randomUUID().toString(), contactId, before, after, System.currentTimeMillis(), "delete")
        replaceMemories(contactId, after)
        val retained = MemoryPolicy.retainOperations(operations(contactId).map { decode<MemoryOperation>(it.payload) } + op)
        clearOperations(contactId)
        retained.forEachIndexed { index, item -> put(MemoryOperationRow(item.id, contactId, index.toLong(), encode(item))) }
        op
    }
    suspend fun undoLatest(contactId: String, stamp: WriteStamp, operationId: String? = null) = session.transaction {
        checkStamp(session, stamp, contactId)
        val result = MemoryPolicy.undoLatest(memories(contactId).map { decode(it.payload) },
            operations(contactId).map { decode(it.payload) }, operationId)
        // Verify the restored state too; no partial write if a quota is violated.
        require(MemoryPolicy.enforceQuotas(result.entries) == result.entries)
        replaceMemories(contactId, result.entries)
        clearOperations(contactId)
        result.operations.forEachIndexed { index, op -> put(MemoryOperationRow(op.id, contactId, index.toLong(), encode(op))) }
    }
}

private suspend fun JunshiDao.replaceMemories(contactId: String, entries: List<MemoryEntry>) {
    // Snapshot IDs use a global primary key. Validate the entire restoration before deleting
    // anything: an ID freed by this contact may since have been claimed by another contact.
    entries.forEach { entry ->
        require(entry.contactId == contactId) { "Memory snapshot belongs to another contact" }
        require(ownerOfMemory(entry.id).let { it == null || it == contactId }) { "Memory ID belongs to another contact" }
    }
    clearMemories(contactId)
    entries.forEach { put(MemoryRow(it.id, contactId, encode(it))) }
}
